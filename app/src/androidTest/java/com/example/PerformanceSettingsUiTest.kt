package com.example

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.PerformanceSettings
import com.example.performance.LiquidGlassProfileSetting
import com.example.ui.screens.settings.OtherSettingsPage
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original: PerformanceSettings? = null
    private var originalProfile: LiquidGlassProfileSetting? = null

    @After fun restorePreferences() {
        original?.let {
            OtherSettingsPreferences.setPerformanceRefreshRate(context, it.refreshRateHz)
            OtherSettingsPreferences.setHighPerformanceEnabled(context, it.highPerformanceEnabled)
        }
        originalProfile?.let { OtherSettingsPreferences.setLiquidGlassProfile(context, it) }
    }

    @Test fun autoIsSelectedAndFixedRateChoicesArePersisted() {
        original = OtherSettingsPreferences.performanceSettings(context)
        OtherSettingsPreferences.setHighPerformanceEnabled(context, true)
        OtherSettingsPreferences.setPerformanceRefreshRate(context, 0)
        compose.setContent { MyApplicationTheme { OtherSettingsPage(isDarkMode = true) } }
        compose.onNodeWithTag("refresh_rate_0").performScrollTo().assertIsSelected()
        for (rate in listOf(60, 90, 120, 0)) {
            compose.onNodeWithTag("refresh_rate_$rate").performClick()
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("refresh_rate_$rate").fetchSemanticsNode().config[SemanticsProperties.Selected]
            }
            assertEquals(rate, OtherSettingsPreferences.performanceSettings(context).refreshRateHz)
            compose.onNodeWithTag("refresh_rate_$rate").assertIsSelected()
        }
    }

    @Test fun allRenderingProfileSelectionsRoundTripWithoutChangingPerformanceSettings() {
        originalProfile = OtherSettingsPreferences.getLiquidGlassProfile(context)
        val performance = OtherSettingsPreferences.performanceSettings(context)
        for (profile in LiquidGlassProfileSetting.entries) {
            OtherSettingsPreferences.setLiquidGlassProfile(context, profile)
            assertEquals(profile, OtherSettingsPreferences.getLiquidGlassProfile(context))
            assertEquals(performance, OtherSettingsPreferences.performanceSettings(context))
        }
    }
}
