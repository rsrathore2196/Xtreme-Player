package com.example

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.playerArtworkEdgeBlend
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
@Config(sdk = [31, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerArtworkBlendUiTest {
    @get:Rule val compose = createComposeRule()

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
}
