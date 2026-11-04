package com.example

import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreTabAdmissionTest {
    @Test fun coreCachePreparationBlocksOptionalWorkWhileUserAndPlaybackPriorityRemainAvailable() = runBlocking<Unit> {
        val coordinator = BackgroundWorkCoordinator
        coordinator.foreground(true); coordinator.windowFocus(true); coordinator.touch(false); coordinator.scrolling(false); coordinator.firstFrameRendered()
        val lease = coordinator.coreTabsPreparation()
        val started = CompletableDeferred<Unit>()
        val optional = async { coordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) { started.complete(Unit) } }
        try {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            assertNull(withTimeoutOrNull(100) { started.await() })
            assertEquals("user", coordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) { "user" })
            assertEquals("playback", com.example.recommendation.DiscoveryRequestCoordinator.playback { "playback" })
            lease.close(); lease.close()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            withTimeout(3_000) { optional.await() }
        } finally { lease.close(); optional.cancelAndJoin(); coordinator.foreground(false) }
    }
}
