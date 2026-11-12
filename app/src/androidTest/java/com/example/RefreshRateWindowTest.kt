package com.example

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.OtherSettingsPreferences
import com.example.performance.DisplayRefreshRatePolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshRateWindowTest {
    @Test fun savedRefreshChoicesReachTheWindowAndAreReleasedOnPauseAndDisable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = OtherSettingsPreferences.performanceSettings(context)
        try {
            OtherSettingsPreferences.setPerformanceRefreshRate(context, 60)
            OtherSettingsPreferences.setHighPerformanceEnabled(context, true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var supported = emptyList<Int>()
                scenario.onActivity { supported = DisplayRefreshRatePolicy.supportedPerformanceRefreshRates(it.window.decorView.display) }
                val expected = DisplayRefreshRatePolicy.resolvePreferredRefreshRate(60, supported).toFloat()
                awaitRate(scenario, expected)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { assertEquals(0f, it.window.attributes.preferredRefreshRate, 0f) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitRate(scenario, expected)
                OtherSettingsPreferences.setHighPerformanceEnabled(context, false)
                awaitRate(scenario, 0f)
            }
        } finally {
            OtherSettingsPreferences.setPerformanceRefreshRate(context, original.refreshRateHz)
            OtherSettingsPreferences.setHighPerformanceEnabled(context, original.highPerformanceEnabled)
        }
    }

    private fun awaitRate(scenario: ActivityScenario<MainActivity>, expected: Float) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        var actual = Float.NaN
        var focused = false
        do {
            scenario.onActivity {
                actual = it.window.attributes.preferredRefreshRate
                focused = it.window.decorView.hasWindowFocus()
            }
            if (actual == expected) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Saved setting must reach the active window (focused=$focused; unlock the test device)", expected, actual, 0f)
    }
}
