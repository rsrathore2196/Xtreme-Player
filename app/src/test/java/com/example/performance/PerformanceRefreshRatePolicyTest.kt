package com.example.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceRefreshRatePolicyTest {

    @Test
    fun autoDoesNotForceAnExplicitRefreshRate() {
        assertEquals(0, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(0, listOf(60, 90, 120)))
    }

    @Test
    fun exact120HzPreferenceResolvesTo120WhenPanelSupportsIt() {
        assertEquals(120, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(120, listOf(60, 90, 120)))
    }

    @Test
    fun unsupported120HzPreferenceFallsBackToNearestSupportedMode() {
        assertEquals(90, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(120, listOf(60, 90)))
        assertEquals(90, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(120, listOf(60, 90, 160)))
        assertEquals(144, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(120, listOf(60, 90, 144)))
    }

    @Test
    fun exactTiePrefersHigherRefreshRate() {
        assertEquals(120, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(105, listOf(90, 120)))
    }

    @Test
    fun invalidSupportedRatesAreIgnored() {
        assertEquals(90, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(120, listOf(0, 30, 90, 300)))
    }

    @Test
    fun emptySupportedModeListUsesClampedPreference() {
        assertEquals(120, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(120, emptyList()))
        assertEquals(240, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(500, emptyList()))
    }
}
