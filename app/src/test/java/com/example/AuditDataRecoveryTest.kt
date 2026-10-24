package com.example

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuditDataRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: MusicDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    private fun song(id: String) = MusicTrack(id, id, "Artist", "Album", 180000, "", "https://audio.example/$id")

    @Test fun sharedObserversRecoverWithoutRestartingHealthyObserversOrLosingTheirLastSnapshot() = runBlocking {
        val dao = db.musicDao()
        val original = TrackEntity.fromMusicTrack(song("first")).copy(isLiked = true)
        dao.insertOrUpdateTrack(original)
        val fail = CompletableDeferred<Unit>()
        var healthy = false
        val calls = AtomicInteger()
        val playlistCalls = AtomicInteger()
        val faulty = object : MusicDao by dao {
            override fun getFavoriteTracks() = flow {
                calls.incrementAndGet()
                if (healthy) emitAll(dao.getFavoriteTracks())
                else { emit(listOf(original)); fail.await(); throw IOException("Temporary database failure") }
            }
            override fun getAllPlaylists() = flow { playlistCalls.incrementAndGet(); emitAll(dao.getAllPlaylists()) }
        }
        val repo = MusicRepository(faulty)
        val seen = MutableStateFlow<List<MusicTrack>>(emptyList())
        val favorites = launch { repo.getFavoriteTracks().collect { seen.value = it } }
        val playlists = launch { repo.getAllPlaylists().collect() }
        try {
            withTimeout(5000) { seen.first { it.isNotEmpty() } }
            fail.complete(Unit)
            withTimeout(5000) { repo.libraryFailures.first { "favorites" in it } }
            assertEquals("first", repo.retainedFavoriteTracks.single().id)
            assertTrue(favorites.isActive)
            healthy = true
            dao.insertOrUpdateTrack(TrackEntity.fromMusicTrack(song("second")).copy(isLiked = true))
            repo.retryLocalLibrary()
            withTimeout(5000) { seen.first { it.size == 2 } }
            assertTrue(repo.libraryFailures.value.isEmpty())
            assertEquals(4, calls.get())
            assertEquals(1, playlistCalls.get())
        } finally {
            favorites.cancelAndJoin(); playlists.cancelAndJoin()
            (MusicRepository::class.java.getDeclaredField("initialCatalogScope").apply { isAccessible = true }.get(repo) as CoroutineScope).cancel()
        }
    }

    @Test fun playlistOrderOccurrencesAppendAndSingleEntryRemovalSurviveRoomReads() = runBlocking {
        val repo = MusicRepository(db.musicDao())
        val id = repo.createPlaylistWithTracks("Ordered", "", tracks = listOf(song("z"), song("a"), song("z")))
        var playlist = repo.getPlaylistWithTracks(id).first()!!
        assertEquals(listOf("z", "a", "z"), playlist.musicTracks.map { it.id })
        val ids = playlist.musicTracks.map { it.playlistEntryId }
        assertEquals(3, ids.distinct().size)
        assertTrue(ids.all { it > 0L })
        repo.addTrackToPlaylist(id, song("b"))
        repo.removePlaylistEntry(id, ids.last())
        playlist = repo.getPlaylistWithTracks(id).first()!!
        assertEquals(listOf("z", "a", "b"), playlist.musicTracks.map { it.id })
        assertEquals(ids.first(), playlist.musicTracks.first().playlistEntryId)
    }

    @Test fun failedPlaylistCreationRollsBackPlaylistMetadataAndEntriesTogether() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_second_entry BEFORE INSERT ON playlist_track_cross_ref WHEN NEW.orderIndex = 1 BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")
        val result = runCatching { MusicRepository(db.musicDao()).createPlaylistWithTracks("Failed", "", tracks = listOf(song("a"), song("b"))) }
        assertTrue(result.isFailure)
        assertTrue(db.musicDao().getAllPlaylistsSync().isEmpty())
        assertTrue(db.musicDao().getAllTracksSync().isEmpty())
        assertTrue(db.musicDao().getAllPlaylistCrossRefsSync().isEmpty())
    }

    @Test fun newBackupRoundTripsDuplicateOccurrencesAndUsesTheActualConfiguredFile() = runBlocking {
        val dir = File(context.filesDir, "verified-backup-test")
        BackupRestoreManager.setAutoBackupLocation(context, dir.absolutePath)
        val repo = MusicRepository(db.musicDao())
        repo.createPlaylistWithTracks("Repeated", "", tracks = listOf(song("a"), song("b"), song("a")))
        val saved = BackupRestoreManager.createBackup(context, db).getOrThrow()
        val file = File(saved.filePath)
        assertTrue(file.isFile && file.length() > 0)
        assertEquals(dir.canonicalFile, file.parentFile!!.canonicalFile)
        val json = JSONObject(file.readText())
        assertEquals(3, json.getInt("schemaVersion"))
        assertEquals(3, json.getJSONArray("playlistTrackRefs").length())
        assertEquals(0, json.getJSONArray("favoriteTracks").length())
        val restored = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try {
            assertTrue(BackupRestoreManager.restoreBackup(context, restored, rawJson = json.toString()).success)
            val id = restored.musicDao().getAllPlaylistsSync().single().playlistId
            assertEquals(listOf("a", "b", "a"), MusicRepository(restored.musicDao()).getPlaylistWithTracks(id).first()!!.musicTracks.map { it.id })
        } finally { restored.close(); dir.deleteRecursively() }
    }

    @Test fun nullMediaStoreInsertionCannotCreateAFakeSuccessfulBackup() = runBlocking {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        }
        ShadowContentResolver.registerProviderInternal("media", provider)
        val prefs = ReactiveSettingsRepository.preferences(context, "xtreme_backup_preferences")
        prefs.edit().remove("auto_backup_location").putLong("last_backup_time", 123L).putString("last_backup_file", "previous").apply()
        assertTrue(BackupRestoreManager.createBackup(context, db).isFailure)
        assertEquals(123L, prefs.getLong("last_backup_time", 0L))
        assertEquals("previous", prefs.getString("last_backup_file", null))
    }
}
