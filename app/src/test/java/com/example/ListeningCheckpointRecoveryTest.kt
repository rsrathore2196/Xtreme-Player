package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.ListeningCheckpointJournal
import com.example.data.repository.ListeningStatsRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListeningCheckpointRecoveryTest {
    private val track = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 120_000, "cover", "signed-secret",
        isrc = "USABC2600001", primaryArtists = listOf("Artist"))
    private fun snapshot(id: String, ms: Long = 60_000) = ListeningSnapshot(id, track,
        mapOf("2026-10-26" to ListeningDay(ms, if (ms >= 60_000) 1 else 0)), ms, true)

    @Test fun pendingOccurrencesReplayAfterRestartAndCommittedJournalReplayDoesNotDuplicate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "recovery-journal.json")
        val journal = ListeningCheckpointJournal(file)
        val snapshots = listOf(snapshot("first"), snapshot("second"))
        journal.write(snapshots)
        assertFalse("The journal must not retain signed audio URLs", file.readText().contains("signed-secret"))
        val db = MusicDatabase.createInMemoryDatabase(context)
        val first = ListeningStatsRepository(context, db, file)
        try {
            withTimeout(5_000) { first.flush() }
            assertEquals(120_000L, first.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(2L, first.summary(ListeningPeriod.LIFETIME).plays)
            assertTrue(journal.read().isEmpty())
        } finally { first.close() }
        // Simulate process termination after Room commit but before journal acknowledgement.
        journal.write(snapshots)
        val restarted = ListeningStatsRepository(context, db, file)
        try {
            withTimeout(5_000) { restarted.flush() }
            assertEquals(120_000L, restarted.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(2, db.listeningEventDao().getActualPlays().size)
            assertTrue(journal.read().isEmpty())
        } finally { restarted.close(); db.close() }
    }

    @Test fun failedDatabaseWriteLeavesADurableCheckpointForTheNextProcess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "failed-journal.json")
        val unavailable = MusicDatabase.createInMemoryDatabase(context)
        unavailable.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_stats BEFORE INSERT ON listening_totals BEGIN SELECT RAISE(ABORT, 'Injected transaction failure'); END")
        val failed = ListeningStatsRepository(context, unavailable, file)
        try {
            failed.record(snapshot("pending", 30_000))
            failed.record(snapshot("pending"))
            try { withTimeout(5_000) { failed.flush() }; fail("The transaction must fail") } catch (_: java.io.IOException) { }
            val pending = ListeningCheckpointJournal(file).read()
            assertEquals(1, pending.size)
            assertEquals(60_000L, pending.single().listenedMs)
        } finally { failed.close() }
        unavailable.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_stats")
        val db = unavailable
        val restarted = ListeningStatsRepository(context, db, file)
        try {
            withTimeout(5_000) { restarted.flush() }
            assertEquals(60_000L, restarted.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(1L, restarted.summary(ListeningPeriod.LIFETIME).plays)
        } finally { restarted.close(); db.close() }
    }

    @Test fun slowRoomAggregationCannotBlockNewSnapshotsFromReachingTheJournal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MusicDatabase.createInMemoryDatabase(context)
        val file = File(context.cacheDir, "slow-room-journal.json")
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }
        withTimeout(5_000) { locked.await() }
        val repository = ListeningStatsRepository(context, db, file)
        try {
            repository.record(snapshot("first"))
            withTimeout(5_000) { repository.flushJournal() }
            repository.record(snapshot("second"))
            withTimeout(5_000) { repository.flushJournal() }
            assertEquals(setOf("first", "second"), ListeningCheckpointJournal(file).read().map { it.occurrence }.toSet())
        } finally {
            repository.close() // Simulate process termination with aggregation still outstanding.
            release.complete(Unit); blocker.join()
        }
        val restarted = ListeningStatsRepository(context, db, file)
        try {
            withTimeout(5_000) { restarted.flush() }
            assertEquals(120_000L, restarted.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(2L, restarted.summary(ListeningPeriod.LIFETIME).plays)
        } finally { restarted.close(); db.close() }
    }

    @Test fun replayingARetiredOccurrenceAfterWatermarkPruningDoesNotDuplicateTotalsOrHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MusicDatabase.createInMemoryDatabase(context)
        val file = File(context.cacheDir, "retired-occurrences.json")
        val repository = ListeningStatsRepository(context, db, file)
        val first = snapshot("retired-first")
        try {
            repository.record(first); withTimeout(5_000) { repository.flush() }
            repeat(40) { index ->
                repository.record(snapshot("later-$index")); withTimeout(5_000) { repository.flush() }
            }
            assertNull(db.listeningStatsDao().checkpoint(first.occurrence))
            // Exercise the suspected stale-journal replay through the actual repository entry point.
            repository.record(first); withTimeout(5_000) { repository.flush() }
            assertEquals(41 * 60_000L, repository.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(41L, repository.summary(ListeningPeriod.LIFETIME).plays)
            assertEquals(41, db.listeningEventDao().getActualPlays().size)
        } finally { repository.close(); db.close() }
    }
}
