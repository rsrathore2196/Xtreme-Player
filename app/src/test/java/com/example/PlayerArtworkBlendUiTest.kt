package com.example

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.headerArtworkEdgeBlend
import com.example.ui.components.playerArtworkEdgeBlend
import com.example.ui.components.PlayerGlassBackground
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerArtworkBlendUiTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(sdk = [28, 36])
    fun headerAndControlsMatchTheirOwnArtworkEdgeAsCoverBoundsAndColorsChange() {
        var top by mutableStateOf(Color(0xff6a1b9a))
        var bottom by mutableStateOf(Color(0xffef6c00))
        var inset by mutableStateOf(40.dp)
        var coverHeight by mutableStateOf(160.dp)
        val bounds = mutableStateOf<Rect?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicAlbumGlow = true),
                LocalDynamicGlassEnabled provides true) {
                // Offset the entire player to catch root/local coordinate mix-ups.
                Box(Modifier.size(240.dp, 360.dp)) {
                    Box(Modifier.offset(y = 15.dp).size(200.dp, 320.dp).testTag("edge_color_player")) {
                        PlayerGlassBackground(rememberUpdatedState(top), rememberUpdatedState(bottom), bounds)
                        Canvas(Modifier.offset(y = inset).size(200.dp, coverHeight)
                            .onGloballyPositioned { bounds.value = it.boundsInRoot() }
                            .playerArtworkEdgeBlend(true)) {
                            drawRect(top)
                            drawRect(bottom, Offset(0f, size.height / 2), Size(size.width, size.height / 2))
                        }
                    }
                }
            }
        }
        fun verify() {
            val file = java.io.File.createTempFile("player-edge-colors-", ".png")
            val image = try {
                compose.onNodeWithTag("edge_color_player").captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                javax.imageio.ImageIO.read(file)
            } finally { file.delete() }
            val start = (image.height * inset.value / 320f).toInt()
            val end = (image.height * (inset.value + coverHeight.value) / 320f).toInt()
            fun assertColor(expected: Color, y: Int, message: String) {
                val actual = java.awt.Color(image.getRGB(image.width / 2, y))
                val target = java.awt.Color(expected.toArgb())
                // Native premultiplied alpha can round one channel by 1/255.
                assertTrue("$message at y=$y: $actual", maxOf(kotlin.math.abs(target.red - actual.red),
                    kotlin.math.abs(target.green - actual.green), kotlin.math.abs(target.blue - actual.blue)) <= 1)
            }
            for (y in listOf(1, start - 1, start + 2, start + (end - start) / 10)) {
                assertColor(top, y, "The header and upper fade must retain the cover's top hue")
            }
            for (y in listOf(end - (end - start) / 10, end - 2, end + 1, image.height - 2)) {
                assertColor(bottom, y, "The lower fade and controls must retain the cover's bottom hue")
            }
        }
        verify()
        compose.runOnIdle {
            top = Color.Black; bottom = Color.White
            inset = 100.dp; coverHeight = 100.dp
        }
        compose.waitForIdle()
        verify()
    }

    @Test fun contrastingArtworkMarginsMatchTheUnchangedBackdropAndTheCenterStaysOriginal() {
        var cover by mutableStateOf(false)
        val backdrop = Brush.verticalGradient(listOf(Color(0xffac326e), Color(0xff8e7627)))
        compose.setContent {
            Box(Modifier.size(200.dp, 320.dp).background(backdrop).testTag("artwork_blend")) {
                if (cover) Canvas(Modifier.matchParentSize().playerArtworkEdgeBlend(true)) {
                    // A high-contrast edge would expose even a small hard seam.
                    drawRect(Color.White)
                    drawRect(Color.Black, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                }
            }
        }
        fun capture(): java.awt.image.BufferedImage {
            val file = java.io.File.createTempFile("artwork-blend-", ".png")
            return try {
                compose.onNodeWithTag("artwork_blend").captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                javax.imageio.ImageIO.read(file)
            } finally { file.delete() }
        }
        val before = capture()
        compose.runOnIdle { cover = true }
        compose.waitForIdle()
        val after = capture()
        for (y in 0..(after.height * .015f).toInt()) for (x in 0 until after.width) {
            assertEquals("The actual top boundary must reveal only the original backdrop", before.getRGB(x, y), after.getRGB(x, y))
            val bottom = after.height - y - 1
            assertEquals("The actual bottom boundary must reveal only the original backdrop", before.getRGB(x, bottom), after.getRGB(x, bottom))
        }
        for (y in listOf(after.height / 3, after.height / 2, after.height * 2 / 3)) {
            assertEquals(Color.White.toArgb(), after.getRGB(after.width / 4, y))
            assertEquals(Color.Black.toArgb(), after.getRGB(after.width * 3 / 4, y))
        }
        // Verify that the fade starts gradually, rather than as a visible horizontal band.
        for (x in listOf(after.width / 4, after.width * 3 / 4)) for (edge in listOf(false, true)) {
            val samples = (1..8).map { index ->
                val y = if (edge) after.height - index - 1 else index
                java.awt.Color(after.getRGB(x, y))
            }
            assertTrue(samples.zipWithNext().all { (a, b) -> maxOf(kotlin.math.abs(a.red - b.red),
                kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue)) <= 3 })
        }
    }

    @Test fun headerArtworkEdgeBlendPreservesCentralEightyPercentAndFadesOnlyTopAndBottomTenPercent() {
        var cover by mutableStateOf(false)
        val backdrop = Color.Black
        compose.setContent {
            Box(Modifier.size(200.dp, 400.dp).background(backdrop).testTag("header_blend")) {
                if (cover) Canvas(Modifier.matchParentSize().headerArtworkEdgeBlend(topFade = 0.10f, bottomFade = 0.10f, topMargin = 0.01f, bottomMargin = 0.02f)) {
                    drawRect(Color.White)
                }
            }
        }
        fun capture(): java.awt.image.BufferedImage {
            val file = java.io.File.createTempFile("header-blend-", ".png")
            return try {
                compose.onNodeWithTag("header_blend").captureRoboImage(file.absolutePath,
                    roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                javax.imageio.ImageIO.read(file)
            } finally { file.delete() }
        }
        compose.waitForIdle()
        compose.runOnIdle { cover = true }
        compose.waitForIdle()
        val after = capture()

        // 1. Extreme top and bottom edges (0% and 100%) must be completely blended/transparent
        val topPixel = java.awt.Color(after.getRGB(after.width / 2, 0))
        val bottomPixel = java.awt.Color(after.getRGB(after.width / 2, after.height - 1))
        assertEquals(0, topPixel.red)
        assertEquals(0, bottomPixel.red)

        // 2. Central 80% (from 10% to 90% of height) must be fully opaque
        val y15 = (after.height * 0.15f).toInt()
        val y50 = (after.height * 0.50f).toInt()
        val y85 = (after.height * 0.85f).toInt()
        for (y in listOf(y15, y50, y85)) {
            val centerPixel = java.awt.Color(after.getRGB(after.width / 2, y))
            assertEquals("Central 80% at y=$y must be full white", 255, centerPixel.red)
            assertEquals("Central 80% at y=$y must be full white", 255, centerPixel.green)
            assertEquals("Central 80% at y=$y must be full white", 255, centerPixel.blue)
        }
    }
}

