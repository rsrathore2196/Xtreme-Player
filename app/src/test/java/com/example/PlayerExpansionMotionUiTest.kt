package com.example

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerExpansionMotionUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var visible: MutableTransitionState<Boolean>
    private lateinit var motion: PlayerExpansionMotion
    private var playerCompositions = 0
    private var playTaps = 0
    private var nextTaps = 0

    private fun show(light: Boolean = false) {
        compose.mainClock.autoAdvance = false
        val song = MusicTrack("motion", "Song", "Artist", "Album", 180_000, "", "")
        compose.setContent { MaterialTheme {
            val colors = (if (light) LightAppColors else DarkAppColors).copy(dynamicGlassEnabled = false)
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides false,
                LocalArtworkRequestsActive provides false, LocalVisualEffectsActive provides false) {
                visible = remember { MutableTransitionState(false) }
                motion = remember { PlayerExpansionMotion() }
                var bounds by remember { mutableStateOf<Rect?>(null) }
                Box(Modifier.fillMaxSize().background(colors.scaffoldBackground).testTag("motion_stage")) {
                    AdaptiveBottomControls(
                        landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                        collapseProgress = remember { mutableStateOf(0f) },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp),
                        miniPlayer = {
                            androidx.compose.animation.AnimatedVisibility(!visible.targetState || !visible.isIdle,
                                enter = EnterTransition.None, exit = ExitTransition.None,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                                    .onGloballyPositioned { if (it.size.width > 0 && it.size.height > 0) bounds = it.boundsInRoot() }
                                    .then(if (visible.targetState) Modifier.clearAndSetSemantics { } else Modifier)) {
                                    MiniPlayer(PlayerUiState(currentTrack = song), { playTaps++ }, { nextTaps++ }, {},
                                        { visible.targetState = true }, modifier = Modifier.playerMiniTransition(motion))
                            }
                        },
                        dock = {
                            AnimatedVisibility(!visible.targetState || !visible.isIdle,
                                enter = EnterTransition.None, exit = ExitTransition.None,
                                modifier = Modifier.playerDockTransition(motion)) {
                                FloatingPillBottomBar(0, {})
                            }
                        }
                    )
                    PlayerExpansionOverlay(visible, motion, { bounds }, Modifier.fillMaxSize()) {
                        SideEffect { playerCompositions++ }
                        ExpandedPlayerScreen(PlayerUiState(currentTrack = song), { visible.targetState = false }, {}, {}, {}, {}, {}, {}, {}, {},
                            onAddToPlaylist = {}, isScreenVisible = visible.targetState)
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun portraitOpeningAndClosingShareAContinuousSpringAndLandAtTheMiniPlayer() = checkRoundTrip()
    @Test fun lightThemeKeepsTheSameRoundTripAndWorkingMiniControls() = checkRoundTrip(light = true)
    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeTargetsTheActualNarrowMiniPlayer() = checkRoundTrip()

    private fun checkRoundTrip(light: Boolean = false) {
        show(light)
        val miniBefore = compose.onNodeWithTag("mini_player_container").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("motion_stage").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("mini_player_container").performClick()
        frame()
        val first = compose.onNodeWithTag("expanded_player_transition").fetchSemanticsNode().boundsInRoot
        assertTrue("Expansion begins at the dock, not below the window: $first / $miniBefore", first.top >= viewport.height * .45f)
        repeat(15) { frame() }
        assertTrue("Opening must retain a visible journey instead of snapping to the full viewport", motion.fraction > .01f)
        var openingOvershoot = 0f
        repeat(100) { frame(); openingOvershoot = minOf(openingOvershoot, motion.fraction) }
        assertTrue("The expanded landing must have a perceptible damped bounce", openingOvershoot < -.04f)
        assertTrue(visible.isIdle && visible.currentState)
        assertEquals(0f, motion.fraction, .001f)
        val expanded = compose.onNodeWithTag("expanded_player_transition").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.left, expanded.left, 1f)
        assertEquals(viewport.top, expanded.top, 1f)
        assertEquals(viewport.width, expanded.width, 1f)
        assertEquals(viewport.height, expanded.height, 1f)
        val compositions = playerCompositions
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        frame()
        var closingOvershoot = 1f
        var widestMini = miniBefore.width
        var tappedAtLanding = false
        var closingFrames = 1
        var contactFrame = 0
        fun checkLandingTouch() {
            closingFrames++
            if (motion.fraction > 1f && !tappedAtLanding) {
                contactFrame = closingFrames
                compose.onNodeWithTag("mini_player_play_pause").performTouchInput { click() }
                assertEquals("The invisible expanded surface must not intercept the landed mini player's button", 1, playTaps)
                tappedAtLanding = true
            }
        }
        repeat(16) {
            frame()
            closingOvershoot = maxOf(closingOvershoot, motion.fraction)
            widestMini = maxOf(widestMini, compose.onNodeWithTag("mini_player_container").fetchSemanticsNode().boundsInRoot.width)
            checkLandingTouch()
        }
        assertTrue("Closing should still be travelling after 250ms", motion.fraction < .97f)
        compose.onNodeWithTag("expanded_player_transition").assertExists()
        compose.onNodeWithTag("motion_stage").captureRoboImage("/tmp/player-minimize-${if (light) "light" else "dark"}-${viewport.width.toInt()}.png",
            RoborazziOptions(taskType = RoborazziTaskType.Record))
        repeat(100) {
            frame()
            closingOvershoot = maxOf(closingOvershoot, motion.fraction)
            widestMini = maxOf(widestMini, compose.onNodeWithTag("mini_player_container").fetchSemanticsNode().boundsInRoot.width)
            checkLandingTouch()
        }
        assertTrue("Dock contact should occur after a smooth, bounded journey: $contactFrame frames", contactFrame in 18..40)
        assertTrue("Mini landing reads the same perceptible spring overshoot", closingOvershoot > 1.04f)
        assertTrue(tappedAtLanding)
        assertTrue("The mini player visibly springs after reaching its dock", widestMini > miniBefore.width * 1.025f)
        assertTrue(visible.isIdle && !visible.currentState)
        compose.onNodeWithTag("expanded_player_transition").assertDoesNotExist()
        val miniAfter = compose.onNodeWithTag("mini_player_container").fetchSemanticsNode().boundsInRoot
        assertEquals(miniBefore.left, miniAfter.left, 1f)
        assertEquals(miniBefore.top, miniAfter.top, 1f)
        assertEquals(miniBefore.width, miniAfter.width, 1f)
        assertEquals(miniBefore.height, miniAfter.height, 1f)
        assertTrue("Motion frames must not recompose the heavy player tree", playerCompositions - compositions <= 3)
        compose.onNodeWithTag("mini_player_play_pause").performClick()
        compose.onNodeWithTag("mini_player_next").performClick()
        assertEquals(2, playTaps)
        assertEquals(1, nextTaps)
    }

    @Test fun rapidReversalsDoNotSnapDisposeThePlayerOrDuplicateItsContent() {
        show()
        compose.onNodeWithTag("mini_player_container").performClick()
        repeat(7) { frame() }
        val before = motion.fraction
        compose.runOnIdle { visible.targetState = false }
        frame()
        assertTrue(abs(motion.fraction - before) < .3f)
        repeat(5) { frame() }
        val returning = motion.fraction
        compose.runOnIdle { visible.targetState = true }
        frame()
        assertTrue(abs(motion.fraction - returning) < .3f)
        compose.onAllNodesWithTag("expanded_player_transition").assertCountEquals(1)
        repeat(110) { frame() }
        assertTrue(visible.isIdle && visible.currentState)
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        repeat(110) { frame() }
        assertTrue(visible.isIdle && !visible.currentState)
        compose.onNodeWithTag("expanded_player_transition").assertDoesNotExist()
        compose.onNodeWithTag("mini_player_container").assertIsDisplayed()
    }
}
