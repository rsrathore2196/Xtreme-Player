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
    }
}
