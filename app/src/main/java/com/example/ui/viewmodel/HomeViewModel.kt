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
    private val idleAdmission: suspend (Long) -> Unit = { BackgroundWorkCoordinator.awaitContinuousForegroundIdle(it) },
    private val elapsedTime: () -> Long = SystemClock::elapsedRealtime,
    private val supplementalLoader: suspend (String, String, Boolean) -> com.example.data.remote.YouTubeHomeParser.ParsedHomeFeed = { region, browseId, authenticated ->
        YouTubeMusicApiService.getHomeRecommendations(region, includeAccount = authenticated, browseId = browseId) },
    private val supplementContinuationLoader: suspend (String, String, String, Boolean) -> com.example.data.remote.YouTubeHomeParser.ParsedHomeFeed = { region, browse, token, authenticated ->
        YouTubeMusicApiService.getHomeRecommendations(region, continuation = token, includeAccount = authenticated, browseId = browse) }
) : AndroidViewModel(application) {
    private val owner = viewModelScope + AppDispatchers.HomeBookkeeping
    private val cache = HomeCardCache(application)
    private val database by lazy { suppliedDatabase ?: MusicDatabase.getDatabase(application) }
    private val artistHistory by lazy { LocalArtistHistory.Reader(database) }
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
    private val _revealedShelfCount = MutableStateFlow(4)
    val revealedShelfCount = _revealedShelfCount.asStateFlow()
    private val _listeningHistoryCard = MutableStateFlow(SectionState(title = "Jump Back In", subtitle = "Based on your recent listening history & frequency"))
    val listeningHistoryCard = _listeningHistoryCard.asStateFlow()
    val recentlyPlayed = _listeningHistoryCard.map { it.tracks }.stateIn(owner, SharingStarted.Eagerly, emptyList())
    val pillsCardTracks = recentlyPlayed.map { it.take(RECENTLY_PLAYED_CARD_LIMIT) }.stateIn(owner, SharingStarted.Eagerly, emptyList())
    private val _mostListenedArtists = MutableStateFlow<List<MostListenedArtist>>(emptyList())
    val mostListenedArtists = _mostListenedArtists.asStateFlow()
    private val _hasListenedArtists = MutableStateFlow(false)
    val hasListenedArtists = _hasListenedArtists.asStateFlow()
    val hasRecentlyPlayed = recentlyPlayed.map { it.isNotEmpty() }.stateIn(owner, SharingStarted.Eagerly, false)
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
    private var visible = false
    private val homeVisible = MutableStateFlow(false)
    private val localRestored = CompletableDeferred<Unit>()
    private val profileRestored = CompletableDeferred<Unit>()
    private val homeDrawAt = MutableStateFlow<Long?>(null)
    private val _optionalWorkReady = MutableStateFlow(false)
    val optionalWorkReady = _optionalWorkReady.asStateFlow()
    fun onHomeFrameDrawn() { if (homeDrawAt.value == null) homeDrawAt.value = elapsedTime() }
    private val artistSectionRelevant = MutableStateFlow(false)
    fun setArtistSectionRelevant(active: Boolean) { artistSectionRelevant.value = active }
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
    private val sourceJobs = mutableMapOf<String, Job>()
    private val sourceGenerations = mutableMapOf<String, Long>()
    private val sourceFailures = mutableMapOf<String, ContinuationFailure>()
    private val loadingSources = mutableSetOf<String>()
    private val _paginationRevision = MutableStateFlow(0L)
    val paginationRevision = _paginationRevision.asStateFlow()
    private var refreshJob: Job? = null
    private var refreshIsExplicit = false
    private val artistRequests = MutableStateFlow(0L)
    private var completedArtistRequest = 0L
    private var artistRevision: String? = null
    private var cachedArtistBaseline: Pair<String, List<MostListenedArtist>>? = null
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
            combine(homeVisible, homeDrawAt) { active, drawn -> active && drawn != null }
                .distinctUntilChanged().collect { eligible -> if (eligible) scheduleSupplements(false) }
        }
        owner.launch {
            combine(homeVisible, homeDrawAt, BackgroundWorkCoordinator.uiForeground, BackgroundWorkCoordinator.interactions) {
                active, drawn, foreground, _ -> drawn.takeIf { active && foreground }
            }.collectLatest { drawn ->
                _optionalWorkReady.value = false
                if (drawn != null) {
                    idleAdmission(drawn)
                    _optionalWorkReady.value = true
                }
            }
        }
        owner.launch {
            combine(_optionalWorkReady, profileRequests) { ready, request -> request.takeIf { ready } }
                .collectLatest { request ->
                    if (request == null) return@collectLatest
                    try { if (loadProfileShelf(profileForcePending)) profileForcePending = false }
                    finally { if (!_optionalWorkReady.value) profileJob?.cancelAndJoin() }
                }
        }
        owner.launch {
            try { restoreCachedHome() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing cache never blocks the native request. */ }
            finally { restored.complete(Unit) }
        }
        owner.launch {
            try { cache.readProfile(profileKey)?.let(::restoreProfile) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Profile discovery remains independent. */ }
            finally { profileRestored.complete(Unit) }
        }
        owner.launch {
            try { cache.readLocal(homeKey)?.let { saved ->
                _listeningHistoryCard.update { it.copy(tracks = saved.history.tracks, isLoaded = saved.history.tracks.isNotEmpty()) }
                _mostListenedArtists.value = saved.artists.take(15)
                _hasListenedArtists.value = saved.artists.isNotEmpty()
                _artistStatus.value = if (saved.artists.isEmpty()) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS
                artistRevision = saved.inputRevision.takeIf { saved.artistDataVersion == 2 }
                cachedArtistBaseline = artistRevision?.let { it to _mostListenedArtists.value }
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Local Room data can still be loaded on visibility. */ }
            finally { localRestored.complete(Unit) }
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
            combine(historyRetry, homeVisible) { retry, active -> retry.takeIf { active } }
                .distinctUntilChanged().flatMapLatest { retry -> if (retry == null) emptyFlow() else flow {
                    emitAll(database.listeningEventDao().observeActualPlaySnapshot().distinctUntilChanged(::sameLocalHomeSnapshot))
                }.catch { error ->
                    if (error is CancellationException) throw error
                    historyUnavailable = true
                    _userMode.value = HomeUserMode.UNAVAILABLE
                    _artistStatus.value = ShelfLoadStatus.ERROR
                } }.collectLatest { rowsSnapshot ->
                    try {
                        val metadataRevision = rowsSnapshot.firstOrNull()?.trackMetadataRevision
                        val metadataChanged = metadataRevision == null || metadataRevision != recentMetadataRevision
                        val representativeRevision = rowsSnapshot.firstOrNull()?.artistMetadataRevision
                        val representativesChanged = representativeRevision == null || representativeRevision != artistMetadataRevision
                        val previousIds = recentTrackIds
                        val (ids, playedIds, missingIds) = BackgroundWorkCoordinator.localDataPreparation { withContext(AppDispatchers.CpuBounded) {
                            val events = rowsSnapshot.mapNotNull { it.event }
                            val ids = events.map { it.trackId }.distinct()
                            val missing = if (metadataChanged) ids else if (ids == previousIds) emptyList()
                                else previousIds.toHashSet().let { known -> ids.filterNot(known::contains) }
                            Triple(ids, events.asSequence().filter(HomeRecommendationPolicy::played)
                                .map { it.trackId }.distinct().toList(), missing)
                        } }
                        val revision = rowsSnapshot.firstOrNull()?.artistInputRevision
                        val reuseMetadata = !metadataChanged && missingIds.isEmpty()
                        val tracks = if (reuseMetadata && ids.size == previousIds.size) recentMetadata else {
                            val records = if (missingIds.isEmpty()) emptyList() else withContext(AppDispatchers.Database) {
                                missingIds.chunked(900).flatMap { database.musicDao().getTracksByIds(it) }
                            }
                            BackgroundWorkCoordinator.localDataPreparation { withContext(AppDispatchers.CpuBounded) {
                                val existing = if (metadataChanged) emptyList() else ids.toHashSet().let { wanted -> recentMetadata.filter { it.id in wanted } }
                                existing + records.map { it.toMusicTrack() }
                            } }
                        }
                        // The order/actual-audio predicate can change without track metadata
                        // changing (for example a zero-time occurrence starts playing).
                        val recent = if (reuseMetadata && playedIds == recentPlayedIds) _listeningHistoryCard.value.tracks
                        else BackgroundWorkCoordinator.localDataPreparation { withContext(AppDispatchers.CpuBounded) {
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
                        if (historyChanged) cache.saveLocal(snapshot())
                        if (artistRevision == null || artistRevision != revision || representativesChanged) {
                            artistRequests.update { it + 1 }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        historyUnavailable = true
                        _userMode.value = HomeUserMode.UNAVAILABLE
                        _artistStatus.value = ShelfLoadStatus.ERROR
                    }
                }
        }
        owner.launch {
            localRestored.await()
            combine(artistRequests, _optionalWorkReady, homeVisible) { revision, idle, active ->
                if (idle && active) revision else 0L
            }.distinctUntilChanged().collectLatest { request ->
                if (request == 0L || request == completedArtistRequest) return@collectLatest
                try {
                    cachedArtistBaseline?.let { (revision, artists) ->
                        withContext(AppDispatchers.CpuBounded) { artistHistory.restore(revision, artists) }
                        cachedArtistBaseline = null
                    }
                    val before = withContext(AppDispatchers.Database) { database.listeningEventDao().getArtistInputRevision() }
                    val artists = BackgroundWorkCoordinator.localDataPreparation { artistHistory.read(before) }
                    if (before != withContext(AppDispatchers.Database) { database.listeningEventDao().getArtistInputRevision() }) { artistRequests.update { it + 1 }; return@collectLatest }
                    _hasListenedArtists.value = artists.isNotEmpty()
                    _mostListenedArtists.value = artists
                    artistRevision = before; artistFailed = false; completedArtistRequest = request
                    cache.saveLocal(snapshot())
                    _artistStatus.value = if (artists.isEmpty()) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    artistFailed = true // Preserve the last valid cached ranking.
                    _artistStatus.value = ShelfLoadStatus.ERROR
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
    private suspend fun publishServer(shelves: List<ServerHomeShelf>, status: ShelfLoadStatus = ShelfLoadStatus.SUCCESS,
        message: String = "", revealed: Int? = null) {
        val key = homeKey; val generation = serverGeneration
        val unique = if (shelves.isEmpty() || shelves === _serverShelves.value) shelves else withContext(AppDispatchers.CpuBounded) {
            HomeShelfIdentity.deduplicate(shelves)
        }
        if (key != homeKey || generation != serverGeneration) return
        // Publish eligibility before rows so a ready shelf cannot appear hidden
        // for a frame or briefly remove its matching loading slot.
        if (revealed != null) _revealedShelfCount.value = revealed.coerceAtMost(unique.size)
        _serverCards.value = presentNativeShelves(_serverCards.value, unique, status, message)
        _serverShelves.value = unique
    }
    private fun snapshot() = SavedHomeCards(serverCacheKey, serverFetchedAt, _listeningHistoryCard.value,
        _mostListenedArtists.value.take(15), artistRevision, _serverShelves.value, _profileShelf.value, homeContinuationToken,
        profileKey, profileFetchedAt, serverSuccess, profileSuccess, sources["FEmusic_home"]?.consumed.orEmpty(),
        sourceBrowseId = "FEmusic_home", completedBrowseIds = supplementalBrowseIds.filterTo(linkedSetOf()) { sources[it]?.succeeded == true },
        browseSources = sources.values.toList())
    private suspend fun restoreHome(saved: SavedHomeCards) {
        val key = homeKey; val generation = serverGeneration
        if (homeKey != currentHomeKey(_userProfile.value) || saved.profileKey !in setOf(homeKey, HomeCacheIdentity.home(_userProfile.value, "anonymous"))) return
        publishServer(saved.serverShelves, when {
            saved.serverShelves.isNotEmpty() -> ShelfLoadStatus.SUCCESS
            saved.serverFetchSucceeded -> ShelfLoadStatus.EMPTY
            else -> ShelfLoadStatus.LOADING
        })
        if (key != homeKey || generation != serverGeneration) return
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

    }
    private suspend fun restoreCachedHome() {
        val requested = cache.readServer(homeKey)
        requested?.let { restoreHome(it) }
        if (homeKey != HomeCacheIdentity.home(_userProfile.value, "anonymous") &&
            requested?.serverFetchSucceeded != true && requested?.serverShelves.isNullOrEmpty()) {
            cache.readServer(HomeCacheIdentity.home(_userProfile.value, "anonymous"))?.let { restoreHome(it) }
        }
    }
    private fun restoreProfile(saved: SavedHomeCards) {
        if (saved.profileShelfKey != profileKey || profileKey != currentProfileKey(_userProfile.value)) return
        _profileShelf.value = saved.profileShelf; _profileStatus.value = if (saved.profileShelf == null) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS; profileFetchedAt = saved.profileFetchedAt; profileSuccess = saved.profileFetchSucceeded
    }
    private fun fresh(success: Boolean, fetchedAt: Long, empty: Boolean = false) = success &&
        System.currentTimeMillis() - fetchedAt in 0 until (if (empty) 60_000L else 10 * 60_000L)

    suspend fun loadServerHome(force: Boolean = false): Boolean = withContext(AppDispatchers.HomeBookkeeping) {
        restored.await()
        if (!force && fresh(serverSuccess, serverFetchedAt, _serverShelves.value.isEmpty()) &&
            (serverCacheKey == homeKey || serverAttemptAt != null)) {
            return@withContext true
        }
        serverJob?.takeIf { it.isActive }?.let { if (force || _serverShelves.value.isEmpty()) serverUrgent.value = true; return@withContext it.await() }
        val now = System.currentTimeMillis()
        if (!force && serverAttemptAt?.let { now - it in 0 until 60_000L } == true) return@withContext false
        serverAttemptAt = now
        val key = homeKey; val generation = serverGeneration; val region = HomeCacheIdentity.region(_userProfile.value)
        sourceJobs.remove("FEmusic_home")?.cancel()
        val sourceGeneration = (sourceGenerations["FEmusic_home"] ?: 0L) + 1
        sourceGenerations["FEmusic_home"] = sourceGeneration
        sourceFailures.remove("FEmusic_home")
        _continuationStatus.value = ShelfLoadStatus.EMPTY
        if (_serverShelves.value.isEmpty()) publishServer(emptyList(), ShelfLoadStatus.LOADING)
        val urgent = MutableStateFlow(force || _serverShelves.value.isEmpty()).also { serverUrgent = it }
        val job = owner.async {
            try {
                val response = com.example.recommendation.DiscoveryRequestCoordinator.withPriority(urgent) {
                    withContext(AppDispatchers.Io) { homeLoader(region, null, key.substringAfter('|') != "anonymous") }
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
                publishSources(revealNew = force)
                if (generation != serverGeneration || key != homeKey) return@async false
                if (response.shelves.isEmpty()) _refreshErrorMessage.value = "No recommendations are available. Tap to retry."
                cache.saveHome(snapshot()); true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _refreshErrorMessage.value = "Recommendations are temporarily unavailable. Tap to retry."
                publishSources()
                false
            }
        }
        serverJob = job
        val loaded = try { job.await() } finally { if (serverJob === job && job.isCompleted) {
            serverJob = null; publishSources()
        } }
        loaded
    }
    suspend fun loadProfileShelf(force: Boolean = false): Boolean = withContext(AppDispatchers.HomeBookkeeping) {
        profileRestored.await()
        if (!force && fresh(profileSuccess, profileFetchedAt, _profileShelf.value == null)) return@withContext true
        _optionalWorkReady.first { it }
        profileJob?.takeIf { it.isActive }?.let { return@withContext it.await() }
        val now = System.currentTimeMillis()
        if (!force && profileAttemptAt?.let { now - it in 0 until 60_000L } == true) return@withContext false
        profileAttemptAt = now
        val key = profileKey; val generation = profileGeneration; val profile = _userProfile.value; val rotation = profileRotation++
        val job = owner.async {
            try {
                val result = BackgroundWorkCoordinator.optionalProcessing {
                    BackgroundWorkCoordinator.awaitOptionalBatch()
                    withContext(AppDispatchers.Io) { profileLoader(profile, rotation, force) }
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
    private suspend fun publishSources(revealNew: Boolean = false) {
        val key = homeKey; val generation = serverGeneration
        val snapshot = sources.toMap()
        val previous = _serverShelves.value
        val rows = (listOf("FEmusic_home") + supplementalBrowseIds).flatMap { snapshot[it]?.shelves.orEmpty() }
        val shelves = withContext(AppDispatchers.CpuBounded) { HomeShelfIdentity.refresh(previous, rows) }
        if (key != homeKey || generation != serverGeneration) return
        if (snapshot != sources.toMap()) { publishSources(revealNew); return }
        if (shelves != previous) android.util.Log.d("NativeHome", "publish shelves=${shelves.size} suppressed=${rows.size - shelves.size}")
        _revealedShelfCount.value = maxOf(4, _revealedShelfCount.value +
            if (revealNew) (shelves.size - previous.size).coerceAtLeast(0) else 0).coerceAtMost(shelves.size)
        val pending = loadingSources.isNotEmpty() || _continuationStatus.value == ShelfLoadStatus.LOADING ||
            !serverSuccess && serverJob?.isActive == true
        _serverCards.value = presentNativeShelves(_serverCards.value, shelves,
            if (pending) ShelfLoadStatus.LOADING else ShelfLoadStatus.SUCCESS)
        _serverShelves.value = shelves
    }
    private fun sourceFailed(browse: String, token: String, category: String = "response_or_transport") {
        val old = sourceFailures[browse]?.takeIf { it.token == token }
        val attempts = (old?.attempts ?: 0) + 1
        sourceFailures[browse] = ContinuationFailure(token, attempts, elapsedTime() + (15_000L shl (attempts - 1).coerceAtMost(2)))
        android.util.Log.d("NativeHome", "failure browse=$browse category=$category attempts=$attempts initial=${token == "initial"}")
    }
    private fun retryAllowed(browse: String, token: String): Boolean = sourceFailures[browse]?.takeIf { it.token == token }
        ?.let { it.attempts < 3 && elapsedTime() >= it.retryAt } ?: true

    private fun scheduleSupplements(force: Boolean) {
        supplementalBrowseIds.forEach { browse ->
            if (sourceJobs[browse]?.isActive == true) return@forEach
            val state = sources[browse]
            if (!force && state != null && fresh(state.succeeded, state.fetchedAt, state.shelves.isEmpty())) return@forEach
            if (!force && !retryAllowed(browse, "initial")) return@forEach
            val key = homeKey; val generation = serverGeneration
            val sourceGeneration = (sourceGenerations[browse] ?: 0L) + 1
            sourceGenerations[browse] = sourceGeneration
            sourceJobs[browse] = owner.launch {
                try {
                    restored.await()
                    homeDrawAt.first { it != null }
                    if (!visible || key != homeKey || generation != serverGeneration) return@launch
                    sources[browse]?.let { if (!force && fresh(it.succeeded, it.fetchedAt, it.shelves.isEmpty())) return@launch }
                    // Existing optional admission is interaction-aware and bounds
                    // native supplements separately from required Home/playback.
                    val result = BackgroundWorkCoordinator.optionalProcessing {
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
                    publishSources(revealNew = true)
                    cache.saveHome(snapshot())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (key == homeKey && generation == serverGeneration) sourceFailed(browse, "initial") }
                finally { if (sourceGenerations[browse] == sourceGeneration && generation == serverGeneration) {
                    sourceJobs.remove(browse); loadingSources.remove(browse)
                    withContext(NonCancellable) { publishSources() }
                } }
            }
        }
    }
    fun revealNextHomeSection() {
        owner.launch {
            restored.await()
            if (_revealedShelfCount.value < _serverShelves.value.size) {
                _revealedShelfCount.value = (_revealedShelfCount.value + 2).coerceAtMost(_serverShelves.value.size)
                _paginationRevision.update { it + 1 }
            } else requestMoreHome()
        }
    }
    private fun requestMoreHome() {
        val browse = (listOf("FEmusic_home") + supplementalBrowseIds).firstOrNull { id ->
            val state = sources[id]
            val token = state?.continuation
            state != null && token != null && state.emptyPages < 2 && token !in state.consumed &&
                sourceJobs[id]?.isActive != true && (id != "FEmusic_home" || serverJob?.isActive != true) && retryAllowed(id, token)
        } ?: return
        val sourceGeneration = (sourceGenerations[browse] ?: 0L) + 1
        sourceGenerations[browse] = sourceGeneration
        sourceJobs[browse] = owner.launch { loadContinuation(browse, sourceGeneration) }
    }
    private suspend fun loadContinuation(browse: String, sourceGeneration: Long) {
        val key = homeKey; val generation = serverGeneration
        var addedContent = false
        try {
            repeat(2) {
                val state = sources[browse] ?: return
                val token = state.continuation?.takeUnless { it in state.consumed } ?: return
                if (state.emptyPages >= 2 || !retryAllowed(browse, token)) return
                _continuationStatus.value = ShelfLoadStatus.LOADING
                loadingSources.add(browse)
                publishServer(_serverShelves.value, ShelfLoadStatus.LOADING)
                val result = com.example.recommendation.DiscoveryRequestCoordinator.withPriority(MutableStateFlow(true)) {
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
                val appended = withContext(AppDispatchers.CpuBounded) { appendServerShelves(state.shelves, result.shelves) }
                if (generation != serverGeneration || key != homeKey || sourceGenerations[browse] != sourceGeneration) return
                val consumed = state.consumed + token
                val changed = appended != state.shelves
                sources[browse] = state.copy(shelves = appended, consumed = consumed,
                    continuation = result.continuationToken?.takeUnless { it in consumed }, emptyPages = if (changed) 0 else state.emptyPages + 1)
                if (browse == "FEmusic_home") homeContinuationToken = sources[browse]?.continuation
                sourceFailures.remove(browse)
                loadingSources.remove(browse)
                _continuationStatus.value = ShelfLoadStatus.SUCCESS
                publishSources(revealNew = true)
                // Release only this source's owner; persistence is already checkpointed.
                if (changed) { addedContent = true; sourceJobs.remove(browse); loadingSources.remove(browse) }
                cache.saveHome(snapshot())
                if (changed) return
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (key == homeKey && generation == serverGeneration) {
            sources[browse]?.continuation?.let { sourceFailed(browse, it) }; _continuationStatus.value = ShelfLoadStatus.ERROR
        } }
        finally {
            if (generation == serverGeneration && sourceGenerations[browse] == sourceGeneration) {
                sourceJobs.remove(browse); loadingSources.remove(browse)
                if (_continuationStatus.value == ShelfLoadStatus.LOADING) _continuationStatus.value = ShelfLoadStatus.EMPTY
                withContext(NonCancellable) { publishSources() }
                if (addedContent) _paginationRevision.update { it + 1 }
            }
        }
    }
    private suspend fun applyUserProfile(profile: UserProfile) {
        val nextHome = currentHomeKey(profile); val nextProfile = currentProfileKey(profile)
        _userProfile.value = profile
        if (nextHome != homeKey) {
            serverGeneration++; serverJob?.cancel(); sourceJobs.values.toList().forEach { it.cancel() }; sourceJobs.clear(); sourceFailures.clear(); sources.clear(); homeKey = nextHome
            serverCacheKey = nextHome
            publishServer(emptyList(), ShelfLoadStatus.LOADING); homeContinuationToken = null; loadingSources.clear()
            serverFetchedAt = 0; serverSuccess = false; serverAttemptAt = null
            _refreshErrorMessage.value = null
            restoreCachedHome()
            if (visible) { owner.launch { loadServerHome(false) }; scheduleSupplements(false) }
        }
        if (nextProfile != profileKey) {
            profileGeneration++; profileJob?.cancel();  profileKey = nextProfile
            _profileShelf.value = null; profileFetchedAt = 0; profileSuccess = false; profileAttemptAt = null
            cache.readProfile(nextProfile)?.let(::restoreProfile)
            if (visible) requestProfileShelf(false)
        }
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
                sourceJobs.values.toList().forEach { it.cancel() }; sourceJobs.clear(); loadingSources.clear()
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
        requestProfileShelf(isExplicitPull)
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
                scheduleSupplements(isExplicitPull)
                if (loadServerHome(isExplicitPull) && isExplicitPull) requestMoreHome()
            } finally { _isRefreshing.value = false }
        }
        refreshJob?.join()
    }
    private fun requestProfileShelf(force: Boolean) {
        profileForcePending = profileForcePending || force
        profileRequests.update { it + 1 }
    }
    fun refreshAll() = refreshHome(true)
    fun clearRefreshError() { _refreshErrorMessage.value = null }
    fun updateUserProfile(profile: UserProfile) { owner.launch {
        withContext(AppDispatchers.Io) { UserProfilePreferences.saveUserProfile(getApplication(), profile) }; applyUserProfile(profile)
    } }
    fun playTrack(track: MusicTrack, queue: List<MusicTrack>) = playbackManager.playTrack(track, queue)
    fun toggleFavorite(track: MusicTrack) { owner.launch { repository.toggleFavorite(track) } }
    fun computeGreeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..22 -> "Good evening"; else -> "Late Night Sessions"
    }
    override fun onCleared() { setHomeScrolling(false); cache.close(); super.onCleared() }
    companion object {
        fun provideFactory(repository: MusicRepository, playbackManager: PlaybackManager, application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) =
                HomeViewModel(repository, playbackManager, application) as T }
    }
}
