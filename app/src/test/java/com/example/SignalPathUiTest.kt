package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import com.example.playback.*
import com.example.ui.components.AudioQualityBadge
import com.example.ui.components.SignalPathPopupPositionProvider
import com.example.ui.screens.settings.AudioEnhancerSettingsSection
import androidx.compose.ui.unit.*
import androidx.compose.ui.geometry.*
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
    @After fun clear() { AudioEffectsManager.setAudioEnhancerEnabled(false); AudioSignalPath.reset() }
    @Test fun badgeOpensCompactSignalPathAndShowsActualLossyFormat() {
        AudioSignalPath.update { SignalPathState(trackId = "playing", source = "YouTube Music",
            quality = StreamQualityBadge("OPUS", 160, 48000, verified = true),
            outputDevice = "Bluetooth TWS", outputType = "Bluetooth", inputRateHz = 48000, outputRateHz = 48000) }
        compose.setContent { MaterialTheme {
            AudioQualityBadge("playing", "Lossless", Color.DarkGray, Color.White, Color.White)
        } }
        compose.onNodeWithText("OPUS • 160 kbps").assertExists().performClick()
        compose.onNodeWithText("Signal Path").assertExists()
        compose.runOnIdle {
            val popup = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .firstOrNull { it.javaClass.simpleName == "PopupLayout" }
            Assert.assertNotNull("The real popup window must be present", popup)
            Assert.assertEquals("No framework window shadow behind the card", 0f, popup!!.elevation)
        }
        compose.onNodeWithText("High Quality").assertExists()
        compose.onNodeWithText("Bypassed (Pure)").assertExists()
        compose.onNodeWithText("YouTube Music • OPUS • 160 kbps • 48.0 kHz").assertExists()
        compose.onNodeWithText("Audio Enhancer").assertExists()
        compose.onNodeWithText("Volume / Gain").assertDoesNotExist()
        compose.onNodeWithText("Clarity Enhancer").assertDoesNotExist()
        compose.onNodeWithText("Output Format").assertExists()
        compose.onNodeWithText("Output Result").assertExists()
        compose.onNodeWithText("Bit-Perfect", substring = true).assertDoesNotExist()
    }
    @Test fun popupRemainsCenteredWhenBadgeTextOrAnchorPositionChanges() {
        val position = SignalPathPopupPositionProvider(16)
        val window = IntSize(1080, 1920)
        val popup = IntSize(900, 1200)
        val first = position.calculatePosition(IntRect(100, 1400, 400, 1450), window, LayoutDirection.Ltr, popup)
        val changed = position.calculatePosition(IntRect(500, 1400, 1030, 1450), window, LayoutDirection.Rtl, popup)
        Assert.assertEquals(first, changed)
        Assert.assertEquals(90, first.x)
        val tiny = position.calculatePosition(IntRect.Zero, IntSize(300, 300), LayoutDirection.Ltr, IntSize(300, 300))
        Assert.assertEquals(IntOffset.Zero, tiny)
    }
    @Test fun settingsAndPopupShareEnhancerChoiceWithoutEnablingEqualizer() {
        AudioEffectsManager.resetAll(false)
        AudioEffectsManager.setAudioEnhancerEnabled(false)
        AudioSignalPath.source("playing", "JioSaavn")
        compose.setContent { MaterialTheme { Column {
            AudioEnhancerSettingsSection()
            AudioQualityBadge("playing", "AAC • 320 kbps", Color.DarkGray, Color.White, Color.White)
        } } }
        compose.onNodeWithTag("switch_audio_enhancer_settings").assertIsOff().performClick()
        compose.waitUntil(2000) { AudioSignalPath.state.value.enhancerEnabled }
        Assert.assertFalse(AudioEffectsManager.effectsState.value.isEnabled)
        compose.onNodeWithText("AAC • 320 kbps").performClick()
        compose.onNodeWithTag("audio_enhancer_toggle").assertIsOn().performClick()
        compose.waitUntil(2000) { !AudioSignalPath.state.value.enhancerEnabled }
        compose.onNodeWithTag("switch_audio_enhancer_settings").assertIsOff()
        Assert.assertEquals("JioSaavn", AudioSignalPath.state.value.source)
        Assert.assertFalse(AudioEffectsManager.effectsState.value.isEnabled)
    }
}
