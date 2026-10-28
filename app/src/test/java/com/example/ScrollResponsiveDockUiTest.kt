package com.example

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollResponsiveDockUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var dock: ScrollDockState
    private lateinit var dockProgress: State<Float>
    private var plays = 0
    private var expanded = 0
    private var skips = 0
    private var selected = 0
    private var playerCompositions = 0
    private val song = MusicTrack("test", "A long song title to test ellipsis", "Artist name", "", 180000, "", "")

    private fun show(miniVisible: Boolean = true, plainScroll: Boolean = false, light: Boolean = false,
        rtl: Boolean = false, fontScale: Float = 1f) = compose.setContent {
        MaterialTheme {
            val colors = (if (light) LightAppColors else DarkAppColors).copy(dynamicGlassEnabled = false)
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides false,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                dock = rememberScrollDockState()
                val progress = rememberScrollDockProgress(dock)
                dockProgress = progress
                var tab by remember { mutableIntStateOf(0) }
                val navigate: (Int) -> Unit = { tab = it; selected = it; dock.expand() }
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                Box(Modifier.fillMaxSize().background(colors.scaffoldBackground).testTag("dock_review")) {
                    Box(Modifier.fillMaxSize().nestedScroll(dock)) {
                        if (plainScroll) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("scroll_page")) {
                            repeat(40) { Text("Page row $it", Modifier.fillMaxWidth().height(72.dp)) }
                        } else LazyColumn(Modifier.fillMaxSize().testTag("scroll_page")) {
                            items(40) { Text("Page row $it", Modifier.fillMaxWidth().height(72.dp)) }
                        }
                    }
                    AdaptiveBottomControls(landscape, Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        collapseProgress = progress,
                        miniPlayer = {
                            if (miniVisible) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                                SideEffect { playerCompositions++ }
                                MiniPlayer(PlayerUiState(currentTrack = song), { plays++ }, { skips++ }, {}, { expanded++ },
                                    compactProgress = progress, compact = dock.isCompact)
                            }
                        }, dock = {
                            FloatingPillBottomBar(tab, navigate, compactProgress = progress, compact = dock.isCompact)
                        })
                }
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    // Begin within page content, above the floating controls' real touch surfaces.
    private fun down() = compose.onNodeWithTag("scroll_page").performTouchInput {
        swipe(androidx.compose.ui.geometry.Offset(centerX, height * 0.65f),
            androidx.compose.ui.geometry.Offset(centerX, height * 0.2f))
    }
    private fun up() = compose.onNodeWithTag("scroll_page").performTouchInput {
        swipe(androidx.compose.ui.geometry.Offset(centerX, height * 0.2f),
            androidx.compose.ui.geometry.Offset(centerX, height * 0.65f))
    }

    private fun assertCompact(rtl: Boolean = false) {
        compose.runOnIdle { assertTrue(dock.isCompact) }
        val mini = bounds("mini_player_container")
        val bar = bounds("floating_bottom_navigation_bar")
        compose.onNodeWithTag("compact_dock_search").assertDoesNotExist()
        assertEquals(with(compose.density) { 120.dp.toPx() }, bar.width, 1f)
        assertEquals(bar.center.y, mini.center.y, 2f)
        if (rtl) {
            assertEquals(with(compose.density) { 8.dp.toPx() }, bar.left - mini.right, 1f)
        } else {
            assertEquals(with(compose.density) { 8.dp.toPx() }, mini.left - bar.right, 1f)
        }
        for (title in listOf("Home", "Search")) {
            val icon = compose.onNodeWithContentDescription(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$title remains inside the connected navigation pill", icon.left >= bar.left && icon.right <= bar.right)
            assertEquals(icon.width, icon.height, 0.6f)
        }
        val libraryTag = "nav_tab_library_${if (selected == 2) "active" else "inactive"}"
        compose.onNodeWithTag(libraryTag).assertDoesNotExist()
        val viewport = bounds("dock_review")
        assertEquals(viewport.center.x, (minOf(bar.left, mini.left) + maxOf(bar.right, mini.right)) / 2f, 1f)
        val play = bounds("mini_player_play_pause")
        assertTrue(play.left >= mini.left && play.right <= mini.right)
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test fun realListScrollLowersAndNarrowsPlayerAndRestoresOnReverse() {
        show()
        val original = bounds("mini_player_container")
        down()
        assertCompact()
        val compact = bounds("mini_player_container")
        assertEquals(with(compose.density) { 227.8.dp.toPx() }, compact.width, 1f)
        assertTrue(compact.center.y > original.center.y)
        assertTrue(compact.width < original.width)
        val indicator = java.io.File.createTempFile("compact-selected-pill-", ".png")
        try {
            compose.onNodeWithTag("nav_tab_home_active").captureRoboImage(indicator.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val image = javax.imageio.ImageIO.read(indicator)
            val accent = DarkAppColors.primaryAccent
            var left = image.width; var top = image.height; var right = -1; var bottom = -1
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = java.awt.Color(image.getRGB(x, y), true)
                if (kotlin.math.abs(pixel.red - accent.red * 255) < 8 &&
                    kotlin.math.abs(pixel.green - accent.green * 255) < 8 &&
                    kotlin.math.abs(pixel.blue - accent.blue * 255) < 8) {
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("The selected fill must be visible", right > left && bottom > top)
            assertTrue("The compact selected fill must be circular", kotlin.math.abs((right - left) - (bottom - top)) <= 2)
            assertTrue("The circle is centered inside the unchanged touch height", top > 0 && bottom < image.height - 1)
        } finally { indicator.delete() }
        compose.onNodeWithTag("mini_player_play_pause").performClick()
        assertEquals(1, plays)
        assertEquals(0, expanded)
        compose.onNodeWithTag("mini_player_container").performClick()
        assertEquals(1, expanded)
        val file = java.io.File("build/reports/dock-review/compact.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag("dock_review").captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        up()
        compose.runOnIdle { assertFalse(dock.isCompact) }
        assertEquals(original.width, bounds("mini_player_container").width, 1f)
        compose.onNodeWithTag("mini_player_next").assertIsDisplayed().performClick()
        assertEquals(1, skips)
    }

    @Test fun compactControlsShowOnlyHomeAndSearchAndRestoreLibraryWhenExpanded() {
        show()
        down()
        assertCompact()
        compose.onNodeWithTag("nav_tab_search_inactive").performClick()
        assertEquals(1, selected)
        compose.runOnIdle { assertFalse(dock.isCompact) }
        up()
        down()
        assertCompact()
        compose.onNodeWithTag("nav_tab_home_inactive").performClick()
        assertEquals(0, selected)
        compose.onNodeWithTag("nav_tab_library_inactive").assertIsDisplayed().performClick()
        assertEquals(2, selected)
        up()
        down()
        assertCompact()
        compose.onNodeWithTag("nav_tab_home_inactive").performClick()
        assertEquals(0, selected)
    }

    @Test @Config(sdk = [31]) fun ordinarySettingsStyleScrollAlsoDrivesDockOnAndroid12() {
        show(plainScroll = true)
        down()
        assertCompact()
        up()
        compose.runOnIdle { assertFalse(dock.isCompact) }
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land") fun landscapeCompactControlsFitAndRestoreOriginalLayout() {
        show()
        val original = bounds("mini_player_container")
        down()
        assertCompact()
        assertEquals(original.width * 0.85f, bounds("mini_player_container").width, 1f)
        up()
        assertEquals(original.width, bounds("mini_player_container").width, 1f)
    }

    @Test @Config(qualifiers = "w320dp-h800dp-port") fun narrowLightThemeAndLargeTextKeepTransportWithinPlayer() {
        show(light = true, fontScale = 1.5f)
        down()
        assertCompact()
        assertEquals(with(compose.density) { 159.8.dp.toPx() }, bounds("mini_player_container").width, 1f)
        compose.onNodeWithTag("mini_player_play_pause").performClick()
        assertEquals(1, plays)
    }

    @Test fun rightToLeftControlsDoNotOverlap() {
        show(rtl = true)
        down()
        assertCompact(rtl = true)
    }

    @Test fun noPlaybackSessionCentersOnlyConnectedNavigationWithoutEmptyPlayerSpace() {
        show(miniVisible = false)
        down()
        compose.runOnIdle { assertTrue(dock.isCompact) }
        compose.onNodeWithTag("mini_player_container").assertDoesNotExist()
        compose.onNodeWithTag("compact_dock_search").assertDoesNotExist()
        assertEquals(bounds("dock_review").center.x, bounds("floating_bottom_navigation_bar").center.x, 1f)
        compose.onNodeWithTag("nav_tab_library_inactive").assertDoesNotExist()
        compose.onNodeWithTag("nav_tab_home_active").performClick()
        compose.onNodeWithTag("nav_tab_library_inactive").performClick()
        assertEquals(2, selected)
    }

    @Test fun animationFramesDoNotRecomposePlayerAndReversalDoesNotSnap() {
        show()
        compose.mainClock.autoAdvance = false
        // Inject the consumed-scroll event without gesture-dispatch clock advancement.
        compose.runOnIdle {
            dock.onPostScroll(androidx.compose.ui.geometry.Offset(0f, -100f),
                androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        val middle = bounds("mini_player_container")
        compose.mainClock.advanceTimeBy(64)
        val later = bounds("mini_player_container")
        assertTrue(later.center.y > middle.center.y)
        compose.mainClock.advanceTimeBy(80)
        assertTrue("The shared dock motion should still be visibly progressing after 200 ms: ${dockProgress.value}",
            dockProgress.value in 0.1f..0.85f)
        bounds("mini_player_container")
        val compositions = playerCompositions
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            bounds("mini_player_container")
            assertEquals(compositions, playerCompositions)
        }
        val beforeReverse = bounds("mini_player_container")
        compose.runOnIdle { dock.expand() }
        compose.mainClock.advanceTimeByFrame()
        val reversed = bounds("mini_player_container")
        assertTrue(kotlin.math.abs(reversed.center.y - beforeReverse.center.y) < 20f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(bounds("mini_player_container").bottom < bounds("floating_bottom_navigation_bar").top)
    }

    @Test fun collapsingAndExpandingDoNotSnapAcrossTheLastPixels() = checkSettlingFrames()

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapeDockAlsoSettlesWithoutAnEndpointAdjustment() = checkSettlingFrames()

    private fun checkSettlingFrames() {
        show()
        compose.mainClock.autoAdvance = false
        fun checkEndpoint(target: Float) {
            var previous = dockProgress.value
            var previousMini = bounds("mini_player_container")
            var previousBar = bounds("floating_bottom_navigation_bar")
            repeat(160) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val current = dockProgress.value
                val mini = bounds("mini_player_container")
                val bar = bounds("floating_bottom_navigation_bar")
                if (current == target && previous != target) {
                    assertTrue("The spring must not discard a visible final distance: $previous -> $current",
                        kotlin.math.abs(current - previous) <= .0005f)
                    for ((before, after) in listOf(previousMini to mini, previousBar to bar)) {
                        assertEquals("No horizontal endpoint correction", before.left, after.left, 1.1f)
                        assertEquals("No vertical endpoint correction", before.top, after.top, 1.1f)
                        assertEquals(before.width, after.width, 1.1f)
                        assertEquals(before.height, after.height, 1.1f)
                    }
                    return
                }
                previous = current
                previousMini = mini
                previousBar = bar
            }
            fail("Dock did not reach its endpoint")
        }
        compose.runOnIdle {
            dock.onPostScroll(androidx.compose.ui.geometry.Offset(0f, -100f),
                androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput)
        }
        checkEndpoint(1f)
        compose.runOnIdle { dock.expand() }
        checkEndpoint(0f)
    }
}
