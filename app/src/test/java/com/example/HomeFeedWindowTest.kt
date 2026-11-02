package com.example

import com.example.ui.viewmodel.HomeFeedWindow
import com.example.ui.viewmodel.HomeRevealGesture
import org.junit.Assert.*
import org.junit.Test

class HomeFeedWindowTest {
    @Test fun initialWindowUsesTheExistingFourServerShelves() { assertEquals(4, HomeFeedWindow().visibleCount) }
    @Test fun eachDemandRevealsOneAlreadyFetchedShelf() {
        val initial = HomeFeedWindow(); val next = initial.appendNext(9)
        assertEquals(5, next.visibleCount); assertEquals(4, initial.visibleCount)
        assertEquals(6, next.appendNext(9).visibleCount)
    }
    @Test fun demandNeverInventsUnavailableSectionsOrExceedsTheAvailableFeed() {
        val initial = HomeFeedWindow()
        assertSame(initial, initial.appendNext(2))
        val complete = initial.appendNext(5)
        assertEquals(5, complete.visibleCount); assertSame(complete, complete.appendNext(5))
    }
    @Test fun lateServerResultsDoNotChangeTheExistingDisplayWindow() {
        val initial = HomeFeedWindow(); assertEquals(4, initial.visibleCount)
        assertEquals(5, initial.appendNext(30).visibleCount)
    }
    @Test fun initialLayoutAndRepeatedTailMeasurementsNeverCountAsScrollIntent() {
        val gate = HomeRevealGesture()
        repeat(10) { assertFalse(gate.consumeNearTail(0L, nearTail = true)) }
        val intent = gate.forwardInput()
        assertFalse(gate.consumeNearTail(intent, nearTail = false)); assertTrue(gate.consumeNearTail(intent, nearTail = true))
        repeat(10) { assertFalse(gate.consumeNearTail(gate.forwardInput(), nearTail = true)) }
        gate.settled(); assertTrue(gate.consumeNearTail(gate.forwardInput(), nearTail = true))
    }
    @Test fun aCompletedPageDoesNotInventAnotherSwipeDemand() {
        val gate = HomeRevealGesture()
        val intent = gate.forwardInput()
        assertTrue(gate.consumeNearTail(intent, true))
        repeat(10) { assertFalse(gate.consumeNearTail(intent, true)) }
        gate.settled()
        assertTrue(gate.consumeNearTail(gate.forwardInput(), true))
    }

}
