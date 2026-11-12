package com.example.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceRefreshRatePolicyTest {

    @Test fun ratesAtAnotherResolutionCannotForceAResolutionSwitch() {
        val modes = listOf(
            DisplayModeRefreshRates(1440, 3200, 60f, listOf(90f)),
            DisplayModeRefreshRates(1080, 2400, 120f),
            DisplayModeRefreshRates(1440, 3200, Float.NaN, listOf(Float.NaN, 0f))
        )
        val rates = DisplayRefreshRatePolicy.supportedPerformanceRefreshRates(1440, 3200, modes, 60f)
        assertEquals(listOf(60, 90), rates)
        assertEquals(90, DisplayRefreshRatePolicy.resolvePreferredRefreshRate(120, rates))
        assertEquals(listOf(120), DisplayRefreshRatePolicy.supportedPerformanceRefreshRates(1080, 2400, modes, 60f))
    }

    @Test fun fixedRateAndPowerSavingVotesAreReleasedWhenTheWindowIsInactive() {
        assertEquals(WindowRefreshRateRequest(120f, true, false),
            DisplayRefreshRatePolicy.resolveWindowRequest(true, 120, true, listOf(60, 120)))
        assertEquals(WindowRefreshRateRequest(0f, false, true),
            DisplayRefreshRatePolicy.resolveWindowRequest(true, 120, false, listOf(60, 120)))
        assertEquals(WindowRefreshRateRequest(0f, true, true),
            DisplayRefreshRatePolicy.resolveWindowRequest(false, 120, true, listOf(60, 120)))
        assertEquals(WindowRefreshRateRequest(0f, true, false),
            DisplayRefreshRatePolicy.resolveWindowRequest(true, 0, true, listOf(60, 120)))
        assertEquals(WindowRefreshRateRequest(90f, true, false),
            DisplayRefreshRatePolicy.resolveWindowRequest(true, 120, true, listOf(60, 90)))
    }

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
