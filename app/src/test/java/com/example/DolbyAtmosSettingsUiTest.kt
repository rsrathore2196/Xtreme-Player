package com.example

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.playback.AudioQualityCeiling
import com.example.ui.screens.settings.DolbyAtmosSettingsCard
import com.example.ui.theme.MyApplicationTheme
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
class DolbyAtmosSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unsupportedAndLowQualityAreDisabledAndEligibleDevicesCanToggleTheSavedChoice() {
        var supported by mutableStateOf(false)
        var quality by mutableStateOf(AudioQualityCeiling.HIGH)
        var requested by mutableStateOf(false)
        compose.setContent { MyApplicationTheme { DolbyAtmosSettingsCard(requested, supported, quality) { requested = it } } }
        compose.onNodeWithTag("switch_dolby_atmos").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { supported = true }
        compose.onNodeWithTag("switch_dolby_atmos").assertIsEnabled().assertIsOff().performClick()
        compose.runOnIdle { assertTrue(requested); quality = AudioQualityCeiling.DATA_SAVER }
        compose.onNodeWithTag("switch_dolby_atmos").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(requested); quality = AudioQualityCeiling.MAX }
        compose.onNodeWithTag("switch_dolby_atmos").assertIsOn().assertIsEnabled().performClick()
        compose.runOnIdle { assertFalse(requested) }
    }
}
