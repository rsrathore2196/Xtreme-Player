package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserProfile
import com.example.data.local.UserProfilePreferences
import com.example.data.local.MusicDatabase
import com.example.data.local.ListeningEventEntity
import com.example.data.model.CountryData
import com.example.data.model.MusicTrack
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.UserTasteProfile
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onEach
import com.example.data.cache.HomeCardCache
import com.example.data.cache.SavedHomeCards
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.example.recommendation.PersonalizedHomeRecommendationEngine
import com.example.ui.ai.HomeShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import androidx.compose.runtime.Immutable

@Immutable
data class SectionState(
    val title: String = "",
    val subtitle: String = "",
    val tracks: List<MusicTrack> = emptyList(),
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String = ""
)

@Immutable
data class MostListenedArtist(
    val name: String,
    val playCount: Int,
    val tracks: List<MusicTrack>
)

@Immutable
data class HomeUiState(
    val greeting: String = "",
    val userProfile: UserProfile? = null,
    val heroTrack: MusicTrack? = null,
    val catalogTracks: List<MusicTrack> = emptyList(),
    val recentlyPlayed: List<MusicTrack> = emptyList(),
    val favoriteTracks: List<MusicTrack> = emptyList(),
    val pillsCardTracks: List<MusicTrack> = emptyList(),
    val ytmFeedState: SectionState = SectionState(),
    val eraHitsState: SectionState = SectionState(),
    val countryLanguageState: SectionState = SectionState(),
    val listeningHistoryState: SectionState = SectionState(),
    val personalizedState: SectionState = SectionState(),
    val isRefreshing: Boolean = false
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    application: Application
) : AndroidViewModel(application) {

    // 1. Instant User Profile (< 1ms)
    private val _userProfile = MutableStateFlow(UserProfilePreferences.getUserProfile(application))
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()
    private val shelfCache = HomeCardCache(application)
    private val initialCards = shelfCache.peek(profileKey())
    private val _currentStage = MutableStateFlow(LoadingStage.PRIORITY_1_INSTANT_SHELL)
    val currentStage: StateFlow<LoadingStage> = _currentStage.asStateFlow()

    // 2. Instant Local Catalog state (Local-First: Room/Memory)
    private val _catalogTracks = MutableStateFlow(initialCards?.catalog.orEmpty())
    val catalogTracks: StateFlow<List<MusicTrack>> = _catalogTracks.asStateFlow()
    private val homeVisible = MutableStateFlow(false)
    private val restored = CompletableDeferred<Unit>()
    private val eventsLoaded = CompletableDeferred<Unit>()
    private val favoritesLoaded = CompletableDeferred<Unit>()
    private var savedSignal: String? = initialCards?.signal
    private var savedGeneration = initialCards?.generation ?: -1L
    private val lastEventStamps = mutableMapOf<Long, com.example.data.local.ActualPlayStamp>()
    private val retainedEvents = mutableMapOf<Long, ListeningEventEntity>()
    private fun profileKey() = "${_userProfile.value.countryCode}|${_userProfile.value.languages.joinToString(",")}"
    private val cachedHero = MutableStateFlow(initialCards?.history?.tracks?.firstOrNull() ?: initialCards?.catalog?.firstOrNull())
    private val cachedHistory = MutableStateFlow(initialCards?.history?.tracks)

    private val database by lazy { MusicDatabase.getDatabase(application) }
    private val listeningEventDao by lazy { database.listeningEventDao() }
    private val personalizedEngine by lazy { PersonalizedHomeRecommendationEngine(listeningEventDao) }
    val favoriteTracks = homeVisible.flatMapLatest { visible ->
        if (visible) repository.getFavoriteTracks().onEach { favoritesLoaded.complete(Unit) } else emptyFlow()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    private val actualPlays = homeVisible.flatMapLatest { visible ->
        if (!visible) emptyFlow() else flow {
            restored.await()
            if (savedCandidates != null && !eventsLoaded.isCompleted) awaitHomeIdle()
            listeningEventDao.observeActualPlayStamps().collect { stamps ->
                val changed = stamps.filter { lastEventStamps[it.id] != it }.map { it.id }
                val rows = changed.chunked(900).flatMap { listeningEventDao.getEventsByIds(it) }
                rows.forEach { retainedEvents[it.id] = it }
                val ids = stamps.map { it.id }.toSet()
                retainedEvents.keys.retainAll(ids)
                lastEventStamps.clear(); stamps.forEach { lastEventStamps[it.id] = it }
                emit(stamps.mapNotNull { retainedEvents[it.id] })
                if (stamps.isEmpty()) { cachedHero.value = null; cachedHistory.value = null }
                eventsLoaded.complete(Unit)
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private data class HistoryRows(val ids: List<String> = emptyList(), val tracks: List<MusicTrack> = emptyList())
    private val historyRows = combine(actualPlays.map { events -> events.map { it.trackId }.distinct() }.distinctUntilChanged(), homeVisible) { ids, visible -> ids to visible }.flatMapLatest { (ids, visible) ->
        if (!visible) return@flatMapLatest emptyFlow()
        val pages = ids.chunked(900).map { database.musicDao().observeTracksByIds(it).distinctUntilChanged() }
        if (pages.isEmpty()) kotlinx.coroutines.flow.flowOf(HistoryRows(ids)) else combine(pages) { groups ->
            HistoryRows(ids, groups.flatMap { rows -> rows.map { it.toMusicTrack() } })
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, HistoryRows())
    private val historyMetadata = historyRows.map { it.tracks }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private data class TasteInputs(val events: List<ListeningEventEntity>, val tracks: List<MusicTrack>,
        val favorites: List<MusicTrack>, val preferences: UserProfile)
    private data class TasteSnapshot(val inputs: TasteInputs? = null,
        val taste: UserTasteProfile = UserTasteProfile.COLD_START_PROFILE)
    private var lastTasteInputs: TasteInputs? = null
    private var retainedTaste = UserTasteProfile.COLD_START_PROFILE
    private val tasteSnapshot = combine(actualPlays, historyMetadata, favoriteTracks, _userProfile) { events, tracks, favorites, preferences ->
        TasteInputs(events, tracks, favorites, preferences)
    }.distinctUntilChanged().mapLatest { inputs ->
        restored.await()
        eventsLoaded.await(); favoritesLoaded.await()
        // Restarting a screen subscription is not a new listening signal. Keep the same decay
        // snapshot until real data changes, rather than reshuffling on every tab return.
        if (inputs != lastTasteInputs) {
            awaitHomeIdle()
            BackgroundWorkCoordinator.work {
                val taste = UserTasteProfile.fromEvents(inputs.events, inputs.tracks, inputs.favorites)
                currentCoroutineContext().ensureActive()
                retainedTaste = taste.copy(languageAffinities = inputs.preferences.languages.associate {
                    HomeRecommendationPolicy.normalize(it) to 0.35f
                } + taste.languageAffinities)
                lastTasteInputs = inputs
            }
        }
        TasteSnapshot(inputs, retainedTaste)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), TasteSnapshot())
    private val tasteProfile = tasteSnapshot.map { it.taste }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UserTasteProfile.COLD_START_PROFILE)
    val recentlyPlayed: StateFlow<List<MusicTrack>> = combine(actualPlays, historyMetadata, cachedHistory) { events, metadata, cached ->
        if (events.none(UserTasteProfile::played)) cached.orEmpty() else {
            val byId = metadata.associateBy { it.id }
            val seen = HomeRecommendationPolicy.Recordings()
            // The DAO already orders newest first; stop once the visible history is filled.
            events.asSequence().filter(UserTasteProfile::played)
                .mapNotNull { byId[it.trackId] }.filter { seen.add(it) }.take(30).toList()
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    val heroSeedTrack: StateFlow<MusicTrack?> = combine(recentlyPlayed, favoriteTracks, _catalogTracks, cachedHero) { recent, favorites, catalog, cached ->
        (recent.asSequence() + listOfNotNull(cached).asSequence() + favorites.asSequence() + catalog.asSequence())
            .firstOrNull(com.example.recommendation.OriginalMusicFilter::isHomeRecommendation)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), cachedHero.value)

    // Helper to compute authoritative trending title
    fun getAuthoritativeTrendingTitle(countryDisplayName: String): String {
        return if (countryDisplayName.trim().equals("India", ignoreCase = true)) {
            "Trending in India"
        } else {
            "Trending in ${countryDisplayName.trim().ifBlank { "India" }}"
        }
    }

    fun resolveCountryDisplayName(profile: UserProfile): String {
        val countryItem = CountryData.findCountry(profile.countryCode)
            ?: CountryData.findCountry(profile.country)
        return countryItem?.name ?: profile.country.trim().ifBlank { "India" }
    }

    // Card 1: Recently Played (derived strictly from real listening history, never recommendations or filler)
    val pillsCardTracks: StateFlow<List<MusicTrack>> = recentlyPlayed
        .map { recent ->
            recent.distinctBy { it.id }.take(6)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Card 2: YouTube Music Home Screen-Based Card
    private val _ytmCard = MutableStateFlow(
        initialCards?.quick ?: SectionState(title = "Quick Picks", subtitle = "Dynamic hits inspired by YouTube Music", isLoading = true)
    )
    val ytmCard: StateFlow<SectionState> = _ytmCard.asStateFlow()

    // Card 3: Era-Specific Hits Card
    private val _eraCard = MutableStateFlow(
        initialCards?.era ?: SectionState(title = "Era-Specific Hits", subtitle = "Golden 90s, 2000s classics & retro anthems", isLoading = true)
    )
    val eraCard: StateFlow<SectionState> = _eraCard.asStateFlow()
    val eraSection: StateFlow<SectionState> = _eraCard.asStateFlow() // Backward compatibility alias

    // Card 4: Trending Section (Country & Regional Charts)
    private val _countryLanguageCard = MutableStateFlow(
        initialCards?.chart ?: SectionState(
            title = getAuthoritativeTrendingTitle(resolveCountryDisplayName(_userProfile.value)),
            subtitle = "Regional chart discoveries",
            isLoading = true
        )
    )
    val countryLanguageCard: StateFlow<SectionState> = _countryLanguageCard.asStateFlow()

    // Card 5: User Listening History Based Card
    private val _listeningHistoryCard = MutableStateFlow(
        initialCards?.history ?: SectionState(title = "Jump Back In", subtitle = "Based on your recent listening history & frequency", isLoading = false)
    )
    val listeningHistoryCard: StateFlow<SectionState> = _listeningHistoryCard.asStateFlow()

    // Card 6: Fully Personalized Recommendation Card
    private val _personalizedCard = MutableStateFlow(
        initialCards?.personalized ?: SectionState(title = "Recommended for You", subtitle = "Curated picks tailored to your sonic taste", isLoading = false)
    )
    val personalizedCard: StateFlow<SectionState> = _personalizedCard.asStateFlow()

    private val exposurePreferences by lazy {
        application.getSharedPreferences("home_shelf_exposure", Context.MODE_PRIVATE)
    }

    private data class Candidates(
        val quick: List<MusicTrack> = emptyList(),
        val era: List<MusicTrack> = emptyList(),
        val chart: List<MusicTrack> = emptyList(),
        val generation: Long = 0L
    )
    private val candidates = MutableStateFlow(initialCards?.let {
        Candidates(it.quickCandidates, it.eraCandidates, it.chartCandidates, it.generation)
    } ?: Candidates())
    private var savedCandidates: Candidates? = initialCards?.let { candidates.value }
    private val _mostListenedArtists = MutableStateFlow(initialCards?.artists.orEmpty())
    val mostListenedArtists: StateFlow<List<MostListenedArtist>> = _mostListenedArtists.asStateFlow()
    private val _homeShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val homeShelves: StateFlow<List<HomeShelf>> = _homeShelves.asStateFlow()
    // Compatibility accessors for the old staged-loader name; no second pipeline.
    val primaryCatalog: StateFlow<List<MusicTrack>> get() = catalogTracks
    val heroTrack: StateFlow<MusicTrack?> get() = heroSeedTrack
    val secondaryShelves: StateFlow<List<HomeShelf>> get() = homeShelves
    fun refreshAll() = refreshHome(isExplicitPull = true)
    private val renderMutex = Mutex()
    private var renderedGeneration = -1L
    private var previousExposure: Set<String> = emptySet()
    private data class RenderInputs(val pool: Candidates, val taste: UserTasteProfile,
        val profile: UserProfile, val catalog: List<MusicTrack>, val history: List<MusicTrack>,
        val favorites: List<MusicTrack>, val recent: List<MusicTrack>)
    private var lastRenderInputs: RenderInputs? = null

    private suspend fun renderRecommendations(pool: Candidates, requestedTaste: UserTasteProfile) = renderMutex.withLock {
        restored.await()
        eventsLoaded.await(); favoritesLoaded.await()
        val ids = actualPlays.value.map { it.trackId }.distinct()
        val metadata = historyRows.first { it.ids == ids }.tracks
        val taste = tasteSnapshot.first { snapshot ->
            snapshot.inputs?.let { input -> input.events == actualPlays.value && input.tracks == metadata &&
                input.favorites == favoriteTracks.value && input.preferences == _userProfile.value } == true
        }.taste
        cachedHistory.value = null
        val recentById = metadata.associateBy { it.id }
        val recentSeen = HomeRecommendationPolicy.Recordings()
        val recent = if (taste.isColdStart) emptyList() else actualPlays.value.asSequence()
            .filter(UserTasteProfile::played).mapNotNull { recentById[it.trackId] }
            .filter(recentSeen::add).take(30).toList()
        val inputs = RenderInputs(pool, taste, _userProfile.value, _catalogTracks.value,
            metadata, favoriteTracks.value, recent)
        if (inputs == lastRenderInputs) return@withLock
        val metadataSignal = inputs.history.map { listOf(it.id, it.title, it.artist, it.album, it.coverUrl,
            it.genre, it.language, it.year, it.isLiked) }.hashCode()
        val signal = "${actualPlays.value.hashCode()}|$metadataSignal|${inputs.favorites.map { it.id }.sorted()}|${profileKey()}"
        if (pool == savedCandidates && pool.generation == savedGeneration && signal == savedSignal) {
            _listeningHistoryCard.update { it.copy(tracks = inputs.recent, isLoaded = inputs.recent.isNotEmpty()) }
            _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
            lastRenderInputs = inputs
            return@withLock
        }
        if (!activeRefreshIsExplicit && pool.generation != manualRenderGeneration)
            awaitHomeIdle()
        val rendering = BackgroundWorkCoordinator.busy()
        try {
        currentCoroutineContext().ensureActive()
        val catalog = HomeRecommendationPolicy.originals(inputs.catalog + pool.quick + pool.era + pool.chart + inputs.history + inputs.favorites)
        if (pool.generation != renderedGeneration) {
            previousExposure = withContext(Dispatchers.IO) {
                exposurePreferences.getStringSet("recordings_v2", emptySet()).orEmpty().toSet()
            }
            renderedGeneration = pool.generation
        }
        val shelves = personalizedEngine.generateHomeShelves(catalog, inputs.profile,
            favorites = inputs.favorites, taste = taste)
        currentCoroutineContext().ensureActive()
        val seen = HomeRecommendationPolicy.Recordings()
        val chart = HomeRecommendationPolicy.originals(pool.chart).take(20)
        chart.forEach { seen.add(it) } // Charts have priority and retain their source ranking.
        fun fresh(tracks: List<MusicTrack>) = tracks.filter {
            (taste.skipCounts[taste.keyFor(it)] ?: 0) < 2 || taste.keyFor(it) in taste.favoriteRecordings
        }.sortedBy { HomeRecommendationPolicy.metadataKey(it) in previousExposure }
        val era = HomeRecommendationPolicy.diversify(fresh(pool.era), 15, seen)
        val plays = actualPlays.value.filter(UserTasteProfile::played).distinctBy { it.sessionId to it.trackId }
        val historyArtists = inputs.history.associate { it.id to HomeRecommendationPolicy.artists(it) }
        val artistCounts = if (taste.isColdStart) emptyMap() else plays.asSequence().flatMap { event ->
            historyArtists[event.trackId].orEmpty().asSequence()
        }.groupingBy { it }.eachCount()
        val catalogByArtist = mutableMapOf<String, MutableList<MusicTrack>>()
        for (track in catalog) HomeRecommendationPolicy.artists(track).forEach { artist ->
            catalogByArtist.getOrPut(artist) { mutableListOf() }.add(track)
        }
        val artists = artistCounts.entries.sortedByDescending { it.value }.take(10).mapNotNull { (artist, count) ->
            currentCoroutineContext().ensureActive()
            val matches = catalogByArtist[artist].orEmpty()
            val tracks = HomeRecommendationPolicy.diversify(fresh(matches), 3, seen).ifEmpty {
                // An artist history card can use its actual played song even when it is in a chart.
                inputs.recent.filter { artist in HomeRecommendationPolicy.artists(it) }.take(1)
            }
            if (tracks.isEmpty()) null else MostListenedArtist(
                matches.firstOrNull()?.let { track -> (track.artist + "," + track.singers).split(',')
                    .firstOrNull { HomeRecommendationPolicy.normalize(it) == artist }?.trim() } ?: artist,
                count, tracks)
        }
        val personalizedGroups = if (taste.isColdStart) emptyList() else shelves.filter {
            it.id in setOf("shelf_mood_vibe", "shelf_top_artist", "shelf_quick_picks")
        }.map { it.tracks }
        // Round robin prevents the first shelf from monopolizing the entire card.
        val personalized = HomeRecommendationPolicy.diversify(fresh(HomeRecommendationPolicy.interleave(personalizedGroups)), 15, seen)
        val quickRanked = personalizedEngine.scoreAndRankCandidates(pool.quick, taste,
            limit = pool.quick.size, variationSeed = pool.generation)
        val quick = HomeRecommendationPolicy.diversify(fresh(quickRanked), 15, seen)
        // Cancellation on a tab change must not publish a partially calculated stale shelf set.
        currentCoroutineContext().ensureActive()
        _homeShelves.value = shelves
        // Reranking must retain error/loading updates arriving from the refresh requests.
        _countryLanguageCard.update { it.copy(tracks = chart) }
        _eraCard.update { it.copy(tracks = era, isLoading = false, isLoaded = era.isNotEmpty()) }
        _ytmCard.update { it.copy(tracks = quick, isLoading = false, isLoaded = quick.isNotEmpty()) }
        _mostListenedArtists.value = artists
        _personalizedCard.value = _personalizedCard.value.copy(tracks = personalized,
            isLoading = false, isLoaded = personalized.isNotEmpty())
        // The small Recently Played card consumes this existing history flow without recommendation filtering.
        _listeningHistoryCard.value = _listeningHistoryCard.value.copy(tracks = inputs.recent,
            isLoading = false, isLoaded = inputs.recent.isNotEmpty())
        lastRenderInputs = inputs
        _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
        savedSignal = signal; savedGeneration = pool.generation; savedCandidates = pool
        shelfCache.save(SavedHomeCards(profileKey(), signal, pool.generation, lastDiscoveryRefresh,
            _ytmCard.value, _eraCard.value, _countryLanguageCard.value, _listeningHistoryCard.value,
            _personalizedCard.value, _mostListenedArtists.value, pool.quick, pool.era, pool.chart, catalog.take(400),
            chartRefreshedAt = lastRegionalRefresh))
        if (manualRenderGeneration == pool.generation) manualRenderGeneration = -1L
        val shown = chart + era + quick + personalized + artists.flatMap { it.tracks }
        if (shown.isNotEmpty()) {
            val exposure = shown.map(HomeRecommendationPolicy::metadataKey).toSet()
            withContext(Dispatchers.IO) {
                exposurePreferences.edit().putStringSet("recordings_v2", exposure).apply()
            }
        }

        } finally { rendering.close() }
    }

    // Smooth Pull-to-Refresh & Background Sync State
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    val isSilentRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshErrorMessage = MutableStateFlow<String?>(null)
    val refreshErrorMessage: StateFlow<String?> = _refreshErrorMessage.asStateFlow()

    fun clearRefreshError() {
        _refreshErrorMessage.value = null
    }

    private var regionalTrendingJob: Job? = null
    private var lastRegionalRefresh = initialCards?.chartRefreshedAt ?: 0L
    private var lastRegionalAttempt = 0L
    private var lastRegionalAttemptTitle: String? = null

    private suspend fun awaitHomeIdle() {
        while (true) {
            homeVisible.first { it }
            val essential = _ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty()
            BackgroundWorkCoordinator.awaitIdle(quietMillis = if (essential) 1_500L else 2_500L,
                minimumUptimeMillis = if (essential) 1_500L else 6_000L,
                userRequested = { _isRefreshing.value || manualRenderGeneration == candidates.value.generation })
            if (homeVisible.value) return
        }
    }

    fun loadRegionalTrending(country: String, countryCode: String = "IN", forceRefresh: Boolean = false,
        alreadyIdle: Boolean = false) {
        val item = CountryData.findCountry(countryCode) ?: CountryData.findCountry(country)
        val name = item?.name ?: country.ifBlank { "India" }
        val title = getAuthoritativeTrendingTitle(name)
        val sameCountry = _countryLanguageCard.value.title == title
        if (!forceRefresh && sameCountry && (regionalTrendingJob?.isActive == true ||
                lastRegionalAttemptTitle == title && System.currentTimeMillis() - lastRegionalAttempt in 0 until 60_000L)) return
        regionalTrendingJob?.cancel()
        if (!sameCountry) candidates.value = candidates.value.copy(chart = emptyList())
        _countryLanguageCard.update { it.copy(title = title,
            tracks = if (sameCountry) it.tracks else emptyList(),
            isLoading = true, isError = false, errorMessage = "") }
        regionalTrendingJob = viewModelScope.launch(Dispatchers.IO) {
          if (!forceRefresh && !alreadyIdle) awaitHomeIdle()
          lastRegionalAttempt = System.currentTimeMillis()
          lastRegionalAttemptTitle = title
          BackgroundWorkCoordinator.work {
            try {
                val songs = repository.getRegionalTrending(name, item?.code ?: countryCode, limit = 20, forceRefresh = forceRefresh)
                if (_countryLanguageCard.value.title != title) return@launch
                if (songs.isNotEmpty()) {
                    lastRegionalRefresh = System.currentTimeMillis()
                    candidates.update { it.copy(chart = songs) }
                }
                _countryLanguageCard.update { if (it.title != title) it else it.copy(isLoading = false,
                    isLoaded = candidates.value.chart.isNotEmpty(), isError = songs.isEmpty(),
                    errorMessage = if (songs.isEmpty()) "Regional charts are temporarily unavailable." else "") }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (_countryLanguageCard.value.title == title) {
                    _countryLanguageCard.update { if (it.title != title) it else it.copy(isLoading = false, isError = true,
                        errorMessage = "Regional charts are temporarily unavailable.") }
                }
            }
          }
        }
    }

    fun updateUserProfile(profile: UserProfile) {
        UserProfilePreferences.saveUserProfile(getApplication(), profile)
        _userProfile.value = profile
        homeRefreshJob?.cancel()
        homeRefreshJob = null
        refreshHome(isExplicitPull = false)
    }

    private var homeRefreshJob: Job? = null
    private var refreshWaitingForIdle = false
    private var pendingExplicitRefresh = false
    private var activeRefreshIsExplicit = false
    private var refreshGeneration = 0L
    private var lastDiscoveryRefresh = initialCards?.refreshedAt ?: 0L
    private var lastDiscoveryAttempt = 0L
    private var lastAttemptProfile: String? = null
    private var manualRenderGeneration = -1L
    private var pendingSilentRefresh = false
    private var refreshCandidatesPending = false
    private var lastDiscoverySignal: Pair<Set<String>, Set<String>>? = null

    fun setHomeVisible(visible: Boolean) {
        if (homeVisible.value == visible) return
        homeVisible.value = visible
        if (!visible) {
            // Keep bounded requests that have already started. Cancel only optional
            // work still waiting for idle; tab returns reuse the same request/results.
            if (homeRefreshJob?.isActive == true && refreshWaitingForIdle) {
                pendingSilentRefresh = true
                if (activeRefreshIsExplicit) pendingExplicitRefresh = true
                homeRefreshJob?.cancel()
                homeRefreshJob = null
                ++refreshGeneration
                _isRefreshing.value = false
            }
        }
    }

    init {
        viewModelScope.launch {
            combine(_ytmCard, _eraCard, _countryLanguageCard) { quick, era, chart ->
                listOf(quick, era, chart).filter { it.isError && !it.isLoading }.map { it.title }
            }.distinctUntilChanged().collect { failed ->
                _refreshErrorMessage.value = failed.takeIf { it.isNotEmpty() }?.let {
                    "${it.joinToString(", ")} couldn't load. Tap Retry to try again."
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
          try {
            val cached = initialCards ?: shelfCache.read(profileKey())
            if (cached != null) {
                savedSignal = cached.signal; savedGeneration = cached.generation
                _ytmCard.value = cached.quick; _eraCard.value = cached.era
                _countryLanguageCard.value = cached.chart; _listeningHistoryCard.value = cached.history
                _personalizedCard.value = cached.personalized; _mostListenedArtists.value = cached.artists
                _catalogTracks.value = cached.catalog
                cachedHero.value = cached.history.tracks.firstOrNull() ?: cached.catalog.firstOrNull()
                cachedHistory.value = cached.history.tracks
                candidates.value = Candidates(cached.quickCandidates, cached.eraCandidates,
                    cached.chartCandidates, cached.generation)
                savedCandidates = candidates.value
                lastDiscoveryRefresh = cached.refreshedAt
                lastRegionalRefresh = cached.chartRefreshedAt
                withContext(Dispatchers.Main.immediate) { playbackManager.setCandidatePool(cached.catalog) }
            }
            val initial = if (cached == null) repository.getInitialCatalog(_userProfile.value) else emptyList()
            if (_catalogTracks.value.isEmpty()) {
                _catalogTracks.value = initial
                candidates.value = candidates.value.copy(quick = initial,
                    era = initial.filter { (it.year.toIntOrNull() ?: 0) in 1990..2009 })
                withContext(Dispatchers.Main.immediate) { playbackManager.setCandidatePool(initial) }
            }
          } finally {
            _currentStage.value = LoadingStage.PRIORITY_2_ESSENTIAL_CONTENT
            restored.complete(Unit)
          }
        }
        viewModelScope.launch {
            UserProfilePreferences.getUserProfileFlow(getApplication()).collect { profile ->
                if (_userProfile.value != profile) {
                    _userProfile.value = profile
                    homeRefreshJob?.cancel()
                    homeRefreshJob = null
                    refreshHome(isExplicitPull = false)
                }
            }
        }
        viewModelScope.launch {
            homeVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
                restored.await()
                coroutineScope {
                    launch(Dispatchers.Default) {
                        combine(candidates, tasteProfile, favoriteTracks, _userProfile, recentlyPlayed) { pool, taste, _, _, _ -> pool to taste }
                            .debounce(250).collectLatest { (pool, taste) -> renderRecommendations(pool, taste) }
                    }
                    launch {
                        recentlyPlayed.collect { recent ->
                            _listeningHistoryCard.update { it.copy(tracks = recent,
                                isLoaded = recent.isNotEmpty(), isLoading = false) }
                        }
                    }
                    // Re-read real listening/likes on return, but do not fetch again for an unchanged signal.
                    launch {
                        combine(tasteProfile, favoriteTracks) { taste, favorites ->
                            taste.topRecentTrackIds.toSet() to favorites.map { it.id }.toSet()
                        }.flowOn(Dispatchers.Default).distinctUntilChanged().debounce(2_000).collectLatest { signal ->
                            if (lastDiscoverySignal == null) {
                                // The initial request already reads listening history and likes.
                                lastDiscoverySignal = signal
                                return@collectLatest
                            }
                            if (signal != lastDiscoverySignal && (signal.first.isNotEmpty() || signal.second.isNotEmpty())) {
                                delay((60_000 - (System.currentTimeMillis() - lastDiscoveryRefresh)).coerceAtLeast(0))
                                lastDiscoverySignal = signal
                                refreshHome(false)
                            }
                        }
                    }
                    if (pendingSilentRefresh || lastDiscoveryRefresh == 0L || System.currentTimeMillis() - lastDiscoveryRefresh >= 10 * 60_000L) {
                        refreshHome(false)
                    } else if (candidates.value.chart.isEmpty() ||
                        System.currentTimeMillis() - lastRegionalRefresh >= 10 * 60_000L) {
                        val profile = _userProfile.value
                        loadRegionalTrending(resolveCountryDisplayName(profile), profile.countryCode)
                    }
                    awaitCancellation()
                }
            }
        }
    }

    fun refreshHome(isExplicitPull: Boolean = true) {
        // Request/job ownership stays on Main; catalog and ranking work stays off Main.
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!homeVisible.value) {
                if (isExplicitPull) pendingExplicitRefresh = true else pendingSilentRefresh = true
                return@launch
            }
            if (!isExplicitPull && !pendingExplicitRefresh && lastAttemptProfile == profileKey() &&
                System.currentTimeMillis() - lastDiscoveryAttempt in 0 until 60_000L) return@launch
            if (homeRefreshJob?.isActive == true) {
                if (isExplicitPull && refreshWaitingForIdle) {
                    // A user refresh can take over a deferred job before it has made any requests.
                    ++refreshGeneration
                    homeRefreshJob?.cancel()
                    homeRefreshJob = null
                    refreshWaitingForIdle = false
                } else {
                    if (isExplicitPull) {
                        _isRefreshing.value = true
                        _refreshErrorMessage.value = null
                        // Coalesce pulls into one forced pass after a silent request, never parallel requests.
                        if (!activeRefreshIsExplicit) pendingExplicitRefresh = true
                    }
                    return@launch
                }
            }
            val generation = ++refreshGeneration
            pendingSilentRefresh = false
            val initialExplicit = isExplicitPull || pendingExplicitRefresh || _isRefreshing.value
            pendingExplicitRefresh = false
            homeRefreshJob = viewModelScope.launch {
                var explicit = initialExplicit
                try {
                    restored.await()
                    if (!initialExplicit && !pendingExplicitRefresh) {
                        refreshWaitingForIdle = true
                        try { awaitHomeIdle() }
                        finally { if (generation == refreshGeneration) refreshWaitingForIdle = false }
                    }
                    while (true) {
                        lastDiscoveryAttempt = System.currentTimeMillis()
                        lastAttemptProfile = profileKey()
                        activeRefreshIsExplicit = explicit
                        refreshCandidatesPending = true
                        _isRefreshing.value = explicit
                        _refreshErrorMessage.value = null
                        _ytmCard.update { it.copy(isLoading = it.tracks.isEmpty(), isError = false, errorMessage = "") }
                        _eraCard.update { it.copy(isLoading = it.tracks.isEmpty(), isError = false, errorMessage = "") }
                        try {
                            refreshSuggestions(explicit)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            _ytmCard.update { it.copy(isLoading = false, isError = true,
                                errorMessage = "Quick Picks are temporarily unavailable.") }
                            _eraCard.update { it.copy(isLoading = false, isError = true,
                                errorMessage = "Era hits are temporarily unavailable.") }
                        }
                        if (!pendingExplicitRefresh) break
                        pendingExplicitRefresh = false
                        explicit = true
                    }
                } finally {
                    // A canceled request must not hide the spinner or clear ownership of its replacement.
                    if (generation == refreshGeneration) {
                        _isRefreshing.value = false
                        _ytmCard.update { it.copy(isLoading = false) }
                        _eraCard.update { it.copy(isLoading = false) }
                        activeRefreshIsExplicit = false
                        refreshWaitingForIdle = false
                        homeRefreshJob = null
                    }
                }
            }
        }
    }

    private suspend fun refreshSuggestions(force: Boolean) {
      BackgroundWorkCoordinator.work {
        val profile = _userProfile.value
        val country = resolveCountryDisplayName(profile)
        if (force || candidates.value.chart.isEmpty() ||
            _countryLanguageCard.value.title != getAuthoritativeTrendingTitle(country) ||
            System.currentTimeMillis() - lastRegionalRefresh >= 10 * 60_000L)
            loadRegionalTrending(country, profile.countryCode, force, alreadyIdle = true)
        val chartRequest = regionalTrendingJob?.takeIf { it.isActive }
        val (quick, era) = withContext(Dispatchers.IO) {
            coroutineScope {
                val quickJob = async {
                    try { repository.getYtmQuickPicks(limit = 100, forceRefresh = force) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
                val eraJob = async {
                    try { repository.getEraHits(limit = 60, forceRefresh = force) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
                quickJob.await() to eraJob.await()
            }
        }
        if (_userProfile.value != profile) return
        if (quick.isNotEmpty() || era.isNotEmpty()) {
            lastDiscoveryRefresh = System.currentTimeMillis()
            if (force) manualRenderGeneration = candidates.value.generation + 1L
            candidates.update { old -> old.copy(quick = quick.ifEmpty { old.quick },
                era = era.ifEmpty { old.era }, generation = old.generation + 1) }
        }
        _ytmCard.update { it.copy(isLoading = false, isError = quick.isEmpty(),
            errorMessage = if (quick.isEmpty()) "Quick Picks are temporarily unavailable." else "") }
        _eraCard.update { it.copy(isLoading = false, isError = era.isEmpty(),
            errorMessage = if (era.isEmpty()) "Era hits are temporarily unavailable." else "") }
        val catalog = withContext(Dispatchers.Default) {
            HomeRecommendationPolicy.originals(quick + era + _catalogTracks.value).take(300)
        }
        if (catalog.isNotEmpty()) {
            _catalogTracks.value = catalog
            playbackManager.setCandidatePool(catalog)
        }
        refreshCandidatesPending = false
        chartRequest?.join()
      }
    }

    fun loadEraSectionIfNeeded() {
        if (!_eraCard.value.isLoaded && !_eraCard.value.isLoading) refreshHome(false)
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack>) {
        playbackManager.playTrack(track, queue)
    }

    fun toggleFavorite(track: MusicTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleFavorite(track)
        }
    }

    fun computeGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..22 -> "Good evening"
            else -> "Late Night Sessions"
        }
    }

    override fun onCleared() {
        shelfCache.close()
        super.onCleared()
    }

    companion object {
        fun provideFactory(
            repository: MusicRepository,
            playbackManager: PlaybackManager,
            application: Application
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(repository, playbackManager, application) as T
            }
        }
    }
}
