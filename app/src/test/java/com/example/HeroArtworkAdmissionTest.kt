package com.example

import android.app.Application
import com.example.performance.BackgroundWorkCoordinator
import com.example.recommendation.DiscoveryRequestCoordinator
import com.example.ui.util.loadVisibleHeroArtwork
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HeroArtworkAdmissionTest {
    @Before fun idle() {
        BackgroundWorkCoordinator.foreground(false)
        BackgroundWorkCoordinator.scrolling(false)
        DiscoveryRequestCoordinator.homeScrolling(false)
        ShadowSystemClock.advanceBy(Duration.ofDays(1))
    }

    @Test fun heroWaitsForAudioResolutionAndThenUsesTheExistingDiscoveryLane() = runBlocking {
        val playing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetched = CompletableDeferred<Unit>()
        val playback = launch { DiscoveryRequestCoordinator.playback { playing.complete(Unit); release.await() } }
        playing.await()
        val hero = async { loadVisibleHeroArtwork { fetched.complete(Unit); "landscape" } }
        try {
            assertNull(withTimeoutOrNull(150) { fetched.await() })
            release.complete(Unit)
            assertEquals("landscape", withTimeout(5_000) { hero.await() })
        } finally { release.complete(Unit); hero.cancelAndJoin(); playback.cancelAndJoin() }
    }

    @Test fun hiddenHeroCancelsItsPendingAdmissionWithoutStartingARequest() = runBlocking {
        val playing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val playback = launch { DiscoveryRequestCoordinator.playback { playing.complete(Unit); release.await() } }
        playing.await()
        val hero = async { loadVisibleHeroArtwork { calls++; "landscape" } }
        try {
            yield()
            hero.cancelAndJoin()
            release.complete(Unit); playback.join()
            assertEquals(0, calls)
            assertEquals("next", withTimeout(5_000) { loadVisibleHeroArtwork { calls++; "next" } })
            assertEquals(1, calls)
        } finally { release.complete(Unit); hero.cancelAndJoin(); playback.cancelAndJoin() }
    }
}
