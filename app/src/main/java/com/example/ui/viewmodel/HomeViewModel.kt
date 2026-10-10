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
import com.example.data.model.CountryData
import com.example.data.model.MusicTrack
import com.example.data.remote.MusicDataSource
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
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

    // 3. Room Database Reactive Flows
    val recentlyPlayed: StateFlow<List<MusicTrack>> = repository.getRecentlyPlayedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val favoriteTracks: StateFlow<List<MusicTrack>> = repository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Database & Recommendation Engines
    private val database by lazy { MusicDatabase.getDatabase(application) }
    private val listeningEventDao by lazy { database.listeningEventDao() }
    private val personalizedEngine by lazy { PersonalizedHomeRecommendationEngine(listeningEventDao) }

    // =========================================================================
    // EXACT MAXIMUM 6 CARDS ARCHITECTURE:
    // 1. Small Pills-Based Card (2x3 Grid Layout - 6 items total)
    // 2. YouTube Music Home Screen-Based Card ("Quick Picks")
    // 3. Era-Specific Hits Card ("Era-Specific Hits", 90s, 2000s classics)
    // 4. User Selected Country & Language Card (Dynamically powered by user prefs)
    // 5. User Listening History Based Card (Room DB history DAO & frequency analysis)
    // 6. Fully Personalized Recommendation Card (PersonalizedHomeRecommendationEngine)
    // Restored Infinite Radio Hero Card Seed Track
    val heroSeedTrack: StateFlow<MusicTrack?> = combine(
        recentlyPlayed,
        favoriteTracks,
        _catalogTracks
    ) { recent, favorites, catalog ->
        recent.firstOrNull()
            ?: favorites.firstOrNull()
            ?: catalog.firstOrNull()
            ?: MusicDataSource.curatedTracks.firstOrNull()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        MusicDataSource.curatedTracks.firstOrNull()
    )

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
            subtitle = "Official regional charts & trending hits",
            isLoading = true
        )
    )
    val countryLanguageCard: StateFlow<SectionState> = _countryLanguageCard.asStateFlow()

    // Card 5: User Listening History Based Card
    private val _listeningHistoryCard = MutableStateFlow(
        SectionState(title = "Jump Back In", subtitle = "Based on your recent listening history & frequency", isLoading = true)
    )
    val listeningHistoryCard: StateFlow<SectionState> = _listeningHistoryCard.asStateFlow()

    // Card 6: Fully Personalized Recommendation Card
    private val _personalizedCard = MutableStateFlow(
        SectionState(title = "Recommended for You", subtitle = "Curated picks tailored to your sonic taste", isLoading = true)
    )
    val personalizedCard: StateFlow<SectionState> = _personalizedCard.asStateFlow()

    // Smart Shelves flow (backward compatibility)
    private val currentPlayingTrackFlow = playbackManager.uiState
        .map { it.currentTrack?.id to it.currentTrack }
        .distinctUntilChanged { old, new -> old.first == new.first }
        .map { it.second }

    val homeShelves: StateFlow<List<HomeShelf>> = combine(
        currentPlayingTrackFlow,
        _catalogTracks,
        _userProfile,
        listeningEventDao.getMeaningfulListeningCountFlow()
    ) { _, catalog, profile, _ ->
        withContext(Dispatchers.Default) {
            personalizedEngine.generateHomeShelves(
                catalogTracks = catalog,
                userProfile = profile
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

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

        val countryItem = CountryData.findCountry(countryCode) ?: CountryData.findCountry(country)
        val countryName = countryItem?.name ?: country.trim().ifBlank { "India" }
        val title = getAuthoritativeTrendingTitle(countryName)
        val resolvedCode = countryItem?.code ?: countryCode.ifBlank { "IN" }

        // Check if data is already cached for this exact country
        val cached = if (!forceRefresh) repository.getCachedRegionalTrending(countryName) else null
        if (cached != null && cached.isNotEmpty()) {
            _countryLanguageCard.value = SectionState(
                title = title,
                subtitle = "Official regional charts & trending hits",
                tracks = cached,
                isLoading = false,
                isLoaded = true
            )
            return
        }

        // Set title and loading state immediately for this country.
        // Never keep previous country's tracks under the new country's title!
        _countryLanguageCard.value = SectionState(
            title = title,
            subtitle = "Official regional charts & trending hits",
            tracks = emptyList(),
            isLoading = true,
            isLoaded = false
        )

        regionalTrendingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val songs = repository.getRegionalTrending(
                    country = countryName,
                    countryCode = resolvedCode,
                    limit = 20,
                    forceRefresh = forceRefresh
                )
                if (songs.isNotEmpty()) {
                    _countryLanguageCard.value = SectionState(
                        title = title,
                        subtitle = "Official regional charts & trending hits",
                        tracks = songs,
                        isLoading = false,
                        isLoaded = true
                    )
                } else {
                    _countryLanguageCard.value = SectionState(
                        title = title,
                        subtitle = "Official regional charts & trending hits",
                        tracks = emptyList(),
                        isLoading = false,
                        isLoaded = false,
                        isError = true,
                        errorMessage = "Regional trending charts are currently unavailable for $countryName."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _countryLanguageCard.value = SectionState(
                    title = title,
                    subtitle = "Official regional charts & trending hits",
                    tracks = emptyList(),
                    isLoading = false,
                    isLoaded = false,
                    isError = true,
                    errorMessage = "Failed to load trending charts for $countryName. Pull down to retry."
                )
            }
        }
    }

    fun updateUserProfile(profile: UserProfile) {
        UserProfilePreferences.saveUserProfile(getApplication(), profile)
        _userProfile.value = profile
        loadRegionalTrending(profile.country, profile.countryCode, forceRefresh = false)
    }

    init {
        // Step 1: Immediately render local Room cached data (<10ms fast)
        loadInitialLocalData()

        // Step 2: Observe user profile changes reactively across the entire app
        viewModelScope.launch {
            UserProfilePreferences.getUserProfileFlow(getApplication()).collect { newProfile ->
                val prev = _userProfile.value
                val countryChanged = !newProfile.country.equals(prev.country, ignoreCase = true) ||
                        !newProfile.countryCode.equals(prev.countryCode, ignoreCase = true)
                _userProfile.value = newProfile
                if (countryChanged) {
                    loadRegionalTrending(newProfile.country, newProfile.countryCode, forceRefresh = false)
                }
            }
        }

        // Step 3: Trigger fresh fetch call on app/home launch
        refreshHome(isExplicitPull = false)
    }

    private fun loadInitialLocalData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val initial = repository.getInitialCatalog(_userProfile.value)
                _catalogTracks.value = initial
                playbackManager.setCandidatePool(initial)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Graceful handling of local data reading error
            }
        }
    }

    /**
     * Fresh fetch & dynamic content logic with smooth Pull-to-Refresh support:
     * - Systematically runs strictly on Dispatchers.IO.
     * - Prevents duplicate concurrent refreshes.
     * - Invalidates stale in-memory state and re-fetches all dynamic feeds in parallel.
     * - Fallback gracefully to Room DB cached data if network requests fail or offline.
     */
    fun refreshHome(isExplicitPull: Boolean = true) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _refreshErrorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = _userProfile.value
                val countryDisplayName = resolveCountryDisplayName(profile)
                val resolvedCode = profile.countryCode.ifBlank { "IN" }

                // Refresh genuine regional trending for the authoritative selected country
                loadRegionalTrending(countryDisplayName, resolvedCode, forceRefresh = isExplicitPull)

                coroutineScope {
                    // 1. Fresh Catalog Sync
                    val catalogDeferred = async {
                        try {
                            repository.syncOnlineCatalog(profile)
                        } catch (_: Exception) {
                            repository.getInitialCatalog(profile)
                        }
                    }

                    // 2. Fresh YouTube Music Quick Picks
                    val ytmDeferred = async {
                        try {
                            repository.getYtmQuickPicks(limit = 15, forceRefresh = true)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    // 3. Fresh Era-Specific Hits
                    val eraDeferred = async {
                        try {
                            repository.getEraHits(limit = 15, forceRefresh = true)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    // 4. Fresh Listening History from Room DB
                    val historyDeferred = async {
                        try {
                            val events = listeningEventDao.getRecentEvents(25)
                            val recentTracks = database.musicDao().getCachedTracksSync(30).map { it.toMusicTrack() }
                            val orderedHistory = mutableListOf<MusicTrack>()
                            for (ev in events) {
                                val match = recentTracks.firstOrNull { it.id == ev.trackId }
                                if (match != null && orderedHistory.none { it.id == match.id }) {
                                    orderedHistory.add(match)
                                }
                            }
                            if (orderedHistory.isEmpty()) {
                                recentTracks.take(15)
                            } else {
                                orderedHistory
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    // 5. Fresh Personalized Recommendations
                    val personalizedDeferred = async {
                        try {
                            val catalog = catalogDeferred.await()
                            val shelves = personalizedEngine.generateHomeShelves(
                                catalogTracks = catalog.ifEmpty { _catalogTracks.value },
                                userProfile = profile,
                                forceRefresh = true
                            )
                            shelves.flatMap { it.tracks }.distinctBy { it.id }.take(15)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    val freshCatalog = catalogDeferred.await()
                    if (freshCatalog.isNotEmpty()) {
                        _catalogTracks.value = freshCatalog
                        playbackManager.setCandidatePool(freshCatalog)
                    }

                    val freshYtm = ytmDeferred.await()
                    _ytmCard.value = _ytmCard.value.copy(
                        tracks = freshYtm.ifEmpty { _catalogTracks.value.take(12) },
                        isLoading = false,
                        isLoaded = true
                    )

                    val freshEra = eraDeferred.await()
                    _eraCard.value = _eraCard.value.copy(
                        tracks = freshEra.ifEmpty { _catalogTracks.value.take(12) },
                        isLoading = false,
                        isLoaded = true
                    )

                    val freshHistory = historyDeferred.await()
                    _listeningHistoryCard.value = _listeningHistoryCard.value.copy(
                        tracks = freshHistory.ifEmpty { _catalogTracks.value.take(12) },
                        isLoading = false,
                        isLoaded = true
                    )

                    val freshPersonalized = personalizedDeferred.await()
                    _personalizedCard.value = _personalizedCard.value.copy(
                        tracks = freshPersonalized.ifEmpty { _catalogTracks.value.take(12) },
                        isLoading = false,
                        isLoaded = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Graceful fallback to local Room data
                if (isExplicitPull) {
                    _refreshErrorMessage.value = "Couldn't refresh recommendations. Pull again to retry."
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadEraSectionIfNeeded() {
        if (_eraCard.value.isLoaded || _eraCard.value.isLoading) return
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getEraHits(15)
            _eraCard.value = _eraCard.value.copy(tracks = tracks, isLoading = false, isLoaded = true)
        }
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
