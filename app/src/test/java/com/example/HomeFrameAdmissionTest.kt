package com.example

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlaybackManager
import com.example.recommendation.DiscoveryRequestCoordinator
import com.example.ui.viewmodel.HomeViewModel
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeFrameAdmissionTest {
    @Test fun visibleDiscoveryStillWaitsForNavigationAndPlaybackAndCanBeCancelled() = runBlocking<Unit> {
        val coordinator = BackgroundWorkCoordinator
        coordinator.foreground(true); coordinator.windowFocus(true); coordinator.touch(false); coordinator.scrolling(false)
        coordinator.firstFrameRendered()
        val navigation = coordinator.navigationBusy()
        val release = CompletableDeferred<Unit>(); val playbackStarted = CompletableDeferred<Unit>()
        val playback = async { DiscoveryRequestCoordinator.playback { playbackStarted.complete(Unit); release.await() } }
        val started = CompletableDeferred<Unit>()
        withTimeout(2_000) { playbackStarted.await() }
        val request = async {
            DiscoveryRequestCoordinator.withPriority(MutableStateFlow(false), awaitFrameQuiet = false) {
                DiscoveryRequestCoordinator.network { started.complete(Unit) }
            }
        }
        try {
            assertNull(withTimeoutOrNull(100) { started.await() })
            release.complete(Unit); playback.join()
            assertNull(withTimeoutOrNull(100) { started.await() })
            navigation.close()
            ShadowSystemClock.advanceBy(Duration.ofMillis(300)); coordinator.frame(20_000_000, 8_000_000)
            withTimeout(2_000) { request.await() }
            val again = coordinator.navigationBusy()
            try {
                val obsolete = async {
                    DiscoveryRequestCoordinator.withPriority(MutableStateFlow(false), awaitFrameQuiet = false) {
                        DiscoveryRequestCoordinator.network { fail("Cancelled navigation work must not start") }
                    }
                }
                delay(100); obsolete.cancelAndJoin(); assertTrue(obsolete.isCancelled)
            } finally { again.close() }
        } finally {
            release.complete(Unit); playback.cancelAndJoin(); request.cancelAndJoin(); navigation.close()
            coordinator.foreground(false); coordinator.frame(2, 1); ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        }
    }
    @Test fun visibleHomeStagesProgressWhileFramesMissTheirBudgetWithoutUserInput() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val coordinator = BackgroundWorkCoordinator
        coordinator.foreground(true); coordinator.windowFocus(true); coordinator.touch(false)
        coordinator.scrolling(false); coordinator.firstFrameRendered()
        val db = MusicDatabase.createInMemoryDatabase(app)
        val repository = MusicRepository(db.musicDao(), app)
        val player = PlaybackManager(app, repository)
        val store = ViewModelStore()
        val calls = AtomicInteger()
        fun song(n: Int) = MusicTrack("yt_${NativeFeedFixtures.id(n)}", "Song $n", "Singer $n", "Album", 120_000, "", "")
        var noise: Job? = null
        try {
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(1)))
            db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = song(1).id, artistName = song(1).artist,
                eventType = "ACTUAL_PLAY", playedDurationMs = 30_000, sessionId = "returning"))
            coordinator.frame(20_000_000, 8_000_000)
            noise = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    ShadowSystemClock.advanceBy(Duration.ofMillis(80))
                    coordinator.frame(20_000_000, 8_000_000)
                    delay(8)
                }
            }
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                    repository, player, app, db,
                    homeLoader = { _, _, _ -> YouTubeHomeParser.ParsedHomeFeed((1..4).map {
                        ServerHomeShelf("native-$it", "Native $it", tracks = listOf(song(it))) }, null) },
                    profileLoader = { _, _, _ -> DiscoveryRequestCoordinator.network {
                        assertFalse("Visible Home discovery must keep its optional lane", DiscoveryRequestCoordinator.requestUrgency()?.value == true)
                        calls.incrementAndGet()
                        YouTubeMusicApiService.ProfileShelfResult(ServerHomeShelf("profile", "Music for You", tracks = listOf(song(5))))
                    } },
                    supplementalLoader = { _, _, _ -> YouTubeHomeParser.ParsedHomeFeed(emptyList(), null) },
                    connectivityUpdates = emptyFlow()) as T
            }
            val vm = ViewModelProvider(store, factory)[HomeViewModel::class.java]
            vm.onHomeFrameDrawn(); vm.setHomeVisible(true)
            withTimeout(5_000) { vm.serverShelves.first { it.size == 4 } }
            val initial = SystemClock.elapsedRealtime()
            val ready = withTimeoutOrNull(5_000) {
                vm.mostListenedArtists.first { it.isNotEmpty() }
                vm.profileShelf.first { it != null }
                vm.revealedShelfCount.first { it == 4 }
            }
            assertNotNull("Visible data starved during slow idle frames: mode=${vm.userMode.value}, artists=${vm.mostListenedArtists.value.size}, profileCalls=${calls.get()}, elapsed=${SystemClock.elapsedRealtime() - initial}", ready)
            assertTrue(vm.artistPortraitsReady.value); assertTrue(vm.profileShelfVisible.value)
            assertEquals(1, calls.get())
        } finally {
            noise?.cancelAndJoin(); store.clear(); player.release(); db.close()
            coordinator.foreground(false); coordinator.frame(2, 1)
            ShadowSystemClock.advanceBy(Duration.ofMillis(300)); Dispatchers.resetMain()
        }
    }
}
