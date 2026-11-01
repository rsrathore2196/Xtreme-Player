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
        val calls = AtomicInteger(); val store = ViewModelStore()
        val db = MusicDatabase.createInMemoryDatabase(app)
        val repo = MusicRepository(db.musicDao(), app); val player = PlaybackManager(app, repo)
        try {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repo, player, app, db,
                    homeLoader = { _, token, _ ->
                        assertNull("Initial presentation never consumes a continuation", token)
                        calls.incrementAndGet(); entered.complete(Unit); release.await()
                        YouTubeHomeParser.ParsedHomeFeed(listOf(ServerHomeShelf("native", "Native shelf", tracks = listOf(
                            MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "", "")))), "later")
                    }, profileLoader = { _, _, _ ->
                        profileEntered.complete(Unit); profileRelease.await(); YouTubeMusicApiService.ProfileShelfResult(null)
                    }) as T
            }
            val vm = ViewModelProvider(store, factory)[HomeViewModel::class.java]
            assertTrue(vm.serverCards.value.all { it.status == ShelfLoadStatus.LOADING })
            val profile = async { vm.loadProfileShelf() }
            val refresh = vm.refreshHome(false)
            withTimeout(5_000) { entered.await(); profileEntered.await() }
            release.complete(Unit)
            withTimeout(5_000) { refresh.join(); vm.serverCards.first { it.singleOrNull()?.status == ShelfLoadStatus.SUCCESS } }
            assertEquals(1, vm.serverShelves.value.size); assertEquals(1, calls.get())
            assertFalse(vm.isRefreshing.value); assertFalse(profileRelease.isCompleted)
            profileRelease.complete(Unit); profile.await()
        } finally {
            release.complete(Unit); profileRelease.complete(Unit); store.clear(); player.release(); db.close(); Dispatchers.resetMain()
        }
    }
}
