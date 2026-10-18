package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import com.example.playback.*
import com.example.ui.components.AudioQualityBadge
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignalPathUiTest {
    @get:Rule val compose = createComposeRule()
    @After fun clear() { AudioSignalPath.reset() }
    @Test fun badgeOpensCompactSignalPathAndShowsActualLossyFormat() {
        AudioSignalPath.update { SignalPathState(trackId = "playing", source = "YouTube Music",
            quality = StreamQualityBadge("OPUS", 160, 48000, verified = true),
            outputDevice = "Bluetooth TWS", outputType = "Bluetooth", inputRateHz = 48000, outputRateHz = 48000) }
        compose.setContent { MaterialTheme {
            AudioQualityBadge("playing", "Lossless", Color.DarkGray, Color.White, Color.White)
        } }
        compose.onNodeWithText("OPUS • 160 kbps").assertExists().performClick()
        compose.onNodeWithText("Signal Path").assertExists()
        compose.onNodeWithText("High Quality").assertExists()
        compose.onNodeWithText("Bypassed (Pure)").assertExists()
        compose.onNodeWithText("YouTube Music • OPUS • 160 kbps").assertExists()
        compose.onNodeWithText("Bit-Perfect", substring = true).assertDoesNotExist()
    }
}
