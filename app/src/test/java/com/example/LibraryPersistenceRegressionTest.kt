package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryPersistenceRegressionTest {
    private lateinit var context: Context
    private lateinit var db: MusicDatabase
    private val saved = TrackEntity("song", "Song", "Artist", "Album", 180000L, "cover", "url",
        isLiked = true, isCached = true, lastPlayedAt = 123L, addedAt = 42L)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = MusicDatabase.createInMemoryDatabase(context)
        UserProfilePreferences.saveUserProfile(context, UserProfile(name = "Original"))
    }

    @After fun tearDown() { db.close() }

    @Test fun catalogRefreshPreservesUserFieldsAndUpdatesMetadata() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(saved)
        dao.mergeTracksMetadata(listOf(saved.copy(title = "Updated", isLiked = false,
            isCached = false, lastPlayedAt = 0, addedAt = 999)))
        assertEquals(saved.copy(title = "Updated"), dao.getTrackById(saved.id))
    }

    @Test fun playlistAndPlaybackWritesPreserveFavoritesAndHistory() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(saved)
        val repo = MusicRepository(dao)
        val stale = saved.toMusicTrack().copy(isLiked = false, isCached = false)
        val playlist = repo.createPlaylistWithTracks("Test", "", tracks = listOf(stale))
        assertEquals(saved, dao.getTrackById(saved.id))
        repo.addTrackToPlaylist(playlist, stale)
        assertEquals(saved, dao.getTrackById(saved.id))
        dao.recordTrackPlayed(TrackEntity.fromMusicTrack(stale), 456)
        assertEquals(saved.copy(lastPlayedAt = 456), dao.getTrackById(saved.id))
    }

    @Test fun staleMetadataCannotRelikeAnUnlikedTrack() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(saved.copy(isLiked = false))
        dao.mergeTrackMetadata(saved)
        assertFalse(dao.getTrackById(saved.id)!!.isLiked)
        assertTrue(dao.toggleTrackFavorite(saved))
        assertFalse(dao.toggleTrackFavorite(saved))
    }

    @Test fun malformedLateRecordDoesNotModifyProfileOrPlaylists() = runBlocking {
        db.musicDao().insertPlaylist(PlaylistEntity(7, "Original"))
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = """
            {"userProfile":{"name":"Changed"}, "playlists":[{"playlistId":7,"title":"Changed"}],
             "libraryTracks":[{"title":"Missing required ID"}]}
        """.trimIndent())
        assertFalse(result.success)
        assertEquals("Original", db.musicDao().getAllPlaylistsSync().single().title)
        assertEquals("Original", UserProfilePreferences.getUserProfile(context).name)
    }

    @Test fun databaseFailureRollsBackEarlierRestoreWrites() = runBlocking {
        db.musicDao().insertPlaylist(PlaylistEntity(7, "Original"))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_track BEFORE INSERT ON tracks WHEN NEW.id = 'bad'
            BEGIN SELECT RAISE(ABORT, 'injected write failure'); END
        """.trimIndent())
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = """
            {"userProfile":{"name":"Changed"}, "playlists":[{"playlistId":7,"title":"Changed"}],
             "libraryTracks":[{"id":"bad"}]}
        """.trimIndent())
        assertFalse(result.success)
        assertEquals("Original", db.musicDao().getAllPlaylistsSync().single().title)
        assertTrue(db.musicDao().getAllTracksSync().isEmpty())
        assertEquals("Original", UserProfilePreferences.getUserProfile(context).name)
    }

    @Test fun danglingRelationshipsAreRejectedBeforeWrites() = runBlocking {
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = """
            {"playlists":[{"playlistId":1}],"libraryTracks":[],
             "playlistTrackRefs":[{"playlistId":1,"trackId":"missing"}]}
        """.trimIndent())
        assertFalse(result.success)
        assertTrue(db.musicDao().getAllPlaylistsSync().isEmpty())
    }

    @Test fun validRestorePreservesHistoryAndRelationshipOrder() = runBlocking {
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = """
            {"app":"Xtreme Player","schemaVersion":2,"playlists":[{"playlistId":1,"title":"Restored"}],
             "libraryTracks":[{"id":"song","isLiked":true,"lastPlayedAt":123,"isrc":"recording"}],
             "playlistTrackRefs":[{"playlistId":1,"trackId":"song","orderIndex":4}]}
        """.trimIndent())
        assertTrue(result.message, result.success)
        assertEquals(123L, db.musicDao().getTrackById("song")!!.lastPlayedAt)
        assertEquals("recording", db.musicDao().getTrackById("song")!!.isrc)
        assertEquals(4, db.musicDao().getAllPlaylistCrossRefsSync().single().orderIndex)
    }

    @Test fun legacyFavoriteBackupStillRestores() = runBlocking {
        val result = BackupRestoreManager.restoreBackup(context, db,
            rawJson = """{"favoriteTracks":[{"id":"legacy"}]}""")
        assertTrue(result.message, result.success)
        assertTrue(db.musicDao().getTrackById("legacy")!!.isLiked)
    }
}
