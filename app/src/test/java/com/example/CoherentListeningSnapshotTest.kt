package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoherentListeningSnapshotTest {
    @Test fun snapshotPreservesEveryEventFieldDuplicatesAndTimestampTieOrder() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        try {
            val dao = db.listeningEventDao()
            val event = ListeningEventEntity(id = 1, trackId = "repeated-track", artistId = "artist|id",
                artistName = "Artist | Guest कलाकार", album = "Album|Edition", genre = "Pop|Dance", language = "Hindi|English",
                timestamp = 123_456, sessionId = "session|one", playedDurationMs = 64_321, trackDurationMs = 180_123,
                completionRatio = .357f, completed = true, skipped = true, liked = true,
                source = "Source|Name", eventType = "ACTUAL_PLAY")
            dao.insertListeningEvent(event)
            dao.insertListeningEvent(event.copy(id = 2, sessionId = "session|two", playedDurationMs = 12_345))
            dao.insertListeningEvent(event.copy(id = 3, eventType = "STARTED", timestamp = 999_999))
            val snapshot = dao.observeActualPlaySnapshot().first()
            assertEquals(2, snapshot.first().total)
            assertEquals(listOf(2L, 1L), snapshot.map { it.event!!.id })
            assertEquals(dao.getActualPlays(300), snapshot.mapNotNull { it.event })
            assertEquals(event, snapshot.last().event)
            // Metadata delimiters must not turn different field values into an equal snapshot.
            val before = event.copy(artistId = "a|b", artistName = "c")
            dao.insertListeningEvent(before)
            val first = dao.observeActualPlaySnapshot().first()
            val after = before.copy(artistId = "a", artistName = "b|c")
            dao.insertListeningEvent(after)
            val second = dao.observeActualPlaySnapshot().first()
            assertNotEquals(first, second)
            assertEquals(after, second.last().event)
        } finally { db.close() }
    }

    @Test fun unchangedRecentResultsAreSuppressedButCountsAndMilestonesStillInvalidate() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        val raw = Channel<List<ActualPlaySnapshotRow>>(Channel.UNLIMITED)
        val changed = Channel<List<ActualPlaySnapshotRow>>(Channel.UNLIMITED)
        var observer: Job? = null
        suspend fun nextRaw() = withTimeout(5_000) { raw.receive() }
        suspend fun nextChanged() = withTimeout(5_000) { changed.receive() }
        try {
            val dao = db.listeningEventDao()
            repeat(301) { index -> dao.insertListeningEvent(ListeningEventEntity(id = index + 1L,
                trackId = "track-$index", timestamp = index.toLong(), eventType = "ACTUAL_PLAY")) }
            observer = launch(Dispatchers.IO) {
                dao.observeActualPlaySnapshot().onEach { raw.send(it) }
                    .distinctUntilChanged().collect { changed.send(it) }
            }
            val initial = nextRaw()
            assertEquals(initial, nextChanged())
            dao.insertListeningEvent(ListeningEventEntity(id = 1_000, trackId = "not-an-actual-play",
                timestamp = 1_000, eventType = "STARTED"))
            assertEquals("Room still observes table invalidations", initial, nextRaw())
            dao.insertListeningEvent(ListeningEventEntity(id = 1, trackId = "track-0", artistName = "Updated old artist",
                timestamp = 0, eventType = "ACTUAL_PLAY"))
            assertEquals("Metadata outside the 300-row window must not change recent results", initial, nextRaw())
            dao.pruneOldEvents(1)
            val deletion = nextRaw()
            assertEquals(300, deletion.first().total)
            assertEquals(initial.map { it.event }, deletion.map { it.event })
            assertEquals("Count-only changes must still reach Home", deletion, nextChanged())
            val milestone = deletion.first().event!!.copy(playedDurationMs = 40_000, liked = true)
            dao.insertListeningEvent(milestone)
            val updated = nextRaw()
            assertEquals(updated, nextChanged())
            assertEquals(milestone, updated.first().event)
            assertTrue("No duplicate unchanged snapshots may be published", changed.tryReceive().isFailure)
        } finally {
            observer?.cancelAndJoin()
            raw.close(); changed.close()
            db.close()
        }
    }

    @Test fun countAndRecentRowsRemainCoherentThroughInsertMilestoneAndDeletion() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        try {
            val dao = db.listeningEventDao()
            val empty = dao.observeActualPlaySnapshot().first()
            assertEquals(0, empty.single().total)
            assertNull(empty.single().event)
            // Concurrent invalidations may coalesce, but no emission may pair a
            // new count with an old window (the former combine-of-two-flows bug).
            val observer = async(Dispatchers.IO) {
                dao.observeActualPlaySnapshot().takeWhile { it.first().total < 305 }.collect { rows ->
                    val total = rows.first().total
                    assertTrue(rows.all { it.total == total })
                    assertEquals(minOf(total, 300), rows.count { it.event != null })
                    val events = rows.mapNotNull { it.event }
                    assertEquals(events.sortedWith(compareByDescending<ListeningEventEntity> { it.timestamp }
                        .thenByDescending { it.id }), events)
                }
            }
            repeat(305) { index ->
                dao.insertListeningEvent(ListeningEventEntity(id = index + 1L, trackId = "track-$index",
                    timestamp = index.toLong(), sessionId = "session-$index", eventType = "ACTUAL_PLAY"))
            }
            withTimeout(10_000) { observer.await() }
            val before = dao.observeActualPlaySnapshot().first()
            assertEquals(305, before.first().total)
            assertEquals(300, before.size)
            val played = dao.getActualPlays(1).single()
            dao.insertListeningEvent(played.copy(playedDurationMs = 30_000, completionRatio = .5f))
            val milestone = dao.observeActualPlaySnapshot().first()
            assertEquals(before.map { it.event!!.id }, milestone.map { it.event!!.id })
            assertEquals(305, milestone.first().total)
            assertEquals(30_000L, milestone.first().event!!.playedDurationMs)
            dao.pruneOldEvents(10L)
            val deleted = dao.observeActualPlaySnapshot().first()
            assertEquals(295, deleted.first().total)
            assertEquals(295, deleted.size)
        } finally { db.close() }
    }

    @Test fun historyCountAndWindowUseTheCompositeIndex() {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        try {
            for (sql in listOf("SELECT COUNT(*) FROM listening_events WHERE eventType='ACTUAL_PLAY'",
                "SELECT id,timestamp FROM listening_events WHERE eventType='ACTUAL_PLAY' ORDER BY timestamp DESC,id DESC LIMIT 300")) {
                db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                    val details = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString()
                    assertTrue(details, details.contains("index_listening_events_eventType_timestamp_id"))
                    assertFalse(details, details.contains("TEMP B-TREE"))
                }
            }
        } finally { db.close() }
    }
}
