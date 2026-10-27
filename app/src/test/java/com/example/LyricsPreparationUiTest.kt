package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
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
