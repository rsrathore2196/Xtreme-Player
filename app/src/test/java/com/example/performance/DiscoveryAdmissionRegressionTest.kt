package com.example.performance

import android.app.Application
import com.example.recommendation.DiscoveryRequestCoordinator
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
            BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) {
                DiscoveryRequestCoordinator.network(essential = true) { firstShelfReady = true }
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
            BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) {
                DiscoveryRequestCoordinator.network(essential = true) { nextBatchRan = true }
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

    @Test fun aNewVisibleEmptyCardKeepsItsEssentialHintInSharedLookups() = kotlinx.coroutines.runBlocking {
        BackgroundWorkCoordinator.frame(20_000_000L, 8_000_000L)
        var ran = false
        val request = async {
            kotlinx.coroutines.coroutineScope {
                DiscoveryRequestCoordinator.shared("selected-recording", essential = true) {
                    withContext(AppDispatchers.CpuBounded) {
                        ran = true
                        emptyList()
                    }
                }
            }
        }
        // The shared request is owned by the IO scope, not the test dispatcher.
        kotlinx.coroutines.withTimeout(2_000L) { request.await() }
        assertTrue("Slow placeholder frames cannot indefinitely starve a newly visible empty card", ran)
    }
    @Test fun manualArchiveMetadataPromotesTheExistingRequestWithoutLosingItsUrgency() = kotlinx.coroutines.runBlocking {
        @Suppress("UNCHECKED_CAST") val readiness = BackgroundWorkCoordinator::class.java.getDeclaredField("_startupReadiness")
            .apply { isAccessible = true }.get(null) as kotlinx.coroutines.flow.MutableStateFlow<StartupReadiness>
        val original = readiness.value
        readiness.value = StartupReadiness(StartupTask.entries.associateWith { StartupStatus.READY })
        val navigation = BackgroundWorkCoordinator.navigationBusy()
        val entered = CompletableDeferred<Unit>()
        val key = "manual-archive-metadata-priority"
        var calls = 0
        val automatic = async {
            DiscoveryRequestCoordinator.shared(key, essential = false, network = false, playbackMetadata = true) {
                BackgroundWorkCoordinator.awaitOptionalBatch()
                calls++; entered.complete(Unit); emptyList()
            }
        }
        try {
            // Verify that optional metadata really is waiting on this interaction.
            assertNull(kotlinx.coroutines.withTimeoutOrNull(100L) { entered.await() })
            val manual = async {
                DiscoveryRequestCoordinator.shared(key, essential = true, network = false, playbackMetadata = true) {
                    error("Manual join must reuse the automatic producer")
                }
            }
            kotlinx.coroutines.withTimeout(3_000L) { manual.await(); automatic.await() }
            assertEquals(1, calls)
        } finally {
            navigation.close(); automatic.cancelAndJoin(); readiness.value = original
        }
    }
    @Test fun aSharedAutomaticRequestReturnsToOptionalPriorityWhenItsManualConsumerLeaves() = kotlinx.coroutines.runBlocking<Unit> {
        val entered = CompletableDeferred<kotlinx.coroutines.flow.StateFlow<Boolean>>()
        val release = CompletableDeferred<Unit>()
        val automatic = async {
            DiscoveryRequestCoordinator.shared("temporary-manual-priority", essential = false, network = false, playbackMetadata = true) {
                entered.complete(checkNotNull(DiscoveryRequestCoordinator.requestUrgency()))
                release.await(); emptyList()
            }
        }
        var manual: kotlinx.coroutines.Deferred<*>? = null
        try {
            val urgency = kotlinx.coroutines.withTimeout(3_000L) { entered.await() }
            assertFalse(urgency.value)
            manual = async {
                DiscoveryRequestCoordinator.shared("temporary-manual-priority", essential = true, network = false, playbackMetadata = true) {
                    error("A second producer must not start")
                }
            }
            kotlinx.coroutines.withTimeout(3_000L) { urgency.first { it } }
            manual.cancelAndJoin()
            assertFalse("A departing manual owner must not leave optional discovery permanently urgent", urgency.value)
            release.complete(Unit); automatic.await()
        } finally { release.complete(Unit); manual?.cancelAndJoin(); automatic.cancelAndJoin() }
    }
    @Test fun aManualRecordingCannotBeStarvedByTwoParkedOptionalArchiveLookups() = kotlinx.coroutines.runBlocking<Unit> {
        @Suppress("UNCHECKED_CAST") val readiness = BackgroundWorkCoordinator::class.java.getDeclaredField("_startupReadiness")
            .apply { isAccessible = true }.get(null) as kotlinx.coroutines.flow.MutableStateFlow<StartupReadiness>
        val previous = readiness.value
        readiness.value = StartupReadiness(StartupTask.entries.associateWith { StartupStatus.READY })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val entered = List(2) { CompletableDeferred<Unit>() }
        val releaseOptional = CompletableDeferred<Unit>()
        val resolver = com.example.data.repository.StreamResolver(lookupScope = scope, supportsLossless = { true },
            findLossless = { track, _, _ ->
                if (track.id != "manual") {
                    entered[track.id.toInt()].complete(Unit)
                    releaseOptional.await()
                    BackgroundWorkCoordinator.awaitOptionalBatch()
                    kotlinx.coroutines.awaitCancellation()
                }
                com.example.data.remote.InternetArchiveApiService.LosslessSearchPage(listOf(track.copy(
                    audioUrl = "https://archive.org/download/priority/manual.flac", source = "INTERNET_ARCHIVE", isLossless = true)))
            })
        fun track(id: String) = com.example.data.model.MusicTrack(id, "Song $id", "Artist", "Album", 180_000, "", "")
        val optional = (0..1).map { index -> async { resolver.discoverLosslessBackground(track(index.toString()), policyKey = "policy") } }
        var navigation: AutoCloseable? = null
        try {
            kotlinx.coroutines.withTimeout(3_000L) { entered.forEach { it.await() } }
            navigation = BackgroundWorkCoordinator.navigationBusy(); releaseOptional.complete(Unit)
            val manual = async { resolver.discoverLosslessBackground(track("manual"), policyKey = "policy", urgent = true) }
            assertNotNull("Paused optional batches cannot reserve every recording slot",
                kotlinx.coroutines.withTimeout(2_000L) { manual.await() })
        } finally {
            navigation?.close(); releaseOptional.complete(Unit); optional.forEach { it.cancelAndJoin() }
            resolver.clear(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin(); readiness.value = previous
        }
    }
    @Test fun anExplicitHomeRefreshPromotesItsExistingRequestWithoutAnotherNetworkCall() = runTest {
        val urgent = kotlinx.coroutines.flow.MutableStateFlow(false)
        val navigation = BackgroundWorkCoordinator.navigationBusy()
        DiscoveryRequestCoordinator.homeScrolling(true)
        var calls = 0
        val request = async {
            DiscoveryRequestCoordinator.withPriority(urgent) {
                DiscoveryRequestCoordinator.network { calls++ }
            }
        }
        try {
            runCurrent(); assertEquals(0, calls)
            urgent.value = true
            advanceTimeBy(100); runCurrent()
            request.await(); assertEquals(1, calls)
        } finally {
            navigation.close(); DiscoveryRequestCoordinator.homeScrolling(false); request.cancelAndJoin()
        }
    }

}
