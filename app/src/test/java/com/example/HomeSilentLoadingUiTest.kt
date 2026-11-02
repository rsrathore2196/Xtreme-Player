package com.example

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MusicDatabase
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.HomeScreenContent
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeSilentLoadingUiTest {
    @get:Rule val compose = createComposeRule()
    private val song = MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "", "")
    @Composable private fun listFixture() = rememberLazyListState(prefetchStrategy = remember {
        object : LazyListPrefetchStrategy {
            override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {}
            override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {}
            override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {}
        }
    })
    private fun content(block: @Composable () -> Unit) = compose.setContent { MaterialTheme {
        CompositionLocalProvider(LocalAppColors provides DarkAppColors, LocalDynamicGlassEnabled provides false,
            LocalVisualEffectsActive provides false, LocalArtworkRequestsActive provides false, content = block)
    } }
    @Test fun delayedOptionalShelvesPreserveTheNativeViewportKeyAndOffset() {
        var profile by mutableStateOf<ServerHomeShelf?>(null)
        var artists by mutableStateOf(emptyList<MostListenedArtist>())
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        content {
            list = listFixture(); scope = rememberCoroutineScope()
            HomeScreenContent(serverShelves = (1..4).map { ServerHomeShelf("s$it", "Native $it", tracks = listOf(song)) },
                profileShelf = profile, mostListenedArtists = artists, listState = list,
                onTrackClick = { _, _ -> }, onToggleFavorite = {})
        }
        compose.runOnIdle { scope.launch { list.scrollToItem(3, 32) } }
        compose.waitForIdle()
        val anchor = compose.runOnIdle { list.layoutInfo.visibleItemsInfo.first().key to list.firstVisibleItemScrollOffset }
        compose.runOnIdle {
            profile = ServerHomeShelf("profile", "Music for You", tracks = listOf(song))
            artists = listOf(MostListenedArtist("Singer", 1, listOf(song)))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor.first, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(anchor.second, list.firstVisibleItemScrollOffset)
        }
    }
    @Test fun failedNativeLoadingShowsNeitherAnErrorRowNorAnEndlessSkeleton() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = MusicDatabase.createInMemoryDatabase(app)
        val repository = MusicRepository(db.musicDao(), app)
        val player = PlaybackManager(app, repository)
        val store = ViewModelStore()
        try {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repository, player, app, db,
                    homeLoader = { _, _, _ -> YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, isError = true) }) as T
            }
            val vm = ViewModelProvider(store, factory)[HomeViewModel::class.java]
            content { HomeScreenContent(homeViewModel = vm, listState = listFixture(), onTrackClick = { _, _ -> }, onToggleFavorite = {}) }
            withTimeout(5_000) { vm.refreshHome(true).join() }
            compose.waitForIdle()
            assertTrue(vm.serverCards.value.isEmpty())
            compose.onAllNodesWithTag("home_feed_notice").assertCountEquals(0)
            compose.onAllNodesWithText("Retry").assertCountEquals(0)
            compose.onAllNodesWithTag("home_shelf_heading_loading").assertCountEquals(0)
            compose.onNodeWithText("XTREME").assertExists()
        } finally { compose.runOnUiThread { store.clear(); player.release() }; db.close() }
    }
}
