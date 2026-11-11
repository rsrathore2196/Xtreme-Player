package com.example

import com.example.performance.DisplayRefreshRatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshRatePolicyTest {

    @Test
    fun testSamsungS22SeriesIdentification() {
        // S22 Base
        assertTrue(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "samsung",
            model = "SM-S901B",
            sdkInt = 35
        ))
        // S22 Plus
        assertTrue(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "samsung",
            model = "SM-S906U",
            sdkInt = 34
        ))
        // S22 Ultra
        assertTrue(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "Samsung",
            model = "SM-S908N",
            sdkInt = 33
        ))
    }

    @Test
    fun testSamsungOnAndroid12To14Identification() {
        // Samsung Galaxy on Android 12 (API 31)
        assertTrue(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "samsung",
            model = "SM-G998B",
            sdkInt = 31
        ))
        // Samsung Galaxy on Android 13 (API 33)
        assertTrue(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "samsung",
            model = "SM-A536B",
            sdkInt = 33
        ))
        // Samsung Galaxy on Android 14 (API 34)
        assertTrue(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "samsung",
            model = "SM-S911B",
            sdkInt = 34
        ))
    }

    @Test
    fun testNonSamsungDevicesNotAffected() {
        // Google Pixel 7 (API 33)
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "Google",
            model = "Pixel 7",
            sdkInt = 33
        ))
        // OnePlus 11 (API 34)
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "OnePlus",
            model = "CPH2449",
            sdkInt = 34
        ))
        // Xiaomi 13 (API 34)
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "Xiaomi",
            model = "2211133G",
            sdkInt = 34
        ))
        // Motorola Edge 40 (API 33)
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "motorola",
            model = "motorola edge 40",
            sdkInt = 33
        ))
    }

    @Test
    fun testSamsungOnAndroid15UnaffectedUnlessS22() {
        // Samsung S24 on Android 15 (API 35) should not be affected
        assertFalse(DisplayRefreshRatePolicy.isAffectedSamsungDevice(
            manufacturer = "samsung",
            model = "SM-S921B",
            sdkInt = 35
        ))
    }

    @Test
    fun testApplyAdaptiveRefreshRatePolicySafeExecution() {
        // Must execute cleanly without uncaught exceptions regardless of classpath presence
        DisplayRefreshRatePolicy.applyAdaptiveRefreshRatePolicy()
    }

    @Test
    fun testNullDisplayResolutionFallback() {
        val (hasHighRefresh, maxRate) = DisplayRefreshRatePolicy.resolveDisplayRefreshCapabilities(null)
        assertFalse(hasHighRefresh)
        assertEquals(60f, maxRate, 0.01f)
    }
}
