package com.example.recommendation

import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InitialShelfConcurrencyTest {
    @Test fun popupRequestsUseThreeVerifiedMatchesEvenWithoutTheLegacyStartupFlag() {
        assertEquals(3, UnifiedRecommendationEngine.requestedShelfSize(false, true, 16))
        assertEquals(3, UnifiedRecommendationEngine.requestedShelfSize(true, false, 16))
        assertEquals(16, UnifiedRecommendationEngine.requestedShelfSize(false, false, 16))
    }

    @Test fun aSlowChartDoesNotBlockQuickPicksAndInitialShelfConcurrencyIsBounded() = runTest {
        val completed = mutableListOf<Shelf>()
        var running = 0
        var maximum = 0
        val task = launch {
            UnifiedRecommendationEngine.prepareShelves(listOf(Shelf.QUICK, Shelf.CHART, Shelf.ERA), true) { shelf ->
                running++; maximum = maxOf(maximum, running)
                try {
                    delay(if (shelf == Shelf.CHART) 4000 else 100)
                    completed.add(shelf)
                } finally { running-- }
            }
        }
        runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf(Shelf.QUICK), completed)
        assertTrue(task.isActive)
        advanceUntilIdle()
        assertEquals(2, maximum)
        assertEquals(0, running)
        assertEquals(listOf(Shelf.QUICK, Shelf.ERA, Shelf.CHART), completed)
    }

    @Test fun oneSharedDeadlineCancelsUnfinishedShelvesAndRetainsCompletedResults() = runTest {
        val complete = mutableListOf<Shelf>()
        val cancelled = mutableListOf<Shelf>()
        val priority = DiscoveryRequestCoordinator.HomeRefresh(false, initialPresentation = true)
        val result = priority.withBudget(DiscoveryRequestCoordinator.INITIAL_DISCOVERY_BUDGET_MS) {
            UnifiedRecommendationEngine.prepareShelves(listOf(Shelf.QUICK, Shelf.CHART), true) { shelf ->
                try {
                    if (shelf == Shelf.QUICK) { delay(100); complete.add(shelf) }
                    else awaitCancellation()
                } finally { if (!currentCoroutineContext().isActive) cancelled.add(shelf) }
            }
            true
        }
        assertNull(result)
        assertEquals(DiscoveryRequestCoordinator.INITIAL_DISCOVERY_BUDGET_MS, currentTime)
        assertEquals(listOf(Shelf.QUICK), complete)
        assertEquals(listOf(Shelf.CHART), cancelled)
        assertTrue(currentTime in 5_000L..8_000L)
    }

    @Test fun lifecycleCancellationStopsAllChildrenInsteadOfStartingAnotherShelf() = runTest {
        val started = mutableListOf<Shelf>()
        val stopped = mutableListOf<Shelf>()
        val job = launch {
            UnifiedRecommendationEngine.prepareShelves(Shelf.entries, true) { shelf ->
                started.add(shelf)
                try { awaitCancellation() } finally { stopped.add(shelf) }
            }
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(2, started.size)
        assertEquals(started.toSet(), stopped.toSet())
    }

    @Test fun optionalShelfWorkRemainsOneAtATime() = runTest {
        var active = 0
        var maximum = 0
        val visited = mutableListOf<Shelf>()
        UnifiedRecommendationEngine.prepareShelves(Shelf.entries, false) { shelf ->
            visited += shelf
            active++; maximum = maxOf(maximum, active)
            delay(100)
            active--
        }
        assertEquals(1, maximum)
        assertEquals(Shelf.entries, visited)
        assertEquals(Shelf.entries.size * 100L, currentTime)
    }
}
