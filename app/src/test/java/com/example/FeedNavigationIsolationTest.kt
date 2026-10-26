package com.example

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.AnimatedBottomBar
import com.example.ui.components.AnimatedBottomBarItem
import com.example.ui.components.MainTabPager
import com.example.ui.components.RetainedPrimaryTabHost
import com.example.ui.components.SettingsNavigationOverlay
import com.github.takahirom.roborazzi.captureRoboImage
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

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun settingsAndPlaylistCoverageNeverDisposeOrRebuildThePrimaryPages() {
        val mounts = IntArray(3)
        val disposals = IntArray(3)
        var taps = 0
        var covered by mutableStateOf(false)
        var destination by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val pager = rememberPagerState(pageCount = { 3 })
            val transition = androidx.compose.animation.core.updateTransition(destination, label = "test_settings")
            RetainedPrimaryTabHost(pager, transition, covered = covered, inputBlocked = covered) { page ->
                DisposableEffect(page) { mounts[page]++; onDispose { disposals[page]++ } }
                Text("Retained page $page", Modifier.testTag("retained_page_$page").clickable { taps++ })
            }
        } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        repeat(3) { visit ->
            compose.runOnIdle { covered = true; destination = if (visit % 2 == 0) 3 else 0 }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("retained_primary_tabs", useUnmergedTree = true).assert(
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.InvisibleToUser))
            val before = taps
            compose.onNodeWithTag("retained_primary_tabs", useUnmergedTree = true)
                .performTouchInput { click(androidx.compose.ui.geometry.Offset(10f, 10f)) }
            assertEquals("Covered content must not receive taps", before, taps)
            compose.runOnIdle { covered = false; destination = 0 }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("retained_page_0").assertIsDisplayed()
            compose.onNodeWithTag("retained_page_0").performTouchInput { click() }
            assertEquals("Uncovered content must accept taps again", before + 1, taps)
        }
        assertArrayEquals(intArrayOf(1, 1, 1), mounts)
        assertArrayEquals(intArrayOf(0, 0, 0), disposals)
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test fun settingsOpensDirectlyAsOneOpaquePageWithoutHomeBleed() {
        var destination by mutableIntStateOf(0)
        var settingsDraws = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val transition = androidx.compose.animation.core.updateTransition(destination, label = "first_settings_frame")
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Blue)) {
                Text("Home")
                CompositionLocalProvider(com.example.ui.theme.LocalAppColors provides
                    com.example.ui.theme.LightAppColors.copy(scaffoldBackground = androidx.compose.ui.graphics.Color.Red)) {
                SettingsNavigationOverlay(transition) {
                    Box(Modifier.fillMaxSize().drawWithContent { settingsDraws++; drawContent() }
                        .background(androidx.compose.ui.graphics.Color.Red))
                }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { destination = 3 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        fun capturePixel(x: Float = 0.5f): Int {
            val file = java.io.File.createTempFile("settings-first-frame-", ".png")
            return try {
                compose.onRoot().captureRoboImage(file.absolutePath,
                    roborazziOptions = com.github.takahirom.roborazzi.RoborazziOptions(
                        taskType = com.github.takahirom.roborazzi.RoborazziTaskType.Record))
                val pixels = javax.imageio.ImageIO.read(file)
                pixels.getRGB((pixels.width * x).toInt(), pixels.height / 2)
            } finally { file.delete() }
        }
        assertEquals("Settings must open directly, without a Home preview", android.graphics.Color.RED, capturePixel())
        assertEquals("The revealed margins must be opaque too", android.graphics.Color.RED, capturePixel(.01f))
        compose.runOnIdle { assertTrue("There must be only one real Settings presentation", settingsDraws > 0) }
        compose.mainClock.advanceTimeBy(60)
        assertEquals("The entering page must remain opaque", android.graphics.Color.RED, capturePixel(.95f))
        compose.mainClock.advanceTimeBy(340)
        assertEquals(android.graphics.Color.RED, capturePixel())
        compose.runOnIdle { assertTrue(settingsDraws > 0) }
    }

    @Test fun settingsUsesOneOverlayAcrossInterruptedOpeningAndClosing() {
        var destination by mutableIntStateOf(0)
        var settingsMounts = 0
        var settingsDisposals = 0
        val pageMounts = IntArray(3)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val pager = rememberPagerState(pageCount = { 3 })
            val transition = androidx.compose.animation.core.updateTransition(destination, label = "settings_test")
            Box(Modifier.fillMaxSize()) {
                RetainedPrimaryTabHost(pager, transition,
                    covered = destination == 3 && !transition.isRunning,
                    inputBlocked = destination == 3) { page ->
                    DisposableEffect(page) { pageMounts[page]++; onDispose { } }
                    Text("Page $page")
                }
                SettingsNavigationOverlay(transition) {
                    DisposableEffect(Unit) { settingsMounts++; onDispose { settingsDisposals++ } }
                    Box(Modifier.fillMaxSize().testTag("settings_test_panel"))
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("settings_navigation_overlay").assertDoesNotExist()
        compose.runOnIdle { destination = 3 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { destination = 0 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle { destination = 3 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithTag("settings_test_panel").assertCountEquals(1)
        compose.runOnIdle { assertEquals(1, settingsMounts); assertEquals(0, settingsDisposals) }
        compose.runOnIdle { destination = 0 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("settings_navigation_overlay").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, settingsDisposals)
            assertArrayEquals(intArrayOf(1, 1, 1), pageMounts)
        }
    }

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

    @OptIn(ExperimentalFoundationApi::class)
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
            // Robolectric cannot advance Android's frame deadline while this
            // fixture deliberately freezes Compose time to inspect mid-animation
            // retargeting. Disable speculative row prefetch only in the fixture;
            // otherwise AndroidPrefetchScheduler can endlessly repost onIdle.
            // Real scrolling, retained rows and the production pager/dock remain.
            val prefetch = remember { object : LazyListPrefetchStrategy {
                override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {}
                override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {}
                override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {}
            } }
            homeScroll = rememberLazyListState(prefetchStrategy = prefetch)
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
