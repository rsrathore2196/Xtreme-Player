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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

@OptIn(FlowPreview::class)
class HomeViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    application: Application
) : AndroidViewModel(application) {

    // 1. Instant User Profile (< 1ms)
    private val _userProfile = MutableStateFlow(UserProfilePreferences.getUserProfile(application))
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    // 2. Instant Local Catalog state (Local-First: Room/Memory)
    private val _catalogTracks = MutableStateFlow<List<MusicTrack>>(emptyList())
    val catalogTracks: StateFlow<List<MusicTrack>> = _catalogTracks.asStateFlow()

    private val database by lazy { MusicDatabase.getDatabase(application) }
    private val listeningEventDao by lazy { database.listeningEventDao() }
    private val personalizedEngine by lazy { PersonalizedHomeRecommendationEngine(listeningEventDao) }
    val favoriteTracks = repository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val actualPlays = listeningEventDao.observeActualPlays()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val historyMetadata = actualPlays.map { events -> events.map { it.trackId }.distinct() }.distinctUntilChanged().map { ids ->
        withContext(Dispatchers.IO) {
            ids.chunked(900).flatMap { database.musicDao().getTracksByIds(it) }.map { it.toMusicTrack() }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val tasteProfile = combine(actualPlays, historyMetadata, favoriteTracks, _userProfile) { events, tracks, favorites, preferences ->
        val taste = UserTasteProfile.fromEvents(events, tracks, favorites)
        taste.copy(languageAffinities = preferences.languages.associate { HomeRecommendationPolicy.normalize(it) to 0.35f } + taste.languageAffinities)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, UserTasteProfile.COLD_START_PROFILE)
    val recentlyPlayed: StateFlow<List<MusicTrack>> = combine(actualPlays, historyMetadata, tasteProfile) { events, metadata, taste ->
        if (taste.isColdStart) emptyList() else {
            val byId = metadata.associateBy { it.id }
            val seen = HomeRecommendationPolicy.Recordings()
            events.filter(UserTasteProfile::played).sortedByDescending { it.timestamp }
                .mapNotNull { byId[it.trackId] }.filter { seen.add(it) }.take(30)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val heroSeedTrack: StateFlow<MusicTrack?> = combine(recentlyPlayed, favoriteTracks, _catalogTracks) { recent, favorites, catalog ->
        (recent.asSequence() + favorites.asSequence() + catalog.asSequence())
            .firstOrNull(com.example.recommendation.OriginalMusicFilter::isHomeRecommendation)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Card 2: YouTube Music Home Screen-Based Card
    private val _ytmCard = MutableStateFlow(
        SectionState(title = "Quick Picks", subtitle = "Dynamic hits inspired by YouTube Music", isLoading = true)
    )
    val ytmCard: StateFlow<SectionState> = _ytmCard.asStateFlow()

    // Card 3: Era-Specific Hits Card
    private val _eraCard = MutableStateFlow(
        SectionState(title = "Era-Specific Hits", subtitle = "Golden 90s, 2000s classics & retro anthems", isLoading = true)
    )
    val eraCard: StateFlow<SectionState> = _eraCard.asStateFlow()
    val eraSection: StateFlow<SectionState> = _eraCard.asStateFlow() // Backward compatibility alias

    // Card 4: Trending Section (Country & Regional Charts)
    private val _countryLanguageCard = MutableStateFlow(
        SectionState(
            title = getAuthoritativeTrendingTitle(resolveCountryDisplayName(_userProfile.value)),
            subtitle = "Regional chart discoveries",
            isLoading = true
        )
    )
    val countryLanguageCard: StateFlow<SectionState> = _countryLanguageCard.asStateFlow()

    // Card 5: User Listening History Based Card
    private val _listeningHistoryCard = MutableStateFlow(
        SectionState(title = "Jump Back In", subtitle = "Based on your recent listening history & frequency", isLoading = false)
    )
    val listeningHistoryCard: StateFlow<SectionState> = _listeningHistoryCard.asStateFlow()

    // Card 6: Fully Personalized Recommendation Card
    private val _personalizedCard = MutableStateFlow(
        SectionState(title = "Recommended for You", subtitle = "Curated picks tailored to your sonic taste", isLoading = false)
    )
    val personalizedCard: StateFlow<SectionState> = _personalizedCard.asStateFlow()

    private val exposurePreferences = application.getSharedPreferences("home_shelf_exposure", Context.MODE_PRIVATE)

    private data class Candidates(
        val quick: List<MusicTrack> = emptyList(),
        val era: List<MusicTrack> = emptyList(),
        val chart: List<MusicTrack> = emptyList(),
        val generation: Long = 0L
    )
    private val candidates = MutableStateFlow(Candidates())
    private val _mostListenedArtists = MutableStateFlow<List<MostListenedArtist>>(emptyList())
    val mostListenedArtists: StateFlow<List<MostListenedArtist>> = _mostListenedArtists.asStateFlow()
    private val _homeShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val homeShelves: StateFlow<List<HomeShelf>> = _homeShelves.asStateFlow()
    private val renderMutex = Mutex()
    private var renderedGeneration = -1L
    private var previousExposure: Set<String> = emptySet()

    private suspend fun renderRecommendations(pool: Candidates, taste: UserTasteProfile) = renderMutex.withLock {
        val catalog = HomeRecommendationPolicy.originals(_catalogTracks.value + pool.quick + pool.era + pool.chart + historyMetadata.value + favoriteTracks.value)
        if (pool.generation != renderedGeneration) {
            previousExposure = exposurePreferences.getStringSet("recordings_v2", emptySet()).orEmpty().toSet()
            renderedGeneration = pool.generation
        }
        val shelves = personalizedEngine.generateHomeShelves(catalog, _userProfile.value,
            favorites = favoriteTracks.value, taste = taste)
        _homeShelves.value = shelves
        val seen = HomeRecommendationPolicy.Recordings()
        val chart = HomeRecommendationPolicy.originals(pool.chart).take(20)
        chart.forEach { seen.add(it) } // Charts have priority and retain their source ranking.
        fun fresh(tracks: List<MusicTrack>) = tracks.filter {
            (taste.skipCounts[taste.keyFor(it)] ?: 0) < 2 || taste.keyFor(it) in taste.favoriteRecordings
        }.sortedBy { HomeRecommendationPolicy.metadataKey(it) in previousExposure }
        val era = HomeRecommendationPolicy.diversify(fresh(pool.era), 15, seen)
        val plays = actualPlays.value.filter(UserTasteProfile::played).distinctBy { it.sessionId to it.trackId }
        val artistCounts = if (taste.isColdStart) emptyMap() else plays.flatMap { event ->
            val track = historyMetadata.value.find { it.id == event.trackId }
            track?.let(HomeRecommendationPolicy::artists).orEmpty()
        }.groupingBy { it }.eachCount()
        val artists = artistCounts.entries.sortedByDescending { it.value }.take(10).mapNotNull { (artist, count) ->
            val matches = catalog.filter { artist in HomeRecommendationPolicy.artists(it) }
            val tracks = HomeRecommendationPolicy.diversify(fresh(matches), 3, seen).ifEmpty {
                // An artist history card can use its actual played song even when it is in a chart.
                recentlyPlayed.value.filter { artist in HomeRecommendationPolicy.artists(it) }.take(1)
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
        val quickRanked = personalizedEngine.scoreAndRankCandidates(pool.quick, taste, limit = pool.quick.size)
        val quick = HomeRecommendationPolicy.diversify(fresh(quickRanked), 15, seen)
        _countryLanguageCard.value = _countryLanguageCard.value.copy(tracks = chart)
        _eraCard.value = _eraCard.value.copy(tracks = era, isLoading = false, isLoaded = era.isNotEmpty())
        _ytmCard.value = _ytmCard.value.copy(tracks = quick, isLoading = false, isLoaded = quick.isNotEmpty())
        _mostListenedArtists.value = artists
        _personalizedCard.value = _personalizedCard.value.copy(tracks = personalized,
            isLoading = false, isLoaded = personalized.isNotEmpty())
        // The small Recently Played card consumes this existing history flow without recommendation filtering.
        _listeningHistoryCard.value = _listeningHistoryCard.value.copy(tracks = recentlyPlayed.value,
            isLoading = false, isLoaded = recentlyPlayed.value.isNotEmpty())
        val shown = chart + era + quick + personalized + artists.flatMap { it.tracks }
        if (shown.isNotEmpty()) exposurePreferences.edit().putStringSet("recordings_v2",
            shown.map(HomeRecommendationPolicy::metadataKey).toSet()).apply()
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

    fun loadRegionalTrending(country: String, countryCode: String = "IN", forceRefresh: Boolean = false) {
        regionalTrendingJob?.cancel()
        val item = CountryData.findCountry(countryCode) ?: CountryData.findCountry(country)
        val name = item?.name ?: country.ifBlank { "India" }
        val title = getAuthoritativeTrendingTitle(name)
        val sameCountry = _countryLanguageCard.value.title == title
        if (!sameCountry) candidates.value = candidates.value.copy(chart = emptyList())
        _countryLanguageCard.value = _countryLanguageCard.value.copy(title = title,
            tracks = if (sameCountry) _countryLanguageCard.value.tracks else emptyList(), isLoading = true)
        regionalTrendingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val songs = repository.getRegionalTrending(name, item?.code ?: countryCode, limit = 20, forceRefresh = forceRefresh)
                if (songs.isNotEmpty()) candidates.update { it.copy(chart = songs) }
                _countryLanguageCard.value = _countryLanguageCard.value.copy(isLoading = false,
                    isLoaded = candidates.value.chart.isNotEmpty(), isError = songs.isEmpty(),
                    errorMessage = if (songs.isEmpty()) "Regional charts are temporarily unavailable." else "")
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _countryLanguageCard.value = _countryLanguageCard.value.copy(isLoading = false, isError = true)
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
    private var lastDiscoveryRefresh = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val initial = withContext(Dispatchers.IO) { repository.getInitialCatalog(_userProfile.value) }
            if (_catalogTracks.value.isEmpty()) {
                _catalogTracks.value = initial
                candidates.value = candidates.value.copy(quick = initial,
                    era = initial.filter { (it.year.toIntOrNull() ?: 0) in 1990..2009 })
                withContext(Dispatchers.Main.immediate) { playbackManager.setCandidatePool(initial) }
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
        viewModelScope.launch(Dispatchers.Default) {
            combine(candidates, tasteProfile, favoriteTracks, _userProfile, recentlyPlayed) { pool, taste, _, _, _ -> pool to taste }
                .debounce(250).collectLatest { (pool, taste) -> renderRecommendations(pool, taste) }
        }
        // History/likes trigger cheap local reranking immediately; network discovery is coalesced.
        viewModelScope.launch {
            combine(tasteProfile, favoriteTracks) { taste, favorites ->
                taste.topRecentTrackIds.toSet() to favorites.map { it.id }.toSet()
            }.distinctUntilChanged().debounce(2_000).collectLatest { (recent, favorites) ->
                if (recent.isNotEmpty() || favorites.isNotEmpty()) {
                    delay((60_000 - (System.currentTimeMillis() - lastDiscoveryRefresh)).coerceAtLeast(0))
                    refreshHome(false)
                }
            }
        }
        refreshHome(isExplicitPull = false)
    }

    fun refreshHome(isExplicitPull: Boolean = true) {
        if (homeRefreshJob?.isActive == true) return
        _isRefreshing.value = isExplicitPull
        _refreshErrorMessage.value = null
        homeRefreshJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val profile = _userProfile.value
                loadRegionalTrending(resolveCountryDisplayName(profile), profile.countryCode, isExplicitPull)
                coroutineScope {
                    val quickJob = async { repository.getYtmQuickPicks(limit = 100, forceRefresh = isExplicitPull) }
                    val eraJob = async { repository.getEraHits(limit = 60, forceRefresh = isExplicitPull) }
                    val quick = quickJob.await()
                    val era = eraJob.await()
                    if (_userProfile.value != profile) return@coroutineScope
                    candidates.update { old -> old.copy(quick = quick.ifEmpty { old.quick }, era = era.ifEmpty { old.era },
                        generation = old.generation + 1) }
                    val catalog = HomeRecommendationPolicy.originals(quick + era + _catalogTracks.value).take(300)
                    if (catalog.isNotEmpty()) {
                        _catalogTracks.value = catalog
                        withContext(Dispatchers.Main.immediate) { playbackManager.setCandidatePool(catalog) }
                    }
                    lastDiscoveryRefresh = System.currentTimeMillis()
                    regionalTrendingJob?.join()
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (isExplicitPull) _refreshErrorMessage.value = "Couldn't refresh recommendations. Your saved suggestions are still available."
            } finally {
                _isRefreshing.value = false
                _ytmCard.value = _ytmCard.value.copy(isLoading = false)
                _eraCard.value = _eraCard.value.copy(isLoading = false)
            }
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
