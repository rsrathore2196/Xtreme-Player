package com.example.performance

import android.app.Application
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Keep this process-wide scheduler in a separate SDK sandbox: other app-backed
// tests manually construct Home ViewModels without clearing their waiting jobs.
@Config(sdk = [33], application = Application::class)
class StartupCooperationRegressionTest {
    private val coordinator = BackgroundWorkCoordinator
    @Before fun ready() {
        coordinator.firstFrameRendered()
        coordinator.cachedHomeRestored()
        coordinator.homeContentReady()
        coordinator.foreground(true)
        coordinator.scrolling(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    }
    @After fun stop() {
        coordinator.scrolling(false)
        coordinator.foreground(false)
    }

    private fun schedulerState(): String = listOf("jobs", "essentialActive", "optionalActive", "foreground",
        "lastInteraction", "lastSlowFrame", "navigationJobs", "navigationUntil", "scrolling", "waiting").joinToString {
        val field = BackgroundWorkCoordinator::class.java.getDeclaredField(it).apply { isAccessible = true }
        "$it=${field.get(null)}"
    } + " elapsed=${android.os.SystemClock.elapsedRealtime()}"

    @Test fun anAdmittedBatchYieldsToScrollingWithoutBlockingCriticalPlayback() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        val optional = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                entered.complete(Unit)
                coordinator.scrolling(true)
                coordinator.yieldToInteraction()
                resumed.complete(Unit)
            }
        }
        runCurrent()
        assertTrue(schedulerState(), entered.isCompleted)
        assertFalse(resumed.isCompleted)
        var playbackRestored = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { playbackRestored = true }
        assertTrue(playbackRestored)
        assertFalse(resumed.isCompleted)
        coordinator.scrolling(false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        advanceTimeBy(100)
        runCurrent()
        assertTrue(schedulerState(), resumed.isCompleted)
        optional.join()
    }

    @Test fun newOptionalBatchesAreNotAdmittedDuringScrolling() = runTest {
        coordinator.scrolling(true)
        var ran = false
        val optional = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { ran = true }
        }
        runCurrent()
        assertFalse(ran)
        coordinator.scrolling(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        advanceTimeBy(100)
        runCurrent()
        assertTrue(schedulerState(), ran)
        optional.join()
    }

    @Test fun cancellationReleasesAnAdmittedSlotAndItsBusyLease() = runTest {
        val first = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                coordinator.scrolling(true)
                coordinator.yieldToInteraction()
            }
        }
        runCurrent()
        first.cancelAndJoin()
        coordinator.scrolling(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        var ran = false
        val replacement = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { ran = true }
        }
        runCurrent()
        assertTrue(schedulerState(), ran)
        replacement.join()
    }

    @Test fun anAdmittedTaskDoesNotWaitForItsOwnBusyLease() = runTest {
        var completed = false
        val work = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                coordinator.awaitIdle(quietMillis = 0, minimumUptimeMillis = 0)
                coordinator.yieldToInteraction()
                completed = true
            }
        }
        runCurrent()
        assertTrue(schedulerState(), completed)
        work.join()
    }

    @Test fun navigationDefersEssentialHomeWorkButNotCriticalPlayback() = runTest {
        val navigation = coordinator.navigationBusy()
        var prepared = false
        val essential = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { prepared = true }
        }
        try {
            runCurrent()
            assertFalse(prepared)
            var played = false
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { played = true }
            assertTrue(played)
            assertFalse(prepared)
        } finally { navigation.close() }
        advanceTimeBy(100)
        runCurrent()
        assertTrue(schedulerState(), prepared)
        essential.join()
    }
}
