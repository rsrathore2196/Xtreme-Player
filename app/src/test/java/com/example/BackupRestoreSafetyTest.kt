package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupRestoreSafetyTest {
    private lateinit var context: Context
    private lateinit var db: MusicDatabase
    private val local = TrackEntity("local", "Edited title", "Artist", "Edited album", 180_000,
        "local-cover", "local-audio", bitrateKbps = 999, qualityBadge = "Local quality", genre = "Local genre",
        isLiked = true, isCached = true, lastPlayedAt = 123, addedAt = 42, singers = "Local singers",
        writer = "Local writer", language = "Local language", year = "2001", source = "Local source", isrc = "GBAHS1600463")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = MusicDatabase.createInMemoryDatabase(context)
        UserProfilePreferences.saveUserProfile(context, UserProfile(name = "Original", appLanguage = "Punjabi", isOnboardingCompleted = true))
    }

    @After fun tearDown() { db.close() }

    private fun song(id: String, title: String = "Song", artist: String = "Artist", duration: Long = 180_000) =
        JSONObject().put("id", id).put("title", title).put("artist", artist).put("durationMs", duration)
            .put("isLiked", false)

    private fun playlist(id: Long, title: String) = JSONObject().put("playlistId", id).put("title", title)
    private fun ref(playlist: Long, track: String, order: Int) =
        JSONObject().put("playlistId", playlist).put("trackId", track).put("orderIndex", order)

    private fun backup(tracks: List<JSONObject>, playlists: List<JSONObject> = emptyList(), refs: List<JSONObject> = emptyList()) =
        JSONObject().put("app", "Xtreme Player").put("schemaVersion", 2)
            .put("libraryTracks", JSONArray(tracks)).put("playlists", JSONArray(playlists))
            .put("playlistTrackRefs", JSONArray(refs))

    @Test fun remappedPlaylistIdsCannotConsumeAnotherImportedId() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(local)
        dao.insertPlaylist(PlaylistEntity(1, "Local"))
        dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(1, local.id, 4))
        val json = backup(listOf(song("a"), song("b", "Different")),
            listOf(playlist(1, "Imported one"), playlist(2, "Imported two")),
            listOf(ref(1, "a", 8), ref(2, "b", 1)))

        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString())
        assertTrue(result.message, result.success)
        val playlists = dao.getAllPlaylistsSync()
        assertEquals(3, playlists.size)
        assertEquals("Local", playlists.single { it.playlistId == 1L }.title)
        val one = playlists.single { it.title == "Imported one" }.playlistId
        val two = playlists.single { it.title == "Imported two" }.playlistId
        assertNotEquals(one, two)
        val refs = dao.getAllPlaylistCrossRefsSync()
        assertTrue(refs.contains(PlaylistTrackCrossRef(1, local.id, 4)))
        assertTrue(refs.contains(PlaylistTrackCrossRef(one, "a", 8)))
        assertTrue(refs.contains(PlaylistTrackCrossRef(two, "b", 1)))
    }

    @Test fun matchingRecordingPreservesAllEditedLocalMetadataAndHistory() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(local)
        val event = ListeningEventEntity(trackId = local.id, artistName = local.artist, timestamp = 80,
            sessionId = "session", playedDurationMs = 1234, eventType = "ACTUAL_PLAY")
        val eventId = db.listeningEventDao().insertListeningEvent(event)
        val imported = song("imported", "Provider title").put("isrc", "GB-AHS-16-00463")
            .put("genre", "Pop").put("durationMs", 182_000).put("bitrateKbps", 320)
            .put("qualityBadge", "Remote quality").put("lastPlayedAt", 999).put("addedAt", 999)
        val json = backup(listOf(imported), listOf(playlist(3, "Imported")), listOf(ref(3, "imported", 7)))

        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString())
        assertTrue(result.message, result.success)
        assertEquals(listOf(local), dao.getAllTracksSync())
        assertEquals(listOf(event.copy(id = eventId)), db.listeningEventDao().getRecentEvents(10))
        assertEquals(PlaylistTrackCrossRef(3, local.id, 7), dao.getAllPlaylistCrossRefsSync().single())
    }

    @Test fun duplicateImportedRecordingsUseOneTrackAndEarliestPlaylistPosition() = runBlocking {
        val json = backup(listOf(song("first").put("isrc", "GBAHS1600463"),
            song("second").put("isrc", "gb-ahs-16-00463").put("isCached", true)), listOf(playlist(3, "Imported")),
            listOf(ref(3, "first", 8), ref(3, "second", 2)))
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString())
        assertTrue(result.message, result.success)
        assertEquals(1, db.musicDao().getAllTracksSync().size)
        assertTrue(db.musicDao().getAllTracksSync().single().isCached)
        val relationship = db.musicDao().getAllPlaylistCrossRefsSync().single()
        assertEquals(2, relationship.orderIndex)
        assertEquals(db.musicDao().getAllTracksSync().single().id, relationship.trackId)
    }

    @Test fun nonLatinTitlesAndDifferentRecordingVersionsStayDistinct() = runBlocking {
        val json = backup(listOf(song("a", "Песня первая", "Артист"), song("b", "Песня другая", "Артист"),
            song("c", "Song", duration = 180_000).put("album", "Studio"),
            song("d", "Song (Live)", duration = 240_000).put("album", "Live"),
            song("e", "कल", "कलाकार"), song("f", "काल", "कलाकार"),
            song("g", "Song", "Unknown"), song("h", "Song", "Unknown")))
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString())
        assertTrue(result.message, result.success)
        assertEquals(setOf("a", "b", "c", "d", "e", "f", "g", "h"), db.musicDao().getAllTracksSync().map { it.id }.toSet())
    }

    @Test fun unrelatedTrackWithSameProviderIdCannotOverwriteLocalRecord() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(local)
        dao.insertPlaylist(PlaylistEntity(1, "Original"))
        val json = backup(listOf(song(local.id, "Unrelated", "Another artist").put("isrc", "USAAA2300001")),
            listOf(playlist(2, "New playlist")))
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString())
        assertFalse(result.success)
        assertEquals(local, dao.getTrackById(local.id))
        assertEquals("Original", dao.getAllPlaylistsSync().single().title)
    }

    @Test fun relationshipsCannotAttachImportedDataToUnrelatedLocalPlaylists() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(local)
        dao.insertPlaylist(PlaylistEntity(1, "Original"))
        dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(1, local.id, 4))
        for (mode in BackupRestoreManager.RestoreMode.entries) {
            val json = backup(listOf(song("imported")), refs = listOf(ref(1, "imported", 0)))
            val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString(), mode = mode, confirmReplace = true)
            assertFalse(result.success)
            assertEquals(listOf(local), dao.getAllTracksSync())
            assertEquals("Original", dao.getAllPlaylistsSync().single().title)
            assertEquals(PlaylistTrackCrossRef(1, local.id, 4), dao.getAllPlaylistCrossRefsSync().single())
        }
    }

    @Test fun unsupportedOrMalformedVersionsAreRejectedWithoutWrites() = runBlocking {
        db.musicDao().insertPlaylist(PlaylistEntity(1, "Original"))
        for (version in listOf<Any>(0, 3, 10, 2.5, "2", JSONObject.NULL)) {
            val json = backup(listOf(song("imported"))).put("schemaVersion", version)
            val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString())
            assertFalse("Accepted version $version", result.success)
            assertTrue(db.musicDao().getAllTracksSync().isEmpty())
            assertEquals("Original", db.musicDao().getAllPlaylistsSync().single().title)
        }
    }

    @Test fun malformedSuppliedFieldsAreRejectedBeforeAnyWrites() = runBlocking {
        val invalid = listOf(
            backup(listOf(song("imported").put("id", JSONObject.NULL))),
            backup(listOf(song("imported").put("id", 42))),
            backup(listOf(song("imported").put("durationMs", -1))),
            backup(listOf(song("imported").put("isLiked", "true"))),
            backup(listOf(song("imported"))).put("userProfile", "invalid"),
            backup(listOf(song("imported"))).put("userProfile", JSONObject().put("languages", "Hindi")),
            backup(listOf(song("imported")), listOf(playlist(1, "Bad").put("playlistId", 1.5)))
        )
        for (json in invalid) {
            val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString())
            assertFalse("Accepted $json", result.success)
            assertTrue(db.musicDao().getAllTracksSync().isEmpty())
            assertTrue(db.musicDao().getAllPlaylistsSync().isEmpty())
            assertEquals("Original", UserProfilePreferences.getUserProfile(context).name)
        }
    }

    @Test fun confirmedReplaceFailureRollsBackDeletedPlaylistsAndEarlierInserts() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(local)
        dao.insertPlaylist(PlaylistEntity(1, "Original"))
        dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(1, local.id, 4))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER restore_failure BEFORE INSERT ON tracks WHEN NEW.id = 'failure'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        val json = backup(listOf(song("good"), song("failure", "Different")), listOf(playlist(2, "Replacement")))
            .put("userProfile", JSONObject().put("name", "Changed"))
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString(),
            mode = BackupRestoreManager.RestoreMode.REPLACE, confirmReplace = true)
        assertFalse(result.success)
        assertEquals(listOf(local), dao.getAllTracksSync())
        assertEquals("Original", dao.getAllPlaylistsSync().single().title)
        assertEquals(PlaylistTrackCrossRef(1, local.id, 4), dao.getAllPlaylistCrossRefsSync().single())
        assertEquals("Original", UserProfilePreferences.getUserProfile(context).name)
    }

    @Test fun legacyIdOnlyFavoriteBackupStillWorks() = runBlocking {
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = """{"favoriteTracks":[{"id":"legacy"}]}""")
        assertTrue(result.message, result.success)
        assertTrue(db.musicDao().getTrackById("legacy")!!.isLiked)
    }
    @Test fun legacyFavoriteOnlyReplaceDoesNotDeleteUnspecifiedPlaylists() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(local)
        dao.insertPlaylist(PlaylistEntity(1, "Original"))
        dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(1, local.id, 4))
        val result = BackupRestoreManager.restoreBackup(context, db,
            rawJson = """{"favoriteTracks":[{"id":"legacy"}]}""",
            mode = BackupRestoreManager.RestoreMode.REPLACE, confirmReplace = true)
        assertTrue(result.message, result.success)
        assertEquals("Original", dao.getAllPlaylistsSync().single().title)
        assertEquals(PlaylistTrackCrossRef(1, local.id, 4), dao.getAllPlaylistCrossRefsSync().single())
        assertEquals(local, dao.getTrackById(local.id))
        assertTrue(dao.getTrackById("legacy")!!.isLiked)
    }

    @Test fun profilePersistenceFailureRollsBackLibraryAndCompensatesPreferences() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(local)
        dao.insertPlaylist(PlaylistEntity(1, "Original"))
        dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(1, local.id, 4))
        val failingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = baseContext.getSharedPreferences(name, mode)
                if (name != "xtreme_user_profile_prefs") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            private var imported = false
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                if (key == "user_name" && value == "Changed") imported = true
                                editor.putString(key, value)
                                return this
                            }
                            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                                editor.putBoolean(key, value)
                                return this
                            }
                            override fun commit(): Boolean {
                                val committed = editor.commit()
                                // Simulate SharedPreferences' failure result after its memory map changed.
                                return committed && !imported
                            }
                        }
                    }
                }
            }
        }
        val json = backup(listOf(song("new")), listOf(playlist(2, "Replacement")), listOf(ref(2, "new", 1)))
            .put("userProfile", JSONObject().put("name", "Changed"))
        val result = BackupRestoreManager.restoreBackup(failingContext, db, rawJson = json.toString(),
            mode = BackupRestoreManager.RestoreMode.REPLACE, confirmReplace = true)
        assertFalse(result.success)
        assertEquals(listOf(local), dao.getAllTracksSync())
        assertEquals("Original", dao.getAllPlaylistsSync().single().title)
        assertEquals(PlaylistTrackCrossRef(1, local.id, 4), dao.getAllPlaylistCrossRefsSync().single())
        assertEquals("Original", UserProfilePreferences.getUserProfile(context).name)
        assertEquals("Original", UserProfilePreferences.getUserProfileFlow(context).value.name)
    }

    @Test fun mergeCannotReuseAnIdBelongingToLegacyOrphanRelationships() = runBlocking {
        val dao = db.musicDao()
        dao.insertOrUpdateTrack(local)
        dao.insertPlaylist(PlaylistEntity(1, "Deleted playlist"))
        dao.insertPlaylistTrackRef(PlaylistTrackCrossRef(1, local.id, 4))
        dao.deletePlaylistRow(1) // Older app versions left this junction row behind.
        val json = backup(listOf(song("imported")), listOf(playlist(1, "Imported")), listOf(ref(1, "imported", 0)))
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = json.toString())
        assertTrue(result.message, result.success)
        val importedId = dao.getAllPlaylistsSync().single().playlistId
        assertNotEquals(1L, importedId)
        val refs = dao.getAllPlaylistCrossRefsSync()
        assertTrue(refs.contains(PlaylistTrackCrossRef(1, local.id, 4)))
        assertEquals(listOf(PlaylistTrackCrossRef(importedId, "imported", 0)), refs.filter { it.playlistId == importedId })
    }

}
