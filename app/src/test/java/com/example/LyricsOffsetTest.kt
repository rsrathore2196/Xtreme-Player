package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LyricsOffsetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsOffsetTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LyricsOffsetManager.resetOffset(context, "test_track_1", applyToAll = true)
    }

    @Test
    fun positiveOffsetDelaysLyrics() {
        assertEquals(8_500L, LyricsOffsetManager.adjustedLyricsPosition(positionMs = 10_000L, offsetMs = 1_500))
    }

    @Test
    fun negativeOffsetAdvancesLyrics() {
        assertEquals(11_500L, LyricsOffsetManager.adjustedLyricsPosition(positionMs = 10_000L, offsetMs = -1_500))
    }

    @Test
    fun offsetNeverProducesNegativeLyricClock() {
        assertEquals(0L, LyricsOffsetManager.adjustedLyricsPosition(positionMs = 500L, offsetMs = 1_500))
    }

    @Test
    fun seekingALyricIncludesItsOffset() {
        assertEquals(11_500L, LyricsOffsetManager.adjustedLyricsSeekTarget(lineTimeMs = 10_000L, offsetMs = 1_500))
        assertEquals(8_500L, LyricsOffsetManager.adjustedLyricsSeekTarget(lineTimeMs = 10_000L, offsetMs = -1_500))
    }

    @Test
    fun formatOffsetProducesConsistentLabels() {
        assertEquals("0.0 s", LyricsOffsetManager.formatOffset(0))
        assertEquals("+0.5 s", LyricsOffsetManager.formatOffset(500))
        assertEquals("-0.3 s", LyricsOffsetManager.formatOffset(-300))
        assertEquals("+5.0 s", LyricsOffsetManager.formatOffset(5000))
        assertEquals("-5.0 s", LyricsOffsetManager.formatOffset(-5000))
    }

    @Test
    fun trackSpecificOffsetOverridesGlobal() {
        LyricsOffsetManager.updateCurrentOffset(context, null, 200, applyToAll = true)
        assertEquals(200, LyricsOffsetManager.getEffectiveOffset(context, "track_a"))

        LyricsOffsetManager.updateCurrentOffset(context, "track_a", -400, applyToAll = false)
        assertTrue(LyricsOffsetManager.isTrackSpecificOffset(context, "track_a"))
        assertFalse(LyricsOffsetManager.isTrackSpecificOffset(context, "track_b"))

        assertEquals(-400, LyricsOffsetManager.loadOffsetForTrack(context, "track_a"))
        assertEquals(200, LyricsOffsetManager.loadOffsetForTrack(context, "track_b"))
    }
}
