package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.ListeningStatsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListeningStatsBackupTest {
    @Test fun exportedStatsRestoreOfflineAndRepeatedImportsDoNotDuplicateMinutesOrPlays() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = MusicDatabase.createInMemoryDatabase(context)
        val target = MusicDatabase.createInMemoryDatabase(context)
        val repository = ListeningStatsRepository(context, source)
        val restored = ListeningStatsRepository(context, target)
        val folder = File(context.filesDir, "stats-backup-test")
        BackupRestoreManager.setAutoBackupLocation(context, folder.absolutePath)
        try {
            val track = MusicTrack("yt_abcdefghijk", "Song", "Main Artist", "Album", 120_000, "", "source", isLiked = true)
            repository.record(ListeningSnapshot("one", track, mapOf("2026-10-26" to ListeningDay(60_000, 1)), 60_000, true))
            repository.flush()
            val backup = BackupRestoreManager.createBackup(context, source).getOrThrow()
            val body = File(backup.filePath).readText()
            assertEquals(4, JSONObject(body).getInt("schemaVersion"))
            assertEquals(4, JSONObject(body).getJSONArray("listeningStats").length())
            repeat(2) {
                val result = BackupRestoreManager.restoreBackup(context, target, rawJson = body)
                assertTrue(result.message, result.success)
            }
            assertEquals(60_000, restored.summary(ListeningPeriod.LIFETIME).millis)
            assertEquals(1, restored.summary(ListeningPeriod.LIFETIME).plays)
            assertTrue(target.musicDao().getTrackById(track.id)!!.isLiked)
            assertTrue("Import must not invent raw playback events", target.listeningEventDao().getActualPlays().isEmpty())
        } finally { repository.close(); restored.close(); source.close(); target.close(); folder.deleteRecursively() }
    }
    @Test fun malformedStatsAbortTheWholeRestoreBeforeAnyLibraryChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = MusicDatabase.createInMemoryDatabase(context)
        try {
            val row = ListeningTotal("device", "2026-10", "song", "song", "one", "Song", "Artist", "", "", 120_000, "", 60_000, 1)
            val stats = ListeningStatsBackup.encode(listOf(row))
            stats.getJSONObject(0).put("listenedMs", -1)
            val backup = JSONObject().put("schemaVersion", 4).put("libraryTracks", JSONArray().put(JSONObject().put("id", "new-song")))
                .put("listeningStats", stats)
            assertFalse(BackupRestoreManager.restoreBackup(context, db, rawJson = backup.toString()).success)
            assertTrue(db.musicDao().getAllTracksSync().isEmpty())
            assertTrue(db.listeningStatsDao().all().isEmpty())
        } finally { db.close() }
    }
}
