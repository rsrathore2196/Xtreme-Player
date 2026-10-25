package com.example

import com.example.data.model.LyricLine
import com.example.ui.components.LyricLineTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricLineTimelineTest {
    @Test fun timelineMatchesOriginalSemanticsAcrossDuplicatesAndBackwardSeeks() {
        val lines = List(2_000) { LyricLine((it / 2) * 100L, "Line $it") }
        val timeline = LyricLineTimeline.from(lines)
        for (position in listOf(-1L, 0L, 99L, 100L, 99_999L, 400L, 0L, Long.MAX_VALUE)) {
            assertEquals(lines.indexOfLast { it.timestampMs <= position }, timeline.activeLine(position))
        }
    }
    @Test fun unorderedImportedLyricsAndEmptyListsRemainSafe() {
        val lines = listOf(LyricLine(300, "Third"), LyricLine(100, "First"), LyricLine(200, "Second"))
        val timeline = LyricLineTimeline.from(lines)
        for (position in -1L..400L) {
            assertEquals(lines.indexOfLast { it.timestampMs <= position }, timeline.activeLine(position))
        }
        assertEquals(-1, LyricLineTimeline.from(emptyList()).activeLine(1_000))
    }
}
