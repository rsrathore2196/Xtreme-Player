package com.example.performance

import android.app.Application
import android.os.Process
import com.example.recommendation.DiscoveryRequestCoordinator
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class BackgroundTaskPreemptionTest {
    private val coordinator = BackgroundWorkCoordinator

    @Before fun ready() {
        coordinator.firstFrameRendered()
        coordinator.cachedHomeRestored()
        coordinator.homeContentReady()
        coordinator.foreground(true)
        coordinator.frame(2, 1)
        for (name in listOf("lastOptionalCompleted", "navigationUntil")) {
            (coordinator.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(null) as AtomicLong).set(0)
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
    }
    @After fun stop() { coordinator.foreground(false) }

    @Test fun backgroundPriorityIsScopedAndRestoredEvenWhenWorkThrows() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { pool ->
            val dispatcher = BackgroundDispatcher(pool)
            val previous = withContext(pool) { Process.getThreadPriority(Process.myTid()) }
            withContext(dispatcher) {
                assertEquals(maxOf(previous, Process.THREAD_PRIORITY_BACKGROUND), Process.getThreadPriority(Process.myTid()))
            }
            assertEquals(previous, withContext(pool) { Process.getThreadPriority(Process.myTid()) })
            try {
                withContext(dispatcher) { throw IllegalStateException("failed batch") }
                fail("Expected the original failure")
            } catch (expected: IllegalStateException) { assertEquals("failed batch", expected.message) }
            assertEquals(previous, withContext(pool) { Process.getThreadPriority(Process.myTid()) })
        }
    }

    @Test fun aDockTouchParksOptionalCpuButPreservesProgressAndCriticalWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val progress = AtomicInteger()
        val optional = async(AppDispatchers.Io) {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                withContext(AppDispatchers.CpuBounded) {
                    progress.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    // The test thread can release before await suspends. Cross
                    // a dispatch boundary so this always tests admission of the
                    // next CPU batch, rather than racing an already-running one.
                    yield()
                    progress.incrementAndGet()
                }
            }
        }
        try {
            withTimeout(3_000) { entered.await() }
            coordinator.cancelNonEssentialWork()
            release.complete(Unit)
            delay(100)
            assertEquals("The next CPU batch must park at its dispatcher boundary", 1, progress.get())
            assertTrue("Preemption must preserve completed work", optional.isActive)
            withTimeout(2_000) { withContext(AppDispatchers.CpuBounded) {
                coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { /* playback preparation */ }
            } }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
            withTimeout(3_000) { optional.await() }
            assertEquals(2, progress.get())
        } finally { optional.cancelAndJoin() }
    }

    @Test fun cancellingParkedWorkImmediatelyReleasesTheCoroutineAndAdmission() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val optional = launch(AppDispatchers.Io) {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.MAINTENANCE) {
                withContext(AppDispatchers.CpuBounded) {
                    try {
                        entered.complete(Unit)
                        release.await()
                        yield() // Also cover an early release before await actually suspends.
                        fail("Stale work resumed")
                    }
                    finally { cleaned.complete(Unit) }
                }
            }
        }
        try {
            withTimeout(3_000) { entered.await() }
            coordinator.cancelNonEssentialWork()
            release.complete(Unit)
            delay(100)
            withTimeout(2_000) { optional.cancelAndJoin(); cleaned.await() }
            // No idle advancement: cancellation/finally must not be behind the dock gate.
            withTimeout(2_000) { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { } }
        } finally { optional.cancelAndJoin() }
    }

    @Test fun preemptionDoesNotParkTheCompletionOfAnIoTransaction() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        val optional = async(AppDispatchers.Io) {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                entered.complete(Unit)
                commit.await()
                "committed"
            }
        }
        try {
            withTimeout(3_000) { entered.await() }
            coordinator.cancelNonEssentialWork()
            commit.complete(Unit)
            assertEquals("committed", withTimeout(2_000) { optional.await() })
        } finally { optional.cancelAndJoin() }
    }

    @Test fun promotingSharedWorkToAnExplicitRequestBypassesOptionalWaiting() = runBlocking {
        val urgent = java.util.concurrent.atomic.AtomicBoolean()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val work = async(AppDispatchers.Io) {
            coordinator.optionalProcessing(urgent = urgent::get) {
                withContext(AppDispatchers.CpuBounded) {
                    entered.complete(Unit)
                    release.await()
                    // The release can win the race with await's suspension.
                    // Always cross a dispatcher boundary before checking that
                    // optional work parks and an explicit request promotes it.
                    yield()
                    "ready"
                }
            }
        }
        try {
            withTimeout(3_000) { entered.await() }
            coordinator.cancelNonEssentialWork()
            release.complete(Unit)
            delay(100)
            assertFalse(work.isCompleted)
            urgent.set(true)
            // No clock advancement or second lookup is needed for a user request.
            assertEquals("ready", withTimeout(2_000) { work.await() })
        } finally { work.cancelAndJoin() }
    }

    @Test fun anInFlightResponseFinishesParsingBeforeItsNetworkPermitIsReleased() = runBlocking {
        val response = CompletableDeferred<Unit>()
        val parse = CompletableDeferred<Unit>()
        val work = async(AppDispatchers.Io) {
            coordinator.optionalProcessing {
                DiscoveryRequestCoordinator.network {
                    response.complete(Unit)
                    parse.await()
                    withContext(AppDispatchers.CpuBounded) { "verified response" }
                }
            }
        }
        try {
            withTimeout(3_000) { response.await() }
            coordinator.cancelNonEssentialWork()
            parse.complete(Unit)
            assertEquals("verified response", withTimeout(2_000) { work.await() })
        } finally { work.cancelAndJoin() }
    }
}
