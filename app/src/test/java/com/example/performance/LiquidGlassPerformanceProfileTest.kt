package com.example.performance

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.dp

class LiquidGlassPerformanceProfileTest {

    @Test fun floatingChromeUsesItsDedicatedRadiusAndFastFill() {
        assertEquals(8.dp, LiquidGlassProfile.PREMIUM.blurRadius(20.dp, floatingDock = true))
        assertEquals(5.dp, LiquidGlassProfile.BALANCED.blurRadius(20.dp, floatingDock = true))
        assertEquals(3.dp, LiquidGlassProfile.PERFORMANCE.blurRadius(20.dp, floatingDock = true))
        assertEquals(12.dp, LiquidGlassProfile.BALANCED.blurRadius(20.dp, floatingDock = false))
        assertEquals(.24f, LiquidGlassProfile.PREMIUM.surfaceFillAlpha(.24f, .5f, floatingDock = true))
        assertEquals(.24f, LiquidGlassProfile.BALANCED.surfaceFillAlpha(.24f, .5f, floatingDock = true))
        assertEquals(.78f, LiquidGlassProfile.PERFORMANCE.surfaceFillAlpha(.24f, .5f, floatingDock = true))
        assertEquals(0f, LiquidGlassProfile.PERFORMANCE.surfaceFillAlpha(0f, .5f, floatingDock = false))
    }

    private fun capabilities(
        sdk: Int = 35,
        powerSave: Boolean = false,
        lowRam: Boolean = false,
        memoryClass: Int = 256,
        processors: Int = 6,
        highRefresh: Boolean = false,
        refreshRate: Float = 60f
    ) = LiquidGlassDeviceCapabilities(
        sdkInt = sdk,
        powerSaveMode = powerSave,
        lowRamDevice = lowRam,
        memoryClassMb = memoryClass,
        availableProcessors = processors,
        hasHighRefresh = highRefresh,
        maxRefreshRateHz = refreshRate
    )

    @Test
    fun unsupportedPreAndroid12DevicesUsePerformanceProfile() {
        assertEquals(
            LiquidGlassProfile.PERFORMANCE,
            LiquidGlassDeviceCapability.selectAutomaticProfile(capabilities(sdk = 30))
        )
    }

    @Test
    fun batterySaverAndLowRamAlwaysPreferPerformanceProfile() {
        assertEquals(
            LiquidGlassProfile.PERFORMANCE,
            LiquidGlassDeviceCapability.selectAutomaticProfile(capabilities(powerSave = true))
        )
        assertEquals(
            LiquidGlassProfile.PERFORMANCE,
            LiquidGlassDeviceCapability.selectAutomaticProfile(capabilities(lowRam = true))
        )
    }

    @Test
    fun android12And13LUseBalancedOrPerformanceWithoutAgslSupport() {
        assertEquals(
            LiquidGlassProfile.BALANCED,
            LiquidGlassDeviceCapability.selectAutomaticProfile(
                capabilities(sdk = 32, memoryClass = 256, processors = 6)
            )
        )
        assertEquals(
            LiquidGlassProfile.PERFORMANCE,
            LiquidGlassDeviceCapability.selectAutomaticProfile(
                capabilities(sdk = 32, memoryClass = 192, processors = 4)
            )
        )
    }

    @Test
    fun capableDevicesSelectPremiumAndWeakDevicesSelectPerformance() {
        assertEquals(
            LiquidGlassProfile.PREMIUM,
            LiquidGlassDeviceCapability.selectAutomaticProfile(
                capabilities(memoryClass = 512, processors = 8)
            )
        )
        assertEquals(
            LiquidGlassProfile.PERFORMANCE,
            LiquidGlassDeviceCapability.selectAutomaticProfile(
                capabilities(memoryClass = 128, processors = 2)
            )
        )
    }

    @Test
    fun highRefreshDisplayUsesBalancedUnlessDeviceIsFlagshipTier() {
        assertEquals(
            LiquidGlassProfile.BALANCED,
            LiquidGlassDeviceCapability.selectAutomaticProfile(
                capabilities(memoryClass = 256, processors = 6, highRefresh = true, refreshRate = 120f)
            )
        )
        assertEquals(
            LiquidGlassProfile.PREMIUM,
            LiquidGlassDeviceCapability.selectAutomaticProfile(
                capabilities(memoryClass = 512, processors = 8, highRefresh = true, refreshRate = 120f)
            )
        )
    }

    @Test
    fun explicitProfilesRemainStableSingletonConfigurations() {
        assertEquals("Premium", LiquidGlassProfile.PREMIUM.name)
        assertEquals("Balanced", LiquidGlassProfile.BALANCED.name)
        assertEquals("Performance", LiquidGlassProfile.PERFORMANCE.name)
    }
}
