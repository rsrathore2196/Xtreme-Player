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

/** Retains the existing server feed; local playback records never trigger server recommendations. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val repository: MusicRepository, private val playbackManager: PlaybackManager,
    application: Application, private val suppliedDatabase: MusicDatabase? = null,
    private val homeLoader: suspend (String, String?, Boolean) -> com.example.data.remote.YouTubeHomeParser.ParsedHomeFeed = { region, token, authenticated ->
        YouTubeMusicApiService.getHomeRecommendations(region, continuation = token, includeAccount = authenticated) },
    private val profileLoader: suspend (UserProfile, Int, Boolean) -> YouTubeMusicApiService.ProfileShelfResult = { profile, rotation, force ->
        YouTubeMusicApiService.getProfileShelfResult(HomeCacheIdentity.region(profile), profile.languages, rotation, force) },
    private val accountIdentity: () -> String = YouTubeMusicApiService::discoveryAccountIdentity,
    private val accountUpdates: Flow<*> = com.example.data.remote.YouTubeAudioStreamService.discoveryAccountChanges
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
    private val artistSectionRelevant = MutableStateFlow(false)
    fun setArtistSectionRelevant(active: Boolean) { artistSectionRelevant.value = active }
    private var artistIdentityDirty = false
    private var serverGeneration = 0L
    private var profileGeneration = 0L
    private var serverFetchedAt = 0L
    private var profileFetchedAt = 0L
    private var serverSuccess = false
    private var profileSuccess = false
    private var serverAttemptAt = 0L
    private var profileAttemptAt = 0L
    private var profileRotation = 0
    private var homeContinuationToken: String? = null
    private val consumedContinuations = linkedSetOf<String>()
    private var serverJob: Deferred<Boolean>? = null
    private var serverUrgent = MutableStateFlow(false)
    private var profileJob: Deferred<Boolean>? = null
    private var profileRequestJob: Job? = null
    private var profileRequestIsExplicit = false
    private var pageJob: Job? = null
    private var expansionJob: Job? = null
    private var expansionUrgent = MutableStateFlow(false)
    private var refreshJob: Job? = null
    private var refreshIsExplicit = false
    private val artistRequests = MutableStateFlow(0L)
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
            combine(artistRequests, artistSectionRelevant, homeVisible) { revision, relevant, active ->
                if (relevant && active) revision else 0L
            }.distinctUntilChanged().collectLatest { request ->
                if (request == 0L) return@collectLatest
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
                    artistRevision = before; artistFailed = false
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
    private fun publishServer(shelves: List<ServerHomeShelf>, status: ShelfLoadStatus = ShelfLoadStatus.SUCCESS, message: String = "") {
        _serverCards.value = presentNativeShelves(_serverCards.value, shelves, status, message)
        _serverShelves.value = shelves
    }
    private fun snapshot() = SavedHomeCards(serverCacheKey, serverFetchedAt, _listeningHistoryCard.value,
        _mostListenedArtists.value.take(15), artistRevision, _serverShelves.value, _profileShelf.value, homeContinuationToken,
        profileKey, profileFetchedAt, serverSuccess, profileSuccess, consumedContinuations.toSet())
    private fun restoreHome(saved: SavedHomeCards) {
        if (homeKey != currentHomeKey(_userProfile.value) || saved.profileKey !in setOf(homeKey, HomeCacheIdentity.home(_userProfile.value, "anonymous"))) return
        publishServer(saved.serverShelves, when {
            saved.serverShelves.isNotEmpty() -> ShelfLoadStatus.SUCCESS
            saved.serverFetchSucceeded -> ShelfLoadStatus.EMPTY
            else -> ShelfLoadStatus.LOADING
        }); homeContinuationToken = saved.continuationToken
        serverCacheKey = saved.profileKey
        serverFetchedAt = saved.refreshedAt
        // Older snapshots intentionally omitted collection shelves. Keep their
        // usable cards visible, but refresh that incomplete representation once.
        serverSuccess = saved.serverFetchSucceeded && saved.serverContentVersion == SERVER_HOME_CONTENT_VERSION
        consumedContinuations.clear(); consumedContinuations.addAll(saved.consumedContinuations)

    }
    private suspend fun restoreCachedHome() {
        val requested = cache.readServer(homeKey)
        requested?.let(::restoreHome)
        if (homeKey != HomeCacheIdentity.home(_userProfile.value, "anonymous") &&
            requested?.serverFetchSucceeded != true && requested?.serverShelves.isNullOrEmpty()) {
            cache.readServer(HomeCacheIdentity.home(_userProfile.value, "anonymous"))?.let(::restoreHome)
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
            (serverCacheKey == homeKey || serverAttemptAt > 0L)) {
            if (visible) expandServerHome()
            return@withContext true
        }
        serverJob?.takeIf { it.isActive }?.let { if (force || _serverShelves.value.isEmpty()) serverUrgent.value = true; return@withContext it.await() }
        val now = System.currentTimeMillis()
        if (!force && now - serverAttemptAt in 0 until 60_000L) return@withContext false
        serverAttemptAt = now
        val key = homeKey; val generation = ++serverGeneration; val region = HomeCacheIdentity.region(_userProfile.value)
        expansionJob?.cancel(); pageJob?.cancel(); _continuationStatus.value = ShelfLoadStatus.EMPTY
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
                if (responseAccount != "anonymous" && responseAccount != key.substringAfter('|')) return@async false
                val responseKey = HomeCacheIdentity.home(_userProfile.value, responseAccount)
                val previous = _serverShelves.value.takeIf { serverSuccess && serverCacheKey == responseKey }.orEmpty()
                val shelves = BackgroundWorkCoordinator.localDataPreparation {
                    withContext(AppDispatchers.CpuBounded) { refreshServerShelves(previous, response.shelves) }
                }
                if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value)) return@async false
                serverCacheKey = responseKey
                serverFetchedAt = System.currentTimeMillis(); serverSuccess = true
                homeContinuationToken = response.continuationToken
                consumedContinuations.clear()
                _refreshErrorMessage.value = null
                publishServer(shelves, if (shelves.isEmpty()) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS)
                _revealedShelfCount.value = maxOf(4, _revealedShelfCount.value).coerceAtMost(shelves.size)
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
        if (loaded && visible) expandServerHome()
        loaded
    }
    suspend fun loadProfileShelf(force: Boolean = false): Boolean = withContext(AppDispatchers.HomeBookkeeping) {
        profileRestored.await()
        if (!force && fresh(profileSuccess, profileFetchedAt, _profileShelf.value == null)) return@withContext true
        profileJob?.takeIf { it.isActive }?.let { return@withContext it.await() }
        val now = System.currentTimeMillis()
        if (!force && now - profileAttemptAt in 0 until 60_000L) return@withContext false
        profileAttemptAt = now
        val key = profileKey; val generation = profileGeneration; val profile = _userProfile.value; val rotation = profileRotation++
        val job = owner.async {
            try {
                val result = BackgroundWorkCoordinator.optionalProcessing {
                    BackgroundWorkCoordinator.awaitOptionalBatch()
                    if (_profileShelf.value == null) _profileStatus.value = ShelfLoadStatus.LOADING
                    withContext(AppDispatchers.Io) { profileLoader(profile, rotation, force) }
                }
                if (generation != profileGeneration || key != profileKey || key != currentProfileKey(_userProfile.value)) return@async false
                if (result.isError) { if (_profileShelf.value == null) _profileStatus.value = ShelfLoadStatus.ERROR; return@async false }
                _profileShelf.value = result.shelf; _profileStatus.value = if (result.shelf == null) ShelfLoadStatus.EMPTY else ShelfLoadStatus.SUCCESS; profileFetchedAt = System.currentTimeMillis(); profileSuccess = true
                cache.saveProfile(snapshot()); true
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { if (_profileShelf.value == null) _profileStatus.value = ShelfLoadStatus.ERROR; false }
        }
        profileJob = job
        try { job.await() } finally { if (profileJob === job && job.isCompleted) profileJob = null }
    }
    fun revealNextHomeSection() {
        owner.launch {
            restored.await()
            if (_revealedShelfCount.value < _serverShelves.value.size) {
                _revealedShelfCount.value = (_revealedShelfCount.value + 2).coerceAtMost(_serverShelves.value.size); return@launch
            }
            if (pageJob?.isActive == true) return@launch
            pageJob = owner.launch { loadContinuation() }
        }
    }
    /** One optional, cancellable expansion owner; no artwork is mounted by prefetching. */
    private fun expandServerHome(explicit: Boolean = false) {
        expansionJob?.takeIf { it.isActive }?.let {
            if (explicit) expansionUrgent.value = true
            return
        }
        if (homeContinuationToken == null || !visible && !explicit) return
        val key = homeKey; val generation = serverGeneration
        val urgent = MutableStateFlow(explicit).also { expansionUrgent = it }
        expansionJob = owner.launch {
            BackgroundWorkCoordinator.optionalProcessing(urgent = { urgent.value }) {
                repeat(BACKGROUND_HOME_PAGE_LIMIT) {
                    BackgroundWorkCoordinator.awaitOptionalBatch()
                    if (key != homeKey || generation != serverGeneration || !visible && !urgent.value) return@optionalProcessing
                    if (!loadContinuation(urgent = urgent, reveal = false)) return@optionalProcessing
                }
            }
        }
    }

    private suspend fun loadContinuation(urgent: MutableStateFlow<Boolean> = MutableStateFlow(false),
        reveal: Boolean = true): Boolean {
        val token = homeContinuationToken?.takeUnless { it in consumedContinuations } ?: return false
        val requestOwner = currentCoroutineContext()[Job] ?: return false
        pageJob?.takeIf { it !== requestOwner && it.isActive }?.let { it.join(); return homeContinuationToken != token }
        pageJob = requestOwner
        val key = homeKey; val generation = serverGeneration
        val responseAccount = serverCacheKey.substringAfter('|')
        try {
            if (reveal) _continuationStatus.value = ShelfLoadStatus.LOADING
            suspend fun request() = withContext(AppDispatchers.Io) {
                homeLoader(HomeCacheIdentity.region(_userProfile.value), token, responseAccount != "anonymous")
            }
            val result = if (!reveal)
                com.example.recommendation.DiscoveryRequestCoordinator.withPriority(urgent) { request() }
            else BackgroundWorkCoordinator.localDataPreparation { request() }
            if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value) || token != homeContinuationToken) return false
            if (result.accountIdentity != null && result.accountIdentity != responseAccount) return false
            if (result.isError) { _continuationStatus.value = ShelfLoadStatus.ERROR; return false } // Keep the cursor retryable.
            val previous = _serverShelves.value
            val appended = BackgroundWorkCoordinator.localDataPreparation {
                withContext(AppDispatchers.CpuBounded) { appendServerShelves(previous, result.shelves) }
            }
            if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value) ||
                token != homeContinuationToken || previous !== _serverShelves.value) return false
            consumedContinuations.add(token)
            publishServer(appended)
            _continuationStatus.value = ShelfLoadStatus.SUCCESS
            homeContinuationToken = result.continuationToken?.takeUnless { it in consumedContinuations }
            if (reveal) _revealedShelfCount.value = (_revealedShelfCount.value + 2).coerceAtMost(_serverShelves.value.size)
            cache.saveHome(snapshot())
            return true
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { _continuationStatus.value = ShelfLoadStatus.ERROR; return false /* Keep the cursor and visible content. */ }
        finally { if (pageJob === requestOwner) {
            pageJob = null
            if (_continuationStatus.value == ShelfLoadStatus.LOADING) _continuationStatus.value = ShelfLoadStatus.EMPTY
        } }
    }
    private suspend fun applyUserProfile(profile: UserProfile) {
        val nextHome = currentHomeKey(profile); val nextProfile = currentProfileKey(profile)
        _userProfile.value = profile
        if (nextHome != homeKey) {
            serverGeneration++; serverJob?.cancel(); expansionJob?.cancel(); pageJob?.cancel(); homeKey = nextHome
            serverCacheKey = nextHome
            publishServer(emptyList(), ShelfLoadStatus.LOADING); homeContinuationToken = null; consumedContinuations.clear()
            serverFetchedAt = 0; serverSuccess = false; serverAttemptAt = 0
            _refreshErrorMessage.value = null
            restoreCachedHome()
            if (visible) owner.launch { loadServerHome(false) }
        }
        if (nextProfile != profileKey) {
            profileGeneration++; profileJob?.cancel(); profileRequestJob?.cancel(); profileKey = nextProfile
            _profileShelf.value = null; profileFetchedAt = 0; profileSuccess = false; profileAttemptAt = 0
            cache.readProfile(nextProfile)?.let(::restoreProfile)
            if (visible) requestProfileShelf(false)
        }
    }
    fun setHomeVisible(active: Boolean) {
        if (!active) setHomeScrolling(false)
        owner.launch {
            visible = active; homeVisible.value = active; restored.await()
            if (active) {
                applyUserProfile(_userProfile.value)
                if (artistIdentityDirty) { artistIdentityDirty = false; artistRequests.update { it + 1 } }
                refreshHome(false)
            }
            else {
                serverUrgent.value = false
                expansionJob?.cancel()
                artistSectionRelevant.value = false
                profileGeneration++; profileRequestJob?.cancel(); profileJob?.cancel(); profileAttemptAt = 0L
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
                if (loadServerHome(isExplicitPull)) expandServerHome(isExplicitPull)
            } finally { _isRefreshing.value = false }
        }
        refreshJob?.join()
    }
    private fun requestProfileShelf(force: Boolean) {
        if (profileRequestJob?.isActive == true && (!force || profileRequestIsExplicit)) return
        profileRequestJob?.cancel()
        profileRequestIsExplicit = force
        profileRequestJob = owner.launch { loadProfileShelf(force) }
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
