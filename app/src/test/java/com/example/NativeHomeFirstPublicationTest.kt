package com.example

import android.app.Application
import androidx.lifecycle.*
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MusicDatabase
import com.example.data.model.*
import com.example.data.remote.*
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class NativeHomeFirstPublicationTest {
    @Test fun oneNativeShelfPublishesWhileOtherDiscoveryIsStillPending() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val profileEntered = CompletableDeferred<Unit>(); val profileRelease = CompletableDeferred<Unit>()
        val idle = CompletableDeferred<Unit>()
        com.example.performance.BackgroundWorkCoordinator.foreground(true)
        com.example.performance.BackgroundWorkCoordinator.firstFrameRendered()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        val calls = AtomicInteger(); val store = ViewModelStore()
        val db = MusicDatabase.createInMemoryDatabase(app)
        val repo = MusicRepository(db.musicDao(), app); val player = PlaybackManager(app, repo)
        try {
            val listened = MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "", "")
            db.musicDao().insertOrUpdateTrack(com.example.data.local.TrackEntity.fromMusicTrack(listened))
            db.listeningEventDao().insertListeningEvent(com.example.data.local.ListeningEventEntity(
                trackId = listened.id, artistName = listened.artist, eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repo, player, app, db,
                    homeLoader = { _, token, _ ->
                        assertNull("Initial presentation never consumes a continuation", token)
                        calls.incrementAndGet(); entered.complete(Unit); release.await()
                        YouTubeHomeParser.ParsedHomeFeed(listOf(ServerHomeShelf("native", "Native shelf", tracks = listOf(
                            MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "", "")))), "later")
                    }, profileLoader = { _, _, _ ->
                        profileEntered.complete(Unit); profileRelease.await(); YouTubeMusicApiService.ProfileShelfResult(null)
                    }, idleAdmission = { idle.await() },
                    supplementalLoader = { _, _, _ -> YouTubeHomeParser.ParsedHomeFeed(emptyList(), null) }) as T
            }
            val vm = ViewModelProvider(store, factory)[HomeViewModel::class.java]
            assertTrue(vm.serverCards.value.all { it.status == ShelfLoadStatus.LOADING })
            val profile = async { vm.loadProfileShelf() }
            val refresh = vm.refreshHome(false)
            withTimeout(5_000) { entered.await() }
            assertFalse("Optional discovery is deferred even when a native request is active", profileEntered.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { refresh.join(); vm.serverCards.first { it.singleOrNull()?.status == ShelfLoadStatus.SUCCESS } }
            assertEquals(1, vm.serverShelves.value.size); assertEquals(1, calls.get())
            assertFalse(vm.isRefreshing.value); assertFalse(profileRelease.isCompleted)
            val beforeResume = com.example.performance.BackgroundWorkCoordinator.interactions.value
            vm.onHomeFrameDrawn(); vm.setHomeVisible(true)
            withTimeout(5_000) { com.example.performance.BackgroundWorkCoordinator.interactions.first { it > beforeResume } }
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
            idle.complete(Unit)
            withTimeout(5_000) { profileEntered.await() }
            profileRelease.complete(Unit); profile.await()
        } finally {
            release.complete(Unit); profileRelease.complete(Unit); store.clear(); player.release(); db.close(); Dispatchers.resetMain(); com.example.performance.BackgroundWorkCoordinator.foreground(false)
        }
    }
}
