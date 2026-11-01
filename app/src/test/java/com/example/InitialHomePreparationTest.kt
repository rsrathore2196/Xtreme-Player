package com.example

import android.app.Application
import androidx.lifecycle.*
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
    private val preferences = UserProfile(name = "User", countryCode = "IN", country = "India", languages = listOf("Hindi"))
    private val track = MusicTrack("yt_abcdefghijk", "Song", "Singer", "Album", 180_000, "", "")
    private fun shelf(id: String) = ServerHomeShelf(id, id, tracks = listOf(track.copy(id = "yt_$id")))
    private fun page(id: String, cursor: String? = null) = YouTubeHomeParser.ParsedHomeFeed(listOf(shelf(id)), cursor)
    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        UserProfilePreferences.saveUserProfile(app, preferences)
        db = MusicDatabase.createInMemoryDatabase(app)
        player = PlaybackManager(app, MusicRepository(db.musicDao(), app))
    }
    @After fun stop() { stores.forEach { it.clear() }; player.release(); db.close(); Dispatchers.resetMain() }
    private fun viewModel(loader: suspend (String, String?) -> YouTubeHomeParser.ParsedHomeFeed,
        profile: suspend (UserProfile, Int) -> YouTubeMusicApiService.ProfileShelfResult = { _, _ -> YouTubeMusicApiService.ProfileShelfResult(null) },
        account: kotlinx.coroutines.flow.StateFlow<String>? = null,
        authenticatedRequests: MutableList<Boolean>? = null): HomeViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(MusicRepository(db.musicDao(), app), player, app, db,
                    { region, token, authenticated -> authenticatedRequests?.add(authenticated); loader(region, token) },
                    { selected, rotation, _ -> profile(selected, rotation) },
                    accountIdentity = { account?.value ?: YouTubeMusicApiService.discoveryAccountIdentity() },
                    accountUpdates = account ?: YouTubeAudioStreamService.discoveryAccountChanges) as T
        }
        return ViewModelProvider(store, factory)[HomeViewModel::class.java]
    }
    private suspend fun cached() {
        HomeCardCache(app).save(SavedHomeCards(HomeCacheIdentity.home(preferences), refreshedAt = System.currentTimeMillis(),
            serverShelves = listOf(shelf("cached")), serverFetchSucceeded = true))
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
        val automatic = vm.refreshHome(false)
        withTimeout(5_000) { started.await() }
        assertEquals(0, calls.get())
        val manual = vm.refreshHome(true)
        withTimeout(5_000) { vm.serverShelves.first { it.singleOrNull()?.id == "refreshed" } }
        withTimeout(5_000) { manual.join() }
        assertFalse(vm.isRefreshing.value)
        release.complete(Unit)
        withTimeout(5_000) { manual.join(); automatic.join() }
        assertEquals(1, calls.get()); assertEquals(1, profileCalls.get()); assertFalse(vm.isRefreshing.value)
    }
    @Test fun manualRefreshReusesBothActualRequestsWhenPromotingAnAutomaticRefresh() = runBlocking {
        val serverStarted = CompletableDeferred<Unit>(); val profileStarted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(); val profileCalls = AtomicInteger()
        val vm = viewModel({ _, _ -> calls.incrementAndGet(); serverStarted.complete(Unit); release.await(); page("native") }, { _, _ ->
            profileCalls.incrementAndGet(); profileStarted.complete(Unit); release.await(); YouTubeMusicApiService.ProfileShelfResult(null)
        })
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
        vm.revealNextHomeSection()
        withTimeout(5_000) { while (requests.get() < 1) delay(10) }; delay(50)
        vm.revealNextHomeSection()
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
        vm.loadServerHome(true); vm.revealNextHomeSection(); withTimeout(5_000) { started.await() }
        vm.updateUserProfile(preferences.copy(countryCode = "GB", country = "United Kingdom"))
        withTimeout(5_000) { vm.userProfile.first { it.countryCode == "GB" } }
        vm.loadServerHome(true); release.complete(Unit); delay(100)
        assertEquals(listOf("GB"), vm.serverShelves.value.map { it.title })
    }
    @Test fun aPageWithUnsupportedCollectionsStillRetainsItsCursorToPlayableShelves() = runBlocking {
        val vm = viewModel({ _, token -> if (token == null) YouTubeHomeParser.ParsedHomeFeed(emptyList(), "cursor")
            else YouTubeHomeParser.ParsedHomeFeed((1..6).map { shelf("server-$it") }, null) })
        assertTrue(vm.loadServerHome(true))
        vm.revealNextHomeSection()
        withTimeout(5_000) { vm.serverShelves.first { it.size == 6 } }
        vm.revealNextHomeSection()
        withTimeout(5_000) { vm.revealedShelfCount.first { it >= 4 } }
        assertEquals((1..6).map { "server-$it" }, vm.serverShelves.value.map { it.title })
    }
    @Test fun initialArtistReadinessIsPublishedOnlyAfterActualRankingsAreAvailable() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val readiness = com.example.performance.BackgroundWorkCoordinator::class.java.getDeclaredField("_startupReadiness")
            .apply { isAccessible = true }.get(null) as kotlinx.coroutines.flow.MutableStateFlow<com.example.performance.StartupReadiness>
        readiness.value = com.example.performance.StartupReadiness()
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
        db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
            eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        val vm = viewModel({ _, _ -> page("native") })
        withTimeout(5_000) { readiness.first { it.tasks[com.example.performance.StartupTask.RECENT_ARTISTS]?.terminal == true } }
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
        val vm = viewModel({ _, _ -> page("native") })
        vm.setHomeVisible(true)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.firstOrNull()?.playCount == 301 } }
        // Restoration can affect an artist outside the observable 300-row recent window.
        dao.insertListeningEvent(ListeningEventEntity(trackId = older.id, artistName = older.artist, timestamp = 1,
            sessionId = "restored", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        withTimeout(5_000) { vm.mostListenedArtists.first { it.any { artist -> artist.name == "Older Artist" && artist.playCount == 1 } } }
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, timestamp = 2_000,
            sessionId = "new-play", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        withTimeout(5_000) { vm.mostListenedArtists.first { it.firstOrNull()?.playCount == 302 } }
        dao.pruneOldEvents(Long.MAX_VALUE)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.isEmpty() } }
        assertFalse(vm.hasListenedArtists.value)
    }
    @Test fun databaseFailureCannotEraseAValidCachedArtistRanking() = runBlocking {
        HomeCardCache(app).saveLocal(SavedHomeCards(HomeCacheIdentity.home(preferences),
            artists = listOf(MostListenedArtist("Singer", 250, listOf(track))), inputRevision = "known-valid"))
        db.openHelper.writableDatabase.execSQL("DROP TABLE listening_events")
        val vm = viewModel({ _, _ -> page("native") })
        withTimeout(5_000) { vm.userMode.first { it == com.example.recommendation.HomeUserMode.UNAVAILABLE } }
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
        vm.revealNextHomeSection()
        withTimeout(5_000) { vm.serverShelves.first { it.size == 2 } }
        assertEquals(listOf(true, false), authenticated)
    }
    @Test fun switchingToAFreshRegionalAndLanguageCacheNeverForcesRequests() = runBlocking {
        val country = preferences.copy(country = "United Kingdom", countryCode = "GB")
        val language = country.copy(languages = listOf("Punjabi"))
        val cache = HomeCardCache(app)
        for (selected in listOf(preferences, country, language)) cache.save(SavedHomeCards(HomeCacheIdentity.home(selected),
            refreshedAt = System.currentTimeMillis(), serverShelves = listOf(shelf(selected.countryCode)), serverFetchSucceeded = true,
            profileShelfKey = HomeCacheIdentity.profile(selected), profileFetchedAt = System.currentTimeMillis(), profileFetchSucceeded = true,
            profileShelf = shelf(selected.languages.single())))
        val homeCalls = AtomicInteger(); val profileCalls = AtomicInteger()
        val vm = viewModel({ _, _ -> homeCalls.incrementAndGet(); page("wrong") }, { _, _ ->
            profileCalls.incrementAndGet(); YouTubeMusicApiService.ProfileShelfResult(shelf("wrong"))
        })
        vm.setHomeVisible(true)
        withTimeout(5_000) { vm.profileShelf.first { it?.id == "Hindi" } }
        vm.updateUserProfile(country)
        withTimeout(5_000) { vm.serverShelves.first { it.singleOrNull()?.id == "GB" }; vm.profileShelf.first { it?.id == "Hindi" } }
        vm.updateUserProfile(language)
        withTimeout(5_000) { vm.profileShelf.first { it?.id == "Punjabi" } }
        assertTrue(vm.loadServerHome()); assertTrue(vm.loadProfileShelf())
        assertEquals(0, homeCalls.get()); assertEquals(0, profileCalls.get())
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
        vm.setHomeVisible(true); withTimeout(5_000) { started.await() }
        vm.setHomeVisible(false); withTimeout(5_000) { canceled.await() }
        assertNull(vm.profileShelf.value); assertEquals("native", vm.serverShelves.value.single().id)
    }
    @Test fun hiddenHomeRetainsLocalCardsAndCatchesUpFromActualRecordsWhenShownAgain() = runBlocking {
        db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
        val dao = db.listeningEventDao()
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
            sessionId = "first", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        val vm = viewModel({ _, _ -> page("native") })
        vm.setHomeVisible(true)
        @Suppress("UNCHECKED_CAST")
        val shown = vm.javaClass.getDeclaredField("homeVisible").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.StateFlow<Boolean>
        @Suppress("UNCHECKED_CAST")
        val prepared = vm.javaClass.getDeclaredField("localPrepared").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.StateFlow<Boolean>
        withTimeout(5_000) { shown.first { it }; prepared.first { it }; vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 1 } }
        vm.setHomeVisible(false); withTimeout(5_000) { shown.first { !it } }
        dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
            sessionId = "second", eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
        assertEquals(2, dao.getActualTrackPlayCounts().single().playCount)
        assertNull(withTimeoutOrNull(200) { vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 2 } })
        vm.setHomeVisible(true)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.singleOrNull()?.playCount == 2 } }
        assertEquals(1, vm.pillsCardTracks.value.size)
    }
}
