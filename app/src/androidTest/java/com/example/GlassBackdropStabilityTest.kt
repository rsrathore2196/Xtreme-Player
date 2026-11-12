package com.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.components.backdrop.backdrops.layerBackdrop
import com.example.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.example.ui.components.backdrop.drawBackdrop
import com.example.ui.components.backdrop.effects.blur
import com.example.ui.components.LocalLiquidGlassProfile
import com.example.ui.components.xtremeLiquidGlassBackground
import com.example.performance.LiquidGlassProfile
import com.example.ui.theme.LightAppColors
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/** PixelCopy exercises Android's RenderNode/RenderEffect path, including GPU sampling. */
@RunWith(AndroidJUnit4::class)
class GlassBackdropStabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fastProfileKeepsLightDockSurfacesReadable() {
        compose.setContent {
            CompositionLocalProvider(LocalLiquidGlassProfile provides LiquidGlassProfile.PERFORMANCE) {
                Box(Modifier.size(240.dp, 180.dp).background(Color.White).testTag("scene"),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(180.dp, 64.dp).xtremeLiquidGlassBackground(
                        floatingDock = true, neutralFinish = true, appColors = LightAppColors,
                        baseFillAlpha = .24f))
                }
            }
        }
        val image = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        val center = image[image.width / 2, image.height / 2]
        assertTrue("Fast's opaque fill must remain readable behind black light-theme labels",
            center.red > .65f && center.green > .65f && center.blue > .65f)
    }

    @Test fun newArtworkRefreshesStationaryGlassAndThenDrawingGoesIdle() {
        val artwork = mutableStateOf(Color.Red)
        var sourceDraws = 0
        var readerDraws = 0
        var effectBuilds = 0
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(240.dp, 180.dp).testTag("scene")) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    sourceDraws++
                    drawRect(artwork.value)
                }
                Box(Modifier.offset(40.dp, 50.dp).size(160.dp, 70.dp)
                    .drawBackdrop(backdrop, shape = { RectangleShape }, effects = { effectBuilds++; blur(2f) },
                        highlight = null, shadow = null, backdropScale = .28f,
                        onDrawSurface = { readerDraws++ }))
            }
        }
        compose.onNodeWithTag("scene").captureToImage()
        val effectsBefore = effectBuilds
        compose.runOnIdle { artwork.value = Color.Blue }
        compose.waitForIdle()
        val image = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        val pixel = image[(image.width * .4f).toInt(), (image.height * .45f).toInt()]
        assertTrue("New source artwork must reach stationary glass", pixel.blue > .95f && pixel.red < .05f)
        assertEquals("Artwork changes reuse the effect chain", effectsBefore, effectBuilds)
        val sourcesAtRest = sourceDraws
        val readersAtRest = readerDraws
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertEquals("No source capture loop after settling", sourcesAtRest, sourceDraws)
        assertEquals("No glass redraw loop after settling", readersAtRest, readerDraws)
    }

    @Test fun stationaryPageStaysFixedThroughTheFinalDockSpringFrames() {
        val compression = mutableFloatStateOf(.62f)
        val translation = mutableFloatStateOf(0f)
        val sampling = mutableFloatStateOf(.28f)
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            Box(Modifier.size(240.dp, 180.dp).testTag("scene")) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                    for (x in 0 until (size.width / 12f).toInt() + 1) {
                        for (y in 0 until (size.height / 12f).toInt() + 1) {
                            drawRect(if ((x + y) % 2 == 0) Color.White else Color.Black,
                                Offset(x * 12f, y * 12f), Size(12f, 12f))
                        }
                    }
                }
                Box(Modifier.offset(40.dp, 50.dp).size(160.dp, 70.dp).graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = compression.floatValue
                    translationX = translation.floatValue
                }.drawBackdrop(backdrop, shape = { RectangleShape }, effects = { blur(2f) },
                    highlight = null, shadow = null, backdropScale = sampling.floatValue,
                    onSample = { compression.floatValue; translation.floatValue; Unit }))
            }
        }
        for (scale in listOf(.2f, .28f, .33f)) {
            compose.runOnIdle { sampling.floatValue = scale; compression.floatValue = .62f; translation.floatValue = 0f }
            compose.waitForIdle()
            val reference = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
            assertTrue("The fixture must have rendered its texture, not a blank buffer",
                reference[6, 6].red > .9f && reference[18, 6].red < .1f)
            for ((width, shift) in listOf(.78f to .4f, .95f to .8f, .997f to .03f, 1f to 0f)) {
                compose.runOnIdle { compression.floatValue = width; translation.floatValue = shift }
                compose.waitForIdle()
                val frame = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
                var error = 0f
                var count = 0
                for (y in (frame.height * .40f).toInt() until (frame.height * .50f).toInt()) {
                    for (x in (frame.width * .30f).toInt() until (frame.width * .48f).toInt()) {
                        val a = reference[x, y]
                        val b = frame[x, y]
                        error += max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue)))
                        count++
                    }
                }
                val average = error * 255f / count
                assertTrue("Glass moved over a stationary page: sample=$scale width=$width shift=$shift error=$average", average < 1.5f)
            }
        }
    }
}
