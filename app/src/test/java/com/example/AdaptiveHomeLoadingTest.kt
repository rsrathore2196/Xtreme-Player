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
    private val idleAdmissions = AtomicInteger()
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
        refreshTracks: Boolean = false, failProfile: Boolean = false,
        supplementWait: CompletableDeferred<Unit>? = null, failSupplements: Boolean = false,
        profileWait: CompletableDeferred<Unit>? = null): HomeViewModel {
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
                    profileWait?.await()
                    if (failProfile) throw java.io.IOException("Temporary profile failure")
                    YouTubeMusicApiService.ProfileShelfResult(shelf(20).copy(title = "Music for You")) },
                idleAdmission = { start ->
                    idleAdmissions.incrementAndGet()
                    if (optional) BackgroundWorkCoordinator.awaitContinuousForegroundIdle(start, 6_000L) else awaitCancellation()
                },
                connectivityUpdates = emptyFlow(),
                supplementalLoader = { _, browse, _ ->
                    supplements.incrementAndGet()
                    supplementWait?.await()
                    if (failSupplements) throw java.io.IOException("Supplement unavailable")
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
        try {
            withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it == com.example.recommendation.HomeUserMode.NEW_USER } }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("Initial fixture: shelves=${vm.serverShelves.value.size} mode=${vm.userMode.value} " +
                "error=${vm.refreshErrorMessage.value} refreshing=${vm.isRefreshing.value}", error)
        }
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
        idle(30_000)
        assertEquals("Idle alone must not fetch pages beyond the cached feed", 0, pages.get())
        vm.revealNextHomeSection(); idle()
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 9 }; vm.serverShelves.first { it.size == 11 } }
        assertEquals(1, pages.get())
        vm.setHomeVisible(false); delay(100); idle(30_000)
        assertEquals(9, vm.revealedShelfCount.value); assertEquals(1, pages.get())
    }
    @Test fun returningUsersWithOlderActualHistoryAdmitProfileThenArtistsBeforeNativeExpansion() = runBlocking<Unit> {
        db.withTransaction {
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(1)))
            for (n in 0..300) db.listeningEventDao().insertListeningEvent(ListeningEventEntity(
                trackId = song(1).id, eventType = "ACTUAL_PLAY", sessionId = "occurrence-$n", timestamp = n.toLong(),
                playedDurationMs = if (n == 0) 5_000 else 0))
            db.saveArtistTotal(song(1), 5_000, 0)
        }
        val vm = model(optional = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock()
        idle(5_999); assertFalse(vm.profileShelfVisible.value); assertFalse(vm.artistPortraitsReady.value)
        idle(1); settled(vm, "initialProfileSettledAt")
        assertNotNull(vm.profileShelf.value); assertTrue(vm.profileShelfVisible.value)
        assertTrue(vm.mostListenedArtists.value.isEmpty()); assertEquals(3, vm.revealedShelfCount.value)
        assertEquals(1, profiles.get())
        idle(5_999); assertFalse(vm.artistPortraitsReady.value)
        idle(1); settled(vm, "initialArtistSettledAt")
        assertEquals(0, vm.mostListenedArtists.value.single().playCount)
        assertEquals(3, vm.revealedShelfCount.value)
        idle(5_999); assertEquals(3, vm.revealedShelfCount.value)
        idle(1); withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
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
        for (n in 1..2) db.saveArtistTotal(song(n))
        db.listeningEventDao().insertListeningEvent(events.first().copy(id = 4, sessionId = "repeat-one"))
        idle(12_000)
        withTimeout(5_000) { vm.pillsCardTracks.first { it.size == 2 } }
        assertFalse(vm.artistPortraitsReady.value); assertNull(vm.profileShelf.value); assertTrue(vm.mostListenedArtists.value.isEmpty())
        db.listeningEventDao().insertListeningEvent(events.last().copy(playedDurationMs = 30_000))
        db.saveArtistTotal(song(3))
        @Suppress("UNCHECKED_CAST")
        val threshold = vm.javaClass.getDeclaredField("listeningUnlockAt").apply { isAccessible = true }.get(vm) as StateFlow<Long?>
        withTimeout(5_000) { threshold.first { it != null } }
        val audio = BackgroundWorkCoordinator.busy()
        try {
            idle(5_999); assertFalse(vm.profileShelfVisible.value)
            BackgroundWorkCoordinator.interaction(); idle(5_999); assertFalse(vm.profileShelfVisible.value)
            idle(1)
            val nativeCount = vm.revealedShelfCount.value
            settled(vm, "initialProfileSettledAt")
            assertNotNull(vm.profileShelf.value); assertFalse(vm.artistPortraitsReady.value)
            assertEquals(nativeCount, vm.revealedShelfCount.value)
            idle(5_999); assertFalse(vm.artistPortraitsReady.value)
            idle(1); settled(vm, "initialArtistSettledAt")
            assertEquals(3, vm.mostListenedArtists.value.size)
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
        db.saveArtistTotal(song(1))
    }
    @Test fun pendingProfileDoesNotBlockArtistsOrCachedNativeRevealOrRestartOnTouch() = runBlocking<Unit> {
        returningHistory()
        val release = CompletableDeferred<Unit>()
        val vm = model(optional = true, profileWait = release)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock(); idle()
        withTimeout(5_000) { while (profiles.get() != 1) delay(10) }
        val profileAdmittedAt = SystemClock.elapsedRealtime()
        assertNull(vm.profileShelf.value)
        idle(); settled(vm, "initialArtistSettledAt")
        val artistsReadyAt = SystemClock.elapsedRealtime()
        assertTrue(vm.mostListenedArtists.value.isNotEmpty())
        assertNull(vm.profileShelf.value)
        idle(); withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
        val cachedRevealAt = SystemClock.elapsedRealtime()
        assertEquals(0, pages.get()); assertEquals(0, supplements.get())
        BackgroundWorkCoordinator.touch(true); delay(100)
        release.complete(Unit)
        delay(200)
        assertNull("Optional publication waits for input without cancelling transport", vm.profileShelf.value)
        BackgroundWorkCoordinator.touch(false); idle(300)
        withTimeout(5_000) { vm.profileShelf.first { it != null } }
        assertEquals("Interaction must not restart an admitted profile request", 1, profiles.get())
        println("Home independence (fixture clock): profileAdmittedAt=$profileAdmittedAt artistsReadyAt=$artistsReadyAt " +
            "cachedRevealAt=$cachedRevealAt profileReadyAt=${SystemClock.elapsedRealtime()} " +
            "artists=${vm.mostListenedArtists.value.size}, revealed=${vm.revealedShelfCount.value}, profileRequests=${profiles.get()}, pages=${pages.get()}")
    }
    @Test fun nativeRefreshKeepsFreshProfileWhileExplicitFullRefreshCanRenewIt() = runBlocking<Unit> {
        returningHistory()
        val vm = model(optional = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock(); idle(); settled(vm, "initialProfileSettledAt")
        assertEquals(1, profiles.get())
        vm.refreshHome(true).join()
        idle(); delay(100)
        assertEquals("A native refresh does not invalidate the fresh profile shelf", 1, profiles.get())
        assertNotNull(vm.profileShelf.value)
        vm.refreshAll().join()
        withTimeout(5_000) { while (profiles.get() != 2) delay(10) }
        assertNotNull(vm.profileShelf.value)
    }
    @Test fun completedOptionalAdmissionsSurviveTouchesWithoutRepeatingTheIdleStages() = runBlocking<Unit> {
        returningHistory()
        val vm = model(optional = true)
        withTimeout(5_000) {
            vm.serverShelves.first { it.size == 8 }
            vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING }
        }
        stopClock(); idle(); settled(vm, "initialProfileSettledAt")
        idle(); settled(vm, "initialArtistSettledAt")
        assertTrue(vm.optionalWorkReady.value)
        @Suppress("UNCHECKED_CAST")
        val profileReady = vm.javaClass.getDeclaredField("profileWorkReady").apply { isAccessible = true }
            .get(vm) as StateFlow<Boolean>
        assertTrue(profileReady.value)
        val admissions = idleAdmissions.get()
        repeat(4) {
            BackgroundWorkCoordinator.touch(true); delay(75)
            assertTrue("A completed profile admission must survive input", profileReady.value)
            assertTrue("A completed artist admission must survive input", vm.optionalWorkReady.value)
            BackgroundWorkCoordinator.touch(false); delay(75)
        }
        assertEquals("Touches must not relaunch completed idle waits", admissions, idleAdmissions.get())
        assertEquals(1, profiles.get())

        BackgroundWorkCoordinator.foreground(false)
        withTimeout(5_000) { vm.optionalWorkReady.first { !it }; profileReady.first { !it } }
        BackgroundWorkCoordinator.foreground(true)
        delay(100)
        assertFalse("A new foreground lifecycle must pass the idle gate again", vm.optionalWorkReady.value)
        idle(5_999); assertFalse(vm.optionalWorkReady.value)
        idle(1)
        withTimeout(5_000) { vm.optionalWorkReady.first { it }; profileReady.first { it } }
        assertEquals("Retained fresh profile content must not be fetched again", 1, profiles.get())
    }
    @Test fun returningActualPlaysReserveProfileAndArtistStagesBeforeNativeExpansion() = runBlocking<Unit> {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(1)))
        db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = song(1).id, artistName = song(1).artist,
            eventType = "ACTUAL_PLAY", playedDurationMs = 5_000, sessionId = "returning-short"))
        db.saveArtistTotal(song(1), 5_000, 0)
        val vm = model(optional = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock(); idle(); settled(vm, "initialProfileSettledAt")
        assertEquals(3, vm.revealedShelfCount.value); assertFalse(vm.artistPortraitsReady.value)
        assertEquals(1, profiles.get())
        idle(); settled(vm, "initialArtistSettledAt")
        assertTrue(vm.mostListenedArtists.value.isNotEmpty()); assertEquals(3, vm.revealedShelfCount.value)
        idle(); withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
    }
    @Test fun optionalIdleSequenceStartsAfterInitialNativeShelvesAreReady() = runBlocking<Unit> {
        returningHistory()
        val first = CompletableDeferred<Unit>()
        val vm = model(first = first, optional = true)
        withTimeout(5_000) { vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock(); idle(20_000)
        assertFalse("Artists must not consume their slot before the initial native presentation", vm.artistPortraitsReady.value)
        assertEquals(0, profiles.get())
        first.complete(Unit)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 } }
        idle(5_999); assertFalse(vm.artistPortraitsReady.value)
        idle(1); settled(vm, "initialProfileSettledAt")
        assertNotNull(vm.profileShelf.value); assertFalse(vm.artistPortraitsReady.value); assertEquals(3, vm.revealedShelfCount.value)
        idle(5_999); assertFalse(vm.artistPortraitsReady.value)
        idle(1); settled(vm, "initialArtistSettledAt")
        assertEquals(3, vm.revealedShelfCount.value)
        idle(5_999); assertEquals(3, vm.revealedShelfCount.value)
        idle(1); withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
    }
    @Test fun initialShortFeedWaitsForItsNativeSupplementWindowBeforeArtistAdmission() = runBlocking<Unit> {
        returningHistory()
        val release = CompletableDeferred<Unit>()
        val vm = model(initial = 1, optional = true, supplementWait = release)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 1 }; while (supplements.get() != 2) delay(10) }
        stopClock(); idle(20_000)
        assertFalse(vm.artistPortraitsReady.value); assertEquals(0, profiles.get())
        release.complete(Unit)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 3 } }
        idle(5_999); assertFalse(vm.artistPortraitsReady.value)
        idle(1); settled(vm, "initialProfileSettledAt")
        assertNotNull(vm.profileShelf.value); assertFalse(vm.artistPortraitsReady.value); assertEquals(1, profiles.get())
        idle(); settled(vm, "initialArtistSettledAt")
        assertEquals(3, vm.revealedShelfCount.value)
    }
    @Test fun failedSupplementsDoNotPermanentlyBlockOptionalStagesWhenOnlyTwoNativeShelvesExist() = runBlocking<Unit> {
        returningHistory()
        val vm = model(initial = 2, optional = true, failSupplements = true)
        withTimeout(5_000) {
            vm.serverShelves.first { it.size == 2 }
            vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING }
            while (supplements.get() != 2) delay(10)
        }
        settled(vm, "initialNativeReadyAt")
        stopClock(); idle()
        settled(vm, "initialProfileSettledAt")
        assertNotNull(vm.profileShelf.value); assertFalse(vm.artistPortraitsReady.value); assertEquals(1, profiles.get())
        idle(); settled(vm, "initialArtistSettledAt")
        assertNotNull(vm.profileShelf.value); assertEquals(2, vm.revealedShelfCount.value)
    }
    @Test fun cachedOptionalCardsUseProfileThenArtistSlotsWithoutProfileNetworkWork() = runBlocking<Unit> {
        returningHistory()
        val profile = UserProfilePreferences.peekUserProfile() ?: UserProfile()
        val homeKey = HomeCacheIdentity.home(profile)
        val cache = HomeCardCache(app)
        cache.saveLocal(SavedHomeCards(homeKey, artists = listOf(MostListenedArtist(song(1).artist, 1, listOf(song(1))))))
        cache.saveProfile(SavedHomeCards(homeKey, profileShelfKey = HomeCacheIdentity.profile(profile), profileShelf = shelf(20),
            profileFetchedAt = System.currentTimeMillis(), profileFetchSucceeded = true))
        val vm = model(optional = true)
        withTimeout(5_000) {
            vm.serverShelves.first { it.size == 8 }; vm.profileShelf.first { it != null }; vm.mostListenedArtists.first { it.isNotEmpty() }
            vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING }
        }
        stopClock()
        assertFalse(vm.artistPortraitsReady.value); assertFalse(vm.profileShelfVisible.value)
        idle(); settled(vm, "initialProfileSettledAt")
        assertTrue(vm.profileShelfVisible.value); assertFalse(vm.artistPortraitsReady.value)
        assertEquals(3, vm.revealedShelfCount.value)
        idle(); settled(vm, "initialArtistSettledAt")
        assertTrue(vm.artistPortraitsReady.value); assertEquals(0, profiles.get()); assertEquals(3, vm.revealedShelfCount.value)
        idle(); withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
    }
    @Test fun profileFailureStillAdmitsArtistsBeforeNativeCardsWithoutRetryLoops() = runBlocking<Unit> {
        returningHistory()
        val vm = model(optional = true, failProfile = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock(); idle(); settled(vm, "initialProfileSettledAt")
        assertNull(vm.profileShelf.value); assertEquals(1, profiles.get()); assertEquals(3, vm.revealedShelfCount.value)
        assertFalse(vm.artistPortraitsReady.value)
        idle(); settled(vm, "initialArtistSettledAt")
        assertTrue(vm.mostListenedArtists.value.isNotEmpty()); assertEquals(3, vm.revealedShelfCount.value)
        idle(); withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
        idle(); withTimeout(5_000) { vm.revealedShelfCount.first { it == 5 } }
        assertEquals(1, profiles.get())
    }
    @Test fun leavingHomeAfterProfilePreservesItAndResetsArtistAdmissionOnReturn() = runBlocking<Unit> {
        returningHistory()
        val vm = model(optional = true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 8 }; vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        stopClock(); idle(); settled(vm, "initialProfileSettledAt")
        vm.setHomeVisible(false); delay(100)
        idle(30_000)
        assertEquals(1, profiles.get()); assertEquals(3, vm.revealedShelfCount.value)
        assertFalse(vm.artistPortraitsReady.value); assertNotNull(vm.profileShelf.value)
        val input = BackgroundWorkCoordinator.interactions.value
        vm.setHomeVisible(true)
        withTimeout(5_000) { BackgroundWorkCoordinator.interactions.first { it > input } }
        idle(5_999); assertFalse(vm.artistPortraitsReady.value)
        idle(1); settled(vm, "initialArtistSettledAt")
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
