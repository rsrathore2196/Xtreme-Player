package com.example

import android.app.Application
import org.robolectric.annotation.Config
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LyricsOffsetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LyricsOffsetTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("lyrics_offset_preferences", 0).edit().clear().commit()
        LyricsOffsetManager.resetOffset(context, "test_track_1", applyToAll = true)
    }

    @Test fun persistedTrackOffsetIsLoadedByTheObserverWithoutAUserEdit() = runBlocking {
        withContext(Dispatchers.IO) {
            context.getSharedPreferences("lyrics_offset_preferences", 0).edit()
                .putInt("global_lyrics_offset_ms", 500).putInt("track_lyrics_offset_saved", -800).commit()
        }
        assertEquals(-800, withTimeout(2_000) { LyricsOffsetManager.observeOffsetForTrack(context, "saved").first() })
        assertEquals(-800, LyricsOffsetManager.activeOffsetMs.value)
        assertFalse(LyricsOffsetManager.usesGlobalOffset(context, "saved"))
        assertEquals(500, withTimeout(2_000) { LyricsOffsetManager.observeOffsetForTrack(context, "other").first() })
        assertTrue(LyricsOffsetManager.usesGlobalOffset(context, "other"))
    }

    @Test fun activeObserverTracksGlobalEditsAndKeepsZeroTrackOverrides() = runBlocking {
        val values = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.Default) { LyricsOffsetManager.observeOffsetForTrack(context, "observed").toList(values) }
        try {
            withTimeout(2_000) { while (values.isEmpty()) delay(10) }
            LyricsOffsetManager.updateCurrentOffset(context, null, 600, true)
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(2_000) { while (values.last() != 600) { shadowOf(Looper.getMainLooper()).idle(); delay(10) } }
            LyricsOffsetManager.updateCurrentOffset(context, "observed", 0)
            shadowOf(Looper.getMainLooper()).idle()
            withTimeout(2_000) { while (values.last() != 0) { shadowOf(Looper.getMainLooper()).idle(); delay(10) } }
            LyricsOffsetManager.updateCurrentOffset(context, null, 900, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, LyricsOffsetManager.getEffectiveOffset(context, "observed"))
            assertEquals(0, LyricsOffsetManager.activeOffsetMs.value)
        } finally { job.cancelAndJoin() }
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
