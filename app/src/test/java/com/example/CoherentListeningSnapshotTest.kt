package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoherentListeningSnapshotTest {
    @Test fun countAndRecentRowsRemainCoherentThroughInsertMilestoneAndDeletion() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        try {
            val dao = db.listeningEventDao()
            val empty = dao.observeActualPlaySnapshot().first()
            assertEquals(0, empty.single().total)
            assertNull(empty.single().stamp)
            // Concurrent invalidations may coalesce, but no emission may pair a
            // new count with an old window (the former combine-of-two-flows bug).
            val observer = async(Dispatchers.IO) {
                dao.observeActualPlaySnapshot().takeWhile { it.first().total < 305 }.collect { rows ->
                    val total = rows.first().total
                    assertTrue(rows.all { it.total == total })
                    assertEquals(minOf(total, 300), rows.count { it.stamp != null })
                    val stamps = rows.mapNotNull { it.stamp }
                    assertEquals(stamps.sortedWith(compareByDescending<ActualPlayStamp> { it.timestamp }
                        .thenByDescending { it.id }), stamps)
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
            assertEquals(before.map { it.stamp!!.id }, milestone.map { it.stamp!!.id })
            assertEquals(305, milestone.first().total)
            assertEquals(30_000L, milestone.first().stamp!!.playedDurationMs)
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
