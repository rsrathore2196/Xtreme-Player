package com.example

import com.example.ui.viewmodel.HomeFeedSection
import com.example.ui.viewmodel.HomeFeedWindow
import com.example.ui.viewmodel.HomeRevealGesture
import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import org.junit.Assert.*
import org.junit.Test

class HomeFeedWindowTest {
    @Test fun initialWindowAndNetworkBootstrapContainOnlyTheTwoDiscoveryShelves() {
        assertEquals(listOf(HomeFeedSection.QUICK, HomeFeedSection.CHART), HomeFeedWindow().sections)
        assertEquals(setOf(Shelf.QUICK, Shelf.CHART), HomeFeedWindow().shelves)
        assertEquals(HomeFeedWindow.BOOTSTRAP_SHELVES, HomeFeedWindow().shelves)
    }

    @Test fun eachDemandAppendsExactlyOneEligibleSectionWithoutReorderingEarlierCards() {
        var window = HomeFeedWindow()
        val all = HomeFeedSection.entries.toSet()
        for (section in listOf(HomeFeedSection.ARTISTS, HomeFeedSection.ERA, HomeFeedSection.PERSONALIZED,
            HomeFeedSection.SIMILAR, HomeFeedSection.INSPIRED)) {
            val previous = window.sections
            window = window.appendNext(all)
            assertEquals(previous + section, window.sections)
        }
        assertSame(window, window.appendNext(all))
    }

    @Test fun noHistoryDoesNotUnlockPersonalizedOrArtistShelves() {
        val initial = HomeFeedWindow()
        assertSame(initial, initial.appendNext(HomeFeedWindow.INITIAL.toSet()))
        val laterHistory = initial.appendNext(setOf(HomeFeedSection.ARTISTS, HomeFeedSection.ERA))
        assertEquals(HomeFeedSection.ARTISTS, laterHistory.sections.last())
        assertEquals(HomeFeedWindow.INITIAL, initial.sections)
    }

    @Test fun cachedLateEligibilityAppendsWithoutChangingPreviouslyRevealedPositions() {
        val era = HomeFeedWindow().appendNext(setOf(HomeFeedSection.ERA))
        val artist = era.appendNext(setOf(HomeFeedSection.ARTISTS, HomeFeedSection.ERA))
        assertEquals(era.sections + HomeFeedSection.ARTISTS, artist.sections)
    }

    @Test fun initialLayoutAndRepeatedTailMeasurementsNeverCountAsScrollIntent() {
        val gate = HomeRevealGesture()
        repeat(10) { assertFalse(gate.consumeNearTail(0L, nearTail = true)) }
        val intent = gate.forwardInput()
        assertFalse(gate.consumeNearTail(intent, nearTail = false))
        assertTrue(gate.consumeNearTail(intent, nearTail = true))
        repeat(10) { assertFalse(gate.consumeNearTail(gate.forwardInput(), nearTail = true)) }
        gate.settled()
        assertTrue(gate.consumeNearTail(gate.forwardInput(), nearTail = true))
    }
}
