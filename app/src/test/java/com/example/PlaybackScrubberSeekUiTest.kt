package com.example

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.WavyScrubberBar
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackScrubberSeekUiTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun bothEndpointsKeepTheWholeThumbAndRoundTrackCapsInsideTheCanvas() {
        var progress by mutableFloatStateOf(0f)
        val seeks = mutableListOf<Float>()
        compose.setContent { MaterialTheme {
            WavyScrubberBar(progress, false, {}, {}, seeks::add, isDark = true,
                modifier = Modifier.width(300.dp).background(Color.Black))
        } }
        val scrubber = compose.onNodeWithTag("wavy_scrubber_bar")
        for (fraction in listOf(0f, 1f)) {
            compose.runOnIdle { progress = fraction }
            val file = java.io.File.createTempFile("scrubber-endpoint-", ".png")
            try {
                scrubber.captureRoboImage(file.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                val image = javax.imageio.ImageIO.read(file)
                var left = image.width
                var right = -1
                var bright = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val pixel = image.getRGB(x, y)
                    val ink = ((pixel ushr 16) and 255) * ((pixel ushr 24) and 255) / 255
                    if (ink > 5) { left = minOf(left, x); right = maxOf(right, x) }
                    if (ink > 230) bright++
                }
                assertTrue("The thumb is visible even at zero progress", bright > 30)
                assertTrue("The left cap/halo must not be cut off", left > 1)
                assertTrue("The right cap/halo must not be cut off", right < image.width - 2)
            } finally { file.delete() }
        }
        scrubber.performTouchInput { click(Offset(0f, centerY)) }
        scrubber.performTouchInput { click(Offset(width.toFloat(), centerY)) }
        compose.runOnIdle { assertEquals(listOf(0f, 1f), seeks) }
    }

    @Test fun tapAndDragUseTheLoadedDurationAndNewTrackCallbacksWithoutRecreatingTheScrubber() {
        var duration by mutableLongStateOf(0L)
        var track by mutableStateOf("first")
        val seeks = mutableListOf<Pair<String, Long>>()
        val started = mutableListOf<String>()
        val moved = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            val loadedDuration = duration
            val currentTrack = track
            WavyScrubberBar(0f, false, onSeekStarted = { started.add(currentTrack) },
                onSeekProgress = { moved.add(currentTrack) }, onSeekFinished = { fraction -> seeks.add(currentTrack to (fraction * loadedDuration).toLong()) },
                isDark = true, modifier = Modifier.width(300.dp))
        } }
        compose.runOnIdle { duration = 180_000L }
        val scrubber = compose.onNodeWithTag("wavy_scrubber_bar")
        scrubber.performTouchInput { click(Offset(width * .5f, centerY)) }
        compose.runOnIdle {
            assertEquals("first", seeks.single().first)
            assertTrue("Loaded duration must replace zero-duration callbacks", seeks.single().second in 89_500..90_500)
            track = "second"; duration = 240_000L
        }
        val inset = with(compose.density) { 16.dp.toPx() }
        scrubber.performTouchInput { swipe(Offset(inset + (width - inset * 2) * .2f, centerY),
            Offset(inset + (width - inset * 2) * .75f, centerY), 400L) }
        compose.runOnIdle {
            assertEquals("second", seeks.last().first)
            assertTrue(seeks.last().second in 179_000..181_000)
            assertEquals("second", started.last())
            assertEquals("second", moved.last())
        }
    }
}
