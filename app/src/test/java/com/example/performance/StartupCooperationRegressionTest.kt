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
import org.junit.Assert.assertEquals
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
        // Robolectric resets its clock between methods but retains this singleton.
        // Rebase the sampled frame time before advancing to the fixture's idle state.
        coordinator.frame(2L, 1L)
        // Optional work has a 15s policy; reset completion clocks because this
        // singleton survives Robolectric's per-method clock reset.
        (BackgroundWorkCoordinator::class.java.getDeclaredField("lastOptionalCompleted").apply { isAccessible = true }
            .get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
        BackgroundWorkCoordinator::class.java.getDeclaredField("lastCardCompleted").apply { isAccessible = true }.setLong(null, 0L)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
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
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
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
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
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
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
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

    @Test fun optionalWorkNeedsContinuousIdleButVisibleDataDoesNot() = runTest {
        coordinator.interaction()
        var optionalRan = false
        val optional = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { optionalRan = true } }
        var visibleRan = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { visibleRan = true }
        assertTrue(visibleRan)
        runCurrent()
        assertFalse(optionalRan)
        ShadowSystemClock.advanceBy(Duration.ofMillis(14_999))
        advanceTimeBy(100)
        runCurrent()
        assertFalse(optionalRan)
        coordinator.interaction() // Interaction restarts the whole optional window.
        ShadowSystemClock.advanceBy(Duration.ofMillis(14_999))
        advanceTimeBy(100)
        runCurrent()
        assertFalse(optionalRan)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        advanceTimeBy(100)
        runCurrent()
        assertTrue(schedulerState(), optionalRan)
        optional.join()
    }

    @Test fun cardOwnershipSurvivesSuspensionAndNextCardWaitsForTheGap() = runTest {
        coordinator.interaction() // First visible cards must not wait for optional idle.
        val release = CompletableDeferred<Unit>()
        var running = 0
        var maximum = 0
        val order = mutableListOf<Int>()
        val first = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI, cardWork = true) {
            running++; maximum = maxOf(maximum, running); order.add(1)
            release.await()
            running--
        } }
        val second = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI, cardWork = true) {
            running++; maximum = maxOf(maximum, running); order.add(2); running--
        } }
        runCurrent()
        assertEquals(listOf(1), order)
        var played = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { played = true }
        assertTrue(played)
        release.complete(Unit)
        runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(749))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(1), order)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(1, 2), order)
        assertEquals(1, maximum)
        first.join(); second.join()
    }

    @Test fun anExplicitPullPromotesAQueuedCardWithoutBypassingItsOwnership() = runTest {
        coordinator.interaction()
        var requested = false
        var ran = false
        val card = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                urgent = { requested }, cardWork = true) { ran = true }
        }
        runCurrent()
        assertFalse(ran)
        requested = true
        advanceTimeBy(100)
        runCurrent()
        assertTrue(ran)
        card.join()
        // Promotion must release the *essential* slot it actually acquired.
        var essentialRan = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { essentialRan = true }
        assertTrue(essentialRan)
    }

    @Test fun optionalAdmissionStillChecksFrameHealthAndForegroundResume() = runTest {
        coordinator.foreground(false)
        coordinator.foreground(true)
        var ran = false
        val work = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.MAINTENANCE) { ran = true } }
        runCurrent()
        assertFalse(ran)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
        coordinator.frame(20_000_000L, 8_000_000L)
        advanceTimeBy(100)
        runCurrent()
        assertFalse(ran)
        ShadowSystemClock.advanceBy(Duration.ofMillis(250))
        advanceTimeBy(100)
        runCurrent()
        assertTrue(ran)
        work.join()
    }

    @Test fun optionalJobsAreSpacedAcrossSeparateCallers() = runTest {
        val order = mutableListOf<Int>()
        val first = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { order.add(1) } }
        val second = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.MAINTENANCE) { order.add(2) } }
        runCurrent()
        assertEquals(listOf(1), order)
        ShadowSystemClock.advanceBy(Duration.ofMillis(750))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(1, 2), order)
        first.join(); second.join()
    }

    @Test fun oneTabsScrollCleanupCannotReleaseAnotherTabsActiveFling() = runTest {
        val search = Any()
        val library = Any()
        coordinator.scrolling(search, true)
        coordinator.scrolling(library, true)
        coordinator.scrolling(search, false)
        var ran = false
        val essential = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { ran = true } }
        try {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
            advanceTimeBy(100)
            runCurrent()
            assertFalse(ran)
            coordinator.scrolling(library, false)
            advanceTimeBy(100)
            runCurrent()
            assertTrue(ran)
        } finally {
            coordinator.scrolling(search, false)
            coordinator.scrolling(library, false)
            essential.cancelAndJoin()
        }
    }

    @Test fun anOptionalCardCannotDeadlockOnAWaitingEssentialCardThatNeedsItsSlot() = runTest {
        val continueFirst = CompletableDeferred<Unit>()
        var firstCompleted = false
        val first = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED, cardWork = true) {
            continueFirst.await()
            coordinator.yieldToInteraction()
            firstCompleted = true
        } }
        runCurrent()
        var secondCompleted = false
        val second = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI, cardWork = true) {
            secondCompleted = true
        } }
        runCurrent()
        continueFirst.complete(Unit)
        runCurrent()
        assertTrue("A card may not yield to a waiter that can only start after it finishes", firstCompleted)
        assertFalse(secondCompleted)
        ShadowSystemClock.advanceBy(Duration.ofMillis(750))
        advanceTimeBy(100)
        runCurrent()
        assertTrue(secondCompleted)
        first.join(); second.join()
    }

    @Test fun canceledCardsReleaseBothCardOwnershipAndTheirBusyLease() = runTest {
        val hold = CompletableDeferred<Unit>()
        val first = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI, cardWork = true) {
            hold.await()
        } }
        runCurrent()
        first.cancelAndJoin()
        ShadowSystemClock.advanceBy(Duration.ofMillis(750))
        var replacementRan = false
        val replacement = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI, cardWork = true) { replacementRan = true }
        }
        runCurrent()
        assertTrue(replacementRan)
        replacement.join()
    }
}
