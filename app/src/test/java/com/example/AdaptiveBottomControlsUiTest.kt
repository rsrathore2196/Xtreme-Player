package com.example

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.DarkAppColors
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
class AdaptiveBottomControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private var playTaps = 0
    private var nextTaps = 0
    private var selected = 0
    private val song = MusicTrack("test", "Long song title stays within the mini-player", "Singer", "", 180000, "", "")

    private fun show(miniVisible: Boolean = true) = compose.setContent { MaterialTheme {
        CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
            LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
            LocalArtworkRequestsActive provides false) {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            var tab by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxSize()) {
                AdaptiveBottomControls(landscape, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), miniPlayer = {
                    if (miniVisible) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        MiniPlayer(PlayerUiState(currentTrack = song), { playTaps++ }, { nextTaps++ }, {}, {})
                    }
                }, dock = { FloatingPillBottomBar(tab, { tab = it; selected = it }) })
            }
        }
    } }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun px(value: Float) = with(compose.density) { value.dp.toPx() }
    private fun assertControls() {
        val mini = bounds("mini_player_container")
        for (tag in listOf("mini_player_play_pause", "mini_player_next")) {
            val button = bounds(tag)
            assertTrue("$tag must fit inside the mini-player", button.left >= mini.left && button.right <= mini.right + 1f)
            compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        }
        assertEquals(1, playTaps)
        assertEquals(1, nextTaps)
    }

    @Test fun portraitKeepsFullWidthMiniPlayerAboveTheDock() {
        show()
        assertEquals(px(360f), bounds("mini_player_container").width, 1f)
        assertTrue(bounds("mini_player_container").bottom < bounds("floating_bottom_navigation_bar").top)
        compose.onNodeWithTag("landscape_bottom_controls").assertDoesNotExist()
        assertControls()
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapeUsesOneThirdMiniPlayerBesideDockAndKeepsAllControlsWorking() {
        show()
        assertEquals(px(700f / 3f), bounds("mini_player_container").width, 1f)
        assertControls()
        checkDock()
        val file = java.io.File("build/reports/landscape-review/bottom-controls.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag("landscape_bottom_controls").captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    @Test @Config(qualifiers = "w600dp-h360dp-land")
    fun narrowLandscapePreservesTouchTargetsAndAvoidsOverlap() {
        show()
        assertTrue(bounds("mini_player_container").width >= px(220f))
        assertControls()
        checkDock()
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapeWithNoSessionCentersOnlyTheDock() {
        show(miniVisible = false)
        compose.onNodeWithTag("mini_player_container").assertDoesNotExist()
        assertEquals(px(370f), bounds("floating_bottom_navigation_bar").center.x, 1f)
        checkDock()
    }

    private fun checkDock() {
        for (tab in listOf(1, 2, 0)) {
            val title = listOf("home", "search", "library")[tab]
            compose.onNodeWithTag("nav_tab_${title}_inactive").performClick()
            compose.waitForIdle()
            assertEquals(tab, selected)
            val dock = bounds("floating_bottom_navigation_bar")
            if (compose.onAllNodesWithTag("mini_player_container").fetchSemanticsNodes().isNotEmpty()) {
                val mini = bounds("mini_player_container")
                assertTrue("Dock and player must not overlap", mini.right <= dock.left || mini.bottom <= dock.top)
            }
        }
    }
}
