package com.example.performance

import android.app.Application
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
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
}
