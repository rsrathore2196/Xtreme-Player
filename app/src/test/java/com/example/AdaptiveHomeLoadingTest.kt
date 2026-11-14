package com.example

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.cache.*
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.HomeViewModel
import java.time.Duration
import java.util.concurrent.Executor
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

/** Cached presentation is immediate; authoritative startup work uses short, serial quiet gates. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdaptiveHomeLoadingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: MusicDatabase
    private lateinit var player: PlaybackManager
    private val stores = mutableListOf<ViewModelStore>()
    private val primary = AtomicInteger(); private val supplements = AtomicInteger(); private val profiles = AtomicInteger()
    private val cores = AtomicInteger(); private val historyReads = AtomicInteger(); private val artistReads = AtomicInteger()
    private var clockPump: Job? = null
    private fun song(n: Int) = MusicTrack("yt_${NativeFeedFixtures.id(n)}", "Song $n", "Artist $n", "Album", 120_000, "", "")
    private fun shelf(n: Int) = ServerHomeShelf("s$n", when(n) { 1 -> "Quick Picks"; 2 -> "Albums & Singles"; 3 -> "Trending Community Playlists"; else -> "Native $n" }, tracks = listOf(song(n)))
    private val native get() = listOf(5, 3, 2, 1, 4, 6, 7).map(::shelf)
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        UserProfilePreferences.saveUserProfile(app, UserProfile(countryCode = "US"))
        BackgroundWorkCoordinator.apply { foreground(true); windowFocus(true); firstFrameRendered(); scrolling(false); touch(false); frame(2, 1) }
        db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java).addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { HomeInputRevisionTriggers.install(db) }
        }).setQueryCallback({ sql, _ ->
            if (sql.contains("COUNT(*) AS total") && sql.contains("FROM listening_events")) historyReads.incrementAndGet()
            if (sql.contains("FROM listening_totals") && sql.contains("GROUP BY identity")) artistReads.incrementAndGet()
        }, Executor { it.run() }).build()
        player = PlaybackManager(app, MusicRepository(db.musicDao(), app))
        clockPump = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { while (isActive) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(25)); delay(25)
        } }
    }
    @After fun close() = runBlocking<Unit> {
        stores.forEach { it.clear() }; player.release(); db.close(); clockPump?.cancelAndJoin()
        BackgroundWorkCoordinator.foreground(false); Dispatchers.resetMain()
    }
    private suspend fun history() {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(1)))
        db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = song(1).id, artistName = song(1).artist,
            eventType = "ACTUAL_PLAY", playedDurationMs = 30_000, sessionId = "returning"))
        db.saveArtistTotal(song(1)); historyReads.set(0); artistReads.set(0)
    }
    private suspend fun cache(profile: Boolean = false, artists: Boolean = false, includeNative: Boolean = true) {
        HomeCardCache(app).save(SavedHomeCards("US|anonymous", System.currentTimeMillis(), serverShelves = if (includeNative) native else emptyList(),
            serverFetchSucceeded = includeNative, history = com.example.ui.viewmodel.SectionState("Jump Back In", tracks = listOf(song(1))),
            artists = if (artists) listOf(com.example.ui.viewmodel.MostListenedArtist("Artist 1", 1, listOf(song(1)))) else emptyList(),
            profileShelfKey = if (profile) HomeCacheIdentity.profile(UserProfile(countryCode = "US"), "anonymous") else "", profileFetchedAt = System.currentTimeMillis(),
            profileFetchSucceeded = profile, profileShelf = if (profile) shelf(20).copy(title = "Music for You") else null))
    }
    private suspend fun model(core: CompletableDeferred<Unit>? = null, profile: CompletableDeferred<Unit>? = null,
        failHome: Boolean = false, failProfile: Boolean = false, draw: Boolean = true,
        nativeGate: CompletableDeferred<Unit>? = null, awaitNative: Boolean = true): HomeViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                MusicRepository(db.musicDao(), app), player, app, db,
                homeLoader = { _, token, _ -> primary.incrementAndGet(); nativeGate?.await(); if (failHome) throw java.io.IOException("Offline")
                    YouTubeHomeParser.ParsedHomeFeed(if (token == null) native else listOf(shelf(30)), if (token == null) "next" else null) },
                profileLoader = { _, _, _ -> profiles.incrementAndGet(); profile?.await()
                    if (failProfile) throw java.io.IOException("Profile unavailable")
                    YouTubeMusicApiService.ProfileShelfResult(shelf(20).copy(title = "Music for You")) },
                accountIdentity = { "anonymous" }, accountUpdates = emptyFlow<Unit>(), connectivityUpdates = emptyFlow(),
                supplementalLoader = { _, _, _ -> supplements.incrementAndGet(); if (failHome) throw java.io.IOException("Offline")
                    YouTubeHomeParser.ParsedHomeFeed(emptyList(), null) },
                prepareCoreTabs = { cores.incrementAndGet(); core?.await() }) as T
        }
        val vm = ViewModelProvider(store, factory)[HomeViewModel::class.java]
        vm.setHomeVisible(true)
        if (awaitNative) withTimeout(5_000) { vm.initialHomePreparedAt.first { it != null } }
        if (draw) {
            vm.onHomeFrameDrawn(vm.initialHomePreparedAt.value)
            withTimeout(5_000) { while (cores.get() == 0) delay(10) }
            if (core == null) withTimeout(5_000) { vm.coreTabsReady.first { it } }
        }
        return vm
    }
    private suspend fun stopClock() { clockPump?.cancelAndJoin(); BackgroundWorkCoordinator.interaction(); delay(100) }
    private suspend fun idle(ms: Long = 750) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)); delay(300) }
    @Suppress("UNCHECKED_CAST")
    private suspend fun settled(vm: HomeViewModel, name: String) {
        val state = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(vm) as StateFlow<Long?>
        withTimeout(5_000) { state.first { it != null } }
    }
    private suspend fun recent(vm: HomeViewModel) { withTimeout(5_000) { vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }; settled(vm, "initialHistorySettledAt") }
    private suspend fun profile(vm: HomeViewModel) { withTimeout(5_000) { vm.profileShelf.first { it != null } }; settled(vm, "initialProfileSettledAt") }
    private suspend fun artists(vm: HomeViewModel) { withTimeout(5_000) { vm.optionalWorkReady.first { it }; while(artistReads.get() == 0) delay(10) }; settled(vm, "initialArtistSettledAt") }
    private suspend fun throughArtists(vm: HomeViewModel) { idle(); recent(vm); idle(); profile(vm); idle(); artists(vm) }
    private fun titles(vm: HomeViewModel) = vm.serverCards.value.mapNotNull { it.shelf?.title }
    private suspend fun revealed(vm: HomeViewModel, count: Int) {
        val result = withTimeoutOrNull(5_000) { vm.revealedShelfCount.first { it == count } }
        assertNotNull("Expected $count visible shelves, actual=${vm.revealedShelfCount.value}, cached=${vm.serverShelves.value.size}, nativeStage=${vm.additionalShelvesUnlocked.value}", result)
    }

    @Test fun cacheFirstOpeningProjectsOnlyTheThreeRequiredShelvesInOrder() = runBlocking<Unit> {
        cache(); val vm = model(); stopClock()
        assertEquals(listOf("Quick Picks", "Albums & Singles", "Trending Community Playlists"), titles(vm))
        assertEquals(3, vm.serverShelves.value.size); assertEquals(0, primary.get()); assertEquals(0, supplements.get())
        assertEquals(0, historyReads.get()); assertEquals(0, artistReads.get()); assertEquals(0, profiles.get())
    }
    @Test fun cachedPresentationAndCoreTabsDoNotWaitForNativeHomeTransportOrHistoricalQueries() = runBlocking<Unit> {
        cache(profile = true, artists = true, includeNative = false)
        val release = CompletableDeferred<Unit>()
        val vm = model(nativeGate = release, awaitNative = false)
        try {
            stopClock()
            assertNull(vm.initialHomePreparedAt.value)
            assertTrue(vm.coreTabsReady.value)
            assertTrue(vm.hasRecentlyPlayed.value)
            assertEquals(listOf(song(1)), vm.listeningHistoryCard.value.tracks)
            assertTrue(vm.profileShelfVisible.value)
            assertEquals("Music for You", vm.profileShelf.value?.title)
            assertEquals("Artist 1", vm.mostListenedArtists.value.single().name)
            assertTrue(vm.artistPortraitsReady.value)
            assertEquals(0, historyReads.get()); assertEquals(0, artistReads.get()); assertEquals(0, profiles.get())
            assertFalse(vm.optionalWorkReady.value)
        } finally { release.complete(Unit) }
    }
    @Test fun coreTabsMustSettleBeforeAnyHistoricalReadOrOptionalProfileRequest() = runBlocking<Unit> {
        history(); val release = CompletableDeferred<Unit>(); val vm = model(core = release); stopClock(); idle(60_000)
        assertFalse(vm.coreTabsReady.value); assertEquals(0, historyReads.get()); assertEquals(0, profiles.get())
        release.complete(Unit); withTimeout(5_000) { vm.coreTabsReady.first { it } }
        idle(749); assertEquals(0, historyReads.get()); idle(1); recent(vm); assertTrue(historyReads.get() > 0)
    }
    @Test fun firstHomeDrawIsRequiredEvenAfterCachedPriorityDataIsReady() = runBlocking<Unit> {
        cache(); val vm = model(draw = false); stopClock(); idle(60_000)
        assertEquals(0, cores.get()); assertEquals(0, historyReads.get())
        vm.onHomeFrameDrawn(); withTimeout(5_000) { vm.coreTabsReady.first { it } }
    }
    @Test fun shortQuietGatesUseCompletionOfEachPreviousStage() = runBlocking<Unit> {
        history(); val vm = model(); stopClock()
        idle(749); assertEquals(0, historyReads.get()); idle(1); recent(vm)
        assertTrue(vm.hasRecentlyPlayed.value); assertEquals(0, profiles.get()); assertEquals(0, artistReads.get())
        idle(749); assertEquals(0, profiles.get()); idle(1); profile(vm); assertEquals(0, artistReads.get())
        idle(749); assertEquals(0, artistReads.get()); idle(1); artists(vm)
        idle(749); assertEquals(3, vm.revealedShelfCount.value); idle(1)
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
    }
    @Test fun touchingRestartsOnlyPendingIdleAndNeverRevokesCompletedStages() = runBlocking<Unit> {
        history(); val vm = model(); stopClock(); idle(500); BackgroundWorkCoordinator.touch(true); idle(10_000)
        assertEquals(0, historyReads.get()); BackgroundWorkCoordinator.touch(false)
        idle(749); assertEquals(0, historyReads.get()); idle(1); recent(vm)
        idle(); profile(vm); idle(); artists(vm)
        repeat(3) { BackgroundWorkCoordinator.touch(true); delay(50); BackgroundWorkCoordinator.touch(false); delay(50) }
        assertTrue(vm.optionalWorkReady.value); assertTrue(vm.coreTabsReady.value); assertEquals(1, profiles.get()); assertEquals(1, cores.get())
    }
    @Test fun pendingProfileTransportBlocksLaterStagesUntilItsResultSettles() = runBlocking<Unit> {
        history(); val release = CompletableDeferred<Unit>(); val vm = model(profile = release); stopClock()
        idle(); recent(vm); idle(); withTimeout(5_000) { while (profiles.get() == 0) delay(10) }
        idle(60_000); assertEquals(0, artistReads.get()); assertEquals(3, vm.revealedShelfCount.value)
        release.complete(Unit); profile(vm); idle(749); assertEquals(0, artistReads.get()); idle(1); artists(vm)
    }
    @Test fun failedProfileStillSettlesBeforeArtistAndNativeAdmissions() = runBlocking<Unit> {
        history(); val vm = model(failProfile = true); stopClock(); idle(); recent(vm); idle()
        withTimeout(5_000) { vm.profileStatus.first { it == com.example.ui.viewmodel.ShelfLoadStatus.ERROR } }
        settled(vm, "initialProfileSettledAt")
        idle(); artists(vm); idle(750); withTimeout(5_000) { vm.revealedShelfCount.first { it > 3 } }
        assertEquals(1, profiles.get())
    }
    @Test fun offlineTerminalHomeDoesNotDeadlockCoreOrDeferredLocalData() = runBlocking<Unit> {
        history(); val vm = model(failHome = true); stopClock(); assertTrue(vm.coreTabsReady.value)
        assertTrue(titles(vm).isEmpty()); idle(); recent(vm); assertTrue(vm.hasRecentlyPlayed.value)
        assertTrue(primary.get() <= 1); assertTrue(supplements.get() <= 2)
    }
    @Test fun newUsersSettleEmptyLocalCardsAndContinueWithoutInventingListeningData() = runBlocking<Unit> {
        val vm = model(); stopClock(); throughArtists(vm)
        assertFalse(vm.hasRecentlyPlayed.value); assertFalse(vm.hasListenedArtists.value); assertNotNull(vm.profileShelf.value)
        idle(750); withTimeout(5_000) { vm.revealedShelfCount.first { it == 4 } }
    }
    @Test fun completedStagesSurviveTabSwitchesWithoutAnotherCorePreparation() = runBlocking<Unit> {
        history(); val vm = model(); stopClock(); throughArtists(vm)
        vm.setHomeVisible(false); delay(100); idle(30_000); vm.setHomeVisible(true); delay(300)
        assertTrue(vm.coreTabsReady.value); assertTrue(vm.optionalWorkReady.value); assertEquals(1, cores.get()); assertEquals(1, profiles.get())
    }
    @Test fun backgroundTimeDoesNotCountTowardThePendingIdleWindow() = runBlocking<Unit> {
        val vm = model(); stopClock(); idle(500); BackgroundWorkCoordinator.foreground(false); idle(60_000)
        assertEquals(0, historyReads.get()); BackgroundWorkCoordinator.foreground(true); delay(100)
        vm.onHomeFrameDrawn()
        idle(749); assertEquals(0, historyReads.get()); idle(1); recent(vm)
    }
    @Test fun warmOpeningKeepsCachedRecentCardsWhileRoomWaitsForTheActualDraw() = runBlocking<Unit> {
        history(); cache(); val vm = model(); stopClock(); idle(); recent(vm)
        assertTrue(vm.hasRecentlyPlayed.value)
        val previousFrame = vm.initialHomePreparedAt.value
        BackgroundWorkCoordinator.foreground(false); vm.setHomeVisible(false)
        assertTrue(vm.hasRecentlyPlayed.value)
        delay(100)
        val readsBefore = historyReads.get()
        idle(60_000)
        BackgroundWorkCoordinator.foreground(true); vm.setHomeVisible(true)
        withTimeout(5_000) { vm.initialHomePreparedAt.first { it != previousFrame } }
        // Time before the retained Home actually draws cannot earn idle credit.
        idle(60_000)
        assertTrue(vm.hasRecentlyPlayed.value); assertEquals(readsBefore, historyReads.get())
        assertTrue("Cached data remains usable before live Room admission", vm.listeningHistoryCard.value.tracks.isNotEmpty())
        vm.onHomeFrameDrawn()
        idle(749); assertTrue(vm.hasRecentlyPlayed.value); assertEquals(readsBefore, historyReads.get())
        idle(1); recent(vm)
        withTimeout(5_000) { vm.hasRecentlyPlayed.first { it } }
        assertTrue(historyReads.get() > readsBefore); assertEquals(1, cores.get())
        assertEquals(0, profiles.get()); assertEquals(0, artistReads.get())
    }
    @Test fun manualNativeRefreshDoesNotWaitForTheDeferredHomeStages() = runBlocking<Unit> {
        cache(); val vm = model(); stopClock(); withTimeout(5_000) { vm.refreshHome(true).join() }
        assertTrue(primary.get() > 0); assertTrue(supplements.get() >= 2); assertEquals(0, historyReads.get())
        assertEquals(0, profiles.get()); assertTrue(vm.serverCards.value.count { it.shelf != null } >= 3)
    }
    @Test fun cachedArtistsAreNotOverwrittenByTheEarlierRecentHistoryStage() = runBlocking<Unit> {
        history(); cache(artists = true); val vm = model(); stopClock(); idle(); recent(vm)
        assertEquals("Artist 1", HomeCardCache(app).readArtists("US|anonymous")!!.artists.single().name)
        assertTrue(vm.artistPortraitsReady.value); assertEquals("Artist 1", vm.mostListenedArtists.value.single().name)
        assertEquals("Restoring a cached portrait must not aggregate listening history", 0, artistReads.get())
    }
    @Test fun cachedHiddenNativeShelvesWaitForAllThreeLocalStagesThenRevealOneAtATime() = runBlocking<Unit> {
        cache(); val vm = model(); stopClock(); throughArtists(vm)
        assertEquals(3, vm.serverShelves.value.size); idle(750)
        revealed(vm, 4)
        settled(vm, "nativeStageReadyAt")
        assertTrue(vm.serverShelves.value.size >= 7); idle(750)
        revealed(vm, 5)
    }
    @Test fun returningToFreshHomeReusesThePriorityCacheWithoutNetwork() = runBlocking<Unit> {
        cache(); val vm = model(); stopClock(); vm.setHomeVisible(false); delay(100); vm.setHomeVisible(true); delay(300)
        assertEquals(0, primary.get()); assertEquals(0, supplements.get()); assertEquals(1, cores.get())
    }
    @Test fun completeStalePriorityCacheWaitsForTheRemainingNativeStageBeforeRefreshing() = runBlocking<Unit> {
        HomeCardCache(app).saveHome(SavedHomeCards("US|anonymous", System.currentTimeMillis() - 20 * 60_000L,
            serverShelves = native, serverFetchSucceeded = true))
        val vm = model(); stopClock(); assertEquals(0, primary.get())
        throughArtists(vm); assertEquals(0, primary.get()); idle(750)
        withTimeout(5_000) { while (primary.get() == 0) delay(10) }
        withTimeout(5_000) { while ((HomeCardCache(app).readServer("US|anonymous")?.serverShelves?.size ?: 0) < 7) delay(10) }
    }
    @Test fun noAdditionalContinuationRunsDuringInitialOrLocalPreparation() = runBlocking<Unit> {
        val vm = model(); stopClock(); throughArtists(vm)
        assertEquals(1, primary.get()); assertEquals(0, supplements.get()); assertEquals(3, vm.revealedShelfCount.value)
    }
    @Test fun clearingHomeCancelsUnadmittedProfileWaitersAndReleasesCoreOwnership() = runBlocking<Unit> {
        cache(); val vm = model(draw = false)
        val pending = async { vm.loadProfileShelf() }
        yield(); stores.last().clear()
        withTimeout(5_000) { pending.join() }
        assertTrue(pending.isCancelled); assertEquals(0, profiles.get())
    }
    @Test fun mostListenedArtistsRemainsADeferredFifteenArtistLifetimeProjection() = runBlocking<Unit> {
        for(n in 1..18) { db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(song(n))); db.saveArtistTotal(song(n)) }
        val vm = model(); stopClock(); assertEquals(0, artistReads.get()); throughArtists(vm)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.size == 15 } }
        assertEquals(15, vm.mostListenedArtists.value.size)
    }
}
