package com.example.data.local

import androidx.room.*
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** Daily rows retain week boundaries; monthly rows make long-range summaries inexpensive. */
@Entity(tableName = "listening_totals", primaryKeys = ["origin", "bucket", "kind", "identity"], indices = [Index(value = ["kind", "bucket"]), Index(value = ["kind", "identity"])])
data class ListeningTotal(
    val origin: String, val bucket: String, val kind: String, val identity: String,
    val trackId: String, val title: String, val artist: String, val artistId: String,
    val coverUrl: String, val durationMs: Long, val isrc: String,
    val listenedMs: Long, val plays: Long
) {
    fun track() = MusicTrack(trackId, title, artist, "", durationMs, coverUrl, "", bitrateKbps = 0, qualityBadge = "",
        source = if (trackId.startsWith("yt_")) "YouTube Music" else "", isrc = isrc)
}
@Entity(tableName = "listening_checkpoint")
data class ListeningCheckpoint(@PrimaryKey val occurrence: String, val days: String, val eventId: Long)

/** Contribution already folded from a catalog identity into an ISRC, retained across backup imports. */
@Entity(tableName = "listening_identity_migrations", primaryKeys = ["origin", "bucket", "catalogIdentity"])
data class ListeningIdentityMigration(val origin: String, val bucket: String, val catalogIdentity: String,
    val canonicalIdentity: String, val listenedMs: Long, val plays: Long)

@Dao
interface ListeningStatsDao {
    @Query("SELECT '' AS origin, '' AS bucket, kind, identity, trackId, title, artist, artistId, coverUrl, durationMs, isrc, SUM(listenedMs) AS listenedMs, SUM(plays) AS plays FROM listening_totals WHERE kind = :kind AND length(bucket) = :length AND bucket >= :start AND bucket < :end GROUP BY identity")
    suspend fun range(kind: String, length: Int, start: String, end: String): List<ListeningTotal>
    @Query("SELECT * FROM listening_totals") suspend fun all(): List<ListeningTotal>
    @Query("SELECT * FROM listening_totals WHERE kind='song' AND identity=:identity")
    suspend fun songRows(identity: String): List<ListeningTotal>
    @Query("DELETE FROM listening_totals WHERE origin=:origin AND bucket=:bucket AND kind='song' AND identity=:identity")
    suspend fun deleteSongRow(origin: String, bucket: String, identity: String)
    @Query("SELECT * FROM listening_totals WHERE origin=:origin AND bucket=:bucket AND kind=:kind AND identity=:identity")
    suspend fun row(origin: String, bucket: String, kind: String, identity: String): ListeningTotal?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: ListeningTotal)
    @Query("SELECT * FROM listening_checkpoint WHERE occurrence=:occurrence") suspend fun checkpoint(occurrence: String): ListeningCheckpoint?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun checkpoint(row: ListeningCheckpoint)
    // Pending journal entries must retain their watermarks until the journal's acknowledgement is durable.
    @Query("DELETE FROM listening_checkpoint WHERE occurrence NOT IN (:pending) AND rowid NOT IN (SELECT rowid FROM listening_checkpoint ORDER BY rowid DESC LIMIT 32)")
    suspend fun pruneCheckpoints(pending: List<String>)
    @Query("SELECT * FROM listening_identity_migrations") suspend fun identityMigrations(): List<ListeningIdentityMigration>
    @Query("SELECT * FROM listening_identity_migrations WHERE origin=:origin AND bucket=:bucket AND catalogIdentity=:catalog")
    suspend fun identityMigration(origin: String, bucket: String, catalog: String): ListeningIdentityMigration?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMigration(row: ListeningIdentityMigration)
}

/** Caller owns the Room transaction. Apply only a contribution not previously included in its ISRC total. */
internal suspend fun ListeningStatsDao.migrateSongIdentity(row: ListeningTotal, canonical: String): Boolean {
    val previous = identityMigration(row.origin, row.bucket, row.identity)
    if (previous != null && previous.canonicalIdentity != canonical) return false // Conflicting ISRC evidence stays separate.
    val target = this.row(row.origin, row.bucket, "song", canonical)
    val millis = (row.listenedMs - (previous?.listenedMs ?: 0L)).coerceAtLeast(0L)
    val plays = (row.plays - (previous?.plays ?: 0L)).coerceAtLeast(0L)
    if (target != null || millis > 0L || plays > 0L) put((target ?: row).copy(identity = canonical,
        isrc = canonical.removePrefix("isrc:"), listenedMs = (target?.listenedMs ?: 0L) + millis,
        plays = (target?.plays ?: 0L) + plays))
    putMigration(ListeningIdentityMigration(row.origin, row.bucket, row.identity, canonical,
        maxOf(row.listenedMs, previous?.listenedMs ?: 0L), maxOf(row.plays, previous?.plays ?: 0L)))
    deleteSongRow(row.origin, row.bucket, row.identity)
    return true
}

enum class ListeningPeriod(val label: String) {
    WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly"), LIFETIME("Lifetime");
    fun bounds(today: LocalDate): Pair<String, String> = when (this) {
        WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it.toString() to it.plusWeeks(1).toString() }
        MONTHLY -> YearMonth.from(today).let { it.toString() to it.plusMonths(1).toString() }
        YEARLY -> "${today.year}-01" to "${today.year + 1}-01"
        LIFETIME -> "0000-01" to "9999-12"
    }
}
data class ListeningDay(val millis: Long = 0, val plays: Long = 0)
data class ListeningSnapshot(val occurrence: String, val track: MusicTrack, val days: Map<String, ListeningDay>,
    val listenedMs: Long, val finished: Boolean, val playing: Boolean = false)

/** Driven solely by the service's existing callbacks/checkpoints. No timer, I/O or song-position clock. */
class ListeningClock(private val emit: (ListeningSnapshot) -> Unit,
    private val onOccurrence: (MusicTrack?, String) -> Unit = { _, _ -> }) {
    private var track: MusicTrack? = null
    private var occurrence = ""
    private var running = false
    private var lastElapsed = 0L
    private var lastWall = 0L
    private var listened = 0L
    private var qualified = false
    private val days = linkedMapOf<String, ListeningDay>()
    private var saved = -1L
    private var savedPlaying = false

    fun update(next: MusicTrack?, playing: Boolean, elapsed: Long, wall: Long,
        zone: ZoneId = ZoneId.systemDefault(), newOccurrence: Boolean = false, advancing: Boolean = true) {
        val previous = track
        if (running && previous != null && advancing) {
            // The existing service checkpoints every 15s. A delayed/stalled callback
            // cannot attribute an unbounded interval to audio that might have stopped.
            val delta = (elapsed - lastElapsed).coerceIn(0, 30_000)
            val threshold = threshold(previous.durationMs)
            val crossing = if (!qualified && listened + delta >= threshold) (threshold - listened).coerceAtLeast(0) else null
            var start = lastWall
            val end = start + delta
            while (start < end) {
                val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val boundary = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val stop = minOf(end, boundary)
                val key = date.toString()
                val old = days[key] ?: ListeningDay()
                days[key] = old.copy(millis = old.millis + stop - start)
                start = stop
            }
            crossing?.let {
                val date = Instant.ofEpochMilli(lastWall + it - if (it > 0) 1 else 0).atZone(zone).toLocalDate().toString()
                val old = days[date] ?: ListeningDay()
                days[date] = old.copy(plays = old.plays + 1)
                qualified = true
            }
            listened += delta
        }
        val changed = previous?.id != next?.id || newOccurrence
        if (changed) {
            flush(true)
            track = next; occurrence = UUID.randomUUID().toString()
            days.clear(); listened = 0; saved = -1; qualified = false; savedPlaying = false
            onOccurrence(next, occurrence)
        } else track = next
        lastElapsed = elapsed; lastWall = wall; running = playing && next != null
        flush(false)
    }
    private fun flush(finished: Boolean) {
        val current = track ?: return
        if (listened > 0 && (saved != listened || savedPlaying != running || finished)) {
            emit(ListeningSnapshot(occurrence, current, days.toMap(), listened, finished, running))
            saved = listened; savedPlaying = running
        }
    }
    companion object {
        fun threshold(duration: Long): Long = if (duration <= 0) 30_000 else maxOf(30_000, minOf(duration / 2, 240_000))
        fun songKey(track: MusicTrack): String = RecordingIdentity.isrc(track.isrc)?.let { "isrc:$it" } ?: "catalog:${track.id}"
    }
}

/** Shared in-process identity; service callbacks publish before controller playback events. */
object ListeningSessions {
    private data class Active(val trackId: String, val occurrence: String)
    @Volatile private var active: Active? = null
    fun publish(track: MusicTrack?, occurrence: String) { active = track?.let { Active(it.id, occurrence) } }
    fun forTrack(trackId: String): String? = active?.takeIf { it.trackId == trackId }?.occurrence
}

/** Optional backup section: original per-installation counters make repeated imports idempotent. */
object ListeningStatsBackup {
    fun encodeMigrations(rows: List<ListeningIdentityMigration>) = org.json.JSONArray(rows.map { row ->
        org.json.JSONObject().put("origin", row.origin).put("bucket", row.bucket).put("catalog", row.catalogIdentity)
            .put("canonical", row.canonicalIdentity).put("listenedMs", row.listenedMs).put("plays", row.plays)
    })
    fun decodeMigrations(array: org.json.JSONArray): List<ListeningIdentityMigration> {
        require(array.length() <= 500_000) { "Listening identities are too large" }
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val origin = row.getString("origin").also { require(it.isNotBlank() && it.length <= 8192) }
            val bucket = row.getString("bucket")
            if (bucket.length == 7) YearMonth.parse(bucket) else { require(bucket.length == 10); LocalDate.parse(bucket) }
            val catalog = row.getString("catalog").also { require(it.startsWith("catalog:") && it.length in 9..8192) }
            val canonical = row.getString("canonical").also { require(it.startsWith("isrc:") &&
                RecordingIdentity.isrc(it.removePrefix("isrc:")) == it.removePrefix("isrc:")) }
            val millis = row.getLong("listenedMs").also { require(it in 0..1_000_000_000_000L) }
            val plays = row.getLong("plays").also { require(it in 0..1_000_000_000_000L) }
            ListeningIdentityMigration(origin, bucket, catalog, canonical, millis, plays)
        }.also { rows -> require(rows.map { listOf(it.origin, it.bucket, it.catalogIdentity) }.distinct().size == rows.size) }
    }
    fun encode(rows: List<ListeningTotal>) = org.json.JSONArray().apply {
        rows.forEach { r -> put(org.json.JSONObject().put("origin", r.origin).put("bucket", r.bucket)
            .put("kind", r.kind).put("identity", r.identity).put("trackId", r.trackId).put("title", r.title)
            .put("artist", r.artist).put("artistId", r.artistId).put("coverUrl", r.coverUrl)
            .put("durationMs", r.durationMs).put("isrc", r.isrc).put("listenedMs", r.listenedMs).put("plays", r.plays)) }
    }
    fun decode(array: org.json.JSONArray): List<ListeningTotal> {
        require(array.length() <= 500_000) { "Listening records are too large" }
        return (0 until array.length()).map { index ->
            val r = array.getJSONObject(index)
            fun text(key: String) = r.getString(key).also { require(it.length <= 8192) }
            fun number(key: String) = r.getLong(key).also { require(it in 0..1_000_000_000_000L) }
            val bucket = text("bucket")
            require(bucket.length == 7 || bucket.length == 10)
            if (bucket.length == 7) YearMonth.parse(bucket) else LocalDate.parse(bucket)
            val kind = text("kind").also { require(it == "song" || it == "artist") }
            ListeningTotal(text("origin").also { require(it.isNotBlank()) }, bucket, kind,
                text("identity").also { require(it.isNotBlank()) }, text("trackId"), text("title"), text("artist"),
                text("artistId"), text("coverUrl"), number("durationMs"), text("isrc"), number("listenedMs"), number("plays"))
        }.also { rows -> require(rows.map { listOf(it.origin, it.bucket, it.kind, it.identity) }.distinct().size == rows.size) }
    }
    suspend fun merge(dao: ListeningStatsDao, rows: List<ListeningTotal>, migrations: List<ListeningIdentityMigration> = emptyList()) {
        for (migration in migrations) {
            val old = dao.identityMigration(migration.origin, migration.bucket, migration.catalogIdentity)
            require(old == null || old.canonicalIdentity == migration.canonicalIdentity) { "Conflicting listening recording identities" }
            dao.putMigration(migration.copy(listenedMs = maxOf(migration.listenedMs, old?.listenedMs ?: 0L),
                plays = maxOf(migration.plays, old?.plays ?: 0L)))
        }
        for (row in rows) {
            val old = dao.row(row.origin, row.bucket, row.kind, row.identity)
            dao.put(row.copy(listenedMs = maxOf(row.listenedMs, old?.listenedMs ?: 0), plays = maxOf(row.plays, old?.plays ?: 0)))
        }
        // Merge canonical counters first; then only the unmigrated part of an
        // older/local catalog row can add to those counters, in either import order.
        for (migration in dao.identityMigrations()) {
            require(dao.row(migration.origin, migration.bucket, "song", migration.canonicalIdentity) != null) {
                "Listening identity is missing its canonical total"
            }
            val row = dao.row(migration.origin, migration.bucket, "song", migration.catalogIdentity) ?: continue
            dao.migrateSongIdentity(row, migration.canonicalIdentity)
        }
    }
}
