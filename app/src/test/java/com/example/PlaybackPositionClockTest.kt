package com.example

import com.example.playback.PlaybackPositionClock
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionClockTest {
    @Test fun interpolatesWithoutControllerAccessAndStopsOnPause() {
        var now = 0L
        val clock = PlaybackPositionClock { now }
        clock.sample(10_000, true)
        now = 250
        assertEquals(10_250L, clock.position(0, 100_000))
        clock.sample(10_250, false)
        now = 600
        assertEquals(10_250L, clock.position(0, 100_000))
    }
    @Test fun backwardSeekImmediatelyReanchorsAndSpeedIsPreserved() {
        var now = 0L
        val clock = PlaybackPositionClock { now }
        clock.sample(20_000, true)
        now = 500
        clock.sample(5_000, true, 2f)
        now = 1_000
        assertEquals(6_000L, clock.position(0, 100_000))
    }
    @Test fun missingOrStalledSamplesCannotInventUnboundedProgress() {
        var now = 0L
        val clock = PlaybackPositionClock { now }
        assertEquals(2_000L, clock.position(2_000, 100_000))
        clock.sample(5_000, true)
        now = 10_000
        assertEquals(5_750L, clock.position(0, 100_000))
        assertEquals(5_500L, clock.position(0, 5_500))
    }
}
