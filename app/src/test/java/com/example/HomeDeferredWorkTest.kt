package com.example

import android.app.Application
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.remote.*
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.HomeViewModel
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, instrumentedPackages = ["com.example.ui.viewmodel"])
class HomeDeferredWorkTest {
    @Test fun artistQueriesAndProfileSearchWaitForAHomeDrawAndSixSecondsAfterTheLastInput() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val coordinator = BackgroundWorkCoordinator
        coordinator.foreground(true); coordinator.firstFrameRendered(); coordinator.scrolling(false); coordinator.touch(false)
        coordinator.frame(2, 1) // Rebase Robolectric's per-method clock; real uptime never rewinds.
        val queries = AtomicInteger(); val searches = AtomicInteger(); val heavyWorkOnMain = AtomicBoolean()
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(database: androidx.sqlite.db.SupportSQLiteDatabase) { HomeInputRevisionTriggers.install(database) }
            })
            .setQueryCallback({ sql, _ -> if (sql.contains("COUNT(DISTINCT CASE WHEN sessionId")) {
                queries.incrementAndGet()
                if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) heavyWorkOnMain.set(true)
            } }, Executor { it.run() }).build()
        val repo = MusicRepository(db.musicDao(), app); val player = PlaybackManager(app, repo)
        val store = ViewModelStore()
        val song = MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "", "")
        try {
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song))
            db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = song.id, artistName = song.artist,
                eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repo, player, app, db,
                    homeLoader = { _, _, _ -> YouTubeHomeParser.ParsedHomeFeed(listOf(ServerHomeShelf("native", "Native music", tracks = listOf(song))), null) },
                    profileLoader = { _, _, _ ->
                        searches.incrementAndGet()
                        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) heavyWorkOnMain.set(true)
                        YouTubeMusicApiService.ProfileShelfResult(ServerHomeShelf("profile", "Music for You", tracks = listOf(song))) },
                    supplementalLoader = { _, _, _ -> YouTubeHomeParser.ParsedHomeFeed(emptyList(), null) }) as T
            }
            val vm = ViewModelProvider(store, factory)[HomeViewModel::class.java]
            val firstInteraction = coordinator.interactions.value
            vm.setHomeVisible(true)
            withTimeout(5_000) { coordinator.interactions.first { it > firstInteraction } }
            ShadowSystemClock.advanceBy(Duration.ofMillis(300))
            assertNotNull("Native Home must start before optional work: ${vm.refreshErrorMessage.value}",
                withTimeoutOrNull(5_000) { vm.serverShelves.first { it.isNotEmpty() } })
            ShadowSystemClock.advanceBy(Duration.ofSeconds(60)); delay(300)
            assertEquals(0, searches.get()); assertEquals(0, queries.get())
            vm.onHomeFrameDrawn()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5)); delay(300)
            coordinator.interaction()
            ShadowSystemClock.advanceBy(Duration.ofMillis(5_999)); delay(300)
            assertEquals(0, searches.get()); assertEquals(0, queries.get())
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            withTimeout(5_000) { vm.mostListenedArtists.first { it.isNotEmpty() } }
            @Suppress("UNCHECKED_CAST")
            val artistSettled = vm.javaClass.getDeclaredField("initialArtistSettledAt").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.StateFlow<Long?>
            withTimeout(5_000) { artistSettled.first { it != null } }
            assertEquals(0, searches.get()); assertEquals(1, queries.get())
            assertEquals("Singer", vm.mostListenedArtists.value.single().name)
            ShadowSystemClock.advanceBy(Duration.ofMillis(5_999)); delay(300)
            assertEquals(0, searches.get()); assertFalse(vm.profileShelfVisible.value)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            withTimeout(5_000) { vm.profileShelf.first { it != null } }
            assertEquals(1, searches.get()); assertFalse(heavyWorkOnMain.get())
            vm.setHomeVisible(false)
            withTimeout(5_000) { vm.optionalWorkReady.first { !it } }
            assertEquals("Singer", vm.mostListenedArtists.value.single().name)
            val resumeInteraction = coordinator.interactions.value
            vm.setHomeVisible(true)
            withTimeout(5_000) { coordinator.interactions.first { it > resumeInteraction } }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(6)); delay(300)
            withTimeout(5_000) { vm.optionalWorkReady.first { it } }
            assertEquals("Fresh retained artist inputs do not reaggregate", 1, queries.get())
            assertEquals("Fresh retained profile results do not refetch", 1, searches.get())
        } finally { store.clear(); player.release(); db.close(); coordinator.foreground(false); Dispatchers.resetMain() }
    }
}
