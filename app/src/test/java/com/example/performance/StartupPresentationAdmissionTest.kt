package com.example.performance

import android.app.Application
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class StartupPresentationAdmissionTest {
    private val coordinator = BackgroundWorkCoordinator
    @Suppress("UNCHECKED_CAST")
    private val state get() = BackgroundWorkCoordinator::class.java.getDeclaredField("_startupReadiness")
        .apply { isAccessible = true }.get(null) as MutableStateFlow<StartupReadiness>

    @Before fun begin() {
        (BackgroundWorkCoordinator::class.java.getDeclaredField("initialDeadlineJob")
            .apply { isAccessible = true }.get(null) as? kotlinx.coroutines.Job)?.cancel()
        listOf("initialStartedAt", "lastOptionalCompleted", "navigationUntil").forEach { name ->
            (BackgroundWorkCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
        }
        state.value = StartupReadiness()
        BackgroundWorkCoordinator::class.java.getDeclaredField("preparedContentDrawn").apply { isAccessible = true }
            .get(null).let { (it as AtomicBoolean).set(false) }
        coordinator.firstFrameRendered(); coordinator.cachedHomeRestored(); coordinator.homeContentReady()
        coordinator.foreground(true); coordinator.scrolling(false)
        coordinator.frame(2, 1)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        coordinator.beginInitialPresentation()
    }

    @After fun stop() {
        BackgroundWorkCoordinator::class.java.getDeclaredField("initialPresentationPending").apply { isAccessible = true }
            .get(null).let { (it as AtomicBoolean).set(false) }
        coordinator.foreground(false)
    }

    @Test fun aModalStartupBlocksOptionalAdmissionWithoutBlockingPlaybackOrEssentialData() = runTest {
        var optionalRan = false
        val optional = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { optionalRan = true } }
        runCurrent()
        assertFalse(optionalRan)
        var played = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { played = true }
        var prepared = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { prepared = true }
        assertTrue(played); assertTrue(prepared)
        assertFalse(optionalRan)
        optional.cancelAndJoin()
    }

    @Test fun unfinishedStartupFollowersLoseEssentialPriorityAfterThePopupDeadline() = runTest {
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { release.await() }
        }
        runCurrent()
        var restored = false
        val follower = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { restored = true }
        }
        runCurrent()
        assertFalse(restored)
        state.value = state.value.expirePreparation()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("initialPresentationPending")
            .apply { isAccessible = true }.get(null) as AtomicBoolean).set(false)
        coordinator.interaction()
        release.complete(Unit)
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertFalse("A timed-out restore must not run in the first interactive frames", restored)
        // Work is retained, with the ordinary optional idle/admission policy.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
        advanceTimeBy(100); runCurrent()
        assertTrue(restored)
        owner.join(); follower.join()
        // Explicit screen work created after startup is still essential.
        coordinator.interaction()
        var requested = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { requested = true }
        assertTrue(requested)
    }

    @Test fun disposablePreparationCannotStartAfterWaitingPastItsModalBudget() = runTest {
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { release.await() }
        }
        runCurrent()
        var ran = false
        val preparation = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI,
                boundToInitialPresentation = true) { ran = true }
        }
        runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        advanceTimeBy(100); runCurrent()
        assertTrue(preparation.isCancelled)
        assertFalse(ran)
        release.complete(Unit)
        owner.join(); preparation.join()
        assertTrue("Preparation plus the existing exit must remain within six seconds",
            BackgroundWorkCoordinator.INITIAL_PREPARATION_MS + 200L <= 6_000L)
    }

    @Test fun aLateDisposableStartupRequestCannotBecomeNewEssentialWorkAfterReveal() = runTest {
        state.value = state.value.expirePreparation()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("initialPresentationPending")
            .apply { isAccessible = true }.get(null) as AtomicBoolean).set(false)
        var ran = false
        val late = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI,
                boundToInitialPresentation = true) { ran = true }
        }
        runCurrent()
        assertTrue(late.isCancelled)
        assertFalse(ran)
        late.join()
    }

    @Test fun firstFrameAndReadyDataCannotDismissBeforeThePreparedTreeActuallyDraws() = runTest {
        val finish = launch { coordinator.awaitInitialPresentationSettled() }
        runCurrent()
        coordinator.initialContentDrawn() // A premature shell draw is not the final content.
        StartupTask.entries.filter { it != StartupTask.PRESENTATION }.forEach {
            coordinator.startupTask(it, StartupStatus.READY)
        }
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertFalse(coordinator.startupReadiness.value.complete)
        coordinator.initialContentDrawn()
        coordinator.frame(20, 10)
        advanceTimeBy(100); runCurrent()
        assertFalse("A slow final frame must settle", coordinator.startupReadiness.value.complete)
        ShadowSystemClock.advanceBy(Duration.ofMillis(250))
        advanceTimeBy(100); runCurrent()
        assertTrue(coordinator.startupReadiness.value.complete)
        finish.join()
    }

    @Test fun realHealthyFramesReleasePresentationButOptionalWorkStillWaitsForTheRevealInteraction() = runTest {
        coordinator.frame(20, 10)
        StartupTask.entries.filter { it != StartupTask.PRESENTATION }.forEach {
            coordinator.startupTask(it, StartupStatus.READY)
        }
        coordinator.initialContentDrawn()
        repeat(3) { coordinator.frame(5, 10) }
        val finish = launch { coordinator.awaitInitialPresentationSettled() }
        runCurrent()
        assertTrue(coordinator.startupReadiness.value.complete)
        finish.join()
        var ran = false
        val optional = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { ran = true } }
        runCurrent()
        assertFalse("Exit frames cannot release a backlog of maintenance work", ran)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
        advanceTimeBy(100); runCurrent()
        assertTrue(ran)
        optional.join()
    }

    @Test fun aPreparedStaticScreenDoesNotNeedFutureAnimationFramesToFinishStartup() = runTest {
        StartupTask.entries.filter { it != StartupTask.PRESENTATION }.forEach {
            coordinator.startupTask(it, StartupStatus.READY)
        }
        coordinator.frame(20, 10)
        coordinator.initialContentDrawn()
        val finish = launch { coordinator.awaitInitialPresentationSettled() }
        runCurrent()
        assertFalse(coordinator.startupReadiness.value.complete)
        // A cached/static RenderNode need not produce another FrameMetrics event.
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        advanceTimeBy(300); runCurrent()
        assertTrue("Real prepared content was drawn; no further animation is required",
            coordinator.startupReadiness.value.complete)
        finish.join()
    }

    @Test fun slowFrameTelemetryCannotKeepAnAlreadyPreparedDrawModalForever() = runTest {
        StartupTask.entries.filter { it != StartupTask.PRESENTATION }.forEach {
            coordinator.startupTask(it, StartupStatus.READY)
        }
        coordinator.initialContentDrawn()
        val finish = launch { coordinator.awaitInitialPresentationSettled() }
        runCurrent()
        repeat(24) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(50))
            coordinator.frame(20, 10)
            advanceTimeBy(50); runCurrent()
        }
        assertTrue("Frame health guides admission, not an unbounded modal dependency",
            coordinator.startupReadiness.value.complete)
        finish.join()
    }

    @Test fun preparedDataDoesNotReleaseOptionalFollowersBeforeTheActualReveal() = runTest {
        var continued = false
        val follower = launch { coordinator.awaitInitialPresentation(); continued = true }
        StartupTask.entries.filter { it != StartupTask.PRESENTATION }.forEach {
            coordinator.startupTask(it, StartupStatus.READY)
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        runCurrent()
        assertFalse(continued)
        coordinator.initialContentDrawn()
        repeat(3) { coordinator.frame(5, 10) }
        coordinator.awaitInitialPresentationSettled()
        runCurrent()
        assertTrue(continued)
        follower.join()
    }
}
