package com.example.performance

import org.junit.Assert.*
import org.junit.Test

class StartupReadinessTest {
    @Test fun actualCompletedUnitsDriveProgressAndUnfinishedGroupsNeverReportReady() {
        val empty = StartupReadiness()
        assertEquals(0f, empty.progress)
        assertEquals(StartupStatus.PENDING, empty.status(StartupGroup.HOME))
        val active = empty.withStatus(StartupTask.HOME_CACHE, StartupStatus.RUNNING)
        assertEquals(0f, active.progress)
        assertEquals(StartupStatus.RUNNING, active.status(StartupGroup.HOME))
        val completed = active.withStatus(StartupTask.HOME_CACHE, StartupStatus.READY)
        assertEquals(1f / StartupTask.entries.size, completed.progress)
        assertFalse(completed.complete)
        assertEquals(StartupStatus.RUNNING, completed.status(StartupGroup.HOME))
    }

    @Test fun emptyHistoryAndNoPlayerSessionCanFinishIndependentlyOfHome() {
        val ready = listOf(StartupTask.RECENT_HISTORY, StartupTask.RECENT_ARTISTS,
            StartupTask.PLAYER_SESSION, StartupTask.PLAYER_CONNECTION).fold(StartupReadiness()) { state, task ->
            state.withStatus(task, StartupStatus.READY)
        }
        assertEquals(StartupStatus.READY, ready.status(StartupGroup.RECENTS))
        assertEquals(StartupStatus.READY, ready.status(StartupGroup.PLAYER))
        assertEquals(StartupStatus.PENDING, ready.status(StartupGroup.HOME))
        assertFalse(ready.preparationComplete)
    }

    @Test fun homeCanBeReadyWhileThePlayerIsStillRecoveringAndTheGlobalDrawIsPending() {
        val ready = StartupTask.entries.filter { it.group == StartupGroup.HOME && it != StartupTask.PRESENTATION }
            .fold(StartupReadiness()) { state, task -> state.withStatus(task, StartupStatus.READY) }
        assertEquals(StartupStatus.READY, ready.status(StartupGroup.HOME))
        assertEquals(StartupStatus.PENDING, ready.status(StartupGroup.PLAYER))
        assertFalse(ready.preparationComplete)
        assertFalse(ready.complete)
    }

    @Test fun unavailableRemoteDataIsTerminalButNotMisrepresentedAsSuccess() {
        val ready = StartupTask.entries.fold(StartupReadiness()) { state, task ->
            state.withStatus(task, if (task == StartupTask.CHART) StartupStatus.UNAVAILABLE else StartupStatus.READY)
        }
        assertTrue(ready.complete)
        assertTrue(ready.hasUnavailable)
        assertEquals(1f, ready.progress)
        assertEquals(StartupStatus.UNAVAILABLE, ready.status(StartupGroup.HOME))
    }

    @Test fun preparationCannotReach100PercentUntilActualPresentationSettles() {
        val prepared = StartupTask.entries.filter { it != StartupTask.PRESENTATION }
            .fold(StartupReadiness()) { state, task -> state.withStatus(task, StartupStatus.FALLBACK_READY) }
        assertTrue(prepared.preparationComplete)
        assertFalse(prepared.complete)
        assertTrue(prepared.progress < 1f)
        val presented = prepared.withStatus(StartupTask.PRESENTATION, StartupStatus.READY)
        assertTrue(presented.complete)
        assertEquals(StartupStatus.FALLBACK_READY, presented.status(StartupGroup.HOME))
    }

    @Test fun warmReopeningAndDuplicateCallbacksRetainTheSameCompletedSnapshot() {
        val ready = StartupTask.entries.fold(StartupReadiness()) { state, task -> state.withStatus(task, StartupStatus.READY) }
        assertSame(ready, ready.withStatus(StartupTask.HOME_CACHE, StartupStatus.RUNNING))
        assertSame(ready, ready.withStatus(StartupTask.PLAYER_CONNECTION, StartupStatus.READY))
        val fallback = StartupReadiness().withStatus(StartupTask.CHART, StartupStatus.UNAVAILABLE)
        assertSame(fallback, fallback.withStatus(StartupTask.CHART, StartupStatus.READY))
    }

    @Test fun processingDetailsDescribeActualWorkWithoutExposingNavigationStyleGroups() {
        val pending = StartupReadiness()
        assertEquals("Preparing your music", pending.detail)
        assertEquals("Restoring your saved music", pending.withStatus(StartupTask.HOME_CACHE, StartupStatus.RUNNING).detail)
        assertEquals("Finding music for your taste", pending.withStatus(StartupTask.ERA, StartupStatus.RUNNING).detail)
        assertEquals("Restoring your last session", pending.withStatus(StartupTask.PLAYER_CONNECTION, StartupStatus.RUNNING).detail)
    }
}
