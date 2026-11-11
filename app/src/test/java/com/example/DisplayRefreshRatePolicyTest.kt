package com.example

import com.example.performance.DisplayRefreshRatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshRatePolicyTest {

    @Test
    fun samsungS22FamilyIsAffectedOnlyOnAndroid12Through14() {
        val models = listOf("SM-S901B", "SM-S906U", "SM-S908N")
        for (model in models) {
            for (sdk in 31..34) {
                assertTrue(
                    "Expected known S22 combination to be affected: $model / API $sdk",
                    DisplayRefreshRatePolicy.isAffectedSamsungDevice(
                        manufacturer = "Samsung",
                        model = model,
                        sdkInt = sdk
                    )
                )
            }
            assertFalse(
                "Do not disable adaptive refresh rate outside the known OS range",
                DisplayRefreshRatePolicy.isAffectedSamsungDevice(
                    manufacturer = "samsung",
                    model = model,
                    sdkInt = 35
                )
            )
        }
    }

    @Test
    fun otherSamsungModelsAreNotAffectedByTheS22SpecificWorkaround() {
        val models = listOf("SM-A536B", "SM-S911B", "SM-G998B", "SM-S921B")
        for (model in models) {
            for (sdk in 31..35) {
                assertFalse(
                    "Unknown Samsung model must retain native adaptive refresh rate: $model / API $sdk",
                    DisplayRefreshRatePolicy.isAffectedSamsungDevice(
                        manufacturer = "samsung",
                        model = model,
                        sdkInt = sdk
                    )
                )
            }
        }
    }

    @Test
    fun nonSamsungAndUnknownDeviceIdentitiesAreNotAffected() {
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice("Google", "Pixel 7", 33))
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice("OnePlus", "CPH2449", 34))
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice("Xiaomi", "2211133G", 34))
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice("motorola", "edge 40", 33))
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice("samsung", null, 33))
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice(null, "SM-S901B", 33))
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice("samsung", "SM-S901B", 30))
    }

    @Test
    fun applyAdaptiveRefreshRatePolicyExecutesWithoutUncaughtExceptions() {
        DisplayRefreshRatePolicy.applyAdaptiveRefreshRatePolicy()
    }

    @Test
    fun testNullDisplayResolutionFallback() {
        val (hasHighRefresh, maxRate) = DisplayRefreshRatePolicy.resolveDisplayRefreshCapabilities(null)
        assertFalse(hasHighRefresh)
        assertEquals(60f, maxRate, 0.01f)
    }
}
