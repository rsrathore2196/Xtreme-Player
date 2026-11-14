package com.example

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.data.model.LyricLine
import com.example.data.model.TrackLyrics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsViewportMotionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun maximizeMinimizeAndReversalMoveTheViewportWithoutRemeasuringItsContent() {
        var height by mutableStateOf(300.dp)
        var measures = 0
        compose.setContent {
            val density = LocalDensity.current
            val extent = remember(density) { mutableIntStateOf(with(density) { 800.dp.roundToPx() }) }
            val padding = remember(density) { LyricsViewportPadding(density) }
            val measuredContent = remember {
                Modifier.fillMaxSize().layout { measurable, constraints ->
                    measures++
                    val child = measurable.measure(constraints)
                    layout(child.width, child.height) { child.placeRelative(0, 0) }
                }
            }
            Box(Modifier.size(320.dp, height).testTag("window").clipToBounds().lyricsViewportWindow(padding, extent)) {
                Box(measuredContent, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text("Active vocal")
                }
            }
        }
        compose.waitForIdle()
        val initial = measures
        for (nextHeight in listOf(400, 520, 700, 760, 640, 420, 300)) {
            compose.runOnIdle { height = nextHeight.dp }
            compose.waitForIdle()
            val window = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
            val vocal = compose.onNodeWithText("Active vocal").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(window.center.y, vocal.center.y, 1f)
            assertEquals("Animating a viewport must reuse the list's measured geometry", initial, measures)
        }
    }

    @Test fun actualLyricsStayCenteredAsControlsReturnAndRemainScrollable() {
        var height by mutableStateOf(700.dp)
        val lyrics = TrackLyrics("viewport", "Song", "Artist", true,
            List(40) { LyricLine(1_000 + it * 5_000L, "Vocal line $it", 4_000) })
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            val extent = remember(density) { mutableIntStateOf(with(density) { 800.dp.roundToPx() }) }
            CompositionLocalProvider(LocalStableLyricsHeight provides extent) {
                Box(Modifier.size(360.dp, height)) {
                    SyncedLyricsView(lyrics, currentPositionMs = 61_500L, isPlaying = false,
                        onSeekTo = {}, viewportAnimating = true)
                }
            }
        } }
        compose.mainClock.advanceTimeBy(200)
        for (nextHeight in listOf(700, 620, 490, 360, 300, 460, 700)) {
            compose.runOnIdle { height = nextHeight.dp }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("synced_lyrics_view").fetchSemanticsNode().boundsInRoot
            val vocal = compose.onNodeWithText("Vocal line 12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(viewport.center.y, vocal.center.y, with(compose.density) { 3.dp.toPx() })
        }
        compose.onNodeWithTag("synced_lyrics_view").performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Vocal line 12").assertIsNotDisplayed()
    }

    @Test fun playerControlsReturnByPlacementWithoutMeasuringTheSharedBodyOrControlsEveryFrame() {
        val focus = mutableFloatStateOf(0f)
        val visibility = mutableFloatStateOf(1f)
        var bodyMeasures = 0
        var controlMeasures = 0
        var compositions = 0
        compose.setContent { MaterialTheme {
            SideEffect { compositions++ }
            PortraitPlayerExpansion(focus, visibility, Modifier.size(360.dp, 800.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp))
                val geometry = LocalLyricsBodyGeometry.current
                val density = LocalDensity.current
                val padding = remember(density) { LyricsViewportPadding(density) }
                Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                    bodyMeasures++
                    val child = measurable.measure(constraints)
                    layout(child.width, child.height) { child.placeRelative(0, 0) }
                }.testTag("stable_lyric_body").lyricsViewportWindow(padding, null, geometry), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text("Centered active vocal")
                }
                Box(Modifier.fillMaxWidth().height(64.dp))
                Box(Modifier.fillMaxWidth().height(50.dp).testTag("stable_timeline"))
                Box(Modifier.fillMaxWidth().height(180.dp).layout { measurable, constraints ->
                    controlMeasures++
                    val child = measurable.measure(constraints)
                    layout(child.width, child.height) { child.placeRelative(0, 0) }
                }.testTag("stable_transport"))
            }
        } }
        compose.waitForIdle()
        val measuredBody = bodyMeasures
        val measuredControls = controlMeasures
        val initialCompositions = compositions
        val collapsedCenter = compose.onNodeWithText("Centered active vocal").fetchSemanticsNode().boundsInRoot.center.y
        val originalViewport = compose.onNodeWithTag("stable_lyric_body").fetchSemanticsNode().boundsInRoot
        val expandedCenter = originalViewport.top + (originalViewport.height - with(compose.density) { 76.dp.toPx() }) / 2f
        for (fraction in listOf(0.15f, 0.4f, 0.8f, 1f, 0.9f, 0.6f, 0.2f, 0.75f, 0f)) {
            compose.runOnIdle { focus.floatValue = fraction }
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("stable_lyric_body").fetchSemanticsNode().boundsInRoot
            val vocal = compose.onNodeWithText("Centered active vocal").fetchSemanticsNode().boundsInRoot
            val expectedCenter = collapsedCenter + (expandedCenter - collapsedCenter) * fraction
            assertEquals("The vocal follows the visible window without a second scroll", expectedCenter, vocal.center.y, 2f)
            assertEquals("Shared artwork/lyrics must keep their endpoint measurements", measuredBody, bodyMeasures)
            assertEquals("Returning controls must reuse their measured layout", measuredControls, controlMeasures)
            assertEquals("Motion must not recompose the player", initialCompositions, compositions)
            if (fraction == 1f) compose.onNodeWithTag("stable_transport").assertIsNotDisplayed()
            else compose.onNodeWithTag("stable_timeline").assertIsDisplayed()
        }
    }

    @Test fun lyricsOpeningAndClosingKeepTheOutgoingArtworkAtItsOriginalMeasuredBounds() {
        val focus = mutableFloatStateOf(0f)
        val visibility = mutableFloatStateOf(0f)
        var artworkMeasures = 0
        compose.setContent {
            PortraitPlayerExpansion(focus, visibility, Modifier.size(360.dp, 800.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp))
                Box(Modifier.fillMaxSize().lyricsBodyEndpoint(false)) {
                    Box(Modifier.fillMaxSize().testTag("outgoing_sleeve").layout { measurable, constraints ->
                        artworkMeasures++
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) { child.placeRelative(0, 0) }
                    })
                }
                Box(Modifier.fillMaxWidth().height(64.dp))
                Box(Modifier.fillMaxWidth().height(50.dp))
                Box(Modifier.fillMaxWidth().height(180.dp))
            }
        }
        compose.waitForIdle()
        val measured = artworkMeasures
        val original = compose.onNodeWithTag("outgoing_sleeve").fetchSemanticsNode().boundsInRoot
        for (fraction in listOf(0.05f, 0.3f, 0.7f, 1f, 0.6f, 0.2f, 0f)) {
            compose.runOnIdle { visibility.floatValue = fraction }
            compose.waitForIdle()
            assertEquals(original, compose.onNodeWithTag("outgoing_sleeve").fetchSemanticsNode().boundsInRoot)
            assertEquals("The sleeve must not be resized underneath its shared element", measured, artworkMeasures)
        }
    }
}
