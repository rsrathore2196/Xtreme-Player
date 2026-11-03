package com.example

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.ui.components.WavyScrubberBar
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
        scrubber.performTouchInput { swipe(Offset(width * .2f, centerY), Offset(width * .75f, centerY), 400L) }
        compose.runOnIdle {
            assertEquals("second", seeks.last().first)
            assertTrue(seeks.last().second in 179_000..181_000)
            assertEquals("second", started.last())
            assertEquals("second", moved.last())
        }
    }
}
