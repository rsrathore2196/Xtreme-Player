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
    private val localPrepared = MutableStateFlow(false)
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
    private var profileJob: Deferred<Boolean>? = null
    private var profileRequestJob: Job? = null
    private var profileRequestIsExplicit = false
    private var pageJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshIsExplicit = false
    private val artistRequests = MutableStateFlow(0L)
    private var artistRevision: String? = null
    private var historyUnavailable = false
    private var artistFailed = false
    private val historyRetry = MutableStateFlow(0L)
    private var recentMetadataRevision: String? = null
    private var recentTrackIds: List<String> = emptyList()
    private var recentMetadata: List<MusicTrack> = emptyList()
    private var historyPrepared = false
    private var artistsPrepared = false

    init {
        BackgroundWorkCoordinator.startupTask(StartupTask.FAVORITES, StartupStatus.SKIPPED)
        BackgroundWorkCoordinator.startupTask(StartupTask.INITIAL_ARTWORK, StartupStatus.SKIPPED)
        owner.launch {
            try {
                restoreCachedHome()
                cache.readProfile(profileKey)?.let(::restoreProfile)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Room and the native API remain authoritative. */ }
            finally {
                restored.complete(Unit)
                BackgroundWorkCoordinator.startupTask(StartupTask.HOME_CACHE, StartupStatus.FALLBACK_READY)
                BackgroundWorkCoordinator.startupTask(StartupTask.HERO, StartupStatus.FALLBACK_READY)
                BackgroundWorkCoordinator.cachedHomeRestored()
            }
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
            restored.await()
            combine(historyRetry, homeVisible, localPrepared) { retry, active, prepared -> retry.takeIf { active || !prepared } }
                .distinctUntilChanged().flatMapLatest { retry -> if (retry == null) emptyFlow() else flow {
                    emitAll(database.listeningEventDao().observeActualPlaySnapshot().distinctUntilChanged())
                }.catch { error ->
                    if (error is CancellationException) throw error
                    historyUnavailable = true
                    _userMode.value = HomeUserMode.UNAVAILABLE
                    finishHistoryReadiness()
                    finishArtistReadiness(StartupStatus.FALLBACK_READY)
                } }.collectLatest { rowsSnapshot ->
                    try {
                        val (events, ids) = BackgroundWorkCoordinator.localDataPreparation { withContext(AppDispatchers.CpuBounded) {
                            val events = rowsSnapshot.mapNotNull { it.event }
                            events to events.map { it.trackId }.distinct()
                        } }
                        val revision = rowsSnapshot.firstOrNull()?.artistInputRevision
                        val reuseMetadata = revision != null && revision == recentMetadataRevision && ids == recentTrackIds
                        val tracks = if (reuseMetadata) recentMetadata else {
                            val records = withContext(AppDispatchers.Database) {
                                ids.chunked(900).flatMap { database.musicDao().getTracksByIds(it) }
                            }
                            BackgroundWorkCoordinator.localDataPreparation { withContext(AppDispatchers.CpuBounded) { records.map { it.toMusicTrack() } } }
                        }
                        val recent = if (reuseMetadata) _listeningHistoryCard.value.tracks else BackgroundWorkCoordinator.localDataPreparation { withContext(AppDispatchers.CpuBounded) {
                            val byId = tracks.associateBy { it.id }; val seen = HomeRecommendationPolicy.Recordings()
                            events.asSequence().filter(HomeRecommendationPolicy::played).mapNotNull { byId[it.trackId] }
                                .filter(seen::add).take(30).toList()
                        } }
                        recentMetadataRevision = revision; recentTrackIds = ids; recentMetadata = tracks
                        historyUnavailable = false
                        _userMode.value = HomeUserMode.confirmed(rowsSnapshot.firstOrNull()?.total ?: 0, false)
                        val historyChanged = _listeningHistoryCard.value.tracks != recent
                        _listeningHistoryCard.update { it.copy(tracks = recent, isLoaded = recent.isNotEmpty(), isLoading = false) }
                        if (artistRevision == null || artistRevision != revision) {
                            BackgroundWorkCoordinator.startupTask(StartupTask.RECENT_ARTISTS, StartupStatus.RUNNING)
                            artistRequests.update { it + 1 }
                        } else {
                            if (historyChanged) cache.saveLocal(snapshot())
                            if (artistRevision != null && artistRevision == revision) finishArtistReadiness(StartupStatus.READY)
                        }
                        finishHistoryReadiness()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        historyUnavailable = true
                        _userMode.value = HomeUserMode.UNAVAILABLE
                        finishHistoryReadiness()
                        finishArtistReadiness(StartupStatus.FALLBACK_READY)
                    }
                }
        }
        owner.launch {
            restored.await()
            artistRequests.filter { it > 0 }.collectLatest {
                try {
                    val before = withContext(AppDispatchers.Database) { database.listeningEventDao().getArtistInputRevision() }
                    val artists = BackgroundWorkCoordinator.localDataPreparation { LocalArtistHistory.read(database) }
                    if (before != withContext(AppDispatchers.Database) { database.listeningEventDao().getArtistInputRevision() }) { artistRequests.update { it + 1 }; return@collectLatest }
                    _mostListenedArtists.value = artists
                    _hasListenedArtists.value = artists.isNotEmpty()
                    artistRevision = before; artistFailed = false
                    cache.saveLocal(snapshot())
                    finishArtistReadiness(StartupStatus.READY)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    artistFailed = true // Preserve the last valid cached ranking.
                    finishArtistReadiness(StartupStatus.FALLBACK_READY)
                }
            }
        }
        owner.launch {
            restored.await()
            var revision = ArtistPhotoRepository.identities.value
            ArtistPhotoRepository.identities.collect { next ->
                if (next != revision) {
                    revision = next
                    if (visible || !localPrepared.value) artistRequests.update { it + 1 }
                    else artistIdentityDirty = true
                }
            }
        }
    }
    private fun finishHistoryReadiness() {
        historyPrepared = true
        BackgroundWorkCoordinator.startupTask(StartupTask.RECENT_HISTORY, StartupStatus.FALLBACK_READY)
        if (artistsPrepared) { BackgroundWorkCoordinator.homeContentReady(); localPrepared.value = true }
    }
    private fun finishArtistReadiness(status: StartupStatus) {
        artistsPrepared = true
        BackgroundWorkCoordinator.startupTask(StartupTask.RECENT_ARTISTS, status)
        if (historyPrepared) { BackgroundWorkCoordinator.homeContentReady(); localPrepared.value = true }
    }
    private fun snapshot() = SavedHomeCards(serverCacheKey, serverFetchedAt, _listeningHistoryCard.value,
        _mostListenedArtists.value.take(15), artistRevision, _serverShelves.value, _profileShelf.value, homeContinuationToken,
        profileKey, profileFetchedAt, serverSuccess, profileSuccess, consumedContinuations.toSet())
    private fun restoreHome(saved: SavedHomeCards) {
        if (homeKey != currentHomeKey(_userProfile.value) || saved.profileKey !in setOf(homeKey, HomeCacheIdentity.home(_userProfile.value, "anonymous"))) return
        _serverShelves.value = saved.serverShelves; homeContinuationToken = saved.continuationToken
        serverCacheKey = saved.profileKey
        serverFetchedAt = saved.refreshedAt; serverSuccess = saved.serverFetchSucceeded
        consumedContinuations.clear(); consumedContinuations.addAll(saved.consumedContinuations)
        if (!restored.isCompleted) {
            _listeningHistoryCard.update { it.copy(tracks = saved.history.tracks, isLoaded = saved.history.tracks.isNotEmpty()) }
            _mostListenedArtists.value = saved.artists.take(15)
            _hasListenedArtists.value = _mostListenedArtists.value.isNotEmpty()
            artistRevision = saved.inputRevision.takeIf { saved.artistDataVersion == 2 }
        }
    }
    private suspend fun restoreCachedHome() {
        val requested = cache.read(homeKey)
        requested?.let(::restoreHome)
        if (homeKey != HomeCacheIdentity.home(_userProfile.value, "anonymous") &&
            requested?.serverFetchSucceeded != true && requested?.serverShelves.isNullOrEmpty()) {
            cache.read(HomeCacheIdentity.home(_userProfile.value, "anonymous"))?.let(::restoreHome)
        }
    }
    private fun restoreProfile(saved: SavedHomeCards) {
        if (saved.profileShelfKey != profileKey || profileKey != currentProfileKey(_userProfile.value)) return
        _profileShelf.value = saved.profileShelf; profileFetchedAt = saved.profileFetchedAt; profileSuccess = saved.profileFetchSucceeded
    }
    private fun fresh(success: Boolean, fetchedAt: Long, empty: Boolean = false) = success &&
        System.currentTimeMillis() - fetchedAt in 0 until (if (empty) 60_000L else 10 * 60_000L)

    suspend fun loadServerHome(force: Boolean = false): Boolean = withContext(AppDispatchers.HomeBookkeeping) {
        restored.await()
        if (!force && fresh(serverSuccess, serverFetchedAt, _serverShelves.value.isEmpty()) &&
            (serverCacheKey == homeKey || serverAttemptAt > 0L)) return@withContext true
        serverJob?.takeIf { it.isActive }?.let { return@withContext it.await() }
        val now = System.currentTimeMillis()
        if (!force && now - serverAttemptAt in 0 until 60_000L) return@withContext false
        serverAttemptAt = now
        val key = homeKey; val generation = ++serverGeneration; val region = HomeCacheIdentity.region(_userProfile.value)
        pageJob?.cancel()
        val job = owner.async {
            try {
                val response = BackgroundWorkCoordinator.localDataPreparation {
                    withContext(AppDispatchers.Io) { homeLoader(region, null, key.substringAfter('|') != "anonymous") }
                }
                if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value)) return@async false
                if (response.isError) { _refreshErrorMessage.value = response.errorMessage ?: "Recommendations are temporarily unavailable. Tap to retry."; return@async false }
                val responseAccount = response.accountIdentity ?: key.substringAfter('|')
                if (responseAccount != "anonymous" && responseAccount != key.substringAfter('|')) return@async false
                serverCacheKey = HomeCacheIdentity.home(_userProfile.value, responseAccount)
                serverFetchedAt = System.currentTimeMillis(); serverSuccess = true
                homeContinuationToken = response.continuationToken
                consumedContinuations.clear()
                _refreshErrorMessage.value = null
                _serverShelves.value = response.shelves
                _revealedShelfCount.value = maxOf(4, _revealedShelfCount.value).coerceAtMost(response.shelves.size)
                if (response.shelves.isEmpty()) _refreshErrorMessage.value = "No recommendations are available. Tap to retry."
                cache.saveHome(snapshot()); true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _refreshErrorMessage.value = "Recommendations are temporarily unavailable. Tap to retry."; false }
        }
        serverJob = job
        try { job.await() } finally { if (serverJob === job && job.isCompleted) serverJob = null }
    }
    suspend fun loadProfileShelf(force: Boolean = false): Boolean = withContext(AppDispatchers.HomeBookkeeping) {
        restored.await()
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
                    withContext(AppDispatchers.Io) { profileLoader(profile, rotation, force) }
                }
                if (result.isError || generation != profileGeneration || key != profileKey || key != currentProfileKey(_userProfile.value)) return@async false
                _profileShelf.value = result.shelf; profileFetchedAt = System.currentTimeMillis(); profileSuccess = true
                cache.saveProfile(snapshot()); true
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
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
    private suspend fun loadContinuation() {
        val token = homeContinuationToken?.takeUnless { it in consumedContinuations } ?: return
        val key = homeKey; val generation = serverGeneration
        val responseAccount = serverCacheKey.substringAfter('|')
        try {
            val result = BackgroundWorkCoordinator.localDataPreparation {
                withContext(AppDispatchers.Io) { homeLoader(HomeCacheIdentity.region(_userProfile.value), token, responseAccount != "anonymous") }
            }
            if (generation != serverGeneration || key != homeKey || key != currentHomeKey(_userProfile.value) || token != homeContinuationToken) return
            if (result.accountIdentity != null && result.accountIdentity != responseAccount) return
            if (result.isError) return // Failed tokens remain retryable.
            consumedContinuations.add(token)
            _serverShelves.value = appendServerShelves(_serverShelves.value, result.shelves)
            homeContinuationToken = result.continuationToken?.takeUnless { it in consumedContinuations }
            _revealedShelfCount.value = (_revealedShelfCount.value + 2).coerceAtMost(_serverShelves.value.size)
            cache.saveHome(snapshot())
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Keep the cursor and visible content. */ }
    }
    private suspend fun applyUserProfile(profile: UserProfile) {
        val nextHome = currentHomeKey(profile); val nextProfile = currentProfileKey(profile)
        _userProfile.value = profile
        if (nextHome != homeKey) {
            serverGeneration++; serverJob?.cancel(); pageJob?.cancel(); homeKey = nextHome
            serverCacheKey = nextHome
            _serverShelves.value = emptyList(); homeContinuationToken = null; consumedContinuations.clear()
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
            else { profileGeneration++; profileRequestJob?.cancel(); profileJob?.cancel(); profileAttemptAt = 0L }
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
                loadServerHome(isExplicitPull)
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
