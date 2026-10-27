package com.example

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.LyricLine
import com.example.data.model.LyricSyllable
import com.example.data.model.LyricWord
import com.example.data.model.TrackLyrics
import com.example.ui.components.SyncedLyricsView
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsPreparationUiTest {
    @get:Rule val compose = createComposeRule()
    private class ObservedSyllables(private val start: Long) : AbstractList<LyricSyllable>() {
        val reads = AtomicInteger()
        val hashes = AtomicInteger()
        override val size = 2
        override fun get(index: Int): LyricSyllable {
            reads.incrementAndGet()
            return LyricSyllable(start + index * 500L, 500L, if (index == 0) "hel" else "lo")
        }
        override fun hashCode(): Int { hashes.incrementAndGet(); return 0 }
    }

    @Test fun openingBeforeFirstVocalDoesNotHashTheDocumentOrPrepareInactiveReveals() = checkOpening(0L, null)
    @Test fun openingDuringFirstVocalPreparesOnlyItsRealSyllableGeometry() = checkOpening(1_500L, 0)
    @Test fun openingDuringLaterVocalPreparesOnlyItsRealSyllableGeometry() = checkOpening(4_500L, 1)

    @Test fun reopeningStartsAtTheCurrentVocalDuringTheEntrance() = checkReopening()
    @Test fun reopeningUsesTheSavedLyricsOffsetDuringTheEntrance() = checkReopening(offsetMs = 4_000)
    @Test fun lyricsArrivingDuringTheEntranceStartAtTheCurrentVocal() = checkReopening(delayedLyrics = true)
    @Test fun retainedLyricsRecenterAtTheNewVocalDuringTheEntrance() = checkReopening(retained = true)

    private fun checkReopening(offsetMs: Int = 0, delayedLyrics: Boolean = false, retained: Boolean = false) {
        val lines = List(40) { index -> LyricLine(1_000 + index * 5_000L, "Opening lyric $index", 4_000) }
        val lyrics = TrackLyrics("opening", "Song", "Artist", true, lines)
        var visible by mutableStateOf(true)
        var available by mutableStateOf(!delayedLyrics)
        var playhead by mutableLongStateOf(61_500L + offsetMs)
        var renderedInput = Triple(false, false, Long.MIN_VALUE)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val input = Triple(visible, available, playhead)
            SideEffect { renderedInput = input }
            Box(Modifier.fillMaxSize()) {
                if (visible) SyncedLyricsView(if (available) lyrics else null,
                    currentPositionMs = playhead, isPlaying = false, onSeekTo = {},
                    currentPositionProvider = { playhead }, viewportAnimating = true,
                    lyricsOffsetMs = offsetMs)
            }
        } }
        fun awaitInput() {
            compose.waitUntil(3_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnIdle { renderedInput == Triple(visible, available, playhead) }
            }
        }
        awaitInput()
        if (delayedLyrics) compose.runOnIdle { available = true }
        awaitInput()
        assertIncomingLine(12)
        if (!retained) {
            compose.runOnIdle { visible = false }
            awaitInput()
        }
        compose.runOnIdle { playhead = 101_500L + offsetMs; visible = true }
        awaitInput()
        assertIncomingLine(20)
    }

    private fun assertIncomingLine(index: Int) {
        // Stay inside the existing entrance's initial 160 ms fade delay.
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("Opening lyric $index").assertIsDisplayed()
            compose.onNodeWithText("Opening lyric 0").assertDoesNotExist()
        }
        val line = compose.onNodeWithText("Opening lyric $index").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("synced_lyrics_view").fetchSemanticsNode().boundsInRoot
        assertEquals("The active vocal is already centered before the entrance reveals it",
            viewport.center.y, line.center.y, with(compose.density) { 3.dp.toPx() })
    }

    private fun checkOpening(position: Long, activeIndex: Int?) {
        val syllables = List(12) { ObservedSyllables(1_000 + it * 3_000L) }
        val lyrics = TrackLyrics("workload", "Song", "Artist", true, syllables.mapIndexed { index, timing ->
            val start = 1_000 + index * 3_000L
            LyricLine(start, "hello line $index", 1_000, listOf(LyricWord(start, 1_000, "hello", timing)))
        }, hasWordTiming = true, hasSyllableTiming = true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            SyncedLyricsView(lyrics, currentPositionMs = position, isPlaying = false,
                onSeekTo = {}, currentPositionProvider = { position }, viewportAnimating = true)
        } }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("hello line 0").assertExists()
        assertEquals("Composition keys must not recursively hash every word/syllable", 0, syllables.sumOf { it.hashes.get() })
        if (activeIndex == null) {
            assertEquals("Before the first vocal no timed glyph geometry is visible", 0, syllables.sumOf { it.reads.get() })
        } else {
            compose.waitUntil(3_000) { compose.mainClock.advanceTimeByFrame(); syllables[activeIndex].reads.get() > 0 }
            assertTrue("The active line retains real syllable geometry", syllables[activeIndex].reads.get() > 0)
            assertEquals("Inactive rows require no glyph preparation", 0,
                syllables.filterIndexed { index, _ -> index != activeIndex }.sumOf { it.reads.get() })
        }
        assertEquals(0, syllables.sumOf { it.hashes.get() })
    }
}
