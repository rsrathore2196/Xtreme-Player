package com.example

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MusicDatabase
import com.example.data.model.ServerHomeItem
import com.example.data.remote.YouTubeHomeParser
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.StartupReadiness
import com.example.performance.StartupStatus
import com.example.performance.StartupTask
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.HomeViewModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Keep this process-start fixture isolated from other one-shot readiness tests.
@Config(sdk = [31], application = Application::class, instrumentedPackages = ["com.example.performance"])
class InitialServerFeedReadinessTest {
    @Test fun theExistingInitializationFenceCannotFinishBeforeTheFirstNativeCardsArePrepared() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val coordinator = BackgroundWorkCoordinator
        fun field(name: String) = coordinator.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val readiness = field("_startupReadiness").get(null) as MutableStateFlow<StartupReadiness>
        val original = readiness.value
        (field("initialDeadlineJob").get(null) as? Job)?.cancel()
        (field("initialStartedAt").get(null) as AtomicLong).set(0L)
        (field("initialPresentationPending").get(null) as AtomicBoolean).set(false)
        readiness.value = StartupReadiness()
        coordinator.beginInitialPresentation()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(); val store = ViewModelStore()
        val db = MusicDatabase.createInMemoryDatabase(app)
        val repo = MusicRepository(db.musicDao(), app); val player = PlaybackManager(app, repo)
        try {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repo, player, app, db,
                    homeLoader = { _, _, _ ->
                        calls.incrementAndGet(); entered.complete(Unit); release.await()
                        YouTubeHomeParser.parseHomeFeed(JSONObject(NativeFeedFixtures.home(
                            NativeFeedFixtures.collectionShelf("Albums & Singles", NativeFeedFixtures.album(1)),
                            NativeFeedFixtures.shelf("Quick Picks", 2),
                            NativeFeedFixtures.collectionShelf("Trending Playlists", NativeFeedFixtures.playlist(3)))))
                    }, profileLoader = { _, _, _ -> error("Optional discovery is not an initialization requirement") }) as T
            }
            val vm = ViewModelProvider(store, factory)[HomeViewModel::class.java]
            withTimeout(4_000) { entered.await() }
            assertEquals(StartupStatus.RUNNING, readiness.value.tasks[StartupTask.HOME_CACHE])
            assertFalse(readiness.value.preparationComplete)
            release.complete(Unit)
            withTimeout(4_000) { readiness.first { it.tasks[StartupTask.HOME_CACHE] == StartupStatus.READY } }
            assertEquals(3, vm.serverShelves.value.size)
            assertTrue(vm.serverShelves.value[0].items.single() is ServerHomeItem.Album)
            assertTrue(vm.serverShelves.value[2].items.single() is ServerHomeItem.Playlist)
            assertEquals(1, calls.get())
        } finally {
            release.complete(Unit); store.clear(); player.release(); db.close()
            (field("initialDeadlineJob").get(null) as? Job)?.cancel()
            (field("initialPresentationPending").get(null) as AtomicBoolean).set(false)
            (field("initialStartedAt").get(null) as AtomicLong).set(0L)
            readiness.value = original; Dispatchers.resetMain()
        }
    }
}
