package com.example.performance

import android.app.Application
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, instrumentedPackages = ["com.example.performance"])
class HomeContinuousIdleTest {
    private val coordinator = BackgroundWorkCoordinator
    @After fun stop() { coordinator.touch(false); coordinator.scrolling(false); coordinator.windowFocus(true); coordinator.foreground(false) }
    @Test fun theIdleWindowStartsAtTheRealHomeDrawAndInputRestartsAllTenSeconds() = runTest {
        coordinator.foreground(true); coordinator.firstFrameRendered()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60)) // Waiting before Home draws earns no idle credit.
        val draw = android.os.SystemClock.elapsedRealtime()
        val ready = async { coordinator.awaitContinuousForegroundIdle(draw) }
        runCurrent()
        suspend fun advance(millis: Long) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(millis)); advanceTimeBy(millis); runCurrent()
        }
        advance(9_999); assertFalse(ready.isCompleted)
        coordinator.interaction(); advance(9_999); assertFalse(ready.isCompleted)
        advance(1); ready.await()
    }
    @Test fun heldTouchScrollingNavigationAndBackgroundCannotCountAsIdle() = runTest {
        coordinator.foreground(true); coordinator.firstFrameRendered()
        val ready = async { coordinator.awaitContinuousForegroundIdle(android.os.SystemClock.elapsedRealtime()) }
        runCurrent()
        suspend fun advance(millis: Long) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(millis)); advanceTimeBy(millis); runCurrent()
        }
        coordinator.touch(true); advance(12_000); assertFalse(ready.isCompleted)
        coordinator.touch(false); advance(9_000); assertFalse(ready.isCompleted)
        coordinator.scrolling(true); advance(12_000); assertFalse(ready.isCompleted)
        coordinator.scrolling(false)
        val navigation = coordinator.navigationBusy()
        advance(12_000); assertFalse(ready.isCompleted); navigation.close()
        coordinator.foreground(false); advance(12_000); assertFalse(ready.isCompleted)
        coordinator.foreground(true); advance(9_999); assertFalse(ready.isCompleted)
        advance(251); ready.await()
    }
    @Test fun aDialogOrSystemWindowRestartsTheWindowAfterFocusReturns() = runTest {
        coordinator.foreground(true); coordinator.firstFrameRendered()
        val ready = async { coordinator.awaitContinuousForegroundIdle(android.os.SystemClock.elapsedRealtime()) }
        runCurrent(); coordinator.windowFocus(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15)); advanceTimeBy(15_000); runCurrent()
        assertFalse(ready.isCompleted)
        coordinator.windowFocus(true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(9_999)); advanceTimeBy(9_999); runCurrent()
        assertFalse(ready.isCompleted)
        ShadowSystemClock.advanceBy(Duration.ofMillis(251)); advanceTimeBy(251); runCurrent()
        ready.await()
    }
    @Test fun playbackDoesNotHaveToStopForUiToQualifyAsIdle() = runTest {
        coordinator.foreground(true); coordinator.firstFrameRendered()
        val audioLease = coordinator.busy()
        try {
            val ready = async { coordinator.awaitContinuousForegroundIdle(android.os.SystemClock.elapsedRealtime()) }
            runCurrent(); ShadowSystemClock.advanceBy(Duration.ofSeconds(10)); advanceTimeBy(10_000); runCurrent()
            ready.await()
        } finally { audioLease.close() }
    }
}
