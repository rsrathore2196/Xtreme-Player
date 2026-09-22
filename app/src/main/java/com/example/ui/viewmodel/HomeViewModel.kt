package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserProfile
import com.example.data.local.UserProfilePreferences
import com.example.data.model.MusicTrack
import com.example.data.remote.MusicDataSource
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.example.ui.ai.AiMoodEngine
import com.example.ui.ai.HomeShelf
import kotlinx.coroutines.Dispatchers
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

data class SectionState(
    val title: String = "",
    val subtitle: String = "",
    val tracks: List<MusicTrack> = emptyList(),
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false
)

data class HomeUiState(
    val greeting: String = "",
    val userProfile: UserProfile? = null,
    val heroTrack: MusicTrack? = null,
    val catalogTracks: List<MusicTrack> = emptyList(),
    val recentlyPlayed: List<MusicTrack> = emptyList(),
    val favoriteTracks: List<MusicTrack> = emptyList(),
    val quickPicks: List<MusicTrack> = emptyList(),
    val shelves: List<HomeShelf> = emptyList(),
    val isSilentRefreshing: Boolean = false
)

class HomeViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    application: Application
) : AndroidViewModel(application) {

    // 1. Instant User Profile (< 1ms)
    private val _userProfile = MutableStateFlow(UserProfilePreferences.getUserProfile(application))
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    // 2. Instant Local Catalog state (Local-First: Room/Memory/Curated)
    private val _catalogTracks = MutableStateFlow<List<MusicTrack>>(emptyList())
    val catalogTracks: StateFlow<List<MusicTrack>> = _catalogTracks.asStateFlow()

    // 3. Room Database Reactive Flows for Recently Played and Favorites
    val recentlyPlayed: StateFlow<List<MusicTrack>> = repository.getRecentlyPlayedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val favoriteTracks: StateFlow<List<MusicTrack>> = repository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // 4. Reactive Smart Shelves combined on Dispatchers.Default, isolating currentTrack
    // This prevents ExoPlayer's 300ms progress ticker from re-calculating shelves or recomposing HomeScreen
    private val currentPlayingTrackFlow = playbackManager.uiState
        .map { it.currentTrack?.id to it.currentTrack }
        .distinctUntilChanged { old, new -> old.first == new.first }
        .map { it.second }

    val homeShelves: StateFlow<List<HomeShelf>> = combine(
        currentPlayingTrackFlow,
        recentlyPlayed,
        favoriteTracks,
        _catalogTracks,
        _userProfile
    ) { currentTrack, recent, favs, catalog, profile ->
        withContext(Dispatchers.Default) {
            AiMoodEngine.generatePersonalizedShelves(
                lastPlayedTrack = currentTrack,
                recentlyPlayed = recent,
                favoriteTracks = favs,
                catalogTracks = catalog,
                userProfile = profile
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // 5. Deferred Lazy-Loaded Sections (Loaded only when scrolled into view)
    private val _punjabiSection = MutableStateFlow(
        SectionState(title = "Top Punjabi Artists & Hits", subtitle = "Trending charts, bhangra beats & viral tracks")
    )
    val punjabiSection: StateFlow<SectionState> = _punjabiSection.asStateFlow()

    private val _eraSection = MutableStateFlow(
        SectionState(title = "Era-Specific Hits", subtitle = "Golden 90s, 2000s classics & retro anthems")
    )
    val eraSection: StateFlow<SectionState> = _eraSection.asStateFlow()

    // Silent background refreshing indicator (does NOT block screen)
    private val _isSilentRefreshing = MutableStateFlow(false)
    val isSilentRefreshing: StateFlow<Boolean> = _isSilentRefreshing.asStateFlow()

    init {
        // Step 1: Immediately emit local cached data (<15ms UI render)
        loadInitialCachedData()

        // Step 2: Concurrently fetch fresh online data in the background (Stale-While-Revalidate)
        syncOnlineDataConcurrently()
    }

    /**
     * Emits local cached data from Room and memory synchronously / within < 10ms.
     * Prevents any blank screen or global loading blocking on startup.
     */
    private fun loadInitialCachedData() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val initial = repository.getInitialCatalog(_userProfile.value)
            _catalogTracks.value = initial
            playbackManager.setCandidatePool(initial)
        }
    }

    /**
     * Executes API requests in parallel via coroutineScope with async / awaitAll.
     * Never blocks main thread or stalls UI rendering.
     */
    fun syncOnlineDataConcurrently() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSilentRefreshing.value = true
            try {
                coroutineScope {
                    val catalogDeferred = async { repository.syncOnlineCatalog(_userProfile.value) }
                    val enriched = catalogDeferred.await()
                    if (enriched.isNotEmpty()) {
                        _catalogTracks.value = enriched
                        playbackManager.setCandidatePool(enriched)
                    }
                }
            } catch (e: Exception) {
                // Non-fatal: Local catalog remains active without interrupting user
            } finally {
                _isSilentRefreshing.value = false
            }
        }
    }

    /**
     * Deferred loading: only called when the Punjabi section scrolls into view.
     */
    fun loadPunjabiSectionIfNeeded() {
        if (_punjabiSection.value.isLoaded || _punjabiSection.value.isLoading) return
        _punjabiSection.value = _punjabiSection.value.copy(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getPunjabiHits(15)
            _punjabiSection.value = _punjabiSection.value.copy(
                tracks = tracks,
                isLoading = false,
                isLoaded = true
            )
        }
    }

    /**
     * Deferred loading: only called when the Era Specific section scrolls into view.
     */
    fun loadEraSectionIfNeeded() {
        if (_eraSection.value.isLoaded || _eraSection.value.isLoading) return
        _eraSection.value = _eraSection.value.copy(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            val tracks = repository.getEraHits(15)
            _eraSection.value = _eraSection.value.copy(
                tracks = tracks,
                isLoading = false,
                isLoaded = true
            )
        }
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack>) {
        playbackManager.playTrack(track, queue)
    }

    fun toggleFavorite(track: MusicTrack) {
        viewModelScope.launch {
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
