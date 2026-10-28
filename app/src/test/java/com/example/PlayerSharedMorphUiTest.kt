package com.example

import android.app.Application
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.components.*
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render actual recorded layers to catch duplicate artwork, alpha-zero first
 * layout, clipping and handoff errors that geometry-only tests cannot detect. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerSharedMorphUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var visibility: MutableTransitionState<Boolean>
    private lateinit var motion: PlayerExpansionMotion
    private var miniArtwork = Rect.Zero
    private var expandedArtwork = Rect.Zero
    private var ready by mutableStateOf(true)
    private var recording by mutableStateOf("one")
    private var color by mutableStateOf(Color.Magenta)
    private var expandedDraws = 0
    private var expandedCompositions = 0
    private lateinit var sceneLifecycle: Lifecycle
    private var miniClicks = 0
    private var miniBottom by mutableStateOf(24.dp)
    private var expandedRevision by mutableIntStateOf(0)

    private fun show(fullscreen: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            visibility = remember { MutableTransitionState(false) }
            motion = remember { PlayerExpansionMotion() }
            val visual = rememberPlayerHandoff(recording to color, visibility)
            motion.trackId = visual.first
            CompositionLocalProvider(LocalPlayerExpansionMotion provides motion, LocalVisualEffectsActive provides false) {
                Box(Modifier.fillMaxSize().background(Color(0xff202020)).testTag("shared_stage")) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = miniBottom)
                        .fillMaxWidth().height(72.dp)
                        .playerMiniTransition(motion).clickable { visibility.targetState = true }.testTag("shared_mini")) {
                        Box(Modifier.matchParentSize()
                            .playerMorphElement(PlayerMorphSide.MINI, PlayerMorphPart.SURFACE, visual.first)
                            .clip(RoundedCornerShape(36.dp)).background(Color.DarkGray))
                        Box(Modifier.padding(start = 12.dp).align(Alignment.CenterStart).size(44.dp)
                            .onGloballyPositioned { miniArtwork = it.boundsInRoot() }
                            .playerMorphElement(PlayerMorphSide.MINI, PlayerMorphPart.ARTWORK, visual.first, 12.dp)
                            .clip(RoundedCornerShape(12.dp)).background(Color.Magenta))
                        Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(44.dp)
                            .playerMorphMiniControls(PlayerMorphPart.PLAY_PAUSE, visual.first)
                            .background(Color.Yellow).clickable { miniClicks++ }.testTag("shared_mini_control"))
                    }
                    PlayerExpansionOverlay(visibility, motion, { motion.miniBounds }) {
                        val lifecycle = LocalLifecycleOwner.current.lifecycle
                        SideEffect { expandedCompositions++; sceneLifecycle = lifecycle }
                        Box(Modifier.fillMaxSize().background(Color(0xff152538))) {
                            key(expandedRevision) {
                                Box(Modifier.align(Alignment.TopCenter).padding(top = if (expandedRevision == 0) 90.dp else 220.dp)
                                    .size(if (expandedRevision == 0) 260.dp else 180.dp, if (fullscreen) 420.dp else 260.dp)
                                    .onGloballyPositioned { expandedArtwork = it.boundsInRoot() }
                                    .playerMorphElement(PlayerMorphSide.EXPANDED, PlayerMorphPart.ARTWORK,
                                        visual.first, if (fullscreen) 0.dp else 24.dp, presentation = if (fullscreen) 1 else 0,
                                        ready = ready)
                                    .clip(RoundedCornerShape(if (fullscreen) 0.dp else 24.dp))
                                    .background(visual.second).drawWithContent { expandedDraws++; drawContent() })
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    private fun advanceUntil(condition: () -> Boolean) {
        repeat(80) { frame(); if (condition()) return }
        fail("Morph did not reach target: p=${motion.expandedFraction}, idle=${visibility.isIdle}")
    }
    private fun pixels(): java.awt.image.BufferedImage {
        val file = java.io.File.createTempFile("shared-player-", ".png")
        return try {
            compose.onNodeWithTag("shared_stage").captureRoboImage(file.absolutePath,
                RoborazziOptions(taskType = RoborazziTaskType.Record))
            javax.imageio.ImageIO.read(file)
        } finally { file.delete() }
    }
    private fun assertOneMovingArtwork(destination: Rect = expandedArtwork) {
        val expected = lerp(miniArtwork, destination, motion.expandedFraction)
        val image = pixels()
        val viewport = checkNotNull(motion.viewport)
        var left = image.width; var right = -1; var top = image.height; var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = java.awt.Color(image.getRGB(x, y))
            if (pixel.red > 240 && pixel.blue > 240 && pixel.green < 10) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("One artwork must remain drawn during the morph", right > left && bottom > top)
        val found = Rect(viewport.left + left * viewport.width / image.width,
            viewport.top + top * viewport.height / image.height,
            viewport.left + (right + 1) * viewport.width / image.width,
            viewport.top + (bottom + 1) * viewport.height / image.height)
        assertEquals("No stationary/duplicate mini artwork may remain", expected.left, found.left, 2f)
        assertEquals(expected.top, found.top, 2f)
        assertEquals(expected.right, found.right, 2f)
        assertEquals(expected.bottom, found.bottom, 2f)
    }

    @Test fun artworkTravelsBothWaysWithoutDuplicatesOrPerFrameRecording() {
        show()
        compose.onNodeWithTag("shared_mini").performClick()
        advanceUntil { motion.expandedFraction in .35f.. .6f }
        assertTrue(expandedArtwork.width > miniArtwork.width)
        assertOneMovingArtwork()
        val draws = expandedDraws
        val compositions = expandedCompositions
        repeat(5) { frame() }
        assertEquals("The full artwork display list must be reused between animation frames", draws, expandedDraws)
        assertEquals("Progress must not recompose the expanded scene", compositions, expandedCompositions)
        advanceUntil { visibility.isIdle }
        compose.runOnIdle { visibility.targetState = false }
        advanceUntil { motion.expandedFraction in .35f.. .6f }
        assertOneMovingArtwork()
        advanceUntil { visibility.isIdle }
        assertEquals("Retained scene collectors must be stopped while hidden", Lifecycle.State.CREATED, sceneLifecycle.currentState)
        compose.onNodeWithTag("shared_mini_control").performTouchInput { click() }
        assertEquals("No invisible expanded overlay may intercept the mini control", 1, miniClicks)
        compose.runOnIdle { miniBottom = 100.dp }
        frame()
        compose.onNodeWithTag("shared_mini").performClick()
        advanceUntil { motion.expandedFraction in .35f.. .6f }
        assertOneMovingArtwork()
        assertEquals("A later opening must start from the new actual dock, not an old rebase point",
            lerp(checkNotNull(motion.miniBounds), checkNotNull(motion.viewport), motion.expandedFraction), motion.containerBounds())
        advanceUntil { visibility.isIdle }
    }

    @Test fun missingExpandedImageStillUsesItsMeasuredDestinationAndCanFinishLoading() {
        ready = false
        color = Color.Cyan
        show()
        compose.onNodeWithTag("shared_mini").performClick()
        advanceUntil { motion.expandedFraction in .35f.. .55f }
        assertOneMovingArtwork()
        compose.runOnIdle { ready = true }
        var upgraded = false
        repeat(4) {
            if (!upgraded) {
                frame()
                val image = pixels()
                val target = lerp(miniArtwork, expandedArtwork, motion.expandedFraction)
                val viewport = checkNotNull(motion.viewport)
                val pixel = java.awt.Color(image.getRGB(((target.center.x - viewport.left) * image.width / viewport.width).toInt(),
                    ((target.center.y - viewport.top) * image.height / viewport.height).toInt()))
                upgraded = pixel.green > 240 && pixel.blue > 240
            }
        }
        assertTrue("An initially unready destination must publish its image during the morph, not only at its endpoint: draws=$expandedDraws p=${motion.expandedFraction}",
            upgraded && motion.transitioning)
        advanceUntil { visibility.isIdle }
    }

    @Test fun fullscreenArtworkUsesTheSameSingleViewportAndReversesWithoutASnap() {
        show(fullscreen = true)
        compose.onNodeWithTag("shared_mini").performClick()
        advanceUntil { motion.expandedFraction in .4f.. .6f }
        assertOneMovingArtwork()
        val beforeProgress = motion.expandedFraction
        compose.runOnIdle { visibility.targetState = false }
        frame()
        val after = checkNotNull(motion.containerBounds())
        assertTrue("Reversal must retain current progress and velocity: $beforeProgress -> ${motion.expandedFraction}",
            kotlin.math.abs(motion.expandedFraction - beforeProgress) < .15f)
        val expected = lerp(checkNotNull(motion.miniBounds), checkNotNull(motion.viewport), motion.expandedFraction)
        assertEquals("Reversal must stay on the same measured container path", expected, after)
        advanceUntil { visibility.isIdle }
        compose.onNodeWithTag("shared_mini").performTouchInput { click() }
        advanceUntil { motion.expandedFraction in .35f.. .6f }
        assertOneMovingArtwork()
        advanceUntil { visibility.isIdle }
    }

    @Test fun changedRecordingDoesNotReplaceTheSharedImageMidTransition() {
        show()
        compose.onNodeWithTag("shared_mini").performClick()
        advanceUntil { motion.expandedFraction in .35f.. .55f }
        assertOneMovingArtwork()
        compose.runOnIdle { recording = "two"; color = Color.Cyan }
        frame()
        assertOneMovingArtwork()
        advanceUntil { visibility.isIdle }
        val image = pixels()
        val viewport = checkNotNull(motion.viewport)
        val center = expandedArtwork.center - viewport.topLeft
        val pixel = java.awt.Color(image.getRGB((center.x * image.width / viewport.width).toInt(),
            (center.y * image.height / viewport.height).toInt()))
        assertTrue("Native artwork must receive the new recording after handoff", pixel.green > 240 && pixel.blue > 240)
    }

    @Test fun tappingTheVisibleClosingSurfaceReversesItWithoutRestartingTheScene() {
        show()
        compose.onNodeWithTag("shared_mini").performClick()
        advanceUntil { visibility.isIdle }
        compose.runOnIdle { visibility.targetState = false }
        advanceUntil { motion.expandedFraction in .4f.. .6f }
        val progress = motion.expandedFraction
        val point = checkNotNull(motion.containerBounds()).center - checkNotNull(motion.viewport).topLeft
        compose.onNodeWithTag("shared_stage").performTouchInput { click(point) }
        frame()
        assertTrue("A tap that began during closing should reopen", visibility.targetState)
        assertTrue(kotlin.math.abs(motion.expandedFraction - progress) < .2f)
        advanceUntil { visibility.isIdle }
        assertTrue(visibility.currentState)
    }

    @Test fun miniControlsFadeInAboveTheContractingSurfaceBeforeLanding() {
        show()
        compose.onNodeWithTag("shared_mini").performClick()
        advanceUntil { visibility.isIdle }
        compose.runOnIdle { visibility.targetState = false }
        advanceUntil { motion.expandedFraction in .04f.. .07f }
        val image = pixels()
        val viewport = checkNotNull(motion.viewport)
        val mini = checkNotNull(motion.miniBounds)
        val x = mini.right - 34f * compose.density.density
        val y = mini.center.y
        val pixel = java.awt.Color(image.getRGB(((x - viewport.left) * image.width / viewport.width).toInt(),
            ((y - viewport.top) * image.height / viewport.height).toInt()))
        assertTrue("The landing button must already be visible, not pop in after the overlay disappears: $pixel",
            pixel.red > 180 && pixel.green > 180 && pixel.blue < 30)
        advanceUntil { visibility.isIdle }
        compose.onNodeWithTag("shared_mini_control").performTouchInput { click() }
        assertEquals(1, miniClicks)
    }

    @Test fun replacedDestinationLayoutRebasesTheLiveArtworkWithoutSnappingOrKeepingAnObsoleteAnchor() {
        show()
        compose.onNodeWithTag("shared_mini").performClick()
        advanceUntil { motion.expandedFraction in .35f.. .5f }
        assertOneMovingArtwork()
        val previousDestination = expandedArtwork
        compose.runOnIdle { expandedRevision = 1 }
        frame()
        assertNotEquals(previousDestination, expandedArtwork)
        // At the replacement frame, the old path's current position is preserved.
        assertOneMovingArtwork(previousDestination)
        advanceUntil { visibility.isIdle }
        compose.runOnIdle { visibility.targetState = false }
        advanceUntil { motion.expandedFraction in .4f.. .6f }
        assertOneMovingArtwork()
        advanceUntil { visibility.isIdle }
    }
}
