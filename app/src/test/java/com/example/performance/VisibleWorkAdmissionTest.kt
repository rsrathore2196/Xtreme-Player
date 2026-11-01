package com.example.performance

import android.app.Application
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class VisibleWorkAdmissionTest {
    private val coordinator = BackgroundWorkCoordinator
    @Before fun begin() {
        listOf("lastOptionalCompleted", "navigationUntil").forEach { name ->
            (coordinator.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(null) as AtomicLong).set(0L)
        }
        coordinator.firstFrameRendered(); coordinator.foreground(true); coordinator.scrolling(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }
    @After fun stop() { coordinator.scrolling(false); coordinator.foreground(false) }

    @Test fun scrollingDefersScreenDataAndOptionalWorkButNeverPlayback() = runTest {
        coordinator.scrolling(true)
        var restored = false; var enriched = false
        val visible = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { restored = true } }
        val optional = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { enriched = true } }
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertFalse(restored); assertFalse(enriched)
        var played = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { played = true }
        assertTrue(played)
        coordinator.scrolling(false); ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        advanceTimeBy(100); runCurrent()
        assertTrue(restored); assertTrue(enriched)
        visible.join(); optional.join()
    }
    @Test fun visibleLocalDataDoesNotWaitForAnOptionalHomeRequest() = runTest {
        val release = CompletableDeferred<Unit>()
        val optional = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { release.await() } }
        runCurrent()
        var restored = false
        val visible = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { restored = true } }
        runCurrent()
        assertTrue("Local screen data has no Home completion prerequisite", restored)
        assertFalse(release.isCompleted)
        release.complete(Unit); optional.join(); visible.join()
    }
    @Test fun admittedScreenDataParksAtTheNextGestureAndResumesIntact() = runTest {
        val fetched = CompletableDeferred<Unit>()
        var records = 0
        val visible = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) {
                fetched.await()
                currentCoroutineContext()[NonEssentialWork]!!.awaitAdmission()
                records = 300
            }
        }
        runCurrent()
        val navigation = coordinator.navigationBusy()
        try {
            fetched.complete(Unit); runCurrent(); advanceTimeBy(100); runCurrent()
            assertEquals(0, records)
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { assertEquals(0, records) }
        } finally { navigation.close() }
        coordinator.scrolling(true); advanceTimeBy(100); runCurrent(); assertEquals(0, records)
        coordinator.scrolling(false); ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        advanceTimeBy(100); runCurrent()
        assertEquals(300, records); visible.join()
    }
    @Test fun aParkedCpuContinuationDoesNotOccupyAWorkerNeededByPlayback() = runBlocking {
        val admitted = CompletableDeferred<Unit>(); val fetched = CompletableDeferred<Unit>()
        val parked = CompletableDeferred<Unit>(); val decoded = CompletableDeferred<Unit>()
        val visible = launch {
            coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) {
                val gate = currentCoroutineContext()[NonEssentialWork]!!
                admitted.complete(Unit); fetched.await()
                val observed = NonEssentialWork(gate.owner, gate.canRun) { parked.complete(Unit); gate.awaitAdmission() }
                withContext(AppDispatchers.CpuBounded + observed) { decoded.complete(Unit) }
            }
        }
        try {
            withTimeout(5_000) { admitted.await() }
            coordinator.scrolling(true); fetched.complete(Unit)
            withTimeout(5_000) { parked.await() }; assertFalse(decoded.isCompleted)
            withTimeout(5_000) {
                coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) {
                    withContext(AppDispatchers.CpuBounded) { assertFalse(decoded.isCompleted) }
                }
            }
            coordinator.scrolling(false); ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            withTimeout(5_000) { decoded.await(); visible.join() }
        } finally { coordinator.scrolling(false); visible.cancelAndJoin() }
    }
    @Test fun cancelingParkedWorkReleasesItsLane() = runTest {
        val hold = CompletableDeferred<Unit>()
        val old = launch { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { hold.await() } }
        runCurrent(); old.cancelAndJoin()
        var requested = false
        coordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) { requested = true }
        assertTrue(requested)
    }
}
