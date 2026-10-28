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
import androidx.compose.ui.draw.drawWithContent
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
    private var playerMounts = 0
    private var playerDisposals = 0
    private var playTaps = 0
    private var nextTaps = 0

    private fun show(light: Boolean = false, markTransition: Boolean = false, compact: Boolean = false,
        artwork: Boolean = false) {
        compose.mainClock.autoAdvance = false
        val song = MusicTrack("motion", "Song", "Artist", "Album", 180_000,
            if (artwork) "https://lh3.googleusercontent.com/morph-cover=w800-h800" else "", "")
        compose.setContent { MaterialTheme {
            val colors = (if (light) LightAppColors else DarkAppColors).copy(dynamicGlassEnabled = false, dynamicAlbumGlow = false)
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides false,
                LocalArtworkRequestsActive provides artwork, LocalVisualEffectsActive provides false) {
                visible = remember { MutableTransitionState(false) }
                motion = remember { PlayerExpansionMotion() }
                motion.trackId = song.id
                var bounds by remember { mutableStateOf<Rect?>(null) }
                val dockProgress = remember { mutableStateOf(if (compact) 1f else 0f) }
                CompositionLocalProvider(LocalPlayerExpansionMotion provides motion) {
                Box(Modifier.fillMaxSize().background(colors.scaffoldBackground).testTag("motion_stage")) {
                    AdaptiveBottomControls(
                        landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                        collapseProgress = dockProgress,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp),
                        miniPlayer = {
                            androidx.compose.animation.AnimatedVisibility(true,
                                enter = EnterTransition.None, exit = ExitTransition.None,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                                    .onGloballyPositioned { if (it.size.width > 0 && it.size.height > 0) bounds = it.boundsInRoot() }
                                    .then(if (visible.targetState) Modifier.clearAndSetSemantics { } else Modifier)) {
                                    MiniPlayer(PlayerUiState(currentTrack = song), { playTaps++ }, { nextTaps++ }, {},
                                        { visible.targetState = true }, modifier = Modifier.playerMiniTransition(motion),
                                        compactProgress = dockProgress, compact = compact)
                            }
                        },
                        dock = {
                            AnimatedVisibility(true,
                                enter = EnterTransition.None, exit = ExitTransition.None,
                                modifier = Modifier.playerDockTransition(motion)) {
                                FloatingPillBottomBar(0, {}, compactProgress = dockProgress, compact = compact)
                            }
                        }
                    )
                    PlayerExpansionOverlay(visible, motion, { bounds }, Modifier.fillMaxSize()) {
                        SideEffect { playerCompositions++ }
                        DisposableEffect(Unit) { playerMounts++; onDispose { playerDisposals++ } }
                        ExpandedPlayerScreen(PlayerUiState(currentTrack = song), { visible.targetState = false }, {}, {}, {}, {}, {}, {}, {}, {},
                            onAddToPlaylist = {}, isScreenVisible = visible.targetState,
                            modifier = if (markTransition) Modifier.drawWithContent { drawContent(); drawRect(Color.Red) } else Modifier)
                    }
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

    @Test fun portraitOpeningAndClosingShareMeasuredGeometryWithoutBouncing() = checkRoundTrip()
    @Test fun lightThemeKeepsTheSameRoundTripAndWorkingMiniControls() = checkRoundTrip(light = true)
    @Test fun compactDockReturnsToItsActualSmallerSurfaceAndArtwork() = checkRoundTrip(compact = true)
    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeTargetsTheActualNarrowMiniPlayer() = checkRoundTrip()

    private fun checkRoundTrip(light: Boolean = false, compact: Boolean = false) {
        show(light, compact = compact)
        val miniBefore = checkNotNull(motion.miniBounds)
        val viewport = compose.onNodeWithTag("motion_stage").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("mini_player_container").performClick()
        frame()
        val first = checkNotNull(motion.containerBounds())
        assertTrue("Expansion begins at the dock, not below the window: $first / $miniBefore", first.top >= viewport.height * .45f)
        val openingProgress = mutableListOf(1f - motion.fraction)
        repeat(15) { frame(); openingProgress += 1f - motion.fraction }
        assertTrue("Opening must retain a visible journey instead of snapping to the full viewport", motion.fraction > .01f)
        repeat(100) {
            frame()
            openingProgress += 1f - motion.fraction
        }
        assertBoundedZoom(openingProgress)
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
        var widestMini = miniBefore.width
        var tappedAtLanding = false
        var closingFrames = 1
        var contactFrame = 0
        val closingProgress = mutableListOf(motion.fraction)
        fun checkLandingTouch() {
            closingProgress += motion.fraction
            closingFrames++
            if (visible.isIdle && !visible.currentState && !tappedAtLanding) {
                contactFrame = closingFrames
                compose.onNodeWithTag("mini_player_play_pause").performTouchInput { click() }
                assertEquals("The invisible expanded surface must not intercept the landed mini player's button", 1, playTaps)
                tappedAtLanding = true
            }
        }
        repeat(16) {
            frame()
            widestMini = maxOf(widestMini, checkNotNull(motion.miniBounds).width)
            checkLandingTouch()
        }
        assertTrue("Closing should still be travelling after 250ms", motion.fraction < .97f)
        assertEquals("The same expanded scene remains mounted during closing", 0, playerDisposals)
        compose.onNodeWithTag("motion_stage").captureRoboImage("/tmp/player-minimize-${if (light) "light" else "dark"}-${viewport.width.toInt()}.png",
            RoborazziOptions(taskType = RoborazziTaskType.Record))
        repeat(100) {
            frame()
            widestMini = maxOf(widestMini, checkNotNull(motion.miniBounds).width)
            checkLandingTouch()
        }
        assertTrue("Dock contact should occur after a smooth, bounded journey: $contactFrame frames", contactFrame in 18..45)
        assertBoundedZoom(closingProgress)
        assertTrue(tappedAtLanding)
        assertEquals("The mini layout must not stretch during the container handoff", miniBefore.width, widestMini, .5f)
        assertTrue(visible.isIdle && !visible.currentState)
        compose.onNodeWithTag("expanded_player_transition").assertDoesNotExist()
        val miniAfter = compose.onNodeWithTag("mini_player_container").fetchSemanticsNode().boundsInRoot
        assertEquals(miniBefore.left, miniAfter.left, 1f)
        assertEquals(miniBefore.top, miniAfter.top, 1f)
        assertEquals(miniBefore.width, miniAfter.width, 1f)
        assertEquals(miniBefore.height, miniAfter.height, 1f)
        assertTrue("Motion frames must not recompose the heavy player tree", playerCompositions - compositions <= 3)
        assertEquals("The hidden player must be retained, not recreated on every opening", 1, playerMounts)
        assertEquals(0, playerDisposals)
        compose.onNodeWithTag("mini_player_play_pause").performClick()
        assertEquals(2, playTaps)
        if (!compact) {
            compose.onNodeWithTag("mini_player_next").performClick()
            assertEquals(1, nextTaps)
        }
    }

    private fun assertBoundedZoom(progress: List<Float>) {
        progress.forEach { value ->
            assertTrue("Zoom must stay within its two endpoints without overshoot: $value", value in -.0001f..1.0001f)
        }
        progress.zipWithNext().forEach { (previous, current) ->
            assertTrue("Zoom must approach its destination without bouncing back: $previous -> $current",
                current >= previous - .0001f)
        }
        assertEquals("Zoom must finish at its exact destination", 1f, progress.last(), .0001f)
    }

    @Test fun closingRetainsThePlayerUntilItsViewportFinishesMorphingIntoTheMiniPlayer() {
        show(markTransition = true)
        val mini = compose.onNodeWithTag("mini_player_container").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("motion_stage").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("mini_player_container").performClick()
        advanceUntil { visible.isIdle }
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        advanceUntil { motion.fraction in .84f.. .93f }
        assertEquals(0, playerDisposals)
        val progress = motion.fraction
        // The expanded layout stays full-sized; its independent container outline
        // contracts. Verify the rendered outline, not just layout coordinates.
        val file = java.io.File("/tmp/player-closing-morph-outline.png")
        compose.onNodeWithTag("motion_stage").captureRoboImage(file.absolutePath,
            RoborazziOptions(taskType = RoborazziTaskType.Record))
        val pixels = javax.imageio.ImageIO.read(file)
        var left = pixels.width; var top = pixels.height; var right = -1; var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = java.awt.Color(pixels.getRGB(x, y), true)
            if (color.red > color.green + 5 && color.red > color.blue + 5) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("The outgoing screen remains rendered through the final morph", right > left && bottom > top)
        val closing = Rect(viewport.left + left * viewport.width / pixels.width,
            viewport.top + top * viewport.height / pixels.height,
            viewport.left + (right + 1) * viewport.width / pixels.width,
            viewport.top + (bottom + 1) * viewport.height / pixels.height)
        fun expected(start: Float, end: Float) = start + (end - start) * progress
        assertEquals("The shrinking left edge must aim at the real mini player", expected(viewport.left, mini.left), closing.left, 2f)
        assertEquals(expected(viewport.top, mini.top), closing.top, 2f)
        assertEquals(expected(viewport.right, mini.right), closing.right, 2f)
        assertEquals(expected(viewport.bottom, mini.bottom), closing.bottom, 2f)
        advanceUntil { visible.isIdle }
        compose.onNodeWithTag("expanded_player_transition").assertDoesNotExist()
        val landed = compose.onNodeWithTag("mini_player_container").fetchSemanticsNode().boundsInRoot
        assertEquals(mini.left, landed.left, 1f)
        assertEquals(mini.top, landed.top, 1f)
        assertEquals(mini.width, landed.width, 1f)
        assertEquals(mini.height, landed.height, 1f)
    }

    @Test fun bothDirectionsUseTheSameBoundedClockAndReverseGeometryPath() {
        show()
        compose.onNodeWithTag("mini_player_container").performClick()
        var openingFrames = 0
        do { frame(); openingFrames++ } while (!visible.isIdle && openingFrames < 60)
        assertTrue(visible.currentState && visible.isIdle)
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        var closingFrames = 0
        do { frame(); closingFrames++ } while (!visible.isIdle && closingFrames < 60)
        assertTrue("A full stroke should settle near half a second: $openingFrames / $closingFrames",
            openingFrames in 25..40 && closingFrames in 25..40)
        assertTrue("Opening and closing must use the same animation spec", abs(openingFrames - closingFrames) <= 2)
        assertFalse(visible.currentState)
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
        assertEquals(1, playerMounts)
        repeat(110) { frame() }
        assertTrue(visible.isIdle && visible.currentState)
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        repeat(110) { frame() }
        assertTrue(visible.isIdle && !visible.currentState)
        compose.onNodeWithTag("expanded_player_transition").assertDoesNotExist()
        compose.onNodeWithTag("mini_player_container").assertIsDisplayed()
        assertEquals(0, playerDisposals)
    }

    @Test fun reversingNearTheOpeningEndpointAndReopeningAfterClosingKeepsControlsUsable() {
        show()
        compose.onNodeWithTag("mini_player_container").performClick()
        // The header button must actually have entered the shrinking clip before
        // receiving a real touch. Earlier reversals use Back/controller state.
        advanceUntil { motion.fraction in .001f.. .02f }
        val openingLanding = motion.fraction
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        frame()
        assertTrue("Reversing the zoom must not jump to an endpoint", abs(motion.fraction - openingLanding) < .05f)
        // Reopen once the closing surface has finished morphing into the mini
        // player and released its touch handlers at the same endpoint.
        advanceUntil { visible.isIdle }
        val closingLanding = motion.fraction
        assertFalse("Closing must reach its own endpoint: fraction=$closingLanding target=${visible.targetState}", visible.currentState)
        compose.onNodeWithTag("mini_player_container").performTouchInput { click() }
        frame()
        assertTrue("The landed mini player can reopen without snapping", abs(motion.fraction - closingLanding) < .05f)
        assertEquals("Reopening must reuse the retained scene", 1, playerMounts)
        assertEquals(0, playerDisposals)
        advanceUntil { visible.isIdle }
        assertTrue(visible.currentState)
        assertEquals(0f, motion.fraction, .001f)
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        advanceUntil { visible.isIdle }
        assertFalse(visible.currentState)
        compose.onNodeWithTag("mini_player_next").performTouchInput { click() }
        assertEquals(1, nextTaps)
    }

    @Test fun lyricsAndCreditsSurviveClosingAndReopeningTheSamePlayerScene() {
        show()
        compose.onNodeWithTag("mini_player_container").performClick()
        advanceUntil { visible.isIdle }
        compose.onNodeWithTag("flip_album_art_card").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("TRACK CREDITS & DETAILS").assertIsDisplayed()
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        advanceUntil { visible.isIdle }
        compose.onNodeWithTag("mini_player_container").performClick()
        advanceUntil { visible.isIdle }
        compose.onNodeWithText("TRACK CREDITS & DETAILS").assertIsDisplayed()
        compose.onNodeWithTag("player_lyrics_button").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("flip_album_art_card").assertDoesNotExist()
        compose.onNodeWithContentDescription("Minimize Player").performClick()
        advanceUntil { visible.isIdle }
        compose.onNodeWithTag("mini_player_container").performClick()
        advanceUntil { visible.isIdle }
        compose.onNodeWithTag("flip_album_art_card").assertDoesNotExist()
        compose.onNodeWithTag("player_lyrics_button").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("flip_album_art_card").assertIsDisplayed()
        assertEquals(1, playerMounts)
        assertEquals(0, playerDisposals)
    }

    @Test fun nestedLyricsArtworkAndPlayerMorphDoNotDrawSeparateCoversOrLoseTheirLayers() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val previous = coil.Coil.imageLoader(context)
        val cover = android.graphics.Bitmap.createBitmap(800, 800, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.MAGENTA) }
        val loader = coil.ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain -> coil.request.SuccessResult(
                android.graphics.drawable.BitmapDrawable(context.resources, cover), chain.request, coil.decode.DataSource.MEMORY_CACHE) })
        }.build()
        coil.Coil.setImageLoader(loader)
        try {
            show(artwork = true)
            compose.onNodeWithTag("mini_player_container").performClick()
            advanceUntil { visible.isIdle }
            fun artworkRegions(): Int {
                val file = java.io.File.createTempFile("nested-player-artwork-", ".png")
                val image = try {
                    compose.onNodeWithTag("motion_stage").captureRoboImage(file.absolutePath,
                        RoborazziOptions(taskType = RoborazziTaskType.Record))
                    javax.imageio.ImageIO.read(file)
                } finally { file.delete() }
                val w = image.width; val h = image.height
                val ink = BooleanArray(w * h) { index ->
                    val rgb = java.awt.Color(image.getRGB(index % w, index / w))
                    rgb.red > 240 && rgb.blue > 240 && rgb.green < 10
                }
                var regions = 0
                for (index in ink.indices) if (ink[index]) {
                    val pending = java.util.ArrayDeque<Int>()
                    pending.add(index); ink[index] = false
                    var count = 0
                    while (!pending.isEmpty()) {
                        val pixel = pending.removeLast(); count++
                        val x = pixel % w; val y = pixel / w
                        for (next in listOf(if (x > 0) pixel - 1 else -1, if (x < w - 1) pixel + 1 else -1,
                            if (y > 0) pixel - w else -1, if (y < h - 1) pixel + w else -1)) {
                            if (next >= 0 && ink[next]) { ink[next] = false; pending.add(next) }
                        }
                    }
                    if (count > 32) regions++
                }
                return regions
            }
            compose.waitUntil(3_000) { frame(); artworkRegions() == 1 }
            compose.onNodeWithTag("player_lyrics_button").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            artworkRegions() // Publish the in-flight inner shared element before closing.
            compose.runOnIdle { visible.targetState = false }
            advanceUntil { motion.expandedFraction in .4f.. .6f }
            assertEquals("The inner lyrics scope must hand its image to the one outer overlay", 1, artworkRegions())
            advanceUntil { visible.isIdle }
            compose.onNodeWithTag("mini_player_container").performClick()
            advanceUntil { visible.isIdle }
            assertEquals(1, artworkRegions())
            compose.onNodeWithTag("flip_album_art_card").assertDoesNotExist()
            assertEquals(1, playerMounts)
        } finally { coil.Coil.setImageLoader(previous); loader.shutdown() }
    }

    private fun advanceUntil(condition: () -> Boolean) {
        repeat(120) {
            frame()
            if (condition()) return
        }
        fail("Player transition did not reach the expected state within two seconds: " +
            "fraction=${motion.fraction}, target=${visible.targetState}, current=${visible.currentState}, idle=${visible.isIdle}")
    }
}
