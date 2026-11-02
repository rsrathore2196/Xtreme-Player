package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.ListeningCheckpointJournal
import com.example.data.repository.ListeningStatsRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListeningBackupBarrierTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val track = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 120_000, "cover", "")
    private fun snapshot(ms: Long = 60_000, occurrence: String = "occurrence", song: MusicTrack = track) =
        ListeningSnapshot(occurrence, song, mapOf("2026-10-28" to ListeningDay(ms, 1)), ms, false, true)
    private suspend fun backup(db: MusicDatabase): String {
        val folder = File(context.filesDir, "barrier-backups")
        BackupRestoreManager.setAutoBackupLocation(context, folder.path)
        return File(withTimeout(10_000) { BackupRestoreManager.createBackup(context, db).getOrThrow() }.filePath).readText()
    }

    @Test fun backupReplaysTheDurableJournalWithoutAStatsSingletonOrAnotherDatabase() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(context)
        val file = File(context.noBackupFilesDir, "listening_pending_v1.json")
        ListeningCheckpointJournal(file).write(listOf(snapshot()))
        val unrelated = context.getDatabasePath("xtreme_music_db").exists()
        try {
            val json = JSONObject(backup(db))
            assertEquals(6, json.getInt("schemaVersion"))
            val totals = ListeningStatsBackup.decode(json.getJSONArray("listeningStats"))
            assertEquals(60_000L, totals.single { it.bucket == "2026-10" && it.kind == "song" }.listenedMs)
            val history = ListeningHistoryBackup.decode(json.getJSONObject("listeningHistory"))
            assertEquals(1, history.events.size)
            assertEquals("occurrence", history.events.single().sessionId)
            assertEquals(history.events.single().id, history.checkpoints.single().eventId)
            assertTrue(ListeningCheckpointJournal(file).read().isEmpty())
            assertEquals("The supplied database is the only backup database", unrelated, context.getDatabasePath("xtreme_music_db").exists())
        } finally { db.close(); file.delete() }
    }

    @Test fun activeCheckpointsAreCapturedConsistentlyAndRepeatedMergePreservesHistoryAndObservers() = runBlocking {
        val source = MusicDatabase.createInMemoryDatabase(context)
        val target = MusicDatabase.createInMemoryDatabase(context)
        val writer = ListeningStatsRepository(context, source, File(context.cacheDir, "active-source.json"))
        val reader = ListeningStatsRepository(context, target, File(context.cacheDir, "active-target.json"))
        try {
            writer.record(snapshot(90_000)) // No caller flush: backup owns the barrier.
            val body = backup(source)
            context.getSharedPreferences("listening_stats", Context.MODE_PRIVATE).edit().putString("origin", "target-install").commit()
            val local = track.copy(id = "local", title = "Existing Song")
            reader.record(snapshot(60_000, "local-occurrence", local)); reader.flush()
            assertEquals(60_000L, reader.summary(ListeningPeriod.LIFETIME).millis)
            val initial = CompletableDeferred<Unit>()
            val observed = async(Dispatchers.IO) {
                reader.observe(ListeningPeriod.LIFETIME).onEach { initial.complete(Unit) }.take(2).toList()
            }
            withTimeout(5_000) { initial.await() }
            val result = BackupRestoreManager.restoreBackup(context, target, rawJson = body)
            assertTrue(result.message, result.success)
            assertTrue(result.listeningStatsPresent)
            assertEquals(4, result.restoredListeningRowsCount)
            assertEquals(1, result.restoredListeningEventsCount)
            assertEquals(150_000L, reader.summary(ListeningPeriod.LIFETIME).millis)
            withTimeout(5_000) { assertEquals(150_000L, observed.await().last().millis) }
            val second = BackupRestoreManager.restoreBackup(context, target, rawJson = body)
            assertTrue(second.message, second.success); assertEquals(0, second.restoredListeningEventsCount)
            assertEquals(2, target.listeningEventDao().allForBackup().size)
            assertEquals(2L, reader.summary(ListeningPeriod.LIFETIME).plays)
            for (period in ListeningPeriod.entries) {
                val summary = reader.summary(period, LocalDate.parse("2026-10-28"))
                assertEquals(period.name, 150_000L, summary.millis)
                assertEquals(2L, summary.plays)
                assertEquals(2, summary.songs.size)
            }
            val event = target.listeningEventDao().getActualPlayForOccurrence("occurrence")!!
            assertNotEquals("Local numeric IDs must not overwrite another occurrence", 1L, event.id)
            assertEquals(event.id, target.listeningStatsDao().checkpoint("occurrence")!!.eventId)
            reader.record(snapshot(90_000)); reader.flush() // Imported counters must not replay.
            assertEquals(150_000L, reader.summary(ListeningPeriod.LIFETIME).millis)
            assertNotNull(target.musicDao().getTrackById(track.id))
        } finally { writer.close(); reader.close(); source.close(); target.close() }
    }

    @Test fun failedBackupPreparationRetainsDurablePendingData() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(context)
        val file = File(context.noBackupFilesDir, "listening_pending_v1.json")
        ListeningCheckpointJournal(file).write(listOf(snapshot()))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_backup_stats BEFORE INSERT ON listening_totals BEGIN SELECT RAISE(ABORT, 'Injected failure'); END")
        try {
            assertTrue(withTimeout(10_000) { BackupRestoreManager.createBackup(context, db) }.isFailure)
            assertEquals(60_000L, ListeningCheckpointJournal(file).read().single().listenedMs)
            assertTrue(db.listeningStatsDao().all().isEmpty())
        } finally { db.close(); file.delete() }
    }

    @Test fun oldBackupsReportMissingStatsAndAggregateOnlyBackupsDoNotInventEvents() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(context)
        try {
            val old = JSONObject().put("schemaVersion", 2).put("libraryTracks", JSONArray().put(JSONObject().put("id", "old")))
            val missing = BackupRestoreManager.restoreBackup(context, db, rawJson = old.toString())
            assertTrue(missing.success); assertFalse(missing.listeningStatsPresent)
            assertTrue(missing.message.contains("does not contain listening statistics"))
            val total = ListeningTotal("old-device", "2026-10", "song", "catalog:old", "old", "Old Song", "Artist", "", "", 120_000, "", 60_000, 1)
            old.put("schemaVersion", 5).put("listeningStats", ListeningStatsBackup.encode(listOf(total)))
            repeat(2) { assertTrue(BackupRestoreManager.restoreBackup(context, db, rawJson = old.toString()).success) }
            assertEquals(60_000L, db.listeningStatsDao().all().single().listenedMs)
            assertTrue(db.listeningEventDao().allForBackup().isEmpty())
        } finally { db.close() }
    }

    @Test fun concurrentPlaybackCannotMixCountersAndWatermarksFromDifferentSnapshotCuts() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(context)
        val writer = ListeningStatsRepository(context, db, File(context.cacheDir, "concurrent-backup.json"))
        try {
            writer.record(snapshot()); writer.flush()
            val checkpoints = launch(Dispatchers.IO) { repeat(30) { writer.record(snapshot(61_000L + it * 1_000)); yield() } }
            val json = JSONObject(backup(db))
            checkpoints.join(); writer.flush()
            val total = ListeningStatsBackup.decode(json.getJSONArray("listeningStats")).single { it.kind == "song" && it.bucket == "2026-10" }
            val history = ListeningHistoryBackup.decode(json.getJSONObject("listeningHistory"))
            val days = ListeningStatsRepository.decodeDays(history.checkpoints.single().days)
            assertEquals(total.listenedMs, days.values.sumOf { it.millis })
            assertEquals(1, history.events.size)
        } finally { writer.close(); db.close() }
    }

    @Test fun overlappingBackupBarriersShareTheWriterUntilBothConsumersFinish() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(context)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val first = async(Dispatchers.IO) { ListeningStatsRepository.withPreparedDatabase(context, db) {
                entered.complete(Unit); release.await()
            } }
            withTimeout(5_000) { entered.await() }
            val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                ListeningStatsRepository.withPreparedDatabase(context, db) { db.listeningStatsDao().all() }
            }
            release.complete(Unit)
            withTimeout(5_000) { first.await(); assertTrue(second.await().isEmpty()) }
        } finally { db.close() }
    }

    @Test fun playbackConnectingDuringBackupPromotesTheSameRepositoryWithoutClosingIt() = runBlocking {
        val db = MusicDatabase.getDatabase(context)
        var connected: ListeningStatsRepository? = null
        try {
            ListeningStatsRepository.withPreparedDatabase(context, db) {
                connected = ListeningStatsRepository.get(context)
            }
            connected!!.record(snapshot())
            withTimeout(5_000) { connected!!.flush() }
            assertEquals(60_000L, connected!!.summary(ListeningPeriod.LIFETIME).millis)
        } finally { connected?.close(); db.close() }
    }

    @Test fun anExplicitBackupDatabaseDoesNotConsumeAnotherActiveDatabasesJournal() = runBlocking {
        val source = MusicDatabase.createInMemoryDatabase(context)
        val target = MusicDatabase.createInMemoryDatabase(context)
        val file = File(context.noBackupFilesDir, "listening_pending_v1.json")
        source.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_foreign_stats BEFORE INSERT ON listening_totals BEGIN SELECT RAISE(ABORT, 'Keep source pending'); END")
        val writer = ListeningStatsRepository(context, source)
        try {
            writer.record(snapshot()); writer.flushJournal()
            assertEquals(60_000L, ListeningCheckpointJournal(file).read().single().listenedMs)
            val body = JSONObject(backup(target))
            assertEquals(0, body.getJSONArray("listeningStats").length())
            assertTrue(target.listeningEventDao().allForBackup().isEmpty())
            assertEquals(60_000L, ListeningCheckpointJournal(file).read().single().listenedMs)
        } finally { writer.close(); source.close(); target.close(); file.delete() }
    }
}
