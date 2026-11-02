package com.example

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.*
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.cache.HomeCardCache
import com.example.data.cache.HomeCacheIdentity
import com.example.data.cache.SavedHomeCards
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.MostListenedArtist
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
    private val pages = AtomicInteger(); private val supplements = AtomicInteger(); private val profiles = AtomicInteger()
    private val primaryCalls = AtomicInteger()
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
    @After fun close() = runBlocking<Unit> { stores.forEach { it.clear() }; player.release(); db.close(); clockPump?.cancelAndJoin(); BackgroundWorkCoordinator.foreground(false); Dispatchers.resetMain() }
    private suspend fun model(initial: Int = 8, first: CompletableDeferred<Unit>? = null, optional: Boolean = false,
        refreshTracks: Boolean = false, failProfile: Boolean = false): HomeViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                MusicRepository(db.musicDao(), app), player, app, db,
                homeLoader = { _, token, _ ->
                    if (token == null) {
                        first?.await()
                        val updated = refreshTracks && primaryCalls.incrementAndGet() > 1
                        YouTubeHomeParser.ParsedHomeFeed((1..initial).map { n ->
                            shelf(n).let { if (updated) it.copy(tracks = listOf(song(n).copy(title = "Updated song $n"))) else it }
                        }, "next")
                    }
                    else { pages.incrementAndGet(); YouTubeHomeParser.ParsedHomeFeed((9..11).map(::shelf), null) }
                }, profileLoader = { _, _, _ ->
                    profiles.incrementAndGet()
                    if (failProfile) throw java.io.IOException("Temporary profile failure")
                    YouTubeMusicApiService.ProfileShelfResult(shelf(20).copy(title = "Music for You")) },
                idleAdmission = { start -> if (optional) BackgroundWorkCoordinator.awaitContinuousForegroundIdle(start, 6_000L) else awaitCancellation() },
                connectivityUpdates = emptyFlow(),
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
    private suspend fun stopClock() {
        clockPump?.cancelAndJoin()
        BackgroundWorkCoordinator.interaction()
        delay(100)
    }
    private suspend fun idle(millis: Long = 6_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis)); delay(300)
    }
    @Suppress("UNCHECKED_CAST")
    private suspend fun settled(vm: HomeViewModel, field: String) {
        val state = vm.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(vm) as StateFlow<Long?>
        withTimeout(5_000) { state.first { it != null } }
    }
    @Test fun newcomersStartWithThreeAndAutomaticallyRevealOnlyOneAfterEachSixSecondIdlePeriod() = runBlocking<Unit> {
        val vm = model()
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it == com.example.recommendation.HomeUserMode.NEW_USER } }
        stopClock()
        assertEquals(3, vm.revealedShelfCount.value)
        vm.revealNextHomeSection(); idle(5_999)
        assertEquals(3, vm.revealedShelfCount.value)
        BackgroundWorkCoordinator.interaction(); idle(5_999)
        assertEquals(3, vm.revealedShelfCount.value)
        idle(1)
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
        idle(5_999); assertEquals(4, vm.revealedShelfCount.value)
        idle(1)
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 5 } }
        assertEquals(0, pages.get()); assertEquals(0, supplements.get())
        assertFalse(vm.artistPortraitsReady.value)
    }
    @Test fun manualRefreshStillFetchesTheNativeSourcesTogetherWithoutWaitingForIdle() = runBlocking<Unit> {
        val vm = model()
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 } }
        stopClock()
        withTimeout(5_000) { vm.refreshHome(true).join() }
        assertEquals(2, supplements.get()); assertEquals(0, pages.get())
        assertEquals(4, vm.revealedShelfCount.value); assertFalse(vm.isRefreshing.value)
        assertFalse(vm.artistPortraitsReady.value)
        idle(5_999); assertEquals(4, vm.revealedShelfCount.value)
        idle(1)
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 5 } }
    }
    @Test fun aShortPrimaryResponseComesBeforeBoundedSupplementsAndInitiallyShowsAtMostThree() = runBlocking<Unit> {
        val first = CompletableDeferred<Unit>(); val vm = model(initial = 2, first = first)
        delay(100)
        assertTrue(vm.serverShelves.value.isEmpty()); assertEquals(0, supplements.get())
        first.complete(Unit)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 4 } }
        assertEquals(2, supplements.get()); assertEquals(0, pages.get())
        assertEquals(3, vm.revealedShelfCount.value)
    }
    @Test fun automaticRevealUsesAllCachedShelvesBeforeOneNativePageAndStopsWhenHidden() = runBlocking<Unit> {
        val vm = model()
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 } }
        stopClock()
        for (count in 4..8) { idle(); withTimeout(5_000) { vm.revealedShelfCount.first { it == count } }; assertEquals(0, pages.get()) }
        idle()
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 9 }; vm.serverShelves.first { it.size == 11 } }
        assertEquals(1, pages.get())
        vm.setHomeVisible(false); delay(100); idle(30_000)
        assertEquals(9, vm.revealedShelfCount.value); assertEquals(1, pages.get())
    }
    @Test fun returningUsersWithOlderMeaningfulHistoryAdmitOptionalShelvesAfterSixSeconds() = runBlocking<Unit> {
        db.withTransaction {
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(1)))
            for (n in 0..300) db.listeningEventDao().insertListeningEvent(ListeningEventEntity(
                trackId = song(1).id, eventType = "ACTUAL_PLAY", sessionId = "occurrence-$n", timestamp = n.toLong(),
                playedDurationMs = if (n == 0) 30_000 else 0))
        }
        val vm = model(optional = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock()
        assertEquals(3, vm.revealedShelfCount.value); assertFalse(vm.artistPortraitsReady.value)
        idle(5_999); assertFalse(vm.artistPortraitsReady.value)
        idle(1)
        withTimeout(5_000) { vm.artistPortraitsReady.first { it }; vm.mostListenedArtists.first { it.isNotEmpty() } }
        settled(vm, "initialArtistSettledAt")
        assertEquals(1, vm.mostListenedArtists.value.single().playCount)
        assertNull(vm.profileShelf.value); assertFalse(vm.profileShelfVisible.value)
        assertEquals(3, vm.revealedShelfCount.value); assertEquals(0, profiles.get())
        idle(5_999); assertEquals(0, profiles.get())
        idle(1)
        withTimeout(5_000) { vm.profileShelfVisible.first { it }; vm.profileShelf.first { it != null } }
        settled(vm, "initialProfileSettledAt")
        assertEquals(3, vm.revealedShelfCount.value)
        idle(5_999); assertEquals(3, vm.revealedShelfCount.value)
        idle(1)
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
        assertEquals(1, profiles.get()); assertEquals(0, pages.get())
    }
    @Test fun newUserOptionalShelvesRequireThreeDistinctMeaningfulSongsAndSixIdleSecondsButRecentlyPlayedDoesNot() = runBlocking<Unit> {
        val vm = model(optional = true)
        withTimeout(5_000) { vm.userMode.first { it == com.example.recommendation.HomeUserMode.NEW_USER }; vm.serverShelves.first { it.size == 8 } }
        stopClock()
        for (n in 1..3) db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(n)))
        val events = (1..3).map { n -> ListeningEventEntity(id = n.toLong(), trackId = song(n).id, artistName = song(n).artist, eventType = "ACTUAL_PLAY",
            sessionId = "play-$n", playedDurationMs = if (n == 3) 0 else 30_000) }
        events.forEach { db.listeningEventDao().insertListeningEvent(it) }
        db.listeningEventDao().insertListeningEvent(events.first().copy(id = 4, sessionId = "repeat-one"))
        idle(12_000)
        withTimeout(5_000) { vm.pillsCardTracks.first { it.size == 2 } }
        assertFalse(vm.artistPortraitsReady.value); assertNull(vm.profileShelf.value); assertTrue(vm.mostListenedArtists.value.isEmpty())
        db.listeningEventDao().insertListeningEvent(events.last().copy(playedDurationMs = 30_000))
        @Suppress("UNCHECKED_CAST")
        val threshold = vm.javaClass.getDeclaredField("listeningUnlockAt").apply { isAccessible = true }.get(vm) as StateFlow<Long?>
        withTimeout(5_000) { threshold.first { it != null } }
        val audio = BackgroundWorkCoordinator.busy()
        try {
            idle(5_999); assertFalse(vm.artistPortraitsReady.value)
            BackgroundWorkCoordinator.interaction(); idle(5_999); assertFalse(vm.artistPortraitsReady.value)
            idle(1)
            val nativeCount = vm.revealedShelfCount.value
            withTimeout(5_000) { vm.artistPortraitsReady.first { it }; vm.mostListenedArtists.first { it.size == 3 } }
            settled(vm, "initialArtistSettledAt")
            assertNull(vm.profileShelf.value); assertEquals(nativeCount, vm.revealedShelfCount.value)
            idle()
            withTimeout(5_000) { vm.profileShelf.first { it != null } }
            settled(vm, "initialProfileSettledAt")
            assertEquals(nativeCount, vm.revealedShelfCount.value)
            idle()
            withTimeout(5_000) { vm.revealedShelfCount.first { it == nativeCount + 1 } }
            assertEquals(3, vm.pillsCardTracks.value.size)
        } finally { audio.close() }
    }
    private suspend fun returningHistory() {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(1)))
        db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = song(1).id, artistName = song(1).artist,
            eventType = "ACTUAL_PLAY", playedDurationMs = 30_000, sessionId = "returning"))
    }
    @Test fun cachedOptionalCardsUseTheirSeparateSlotsWithoutProfileNetworkWork() = runBlocking<Unit> {
        returningHistory()
        val profile = UserProfilePreferences.peekUserProfile() ?: UserProfile()
        val homeKey = HomeCacheIdentity.home(profile)
        val cache = HomeCardCache(app)
        cache.saveLocal(SavedHomeCards(homeKey, artists = listOf(MostListenedArtist(song(1).artist, 1, listOf(song(1)))),
            inputRevision = db.listeningEventDao().getArtistInputRevision()))
        cache.saveProfile(SavedHomeCards(homeKey, profileShelfKey = HomeCacheIdentity.profile(profile), profileShelf = shelf(20),
            profileFetchedAt = System.currentTimeMillis(), profileFetchSucceeded = true))
        val vm = model(optional = true)
        withTimeout(5_000) {
            vm.serverShelves.first { it.size == 8 }; vm.profileShelf.first { it != null }; vm.mostListenedArtists.first { it.isNotEmpty() }
            vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING }
        }
        stopClock()
        assertFalse(vm.artistPortraitsReady.value); assertFalse(vm.profileShelfVisible.value)
        idle()
        withTimeout(5_000) { vm.artistPortraitsReady.first { it } }
        settled(vm, "initialArtistSettledAt")
        assertFalse(vm.profileShelfVisible.value); assertEquals(3, vm.revealedShelfCount.value)
        idle()
        withTimeout(5_000) { vm.profileShelfVisible.first { it } }
        settled(vm, "initialProfileSettledAt")
        assertEquals(0, profiles.get()); assertEquals(3, vm.revealedShelfCount.value)
        idle()
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
    }
    @Test fun profileFailureFinishesItsSlotWithoutBlockingNativeCardsOrCreatingRetryLoops() = runBlocking<Unit> {
        returningHistory()
        val vm = model(optional = true, failProfile = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock(); idle()
        settled(vm, "initialArtistSettledAt")
        assertEquals(0, profiles.get()); assertEquals(3, vm.revealedShelfCount.value)
        idle()
        settled(vm, "initialProfileSettledAt")
        assertNull(vm.profileShelf.value); assertEquals(1, profiles.get()); assertEquals(3, vm.revealedShelfCount.value)
        idle()
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
        idle()
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 5 } }
        assertEquals(1, profiles.get())
    }
    @Test fun leavingHomeDuringProfileWaitPreservesArtistsAndResetsAdmissionOnReturn() = runBlocking<Unit> {
        returningHistory()
        val vm = model(optional = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock(); idle(); settled(vm, "initialArtistSettledAt")
        vm.setHomeVisible(false)
        withTimeout(5_000) { vm.optionalWorkReady.first { !it } }
        idle(30_000)
        assertEquals(0, profiles.get()); assertEquals(3, vm.revealedShelfCount.value)
        val input = BackgroundWorkCoordinator.interactions.value
        vm.setHomeVisible(true)
        withTimeout(5_000) { BackgroundWorkCoordinator.interactions.first { it > input } }
        idle(5_999); assertEquals(0, profiles.get())
        idle(1); settled(vm, "initialProfileSettledAt")
        assertEquals(1, profiles.get()); assertTrue(vm.mostListenedArtists.value.isNotEmpty())
        assertEquals(3, vm.revealedShelfCount.value)
    }
    @Test fun manualRefreshDuringPriorityWaitAddsUniqueNativeShelvesAndRefreshesTracksImmediately() = runBlocking<Unit> {
        returningHistory()
        val vm = model(optional = true, refreshTracks = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock()
        withTimeout(5_000) { vm.refreshHome(true).join() }
        assertEquals(2, primaryCalls.get()); assertEquals(2, supplements.get())
        assertEquals(10, vm.serverShelves.value.size)
        assertEquals("Updated song 1", vm.serverShelves.value.first().tracks.first().title)
        assertEquals(10, vm.serverShelves.value.map { it.id }.distinct().size)
        assertFalse(vm.artistPortraitsReady.value); assertFalse(vm.profileShelfVisible.value)
        assertFalse(vm.isRefreshing.value); assertEquals(4, vm.revealedShelfCount.value)
    }
}
