package com.example.ui.components.backdrop

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.backdrop.backdrops.layerBackdrop
import com.example.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.example.ui.components.backdrop.effects.blur
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveBackdropRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceArtworkArrivalRefreshesStationaryGlassWithoutScrollOrAnIdleFrameLoop() {
        val color = mutableStateOf(Color.Red)
        var sourceDraws = 0
        var readerDraws = 0
        var compositions = 0
        var effectBuilds = 0
        compose.setContent {
            MaterialTheme {
                val backdrop = rememberLayerBackdrop()
                SideEffect { compositions++ }
                Box(Modifier.size(240.dp, 180.dp).testTag("scene")) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        sourceDraws++
                        drawRect(color.value)
                    }
                    Box(Modifier.offset(40.dp, 50.dp).size(100.dp, 40.dp)
                        .drawBackdrop(backdrop, shape = { RectangleShape }, effects = { effectBuilds++; blur(3f) },
                            highlight = null, shadow = null, backdropScale = .33f,
                            onDrawSurface = { readerDraws++ }))
                }
            }
        }
        compose.waitForIdle()
        capture() // Robolectric needs a real draw traversal, just as the device does.
        val initialReaders = readerDraws
        val initialSources = sourceDraws
        val initialCompositions = compositions
        val initialEffects = effectBuilds
        assertTrue("Initialize the blur on the first draw", initialEffects > 0)
        // Simulate an asynchronous artwork/cache result. Only the source draw reads this state.
        compose.runOnIdle { color.value = Color.Blue }
        compose.waitForIdle()
        capture()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val updated = capture()
        assertTrue("The new source content must draw", sourceDraws > initialSources)
        assertTrue("Stationary glass must be notified when source artwork arrives", readerDraws > initialReaders)
        assertEquals("Source updates must not recompose the screen", initialCompositions, compositions)
        assertEquals("Artwork arrival must reuse the cached blur chain", initialEffects, effectBuilds)
        val pixel = java.awt.Color(updated.getRGB(updated.width / 3, updated.height / 3))
        assertTrue("The updated blue artwork must reach the glass", pixel.blue > 240 && pixel.red < 10)
        val settledSources = sourceDraws
        val settledReaders = readerDraws
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertEquals("No permanent capture/frame loop", settledSources, sourceDraws)
        assertEquals("Unchanged glass stays idle", settledReaders, readerDraws)
    }

    @Test fun theSampleStaysAlignedWithThePageThroughAncestorScaleAndFractionalTranslation() {
        val visible = mutableStateOf(false)
        val x = mutableFloatStateOf(0f)
        val scaleX = mutableFloatStateOf(.65f)
        compose.setContent {
            MaterialTheme {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(240.dp, 180.dp).testTag("scene")) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        // Sharp, asymmetric boundaries expose even small coordinate errors.
                        for (i in 0 until 12) {
                            drawRect(if (i % 3 == 0) Color.Red else if (i % 3 == 1) Color.Green else Color.Blue,
                                Offset(i * size.width / 12, 0f), Size(size.width / 12, size.height))
                        }
                    }
                    Box(Modifier.offset(40.dp, 50.dp).graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        this.scaleX = scaleX.floatValue
                        scaleY = 1.15f
                        translationX = x.floatValue
                        alpha = if (visible.value) 1f else 0f
                    }.size(140.dp, 50.dp).drawBackdrop(backdrop, shape = { RectangleShape }, effects = {},
                        highlight = null, shadow = null,
                        onSample = { x.floatValue; scaleX.floatValue; Unit }).testTag("reader"))
                }
            }
        }
        compose.waitForIdle()
        for ((scale, shift) in listOf(.65f to 0f, .82f to .4f, 1.03f to 1.2f)) {
            compose.runOnIdle { scaleX.floatValue = scale; x.floatValue = shift; visible.value = false }
            val page = capture()
            compose.runOnIdle { visible.value = true }
            val glass = capture()
            val bounds = compose.onNodeWithTag("reader").fetchSemanticsNode().boundsInRoot
            val sceneBounds = compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInRoot
            val left = (bounds.left - sceneBounds.left).toInt() + 3
            val right = (bounds.right - sceneBounds.left).toInt() - 3
            val y = (bounds.center.y - sceneBounds.top).toInt()
            var wrong = 0
            for (pixel in left until right) {
                // A transformed offscreen layer can antialias a stripe boundary
                // differently. Its solid interiors must remain at the same page coordinates.
                val expected = page.getRGB(pixel, y)
                if ((-2..2).all { page.getRGB(pixel + it, y) == expected } &&
                    difference(expected, glass.getRGB(pixel, y)) > 8) wrong++
            }
            assertEquals("Glass must sample the pixels beneath it at scale=$scale shift=$shift", 0, wrong)
        }
    }

    private fun capture(): BufferedImage {
        val file = File.createTempFile("live-backdrop-", ".png")
        return try {
            compose.onNodeWithTag("scene").captureRoboImage(file.absolutePath,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            ImageIO.read(file)
        } finally { file.delete() }
    }

    private fun difference(a: Int, b: Int) = maxOf(
        abs((a ushr 16 and 255) - (b ushr 16 and 255)),
        abs((a ushr 8 and 255) - (b ushr 8 and 255)), abs((a and 255) - (b and 255))
    )
}
