package com.example

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppThemeMode
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.ThemePresets
import com.example.ui.components.PlayerGlassBackground
import com.example.ui.screens.settings.ThemesAppUiPage
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerVisualSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() = runBlocking { withContext(Dispatchers.IO) {
        context.getSharedPreferences("xtreme_other_settings", 0).edit().clear().commit()
    }; Unit }

    @Test fun visualSwitchesPersistAfterLeavingAndReturningToTheirSettingsPage() {
        var show: MutableState<Boolean>? = null
        compose.setContent {
            val visible = remember { mutableStateOf(true) }; show = visible
            MyApplicationTheme(customThemeState = ThemePresets.StudioNight.toCustomThemeState()) {
                if (visible.value) ThemesAppUiPage(true, AppThemeMode.DARK, {}, ThemePresets.StudioNight.toCustomThemeState())
            }
        }
        compose.onNodeWithTag("switch_high_contrast").performScrollTo().assertIsOff()
        compose.onNodeWithTag("switch_high_contrast").performClick()
        compose.waitUntil(3_000) { OtherSettingsPreferences.playerExperienceSettings(context).highContrastTypography }
        compose.onNodeWithTag("switch_ambient_glow").performScrollTo().performClick()
        compose.waitUntil(3_000) { !OtherSettingsPreferences.playerExperienceSettings(context).dynamicAlbumGlow }
        compose.runOnIdle { show!!.value = false }
        compose.waitForIdle()
        compose.runOnIdle { show!!.value = true }
        compose.waitForIdle()
        compose.waitUntil(3_000) {
            compose.onNodeWithTag("switch_ambient_glow").fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off
        }
        compose.onNodeWithTag("switch_high_contrast").performScrollTo().assertIsOn()
        compose.onNodeWithTag("switch_ambient_glow").performScrollTo().assertIsOff()
    }

    @Test fun theSavedGlowOptionActuallyChangesThePlayerBackdrop() {
        compose.setContent {
            MyApplicationTheme(customThemeState = ThemePresets.StudioNight.toCustomThemeState()) {
                val dominant = remember { mutableStateOf(Color.Blue) }
                val accent = remember { mutableStateOf(Color.Cyan) }
                Box(Modifier.fillMaxSize()) {
                    PlayerGlassBackground("", dominantColor = dominant, accentColor = accent)
                }
            }
        }
        compose.onNodeWithTag("player_album_glow_backdrop").assertExists()
        compose.runOnIdle { OtherSettingsPreferences.setDynamicAlbumGlow(context, false) }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("player_neutral_backdrop").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player_album_glow_backdrop").assertDoesNotExist()
        compose.runOnIdle { OtherSettingsPreferences.setDynamicAlbumGlow(context, true) }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("player_album_glow_backdrop").fetchSemanticsNodes().isNotEmpty() }
    }
}
