package com.example

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.cache.SmartCacheManager
import com.example.data.local.ThemePresets
import com.example.ui.screens.settings.CacheQuotaSlider
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
class CacheQuotaSliderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sliderSelectsBothEndpointsAndDisplaysTheRealSelectedLimit() {
        var selected = SmartCacheManager.MIN_CACHE_BYTES
        compose.setContent {
            var value by remember { mutableLongStateOf(selected) }
            MyApplicationTheme(customThemeState = ThemePresets.StudioNight.toCustomThemeState()) {
                CacheQuotaSlider(value) { value = it; selected = it }
            }
        }
        compose.onNodeWithText("512 MB").assertExists()
        compose.onNodeWithText("8 GB").assertExists()
        compose.onNodeWithTag("slider_cache_quota").performSemanticsAction(SemanticsActions.SetProgress) { it(8192f) }
        compose.runOnIdle { assertEquals(SmartCacheManager.MAX_CACHE_BYTES, selected) }
        compose.onNodeWithTag("cache_limit_value").assertTextEquals(SmartCacheManager.formatBytes(SmartCacheManager.MAX_CACHE_BYTES))
        compose.onNodeWithTag("slider_cache_quota").performSemanticsAction(SemanticsActions.SetProgress) { it(512f) }
        compose.runOnIdle { assertEquals(SmartCacheManager.MIN_CACHE_BYTES, selected) }
    }
}
