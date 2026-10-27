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
        assertEquals(with(compose.density) { 108.dp.toPx() }, bar.width, 1f)
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
        assertTrue(compact.center.y > original.center.y)
        assertTrue(compact.width < original.width)
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
        up()
        assertEquals(original.width, bounds("mini_player_container").width, 1f)
    }

    @Test @Config(qualifiers = "w320dp-h800dp-port") fun narrowLightThemeAndLargeTextKeepTransportWithinPlayer() {
        show(light = true, fontScale = 1.5f)
        down()
        assertCompact()
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
        down()
        compose.mainClock.advanceTimeBy(64)
        val middle = bounds("mini_player_container")
        val compositions = playerCompositions
        compose.mainClock.advanceTimeBy(64)
        assertEquals(compositions, playerCompositions)
        val later = bounds("mini_player_container")
        assertTrue(later.center.y > middle.center.y)
        compose.runOnIdle { dock.expand() }
        compose.mainClock.advanceTimeByFrame()
        val reversed = bounds("mini_player_container")
        assertTrue(kotlin.math.abs(reversed.center.y - later.center.y) < 20f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(bounds("mini_player_container").bottom < bounds("floating_bottom_navigation_bar").top)
    }
}
