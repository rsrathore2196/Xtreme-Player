package com.example.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.MusicDatabase
import com.example.data.local.UserProfile
import com.example.data.local.UserProfilePreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.example.recommendation.PersonalizedHomeRecommendationEngine
import com.example.ui.ai.AiMoodEngine
import com.example.ui.ai.HomeShelf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loading stages for staged / prioritized startup architecture:
 * - Priority 1 (0s Immediate): Instant UI shell, navigation, mini-player & offline cached room state
 * - Priority 2 (1-3s Fast): Asynchronous primary recommendations, visible hero track & primary charts
 * - Priority 3 (Background Lazy): Secondary lists, metadata enrichment, deeper recommendations
 */
enum class LoadingStage {
    PRIORITY_1_INSTANT_SHELL,
    PRIORITY_2_ESSENTIAL_CONTENT,
    PRIORITY_3_BACKGROUND_ENRICHED
}

@Immutable
data class StagedSection<T>(
    val items: List<T> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
data class HomeState(
    val stage: LoadingStage = LoadingStage.PRIORITY_1_INSTANT_SHELL,
    val userProfile: UserProfile? = null,
    val greeting: String = "",
    val heroTrack: MusicTrack? = null,
    val recentlyPlayed: List<MusicTrack> = emptyList(),
    val favoriteTracks: List<MusicTrack> = emptyList(),
    val primaryRecommendations: List<MusicTrack> = emptyList(),
    val shelves: List<HomeShelf> = emptyList(),
    val isSilentRefreshing: Boolean = false
)

/**
 * Production-ready ViewModel implementing the 3-stage loading architecture
 * with strict Dispatcher boundary separation:
 * - Dispatchers.IO: Room database reads/writes, network queries, disk cache
 * - Dispatchers.Default: CPU-bound palette extraction, collection filtering/sorting, shelf AI logic
 * - Dispatchers.Main: UI state collection and emissions
 */
@Stable
class HomeStagedLoadingViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    application: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(application) {

    // Global loading stage tracker
    private val _currentStage = MutableStateFlow(LoadingStage.PRIORITY_1_INSTANT_SHELL)
    val currentStage: StateFlow<LoadingStage> = _currentStage.asStateFlow()

    // Priority 1: User Profile cached locally (< 1ms read)
    private val _userProfile = MutableStateFlow(UserProfilePreferences.getUserProfile(application))
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    // Priority 1: Instant Cached Tracks (Room DB Flow)
    val recentlyPlayed: StateFlow<List<MusicTrack>> = repository.getRecentlyPlayedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val favoriteTracks: StateFlow<List<MusicTrack>> = repository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Priority 2: Primary Online Catalog & Hero Track
    private val _primaryCatalog = MutableStateFlow<List<MusicTrack>>(emptyList())
    val primaryCatalog: StateFlow<List<MusicTrack>> = _primaryCatalog.asStateFlow()

    private val _heroTrack = MutableStateFlow<MusicTrack?>(null)
    val heroTrack: StateFlow<MusicTrack?> = _heroTrack.asStateFlow()

    // Priority 3: Deep Shelves & Secondary Categorized Rows
    private val _secondaryShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val secondaryShelves: StateFlow<List<HomeShelf>> = _secondaryShelves.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var priority2Job: Job? = null
    private var priority3Job: Job? = null

    init {
        // Step 1: Execute Priority 1 immediate initialization
        executePriority1Init()

        // Step 2: Trigger Priority 2 essential fetch asynchronously
        schedulePriority2Fetch()

        // Step 3: Trigger Priority 3 background enrichment lazily
        schedulePriority3LazyFetch()
    }

    /**
     * Priority 1 (0s Immediate):
     * Loads offline cached database records and local candidate pool into memory.
     * Guaranteed never to stall the Android main thread.
     */
    private fun executePriority1Init() {
        viewModelScope.launch(ioDispatcher) {
            val cachedCatalog = repository.getInitialCatalog(_userProfile.value)
            _primaryCatalog.value = cachedCatalog
            if (cachedCatalog.isNotEmpty()) {
                _heroTrack.value = cachedCatalog.firstOrNull()
                playbackManager.setCandidatePool(cachedCatalog)
            }
        }
    }

    /**
     * Priority 2 (1-3s Fast):
     * Asynchronously queries online recommendation APIs for primary catalog,
     * updates hero banner track, and triggers visible artwork pre-fetching.
     */
    private fun schedulePriority2Fetch() {
        priority2Job?.cancel()
        priority2Job = viewModelScope.launch(ioDispatcher) {
            delay(1200L) // Allow initial Compose animation frames to draw without thread contention
            _currentStage.value = LoadingStage.PRIORITY_2_ESSENTIAL_CONTENT
            try {
                val fresh = repository.syncOnlineCatalog(_userProfile.value)
                if (fresh.isNotEmpty()) {
                    _primaryCatalog.value = fresh
                    _heroTrack.value = fresh.firstOrNull()
                    playbackManager.setCandidatePool(fresh)
                }
            } catch (_: Exception) {
                // Non-fatal: UI remains 100% stable with Priority 1 cached records
            }
        }
    }

    /**
     * Priority 3 (Background Lazy):
     * Computes AI personalized shelves, era collections, and secondary playlists
     * strictly on Dispatchers.Default, preventing any frame drops during scroll.
     */
    private fun schedulePriority3LazyFetch() {
        priority3Job?.cancel()
        priority3Job = viewModelScope.launch(defaultDispatcher) {
            delay(3500L) // Wait until UI is fully idle and user has begun interacting
            _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED

            val recent = recentlyPlayed.value
            val favs = favoriteTracks.value
            val catalog = _primaryCatalog.value
            val profile = _userProfile.value

            val shelves = PersonalizedHomeRecommendationEngine(MusicDatabase.getDatabase(getApplication()).listeningEventDao())
                .generateHomeShelves(
                    catalogTracks = catalog,
                    userProfile = profile
                )

            withContext(Dispatchers.Main) {
                _secondaryShelves.value = shelves
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch(ioDispatcher) {
            _isRefreshing.value = true
            try {
                val fresh = repository.syncOnlineCatalog(_userProfile.value)
                if (fresh.isNotEmpty()) {
                    _primaryCatalog.value = fresh
                    _heroTrack.value = fresh.firstOrNull()
                    playbackManager.setCandidatePool(fresh)
                }
                val shelves = withContext(defaultDispatcher) {
                    AiMoodEngine.generatePersonalizedShelves(
                        lastPlayedTrack = playbackManager.uiState.value.currentTrack,
                        recentlyPlayed = recentlyPlayed.value,
                        favoriteTracks = favoriteTracks.value,
                        catalogTracks = fresh,
                        userProfile = _userProfile.value
                    )
                }
                _secondaryShelves.value = shelves
            } catch (_: Exception) {
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
