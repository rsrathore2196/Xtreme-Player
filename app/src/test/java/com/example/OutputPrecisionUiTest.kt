package com.example

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.ThemePresets
import com.example.playback.AudioEffectsState
import com.example.playback.AudioSignalPath
import com.example.ui.screens.settings.MusicPlaybackPage
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutputPrecisionUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() = runBlocking {
        withContext(Dispatchers.IO) { context.getSharedPreferences("xtreme_other_settings", 0).edit().clear().commit() }
        Unit
    }
    @After fun reset() { AudioSignalPath.reset() }

    @Test fun settingsControlSelectsBothModesAndShowsActualOutputWithoutTheOldStaticRow() {
        AudioSignalPath.update { it.copy(outputDevice = "USB-C DAC", outputEncoding = C.ENCODING_PCM_16BIT, outputRateHz = 48000) }
        compose.setContent {
            MyApplicationTheme(customThemeState = ThemePresets.StudioNight.toCustomThemeState()) {
                Box(Modifier.width(320.dp)) {
                    MusicPlaybackPage(AudioEffectsState(), true, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("output_precision_PCM_16").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("output_precision_FLOAT_32").assertIsNotSelected().performClick()
        compose.waitUntil(3_000) {
            compose.onNodeWithTag("output_precision_FLOAT_32").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Selected]
        }
        compose.onNodeWithTag("output_precision_FLOAT_32").assertIsSelected()
        // Selection is an intent: never invent float telemetry while PCM16 is audible.
        compose.onNodeWithTag("output_precision_status").assertTextEquals("AudioTrack · USB-C DAC · 48.0 kHz · 16-bit PCM")
        compose.runOnIdle { AudioSignalPath.update { it.copy(outputEncoding = C.ENCODING_PCM_FLOAT, outputRateHz = 96000) } }
        compose.onNodeWithTag("output_precision_status").assertTextEquals("AudioTrack · USB-C DAC · 96.0 kHz · 32-bit float")
        compose.onNodeWithText("16-bit PCM Hardware Output").assertDoesNotExist()
        compose.onNodeWithTag("output_precision_PCM_16").performClick()
        compose.waitUntil(3_000) {
            compose.onNodeWithTag("output_precision_PCM_16").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Selected]
        }
        compose.onNodeWithTag("output_precision_PCM_16").assertIsSelected()
    }
}
