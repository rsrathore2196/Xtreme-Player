package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.data.model.MusicTrack
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.HomeScreenContent
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.data.model.ServerHomeShelf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class ProgressiveHomeRevealUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private fun shelf(name: String) = ServerHomeShelf(name, name,
        tracks = listOf(MusicTrack(name, "$name song", "Artist", "Album", 180000, "", "")))
    private fun content(restoration: StateRestorationTester? = null) {
        val block: @androidx.compose.runtime.Composable () -> Unit = {
            MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false) {
                // Robolectric has no advancing Android frame deadline while
                // Espresso drains a programmatic scroll. Keep real lazy rows and
                // scrolling, without its speculative prefetch repost loop.
                val prefetch = androidx.compose.runtime.remember {
                    object : androidx.compose.foundation.lazy.LazyListPrefetchStrategy {
                        override fun androidx.compose.foundation.lazy.LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo) {}
                        override fun androidx.compose.foundation.lazy.LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo) {}
                        override fun androidx.compose.foundation.lazy.layout.NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {}
                    }
                }
                list = rememberLazyListState(prefetchStrategy = prefetch)
                scope = rememberCoroutineScope()
                HomeScreenContent(serverShelves = (1..8).map { shelf("Native $it") },
                    listState = list, onTrackClick = { _, _ -> }, onToggleFavorite = {})
            } }
        }
        if (restoration == null) compose.setContent(block) else restoration.setContent(block)
    }
    private fun scrollToLoadedTail() {
        compose.runOnIdle { scope.launch { list.scrollToItem(list.layoutInfo.totalItemsCount - 1) } }
        compose.waitForIdle()
    }

    @Test @Config(qualifiers = "w1920dp-h2400dp-land")
    fun tallLandscapeDoesNotRevealCachedLowerCardsMerelyBecauseTheyWouldFit() {
        content()
        compose.onNodeWithText("Native 1").assertIsDisplayed()
        compose.onNodeWithText("Native 4").assertIsDisplayed()
        compose.onNodeWithText("Native 5").assertDoesNotExist()
        compose.onNodeWithText("Native 7").assertDoesNotExist()
        // A real attempted scroll must work even if the initial four shelves fit.
        compose.onNodeWithTag("home_screen").performTouchInput { swipeUp(durationMillis = 350) }
        compose.onNodeWithText("Native 5").assertExists()
        compose.onNodeWithText("Native 7").assertDoesNotExist()
    }

    @Test fun programmaticScrollAndRecompositionDoNotRevealButUserGestureDoes() {
        content()
        scrollToLoadedTail()
        compose.onNodeWithText("Native 5").assertDoesNotExist()
        compose.onNodeWithTag("home_screen").performTouchInput { swipeUp(durationMillis = 350) }
        scrollToLoadedTail()
        compose.onNodeWithText("Native 5").assertExists()
        compose.onNodeWithText("Native 7").assertDoesNotExist()
    }

    @Test fun recreatedHomeRetainsRevealedSectionsInsteadOfRevealingTheEntireCache() {
        val restoration = StateRestorationTester(compose)
        content(restoration)
        scrollToLoadedTail()
        compose.onNodeWithTag("home_screen").performTouchInput { swipeUp(durationMillis = 350) }
        scrollToLoadedTail()
        compose.onNodeWithText("Native 5").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        scrollToLoadedTail()
        compose.onNodeWithText("Native 5").assertExists()
        compose.onNodeWithText("Native 7").assertDoesNotExist()
    }
}
