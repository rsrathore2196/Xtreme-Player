package com.example.performance

import android.app.Application
import com.example.recommendation.DiscoveryRequestCoordinator
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
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
@Config(sdk = [33], application = Application::class)
class DiscoveryAdmissionRegressionTest {
    @Before fun ready() {
        BackgroundWorkCoordinator.firstFrameRendered()
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.scrolling(false)
        DiscoveryRequestCoordinator.homeScrolling(false)
        BackgroundWorkCoordinator.frame(2L, 1L)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    }
    @After fun stop() { BackgroundWorkCoordinator.foreground(false) }

    @Test fun minimalBootstrapCanProgressDuringSlowPlaceholderFramesButStillYieldsToNavigation() = runTest {
        val coordinator = BackgroundWorkCoordinator
        coordinator.frame(20_000_000L, 8_000_000L)
        var firstShelfReady = false
        val bootstrap = async {
            withContext(DiscoveryRequestCoordinator.HomeRefresh(false, initialShelf = true)) {
                DiscoveryRequestCoordinator.network { firstShelfReady = true }
            }
        }
        runCurrent()
        assertTrue("Placeholder frame misses must not starve the first small shelf", firstShelfReady)
        bootstrap.await()
        var deeperWorkRan = false
        val deeper = async { DiscoveryRequestCoordinator.network { deeperWorkRan = true } }
        val navigation = coordinator.navigationBusy()
        var nextBatchRan = false
        val next = async {
            withContext(DiscoveryRequestCoordinator.HomeRefresh(false, initialShelf = true)) {
                DiscoveryRequestCoordinator.network { nextBatchRan = true }
            }
        }
        try {
            runCurrent()
            assertFalse(nextBatchRan)
            assertFalse(deeperWorkRan)
            navigation.close()
            advanceTimeBy(100L)
            runCurrent()
            assertTrue("A minimal batch resumes after navigation, independently of frame health", nextBatchRan)
            assertFalse("Deeper discovery must keep its frame-idle policy", deeperWorkRan)
        } finally {
            navigation.close()
            next.cancelAndJoin()
            deeper.cancelAndJoin()
        }
    }

    @Test fun aQueuedHomeRequestDoesNotHoldANetworkSlotWhileYieldingToNavigation() = runTest {
        val releases = List(3) { CompletableDeferred<Unit>() }
        var running = 0
        val blockers = releases.map { release -> async {
            DiscoveryRequestCoordinator.network(essential = true) { running++; release.await() }
        } }
        runCurrent()
        assertEquals(3, running)
        var optionalStarted = false
        val optional = async { DiscoveryRequestCoordinator.network { optionalStarted = true } }
        runCurrent()
        val navigation = BackgroundWorkCoordinator.navigationBusy()
        var urgentStarted = false
        var urgent: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            releases[0].complete(Unit)
            runCurrent()
            assertFalse(optionalStarted)
            urgent = async { DiscoveryRequestCoordinator.network(essential = true) { urgentStarted = true } }
            runCurrent()
            assertTrue("A paused Home lookup must release its slot to urgent radio/recovery", urgentStarted)
            assertFalse(optionalStarted)
        } finally {
            navigation.close()
            optional.cancelAndJoin()
            urgent?.cancelAndJoin()
            blockers.forEach { it.cancelAndJoin() }
        }
    }
}
