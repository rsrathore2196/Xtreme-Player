package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MusicDatabase
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.viewmodel.CatalogBrowserState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeLikedMusicTest {
    private fun song(n: Int) = MusicTrack("yt_${NativeFeedFixtures.id(n)}", "Song $n", "Artist", "Album",
        180000, "", "", isLiked = true)
    private fun playlist(id: String = "VLLM") = CatalogPlaylist(id, "Liked Music", "cover", CatalogProvider.YOUTUBE_MUSIC)
    @Before fun foreground() { BackgroundWorkCoordinator.foreground(true); BackgroundWorkCoordinator.firstFrameRendered() }
    @After fun background() { BackgroundWorkCoordinator.foreground(false) }

    @Test fun automaticLikedCardReadsTheAppsActualLikesForBothBrowseIdsWithoutFetchingYouTube() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java).build()
        val repository = MusicRepository(db.musicDao(), app)
        try {
            db.musicDao().insertOrUpdateTracks((1..13).map { TrackEntity.fromMusicTrack(song(it)) } +
                TrackEntity.fromMusicTrack(song(14).copy(isLiked = false)))
            val expected = repository.getFavoriteTracks().first()
            assertEquals(13, expected.size)
            for (id in listOf("LM", "VLLM")) {
                val page = withTimeout(3000) { repository.catalogBrowser.playlistSongs(playlist(id), 1) }
                assertEquals(expected, page.tracks)
                assertNull(page.nextPage); assertNull(page.browseContinuation)
            }
        } finally { db.close() }
    }

    @Test fun cardPlaybackUsesAllLocalLikesAndAnEmptyLocalCollectionNeverFallsBackToYouTube() = runBlocking {
        val local = MutableStateFlow((1..13).map(::song))
        val repository = CatalogRepository(likedTracksSource = { local }, browseLoader = { _, _, _, _, _ ->
            error("The automatic liked playlist must never browse YouTube")
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val browser = CatalogBrowserState(repository, scope)
            val state = browser.prepareCollectionPlayback(CatalogDestination.Playlist(playlist()))
            assertEquals(local.value, state.tracks)
            assertTrue(state.tracks.all { it.isLiked })
            local.value = emptyList()
            assertTrue(repository.playlistSongs(playlist(), 1).tracks.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun openedLikedCardUpdatesOnLikeUnlikeAndReopenAndPausesItsObserverInTheBackground() = runBlocking {
        val local = MutableStateFlow((1..13).map(::song))
        val repository = CatalogRepository(likedTracksSource = { local }, browseLoader = { _, _, _, _, _ ->
            error("Local likes must not request a provider page")
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val browser = CatalogBrowserState(repository, scope)
            val destination = CatalogDestination.Playlist(playlist())
            suspend fun displayed(tracks: List<MusicTrack>) = withTimeout(3000) {
                browser.detail.first { it?.initialized == true && it.tracks == tracks }
            }
            browser.open(destination); displayed(local.value)
            local.value = listOf(song(20), song(3)); displayed(local.value)
            browser.updateFavorites(setOf("unrelated-catalog-track"))
            delay(50)
            assertEquals(local.value, browser.detail.value!!.tracks)
            BackgroundWorkCoordinator.foreground(false)
            withTimeout(3000) { local.subscriptionCount.first { it == 0 } }
            local.value = listOf(song(21))
            BackgroundWorkCoordinator.foreground(true); displayed(local.value)
            browser.close()
            withTimeout(3000) { local.subscriptionCount.first { it == 0 } }
            local.value = emptyList()
            browser.open(destination); displayed(emptyList())
            assertTrue(browser.detail.value!!.pending.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun ordinaryPlaylistsNamedLikedMusicRetainTheirProviderIdentity() {
        assertFalse(playlist("VLcustom").isAppLikedSongs())
        assertFalse(playlist("LM").copy(provider = CatalogProvider.JIOSAAVN).isAppLikedSongs())
        val response = NativeFeedFixtures.home(NativeFeedFixtures.collectionShelf("Fresh finds, old favourites",
            NativeFeedFixtures.playlist(1).apply {
                getJSONObject("musicTwoRowItemRenderer").put("title", NativeFeedFixtures.text("Liked Music"))
                    .getJSONObject("navigationEndpoint").getJSONObject("browseEndpoint").put("browseId", "VLLM")
            }))
        val item = com.example.data.remote.YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(response)).shelves.single().items.single()
        assertTrue((item as com.example.data.model.ServerHomeItem.Playlist).playlist.isAppLikedSongs())
    }
}
