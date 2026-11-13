package com.example.connections

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectedLibraryImportTest {
    private lateinit var db: MusicDatabase
    private lateinit var client: ConnectedLibraries
    private val dao get() = db.musicDao()
    private fun track(id: String) = MusicTrack(id, "Song $id", "Artist", "Album", 180_000, "", "https://example.test/$id")

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = MusicDatabase.createInMemoryDatabase(context)
        client = ConnectedLibraries(context, db)
    }
    @After fun close() { db.close() }
    private suspend fun queue(id: Long) = dao.getPlaylistWithTracks(id).first()!!.musicTracks.map { it.id }

    @Test fun matchingNamesMergeMissingSongsWithoutReplacingLocalEntriesOrUserMetadata() = runBlocking {
        val original = TrackEntity.fromMusicTrack(track("shared")).copy(isLiked = true, isCached = true, lastPlayedAt = 123, addedAt = 42)
        val id = dao.createPlaylistWithEntries(PlaylistEntity(title = "  Road\u00a0 Trip ", description = "My notes", coverUrl = "my-cover"),
            listOf(TrackEntity.fromMusicTrack(track("local")), original))
        val entries = dao.playlistEntries(id)
        val collection = ConnectedCollection("spotify", "remote", "road trip", "remote-cover")
        client.saveImport(collection, "account", listOf(track("shared"), track("new")))
        assertEquals(id, dao.importedPlaylistId("spotify", "account", "remote"))
        assertEquals(listOf("local", "shared", "new"), queue(id))
        assertEquals(entries, dao.playlistEntries(id).take(2))
        val playlist = dao.getAllPlaylistsSync().single()
        assertEquals("  Road\u00a0 Trip ", playlist.title)
        assertEquals("My notes", playlist.description)
        assertEquals("my-cover", playlist.coverUrl)
        assertEquals(original, dao.getTrackById("shared"))
    }

    @Test fun repeatImportsSkipAllExistingSongsAndIncomingDuplicates() = runBlocking {
        val collection = ConnectedCollection("youtube", "VLPLmix", "Mix")
        val a = track("a"); val b = track("b")
        assertEquals(2, client.saveImport(collection, "account", listOf(a, a, b)))
        val id = dao.importedPlaylistId("youtube", "account", collection.id)!!
        val entries = dao.playlistEntries(id)
        assertEquals(0, client.saveImport(collection, "account", listOf(a, a, b)))
        assertEquals(0, client.saveImport(collection, "account", listOf(a, b)))
        assertEquals(entries, dao.playlistEntries(id))
        assertEquals(0, client.saveImport(collection, "account", listOf(a, a, a, b)))
        assertEquals(listOf("a", "b"), queue(id))
        assertEquals(entries, dao.playlistEntries(id))
        assertEquals(1, dao.getAllPlaylistsSync().size)
    }

    @Test fun mergingDoesNotAddDuplicatesOrRemoveExistingLocallyRepeatedEntries() = runBlocking {
        val a = TrackEntity.fromMusicTrack(track("a"))
        val id = dao.createPlaylistWithEntries(PlaylistEntity(title = "Mix"), listOf(a, a))
        val entries = dao.playlistEntries(id)
        client.saveImport(ConnectedCollection("spotify", "remote", "Mix"), "account", listOf(track("a"), track("a"), track("a"), track("b")))
        assertEquals(listOf("a", "a", "b"), queue(id))
        assertEquals(entries, dao.playlistEntries(id).take(2))
    }

    @Test fun matchingRecordingsFromAnotherProviderAreSkippedButLiveAndCoverVersionsRemainDistinct() = runBlocking {
        val original = track("saavn-song").copy(title = "Our Song", artist = "Singer", isrc = "USABC2400001")
        val id = dao.createPlaylistWithEntries(PlaylistEntity(title = "Mix"), listOf(TrackEntity.fromMusicTrack(original)))
        val alias = original.copy(id = "yt_recording", source = "YouTube Music")
        val live = alias.copy(id = "yt_live", title = "Our Song (Live)", isrc = "USABC2400002")
        val cover = alias.copy(id = "yt_cover", artist = "Another Singer", isrc = "USABC2400003")
        client.saveImport(ConnectedCollection("youtube", "VLPLmix", "Mix"), "account", listOf(alias, live, cover, live))
        assertEquals(listOf(original.id, live.id, cover.id), queue(id))
        assertNull(dao.getTrackById(alias.id))
    }

    @Test fun sameNamesFromDifferentAccountsAndProvidersMergeIntoOnePlaylist() = runBlocking {
        client.saveImport(ConnectedCollection("spotify", "one", "Café &amp; Chill"), "account-one", listOf(track("a")))
        client.saveImport(ConnectedCollection("youtube", "VLPLtwo", "CAFE\u0301 & CHILL"), "account-two", listOf(track("a"), track("b")))
        val id = dao.getAllPlaylistsSync().single().playlistId
        assertEquals(listOf("a", "b"), queue(id))
        assertEquals(id, dao.importedPlaylistId("spotify", "account-one", "one"))
        assertEquals(id, dao.importedPlaylistId("youtube", "account-two", "VLPLtwo"))
    }

    @Test fun differentNamesCreateNewPlaylistsAndLeaveOldOnesUntouched() = runBlocking {
        val source = ConnectedCollection("spotify", "remote", "First mix")
        client.saveImport(source, "account", listOf(track("a")))
        val first = dao.importedPlaylistId("spotify", "account", "remote")!!
        client.saveImport(source.copy(title = "Second mix"), "account", listOf(track("b")))
        val second = dao.importedPlaylistId("spotify", "account", "remote")!!
        assertNotEquals(first, second)
        assertEquals(listOf("a"), queue(first))
        assertEquals(listOf("b"), queue(second))
    }

    @Test fun providerLikesMergeIntoBuiltInFavoritesWithoutCreatingAPlaylist() = runBlocking {
        val local = TrackEntity.fromMusicTrack(track("local")).copy(isLiked = true)
        val known = TrackEntity.fromMusicTrack(track("known")).copy(isLiked = false, isCached = true, lastPlayedAt = 123, addedAt = 42)
        dao.insertOrUpdateTracks(listOf(local, known))
        client.saveImport(ConnectedCollection("youtube", "VLLM", "Liked Songs"), "youtube-account", listOf(track("known"), track("a")))
        client.saveImport(ConnectedCollection("spotify", "liked", "Liked Songs"), "spotify-account", listOf(track("a"), track("b")))
        assertTrue(dao.getAllPlaylistsSync().isEmpty())
        assertEquals(setOf("local", "known", "a", "b"), dao.getAllFavoriteTracksSync().map { it.id }.toSet())
        assertEquals(known.copy(isLiked = true), dao.getTrackById("known"))
        assertEquals(local, dao.getTrackById("local"))
    }

    @Test fun ordinaryPlaylistCalledLikedSongsDoesNotChangeTheAppFavorites() = runBlocking {
        client.saveImport(ConnectedCollection("youtube", "VLPLordinary", "Liked Songs"), "account", listOf(track("a")))
        assertEquals("Liked Songs", dao.getAllPlaylistsSync().single().title)
        assertTrue(dao.getAllFavoriteTracksSync().isEmpty())
    }

    @Test fun importingAnotherProviderCopyOfAnExistingFavoriteDoesNotDuplicateIt() = runBlocking {
        val original = track("saavn-liked").copy(title = "Our Song", artist = "Singer", isrc = "USABC2400001", isLiked = true)
        dao.insertOrUpdateTrack(TrackEntity.fromMusicTrack(original))
        client.saveImport(ConnectedCollection("spotify", "liked", "Liked Songs"), "account",
            listOf(original.copy(id = "yt_same-recording", source = "YouTube Music")))
        assertEquals(listOf(original.id), dao.getAllFavoriteTracksSync().map { it.id })
        assertNull(dao.getTrackById("yt_same-recording"))
    }

    @Test fun largeLikedLibrariesUseBatchedUpdatesAndMergeWithoutDuplicates() = runBlocking {
        val tracks = (1..1001).map { track("large-$it") }
        client.saveImport(ConnectedCollection("spotify", "liked", "Liked Songs"), "account", tracks + tracks.first())
        client.saveImport(ConnectedCollection("spotify", "liked", "Liked Songs"), "account", tracks.take(5))
        assertEquals(1001, dao.getAllFavoriteTracksSync().size)
        assertTrue(dao.getAllPlaylistsSync().isEmpty())
    }

    @Test fun concurrentSameNameImportsCannotCreateDuplicatePlaylists() = runBlocking {
        coroutineScope {
            val first = async(Dispatchers.Default) { client.saveImport(ConnectedCollection("spotify", "one", "Mix"), "one", listOf(track("a"))) }
            val second = async(Dispatchers.Default) { client.saveImport(ConnectedCollection("youtube", "VLPLtwo", "Mix"), "two", listOf(track("b"))) }
            awaitAll(first, second)
        }
        val id = dao.getAllPlaylistsSync().single().playlistId
        assertEquals(setOf("a", "b"), queue(id).toSet())
    }

    @Test fun failedImportRollsBackMetadataAndPlaylistChanges() = runBlocking {
        val original = TrackEntity.fromMusicTrack(track("a"))
        val id = dao.createPlaylistWithEntries(PlaylistEntity(title = "Mix"), listOf(original))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_import BEFORE INSERT ON connected_collections WHEN NEW.remoteId = 'remote'
            BEGIN SELECT RAISE(ABORT, 'injected import failure'); END
        """.trimIndent())
        val result = runCatching { client.saveImport(ConnectedCollection("spotify", "remote", "Mix"), "account",
            listOf(track("a").copy(title = "Changed"), track("bad"))) }
        assertTrue(result.isFailure)
        assertEquals(original, dao.getTrackById("a"))
        assertNull(dao.getTrackById("bad"))
        assertEquals(listOf("a"), queue(id))
        assertNull(dao.importedPlaylistId("spotify", "account", "remote"))
    }
}
