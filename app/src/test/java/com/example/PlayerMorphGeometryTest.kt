package com.example

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import com.example.ui.components.PlayerMorphBoundsPath
import org.junit.Assert.*
import org.junit.Test

class PlayerMorphGeometryTest {
    private val mini = Rect(24f, 750f, 390f, 820f)
    private val expanded = Rect(0f, 0f, 414f, 896f)

    @Test fun bothDirectionsVisitTheSameMeasuredRectanglesAndExactEndpoints() {
        val path = PlayerMorphBoundsPath()
        val outward = (0..100).map { path.value(it / 100f, mini, expanded) }
        val inward = (100 downTo 0).map { path.value(it / 100f, mini, expanded) }
        assertEquals(outward, inward.reversed())
        assertEquals(mini, outward.first())
        assertEquals(expanded, outward.last())
        assertEquals(lerp(mini, expanded, .5f), outward[50])
    }

    @Test fun changedInsetsOrCompactBoundsRebaseWithoutAPositionJumpAndRemainReversible() {
        val path = PlayerMorphBoundsPath()
        val before = path.value(.42f, mini, expanded)
        val movedMini = Rect(180f, 790f, 390f, 850f)
        val resized = Rect(0f, 0f, 896f, 414f)
        assertEquals(before, path.value(.42f, movedMini, resized))
        val continued = path.value(.63f, movedMini, resized)
        assertEquals(resized, path.value(1f, movedMini, resized))
        assertEquals(continued, path.value(.63f, movedMini, resized))
        assertEquals(before, path.value(.42f, movedMini, resized))
        assertEquals(movedMini, path.value(0f, movedMini, resized))
    }

    @Test fun lateDestinationMeasurementStartsAtTheVisibleFallbackRatherThanSnapping() {
        val path = PlayerMorphBoundsPath()
        val fallback = path.value(.15f, mini, mini)
        assertEquals(mini.left, fallback.left, .001f)
        assertEquals(mini.top, fallback.top, .001f)
        assertEquals(fallback, path.value(.15f, mini, expanded))
        assertEquals(expanded, path.value(1f, mini, expanded))
        assertEquals(mini, path.value(0f, mini, expanded))
    }
}
