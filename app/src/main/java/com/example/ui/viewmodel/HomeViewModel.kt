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
        YouTubeMusicApiService.getHomeRecommendations(region, includeAccount = authenticated, browseId = browseId) }
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
    private val completedBrowseIds = linkedSetOf<String>()
    private var sourceBrowseId = "FEmusic_home"
    private var homeContinuationToken: String? = null
    private val consumedContinuations = linkedSetOf<String>()
    private var serverJob: Deferred<Boolean>? = null
    private var serverUrgent = MutableStateFlow(false)
    private var profileJob: Deferred<Boolean>? = null
    private val profileRequests = MutableStateFlow(0L)
    private var profileForcePending = false
    private var pageJob: Job? = null
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
        profileKey, profileFetchedAt, serverSuccess, profileSuccess, consumedContinuations.toSet(),
        sourceBrowseId = sourceBrowseId, completedBrowseIds = completedBrowseIds.toSet())
    private suspend fun restoreHome(saved: SavedHomeCards) {
        val key = homeKey; val generation = serverGeneration
        if (homeKey != currentHomeKey(_userProfile.value) || saved.profileKey !in setOf(homeKey, HomeCacheIdentity.home(_userProfile.value, "anonymous"))) return
        publishServer(saved.serverShelves, when {
            saved.serverShelves.isNotEmpty() -> ShelfLoadStatus.SUCCESS
            saved.serverFetchSucceeded -> ShelfLoadStatus.EMPTY
            else -> ShelfLoadStatus.LOADING
        })
        if (key != homeKey || generation != serverGeneration) return
        homeContinuationToken = saved.continuationToken
        serverCacheKey = saved.profileKey
        serverFetchedAt = saved.refreshedAt
        // Older snapshots intentionally omitted collection shelves. Keep their
        // usable cards visible, but refresh that incomplete representation once.
        serverSuccess = saved.serverFetchSucceeded && saved.serverContentVersion == SERVER_HOME_CONTENT_VERSION
        sourceBrowseId = saved.sourceBrowseId.takeIf { it == "FEmusic_home" || it in supplementalBrowseIds } ?: "FEmusic_home"
        completedBrowseIds.clear(); completedBrowseIds.addAll(saved.completedBrowseIds.filter { it in supplementalBrowseIds })
        consumedContinuations.clear(); consumedContinuations.addAll(saved.consumedContinuations.map {
            if (it.startsWith("FEmusic_") && '|' in it) it else "$sourceBrowseId|$it"
        })
        homeContinuationToken = homeContinuationToken?.takeUnless { "$sourceBrowseId|$it" in consumedContinuations }

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
        val key = homeKey; val generation = ++serverGeneration; val region = HomeCacheIdentity.region(_userProfile.value)
        pageJob?.cancel(); pageJob = null; continuationFailure = null; _continuationStatus.value = ShelfLoadStatus.EMPTY
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
                    if (_serverShelves.value.isEmpty()) publishServer(emptyList(), ShelfLoadStatus.ERROR, message)
                    return@async false
                }
                val responseAccount = response.accountIdentity ?: key.substringAfter('|')
                if (responseAccount != "anonymous" && responseAccount != key.substringAfter('|')) {
                    _refreshErrorMessage.value = "Home response account did not match the request"
                    if (_serverShelves.value.isEmpty()) publishServer(emptyList(), ShelfLoadStatus.ERROR)
                    return@async false
                }
                val responseKey = HomeCacheIdentity.home(_userProfile.value, responseAccount)
                val previous = _serverShelves.value.takeIf { serverSuccess && serverCacheKey == responseKey }.orEmpty()
                val shelves = BackgroundWorkCoordinator.localDataPreparation {
                    withContext(AppDispatchers.CpuBounded) { refreshServerShelves(previous, response.shelves) }
                }
                if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value)) return@async false
                serverCacheKey = responseKey
                serverFetchedAt = System.currentTimeMillis(); serverSuccess = true
                homeContinuationToken = response.continuationToken
                consumedContinuations.clear(); completedBrowseIds.clear(); sourceBrowseId = "FEmusic_home"
                _refreshErrorMessage.value = null
                publishServer(shelves, if (shelves.isEmpty()) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS,
                    revealed = maxOf(4, _revealedShelfCount.value))
                if (generation != serverGeneration || key != homeKey) return@async false
                if (response.shelves.isEmpty()) _refreshErrorMessage.value = "No recommendations are available. Tap to retry."
                cache.saveHome(snapshot()); true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _refreshErrorMessage.value = "Recommendations are temporarily unavailable. Tap to retry."
                if (_serverShelves.value.isEmpty()) publishServer(emptyList(), ShelfLoadStatus.ERROR, _refreshErrorMessage.value.orEmpty())
                false
            }
        }
        serverJob = job
        val loaded = try { job.await() } finally { if (serverJob === job && job.isCompleted) serverJob = null }
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
    private var continuationFailure: ContinuationFailure? = null
    private fun pageFailed(token: String, message: String = "Native Home continuation failed") {
        val attempts = if (continuationFailure?.token == token) continuationFailure!!.attempts + 1 else 1
        continuationFailure = ContinuationFailure(token, attempts,
            elapsedTime() + (15_000L shl (attempts - 1).coerceAtMost(3)))
        _continuationStatus.value = ShelfLoadStatus.ERROR
        _refreshErrorMessage.value = message
    }
    fun revealNextHomeSection() {
        owner.launch {
            restored.await()
            if (_revealedShelfCount.value < _serverShelves.value.size) {
                _revealedShelfCount.value = (_revealedShelfCount.value + 2).coerceAtMost(_serverShelves.value.size)
            } else requestMoreHome()
        }
    }
    private fun requestMoreHome() {
        if (pageJob?.isActive == true || serverJob?.isActive == true || homeContinuationToken == null && supplementalBrowseIds.all { it in completedBrowseIds }) return
        pageJob = owner.launch { loadContinuation() }
    }
    /** One bounded demand can traverse a duplicate/unsupported page, never an idle page fan-out. */
    private suspend fun loadContinuation() {
        val requestOwner = currentCoroutineContext()[Job]
        val key = homeKey; val generation = serverGeneration
        val responseAccount = serverCacheKey.substringAfter('|')
        try {
            repeat(2) {
                val token = homeContinuationToken?.takeUnless { "$sourceBrowseId|$it" in consumedContinuations }
                val browse = if (token == null) supplementalBrowseIds.firstOrNull { it !in completedBrowseIds } ?: return else sourceBrowseId
                val cursorKey = "$browse|${token ?: "initial"}"
                continuationFailure?.takeIf { it.token == cursorKey }?.let {
                    if (it.attempts >= 3 || elapsedTime() < it.retryAt) return
                }
                _continuationStatus.value = ShelfLoadStatus.LOADING
                publishServer(_serverShelves.value, ShelfLoadStatus.LOADING)
                val result = com.example.recommendation.DiscoveryRequestCoordinator.withPriority(MutableStateFlow(true)) {
                    withContext(AppDispatchers.Io) {
                        if (token == null) supplementalLoader(HomeCacheIdentity.region(_userProfile.value), browse, responseAccount != "anonymous")
                        else homeLoader(HomeCacheIdentity.region(_userProfile.value), token, responseAccount != "anonymous")
                    }
                }
                currentCoroutineContext().ensureActive()
                if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value) || token != homeContinuationToken) return
                if (result.isError || result.accountIdentity != null && result.accountIdentity != responseAccount) {
                    pageFailed(cursorKey, result.errorMessage ?: if (result.accountIdentity != null && result.accountIdentity != responseAccount)
                        "Native Home response account did not match its cursor" else "Native Home continuation failed")
                    return
                }
                val previous = _serverShelves.value
                val appended = BackgroundWorkCoordinator.localDataPreparation {
                    withContext(AppDispatchers.CpuBounded) { appendServerShelves(previous, result.shelves) }
                }
                if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value) ||
                    token != homeContinuationToken || previous !== _serverShelves.value) return
                sourceBrowseId = browse
                if (token == null) completedBrowseIds.add(browse) else consumedContinuations.add(cursorKey)
                continuationFailure = null
                homeContinuationToken = result.continuationToken?.takeUnless { "$browse|$it" in consumedContinuations }
                val added = appended.size - previous.size
                publishServer(appended, revealed = maxOf(_revealedShelfCount.value + 2,
                    if (added > 0) previous.size + minOf(2, added) else 0))
                _continuationStatus.value = ShelfLoadStatus.SUCCESS
                _refreshErrorMessage.value = null
                val finished = added > 0 || appended != previous ||
                    homeContinuationToken == null && supplementalBrowseIds.all { it in completedBrowseIds }
                // The token is committed before persistence. A new user gesture
                // can own the next token while this completed page checkpoints.
                if (finished && pageJob === requestOwner) pageJob = null
                cache.saveHome(snapshot())
                if (finished) return
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (pageJob === requestOwner && generation == serverGeneration && key == homeKey) {
            val browse = if (homeContinuationToken == null) supplementalBrowseIds.firstOrNull { it !in completedBrowseIds } ?: sourceBrowseId else sourceBrowseId
            pageFailed("$browse|${homeContinuationToken ?: "initial"}")
        } }
        finally {
            if (pageJob === requestOwner && generation == serverGeneration && key == homeKey) {
                publishServer(_serverShelves.value)
                if (_continuationStatus.value == ShelfLoadStatus.LOADING) _continuationStatus.value = ShelfLoadStatus.EMPTY
            }
            if (pageJob === requestOwner) pageJob = null
        }
    }
    private suspend fun applyUserProfile(profile: UserProfile) {
        val nextHome = currentHomeKey(profile); val nextProfile = currentProfileKey(profile)
        _userProfile.value = profile
        if (nextHome != homeKey) {
            serverGeneration++; serverJob?.cancel(); pageJob?.cancel(); continuationFailure = null; homeKey = nextHome
            serverCacheKey = nextHome
            publishServer(emptyList(), ShelfLoadStatus.LOADING); homeContinuationToken = null; consumedContinuations.clear(); completedBrowseIds.clear(); sourceBrowseId = "FEmusic_home"
            serverFetchedAt = 0; serverSuccess = false; serverAttemptAt = null
            _refreshErrorMessage.value = null
            restoreCachedHome()
            if (visible) owner.launch { loadServerHome(false) }
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
            }
            else {
                serverUrgent.value = false
                pageJob?.cancel()
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
