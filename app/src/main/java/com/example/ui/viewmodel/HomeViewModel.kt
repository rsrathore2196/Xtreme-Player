package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.example.data.cache.*
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.*
import com.example.performance.*
import com.example.playback.PlaybackManager
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.HomeUserMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Calendar
import android.os.SystemClock

/** Retains the existing server feed; local playback records never trigger server recommendations. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val repository: MusicRepository, private val playbackManager: PlaybackManager,
    application: Application, private val suppliedDatabase: MusicDatabase? = null,
    private val homeLoader: suspend (String, String?, Boolean) -> com.example.data.remote.YouTubeHomeParser.ParsedHomeFeed = { region, token, authenticated ->
        YouTubeMusicApiService.getHomeRecommendations(region, continuation = token, includeAccount = authenticated) },
    private val profileLoader: suspend (UserProfile, Int, Boolean) -> YouTubeMusicApiService.ProfileShelfResult = { profile, rotation, force ->
        YouTubeMusicApiService.getProfileShelfResult(HomeCacheIdentity.region(profile), profile.languages, rotation, force) },
    private val accountIdentity: () -> String = YouTubeMusicApiService::discoveryAccountIdentity,
    private val accountUpdates: Flow<*> = com.example.data.remote.YouTubeAudioStreamService.discoveryAccountChanges,
    private val idleAdmission: suspend (Long) -> Unit = { BackgroundWorkCoordinator.awaitContinuousForegroundIdle(it, 5_000L) },
    private val elapsedTime: () -> Long = SystemClock::elapsedRealtime,
    private val supplementalLoader: suspend (String, String, Boolean) -> com.example.data.remote.YouTubeHomeParser.ParsedHomeFeed = { region, browseId, authenticated ->
        YouTubeMusicApiService.getHomeRecommendations(region, includeAccount = authenticated, browseId = browseId) },
    private val supplementContinuationLoader: suspend (String, String, String, Boolean) -> com.example.data.remote.YouTubeHomeParser.ParsedHomeFeed = { region, browse, token, authenticated ->
        YouTubeMusicApiService.getHomeRecommendations(region, continuation = token, includeAccount = authenticated, browseId = browse) },
    private val unlockIdleAdmission: suspend (Long) -> Unit = { BackgroundWorkCoordinator.awaitContinuousForegroundIdle(it, 6_000L) },
    private val prepareCoreTabs: suspend () -> Unit = { repository.prepareStartupTabs() },
    private val connectivityUpdates: Flow<Boolean> = flow {
        val monitor = withContext(AppDispatchers.Io) { com.example.playback.NetworkQualityMonitor.getInstance(application) }
        emitAll(monitor.connectionType.map { it != com.example.playback.NetworkConnectionType.OFFLINE }.distinctUntilChanged())
    }
) : AndroidViewModel(application) {
    private val owner = viewModelScope + AppDispatchers.HomeBookkeeping
    private var corePreparationLease: AutoCloseable? = BackgroundWorkCoordinator.coreTabsPreparation()
    private val cache = HomeCardCache(application)
    private val database by lazy { suppliedDatabase ?: MusicDatabase.getDatabase(application) }
    private val artistHistory by lazy { LocalArtistHistory.StatsReader(database) }
    private val _userProfile = MutableStateFlow(UserProfilePreferences.peekUserProfile()
        ?: (application as? com.example.XtremeMusicApp)?.startupSettings?.profile ?: UserProfile())
    val userProfile = _userProfile.asStateFlow()
    private fun currentHomeKey(profile: UserProfile) = HomeCacheIdentity.home(profile, accountIdentity())
    private fun currentProfileKey(profile: UserProfile) = HomeCacheIdentity.profile(profile, accountIdentity())
    private var homeKey = currentHomeKey(_userProfile.value)
    private var serverCacheKey = homeKey
    private var profileKey = currentProfileKey(_userProfile.value)
    private val _serverShelves = MutableStateFlow<List<ServerHomeShelf>>(emptyList())
    val serverShelves = _serverShelves.asStateFlow()
    private val _serverCards = MutableStateFlow(initialHomeSkeletons())
    val serverCards = _serverCards.asStateFlow()
    private val _profileStatus = MutableStateFlow(ShelfLoadStatus.EMPTY)
    val profileStatus = _profileStatus.asStateFlow()
    private val _continuationStatus = MutableStateFlow(ShelfLoadStatus.EMPTY)
    val continuationStatus = _continuationStatus.asStateFlow()
    private val _artistStatus = MutableStateFlow(ShelfLoadStatus.LOADING)
    val artistStatus = _artistStatus.asStateFlow()
    private val _profileShelf = MutableStateFlow<ServerHomeShelf?>(null)
    val profileShelf = _profileShelf.asStateFlow()
    private val _revealedShelfCount = MutableStateFlow(3)
    val revealedShelfCount = _revealedShelfCount.asStateFlow()
    private val historyAdmitted = MutableStateFlow(false)
    private val _listeningHistoryCard = MutableStateFlow(SectionState(title = "Jump Back In", subtitle = "Based on your recent listening history & frequency"))
    val listeningHistoryCard = _listeningHistoryCard.asStateFlow()
    val recentlyPlayed = _listeningHistoryCard.map { it.tracks }.stateIn(owner, SharingStarted.Eagerly, emptyList())
    val pillsCardTracks = recentlyPlayed.map { it.take(RECENTLY_PLAYED_CARD_LIMIT) }.stateIn(owner, SharingStarted.Eagerly, emptyList())
    private val _mostListenedArtists = MutableStateFlow<List<MostListenedArtist>>(emptyList())
    val mostListenedArtists = _mostListenedArtists.asStateFlow()
    private val _hasListenedArtists = MutableStateFlow(false)
    val hasListenedArtists = _hasListenedArtists.asStateFlow()
    val hasRecentlyPlayed = combine(recentlyPlayed, historyAdmitted) { tracks, admitted -> admitted && tracks.isNotEmpty() }
        .stateIn(owner, SharingStarted.Eagerly, false)
    private val _userMode = MutableStateFlow(HomeUserMode.LOADING)
    val userMode = _userMode.asStateFlow()
    val heroSeedTrack = combine(playbackManager.uiState.map { it.currentTrack }.distinctUntilChanged(), recentlyPlayed, _serverShelves) {
        playing, recent, server -> playing ?: recent.firstOrNull() ?: server.firstNotNullOfOrNull { it.tracks.firstOrNull() }
    }.stateIn(owner, SharingStarted.Eagerly, null)
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private val _refreshErrorMessage = MutableStateFlow<String?>(null)
    val refreshErrorMessage = _refreshErrorMessage.asStateFlow()
    private val restored = CompletableDeferred<Unit>()
    private var homeCacheRestore = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var profileCacheRestore = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var visible = false
    private val homeVisible = MutableStateFlow(false)
    private val localRestored = CompletableDeferred<Unit>()
    private val profileRestored = CompletableDeferred<Unit>()
    private var restoredProfileKey: String? = null
    private val homeDrawAt = MutableStateFlow<Long?>(null)
    private val initialNativeReadyAt = MutableStateFlow<Long?>(null)
    val initialHomePreparedAt = initialNativeReadyAt.asStateFlow()
    private val initialHomeRenderedAt = MutableStateFlow<Long?>(null)
    private val startupRevision = MutableStateFlow(0L)
    private val _coreTabsReady = MutableStateFlow(false)
    val coreTabsReady = _coreTabsReady.asStateFlow()
    private var coreSettledAt: Long? = null
    private val initialHistorySettledAt = MutableStateFlow<Long?>(null)
    private val nativeStageReadyAt = MutableStateFlow<Long?>(null)
    private var deferredServerCache = false
    private var fullServerCheckpointPending = false
    private val _optionalWorkReady = MutableStateFlow(false)
    val optionalWorkReady = _optionalWorkReady.asStateFlow()
    private val _artistPortraitsReady = MutableStateFlow(false)
    val artistPortraitsReady = _artistPortraitsReady.asStateFlow()
    private val initialArtistSettledAt = MutableStateFlow<Long?>(null)
    private val initialProfileSettledAt = MutableStateFlow<Long?>(null)
    private val profileWorkReady = MutableStateFlow(false)
    private val _profileShelfVisible = MutableStateFlow(false)
    val profileShelfVisible = _profileShelfVisible.asStateFlow()
    private val _additionalShelvesUnlocked = MutableStateFlow(false)
    val additionalShelvesUnlocked = _additionalShelvesUnlocked.asStateFlow()
    private var pendingNativeReveal = false
    private var automaticRevealPending = false
    private var nativeAdmissionInput: Long? = null
    private val nativeRevealAt = MutableStateFlow<Long?>(null)
    private var nativePageJob: Job? = null
    private var supplementalDemand = false
    private var nativeEndDemand = false
    private val primarySettled = MutableStateFlow(false)
    fun onHomeFrameDrawn(preparedAt: Long? = initialNativeReadyAt.value) {
        if (homeDrawAt.value == null) {
            homeDrawAt.value = elapsedTime()
            android.util.Log.d("HomeWork", "first-draw at=${homeDrawAt.value}")
        }
        if (preparedAt != null && preparedAt == initialNativeReadyAt.value)
            initialHomeRenderedAt.compareAndSet(null, elapsedTime())
    }
    private val artistSectionRelevant = MutableStateFlow(false)
    fun setArtistSectionRelevant(active: Boolean) {
        artistSectionRelevant.value = active
        if (active) owner.launch {
            if (artistIdentityDirty) { artistIdentityDirty = false; artistRequests.update { it + 1 } }
        }
    }
    private var artistIdentityDirty = false
    private var serverGeneration = 0L
    private var profileGeneration = 0L
    private var serverFetchedAt = 0L
    private var profileFetchedAt = 0L
    private var serverSuccess = false
    private var profileSuccess = false
    private var serverAttemptAt: Long? = null
    private var profileAttemptAt: Long? = null
    private var profileRotation = 0
    private val supplementalBrowseIds = listOf("FEmusic_new_releases", "FEmusic_explore")
    private var homeContinuationToken: String? = null
    private var serverJob: Deferred<Boolean>? = null
    private var serverUrgent = MutableStateFlow(false)
    private var profileJob: Deferred<Boolean>? = null
    private val profileRequests = MutableStateFlow(0L)
    private var profileForcePending = false
    private val sources = linkedMapOf<String, HomeBrowseSnapshot>()
    // Request-status changes reuse the projection of these immutable source lists.
    private var projectedKey: String? = null
    private var projectedGeneration = -1L
    private var projectedInputs: List<List<ServerHomeShelf>>? = null
    private var projectedShelves: List<ServerHomeShelf> = emptyList()
    private val sourceJobs = mutableMapOf<String, Job>()
    private val sourceUrgency = mutableMapOf<String, MutableStateFlow<Boolean>>()
    // Two primary turns per cycle keep native Home preferred without starving supplements.
    private val paginationOrder = listOf("FEmusic_home", "FEmusic_new_releases", "FEmusic_home", "FEmusic_explore")
    private var paginationTurn = 0
    private var lastConnectivityRetryAt: Long? = null
    private val sourceGenerations = mutableMapOf<String, Long>()
    private val sourceFailures = mutableMapOf<String, ContinuationFailure>()
    private val loadingSources = mutableSetOf<String>()
    private val _paginationRevision = MutableStateFlow(0L)
    val paginationRevision = _paginationRevision.asStateFlow()
    private var refreshJob: Job? = null
    private var refreshIsExplicit = false
    private val artistRequests = MutableStateFlow(0L)
    private var historyUnavailable = false
    private var artistFailed = false
    private val historyRetry = MutableStateFlow(0L)
    private var recentMetadataRevision: String? = null
    private var artistMetadataRevision: String? = null
    private var recentTrackIds: List<String> = emptyList()
    private var recentPlayedIds: List<String> = emptyList()
    private var recentMetadata: List<MusicTrack> = emptyList()

    init {
        owner.launch {
            var previousForeground: Boolean? = null
            val foreground = BackgroundWorkCoordinator.uiForeground.onEach { active ->
                if (!active) {
                    // Keep the tracks, but do not reopen Room or show the retained
                    // card before the next foreground Home's five idle seconds.
                    historyAdmitted.value = false
                    initialHistorySettledAt.value = null
                    initialHomeRenderedAt.value = null
                } else if (previousForeground == false) initialNativeReadyAt.value?.let { previous ->
                    // A new presentation identity makes the retained UI report an
                    // actual draw, including a reopen without Activity recreation.
                    initialNativeReadyAt.value = maxOf(elapsedTime(), previous + 1L)
                }
                previousForeground = active
            }
            combine(homeVisible, foreground, startupRevision) { active, foreground, revision ->
                revision.takeIf { active && foreground }
            }.distinctUntilChanged().collectLatest { revision -> if (revision != null) runStartupStages(revision) }
        }
        owner.launch {
            val foreground = combine(homeVisible, homeDrawAt, BackgroundWorkCoordinator.uiForeground,
                BackgroundWorkCoordinator.interactions, nativeRevealAt) { active, drawn, visible, _, last ->
                if (active && visible && drawn != null) maxOf(drawn, last ?: drawn) else null
            }
            val available = combine(_serverShelves, _serverCards, _revealedShelfCount, _isRefreshing,
                _paginationRevision) { _, _, _, refreshing, _ -> !refreshing && hasAutomaticNativeWork() }.distinctUntilChanged()
            val priority = nativeStageReadyAt
            combine(foreground, available, priority) { start, work, priorityAt ->
                if (start != null && work && priorityAt != null) maxOf(start, priorityAt) else null
            }.collectLatest { start ->
                nativeAdmissionInput = null
                if (start != null) {
                    unlockIdleAdmission(start)
                    nativeAdmissionInput = BackgroundWorkCoordinator.interactions.value
                    _additionalShelvesUnlocked.value = true
                    revealAdmittedNativeShelf()
                }
            }
        }
        owner.launch {
            var previous: Boolean? = null
            combine(homeVisible, BackgroundWorkCoordinator.uiForeground) { active, foreground -> active && foreground }
                .distinctUntilChanged().flatMapLatest { active -> if (active) connectivityUpdates else emptyFlow() }
                .distinctUntilChanged().collect { online ->
                    val recovered = online && previous == false
                    previous = online
                    val now = elapsedTime()
                    if (recovered && (lastConnectivityRetryAt == null || now - lastConnectivityRetryAt!! >= 15_000L)) {
                        lastConnectivityRetryAt = now
                        supplementalBrowseIds.forEach { browse -> sourceFailures[browse]?.takeIf { it.token == "initial" }?.let {
                            sourceFailures[browse] = it.copy(retryAt = now)
                        } }
                        scheduleSupplements(false)
                    }
                }
        }
        owner.launch {
            combine(homeVisible, homeDrawAt, BackgroundWorkCoordinator.uiForeground) { active, drawn, foreground -> active && drawn != null && foreground }
                .distinctUntilChanged().collect { eligible -> if (eligible) scheduleSupplements(false) }
        }
        owner.launch {
            combine(homeVisible, profileRequests) { active, request -> request.takeIf { active } }
                .collectLatest { request ->
                    if (request == null) return@collectLatest
                    try {
                        profileWorkReady.first { it }
                        val key = profileKey; val generation = profileGeneration
                        if (loadProfileShelf(profileForcePending)) profileForcePending = false
                        if (key == profileKey && generation == profileGeneration)
                            initialProfileSettledAt.compareAndSet(null, elapsedTime())
                    } finally { if (!homeVisible.value) profileJob?.cancelAndJoin() }
                }
        }
        owner.launch {
            try { BackgroundWorkCoordinator.traceWork("Home native cache restore") { restoreCachedHome() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing cache never blocks the native request. */ }
            finally { restored.complete(Unit) }
        }
        owner.launch {
            restored.await()
            UserProfilePreferences.getUserProfileFlow(application).collect { applyUserProfile(it) }
        }
        owner.launch {
            restored.await()
            accountUpdates.collect { applyUserProfile(_userProfile.value) }
        }
        owner.launch {
            localRestored.await()
            combine(historyRetry, homeVisible, historyAdmitted) { retry, active, admitted -> retry.takeIf { active && admitted } }
                .distinctUntilChanged().flatMapLatest { retry -> if (retry == null) emptyFlow() else flow {
                    emitAll(database.listeningEventDao().observeActualPlaySnapshot().distinctUntilChanged(::sameLocalHomeSnapshot))
                }.catch { error ->
                    if (error is CancellationException) throw error
                    android.util.Log.w("HomeFeed", "Listening snapshot unavailable: ${error.javaClass.simpleName}")
                    historyUnavailable = true
                    _userMode.value = HomeUserMode.UNAVAILABLE
                    _artistStatus.value = ShelfLoadStatus.ERROR
                    initialHistorySettledAt.compareAndSet(null, elapsedTime())
                } }.collectLatest { rowsSnapshot -> BackgroundWorkCoordinator.traceWork("Home listening history") {
                    try {
                        val metadataRevision = rowsSnapshot.firstOrNull()?.trackMetadataRevision
                        val metadataChanged = metadataRevision == null || metadataRevision != recentMetadataRevision
                        val representativeRevision = rowsSnapshot.firstOrNull()?.artistMetadataRevision
                        val representativesChanged = representativeRevision == null || representativeRevision != artistMetadataRevision
                        val previousIds = recentTrackIds
                        val (ids, playedIds, missingIds) = BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) { withContext(AppDispatchers.CpuBounded) {
                            val events = rowsSnapshot.mapNotNull { it.event }
                            val ids = events.map { it.trackId }.distinct()
                            val missing = if (metadataChanged) ids else if (ids == previousIds) emptyList()
                                else previousIds.toHashSet().let { known -> ids.filterNot(known::contains) }
                            Triple(ids, events.asSequence().filter(HomeRecommendationPolicy::played)
                                .map { it.trackId }.distinct().toList(), missing)
                        } }
                        val reuseMetadata = !metadataChanged && missingIds.isEmpty()
                        val tracks = if (reuseMetadata && ids.size == previousIds.size) recentMetadata else {
                            val records = if (missingIds.isEmpty()) emptyList() else withContext(AppDispatchers.Database) {
                                missingIds.chunked(900).flatMap { database.musicDao().getTracksByIds(it) }
                            }
                            BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) { withContext(AppDispatchers.CpuBounded) {
                                val existing = if (metadataChanged) emptyList() else ids.toHashSet().let { wanted -> recentMetadata.filter { it.id in wanted } }
                                existing + records.map { it.toMusicTrack() }
                            } }
                        }
                        // The order/actual-audio predicate can change without track metadata
                        // changing (for example a zero-time occurrence starts playing).
                        val recent = if (reuseMetadata && playedIds == recentPlayedIds) _listeningHistoryCard.value.tracks
                        else BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) { withContext(AppDispatchers.CpuBounded) {
                            val byId = tracks.associateBy { it.id }; val seen = HomeRecommendationPolicy.Recordings()
                            playedIds.asSequence().mapNotNull { byId[it] }
                                .filter(seen::add).take(30).toList()
                        } }
                        recentMetadataRevision = metadataRevision; recentTrackIds = ids; recentPlayedIds = playedIds; recentMetadata = tracks
                        historyUnavailable = false
                        _userMode.value = HomeUserMode.confirmed(rowsSnapshot.firstOrNull()?.total ?: 0, false)
                        val historyChanged = _listeningHistoryCard.value.tracks != recent
                        _listeningHistoryCard.update { it.copy(tracks = recent, isLoaded = recent.isNotEmpty(), isLoading = false) }
                        artistMetadataRevision = representativeRevision
                        if (historyChanged && _artistPortraitsReady.value) cache.saveLocal(snapshot())
                        if (representativesChanged) artistRequests.update { it + 1 }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        historyUnavailable = true
                        _userMode.value = HomeUserMode.UNAVAILABLE
                        _artistStatus.value = ShelfLoadStatus.ERROR
                    }
                    // A superseded emission or hidden Home is not a completed
                    // historical read; the next stage waits for a real outcome.
                    initialHistorySettledAt.compareAndSet(null, elapsedTime())
                } }
        }
        owner.launch {
            localRestored.await()
            combine(artistRequests, _artistPortraitsReady, homeVisible) { request, admitted, active ->
                request.takeIf { admitted && active }
            }.distinctUntilChanged().flatMapLatest { request ->
                if (request == null) emptyFlow() else database.listeningStatsDao().observeLifetimeArtists()
                    .distinctUntilChanged().flowOn(AppDispatchers.Database).catch { error ->
                        if (error is CancellationException) throw error
                        artistPreparationFailed()
                    }
            }.collectLatest { totals ->
                try {
                    var artists = BackgroundWorkCoordinator.traceWork("Home artist summaries") {
                        BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) { artistHistory.read(totals) }
                    }
                    val identityBeforeRestore = ArtistPhotoRepository.identities.value
                    ArtistPhotoRepository.restoreSaved(getApplication(), artists.take(4).map { ArtistPhotoRepository.cachedArtist(it.name) })
                    if (identityBeforeRestore != ArtistPhotoRepository.identities.value)
                        artists = BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) { artistHistory.read(totals) }
                    android.util.Log.d("ArtistPortrait", "shelf-ready count=${artists.size} at=${elapsedTime()}")
                    val changed = artists != _mostListenedArtists.value
                    _hasListenedArtists.value = artists.isNotEmpty()
                    _mostListenedArtists.value = artists
                    artistFailed = false
                    if (changed) cache.saveLocal(snapshot())
                    _artistStatus.value = if (artists.isEmpty()) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS
                    initialArtistSettledAt.compareAndSet(null, elapsedTime())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    artistPreparationFailed()
                }
            }
        }
        owner.launch {
            restored.await()
            var revision = ArtistPhotoRepository.identities.value
            ArtistPhotoRepository.identities.collect { next ->
                if (next != revision) {
                    revision = next
                    if (visible && artistSectionRelevant.value) artistRequests.update { it + 1 }
                    else artistIdentityDirty = true
                }
            }
        }
    }
    private suspend fun runStartupStages(revision: Long) {
        fun current() { if (revision != startupRevision.value) throw CancellationException("Home context changed") }
        restored.await()
        combine(homeDrawAt, initialNativeReadyAt, initialHomeRenderedAt) { draw, prepared, rendered ->
            draw != null && prepared != null && (initialHomeShelves(_serverShelves.value).size < 3 || rendered != null)
        }.first { it }
        if (!_coreTabsReady.value) {
            try { BackgroundWorkCoordinator.traceWork("Startup core tabs") { prepareCoreTabs() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("HomeStartup", "Core caches settled: ${error.javaClass.simpleName}") }
            current(); coreSettledAt = elapsedTime(); _coreTabsReady.value = true
            corePreparationLease?.close(); corePreparationLease = null
        }
        if (!historyAdmitted.value || initialHistorySettledAt.value == null) {
            val start = maxOf(coreSettledAt ?: 0L, initialNativeReadyAt.value ?: 0L, initialHomeRenderedAt.value ?: 0L)
            BackgroundWorkCoordinator.traceWork("Startup Recently Played idle") { idleAdmission(start) }
            current()
            if (!localRestored.isCompleted) {
                try { BackgroundWorkCoordinator.traceWork("Startup Recently Played cache") {
                    BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) { cache.readRecent(homeKey) }
                }?.let { saved ->
                    _listeningHistoryCard.update { it.copy(tracks = saved.history.tracks, isLoaded = saved.history.tracks.isNotEmpty()) }
                } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Room remains authoritative. */ }
                finally { localRestored.complete(Unit) }
            }
            if (!historyAdmitted.value) initialHistorySettledAt.value = null
            historyAdmitted.value = true
            initialHistorySettledAt.first { it != null }
        }
        if (initialProfileSettledAt.value == null) {
            BackgroundWorkCoordinator.traceWork("Home profile admission") { idleAdmission(maxOf(initialHistorySettledAt.value ?: 0L, coreSettledAt ?: 0L)) }
            current()
            if (!profileRestored.isCompleted || restoredProfileKey != profileKey) {
                val requestedKey = profileKey
                try { BackgroundWorkCoordinator.traceWork("Home profile cache restore") {
                    BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) { cache.readProfile(requestedKey) }
                }?.let(::restoreProfile) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A missing cache is not a failed stage. */ }
                current(); restoredProfileKey = requestedKey; profileRestored.complete(Unit)
            }
            profileWorkReady.value = true; _profileShelfVisible.value = true
            loadProfileShelf(profileForcePending)
            current(); initialProfileSettledAt.compareAndSet(null, elapsedTime())
        }
        if (initialArtistSettledAt.value == null) {
            BackgroundWorkCoordinator.traceWork("Home artist admission") { idleAdmission(initialProfileSettledAt.value ?: elapsedTime()) }
            current()
            try { BackgroundWorkCoordinator.traceWork("Startup artist cache") {
                BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) { cache.readArtists(homeKey) }
            }?.let { saved ->
                val artists = saved.artists.take(15).takeIf { saved.artistDataVersion == 3 }.orEmpty()
                _mostListenedArtists.value = artists; _hasListenedArtists.value = artists.isNotEmpty()
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Preserve any retained rankings. */ }
            _optionalWorkReady.value = true; _artistPortraitsReady.value = true
            initialArtistSettledAt.first { it != null }
        }
        if (nativeStageReadyAt.value == null) {
            BackgroundWorkCoordinator.traceWork("Startup remaining native idle") { unlockIdleAdmission(maxOf(initialArtistSettledAt.value ?: 0L, initialProfileSettledAt.value ?: 0L)) }
            current(); _additionalShelvesUnlocked.value = true
            BackgroundWorkCoordinator.traceWork("Startup remaining native cache") { restoreRemainingNativeCache() }
            current()
            if (_serverShelves.value.size > _revealedShelfCount.value) _revealedShelfCount.update { it + 1 }
            nativeRevealAt.value = elapsedTime(); nativeStageReadyAt.value = elapsedTime()
            if (fullServerCheckpointPending) persistServerCache()
            supplementalDemand = true
            loadServerHome(false)
            scheduleSupplements(false)
        }
    }

    private suspend fun persistServerCache() {
        val key = homeKey; val generation = serverGeneration
        if (nativeStageReadyAt.value == null && !_additionalShelvesUnlocked.value) {
            fullServerCheckpointPending = true
            cache.saveInitialHome(snapshot())
        } else {
            cache.saveHome(snapshot())
            if (key == homeKey && generation == serverGeneration) fullServerCheckpointPending = false
        }
    }

    internal suspend fun restoreRemainingNativeCache(urgent: Boolean = false) {
        if (!deferredServerCache) { publishSources(urgent); return }
        val key = homeKey; val generation = serverGeneration
        val saved = BackgroundWorkCoordinator.localDataPreparation(urgent = { urgent }, awaitFrameQuiet = false) { cache.readServer(serverCacheKey) }
        if (key != homeKey || generation != serverGeneration) return
        deferredServerCache = false
        if (saved != null) {
            val prior = saved.browseSources.ifEmpty { listOf(HomeBrowseSnapshot("FEmusic_home", saved.profileKey.substringAfter('|'),
                saved.serverShelves, saved.continuationToken, saved.consumedContinuations, saved.refreshedAt, saved.serverFetchSucceeded)) }
            for (cached in prior) {
                if (cached.accountIdentity != serverCacheKey.substringAfter('|')) continue
                val retained = sources[cached.browseId]
                // Equal timestamps identify the initial three-shelf projection of
                // this exact checkpoint. Restore its full source, not a refresh
                // from the deliberately incomplete startup projection.
                sources[cached.browseId] = if (retained == null || retained.fetchedAt <= cached.fetchedAt) cached else retained.copy(
                    shelves = withContext(AppDispatchers.CpuBounded) { refreshServerShelves(cached.shelves, retained.shelves) })
            }
            projectedInputs = null
        }
        publishSources(urgent)
    }

    private fun artistPreparationFailed() {
        artistFailed = true // Preserve the last valid saved-duration ranking.
        android.util.Log.w("HomeFeed", "Artist preparation failed; cached artists=${_mostListenedArtists.value.size}")
        _artistStatus.value = ShelfLoadStatus.ERROR
        initialArtistSettledAt.compareAndSet(null, elapsedTime())
    }
    private suspend fun publishServer(shelves: List<ServerHomeShelf>, status: ShelfLoadStatus = ShelfLoadStatus.SUCCESS,
        message: String = "", revealed: Int? = null) {
        val key = homeKey; val generation = serverGeneration
        val unique = if (shelves.isEmpty() || shelves === _serverShelves.value) shelves else withContext(AppDispatchers.CpuBounded) {
            HomeShelfIdentity.deduplicate(shelves)
        }
        val presented = if (nativeStageReadyAt.value == null && !_additionalShelvesUnlocked.value) initialHomeShelves(unique)
            else initialHomeShelves(unique).let { first -> first + unique.filterNot { it.id in first.map(ServerHomeShelf::id) } }
        if (key != homeKey || generation != serverGeneration) return
        if (_serverShelves.value.isEmpty() && unique.isNotEmpty()) nativeRevealAt.value = elapsedTime()
        // Publish eligibility before rows so a ready shelf cannot appear hidden
        // for a frame or briefly remove its matching loading slot.
        if (revealed != null) _revealedShelfCount.value = revealed.coerceAtMost(unique.size)
        _serverCards.value = presentNativeShelves(_serverCards.value, presented, status, message)
        _serverShelves.value = unique
        markInitialNativeReady()
    }
    private fun markInitialNativeReady() {
        // Wait for the initial native window, not just the scaffold's first draw.
        // A genuinely short/failed response must still let the local stages progress.
        if (initialHomeShelves(_serverShelves.value).size == 3 || primarySettled.value &&
            supplementalBrowseIds.none { sourceJobs[it]?.isActive == true })
            initialNativeReadyAt.compareAndSet(null, elapsedTime())
    }
    private fun snapshot() = SavedHomeCards(serverCacheKey, serverFetchedAt, _listeningHistoryCard.value,
        _mostListenedArtists.value.take(15), null, _serverShelves.value, _profileShelf.value, homeContinuationToken,
        profileKey, profileFetchedAt, serverSuccess, profileSuccess, sources["FEmusic_home"]?.consumed.orEmpty(),
        sourceBrowseId = "FEmusic_home", completedBrowseIds = supplementalBrowseIds.filterTo(linkedSetOf()) { sources[it]?.succeeded == true },
        browseSources = sources.values.toList())
    private suspend fun restoreHome(saved: SavedHomeCards) {
        if (homeKey != currentHomeKey(_userProfile.value) || saved.profileKey !in setOf(homeKey, HomeCacheIdentity.home(_userProfile.value, "anonymous"))) return
        // Freshness and cursor ownership must be coherent before cache-first UI
        // publication can wake another retained Home request.
        deferredServerCache = saved.initialProjection
        serverCacheKey = saved.profileKey
        serverFetchedAt = saved.refreshedAt
        serverSuccess = saved.serverFetchSucceeded && saved.serverContentVersion == SERVER_HOME_CONTENT_VERSION
        sources.clear()
        saved.browseSources.filter { it.browseId in listOf("FEmusic_home") + supplementalBrowseIds &&
            it.accountIdentity == saved.profileKey.substringAfter('|') }.forEach { sources[it.browseId] = it }
        if (sources.isEmpty()) {
            // Older caches contain one combined feed. Keep it visible, but do not
            // pretend that a single old cursor owns all supplemental responses.
            sources["FEmusic_home"] = HomeBrowseSnapshot("FEmusic_home", saved.profileKey.substringAfter('|'),
                saved.serverShelves, saved.continuationToken.takeIf { saved.sourceBrowseId == "FEmusic_home" },
                saved.consumedContinuations.map { it.substringAfter('|', it) }.toSet(), saved.refreshedAt, serverSuccess)
        }
        homeContinuationToken = sources["FEmusic_home"]?.continuation
        publishServer(saved.serverShelves, when {
            saved.serverShelves.isNotEmpty() -> ShelfLoadStatus.SUCCESS
            saved.serverFetchSucceeded -> ShelfLoadStatus.EMPTY
            else -> ShelfLoadStatus.LOADING
        })
    }
    private suspend fun restoreCachedHome() {
        val requested = cache.readInitialServer(homeKey)
        requested?.let { restoreHome(it) }
        if (homeKey != HomeCacheIdentity.home(_userProfile.value, "anonymous") &&
            requested?.serverFetchSucceeded != true && requested?.serverShelves.isNullOrEmpty()) {
            cache.readInitialServer(HomeCacheIdentity.home(_userProfile.value, "anonymous"))?.let { restoreHome(it) }
        }
    }
    private fun restoreProfile(saved: SavedHomeCards) {
        if (saved.profileShelfKey != profileKey || profileKey != currentProfileKey(_userProfile.value)) return
        profileFetchedAt = saved.profileFetchedAt; profileSuccess = saved.profileFetchSucceeded
        _profileShelf.value = saved.profileShelf; _profileStatus.value = if (saved.profileShelf == null) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS
    }
    private fun fresh(success: Boolean, fetchedAt: Long, empty: Boolean = false) = success &&
        System.currentTimeMillis() - fetchedAt in 0 until (if (empty) 60_000L else 10 * 60_000L)

    suspend fun loadServerHome(force: Boolean = false): Boolean = withContext(AppDispatchers.HomeBookkeeping) {
        restored.await()
        while (true) {
            val restoring = homeCacheRestore
            restoring.await()
            if (restoring === homeCacheRestore) break
        }
        val completeInitialCache = initialHomeShelves(_serverShelves.value).size == 3
        val beforeNativeStage = nativeStageReadyAt.value == null && !_additionalShelvesUnlocked.value
        if (!force && (beforeNativeStage && completeInitialCache ||
            (nativeStageReadyAt.value != null || completeInitialCache) && fresh(serverSuccess, serverFetchedAt, sources.values.none { it.shelves.isNotEmpty() }) &&
            (serverCacheKey == homeKey || serverAttemptAt != null))) {
            primarySettled.value = true
            markInitialNativeReady()
            return@withContext true
        }
        serverJob?.takeIf { it.isActive }?.let { if (force || initialHomeShelves(_serverShelves.value).size < 3) serverUrgent.value = true; return@withContext it.await() }
        val now = System.currentTimeMillis()
        if (!force && serverAttemptAt?.let { now - it in 0 until 60_000L } == true) return@withContext false
        serverAttemptAt = now
        val key = homeKey; val generation = serverGeneration; val region = HomeCacheIdentity.region(_userProfile.value)
        sourceJobs.remove("FEmusic_home")?.cancel(); sourceUrgency.remove("FEmusic_home")
        val sourceGeneration = (sourceGenerations["FEmusic_home"] ?: 0L) + 1
        sourceGenerations["FEmusic_home"] = sourceGeneration
        sourceFailures.remove("FEmusic_home")
        _continuationStatus.value = ShelfLoadStatus.EMPTY
        if (_serverShelves.value.isEmpty()) publishServer(emptyList(), ShelfLoadStatus.LOADING)
        val urgent = MutableStateFlow(force || initialHomeShelves(_serverShelves.value).size < 3).also { serverUrgent = it }
        val job = owner.async {
            try {
                val response = com.example.recommendation.DiscoveryRequestCoordinator.withPriority(urgent, awaitFrameQuiet = false) {
                    BackgroundWorkCoordinator.traceWork("Native Home response") {
                        withContext(AppDispatchers.Io) { homeLoader(region, null, key.substringAfter('|') != "anonymous") }
                    }
                }
                if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value)) return@async false
                if (response.isError) {
                    val message = response.errorMessage ?: "Recommendations are temporarily unavailable. Tap to retry."
                    _refreshErrorMessage.value = message
                    publishSources()
                    return@async false
                }
                val responseAccount = response.accountIdentity ?: key.substringAfter('|')
                if (responseAccount != "anonymous" && responseAccount != key.substringAfter('|')) {
                    _refreshErrorMessage.value = "Home response account did not match the request"
                    if (_serverShelves.value.isEmpty()) publishServer(emptyList(), ShelfLoadStatus.ERROR)
                    return@async false
                }
                val responseKey = HomeCacheIdentity.home(_userProfile.value, responseAccount)
                if (sourceGenerations["FEmusic_home"] != sourceGeneration) return@async false
                if (serverCacheKey != responseKey) sources.clear()
                val previous = sources["FEmusic_home"]?.shelves.orEmpty()
                val shelves = withContext(AppDispatchers.CpuBounded) { refreshServerShelves(previous, response.shelves) }
                if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value)) return@async false
                serverCacheKey = responseKey
                serverFetchedAt = System.currentTimeMillis(); serverSuccess = true
                homeContinuationToken = response.continuationToken
                sources["FEmusic_home"] = HomeBrowseSnapshot("FEmusic_home", responseAccount, shelves,
                    response.continuationToken, fetchedAt = serverFetchedAt, succeeded = true)
                _refreshErrorMessage.value = null
                publishSources()
                if (generation != serverGeneration || key != homeKey) return@async false
                if (response.shelves.isEmpty()) _refreshErrorMessage.value = "No recommendations are available. Tap to retry."
                persistServerCache(); true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _refreshErrorMessage.value = "Recommendations are temporarily unavailable. Tap to retry."
                publishSources()
                false
            }
        }
        serverJob = job
        val loaded = try { job.await() } finally { if (serverJob === job && job.isCompleted) {
            serverJob = null; primarySettled.value = true; publishSources(); _paginationRevision.update { it + 1 }
        } }
        loaded
    }
    suspend fun loadProfileShelf(force: Boolean = false): Boolean = withContext(AppDispatchers.HomeBookkeeping) {
        profileRestored.await()
        while (true) {
            val restoring = profileCacheRestore
            restoring.await()
            if (restoring === profileCacheRestore) break
        }
        if (!force && fresh(profileSuccess, profileFetchedAt, _profileShelf.value == null)) return@withContext true
        profileWorkReady.first { it }
        profileJob?.takeIf { it.isActive }?.let { return@withContext it.await() }
        val now = System.currentTimeMillis()
        if (!force && profileAttemptAt?.let { now - it in 0 until 60_000L } == true) return@withContext false
        profileAttemptAt = now
        val key = profileKey; val generation = profileGeneration; val profile = _userProfile.value; val rotation = profileRotation++
        val job = owner.async {
            try {
                val result = com.example.recommendation.DiscoveryRequestCoordinator.withPriority(MutableStateFlow(false), awaitFrameQuiet = false) {
                    BackgroundWorkCoordinator.awaitOptionalBatch()
                    val response = BackgroundWorkCoordinator.traceWork("Home profile discovery") {
                        withContext(AppDispatchers.Io) { profileLoader(profile, rotation, force) }
                    }
                    // Finish transport without restarting it on input; publish after
                    // the interaction fence, with no network/database permit held.
                    BackgroundWorkCoordinator.awaitOptionalBatch()
                    response
                }
                if (generation != profileGeneration || key != profileKey || key != currentProfileKey(_userProfile.value)) return@async false
                if (result.isError) { if (_profileShelf.value == null) _profileStatus.value = ShelfLoadStatus.ERROR; return@async false }
                _profileShelf.value = result.shelf; _profileStatus.value = if (result.shelf == null) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS; profileFetchedAt = System.currentTimeMillis(); profileSuccess = true
                cache.saveProfile(snapshot()); true
            } catch (cancelled: CancellationException) {
                if (generation == profileGeneration && key == profileKey) profileAttemptAt = null
                throw cancelled
            } catch (_: Exception) { if (_profileShelf.value == null) _profileStatus.value = ShelfLoadStatus.ERROR; false }
        }
        profileJob = job
        try { job.await() } finally { if (profileJob === job && job.isCompleted) profileJob = null }
    }
    private data class ContinuationFailure(val token: String, val attempts: Int, val retryAt: Long)
    private suspend fun publishSources(urgent: Boolean = false) {
        val key = homeKey; val generation = serverGeneration
        val snapshot = sources.toMap()
        val previous = _serverShelves.value
        val inputs = (listOf("FEmusic_home") + supplementalBrowseIds).map { snapshot[it]?.shelves.orEmpty() }
        val lastInputs = projectedInputs
        val reuse = projectedKey == key && projectedGeneration == generation && lastInputs != null && lastInputs.size == inputs.size &&
            inputs.indices.all { inputs[it] === lastInputs[it] }
        val allShelves = if (reuse) projectedShelves else BackgroundWorkCoordinator.localDataPreparation(
            urgent = { urgent || _isRefreshing.value || serverUrgent.value }, awaitFrameQuiet = false) {
            withContext(AppDispatchers.CpuBounded) { BackgroundWorkCoordinator.traceWork("Home shelf projection") {
                HomeShelfIdentity.refresh(previous, inputs.flatten())
            } }
        }
        if (key != homeKey || generation != serverGeneration) return
        if (snapshot != sources.toMap()) { publishSources(urgent); return }
        if (!reuse) {
            projectedKey = key; projectedGeneration = generation; projectedInputs = inputs; projectedShelves = allShelves
            android.util.Log.d("NativeHome", "project shelves=${allShelves.size} at=${elapsedTime()}")
        }
        val shelves = if (nativeStageReadyAt.value == null && !_additionalShelvesUnlocked.value) initialHomeShelves(allShelves) else
            initialHomeShelves(allShelves).let { first -> first + allShelves.filterNot { it.id in first.map(ServerHomeShelf::id) } }
        if (_serverShelves.value.isEmpty() && shelves.isNotEmpty()) nativeRevealAt.value = elapsedTime()
        var count = maxOf(3, _revealedShelfCount.value).coerceAtMost(shelves.size)
        if (!_additionalShelvesUnlocked.value) count = minOf(3, shelves.size)
        else if (pendingNativeReveal && (!automaticRevealPending || nativePriorityAt() != null && nativeAdmissionInput == BackgroundWorkCoordinator.interactions.value) &&
            shelves.size > _revealedShelfCount.value && _revealedShelfCount.value > 0) {
            count = maxOf(3, _revealedShelfCount.value + 1).coerceAtMost(shelves.size); pendingNativeReveal = false
            nativeRevealAt.value = elapsedTime()
        } else if (pendingNativeReveal && !automaticRevealPending && shelves.size > 3) { count = 4; pendingNativeReveal = false }
        if (count > _revealedShelfCount.value && _revealedShelfCount.value < 3) nativeRevealAt.value = elapsedTime()
        _revealedShelfCount.value = count
        val pending = loadingSources.isNotEmpty() || _continuationStatus.value == ShelfLoadStatus.LOADING ||
            !serverSuccess && serverJob?.isActive == true
        _serverCards.value = presentNativeShelves(_serverCards.value, shelves,
            if (pending) ShelfLoadStatus.LOADING else ShelfLoadStatus.SUCCESS)
        _serverShelves.value = allShelves
        markInitialNativeReady()
        android.util.Log.d("NativeHome", "publish shelves=${shelves.size} revealed=${_revealedShelfCount.value} suppressed=${inputs.sumOf { it.size } - shelves.size} pending=$pending auth=${serverCacheKey.substringAfter('|') != "anonymous"} at=${elapsedTime()}")
    }
    private fun sourceFailed(browse: String, token: String, category: String = "response_or_transport") {
        val old = sourceFailures[browse]?.takeIf { it.token == token }
        val attempts = ((old?.attempts ?: 0) + 1).coerceAtMost(6)
        val backoff = (15_000L shl (attempts - 1)).coerceAtMost(300_000L)
        sourceFailures[browse] = ContinuationFailure(token, attempts, elapsedTime() + backoff)
        android.util.Log.d("NativeHome", "failure browse=$browse category=$category attempts=$attempts initial=${token == "initial"}")
    }
    private fun retryAllowed(browse: String, token: String): Boolean = sourceFailures[browse]?.takeIf { it.token == token }
        ?.let { elapsedTime() >= it.retryAt } ?: true

    private fun scheduleSupplements(force: Boolean): List<Job> {
        val requested = mutableListOf<Job>()
        supplementalBrowseIds.forEach { browse ->
            sourceJobs[browse]?.takeIf { it.isActive }?.let { running ->
                if (force) sourceUrgency[browse]?.value = true
                requested.add(running)
                return@forEach
            }
            val state = sources[browse]
            if (!force && state != null && sourceFailures[browse]?.token != "initial" && fresh(state.succeeded, state.fetchedAt, state.shelves.isEmpty())) return@forEach
            if (!force && !retryAllowed(browse, "initial")) return@forEach
            val key = homeKey; val generation = serverGeneration
            val sourceGeneration = (sourceGenerations[browse] ?: 0L) + 1
            sourceGenerations[browse] = sourceGeneration
            val urgent = MutableStateFlow(force || nativeStageReadyAt.value == null && initialHomeShelves(_serverShelves.value).size < 3)
            sourceUrgency[browse] = urgent
            val job = owner.launch {
                try {
                    restored.await()
                    if (!visible || key != homeKey || generation != serverGeneration) return@launch
                    combine(homeDrawAt, primarySettled, urgent, _isRefreshing) { drawn, primary, requested, manual ->
                        force || manual || primary && (requested || drawn != null)
                    }.first { it }
                    if (!force && !_isRefreshing.value && nativeStageReadyAt.value == null && initialHomeShelves(_serverShelves.value).size == 3) return@launch
                    if (!urgent.value && !supplementalDemand && nativeStageReadyAt.value == null) return@launch
                    sources[browse]?.let { if (!urgent.value && sourceFailures[browse]?.token != "initial" && fresh(it.succeeded, it.fetchedAt, it.shelves.isEmpty())) return@launch }
                    // Existing optional admission is interaction-aware and bounds
                    // native supplements separately from required Home/playback.
                    val result = com.example.recommendation.DiscoveryRequestCoordinator.withPriority(urgent, awaitFrameQuiet = false) {
                        BackgroundWorkCoordinator.awaitOptionalBatch()
                        withContext(AppDispatchers.HomeBookkeeping) { loadingSources.add(browse); publishSources() }
                        withContext(AppDispatchers.Io) { supplementalLoader(HomeCacheIdentity.region(_userProfile.value), browse,
                            key.substringAfter('|') != "anonymous") }
                    }
                    ensureActive()
                    if (key != homeKey || generation != serverGeneration || sourceGenerations[browse] != sourceGeneration) return@launch
                    val account = result.accountIdentity ?: key.substringAfter('|')
                    if (result.isError) { sourceFailed(browse, "initial"); return@launch }
                    if (account != serverCacheKey.substringAfter('|')) {
                        if (account == "anonymous" && !serverSuccess && sources.values.none { it.shelves.isNotEmpty() }) {
                            sources.clear(); serverCacheKey = HomeCacheIdentity.home(_userProfile.value, "anonymous")
                        } else { sourceFailed(browse, "initial", "account_mismatch"); return@launch }
                    }
                    val previousSource = sources[browse]
                    val shelves = withContext(AppDispatchers.CpuBounded) { refreshServerShelves(previousSource?.shelves.orEmpty(), result.shelves) }
                    if (key != homeKey || generation != serverGeneration || account != serverCacheKey.substringAfter('|')) return@launch
                    sources[browse] = HomeBrowseSnapshot(browse, account, shelves, result.continuationToken,
                        fetchedAt = System.currentTimeMillis(), succeeded = true)
                    sourceFailures.remove(browse)
                    loadingSources.remove(browse)
                    publishSources()
                    persistServerCache()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (key == homeKey && generation == serverGeneration) sourceFailed(browse, "initial") }
                finally { if (sourceGenerations[browse] == sourceGeneration && generation == serverGeneration) {
                    sourceJobs.remove(browse); sourceUrgency.remove(browse); loadingSources.remove(browse)
                    withContext(NonCancellable) { publishSources() }
                    _paginationRevision.update { it + 1 }
                } }
            }
            sourceJobs[browse] = job
            requested.add(job)
        }
        return requested
    }
    fun revealNextHomeSection() {
        // A gesture can wake a retryable source, but never bypass idle admission.
        owner.launch { nativeEndDemand = true; _paginationRevision.update { it + 1 } }
    }
    /** HomeBookkeeping-confined advance after the caller has obtained idle admission. */
    internal suspend fun revealAdmittedNativeShelf() {
        restored.await()
        supplementalDemand = true
        if (_revealedShelfCount.value < _serverShelves.value.size) {
            nativeEndDemand = false // Cached content satisfies this viewport demand.
            BackgroundWorkCoordinator.traceWork("Home cached shelf reveal") {
                _revealedShelfCount.value = (_revealedShelfCount.value + 1).coerceAtMost(_serverShelves.value.size)
                android.util.Log.d("HomeWork", "cached-reveal count=${_revealedShelfCount.value} at=${elapsedTime()}")
            }
            pendingNativeReveal = false
            nativeRevealAt.value = elapsedTime()
            _paginationRevision.update { it + 1 }
        } else {
            nativeEndDemand = false
            pendingNativeReveal = true; automaticRevealPending = true
            if (!requestMoreHome(urgent = false)) scheduleSupplements(false)
        }
    }
    private fun hasAutomaticNativeWork(): Boolean {
        if (_revealedShelfCount.value < _serverShelves.value.size) return true
        if (!nativeEndDemand) return false
        if (_serverShelves.value.isEmpty() && !serverSuccess || serverJob?.isActive == true || nativePageJob?.isActive == true || sourceJobs.values.any { it.isActive }) return false
        return sources.any { (browse, state) ->
            state.continuation?.let { state.emptyPages < 2 && it !in state.consumed && retryAllowed(browse, it) } == true
        } || supplementalBrowseIds.any { browse ->
            val state = sources[browse]
            (state == null || !fresh(state.succeeded, state.fetchedAt, state.shelves.isEmpty())) && retryAllowed(browse, "initial")
        }
    }
    /** Only the completed sequential startup admits automatic native expansion. */
    private fun nativePriorityAt(): Long? = nativeStageReadyAt.value

    private fun requestMoreHome(urgent: Boolean = true): Boolean {
        if (!_additionalShelvesUnlocked.value || !pendingNativeReveal) return false
        if (nativePageJob?.isActive == true) return true
        val selected = (paginationOrder.indices).firstOrNull { offset ->
            val id = paginationOrder[(paginationTurn + offset) % paginationOrder.size]
            val state = sources[id]
            val token = state?.continuation
            state != null && token != null && state.emptyPages < 2 && token !in state.consumed &&
                sourceJobs[id]?.isActive != true && (id != "FEmusic_home" || serverJob?.isActive != true) && retryAllowed(id, token)
        } ?: return false
        val index = (paginationTurn + selected) % paginationOrder.size
        val browse = paginationOrder[index]
        paginationTurn = (index + 1) % paginationOrder.size
        val sourceGeneration = (sourceGenerations[browse] ?: 0L) + 1
        sourceGenerations[browse] = sourceGeneration
        sourceUrgency[browse] = MutableStateFlow(urgent)
        val job = owner.launch { loadContinuation(browse, sourceGeneration) }
        nativePageJob = job
        sourceJobs[browse] = job
        return true
    }
    private suspend fun loadContinuation(browse: String, sourceGeneration: Long) {
        val key = homeKey; val generation = serverGeneration
        val requestOwner = currentCoroutineContext()[Job]
        try {
            repeat(2) {
                val state = sources[browse] ?: return
                val token = state.continuation?.takeUnless { it in state.consumed } ?: return
                if (state.emptyPages >= 2 || !retryAllowed(browse, token)) return
                _continuationStatus.value = ShelfLoadStatus.LOADING
                loadingSources.add(browse)
                publishServer(_serverShelves.value, ShelfLoadStatus.LOADING)
                val result = com.example.recommendation.DiscoveryRequestCoordinator.withPriority(sourceUrgency[browse] ?: MutableStateFlow(false), awaitFrameQuiet = false) {
                    withContext(AppDispatchers.Io) {
                        val region = HomeCacheIdentity.region(_userProfile.value)
                        if (browse == "FEmusic_home") homeLoader(region, token, state.accountIdentity != "anonymous")
                        else supplementContinuationLoader(region, browse, token, state.accountIdentity != "anonymous")
                    }
                }
                currentCoroutineContext().ensureActive()
                if (generation != serverGeneration || key != homeKey || sourceGenerations[browse] != sourceGeneration || sources[browse] !== state) return
                if (result.isError || result.accountIdentity != null && result.accountIdentity != state.accountIdentity) {
                    sourceFailed(browse, token); _continuationStatus.value = ShelfLoadStatus.ERROR; return
                }
                val previousUniqueCount = _serverShelves.value.size
                val appended = withContext(AppDispatchers.CpuBounded) { appendServerShelves(state.shelves, result.shelves) }
                if (generation != serverGeneration || key != homeKey || sourceGenerations[browse] != sourceGeneration) return
                val consumed = state.consumed + token
                val changed = appended != state.shelves
                sources[browse] = state.copy(shelves = appended, consumed = consumed,
                    continuation = result.continuationToken?.takeUnless { it in consumed }, emptyPages = if (changed) 0 else state.emptyPages + 1)
                if (browse == "FEmusic_home") homeContinuationToken = sources[browse]?.continuation
                sourceFailures.remove(browse)
                loadingSources.remove(browse)
                publishSources()
                val newShelf = _serverShelves.value.size > previousUniqueCount
                val finished = newShelf || !pendingNativeReveal
                if (finished) {
                    _continuationStatus.value = ShelfLoadStatus.SUCCESS
                    sourceJobs.remove(browse); loadingSources.remove(browse)
                } else sources[browse] = sources.getValue(browse).copy(emptyPages = state.emptyPages + 1)
                persistServerCache()
                if (finished) return
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (key == homeKey && generation == serverGeneration) {
            sources[browse]?.continuation?.let { sourceFailed(browse, it) }; _continuationStatus.value = ShelfLoadStatus.ERROR
        } }
        finally {
            if (generation == serverGeneration && sourceGenerations[browse] == sourceGeneration) {
                sourceJobs.remove(browse); sourceUrgency.remove(browse); loadingSources.remove(browse)
                if (nativePageJob === requestOwner) { pendingNativeReveal = false; nativePageJob = null }
                if (_continuationStatus.value == ShelfLoadStatus.LOADING) _continuationStatus.value = ShelfLoadStatus.EMPTY
                withContext(NonCancellable) { publishSources() }
                _paginationRevision.update { it + 1 }
            }
        }
    }
    private suspend fun applyUserProfile(profile: UserProfile) {
        val nextHome = currentHomeKey(profile); val nextProfile = currentProfileKey(profile)
        _userProfile.value = profile
        if (nextHome != homeKey || nextProfile != profileKey) {
            if (corePreparationLease == null) corePreparationLease = BackgroundWorkCoordinator.coreTabsPreparation()
            initialProfileSettledAt.value = null
            profileWorkReady.value = false
            _profileShelfVisible.value = false
            _coreTabsReady.value = false; coreSettledAt = null
            if (nextHome != homeKey) {
                initialNativeReadyAt.value = null; initialHomeRenderedAt.value = null
                nativeStageReadyAt.value = null; _additionalShelvesUnlocked.value = false
            }
            startupRevision.update { it + 1 }
        }
        if (nextHome != homeKey) {
            val restoring = CompletableDeferred<Unit>().also { homeCacheRestore = it }
            try {
            serverGeneration++; serverJob?.cancel(); sourceJobs.values.toList().forEach { it.cancel() }; sourceJobs.clear(); sourceUrgency.clear(); sourceFailures.clear(); sources.clear(); paginationTurn = 0; lastConnectivityRetryAt = null; pendingNativeReveal = false; nativeEndDemand = false; primarySettled.value = false; homeKey = nextHome
            projectedInputs = null; projectedShelves = emptyList(); projectedKey = null
            serverCacheKey = nextHome
            fullServerCheckpointPending = false
            publishServer(emptyList(), ShelfLoadStatus.LOADING); homeContinuationToken = null; loadingSources.clear()
            serverFetchedAt = 0; serverSuccess = false; serverAttemptAt = null
            _refreshErrorMessage.value = null
            restoreCachedHome()
            } finally { restoring.complete(Unit) }
            if (visible) { owner.launch { loadServerHome(false) }; scheduleSupplements(false) }
        }
        if (nextProfile != profileKey) {
            val restoring = CompletableDeferred<Unit>().also { profileCacheRestore = it }
            try {
            profileGeneration++; profileJob?.cancel();  profileKey = nextProfile
            _profileShelf.value = null; profileFetchedAt = 0; profileSuccess = false; profileAttemptAt = null
            } finally { restoring.complete(Unit) }
            if (visible) requestProfileShelf(false)
        }
        // Language-only changes retain native data and its completed draw.
        markInitialNativeReady()
    }
    fun setHomeVisible(active: Boolean) {
        if (!active) setHomeScrolling(false)
        owner.launch {
            if (visible == active) return@launch
            visible = active; homeVisible.value = active
            if (active) BackgroundWorkCoordinator.interaction()
            restored.await()
            if (visible != active) return@launch
            if (active) {
                applyUserProfile(_userProfile.value)
                if (artistIdentityDirty) { artistIdentityDirty = false; artistRequests.update { it + 1 } }
                refreshHome(false)
                if (homeDrawAt.value != null) scheduleSupplements(false)
            }
            else {
                serverUrgent.value = false
                sourceJobs.values.toList().forEach { it.cancel() }; sourceJobs.clear(); sourceUrgency.clear(); loadingSources.clear()
                artistSectionRelevant.value = false
                profileGeneration++;  profileJob?.cancel(); profileAttemptAt = null
                _profileStatus.value = if (_profileShelf.value == null) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS
            }
        }
    }
    fun setHomeScrolling(active: Boolean) {
        BackgroundWorkCoordinator.scrolling(this, active)
        com.example.recommendation.DiscoveryRequestCoordinator.homeScrolling(active)
    }
    fun refreshHome(isExplicitPull: Boolean = true): Job = owner.launch {
        restored.await()
        if (isExplicitPull) { restoreRemainingNativeCache(urgent = true); _additionalShelvesUnlocked.value = true; supplementalDemand = true; pendingNativeReveal = true; automaticRevealPending = false }
        while (true) {
            val running = refreshJob?.takeIf { it.isActive } ?: break
            if (!isExplicitPull || refreshIsExplicit) { running.join(); return@launch }
            // A cache-only automatic refresh must not swallow an explicit pull. The
            // underlying request jobs are shared, so an actual in-flight fetch is reused.
            running.cancelAndJoin()
        }
        refreshIsExplicit = isExplicitPull
        refreshJob = owner.launch {
            _isRefreshing.value = isExplicitPull
            try {
                if (historyUnavailable) historyRetry.update { it + 1 }
                if (artistFailed) artistRequests.update { it + 1 }
                val supplements = scheduleSupplements(isExplicitPull)
                val primaryLoaded = loadServerHome(isExplicitPull)
                if (isExplicitPull) {
                    supplements.joinAll()
                    if (primaryLoaded) requestMoreHome()
                }
            } finally { if (isExplicitPull) nativeRevealAt.value = elapsedTime(); _isRefreshing.value = false }
        }
        refreshJob?.join()
    }
    private fun requestProfileShelf(force: Boolean) {
        profileForcePending = profileForcePending || force
        profileRequests.update { it + 1 }
    }
    fun refreshAll() = owner.launch {
        requestProfileShelf(true)
        refreshHome(true).join()
    }
    fun clearRefreshError() { _refreshErrorMessage.value = null }
    fun updateUserProfile(profile: UserProfile) { owner.launch {
        withContext(AppDispatchers.Io) { UserProfilePreferences.saveUserProfile(getApplication(), profile) }; applyUserProfile(profile)
    } }
    fun playTrack(track: MusicTrack, queue: List<MusicTrack>) = playbackManager.playTrack(track, queue)
    fun toggleFavorite(track: MusicTrack) { owner.launch { repository.toggleFavorite(track) } }
    fun computeGreeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..22 -> "Good evening"; else -> "Late Night Sessions"
    }
    override fun onCleared() {
        corePreparationLease?.close(); corePreparationLease = null
        restored.cancel(); localRestored.cancel(); profileRestored.cancel()
        homeCacheRestore.cancel(); profileCacheRestore.cancel()
        setHomeScrolling(false); cache.close(); super.onCleared()
    }
    companion object {
        fun provideFactory(repository: MusicRepository, playbackManager: PlaybackManager, application: Application, prepareCoreTabs: suspend () -> Unit = { repository.prepareStartupTabs() }): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) =
                HomeViewModel(repository, playbackManager, application, prepareCoreTabs = prepareCoreTabs) as T }
    }
}
