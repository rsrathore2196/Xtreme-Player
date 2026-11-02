package com.example

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.*
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.HomeViewModel
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdaptiveHomeLoadingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: MusicDatabase
    private lateinit var player: PlaybackManager
    private val stores = mutableListOf<ViewModelStore>()
    private val pages = AtomicInteger(); private val supplements = AtomicInteger()
    private var clockPump: Job? = null
    private fun song(n: Int) = MusicTrack("yt_${NativeFeedFixtures.id(n)}", "Song $n", "Artist $n", "Album", 120_000, "", "")
    private fun shelf(n: Int) = ServerHomeShelf("s$n", "Native $n", tracks = listOf(song(n)))
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val coordinator = BackgroundWorkCoordinator
        coordinator.foreground(true); coordinator.firstFrameRendered(); coordinator.scrolling(false); coordinator.touch(false)
        coordinator.frame(2, 1)
        db = MusicDatabase.createInMemoryDatabase(app)
        player = PlaybackManager(app, MusicRepository(db.musicDao(), app))
        clockPump = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) { ShadowSystemClock.advanceBy(Duration.ofMillis(25)); delay(25) }
        }
    }
    @After fun close() = runBlocking { stores.forEach { it.clear() }; player.release(); db.close(); clockPump?.cancelAndJoin(); BackgroundWorkCoordinator.foreground(false); Dispatchers.resetMain() }
    private suspend fun model(initial: Int = 8, first: CompletableDeferred<Unit>? = null): HomeViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                MusicRepository(db.musicDao(), app), player, app, db,
                homeLoader = { _, token, _ ->
                    if (token == null) { first?.await(); YouTubeHomeParser.ParsedHomeFeed((1..initial).map(::shelf), "next") }
                    else { pages.incrementAndGet(); YouTubeHomeParser.ParsedHomeFeed((9..11).map(::shelf), null) }
                }, profileLoader = { _, _, _ -> YouTubeMusicApiService.ProfileShelfResult(null) },
                idleAdmission = { awaitCancellation() }, connectivityUpdates = emptyFlow(),
                supplementalLoader = { _, browse, _ ->
                    supplements.incrementAndGet()
                    YouTubeHomeParser.ParsedHomeFeed(listOf(shelf(if (browse == "FEmusic_new_releases") 12 else 13)), null)
                }) as T
        }
        val vm = ViewModelProvider(store, factory)[HomeViewModel::class.java]
        vm.onHomeFrameDrawn(); vm.setHomeVisible(true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(350))
        return vm
    }
    @Test fun newcomersInitiallyRenderFourWithoutFetchingHiddenPagesAndManualRefreshUnlocksOneMore() = runBlocking {
        val vm = model()
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it == com.example.recommendation.HomeUserMode.NEW_USER } }
        assertEquals(4, vm.revealedShelfCount.value)
        assertFalse(vm.additionalShelvesUnlocked.value)
        vm.revealNextHomeSection(); delay(100)
        assertEquals(4, vm.revealedShelfCount.value); assertEquals(0, pages.get()); assertEquals(0, supplements.get())
        withTimeout(5_000) { vm.refreshHome(true).join() }
        assertTrue(vm.additionalShelvesUnlocked.value)
        assertEquals(5, vm.revealedShelfCount.value)
        assertEquals(0, pages.get()) // Existing cached content fulfils the reveal.
    }
    @Test fun aShortPrimaryResponseComesBeforeBoundedSupplementsAndNeverEagerlyPaginates() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val vm = model(initial = 2, first = first)
        delay(100)
        assertTrue(vm.serverShelves.value.isEmpty()); assertEquals(0, supplements.get())
        first.complete(Unit)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 4 } }
        assertEquals(2, supplements.get()); assertEquals(0, pages.get())
        assertEquals(4, vm.revealedShelfCount.value)
    }
    @Test fun returningUsersRevealOneCachedShelfPerDemandAndOneShelfFromANewPage() = runBlocking {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(1)))
        db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = song(1).id, eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        val vm = model()
        withTimeout(5_000) { vm.additionalShelvesUnlocked.first { it }; vm.serverShelves.first { it.size == 8 } }
        assertEquals(4, vm.revealedShelfCount.value)
        for (count in 5..8) { vm.revealNextHomeSection(); withTimeout(5_000) { vm.revealedShelfCount.first { it == count } }; assertEquals(0, pages.get()) }
        vm.revealNextHomeSection()
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 9 }; vm.serverShelves.first { it.size == 11 } }
        assertEquals(1, pages.get()); assertEquals(11, vm.serverShelves.value.size)
        vm.revealNextHomeSection(); withTimeout(5_000) { vm.revealedShelfCount.first { it == 10 } }
        assertEquals(1, pages.get())
    }
    @Test fun meaningfulOlderHistoryStillIdentifiesReturningUsersBeyondTheRecentWindow() = runBlocking {
        db.withTransaction {
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(1)))
            for (n in 0..300) db.listeningEventDao().insertListeningEvent(ListeningEventEntity(
                trackId = song(1).id, eventType = "ACTUAL_PLAY", sessionId = "occurrence-$n", timestamp = n.toLong(),
                playedDurationMs = if (n == 0) 30_000 else 0))
        }
        val vm = model()
        withTimeout(5_000) { vm.additionalShelvesUnlocked.first { it }; vm.serverShelves.first { it.size == 8 } }
        assertEquals(4, vm.revealedShelfCount.value)
        vm.revealNextHomeSection()
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 5 } }
        assertEquals(0, pages.get())
    }
    @Test fun threeDistinctMeaningfulSongsThenSixContinuousIdleSecondsUnlockWhileAudioCanRemainActive() = runBlocking<Unit> {
        val vm = model()
        withTimeout(5_000) { vm.userMode.first { it == com.example.recommendation.HomeUserMode.NEW_USER }; vm.serverShelves.first { it.size == 8 } }
        clockPump?.cancelAndJoin()
        for (n in 1..3) db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(n)))
        val events = (1..3).map { n -> ListeningEventEntity(id = n.toLong(), trackId = song(n).id, eventType = "ACTUAL_PLAY",
            sessionId = "play-$n", playedDurationMs = if (n == 3) 0 else 30_000) }
        events.forEach { db.listeningEventDao().insertListeningEvent(it) }
        // Repeating the same song and zero-duration selection are not a third distinct listen.
        db.listeningEventDao().insertListeningEvent(events.first().copy(id = 4, sessionId = "repeat-one"))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10)); delay(300)
        assertFalse(vm.additionalShelvesUnlocked.value)
        db.listeningEventDao().insertListeningEvent(events.last().copy(playedDurationMs = 30_000))
        @Suppress("UNCHECKED_CAST")
        val threshold = vm.javaClass.getDeclaredField("listeningUnlockAt").apply { isAccessible = true }.get(vm) as StateFlow<Long?>
        withTimeout(5_000) { threshold.first { it != null } }
        val audio = BackgroundWorkCoordinator.busy()
        try {
            ShadowSystemClock.advanceBy(Duration.ofMillis(5_999)); delay(300)
            assertFalse(vm.additionalShelvesUnlocked.value)
            BackgroundWorkCoordinator.interaction()
            ShadowSystemClock.advanceBy(Duration.ofMillis(5_999)); delay(300)
            assertFalse(vm.additionalShelvesUnlocked.value)
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            withTimeout(5_000) { vm.additionalShelvesUnlocked.first { it }; vm.revealedShelfCount.first { it == 5 } }
            assertEquals(0, pages.get()); assertFalse(vm.artistPortraitsReady.value)
        } finally { audio.close() }
    }
}
