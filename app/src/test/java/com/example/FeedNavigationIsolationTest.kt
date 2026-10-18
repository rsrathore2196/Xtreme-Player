package com.example

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.AnimatedBottomBar
import com.example.ui.components.AnimatedBottomBarItem
import com.example.ui.components.MainTabPager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedNavigationIsolationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allPagesStayMountedButOnlyVisiblePagesCollectScreenData() {
        val mounts = IntArray(3)
        val collectors = IntArray(3)
        lateinit var pager: PagerState
        var select: (Int) -> Unit = {}
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            var selected by remember { mutableIntStateOf(0) }
            pager = rememberPagerState(pageCount = { 3 })
            select = { selected = it }
            LaunchedEffect(selected) { pager.animateScrollToPage(selected) }
            MainTabPager(pager, pageCovered = false) { page ->
                DisposableEffect(page) { mounts[page]++; onDispose { } }
                val source = remember(page) { flow {
                    collectors[page]++
                    try { emit(page); awaitCancellation() }
                    finally { collectors[page]-- }
                } }
                val value by source.collectAsStateWithLifecycle(-1, minActiveState = Lifecycle.State.STARTED)
                Text("Page $page value $value")
            }
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertArrayEquals(intArrayOf(1, 1, 1), mounts)
            assertArrayEquals(intArrayOf(1, 0, 0), collectors)
        }
        for (target in listOf(2, 1, 0, 1, 2, 0)) {
            compose.runOnIdle { select(target) }
            // Let the platform apply the state write/start the keyed effect before
            // advancing its animation clock (this fixture has no clickable dock).
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1600)
            compose.runOnIdle {
                assertEquals(target, pager.currentPage)
                assertArrayEquals(intArrayOf(1, 1, 1), mounts)
                assertArrayEquals(IntArray(3) { if (it == target) 1 else 0 }, collectors)
            }
        }
    }

    @Test fun pendingOrPublishingFeedDoesNotBlockDockRetargetingOrResetRetainedHomeScroll() {
        val feedReady = MutableStateFlow(false)
        lateinit var pager: PagerState
        lateinit var homeScroll: androidx.compose.foundation.lazy.LazyListState
        val mounted = mutableSetOf<Int>()
        val mounts = IntArray(3)
        val disposals = IntArray(3)
        var selected = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            var tab by remember { mutableIntStateOf(0) }
            pager = rememberPagerState(pageCount = { 3 })
            homeScroll = rememberLazyListState()
            val tabs = remember { listOf(
                AnimatedBottomBarItem(0, "Home", Icons.Filled.Home, Icons.Outlined.Home),
                AnimatedBottomBarItem(1, "Search", Icons.Filled.Search, Icons.Outlined.Search),
                AnimatedBottomBarItem(2, "Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic)) }
            LaunchedEffect(tab) { pager.animateScrollToPage(tab) }
            Box(Modifier.fillMaxSize()) {
                MainTabPager(pager, pageCovered = false) { page ->
                    DisposableEffect(page) { mounted.add(page); mounts[page]++; onDispose { mounted.remove(page); disposals[page]++ } }
                    if (page == 0) {
                        // No timer or network result releases navigation. The actual host
                        // must remain interactive while this feed is still unprepared.
                        val prepared by feedReady.collectAsState()
                        LazyColumn(state = homeScroll, modifier = Modifier.testTag("feed")) {
                            items((0..50).toList(), key = { it }, contentType = { "song_shelf" }) { index ->
                                Text(if (prepared) "Prepared $index" else "Loading $index",
                                    Modifier.fillMaxWidth().height(100.dp))
                            }
                        }
                    } else Text("Destination $page", Modifier.testTag("destination_$page"))
                }
                AnimatedBottomBar(tab, { tab = it; selected = it }, tabs,
                    Modifier.align(Alignment.BottomCenter))
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(setOf(0, 1, 2), mounted) }
        // Start in the visible feed, above the floating dock. Its outer glass
        // now observes touches too, so a gesture beginning behind the dock is
        // a dock interaction rather than a feed scroll.
        compose.onNodeWithTag("feed").performTouchInput { swipeUp(startY = height * 0.7f) }
        compose.mainClock.advanceTimeBy(1200)
        val retainedIndex = compose.runOnIdle { homeScroll.firstVisibleItemIndex }
        assertTrue(retainedIndex > 0)

        compose.onNodeWithTag("nav_tab_search_inactive").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("nav_tab_search_active").assertExists()
        compose.runOnIdle { assertTrue("The pill reacts while the page is still moving", pager.isScrollInProgress) }
        // Retarget before the first spring finishes, while Home has no prepared data.
        compose.onNodeWithTag("nav_tab_library_inactive").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("nav_tab_library_active").assertExists()
        compose.onNodeWithTag("destination_2").assertIsDisplayed()
        compose.runOnIdle { assertFalse(feedReady.value); assertEquals(2, selected) }

        compose.runOnIdle { feedReady.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(2, pager.currentPage); assertEquals(2, selected) }
        compose.onNodeWithTag("nav_tab_home_inactive").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("nav_tab_home_active").assertExists()
        compose.runOnIdle { assertEquals(retainedIndex, homeScroll.firstVisibleItemIndex) }
        compose.onNodeWithText("Prepared $retainedIndex").assertExists()
        compose.runOnIdle {
            assertArrayEquals(intArrayOf(1, 1, 1), mounts)
            assertArrayEquals(intArrayOf(0, 0, 0), disposals)
        }
    }
}
