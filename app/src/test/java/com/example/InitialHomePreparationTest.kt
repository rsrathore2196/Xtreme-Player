package com.example

import android.app.Application
import androidx.lifecycle.*
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.*
import com.example.data.local.*
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
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InitialHomePreparationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: MusicDatabase
    private lateinit var player: PlaybackManager
    private val stores = mutableListOf<ViewModelStore>()
    private var clockPump: Job? = null
    private var elapsed = 100_000L
    private val preferences = UserProfile(name = "User", countryCode = "IN", country = "India", languages = listOf("Hindi"))
    private val track = MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "", "")
    private fun shelf(id: String) = ServerHomeShelf(id, id, tracks = listOf(track.copy(id = "yt_$id")))
    private fun page(id: String, cursor: String? = null) = YouTubeHomeParser.ParsedHomeFeed(listOf(shelf(id)), cursor)
    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        com.example.performance.BackgroundWorkCoordinator.foreground(true)
        com.example.performance.BackgroundWorkCoordinator.firstFrameRendered()
        com.example.performance.BackgroundWorkCoordinator.scrolling(false)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        // Functional IO tests need the monotonic Android clock to advance just
        // as it does on a device; dedicated idle-policy tests control it exactly.
        clockPump = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(25))
                delay(25)
            }
        }
        UserProfilePreferences.saveUserProfile(app, preferences)
        db = MusicDatabase.createInMemoryDatabase(app)
        player = PlaybackManager(app, MusicRepository(db.musicDao(), app))
    }
    @After fun stop() = runBlocking { stores.forEach { it.clear() }; player.release(); db.close(); clockPump?.cancelAndJoin(); Dispatchers.resetMain(); com.example.performance.BackgroundWorkCoordinator.foreground(false) }
    private suspend fun viewModel(loader: suspend (String, String?) -> YouTubeHomeParser.ParsedHomeFeed,
        profile: suspend (UserProfile, Int) -> YouTubeMusicApiService.ProfileShelfResult = { _, _ -> YouTubeMusicApiService.ProfileShelfResult(null) },
        account: kotlinx.coroutines.flow.StateFlow<String>? = null,
        authenticatedRequests: MutableList<Boolean>? = null,
        idle: suspend (Long) -> Unit = {},
        extra: suspend (String) -> YouTubeHomeParser.ParsedHomeFeed = { YouTubeHomeParser.ParsedHomeFeed(emptyList(), null) },
        extraPage: suspend (String, String) -> YouTubeHomeParser.ParsedHomeFeed = { _, _ -> error("Unexpected supplemental cursor") },
        connectivity: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.emptyFlow()): HomeViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(MusicRepository(db.musicDao(), app), player, app, db,
                    { region, token, authenticated -> authenticatedRequests?.add(authenticated); loader(region, token) },
                    { selected, rotation, _ -> profile(selected, rotation) },
                    accountIdentity = { account?.value ?: YouTubeMusicApiService.discoveryAccountIdentity() },
                    accountUpdates = account ?: YouTubeAudioStreamService.discoveryAccountChanges,
                    idleAdmission = idle, elapsedTime = { elapsed }, unlockIdleAdmission = { awaitCancellation() },
                    supplementalLoader = { _, browse, _ -> extra(browse) },
                    supplementContinuationLoader = { _, browse, token, _ -> extraPage(browse, token) }, connectivityUpdates = connectivity) as T
        }
        return ViewModelProvider(store, factory)[HomeViewModel::class.java].also { vm ->
            // This suite tests transport after startup admission, not startup timing.
            (vm.javaClass.getDeclaredField("corePreparationLease").apply { isAccessible = true }.get(vm) as AutoCloseable).close()
            vm.javaClass.getDeclaredField("corePreparationLease").apply { isAccessible = true }.set(vm, null)
            (vm.javaClass.getDeclaredField("restored").apply { isAccessible = true }.get(vm) as CompletableDeferred<*>).await()
            @Suppress("UNCHECKED_CAST")
            val access = vm.javaClass.getDeclaredField("_additionalShelvesUnlocked").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
            access.value = true // These tests exercise transport after admission; idle timing has dedicated tests.
            @Suppress("UNCHECKED_CAST")
            val qualification = vm.javaClass.getDeclaredField("nativeStageReadyAt").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Long?>
            qualification.value = elapsed
            if (vm.javaClass.getDeclaredField("deferredServerCache").apply { isAccessible = true }.getBoolean(vm))
                withContext(com.example.performance.AppDispatchers.HomeBookkeeping) { vm.restoreRemainingNativeCache(urgent = true) }
            @Suppress("UNCHECKED_CAST")
            val coreReady = vm.javaClass.getDeclaredField("_coreTabsReady").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
            coreReady.value = true
            vm.javaClass.getDeclaredField("coreSettledAt").apply { isAccessible = true }.set(vm, elapsed)
            @Suppress("UNCHECKED_CAST")
            val initialReady = vm.javaClass.getDeclaredField("initialNativeReadyAt").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Long?>
            initialReady.value = elapsed
            vm.onHomeFrameDrawn()
            vm.viewModelScope.launch { initialReady.collect { if (it != null) vm.onHomeFrameDrawn(it) } }
        }
    }
    private suspend fun reveal(vm: HomeViewModel) = withContext(com.example.performance.AppDispatchers.HomeBookkeeping) {
        if (vm.continuationStatus.value != ShelfLoadStatus.LOADING) {
            (vm.javaClass.getDeclaredField("nativePageJob").apply { isAccessible = true }.get(vm) as? Job)?.join()
        }
        vm.javaClass.getDeclaredField("nativeAdmissionInput").apply { isAccessible = true }
            .set(vm, com.example.performance.BackgroundWorkCoordinator.interactions.value)
        vm.revealAdmittedNativeShelf()
        // Transport fixtures model an already admitted request. Automatic idle
        // publication and interruption are covered by AdaptiveHomeLoadingTest.
        vm.javaClass.getDeclaredField("automaticRevealPending").apply { isAccessible = true }.setBoolean(vm, false)
    }
    private suspend fun cached() {
        HomeCardCache(app).save(SavedHomeCards(HomeCacheIdentity.home(preferences), refreshedAt = System.currentTimeMillis(),
            serverShelves = listOf(shelf("cached")), serverFetchSucceeded = true))
    }
    @Test fun requestStatusAndCompletionReuseTheUnchangedNativeProjection() = runBlocking<Unit> {
        fun projections() = org.robolectric.shadows.ShadowLog.getLogsForTag("NativeHome").count { it.msg.startsWith("project shelves=") }
        val before = projections()
        val calls = AtomicInteger()
        val vm = viewModel({ _, token ->
            assertNull(token); calls.incrementAndGet()
            YouTubeHomeParser.ParsedHomeFeed((1..4).map { shelf("native-$it") }, null)
        })
        vm.setHomeVisible(true)
        withTimeout(5_000) {
            vm.serverShelves.first { it.size == 4 }
            vm.refreshHome(false).join()
            @Suppress("UNCHECKED_CAST")
            val jobs = vm.javaClass.getDeclaredField("sourceJobs").apply { isAccessible = true }.get(vm) as Map<String, Job>
            while (jobs.values.any { it.isActive }) delay(10)
        }
        assertEquals(1, calls.get())
        assertEquals("Loading/finally publications must reuse the prepared feed", 1, projections() - before)
        assertEquals(3, vm.revealedShelfCount.value)
    }
    @Test fun returningUserAndLanguageOnlyColdStartupReuseFreshHome() = runBlocking {
        cached(); val calls = AtomicInteger()
        val vm = viewModel({ _, _ -> calls.incrementAndGet(); page("new") })
        withTimeout(5_000) { vm.serverShelves.first { it.isNotEmpty() } }
        assertTrue(vm.loadServerHome()); assertEquals(0, calls.get())
        vm.updateUserProfile(preferences.copy(languages = listOf("Punjabi")))
        withTimeout(5_000) { vm.userProfile.first { it.languages == listOf("Punjabi") } }
        assertTrue(vm.loadServerHome()); assertEquals(0, calls.get())
        val restarted = viewModel({ _, _ -> calls.incrementAndGet(); page("wrong") })
        withTimeout(5_000) { restarted.serverShelves.first { it.isNotEmpty() } }
        assertTrue(restarted.loadServerHome()); assertEquals(0, calls.get())
    }
    @Test fun failedExplicitRefreshKeepsCachedContentAndCompletesTheIndicator() = runBlocking {
        cached(); val vm = viewModel({ _, _ -> YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, isError = true) })
        withTimeout(5_000) { vm.serverShelves.first { it.isNotEmpty() } }
        val before = vm.serverShelves.value
        withTimeout(5_000) { vm.refreshHome(true).join() }
        assertEquals(before, vm.serverShelves.value); assertFalse(vm.isRefreshing.value)
        assertNotNull(vm.refreshErrorMessage.value)
    }
    @Test fun explicitRefreshBypassesFreshHomeEvenWhileAutomaticProfileLoadingIsRunning() = runBlocking {
        cached()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(); val profileCalls = AtomicInteger()
        val vm = viewModel({ _, _ -> calls.incrementAndGet(); page("refreshed") }, { _, _ ->
            profileCalls.incrementAndGet(); started.complete(Unit); release.await(); YouTubeMusicApiService.ProfileShelfResult(null)
        })
        vm.setHomeVisible(true)
        val automatic = vm.refreshHome(false)
        withTimeout(5_000) { started.await() }
        assertEquals(0, calls.get())
        val manual = vm.refreshHome(true)
        withTimeout(5_000) { vm.serverShelves.first { it.firstOrNull()?.id == "refreshed" } }
        withTimeout(5_000) { manual.join() }
        assertFalse(vm.isRefreshing.value)
        release.complete(Unit)
        withTimeout(5_000) { manual.join(); automatic.join() }
        assertEquals(1, calls.get()); assertEquals(1, profileCalls.get()); assertFalse(vm.isRefreshing.value)
        assertEquals(listOf("refreshed"), vm.serverShelves.value.map { it.id })
    }
    @Test fun manualRefreshReusesBothActualRequestsWhenPromotingAnAutomaticRefresh() = runBlocking {
        // A displayed stale native window admits profile work while the automatic refresh runs.
        HomeCardCache(app).save(SavedHomeCards(HomeCacheIdentity.home(preferences), refreshedAt = 0,
            serverShelves = listOf(shelf("native"), shelf("stale-two"), shelf("stale-three")), serverFetchSucceeded = true))
        val serverStarted = CompletableDeferred<Unit>(); val profileStarted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(); val profileCalls = AtomicInteger()
        val vm = viewModel({ _, _ -> calls.incrementAndGet(); serverStarted.complete(Unit); release.await(); page("native") }, { _, _ ->
            profileCalls.incrementAndGet(); profileStarted.complete(Unit); release.await(); YouTubeMusicApiService.ProfileShelfResult(null)
        })
        vm.setHomeVisible(true)
        val automatic = vm.refreshHome(false)
        withTimeout(5_000) { serverStarted.await(); profileStarted.await() }
        val manual = vm.refreshHome(true)
        withTimeout(5_000) { vm.isRefreshing.first { it } }
        release.complete(Unit)
        withTimeout(5_000) { manual.join(); automatic.join() }
        assertEquals(1, calls.get()); assertEquals(1, profileCalls.get())
        assertEquals("native", vm.serverShelves.value.single().id); assertFalse(vm.isRefreshing.value)
    }
    @Test fun refreshCompletesWithHomeWhileOptionalProfileDiscoveryRemainsIndependent() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val vm = viewModel({ _, _ -> page("native") }, { _, _ -> started.complete(Unit); release.await(); YouTubeMusicApiService.ProfileShelfResult(null) })
        vm.setHomeVisible(true)
        val refresh = vm.refreshHome(true)
        withTimeout(5_000) { started.await() }
        withTimeout(5_000) { refresh.join() }
        assertFalse(vm.isRefreshing.value)
        assertEquals("native", vm.serverShelves.value.single().id)
        assertFalse(release.isCompleted)
        release.complete(Unit)
        Unit
    }
    @Test fun failedContinuationRemainsRetryableAndSuccessfulAppendPersists() = runBlocking {
        val requests = AtomicInteger()
        val vm = viewModel({ _, token -> if (token == null) page("first", "cursor") else {
            if (requests.incrementAndGet() == 1) YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, isError = true) else page("next")
        } })
        assertTrue(vm.loadServerHome(true))
        reveal(vm)
        withTimeout(5_000) { while (requests.get() < 1) delay(10) }; delay(50)
        reveal(vm); delay(100)
        assertEquals("Repeated gestures respect failed-token backoff", 1, requests.get())
        elapsed += 15_001
        reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 2 } }
        assertEquals(2, requests.get()); assertEquals(listOf("first", "next"), vm.serverShelves.value.map { it.title })
        withTimeout(5_000) { while (HomeCardCache(app).read(HomeCacheIdentity.home(preferences))?.serverShelves?.size != 2) delay(10) }
        assertNull(HomeCardCache(app).read(HomeCacheIdentity.home(preferences))!!.continuationToken)
    }
    @Test fun lateContinuationCannotEnterANewRegionalFeed() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val vm = viewModel({ country, token -> if (token == null) page(country, "cursor") else {
            started.complete(Unit); withContext(NonCancellable) { release.await() }; page("stale")
        } })
        vm.loadServerHome(true); reveal(vm); withTimeout(5_000) { started.await() }
        vm.updateUserProfile(preferences.copy(countryCode = "GB", country = "United Kingdom"))
        withTimeout(5_000) { vm.userProfile.first { it.countryCode == "GB" } }
        vm.loadServerHome(true); release.complete(Unit); delay(100)
        assertEquals(listOf("GB"), vm.serverShelves.value.map { it.title })
    }
    @Test fun aPageWithUnsupportedCollectionsStillRetainsItsCursorToPlayableShelves() = runBlocking {
        val vm = viewModel({ _, token -> if (token == null) YouTubeHomeParser.ParsedHomeFeed(emptyList(), "cursor")
            else YouTubeHomeParser.ParsedHomeFeed((1..6).map { shelf("server-$it") }, null) })
        assertTrue(vm.loadServerHome(true))
        reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 6 } }
        reveal(vm)
        withTimeout(5_000) { vm.revealedShelfCount.first { it >= 4 } }
        assertEquals((1..6).map { "server-$it" }, vm.serverShelves.value.map { it.title })
    }
    @Test fun artistAggregationWaitsForItsSectionWithoutBlockingNativeHome() = runBlocking {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
        db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
            eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        db.saveArtistTotal(track)
        val releaseIdle = CompletableDeferred<Unit>()
        val vm = viewModel({ _, _ -> page("native") }, idle = { releaseIdle.await() })
        assertTrue(vm.loadServerHome())
        assertEquals(com.example.recommendation.HomeUserMode.LOADING, vm.userMode.value)
        vm.setHomeVisible(true)
        assertEquals("Historical reads now wait for the first idle stage", com.example.recommendation.HomeUserMode.LOADING, vm.userMode.value)
        assertTrue("Room artist aggregation is not a startup prerequisite", vm.mostListenedArtists.value.isEmpty())
        releaseIdle.complete(Unit)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.isNotEmpty() } }
        assertEquals("Singer", vm.mostListenedArtists.value.single().name)
        assertEquals(1, vm.mostListenedArtists.value.single().playCount)
        assertTrue(vm.hasListenedArtists.value)
    }
    @Test fun artistsRefreshAfterNewPlaysOldHistoryRestorationAndDeletion() = runBlocking {
        val older = track.copy(id = "old-track", artist = "Older Artist")
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(older))
        val dao = db.listeningEventDao()
        repeat(301) { index -> dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
            timestamp = 1_000L + index, sessionId = "recent-$index", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000)) }
        db.saveArtistTotal(track, 301 * 90_000L, 301)
        val vm = viewModel({ _, _ -> page("native") })
        vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.firstOrNull()?.playCount == 301 } }
        // Restoration can affect an artist outside the observable 300-row recent window.
        dao.insertListeningEvent(ListeningEventEntity(trackId = older.id, artistName = older.artist, timestamp = 1,
            sessionId = "restored", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        db.saveArtistTotal(older)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.any { artist -> artist.name == "Older Artist" && artist.playCount == 1 } } }
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, timestamp = 2_000,
            sessionId = "new-play", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        db.saveArtistTotal(track, 302 * 90_000L, 302)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.firstOrNull()?.playCount == 302 } }
        dao.pruneOldEvents(Long.MAX_VALUE)
        assertEquals("Stats retain lifetime rankings when raw history is pruned", 302, vm.mostListenedArtists.value.first().playCount)
        db.withTransaction { db.openHelper.writableDatabase.execSQL("DELETE FROM listening_totals") }
        withTimeout(5_000) { vm.mostListenedArtists.first { it.isEmpty() } }
        assertFalse(vm.hasListenedArtists.value)
    }
    @Test fun databaseFailureCannotEraseAValidCachedArtistRanking() = runBlocking {
        HomeCardCache(app).saveLocal(SavedHomeCards(HomeCacheIdentity.home(preferences),
            artists = listOf(MostListenedArtist("Singer", 250, listOf(track))), inputRevision = "known-valid"))
        db.openHelper.writableDatabase.execSQL("DROP TABLE listening_totals")
        val vm = viewModel({ _, _ -> page("native") }); vm.setHomeVisible(true)
        withTimeout(5_000) { vm.artistStatus.first { it == ShelfLoadStatus.ERROR } }
        assertEquals(250, vm.mostListenedArtists.value.single().playCount)
    }
    @Test fun accountSwitchCannotPublishAnOldAccountsResponseOrRetainItsCards() = runBlocking {
        val account = kotlinx.coroutines.flow.MutableStateFlow("account-a")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        HomeCardCache(app).saveHome(SavedHomeCards(HomeCacheIdentity.home(preferences, "account-a"),
            serverShelves = listOf(shelf("account-a"))))
        val vm = viewModel({ _, _ ->
            val identity = account.value
            if (identity == "account-a") { started.complete(Unit); withContext(NonCancellable) { release.await() } }
            page(identity)
        }, account = account)
        val old = async { vm.loadServerHome(true) }
        withTimeout(5_000) { started.await() }
        account.value = "account-b"
        withTimeout(5_000) { vm.serverShelves.first { it.isEmpty() } }
        vm.loadServerHome(true); release.complete(Unit)
        runCatching { old.await() }
        assertEquals(listOf("account-b"), vm.serverShelves.value.map { it.id })
        assertEquals(listOf("account-a"), HomeCardCache(app).read(HomeCacheIdentity.home(preferences, "account-a"))!!.serverShelves.map { it.id })
    }
    @Test fun anonymousFallbackIsCachedSeparatelyAndItsCursorUsesAnonymousDiscovery() = runBlocking {
        val account = kotlinx.coroutines.flow.MutableStateFlow("account-a")
        val authenticated = mutableListOf<Boolean>()
        val vm = viewModel({ _, token -> page(if (token == null) "anonymous-first" else "anonymous-next", if (token == null) "cursor" else null)
            .copy(accountIdentity = "anonymous") }, account = account, authenticatedRequests = authenticated)
        assertTrue(vm.loadServerHome(true))
        assertTrue(HomeCardCache(app).read(HomeCacheIdentity.home(preferences, "account-a"))?.serverShelves.isNullOrEmpty())
        assertEquals("anonymous-first", HomeCardCache(app).read(HomeCacheIdentity.home(preferences, "anonymous"))!!.serverShelves.single().id)
        reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 2 } }
        assertEquals(listOf(true, false), authenticated)
    }
    @Test fun switchingToAFreshRegionalAndLanguageCacheNeverForcesRequests() = runBlocking {
        val country = preferences.copy(country = "United Kingdom", countryCode = "GB")
        val language = country.copy(languages = listOf("Punjabi"))
        val cache = HomeCardCache(app)
        for (selected in listOf(preferences, country, language)) cache.save(SavedHomeCards(HomeCacheIdentity.home(selected),
            refreshedAt = System.currentTimeMillis(), serverShelves = listOf(
                shelf(selected.countryCode).copy(title = "Quick Picks"),
                shelf("albums").copy(title = "Albums & Singles"),
                shelf("playlists").copy(title = "Trending Community Playlists")), serverFetchSucceeded = true,
            profileShelfKey = HomeCacheIdentity.profile(selected), profileFetchedAt = System.currentTimeMillis(), profileFetchSucceeded = true,
            profileShelf = shelf(selected.languages.single())))
        val homeCalls = AtomicInteger(); val profileCalls = AtomicInteger()
        val vm = viewModel({ _, _ -> homeCalls.incrementAndGet(); page("wrong") }, { _, _ ->
            profileCalls.incrementAndGet(); YouTubeMusicApiService.ProfileShelfResult(shelf("wrong"))
        })
        vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        withTimeout(5_000) { vm.profileShelf.first { it?.id == "Hindi" } }
        vm.updateUserProfile(country)
        withTimeout(5_000) { vm.serverShelves.first { it.firstOrNull()?.id == "GB" }; vm.profileShelf.first { it?.id == "Hindi" } }
        vm.updateUserProfile(language)
        withTimeout(5_000) { vm.profileShelf.first { it?.id == "Punjabi" } }
        assertTrue(vm.loadServerHome()); assertTrue(vm.loadProfileShelf())
        assertEquals("Matching fresh regional Home must not refetch", 0, homeCalls.get())
        assertEquals("Matching fresh language/profile shelf must not refetch", 0, profileCalls.get())
    }
    @Test fun aSuccessfulEmptyRefreshClearsOldShelvesAndDoesNotRefetchOnTabReturn() = runBlocking {
        cached(); val calls = AtomicInteger()
        val vm = viewModel({ _, _ -> calls.incrementAndGet(); YouTubeHomeParser.ParsedHomeFeed(emptyList(), null) })
        assertTrue(vm.loadServerHome(true)); assertTrue(vm.serverShelves.value.isEmpty())
        assertTrue(vm.loadServerHome()); assertEquals(1, calls.get())
    }
    @Test fun hidingHomeCancelsOnlyOptionalProfileWorkAndPreventsItsLatePublication() = runBlocking {
        val started = CompletableDeferred<Unit>(); val canceled = CompletableDeferred<Unit>()
        val vm = viewModel({ _, _ -> page("native") }, { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { canceled.complete(Unit) }
        })
        vm.setHomeVisible(true); vm.setArtistSectionRelevant(true); withTimeout(5_000) { started.await() }
        vm.setHomeVisible(false); withTimeout(5_000) { canceled.await() }
        // Home and optional profile requests finish independently.
        withTimeout(5_000) { vm.serverShelves.first { it.singleOrNull()?.id == "native" } }
        assertNull(vm.profileShelf.value); assertEquals("native", vm.serverShelves.value.single().id)
    }
    @Test fun hiddenHomeRetainsLocalCardsAndCatchesUpFromActualRecordsWhenShownAgain() = runBlocking {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
        val dao = db.listeningEventDao()
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
            sessionId = "first", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        db.saveArtistTotal(track)
        val vm = viewModel({ _, _ -> page("native") })
        vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        @Suppress("UNCHECKED_CAST")
        val shown = vm.javaClass.getDeclaredField("homeVisible").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.StateFlow<Boolean>
        withTimeout(5_000) { shown.first { it }; vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 1 } }
        vm.setHomeVisible(false); withTimeout(5_000) { shown.first { !it } }
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
            sessionId = "second", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        db.saveArtistTotal(track, 180_000, 2)
        assertEquals(2, dao.getActualTrackPlayCounts().single().playCount)
        assertNull(withTimeoutOrNull(200) { vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 2 } })
        vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 2 } }
        assertEquals(1, vm.pillsCardTracks.value.size)
    }
    @Test fun aNewOccurrenceReusesRecentTrackMetadataButArtworkAndFavoritesRemainFresh() = runBlocking {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
        val dao = db.listeningEventDao()
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, timestamp = 100,
            sessionId = "one", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        db.saveArtistTotal(track)
        val vm = viewModel({ _, _ -> page("native") }); vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 1 } }
        val field = vm.javaClass.getDeclaredField("recentMetadata").apply { isAccessible = true }
        val before = field.get(vm)
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, timestamp = 200,
            sessionId = "two", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        db.saveArtistTotal(track, 180_000, 2)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 2 } }
        assertSame("Another occurrence needs no repeated recent metadata read", before, field.get(vm))
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track.copy(coverUrl = "new artwork", isLiked = true)))
        withTimeout(5_000) { vm.pillsCardTracks.first { it.singleOrNull()?.let { row -> row.coverUrl == "new artwork" && row.isLiked } == true } }
        assertNotSame(before, field.get(vm))
        withTimeout(5_000) { vm.mostListenedArtists.first { it.singleOrNull()?.tracks?.singleOrNull()?.coverUrl == "new artwork" } }
        assertEquals(2, vm.mostListenedArtists.value.single().playCount)
    }
    @Test fun actualAudioCanEnterRecentlyPlayedWithoutChangingItsTrackMetadata() = runBlocking {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
        val dao = db.listeningEventDao()
        val event = ListeningEventEntity(id = 1, trackId = track.id, artistName = track.artist, timestamp = 100,
            sessionId = "one", eventType = "ACTUAL_PLAY", playedDurationMs = 0)
        dao.insertListeningEvent(event)
        val vm = viewModel({ _, _ -> page("native") }); vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        withTimeout(5_000) { vm.userMode.first { it != com.example.recommendation.HomeUserMode.LOADING } }
        assertTrue(vm.pillsCardTracks.value.isEmpty())
        dao.insertListeningEvent(event.copy(playedDurationMs = 1))
        withTimeout(5_000) { vm.pillsCardTracks.first { it.singleOrNull()?.id == track.id } }
        Unit
    }

    @Test fun replayingAnExistingRecentSongReordersHistoryWithoutRefetchingUnchangedMetadata() = runBlocking {
        val other = track.copy(id = "yt_12345678901", title = "Other Song")
        val dao = db.listeningEventDao()
        for ((index, value) in listOf(track, other).withIndex()) {
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(value))
            dao.insertListeningEvent(ListeningEventEntity(trackId = value.id, artistName = value.artist, timestamp = (100 + index).toLong(),
                sessionId = "$index", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        }
        db.saveArtistTotal(track, 180_000, 2)
        val vm = viewModel({ _, _ -> page("native") }); vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        withTimeout(5_000) { vm.pillsCardTracks.first { it.size == 2 }; vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 2 } }
        val field = vm.javaClass.getDeclaredField("recentMetadata").apply { isAccessible = true }
        val before = field.get(vm)
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, timestamp = 200,
            sessionId = "replay", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        db.saveArtistTotal(track, 270_000, 3)
        withTimeout(5_000) { vm.pillsCardTracks.first { it.firstOrNull()?.id == track.id }; vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 3 } }
        assertSame(before, field.get(vm))
        assertEquals(listOf(track.id, other.id), vm.pillsCardTracks.value.map { it.id })
    }

    @Test fun theFirstResponsePublishesAllSupportedShelfKindsWithoutProfileFanout() = runBlocking {
        val calls = AtomicInteger(); val profileCalls = AtomicInteger()
        val response = YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(NativeFeedFixtures.home(
            NativeFeedFixtures.collectionShelf("Albums & Singles", NativeFeedFixtures.album(1)),
            NativeFeedFixtures.shelf("Quick Picks", 2),
            NativeFeedFixtures.collectionShelf("Trending Playlists", NativeFeedFixtures.playlist(3)))))
        val vm = viewModel({ _, token -> assertNull(token); calls.incrementAndGet(); response }, { _, _ ->
            profileCalls.incrementAndGet(); error("Optional profile discovery must not block initial Home")
        })
        withTimeout(5_000) { vm.loadServerHome() }
        assertEquals(listOf("Albums & Singles", "Quick Picks", "Trending Playlists"), vm.serverShelves.value.map { it.title })
        assertTrue(vm.revealedShelfCount.value >= 3)
        assertEquals(1, calls.get()); assertEquals(0, profileCalls.get())
        vm.loadServerHome(); assertEquals(1, calls.get())
    }
    @Test fun aShortInitialFeedPublishesBeforeAnyContinuationAndCachesItsCursor() = runBlocking {
        val tokens = mutableListOf<String?>()
        val vm = viewModel({ _, token -> tokens += token
            if (token == null) page("quick", "albums") else page("albums", "later")
        })
        assertTrue(vm.loadServerHome())
        assertEquals(listOf<String?>(null), tokens)
        assertEquals(listOf("quick"), vm.serverShelves.value.map { it.id })
        assertEquals(ShelfLoadStatus.SUCCESS, vm.serverCards.value.single().status)
        val saved = HomeCardCache(app).read(HomeCacheIdentity.home(preferences))!!
        assertEquals(listOf("quick"), saved.serverShelves.map { it.id }); assertEquals("albums", saved.continuationToken)
        reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 2 } }
        assertEquals(listOf(null, "albums"), tokens)
    }
    @Test fun aFreshThreeShelfCacheRestoresWithoutAnyHomeOrContinuationRequests() = runBlocking {
        HomeCardCache(app).saveHome(SavedHomeCards(HomeCacheIdentity.home(preferences), System.currentTimeMillis(),
            serverShelves = listOf(shelf("albums"), shelf("quick"), shelf("playlists")), serverFetchSucceeded = true))
        val vm = viewModel({ _, _ -> error("Fresh retained server cards require no network") })
        withTimeout(5_000) { vm.loadServerHome() }
        assertEquals(3, vm.serverShelves.value.size)
    }
    @Test fun visibleHomeFetchesFurtherPagesOnlyAfterDemandAndRevealsTheirShelves() = runBlocking {
        val tokens = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val vm = viewModel({ _, token -> tokens += token
            when (token) {
                null -> YouTubeHomeParser.ParsedHomeFeed((1..3).map { shelf("native-$it") }, "more")
                "more" -> YouTubeHomeParser.ParsedHomeFeed((4..6).map { shelf("native-$it") }, "last")
                else -> page("native-7")
            }
        })
        assertTrue(vm.loadServerHome()); vm.setHomeVisible(true)
        withTimeout(5_000) { vm.optionalWorkReady.first { it } }
        assertEquals(listOf<String?>(null), tokens)
        reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 6 } }
        assertEquals(listOf(null, "more"), tokens)
        assertEquals(4, vm.revealedShelfCount.value)
        reveal(vm)
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 5 } }
        reveal(vm)
        withTimeout(5_000) { vm.revealedShelfCount.first { it == 6 } }
        reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 7 } }
        assertEquals(listOf(null, "more", "last"), tokens)
        withTimeout(5_000) { while (HomeCardCache(app).read(HomeCacheIdentity.home(preferences))?.serverShelves?.size != 7) delay(10) }
        assertNull(HomeCardCache(app).read(HomeCacheIdentity.home(preferences))!!.continuationToken)
    }
    @Test fun explicitRefreshMergesExpandedCacheAndFetchesUniquePagesIndependentlyOfItsIndicator() = runBlocking {
        val key = HomeCacheIdentity.home(preferences)
        HomeCardCache(app).saveHome(SavedHomeCards(key, System.currentTimeMillis(),
            serverShelves = listOf(shelf("first"), shelf("retained")), serverFetchSucceeded = true))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val vm = viewModel({ _, token ->
            calls.incrementAndGet()
            if (token == null) YouTubeHomeParser.ParsedHomeFeed(listOf(shelf("first").copy(tracks = listOf(track))), "more")
            else { entered.complete(Unit); release.await()
                YouTubeHomeParser.ParsedHomeFeed(listOf(shelf("retained"), shelf("added")), "more") }
        })
        withTimeout(5_000) { vm.refreshHome(true).join(); entered.await() }
        assertFalse(vm.isRefreshing.value)
        assertEquals(listOf("first"), vm.serverShelves.value.map { it.id })
        assertEquals(listOf(track), vm.serverShelves.value.first().tracks)
        release.complete(Unit)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 3 } }
        assertEquals(listOf("first", "retained", "added"), vm.serverShelves.value.map { it.id })
        assertEquals(2, calls.get()) // A repeated cursor is exhausted, not fetched again.
        withTimeout(5_000) { while (HomeCardCache(app).read(key)?.serverShelves?.size != 3) delay(10) }
        val restarted = viewModel({ _, _ -> error("The expanded cache is fresh") })
        assertTrue(restarted.loadServerHome())
        // The cache codec marks restored catalog metadata as recommendation-only.
        assertEquals(vm.serverShelves.value.map { it.copy(tracks = it.tracks.map { song -> song.copy(recommendationOnly = true) }) },
            restarted.serverShelves.value)
    }
    @Test fun backgroundPageFailureStopsAndExplicitRefreshCanRetryWithoutLosingCachedShelves() = runBlocking {
        val attempts = AtomicInteger()
        val failed = CompletableDeferred<Unit>()
        val vm = viewModel({ _, token -> if (token == null) page("first", "more") else {
            if (attempts.incrementAndGet() == 1) { failed.complete(Unit); YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, isError = true) }
            else page("next")
        } })
        vm.refreshHome(true).join()
        withTimeout(5_000) { failed.await() }
        assertEquals(listOf("first"), vm.serverShelves.value.map { it.id })
        // Await the completed failed job before deliberately requesting a fresh generation.
        withTimeout(5_000) { while (HomeCardCache(app).read(HomeCacheIdentity.home(preferences))?.continuationToken != "more") delay(10) }
        vm.refreshHome(true).join()
        withTimeout(5_000) { vm.serverShelves.first { it.size == 2 } }
        assertEquals(2, attempts.get())
    }
    @Test fun hidingHomeCancelsOptionalExpansionAndReturningCanReuseItsUnconsumedCursor() = runBlocking {
        HomeCardCache(app).saveHome(SavedHomeCards(HomeCacheIdentity.home(preferences), System.currentTimeMillis(),
            serverShelves = (1..3).map { shelf("native-$it") }, continuationToken = "more", serverFetchSucceeded = true))
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val vm = viewModel({ _, token ->
            assertEquals("more", token)
            if (attempts.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            page("next")
        })
        vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        reveal(vm)
        withTimeout(5_000) { entered.await() }
        vm.setHomeVisible(false)
        withTimeout(5_000) { cancelled.await() }
        assertEquals(3, vm.serverShelves.value.size)
        vm.setHomeVisible(true); vm.setArtistSectionRelevant(true)
        reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 4 } }
        assertEquals(2, attempts.get())
    }
    @Test fun anEndlessServerCursorCannotCreateUnboundedBackgroundRequests() = runBlocking {
        val pages = AtomicInteger()
        val vm = viewModel({ _, token ->
            if (token == null) YouTubeHomeParser.ParsedHomeFeed((1..3).map { shelf("native-$it") }, "page-1")
            else pages.incrementAndGet().let { page("extra-$it", "page-${it + 1}") }
        })
        vm.refreshHome(true).join()
        withTimeout(5_000) { vm.serverShelves.first { it.size == 4 } }
        assertEquals("One explicit refresh does not eagerly walk hidden pages", 1, pages.get())
        assertTrue(vm.revealedShelfCount.value <= 4)
    }
    @Test fun cancellingOptionalContinuationKeepsTheCursorAndPublishedContentRetryable() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); var slow = true
        val vm = viewModel({ _, token -> if (token == null) page("quick", "next") else {
            if (slow) { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
            YouTubeHomeParser.ParsedHomeFeed(listOf(shelf("albums"), shelf("playlists")), null)
        } })
        assertTrue(vm.loadServerHome())
        vm.setHomeVisible(true)
        reveal(vm)
        withTimeout(5_000) { entered.await() }
        vm.setHomeVisible(false)
        withTimeout(5_000) { cancelled.await() }
        assertEquals(listOf("quick"), vm.serverShelves.value.map { it.id })
        assertEquals("next", HomeCardCache(app).read(HomeCacheIdentity.home(preferences))!!.continuationToken)
        slow = false; reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 3 } }
        Unit
    }
    @Test fun obsoleteSongOnlyCacheIsDisplayedThenRefreshedInsteadOfBeingConsideredCompleteForTenMinutes() = runBlocking {
        val key = HomeCacheIdentity.home(preferences)
        val row = org.json.JSONObject().put("version", 3).put("key", key).put("success", true)
            .put("refreshedAt", System.currentTimeMillis()).put("serverShelves", org.json.JSONArray().put(org.json.JSONObject()
                .put("id", "legacy").put("title", "Quick Picks").put("tracks", org.json.JSONArray(com.example.data.cache.RecommendationTrackCodec.encode(listOf(track))))))
        java.io.File(app.filesDir, "home_server_${HomeCacheIdentity.fileKey(key)}_v3.json").writeText(row.toString())
        val calls = AtomicInteger()
        val vm = viewModel({ _, _ -> calls.incrementAndGet(); YouTubeHomeParser.ParsedHomeFeed(listOf(shelf("albums"), shelf("quick"), shelf("playlists")), null) })
        withTimeout(5_000) { vm.serverShelves.first { it.singleOrNull()?.id == "legacy" } }
        vm.loadServerHome()
        assertEquals(1, calls.get()); assertEquals(3, vm.serverShelves.value.size)
    }

    @Test fun failedHomeContinuationCannotBlockIndependentSupplementsAndTheirCache() = runBlocking {
        val browsed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val vm = viewModel({ _, token -> if (token == null) page("Quick Picks", "broken")
            else YouTubeHomeParser.ParsedHomeFeed(emptyList(), "broken", isError = true) }, extra = { browse ->
            browsed += browse
            page(if (browse == "FEmusic_new_releases") "New Releases" else "Trending Playlists")
        })
        assertTrue(vm.loadServerHome())
        assertTrue(browsed.isEmpty())
        reveal(vm)
        vm.setHomeVisible(true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 3 } }
        assertEquals(setOf("FEmusic_new_releases", "FEmusic_explore"), browsed.toSet())
        assertEquals(listOf("Quick Picks", "New Releases", "Trending Playlists"), vm.serverShelves.value.map { it.title })
        withTimeout(5_000) { while (HomeCardCache(app).readServer(HomeCacheIdentity.home(preferences))?.browseSources?.count { it.succeeded } != 3) delay(10) }
        val restored = viewModel({ _, _ -> error("Fresh cached Home must not refetch") }, extra = { error("Fresh supplementary browse must not refetch") })
        assertTrue(restored.loadServerHome())
        assertEquals(3, restored.serverShelves.value.size)
        restored.setHomeVisible(true)
        delay(100)
        assertEquals(2, browsed.size)
    }
    @Test fun automaticSupplementsWaitForPrimaryButManualRefreshCanPromoteThemWithoutAccumulatingStaleSections() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val vm = viewModel({ _, _ ->
            if (calls.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            page("primary-${calls.get()}")
        }, extra = { browse -> page(browse) })
        vm.setHomeVisible(true)
        withTimeout(5_000) { entered.await() }
        assertTrue(vm.serverShelves.value.isEmpty())
        val manual = vm.refreshHome(true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 2 } }
        assertEquals(setOf("FEmusic_new_releases", "FEmusic_explore"), vm.serverShelves.value.map { it.id }.toSet())
        release.complete(Unit)
        withTimeout(5_000) { manual.join(); vm.serverShelves.first { it.size == 3 } }
        repeat(3) {
            withTimeout(5_000) { vm.refreshHome(true).join() }
            assertEquals(3, vm.serverShelves.value.size)
            assertEquals(1, vm.serverShelves.value.count { it.id.startsWith("primary-") })
        }
    }
    @Test fun duplicateOnlyPagesCanAdvanceOnceToTheNextUsableNativePage() = runBlocking {
        val calls = mutableListOf<String?>()
        val original = shelf("first")
        val vm = viewModel({ _, token -> calls += token
            when (token) {
                null -> YouTubeHomeParser.ParsedHomeFeed(listOf(original), "one")
                "one" -> YouTubeHomeParser.ParsedHomeFeed(listOf(original.copy(id = "changed")), "two")
                else -> page("New Releases")
            }
        })
        assertTrue(vm.loadServerHome()); reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 2 } }
        assertEquals(listOf(null, "one", "two"), calls)
        assertEquals(listOf("first", "New Releases"), vm.serverShelves.value.map { it.title })
        assertEquals(2, vm.revealedShelfCount.value)
    }
    @Test fun anAnonymousSupplementCannotEnterAnAuthenticatedHomeOrRepeatOnEveryGesture() = runBlocking {
        val calls = AtomicInteger()
        val vm = viewModel({ _, _ -> page("native") }, account = kotlinx.coroutines.flow.MutableStateFlow("account-a"), extra = {
            calls.incrementAndGet(); page("unrelated-account").copy(accountIdentity = "anonymous")
        })
        assertTrue(vm.loadServerHome()); vm.setHomeVisible(true)
        withTimeout(5_000) { while (calls.get() != 2) delay(10) }
        delay(50); repeat(5) { reveal(vm) }; delay(100)
        assertEquals(2, calls.get()); assertEquals(listOf("native"), vm.serverShelves.value.map { it.id })
    }

    @Test fun aFailedPrimaryCursorDoesNotBlockASupplementsOwnCursor() = runBlocking {
        val mainPages = AtomicInteger()
        val extraPages = AtomicInteger()
        val vm = viewModel({ _, token -> if (token == null) page("main", "broken") else {
            mainPages.incrementAndGet(); YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, isError = true)
        } }, extra = { browse -> page(browse, if (browse == "FEmusic_new_releases") "extra-next" else null) },
            extraPage = { browse, token ->
                assertEquals("FEmusic_new_releases", browse); assertEquals("extra-next", token)
                extraPages.incrementAndGet(); page("extra-page")
            })
        vm.setHomeVisible(true)
        withTimeout(5_000) { vm.refreshHome(true).join() }
        // The refresh may already own its first continuation when the three
        // source results publish together. Observe that outcome before adding demand.
        withTimeout(5_000) {
            (vm.javaClass.getDeclaredField("nativePageJob").apply { isAccessible = true }.get(vm) as? Job)?.join()
        }
        if (mainPages.get() == 0) reveal(vm)
        withTimeout(5_000) { vm.continuationStatus.first { it == ShelfLoadStatus.ERROR } }
        reveal(vm)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 4 } }
        assertEquals(1, mainPages.get()); assertEquals(1, extraPages.get())
    }
    @Test fun canceledSupplementResponsesCannotCrossAnAccountChange() = runBlocking {
        val account = kotlinx.coroutines.flow.MutableStateFlow("account-a")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val vm = viewModel({ _, _ -> page(account.value).copy(accountIdentity = account.value) }, account = account,
            extra = { browse ->
                val selected = account.value
                if (selected == "account-a") { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                page("$selected-$browse").copy(accountIdentity = selected)
            })
        vm.setHomeVisible(true)
        withTimeout(5_000) { entered.await() }
        account.value = "account-b"
        withTimeout(5_000) { vm.serverShelves.first { it.firstOrNull()?.id == "account-b" } }
        release.complete(Unit)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 3 } }
        assertTrue(vm.serverShelves.value.all { it.title.startsWith("account-b") })
        val checkpoint = HomeCardCache(app).readInitialServer(HomeCacheIdentity.home(preferences, "account-b"))!!
        assertEquals(HomeCacheIdentity.home(preferences, "account-b"), checkpoint.profileKey)
        assertFalse(checkpoint.serverShelves.any { it.title.startsWith("account-a") })
    }

    @Test fun manualRefreshPromotesExistingSupplementRequestsAndWaitsForBothWithoutDuplicates() = runBlocking {
        val releases = mapOf("FEmusic_new_releases" to CompletableDeferred<Unit>(), "FEmusic_explore" to CompletableDeferred<Unit>())
        val urgent = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.flow.StateFlow<Boolean>>()
        val calls = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val vm = viewModel({ _, _ -> page("primary") }, extra = { browse ->
            calls.getOrPut(browse) { AtomicInteger() }.incrementAndGet()
            urgent[browse] = requireNotNull(com.example.recommendation.DiscoveryRequestCoordinator.requestUrgency())
            releases.getValue(browse).await()
            page(browse)
        })
        vm.setHomeVisible(true)
        withTimeout(5_000) { while (urgent.size < 2) delay(10) }
        assertTrue(urgent.values.all { !it.value })
        val manual = vm.refreshHome(true)
        withTimeout(5_000) { urgent.values.forEach { it.first { promoted -> promoted } }; vm.isRefreshing.first { it } }
        assertFalse(manual.isCompleted)
        releases.getValue("FEmusic_new_releases").complete(Unit)
        withTimeout(5_000) { vm.serverShelves.first { it.any { shelf -> shelf.title == "FEmusic_new_releases" } } }
        assertTrue("Explore is still a required manual request", vm.isRefreshing.value)
        releases.getValue("FEmusic_explore").complete(Unit)
        withTimeout(5_000) { manual.join() }
        assertFalse(vm.isRefreshing.value)
        assertEquals(setOf(1), calls.values.map { it.get() }.toSet())
        assertEquals(3, vm.serverShelves.value.size)
    }
    @Test fun manualPromotionBypassesOptionalAdmissionWithoutStartingNewRequests() = runBlocking {
        val scrollingOwner = Any()
        val calls = AtomicInteger()
        com.example.performance.BackgroundWorkCoordinator.scrolling(scrollingOwner, true)
        try {
            val vm = viewModel({ _, _ -> page("primary") }, extra = { browse -> calls.incrementAndGet(); page(browse) })
            vm.setHomeVisible(true)
            withTimeout(5_000) { vm.serverShelves.first { it.isNotEmpty() } }
            assertNull(withTimeoutOrNull(150) { while (calls.get() == 0) delay(10); Unit })
            withTimeout(5_000) { vm.refreshHome(true).join() }
            assertEquals(2, calls.get())
            assertEquals(3, vm.serverShelves.value.size)
            assertFalse(vm.isRefreshing.value)
        } finally { com.example.performance.BackgroundWorkCoordinator.scrolling(scrollingOwner, false) }
    }
    @Test fun aFourthSupplementAttemptCanRecoverOnALaterHomeVisitAfterBoundedBackoff() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val vm = viewModel({ _, _ -> page("primary") }, extra = { browse ->
            if (browse != "FEmusic_new_releases") page(browse)
            else if (calls.incrementAndGet() < 4) YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, isError = true)
            else page("recovered")
        })
        vm.setHomeVisible(true)
        suspend fun settled(attempt: Int) { withTimeout(5_000) {
            while (calls.get() < attempt) delay(10)
            vm.serverCards.first { it.none { card -> card.status == ShelfLoadStatus.LOADING } }
        } }
        settled(1)
        for ((attempt, wait) in listOf(2 to 15_001L, 3 to 30_001L, 4 to 60_001L)) {
            elapsed += wait
            vm.setHomeVisible(false); delay(50); vm.setHomeVisible(true)
            settled(attempt)
        }
        assertEquals(4, calls.get())
        withTimeout(5_000) { vm.serverShelves.first { it.any { shelf -> shelf.title == "recovered" } } }
    }
    @Test fun connectivityRecoveryRetriesFailedSourcesAndFlappingDoesNotCreateARetryLoop() = runBlocking {
        val online = kotlinx.coroutines.flow.MutableStateFlow(false)
        val calls = AtomicInteger()
        val vm = viewModel({ _, _ -> page("primary") }, extra = { browse ->
            if (browse == "FEmusic_new_releases") { calls.incrementAndGet(); YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, isError = true) }
            else page(browse)
        }, connectivity = online)
        vm.setHomeVisible(true)
        withTimeout(5_000) { while (calls.get() < 1) delay(10); vm.serverCards.first { it.none { card -> card.status == ShelfLoadStatus.LOADING } } }
        online.value = true
        withTimeout(5_000) { while (calls.get() < 2) delay(10); vm.serverCards.first { it.none { card -> card.status == ShelfLoadStatus.LOADING } } }
        online.value = false; delay(50); online.value = true
        delay(150)
        assertEquals(2, calls.get())
        elapsed += 15_001
        online.value = false; delay(50); online.value = true
        withTimeout(5_000) { while (calls.get() < 3) delay(10) }
        assertTrue(vm.serverShelves.value.any { it.title == "FEmusic_explore" })
    }
    @Test fun endlessPrimaryPaginationStillGivesBothSupplementaryCursorsBoundedTurns() = runBlocking {
        val sequence = java.util.Collections.synchronizedList(mutableListOf<String>())
        val primaryPages = AtomicInteger()
        val vm = viewModel({ _, token ->
            if (token == null) page("primary", "home-1") else {
                sequence += "home"
                val next = primaryPages.incrementAndGet()
                page("home-page-$next", "home-${next + 1}")
            }
        }, extra = { browse -> page(browse, "extra-1") }, extraPage = { browse, token ->
            sequence += browse
            page("$browse-page-$token", "$token-next")
        })
        vm.setHomeVisible(true)
        withTimeout(5_000) { vm.refreshHome(true).join() }
        repeat(4) { index ->
            reveal(vm)
            withTimeout(5_000) { vm.paginationRevision.first { it >= index + 1 }; vm.serverShelves.first { it.size == index + 4 } }
            withTimeout(5_000) {
                (vm.javaClass.getDeclaredField("nativePageJob").apply { isAccessible = true }.get(vm) as? Job)?.join()
            }
        }
        assertEquals(listOf("home", "FEmusic_new_releases", "home", "FEmusic_explore"), sequence)
        assertEquals(vm.serverCards.value.size, vm.serverCards.value.map { it.key }.distinct().size)
    }

    @Test fun manualRefreshRecoversAfterThreeFailuresWithoutDroppingACachedSupplement() = runBlocking {
        val now = System.currentTimeMillis()
        val sources = listOf("FEmusic_home", "FEmusic_new_releases", "FEmusic_explore").map { browse ->
            HomeBrowseSnapshot(browse, "anonymous", listOf(shelf(browse)), fetchedAt = now, succeeded = true)
        }
        HomeCardCache(app).saveHome(SavedHomeCards(HomeCacheIdentity.home(preferences), now,
            serverShelves = sources.flatMap { it.shelves }, serverFetchSucceeded = true, browseSources = sources))
        val calls = AtomicInteger()
        val vm = viewModel({ _, _ -> page("FEmusic_home") }, extra = { browse ->
            if (browse == "FEmusic_new_releases" && calls.incrementAndGet() <= 3)
                YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, isError = true)
            else page(if (browse == "FEmusic_new_releases") "recovered" else browse)
        })
        vm.setHomeVisible(true)
        withTimeout(5_000) { vm.serverShelves.first { it.size == 3 } }
        repeat(3) {
            withTimeout(5_000) { vm.refreshHome(true).join() }
            assertTrue(vm.serverShelves.value.any { shelf -> shelf.id == "FEmusic_new_releases" })
        }
        assertEquals(3, calls.get())
        withTimeout(5_000) { vm.refreshHome(true).join() }
        assertEquals(4, calls.get())
        assertTrue(vm.serverShelves.value.any { it.id == "recovered" })
        assertFalse(vm.isRefreshing.value)
    }

}
