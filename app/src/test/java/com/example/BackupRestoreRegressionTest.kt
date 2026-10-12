package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
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
class BackupRestoreRegressionTest {

    private lateinit var context: Context
    private lateinit var db: MusicDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = MusicDatabase.createInMemoryDatabase(context)
        UserProfilePreferences.saveUserProfile(context, UserProfile(name = "Original User"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testConflictingPlaylistIdAssignsNewIdAndRemapsRelationshipsOnMerge() = runBlocking {
        val dao = db.musicDao()

        // Local existing playlist with ID 1
        dao.insertPlaylist(PlaylistEntity(playlistId = 1, title = "Local Playlist 1"))
        dao.insertOrUpdateTrack(TrackEntity(id = "local_song", title = "Local Song", artist = "Local Artist", album = "", durationMs = 1000, coverUrl = "", audioUrl = ""))
        dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(playlistId = 1, trackId = "local_song", orderIndex = 0))

        // Imported backup also has playlist ID 1, but with a different title and track
        val backupJson = """
            {
                "app": "Xtreme Player",
                "schemaVersion": 2,
                "playlists": [
                    {"playlistId": 1, "title": "Imported Playlist 1"}
                ],
                "libraryTracks": [
                    {"id": "imported_song", "title": "Imported Song", "artist": "Imported Artist", "album": "", "durationMs": 2000, "coverUrl": "", "audioUrl": ""}
                ],
                "playlistTrackRefs": [
                    {"playlistId": 1, "trackId": "imported_song", "orderIndex": 0}
                ]
            }
        """.trimIndent()

        val result = BackupRestoreManager.restoreBackup(
            context,
            db,
            rawJson = backupJson,
            mode = BackupRestoreManager.RestoreMode.MERGE
        )

        assertTrue(result.message, result.success)

        val playlists = dao.getAllPlaylistsSync()
        // Both playlists must exist: local playlist was NOT overwritten!
        assertEquals(2, playlists.size)

        val localPl = playlists.first { it.title == "Local Playlist 1" }
        assertEquals(1L, localPl.playlistId)

        val importedPl = playlists.first { it.title == "Imported Playlist 1" }
        // Must have received a new unique ID (> 1)
        assertTrue(importedPl.playlistId > 1L)

        // Check relationship remapping: imported track ref must point to imported playlist's new ID
        val refs = dao.getAllPlaylistCrossRefsSync()
        assertEquals(2, refs.size)

        val localRef = refs.first { it.trackId == "local_song" }
        assertEquals(1L, localRef.playlistId)

        val importedRef = refs.first { it.trackId == "imported_song" }
        assertEquals(importedPl.playlistId, importedRef.playlistId)
        assertEquals(0, importedRef.orderIndex)
    }

    @Test
    fun testDuplicateTracksReconciledByReliableIdentifiersPreservesLocalFavoritesAndHistory() = runBlocking {
        val dao = db.musicDao()

        // Existing local track: marked favorite by user, with listening history timestamp
        val localTrack = TrackEntity(
            id = "yt_track_1",
            title = "Shape of You",
            artist = "Ed Sheeran",
            album = "Divide",
            durationMs = 230000,
            coverUrl = "local_cover.jpg",
            audioUrl = "local_stream.mp3",
            isLiked = true,
            lastPlayedAt = 1710000000000L,
            addedAt = 1700000000000L,
            isrc = "GBAHS1600463"
        )
        dao.insertOrUpdateTrack(localTrack)

        // Backup has a duplicate track with different ID ("backup_shape") but matching ISRC, unliked, no history
        val backupJson = """
            {
                "app": "Xtreme Player",
                "schemaVersion": 2,
                "playlists": [
                    {"playlistId": 5, "title": "Hits"}
                ],
                "libraryTracks": [
                    {
                        "id": "backup_shape",
                        "title": "Shape of You",
                        "artist": "Ed Sheeran",
                        "album": "Divide Deluxe",
                        "durationMs": 230000,
                        "coverUrl": "backup_cover.jpg",
                        "audioUrl": "backup_stream.mp3",
                        "isLiked": false,
                        "lastPlayedAt": 0,
                        "addedAt": 1720000000000,
                        "isrc": "GBAHS1600463"
                    }
                ],
                "playlistTrackRefs": [
                    {"playlistId": 5, "trackId": "backup_shape", "orderIndex": 0}
                ]
            }
        """.trimIndent()

        val result = BackupRestoreManager.restoreBackup(
            context,
            db,
            rawJson = backupJson,
            mode = BackupRestoreManager.RestoreMode.MERGE
        )

        assertTrue(result.message, result.success)

        // Only one unique track row should exist
        val tracks = dao.getAllTracksSync()
        assertEquals(1, tracks.size)

        val reconciled = tracks[0]
        assertEquals("yt_track_1", reconciled.id)
        // Local user favorite status and listening history MUST be preserved!
        assertTrue("Local favorite must be preserved", reconciled.isLiked)
        assertEquals("Local listening history must be preserved", 1710000000000L, reconciled.lastPlayedAt)
        assertEquals("Local addedAt must be preserved", 1700000000000L, reconciled.addedAt)

        // Playlist relationship must be remapped to the existing track ID
        val refs = dao.getAllPlaylistCrossRefsSync()
        assertEquals(1, refs.size)
        assertEquals(5L, refs[0].playlistId)
        assertEquals("yt_track_1", refs[0].trackId)
    }

    @Test
    fun testEmptyBackupReturnsErrorAndDoesNotModifyDatabase() = runBlocking {
        val dao = db.musicDao()
        dao.insertPlaylist(PlaylistEntity(playlistId = 1, title = "Original Playlist"))

        val emptyBackupJson = """
            {
                "app": "Xtreme Player",
                "schemaVersion": 2,
                "playlists": [],
                "libraryTracks": []
            }
        """.trimIndent()

        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = emptyBackupJson)
        assertFalse(result.success)
        assertTrue(result.message.contains("empty", ignoreCase = true))

        // Database remains untouched
        assertEquals(1, dao.getAllPlaylistsSync().size)
    }

    @Test
    fun testCorruptedBackupReturnsErrorAndDoesNotModifyDatabase() = runBlocking {
        val dao = db.musicDao()
        dao.insertPlaylist(PlaylistEntity(playlistId = 1, title = "Original Playlist"))

        val corruptedJson = "INVALID_JSON_CONTENT {{{ "

        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = corruptedJson)
        assertFalse(result.success)
        assertTrue(result.message.contains("corrupted", ignoreCase = true) || result.message.contains("Invalid", ignoreCase = true))

        assertEquals(1, dao.getAllPlaylistsSync().size)
    }

    @Test
    fun testReplaceModeRequiresExplicitConfirmation() = runBlocking {
        val dao = db.musicDao()
        dao.insertPlaylist(PlaylistEntity(playlistId = 1, title = "Existing Playlist"))

        val backupJson = """
            {
                "app": "Xtreme Player",
                "schemaVersion": 2,
                "playlists": [{"playlistId": 2, "title": "New Playlist"}],
                "libraryTracks": [{"id": "t1", "title": "Song 1", "artist": "Artist 1"}]
            }
        """.trimIndent()

        // 1. Calling REPLACE without confirmation MUST fail safely
        val resultWithoutConfirm = BackupRestoreManager.restoreBackup(
            context,
            db,
            rawJson = backupJson,
            mode = BackupRestoreManager.RestoreMode.REPLACE,
            confirmReplace = false
        )
        assertFalse(resultWithoutConfirm.success)
        assertTrue(resultWithoutConfirm.message.contains("confirmation", ignoreCase = true))
        assertEquals("Existing Playlist", dao.getAllPlaylistsSync().single().title)

        // 2. Calling REPLACE with confirmReplace = true succeeds and replaces
        val resultWithConfirm = BackupRestoreManager.restoreBackup(
            context,
            db,
            rawJson = backupJson,
            mode = BackupRestoreManager.RestoreMode.REPLACE,
            confirmReplace = true
        )
        assertTrue(resultWithConfirm.success)
        assertEquals("New Playlist", dao.getAllPlaylistsSync().single().title)
    }

    @Test
    fun testInterruptedRestorationRollsBackTransaction() = runBlocking {
        val dao = db.musicDao()
        dao.insertPlaylist(PlaylistEntity(playlistId = 1, title = "Original Playlist"))

        // Add SQLite trigger to force an error on inserting track "failing_track"
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_on_trigger BEFORE INSERT ON tracks WHEN NEW.id = 'failing_track'
            BEGIN SELECT RAISE(ABORT, 'Simulated write interruption'); END
        """.trimIndent())

        val backupJson = """
            {
                "userProfile": {"name": "Should Not Be Saved"},
                "playlists": [{"playlistId": 99, "title": "Should Not Be Saved"}],
                "libraryTracks": [{"id": "failing_track", "title": "Fail", "artist": "Fail"}]
            }
        """.trimIndent()

        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = backupJson)
        assertFalse(result.success)

        // Verify total rollback: playlist was not created and profile was not updated
        assertEquals("Original Playlist", dao.getAllPlaylistsSync().single().title)
        assertEquals("Original User", UserProfilePreferences.getUserProfile(context).name)
    }
}
