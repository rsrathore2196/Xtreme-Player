package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

/** A cheap cache-validity token, changed in the same transaction as its source data. */
@Entity(tableName = "home_input_revision")
data class HomeInputRevision(@PrimaryKey val id: Int, val epoch: String, val generation: Long,
    val replacingTrack: String? = null, val replacingEvent: Long? = null)

internal object HomeInputRevisionTriggers {
    fun install(db: SupportSQLiteDatabase) {
        // SELECT avoids a conflict altogether: an outer INSERT OR REPLACE overrides
        // the conflict policy of INSERT OR IGNORE statements inside its triggers.
        val seed = "INSERT INTO home_input_revision (id, epoch, generation) SELECT 1, hex(randomblob(16)), 0 WHERE NOT EXISTS (SELECT 1 FROM home_input_revision WHERE id = 1);"
        db.execSQL(seed)
        val advance = "$seed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 1;"
        fun trigger(name: String, operation: String, table: String, condition: String, body: String = advance) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS $name $operation ON $table WHEN $condition BEGIN $body END")
        }
        // Transaction-local replacement markers distinguish SQLite's implicit DELETE
        // from a real deletion, including when recursive triggers are enabled.
        // INSERT OR REPLACE also passes the INSERT trigger. Milestones and restores cannot bypass it.
        val eventFields = listOf("trackId", "artistId", "artistName", "album", "genre", "language", "timestamp",
            "sessionId", "playedDurationMs", "trackDurationMs", "completionRatio", "completed", "skipped", "liked", "source", "eventType")
        val sameEvent = eventFields.joinToString(" AND ") { "e.$it IS NEW.$it" }
        val relevantEvent = "NEW.eventType = 'ACTUAL_PLAY' OR EXISTS (SELECT 1 FROM listening_events WHERE id = NEW.id AND eventType = 'ACTUAL_PLAY')"
        trigger("home_history_insert", "BEFORE INSERT", "listening_events", relevantEvent,
            "$seed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 1 AND NOT EXISTS " +
                "(SELECT 1 FROM listening_events e WHERE e.id = NEW.id AND $sameEvent); " +
                "UPDATE home_input_revision SET replacingEvent = NEW.id WHERE id = 1;")
        trigger("home_history_insert_end", "AFTER INSERT", "listening_events",
            "NEW.eventType = 'ACTUAL_PLAY' OR EXISTS (SELECT 1 FROM home_input_revision WHERE replacingEvent = NEW.id)",
            "UPDATE home_input_revision SET replacingEvent = NULL WHERE id = 1;")
        trigger("home_history_update", "AFTER UPDATE", "listening_events",
            "(OLD.eventType = 'ACTUAL_PLAY' OR NEW.eventType = 'ACTUAL_PLAY') AND (${eventFields.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }})")
        trigger("home_history_delete", "AFTER DELETE", "listening_events",
            "OLD.eventType = 'ACTUAL_PLAY' AND NOT EXISTS (SELECT 1 FROM home_input_revision WHERE replacingEvent = OLD.id)")
        val fields = listOf("title", "artist", "album", "durationMs", "coverUrl", "genre", "isLiked",
            "singers", "writer", "language", "year", "source", "isrc", "primaryArtists")
        val relevant = "NEW.isLiked = 1 OR EXISTS (SELECT 1 FROM listening_events WHERE trackId = NEW.id AND eventType = 'ACTUAL_PLAY')"
        val same = fields.joinToString(" AND ") { "t.$it IS NEW.$it" }
        // Inspect the old row BEFORE replacement; URL/quality/cache bookkeeping is not taste input.
        trigger("home_track_insert", "BEFORE INSERT", "tracks",
            "$relevant OR EXISTS (SELECT 1 FROM tracks WHERE id = NEW.id AND isLiked = 1)",
            "$seed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 1 AND NOT EXISTS " +
                "(SELECT 1 FROM tracks t WHERE t.id = NEW.id AND $same); " +
                "UPDATE home_input_revision SET replacingTrack = NEW.id WHERE id = 1;")
        trigger("home_track_insert_end", "AFTER INSERT", "tracks",
            "EXISTS (SELECT 1 FROM home_input_revision WHERE replacingTrack = NEW.id)",
            "UPDATE home_input_revision SET replacingTrack = NULL WHERE id = 1;")
        trigger("home_track_update", "AFTER UPDATE", "tracks",
            "($relevant OR OLD.isLiked = 1) AND (${fields.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }})")
        trigger("home_track_delete", "AFTER DELETE", "tracks",
            "(OLD.isLiked = 1 OR EXISTS (SELECT 1 FROM listening_events WHERE trackId = OLD.id AND eventType = 'ACTUAL_PLAY')) " +
                "AND NOT EXISTS (SELECT 1 FROM home_input_revision WHERE replacingTrack = OLD.id)")

        // Row 2 shares this internal table/schema, but excludes duration-only
        // checkpoints and feedback. Row 1 remains the complete history revision.
        val artistSeed = "INSERT INTO home_input_revision (id, epoch, generation) SELECT 2, hex(randomblob(16)), 0 WHERE NOT EXISTS (SELECT 1 FROM home_input_revision WHERE id = 2);"
        db.execSQL(artistSeed)
        val artistAdvance = "$artistSeed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 2;"
        val artistEventFields = listOf("trackId", "artistId", "artistName", "timestamp", "sessionId", "eventType")
        val sameArtistEvent = artistEventFields.joinToString(" AND ") { "e.$it IS NEW.$it" } +
            " AND (e.playedDurationMs > 0) IS (NEW.playedDurationMs > 0)"
        val actualNew = "NEW.eventType = 'ACTUAL_PLAY' AND NEW.playedDurationMs > 0"
        val actualOld = "OLD.eventType = 'ACTUAL_PLAY' AND OLD.playedDurationMs > 0"
        val relevantArtistEvent = "($actualNew) OR EXISTS (SELECT 1 FROM listening_events WHERE id = NEW.id AND eventType = 'ACTUAL_PLAY' AND playedDurationMs > 0)"
        trigger("home_artist_history_insert", "BEFORE INSERT", "listening_events", relevantArtistEvent,
            "$artistSeed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 2 AND NOT EXISTS " +
                "(SELECT 1 FROM listening_events e WHERE e.id = NEW.id AND $sameArtistEvent); " +
                "UPDATE home_input_revision SET replacingEvent = NEW.id WHERE id = 2;")
        trigger("home_artist_history_insert_end", "AFTER INSERT", "listening_events",
            "($actualNew) OR EXISTS (SELECT 1 FROM home_input_revision WHERE id = 2 AND replacingEvent = NEW.id)",
            "UPDATE home_input_revision SET replacingEvent = NULL WHERE id = 2;")
        trigger("home_artist_history_update", "AFTER UPDATE", "listening_events",
            "(($actualOld) OR ($actualNew)) AND (${artistEventFields.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }} " +
                "OR (OLD.playedDurationMs > 0) IS NOT (NEW.playedDurationMs > 0))", artistAdvance)
        trigger("home_artist_history_delete", "AFTER DELETE", "listening_events",
            "($actualOld) AND NOT EXISTS (SELECT 1 FROM home_input_revision WHERE id = 2 AND replacingEvent = OLD.id)", artistAdvance)
        val artistTrackRelevant = "EXISTS (SELECT 1 FROM listening_events WHERE trackId = NEW.id AND eventType = 'ACTUAL_PLAY' AND playedDurationMs > 0)"
        val artistFields = listOf("artist", "singers", "writer", "primaryArtists")
        val sameArtist = artistFields.joinToString(" AND ") { "t.$it IS NEW.$it" }
        // Retire the broad old definitions once. Versioned names keep later
        // opens idempotent instead of rewriting SQLite's schema every startup.
        db.execSQL("DROP TRIGGER IF EXISTS home_artist_track_insert")
        db.execSQL("DROP TRIGGER IF EXISTS home_artist_track_update")
        trigger("home_artist_track_insert_v2", "BEFORE INSERT", "tracks", artistTrackRelevant,
            "$artistSeed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 2 AND NOT EXISTS " +
                "(SELECT 1 FROM tracks t WHERE t.id = NEW.id AND $sameArtist); " +
                "UPDATE home_input_revision SET replacingTrack = NEW.id WHERE id = 2;")
        trigger("home_artist_track_insert_end", "AFTER INSERT", "tracks",
            "EXISTS (SELECT 1 FROM home_input_revision WHERE id = 2 AND replacingTrack = NEW.id)",
            "UPDATE home_input_revision SET replacingTrack = NULL WHERE id = 2;")
        trigger("home_artist_track_update_v2", "AFTER UPDATE", "tracks",
            "$artistTrackRelevant AND (${artistFields.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }})", artistAdvance)
        trigger("home_artist_track_delete", "AFTER DELETE", "tracks",
            "EXISTS (SELECT 1 FROM listening_events WHERE trackId = OLD.id AND eventType = 'ACTUAL_PLAY' AND playedDurationMs > 0) " +
                "AND NOT EXISTS (SELECT 1 FROM home_input_revision WHERE id = 2 AND replacingTrack = OLD.id)", artistAdvance)

        // Track metadata freshness is independent of playback counts and credits.
        // This internal row needs no schema change or destructive data migration.
        val metadataSeed = "INSERT INTO home_input_revision (id, epoch, generation) SELECT 3, hex(randomblob(16)), 0 WHERE NOT EXISTS (SELECT 1 FROM home_input_revision WHERE id = 3);"
        db.execSQL(metadataSeed)
        val metadataAdvance = "$metadataSeed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 3;"
        val metadataFields = fields + listOf("audioUrl", "bitrateKbps", "qualityBadge", "isCached")
        val sameMetadata = metadataFields.joinToString(" AND ") { "t.$it IS NEW.$it" }
        val metadataRelevant = "EXISTS (SELECT 1 FROM listening_events WHERE trackId = NEW.id AND eventType = 'ACTUAL_PLAY')"
        trigger("home_metadata_track_insert", "BEFORE INSERT", "tracks", metadataRelevant,
            "$metadataSeed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 3 AND NOT EXISTS " +
                "(SELECT 1 FROM tracks t WHERE t.id = NEW.id AND $sameMetadata); " +
                "UPDATE home_input_revision SET replacingTrack = NEW.id WHERE id = 3;")
        trigger("home_metadata_track_insert_end", "AFTER INSERT", "tracks",
            "EXISTS (SELECT 1 FROM home_input_revision WHERE id = 3 AND replacingTrack = NEW.id)",
            "UPDATE home_input_revision SET replacingTrack = NULL WHERE id = 3;")
        trigger("home_metadata_track_update", "AFTER UPDATE", "tracks",
            "$metadataRelevant AND (${metadataFields.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }})", metadataAdvance)
        trigger("home_metadata_track_delete", "AFTER DELETE", "tracks",
            "EXISTS (SELECT 1 FROM listening_events WHERE trackId = OLD.id AND eventType = 'ACTUAL_PLAY') " +
                "AND NOT EXISTS (SELECT 1 FROM home_input_revision WHERE id = 3 AND replacingTrack = OLD.id)", metadataAdvance)

        // Home restores metadata only for its 300-event window. Older changes
        // still refresh artist representatives via row 3, without rereading all
        // current Recently Played tracks. Uses the existing event/order index.
        val recentSeed = "INSERT INTO home_input_revision (id, epoch, generation) SELECT 4, hex(randomblob(16)), 0 WHERE NOT EXISTS (SELECT 1 FROM home_input_revision WHERE id = 4);"
        db.execSQL(recentSeed)
        val recentAdvance = "$recentSeed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 4;"
        val recentIds = "SELECT trackId FROM listening_events WHERE eventType = 'ACTUAL_PLAY' ORDER BY timestamp DESC, id DESC LIMIT 300"
        trigger("home_recent_metadata_insert", "BEFORE INSERT", "tracks", "NEW.id IN ($recentIds)",
            "$recentSeed UPDATE home_input_revision SET generation = generation + 1 WHERE id = 4 AND NOT EXISTS " +
                "(SELECT 1 FROM tracks t WHERE t.id = NEW.id AND $sameMetadata); " +
                "UPDATE home_input_revision SET replacingTrack = NEW.id WHERE id = 4;")
        trigger("home_recent_metadata_insert_end", "AFTER INSERT", "tracks",
            "EXISTS (SELECT 1 FROM home_input_revision WHERE id = 4 AND replacingTrack = NEW.id)",
            "UPDATE home_input_revision SET replacingTrack = NULL WHERE id = 4;")
        trigger("home_recent_metadata_update", "AFTER UPDATE", "tracks",
            "NEW.id IN ($recentIds) AND (${metadataFields.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }})", recentAdvance)
        trigger("home_recent_metadata_delete", "AFTER DELETE", "tracks",
            "OLD.id IN ($recentIds) AND NOT EXISTS (SELECT 1 FROM home_input_revision WHERE id = 4 AND replacingTrack = OLD.id)", recentAdvance)
    }
}
