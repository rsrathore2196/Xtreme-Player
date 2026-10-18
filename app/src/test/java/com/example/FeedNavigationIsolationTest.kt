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

    @Test fun pendingOrPublishingFeedDoesNotBlockDockRetargetingOrResetRetainedHomeScroll() {
        val feedReady = MutableStateFlow(false)
        lateinit var pager: PagerState
        lateinit var homeScroll: androidx.compose.foundation.lazy.LazyListState
        val mounted = mutableSetOf<Int>()
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
                    DisposableEffect(page) { mounted.add(page); onDispose { mounted.remove(page) } }
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
        compose.runOnIdle { assertEquals(setOf(0), mounted) }
        compose.onNodeWithTag("feed").performTouchInput { swipeUp() }
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
    }
}
