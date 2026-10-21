package com.example.ui.viewmodel

import com.example.performance.AppDispatchers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.importer.ImportedTrackMeta
import com.example.data.importer.PlaylistImportEngine
import com.example.data.importer.PlaylistImportSummary
import com.example.data.importer.MatchResult
import com.example.data.importer.MatchStatus
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistWithTracks
import com.example.data.model.MusicTrack
import com.example.data.model.TrackLyrics
import com.example.data.model.SongIdentity
import com.example.data.remote.LyricsProvider
import com.example.data.remote.MusicDataSource
import com.example.data.repository.MusicRepository
import com.example.data.repository.SearchResultCategory
import com.example.playback.AudioQuality
import com.example.playback.PlaybackManager
import com.example.playback.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

@Immutable
sealed interface PlaylistImportStep {
    object Idle : PlaylistImportStep
    data class Fetching(val platform: String, val message: String) : PlaylistImportStep
    data class Matching(
        val platform: String,
        val playlistTitle: String,
        val current: Int,
        val total: Int,
        val currentTrackName: String,
        val matchedCount: Int
    ) : PlaylistImportStep
    data class Summary(val summary: PlaylistImportSummary) : PlaylistImportStep
    data class Saving(val title: String) : PlaylistImportStep
    data class Success(val playlistId: Long, val playlistTitle: String, val trackCount: Int) : PlaylistImportStep
    data class Error(val message: String) : PlaylistImportStep
}

@Immutable
data class PlaylistImportUiState(
    val isDialogOpen: Boolean = false,
    val selectedPlatform: String = "Link", // "Link", "Spotify", "YouTube Music", "Apple Music", "CSV/Text"
    val inputUrlOrText: String = "",
    val directApiKeyOrToken: String = "",
    val step: PlaylistImportStep = PlaylistImportStep.Idle,
    val customTitle: String = "",
    val customDescription: String = "",
    val lastSummary: PlaylistImportSummary? = null,
    val retryingRows: Set<Int> = emptySet(),
    val retryMessages: Map<Int, String> = emptyMap()
)

@Immutable
data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val selectedGenre: String = "All",
    val selectedSource: String = "All", // "All", "HD Stream", "Extended Stream"
    val result: SearchResultCategory = SearchResultCategory(),
    val recentSearches: List<String> = emptyList(),
    val displayDirectTracks: List<MusicTrack>? = null,
    val matchingPlaylistGenres: List<String>? = null,
    internal val requestId: Long = 0L
)

class PlayerViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    private val application: android.app.Application = com.example.XtremeMusicApp.getInstance()
) : ViewModel() {

    private val derivedStateDispatcher = AppDispatchers.CpuBounded
    private val derivedStateScope = viewModelScope + derivedStateDispatcher
    val playerUiState: StateFlow<PlayerUiState> = playbackManager.uiState
    // Progress has dedicated flows/providers. It must not invalidate the entire navigation tree.
    // Paused positions remain observable so external seeks still update the stationary player.
    val navigationPlayerUiState = playerUiState.map { state ->
        state.copy(currentPositionMs = if (state.isPlaying) 0L else state.currentPositionMs, bufferedPositionMs = 0L)
    }.distinctUntilChanged().flowOn(derivedStateDispatcher)
        .stateIn(derivedStateScope, SharingStarted.WhileSubscribed(5_000L), playerUiState.value)

    val playbackPosition: StateFlow<Long> = playbackManager.playbackPosition
    val bufferedPosition: StateFlow<Long> = playbackManager.bufferedPosition
    val trackDuration: StateFlow<Long> = playbackManager.trackDuration
    val currentPosition: Long get() = playbackManager.currentPlaybackPositionMs

    val selectedQuality: StateFlow<AudioQuality> = playbackManager.uiState
        .map { it.selectedQuality }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), playbackManager.uiState.value.selectedQuality)

    val currentPlayingTrackId: StateFlow<String?> = playbackManager.uiState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    private val _currentLyrics = MutableStateFlow<TrackLyrics?>(null)
    val currentLyrics: StateFlow<TrackLyrics?> = _currentLyrics.asStateFlow()
    private var lyricsJob: Job? = null
    private var lastObservedLyricsIdentity: SongIdentity? = null
    private val lyricsRequestGeneration = AtomicLong()
    private var lyricsPrefetchStarted = false
    private var lyricsPrefetchAdmission: Job? = null
    @Volatile private var lyricsLookupComplete = false
    private var lyricsVisible = false

    private val _catalogTracks = MutableStateFlow<List<MusicTrack>>(emptyList())
    val catalogTracks: StateFlow<List<MusicTrack>> = _catalogTracks.asStateFlow()

    val catalogBrowser = CatalogBrowserState(repository.catalogBrowser, viewModelScope,
        initialQuality = playbackManager.uiState.value.selectedQuality,
        verifyPlayback = repository::verifySearchPlayback, archiveSearch = repository::searchArchive,
        addonSearch = { query, policy -> com.example.data.remote.StreamingAddons.search(application, query, policy) })

    private val _searchState = MutableStateFlow(SearchUiState())
    fun hasActiveSearch(): Boolean = _searchState.value.let { it.query.isNotBlank() || it.selectedGenre != "All" }
    fun resetSearchNavigation() {
        selectGenre("All")
        if (_searchState.value.query.isNotBlank()) onSearchQueryChange("")
    }
    val searchState: StateFlow<SearchUiState> = _searchState.map { state ->
        val results = state.result
        val raw = results.exactMatches.ifEmpty { results.songs }
        val direct = when (state.selectedSource) {
            "HD Stream" -> results.jioMatches.ifEmpty { raw.filter { it.source != "Extended Stream" && !it.id.startsWith("yt_") } }
            "Extended Stream" -> results.ytMatches.ifEmpty { raw.filter { it.source == "Extended Stream" || it.id.startsWith("yt_") } }
            else -> raw
        }
        state.copy(displayDirectTracks = if (state.query.isNotBlank()) direct.take(5) else direct,
            matchingPlaylistGenres = MusicDataSource.genres.filter { it.contains(state.query, ignoreCase = true) }
                .ifEmpty { MusicDataSource.genres.take(6) })
    }.flowOn(derivedStateDispatcher)
        // Only local transformations; keep the prepared state alive before the first
        // Search composition. This starts no catalog or recommendation request.
        .stateIn(derivedStateScope, SharingStarted.Eagerly, _searchState.value)

    private val _selectedPlaylistTracks = MutableStateFlow<PlaylistWithTracks?>(null)
    val selectedPlaylistTracks: StateFlow<PlaylistWithTracks?> = _selectedPlaylistTracks.asStateFlow()

    private var searchJob: Job? = null
    private val legacySearchGeneration = AtomicLong()
    private val recentSearchDispatcher = AppDispatchers.Io
    private val recentSearchMutex = Mutex()
    private val recentSearchRevision = AtomicLong()
    private val favoriteUpdateMutex = Mutex()
    private var playlistTracksJob: Job? = null

    // Reuse the ViewModel's lifecycle job while keeping large-list publication off Main.
    private val libraryDataScope = viewModelScope + derivedStateDispatcher
    private val localLibraryLoaded = List(3) { kotlinx.coroutines.CompletableDeferred<Unit>() }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> retainedLibraryData(initial: T, loaded: kotlinx.coroutines.CompletableDeferred<Unit>,
        source: () -> kotlinx.coroutines.flow.Flow<T>): StateFlow<T> =
        com.example.performance.BackgroundWorkCoordinator.uiForeground.flatMapLatest { foreground ->
            if (foreground) kotlinx.coroutines.flow.flow {
                com.example.performance.BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
                try {
                    source().collect {
                        emit(it)
                        loaded.complete(Unit)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    loaded.completeExceptionally(error)
                    android.util.Log.w("PlayerViewModel", "Local library snapshot unavailable", error)
                }
            } else emptyFlow()
        }.stateIn(libraryDataScope, SharingStarted.Lazily, initial)

    val favoriteTracks: StateFlow<List<MusicTrack>> = retainedLibraryData(repository.retainedFavoriteTracks, localLibraryLoaded[0]) {
        repository.getFavoriteTracks(derivedStateDispatcher)
    }

    val recentlyPlayed: StateFlow<List<MusicTrack>> = retainedLibraryData(repository.retainedRecentlyPlayed, localLibraryLoaded[1]) {
        repository.getRecentlyPlayedTracks()
    }

    // Foreground collectors replay the repository's one shared Room snapshot.
    // Tab changes never restart observation; backgrounding retains the last value.
    val playlists: StateFlow<List<PlaylistEntity>> = retainedLibraryData(repository.retainedPlaylists, localLibraryLoaded[2]) { repository.getAllPlaylists() }

    private val preparedSettings = (application as? com.example.XtremeMusicApp)?.takeInitialStartupSettings()
    private val initialSettings = preparedSettings ?: com.example.data.local.StartupSettings()
    private val _settingsReady = MutableStateFlow(preparedSettings != null)
    val settingsReady: StateFlow<Boolean> = _settingsReady.asStateFlow()
    private val initialThemeMode = initialSettings.themeMode
    private val _themeMode = MutableStateFlow(initialThemeMode)
    val themeMode: StateFlow<com.example.data.local.AppThemeMode> = _themeMode.asStateFlow()

    private val _textScale = MutableStateFlow(initialSettings.textScale)
    val textScale: StateFlow<Float> = _textScale.asStateFlow()

    private val _textScaleIndex = MutableStateFlow(initialSettings.textSizeIndex)
    val textScaleIndex: StateFlow<Int> = _textScaleIndex.asStateFlow()

    private val _uiScale = MutableStateFlow(initialSettings.uiScale)
    val uiScale: StateFlow<Float> = _uiScale.asStateFlow()

    private val _uiScaleIndex = MutableStateFlow(initialSettings.uiSizeIndex)
    val uiScaleIndex: StateFlow<Int> = _uiScaleIndex.asStateFlow()

    private val _isDynamicGlassEnabled = MutableStateFlow(initialSettings.dynamicGlass)
    val isDynamicGlassEnabled: StateFlow<Boolean> = _isDynamicGlassEnabled.asStateFlow()

    private var searchDataStarted = false
    private var catalogFavoritesStarted = false
    fun prepareCatalogUi(searchVisible: Boolean, detailVisible: Boolean) {
        if (searchVisible && !searchDataStarted) {
            searchDataStarted = true
            viewModelScope.launch(recentSearchDispatcher) {
                com.example.performance.BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
                val revision = recentSearchRevision.get()
                recentSearchMutex.withLock {
                    val recent = com.example.data.local.RecentSearchPreferences.getRecentSearches(application)
                    if (recentSearchRevision.get() == revision) _searchState.update { it.copy(recentSearches = recent) }
                }
            }
        }
        if ((searchVisible || detailVisible) && !catalogFavoritesStarted) {
            catalogFavoritesStarted = true
            derivedStateScope.launch {
                com.example.performance.BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
                // Reuse the local snapshot/observer already warmed for Library.
                // First Search entry must not start another Room favorites query.
                favoriteTracks.map { tracks -> tracks.mapTo(hashSetOf()) { it.id } }
                    .distinctUntilChanged().collect { catalogBrowser.updateFavorites(it) }
            }
        }
    }

    // Activity-retained startup requests survive navigation and configuration changes.
    private var notificationPermissionRequested = false
    private val notificationPermissionPending = MutableStateFlow(false)
    private var automaticUpdateRequested = false
    val canRequestNotificationPermission: Boolean get() = !notificationPermissionRequested
    fun beginNotificationPermissionRequest(): Boolean {
        if (notificationPermissionRequested) return false
        notificationPermissionRequested = true
        notificationPermissionPending.value = true
        return true
    }
    fun finishNotificationPermissionRequest() { notificationPermissionPending.value = false }
    suspend fun awaitNotificationPermissionResult() { notificationPermissionPending.first { !it } }
    fun beginAutomaticUpdateCheck(): Boolean {
        if (automaticUpdateRequested) return false
        automaticUpdateRequested = true
        return true
    }

    fun setDynamicGlassEnabled(enabled: Boolean) {
        com.example.data.local.OtherSettingsPreferences.setDynamicGlassEnabled(application, enabled)
        _isDynamicGlassEnabled.value = enabled
    }

    fun setTextScaleIndex(index: Int) {
        com.example.data.local.OtherSettingsPreferences.setTextSizeIndex(application, index)
        _textScaleIndex.value = index
        _textScale.value = com.example.data.local.OtherSettingsPreferences.getTextScale(application)
    }

    fun setUiScaleIndex(index: Int) {
        com.example.data.local.OtherSettingsPreferences.setUiSizeIndex(application, index)
        _uiScaleIndex.value = index
        _uiScale.value = com.example.data.local.OtherSettingsPreferences.getUiScale(application)
    }

    private val _userProfile = MutableStateFlow(initialSettings.profile)
    val userProfile: StateFlow<com.example.data.local.UserProfile> = _userProfile.asStateFlow()

    private val _isOnboardingCompleted = MutableStateFlow(initialSettings.profile.isOnboardingCompleted)
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()

    fun completeOnboarding(
        name: String,
        languages: List<String>,
        country: String,
        countryCode: String,
        flag: String
    ) {
        val profile = com.example.data.local.UserProfile(
            name = name.trim().ifBlank { "User" },
            languages = if (languages.isNotEmpty()) languages else listOf("English", "Hindi", "Punjabi"),
            country = country.trim().ifBlank { "India" },
            countryCode = countryCode.trim().ifBlank { "IN" },
            flag = flag.trim().ifBlank { "🇮🇳" },
            isOnboardingCompleted = true
        )
        _userProfile.value = profile
        try {
            com.example.data.local.UserProfilePreferences.saveUserProfile(application, profile)
        } catch (e: Exception) {
            android.util.Log.e("PlayerViewModel", "Failed to save user profile: ${e.message}")
        }
        _isOnboardingCompleted.value = true
        try {
            loadCatalog()
        } catch (e: Exception) {
            android.util.Log.e("PlayerViewModel", "Failed to load catalog after onboarding: ${e.message}")
        }
    }

    fun updateUserProfile(profile: com.example.data.local.UserProfile) {
        val updated = profile.copy(isOnboardingCompleted = true)
        _userProfile.value = updated
        _isOnboardingCompleted.value = true
        com.example.data.local.UserProfilePreferences.saveUserProfile(application, updated)
        loadCatalog()
    }

    private val _isDarkMode = MutableStateFlow(
        when (initialThemeMode) {
            com.example.data.local.AppThemeMode.SYSTEM -> {
                val nightModeFlags = application.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            com.example.data.local.AppThemeMode.DARK -> true
            com.example.data.local.AppThemeMode.LIGHT -> false
        }
    )
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun setThemeMode(mode: com.example.data.local.AppThemeMode) {
        _themeMode.value = mode
        com.example.data.local.ThemePreferences.setThemeMode(application, mode)
        val isDark = when (mode) {
            com.example.data.local.AppThemeMode.DARK -> true
            com.example.data.local.AppThemeMode.LIGHT -> false
            com.example.data.local.AppThemeMode.SYSTEM -> {
                val nightModeFlags = application.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
        _isDarkMode.value = isDark
        val defaultPreset = if (isDark) {
            com.example.data.local.ThemePresets.StudioNight
        } else {
            com.example.data.local.ThemePresets.CleanDay
        }
        val updatedState = defaultPreset.toCustomThemeState()
        _customThemeState.value = updatedState
        com.example.data.local.ThemePreferences.saveCustomThemeState(application, updatedState)
    }

    fun setResolvedDarkMode(isDark: Boolean) {
        _isDarkMode.value = isDark
    }

    fun setDarkMode(enabled: Boolean) {
        setThemeMode(if (enabled) com.example.data.local.AppThemeMode.DARK else com.example.data.local.AppThemeMode.LIGHT)
    }

    fun toggleDarkMode() {
        when (_themeMode.value) {
            com.example.data.local.AppThemeMode.SYSTEM -> {
                setThemeMode(if (_isDarkMode.value) com.example.data.local.AppThemeMode.LIGHT else com.example.data.local.AppThemeMode.DARK)
            }
            com.example.data.local.AppThemeMode.DARK -> setThemeMode(com.example.data.local.AppThemeMode.LIGHT)
            com.example.data.local.AppThemeMode.LIGHT -> setThemeMode(com.example.data.local.AppThemeMode.DARK)
        }
    }

    private val _customThemeState = MutableStateFlow(initialSettings.customTheme)
    val customThemeState: StateFlow<com.example.data.local.CustomThemeState> = _customThemeState.asStateFlow()

    fun updateCustomThemeState(newState: com.example.data.local.CustomThemeState) {
        _customThemeState.value = newState
        com.example.data.local.ThemePreferences.saveCustomThemeState(application, newState)
    }

    fun applyThemePreset(preset: com.example.data.local.AppThemePreset) {
        val targetMode = if (preset.isDark) com.example.data.local.AppThemeMode.DARK else com.example.data.local.AppThemeMode.LIGHT
        _themeMode.value = targetMode
        com.example.data.local.ThemePreferences.setThemeMode(application, targetMode)
        _isDarkMode.value = preset.isDark
        val state = preset.toCustomThemeState()
        _customThemeState.value = state
        com.example.data.local.ThemePreferences.saveCustomThemeState(application, state)
    }

    private val currentPlayingTrackFlow = playbackManager.uiState
        .map { it.currentTrack?.id to it.currentTrack }
        .distinctUntilChanged { old, new -> old.first == new.first }
        .map { it.second }

    // Compatibility API: HomeViewModel owns Home. Construct the older engine only
    // if a caller actually requests it, and collect it only while subscribed.
    val homeShelves: StateFlow<List<com.example.ui.ai.HomeShelf>> by lazy { kotlinx.coroutines.flow.combine(
        currentPlayingTrackFlow,
        recentlyPlayed,
        favoriteTracks,
        _catalogTracks,
        _userProfile
    ) { currentTrack, recent, favs, catalog, profile ->
        withContext(AppDispatchers.CpuBounded) {
            com.example.ui.ai.AiMoodEngine.generatePersonalizedShelves(
                lastPlayedTrack = currentTrack,
                recentlyPlayed = recent,
                favoriteTracks = favs,
                catalogTracks = catalog,
                userProfile = profile
            )
        }
    }.flowOn(AppDispatchers.CpuBounded).stateIn(
        viewModelScope + AppDispatchers.CpuBounded,
        SharingStarted.WhileSubscribed(5000L),
        emptyList()
    ) }

    init {
        if (application is com.example.XtremeMusicApp &&
            !com.example.performance.BackgroundWorkCoordinator.startupReadiness.value.complete) {
            viewModelScope.launch(AppDispatchers.Io) {
                val startup = com.example.performance.BackgroundWorkCoordinator
                val task = com.example.performance.StartupTask.LOCAL_TABS
                startup.startupTask(task, com.example.performance.StartupStatus.RUNNING)
                try {
                    startup.startup(com.example.performance.BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI,
                        label = "Local tab snapshots") {
                        kotlinx.coroutines.withTimeout(5_000L) {
                            kotlinx.coroutines.coroutineScope {
                                // Subscribe to the existing retained local flows;
                                // do not compose hidden pages or fetch a catalog.
                                val consumers = listOf(
                                    launch { favoriteTracks.collect { } },
                                    launch { recentlyPlayed.collect { } },
                                    launch { playlists.collect { } })
                                try {
                                    val revision = recentSearchRevision.get()
                                    recentSearchMutex.withLock {
                                        val recent = com.example.data.local.RecentSearchPreferences.getRecentSearches(application)
                                        if (recentSearchRevision.get() == revision)
                                            _searchState.update { it.copy(recentSearches = recent) }
                                    }
                                    withContext(AppDispatchers.Main) { searchDataStarted = true }
                                    localLibraryLoaded.forEach { it.await() }
                                    withContext(derivedStateDispatcher) {
                                        catalogBrowser.updateFavorites(favoriteTracks.value.mapTo(hashSetOf()) { it.id })
                                    }
                                    withContext(AppDispatchers.Main) {
                                        prepareCatalogUi(searchVisible = true, detailVisible = false)
                                    }
                                } finally { consumers.forEach { it.cancel() } }
                            }
                        }
                    }
                    startup.startupTask(task, com.example.performance.StartupStatus.READY)
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    startup.startupTask(task, com.example.performance.StartupStatus.UNAVAILABLE)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("PlayerViewModel", "Initial local tabs unavailable", error)
                    startup.startupTask(task, com.example.performance.StartupStatus.UNAVAILABLE)
                }
            }
        }
        viewModelScope.launch(AppDispatchers.Io) {
            if (preparedSettings != null) return@launch
            try {
                val settings = (application as? com.example.XtremeMusicApp)?.loadStartupSettings()
                    ?: com.example.data.local.StartupSettings.read(application)
                _themeMode.value = settings.themeMode
                _isDarkMode.value = when (settings.themeMode) {
                    com.example.data.local.AppThemeMode.DARK -> true
                    com.example.data.local.AppThemeMode.LIGHT -> false
                    com.example.data.local.AppThemeMode.SYSTEM -> application.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                _customThemeState.value = settings.customTheme
                _textScaleIndex.value = settings.textSizeIndex
                _textScale.value = settings.textScale
                _uiScaleIndex.value = settings.uiSizeIndex
                _uiScale.value = settings.uiScale
                _isDynamicGlassEnabled.value = settings.dynamicGlass
                _userProfile.value = settings.profile
                _isOnboardingCompleted.value = settings.profile.isOnboardingCompleted
            } finally { _settingsReady.value = true }
        }
        viewModelScope.launch(AppDispatchers.CpuBounded) {
            com.example.updater.AppUpdateManager.updateStatus.map { status ->
                status is com.example.updater.UpdateStatus.Checking ||
                    status is com.example.updater.UpdateStatus.Downloading &&
                    !status.message.contains("waiting", ignoreCase = true)
            }.distinctUntilChanged().collectLatest { busy ->
                if (busy) {
                    val work = com.example.performance.BackgroundWorkCoordinator.busy()
                    try { kotlinx.coroutines.awaitCancellation() } finally { work.close() }
                }
            }
        }
        viewModelScope.launch(AppDispatchers.CpuBounded) {
            playbackManager.uiState.map { it.isLoading }.distinctUntilChanged().collectLatest { loading ->
                if (loading) {
                    val work = com.example.performance.BackgroundWorkCoordinator.busy()
                    try { kotlinx.coroutines.awaitCancellation() } finally { work.close() }
                }
            }
        }
        viewModelScope.launch {
            com.example.playback.NetworkQualityMonitor.getInstance(application).sourcePolicy
                .collect(catalogBrowser::setSourcePolicy)
        }
        viewModelScope.launch {
            com.example.data.local.OtherSettingsPreferences.observePlaybackIntegrations(application)
                .collect(catalogBrowser::setStreamingAddon)
        }
        viewModelScope.launch(AppDispatchers.Io) {
            _settingsReady.first { it }
            loadCatalog(com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED)
        }
        observeCurrentTrackForLyrics()
        viewModelScope.launch {
            var previousProvider: String? = null
            com.example.data.local.OtherSettingsPreferences.observeLyricsSettings(application).collect { settings ->
                if (!settings.ready) return@collect
                val changed = previousProvider != null && previousProvider != settings.provider
                previousProvider = settings.provider
                if (changed) {
                    lyricsRequestGeneration.incrementAndGet()
                    lyricsJob?.cancel()
                    _currentLyrics.value = null
                    lyricsLookupComplete = false
                    lyricsPrefetchStarted = false
                    val track = playbackManager.uiState.value.currentTrack
                    if (track != null && lyricsVisible)
                        fetchLyricsForTrack(track, forceRefresh = false)
                }
            }
        }
    }

    private fun observeCurrentTrackForLyrics() {
        viewModelScope.launch {
            combine(playbackManager.uiState, com.example.performance.BackgroundWorkCoordinator.uiForeground) { state, foreground ->
                Triple(state.currentTrack, state.isPlaying && !state.isLoading, foreground)
            }.distinctUntilChanged().flowOn(derivedStateDispatcher).collect { (track, ready, foreground) ->
                val identity = track?.let(SongIdentity::from)
                if (identity != lastObservedLyricsIdentity) {
                    lastObservedLyricsIdentity = identity
                    lyricsPrefetchAdmission?.cancel()
                    lyricsPrefetchAdmission = null
                    lyricsRequestGeneration.incrementAndGet()
                    lyricsJob?.cancel()
                    _currentLyrics.value = null
                    lyricsLookupComplete = false
                    lyricsPrefetchStarted = false
                    if (track != null && lyricsVisible) fetchLyricsForTrack(track, forceRefresh = false)
                }
                // Hidden lyrics are optional work. Stability is tied to this recording,
                // playback readiness and foreground state, never process uptime.
                if (track == null || !ready || !foreground || lyricsVisible) {
                    lyricsPrefetchAdmission?.cancel()
                    lyricsPrefetchAdmission = null
                } else if (!lyricsLookupComplete && !lyricsPrefetchStarted &&
                    lyricsJob?.isActive != true && lyricsPrefetchAdmission?.isActive != true) {
                    lyricsPrefetchAdmission = viewModelScope.launch(AppDispatchers.Io) {
                        kotlinx.coroutines.delay(10_000L)
                        com.example.performance.BackgroundWorkCoordinator.startup(
                            com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                            label = "Idle lyrics prefetch") {
                            val prefetch = withContext(Dispatchers.Main.immediate) {
                                val state = playbackManager.uiState.value
                                if (!lyricsVisible && state.isPlaying && !state.isLoading &&
                                    state.currentTrack?.let(SongIdentity::from) == identity &&
                                    !lyricsLookupComplete && lyricsJob?.isActive != true) {
                                    lyricsPrefetchStarted = true
                                    fetchLyricsForTrack(track, forceRefresh = false)
                                    lyricsJob
                                } else null
                            }
                            prefetch?.join()
                        }
                    }
                }
            }
        }
    }

    fun setLyricsVisible(visible: Boolean) {
        if (lyricsVisible == visible) return
        lyricsVisible = visible
        lyricsPrefetchAdmission?.cancel()
        lyricsPrefetchAdmission = null
        if (visible) {
            if (lyricsJob?.isActive != true && !lyricsLookupComplete) {
                playbackManager.uiState.value.currentTrack?.let { fetchLyricsForTrack(it, forceRefresh = false) }
            }
        } else if (!playbackManager.uiState.value.isPlaying) {
            lyricsRequestGeneration.incrementAndGet()
            lyricsJob?.cancel()
            lyricsPrefetchStarted = false
        }
    }

    fun retryLyrics() {
        val track = playbackManager.uiState.value.currentTrack ?: return
        fetchLyricsForTrack(track, forceRefresh = true)
    }

    private fun fetchLyricsForTrack(track: MusicTrack, forceRefresh: Boolean) {
        lyricsJob?.cancel()
        val generation = lyricsRequestGeneration.incrementAndGet()
        val identity = SongIdentity.from(track)
        lyricsLookupComplete = false
        if (_currentLyrics.value?.trackId != track.id || forceRefresh) _currentLyrics.value = null
        lyricsJob = viewModelScope.launch(AppDispatchers.Io) {
            fun stillSelected(): Boolean = lyricsRequestGeneration.get() == generation &&
                playbackManager.uiState.value.currentTrack?.let(SongIdentity::from) == identity
            try {
                if (forceRefresh) LyricsProvider.clearCacheForTrack(track.id)
                val lyrics = LyricsProvider.getLyricsForTrack(track, forceRefresh = forceRefresh) { improved ->
                    if (stillSelected() && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                        _currentLyrics.value = improved
                    }
                }
                ensureActive()
                if (stillSelected()) {
                    _currentLyrics.value = lyrics
                    lyricsLookupComplete = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stillSelected()) {
                    if (_currentLyrics.value == null) _currentLyrics.value = TrackLyrics(
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        isSynced = false,
                        lines = emptyList()
                    )
                    lyricsLookupComplete = true
                }
            }
        }
    }

    private fun loadCatalog(priority: com.example.performance.BackgroundWorkCoordinator.StartupPriority =
        com.example.performance.BackgroundWorkCoordinator.StartupPriority.CRITICAL) {
        viewModelScope.launch(AppDispatchers.Io) {
            val initial = repository.getInitialCatalog(_userProfile.value, priority)
            _catalogTracks.value = initial
            playbackManager.setCandidatePool(initial)

            // Home owns optional discovery refreshes. Playback can use this real local
            // pool immediately, without launching a competing discovery pass at startup.
        }
    }

    fun renamePlaylist(playlistId: Long, newTitle: String) {
        viewModelScope.launch(AppDispatchers.CpuBounded) {
            withContext(AppDispatchers.Io) { repository.renamePlaylist(playlistId, newTitle) }
            val current = _selectedPlaylistTracks.value
            if (current != null && current.playlist.playlistId == playlistId) {
                val renamed = current.copy(
                    playlist = current.playlist.copy(title = newTitle.trim())
                ).also { it.musicTracks }
                _selectedPlaylistTracks.update { if (it === current) renamed else it }
            }
        }
    }

    fun reloadAfterRestore() {
        viewModelScope.launch(AppDispatchers.Io) {
            _userProfile.value = com.example.data.local.UserProfilePreferences.getUserProfile(application)
            loadCatalog()
            val currentId = _selectedPlaylistTracks.value?.playlist?.playlistId
            if (currentId != null) {
                withContext(Dispatchers.Main.immediate) { openPlaylist(currentId) }
            }
        }
    }

    fun toggleAutoplay() {
        playbackManager.toggleAutoplay(_catalogTracks.value)
    }

    fun addToQueue(track: MusicTrack) {
        playbackManager.addToQueue(track)
    }

    private var activePlaybackLaunchJob: Job? = null

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = _catalogTracks.value, isExplicitPlaylist: Boolean = false) {
        activePlaybackLaunchJob?.cancel()
        activePlaybackLaunchJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            val safeQueue = if (queue.isNotEmpty()) queue else _catalogTracks.value.ifEmpty { listOf(track) }
            playbackManager.playTrack(track, safeQueue, isExplicitPlaylist)
        }
    }

    fun playPlaylistTrack(track: MusicTrack, tracks: List<MusicTrack>) {
        activePlaybackLaunchJob?.cancel()
        activePlaybackLaunchJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            val safeQueue = if (tracks.isNotEmpty()) tracks else listOf(track)
            playbackManager.playTrack(track, safeQueue, isExplicitPlaylist = true)
        }
    }

    fun playCatalogCollection(track: MusicTrack, state: CatalogDetailState) {
        val more = catalogBrowser.playlistPlaybackContinuation(state)
        activePlaybackLaunchJob?.cancel()
        activePlaybackLaunchJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            val queue = state.preparePlaybackQueue(track)
            ensureActive()
            playbackManager.playTrack(track, queue, isExplicitPlaylist = true, collectionLoader = more)
        }
    }

    /**
     * Plays a track selected from search results.
     * Prioritizes the same singer/artist's other songs and matching genre/type
     * rather than blindly following the raw search list.
     */
    fun playFromSearch(track: MusicTrack, searchPool: List<MusicTrack>) {
        activePlaybackLaunchJob?.cancel()
        activePlaybackLaunchJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            playbackManager.playFromSearch(track, searchPool)
        }
    }

    fun retryPlayback() {
        playbackManager.retryPlayback()
    }

    /**
     * Plays a seed track (or dynamically chosen candidate) and activates infinite autoplay mode
     * so that recommendations continue playing infinitely with continuous queue replenishment.
     */
    fun playInfiniteRadio(seedTrack: MusicTrack? = null) {
        val state = playbackManager.uiState.value
        if (state.isInfiniteRadio && (state.isPlaying || state.isLoading)) return
        activePlaybackLaunchJob?.cancel()
        activePlaybackLaunchJob = viewModelScope.launch(AppDispatchers.CpuBounded) {
            val candidates = (_catalogTracks.value + recentlyPlayed.value + favoriteTracks.value + listOfNotNull(seedTrack))
                .distinctBy { it.id }
                .filter { com.example.recommendation.UnifiedRecommendationEngine.verified(it) }
                .map { it.copy(recommendationOnly = true) }
            // Seed Infinite Radio only from the same verified recommendation catalog.
            val pool = candidates.ifEmpty { repository.getYtmQuickPicks(limit = 30) }
            val alternatives = pool.filter { it.id != state.currentTrack?.id }
            val randomized = alternatives.ifEmpty { pool }.shuffled()
            val chosen = randomized.firstOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                playbackManager.setAutoplayEnabled(true)
                playbackManager.playTrack(chosen, randomized, isExplicitPlaylist = false, isInfiniteRadio = true)
            }
        }
    }

    fun togglePlayPause() {
        if (playbackManager.uiState.value.currentTrack == null && _catalogTracks.value.isNotEmpty()) {
            playTrack(_catalogTracks.value.first(), _catalogTracks.value)
        } else {
            playbackManager.playPause()
        }
    }

    fun seekTo(positionMs: Long) {
        playbackManager.seekTo(positionMs)
    }

    fun skipNext() {
        playbackManager.skipNext()
    }

    fun skipPrevious() {
        playbackManager.skipPrevious()
    }

    fun toggleShuffle() {
        playbackManager.toggleShuffle()
    }

    fun toggleRepeat() {
        playbackManager.toggleRepeat()
    }

    fun toggleLike(track: MusicTrack) {
        viewModelScope.launch(AppDispatchers.CpuBounded) {
          favoriteUpdateMutex.withLock {
            val newLikedStatus = withContext(AppDispatchers.Io) { repository.toggleFavorite(track) }
            withContext(Dispatchers.Main.immediate) { playbackManager.updateFavoriteStatus(track.id, newLikedStatus) }
            _catalogTracks.update { list ->
                list.map { if (it.id == track.id) it.copy(isLiked = newLikedStatus) else it }
            }
          }
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        val generation = legacySearchGeneration.incrementAndGet()
        searchJob?.cancel()
        _searchState.update { it.copy(query = newQuery, isSearching = false, requestId = generation) }
        catalogBrowser.queryChanged(newQuery)
        if (newQuery.isBlank()) selectGenre(_searchState.value.selectedGenre)
    }

    fun selectGenre(genre: String) {
        val generation = legacySearchGeneration.incrementAndGet()
        val query = _searchState.value.query
        _searchState.update { it.copy(selectedGenre = genre, isSearching = true, requestId = generation) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(AppDispatchers.CpuBounded) {
            val allGenres = genre.equals("All", ignoreCase = true)
            val results = if (allGenres) {
                repository.search(query, application)
            } else {
                val tracks = repository.getTracksByGenre(genre).shuffled()
                SearchResultCategory(
                    topResult = tracks.firstOrNull(),
                    exactMatches = tracks,
                    similarTypeSongs = emptyList(),
                    matchedGenreOrType = genre,
                    songs = tracks,
                    albums = tracks.map { it.album }.filter { it.isNotBlank() && it != "Single" && it != "Online Stream" }.distinct(),
                    artists = tracks.map { it.artist }.filter { it.isNotBlank() && it != "Unknown Artist" }.distinct()
                )
            }
            ensureActive()
            _searchState.update { if (generation != it.requestId || it.query != query || it.selectedGenre != genre) it else
                it.copy(selectedGenre = if (allGenres) "All" else genre, result = results, isSearching = false) }
        }
    }

    // Serial IO operations preserve history order and survive Search tab disposal.
    fun rememberSearch(query: String) {
        recentSearchRevision.incrementAndGet()
        viewModelScope.launch(recentSearchDispatcher) {
          recentSearchMutex.withLock {
            val recent = com.example.data.local.RecentSearchPreferences.addRecentSearch(application, query)
            _searchState.update { it.copy(recentSearches = recent) }
          }
        }
    }

    fun removeRecentSearch(query: String) {
        recentSearchRevision.incrementAndGet()
        viewModelScope.launch(recentSearchDispatcher) {
          recentSearchMutex.withLock {
            val recent = com.example.data.local.RecentSearchPreferences.removeRecentSearch(application, query)
            _searchState.update { it.copy(recentSearches = recent) }
          }
        }
    }

    fun clearRecentSearches() {
        recentSearchRevision.incrementAndGet()
        viewModelScope.launch(recentSearchDispatcher) {
          recentSearchMutex.withLock {
            com.example.data.local.RecentSearchPreferences.clearRecentSearches(application)
            _searchState.update { it.copy(recentSearches = emptyList()) }
          }
        }
    }

    fun selectSource(source: String) {
        _searchState.update { it.copy(selectedSource = source) }
    }

    fun refreshCatalog() {
        viewModelScope.launch(AppDispatchers.CpuBounded) {
            val freshCatalog = repository.getInitialCatalog()
            _catalogTracks.value = freshCatalog
        }
    }

    fun createPlaylist(title: String, description: String, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val id = withContext(AppDispatchers.Io) { repository.createPlaylist(title, description) }
            onCreated?.invoke(id)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            withContext(AppDispatchers.Io) { repository.deletePlaylist(playlistId) }
            if (_selectedPlaylistTracks.value?.playlist?.playlistId == playlistId) {
                _selectedPlaylistTracks.value = null
            }
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: MusicTrack) {
        viewModelScope.launch(AppDispatchers.Io) {
            repository.addTrackToPlaylist(playlistId, track)
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        viewModelScope.launch(AppDispatchers.Io) {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun openPlaylist(playlistId: Long) {
        playlistTracksJob?.cancel()
        playlistTracksJob = viewModelScope.launch(AppDispatchers.CpuBounded) {
            repository.getPlaylistWithTracks(playlistId).collect {
                // Publication belongs to the selected-page state machine; all row preparation
                // has already completed on the repository's Default dispatcher.
                _selectedPlaylistTracks.value = it
            }
        }
    }

    fun closePlaylist() {
        playlistTracksJob?.cancel()
        _selectedPlaylistTracks.value = null
    }

    val effectsState: StateFlow<com.example.playback.AudioEffectsState> =
        com.example.playback.AudioEffectsManager.effectsState

    fun setEqualizerEnabled(enabled: Boolean) {
        com.example.playback.AudioEffectsManager.setEnabled(enabled)
    }

    fun setBandLevel(bandIndex: Short, levelMb: Short) {
        com.example.playback.AudioEffectsManager.setBandLevel(bandIndex, levelMb)
    }

    fun setBassBoost(strength: Int) {
        com.example.playback.AudioEffectsManager.setBassBoost(strength)
    }

    fun setVirtualizer(strength: Int) {
        com.example.playback.AudioEffectsManager.setVirtualizer(strength)
    }

    fun setLoudnessGain(gainMb: Int) {
        com.example.playback.AudioEffectsManager.setLoudnessGain(gainMb)
    }

    fun setEqualizerPreset(preset: String) {
        playbackManager.setEqualizerPreset(preset)
        com.example.playback.AudioEffectsManager.applyPreset(preset)
    }

    fun setAudioQuality(quality: com.example.playback.AudioQuality) {
        playbackManager.setAudioQuality(quality)
        catalogBrowser.setAudioQuality(quality)
    }

    fun setCrystalClarityEnabled(enabled: Boolean) {
        com.example.playback.AudioEffectsManager.setCrystalClarityEnabled(enabled)
    }

    fun resetEqualizer() {
        com.example.playback.AudioEffectsManager.resetAll()
    }

    val availableAudioDevices: StateFlow<List<com.example.playback.SoundOutputDevice>> =
        playbackManager.availableAudioDevices

    val selectedAudioDeviceId: StateFlow<Int> =
        playbackManager.selectedAudioDeviceId

    fun selectAudioOutputDevice(deviceId: Int) {
        playbackManager.selectAudioOutputDevice(deviceId)
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        playbackManager.reorderQueue(fromIndex, toIndex)
    }

    // ==========================================
    // THIRD-PARTY PLAYLIST IMPORT PIPELINE
    // ==========================================
    private val _importState = MutableStateFlow(PlaylistImportUiState())
    val importState: StateFlow<PlaylistImportUiState> = _importState.asStateFlow()
    val isImportDialogOpen = importState.map { it.isDialogOpen }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), false)
    private var importJob: Job? = null
    private val importGeneration = AtomicLong(0)
    private val importRetryJobs = ConcurrentHashMap<Int, Job>()

    private fun cancelImportRetries() {
        importGeneration.incrementAndGet()
        importRetryJobs.values.forEach { it.cancel() }
        importRetryJobs.clear()
        _importState.update { it.copy(retryingRows = emptySet(), retryMessages = emptyMap()) }
    }

    fun openImportDialog() {
        _importState.update { it.copy(isDialogOpen = true) }
    }

    fun closeImportDialog() {
        importJob?.cancel()
        cancelImportRetries()
        _importState.update { it.copy(isDialogOpen = false, step = PlaylistImportStep.Idle) }
    }

    fun onImportInputChanged(input: String) {
        val detected = if (input.isNotBlank()) PlaylistImportEngine.detectPlatform(input) else _importState.value.selectedPlatform
        _importState.update {
            it.copy(
                inputUrlOrText = input,
                selectedPlatform = detected,
                // Always clear custom title and description when pasting or changing input so new fetch gets its real title
                customTitle = "",
                customDescription = ""
            )
        }
    }

    fun onImportPlatformChanged(platform: String) {
        _importState.update { it.copy(selectedPlatform = platform) }
    }

    fun onImportTokenChanged(token: String) {
        _importState.update { it.copy(directApiKeyOrToken = token) }
    }

    fun onCustomTitleChanged(title: String) {
        _importState.update { it.copy(customTitle = title) }
    }

    fun onCustomDescriptionChanged(desc: String) {
        _importState.update { it.copy(customDescription = desc) }
    }

    fun loadSamplePlaylist(platform: String) {
        val sample = PlaylistImportEngine.getSamplePlaylist(platform)
        val sampleText = when (platform) {
            "Spotify" -> "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
            "YouTube Music" -> "https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKusctNuObj8abgoT88"
            "Apple Music" -> "https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd45149ea88e301da16328"
            else -> sample.second.joinToString("\n") { "${it.originalTitle}, ${it.originalArtist}" }
        }
        _importState.update {
            it.copy(
                selectedPlatform = platform,
                inputUrlOrText = sampleText,
                customTitle = sample.first.title,
                customDescription = sample.first.description
            )
        }
    }

    fun startPlaylistImport() {
        val currentState = _importState.value
        val input = currentState.inputUrlOrText.trim()
        if (input.isBlank()) {
            _importState.update { it.copy(step = PlaylistImportStep.Error("Please paste a playlist URL or track list.")) }
            return
        }

        importJob?.cancel()
        cancelImportRetries()
        importJob = viewModelScope.launch(AppDispatchers.Imports) {
            val work = com.example.performance.BackgroundWorkCoordinator.busy()
            try {
                val platform = currentState.selectedPlatform
                _importState.update {
                    it.copy(step = PlaylistImportStep.Fetching(platform, "Connecting to $platform & parsing tracklist..."))
                }

                val (header, importedTracks) = PlaylistImportEngine.fetchPlaylistMetadata(
                    input = input,
                    platformHint = platform,
                    accessToken = currentState.directApiKeyOrToken
                )

                if (importedTracks.isEmpty()) {
                    _importState.update {
                        it.copy(step = PlaylistImportStep.Error("No tracks found in the provided playlist."))
                    }
                    return@launch
                }

                // Always prioritize the real fetched playlist title from the source link
                val finalTitle = header.title.ifBlank { "Imported Playlist" }
                val finalDesc = header.description.ifBlank { "Imported from ${header.platform}" }
                val updatedHeader = header.copy(title = finalTitle, description = finalDesc)

                _importState.update {
                    it.copy(
                        customTitle = finalTitle,
                        customDescription = finalDesc,
                        step = PlaylistImportStep.Matching(
                            platform = header.platform,
                            playlistTitle = finalTitle,
                            current = 0,
                            total = importedTracks.size,
                            currentTrackName = "Initializing Sound-Matching Engine...",
                            matchedCount = 0
                        )
                    )
                }

                val summary = PlaylistImportEngine.matchTracks(
                    importedTracks = importedTracks,
                    header = updatedHeader
                ) { current, total, trackName, matchedCount ->
                    _importState.update { state ->
                        state.copy(
                            step = PlaylistImportStep.Matching(
                                platform = header.platform,
                                playlistTitle = finalTitle,
                                current = current,
                                total = total,
                                currentTrackName = trackName,
                                matchedCount = matchedCount
                            )
                        )
                    }
                }

                _importState.update {
                    it.copy(
                        step = PlaylistImportStep.Summary(summary),
                        lastSummary = summary
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _importState.update {
                    it.copy(step = PlaylistImportStep.Error(e.message ?: "Failed to import playlist."))
                }
            } finally { work.close() }
        }
    }

    fun retryUnmatchedOnYouTube(item: MatchResult) {
        val snapshot = _importState.value
        val summary = (snapshot.step as? PlaylistImportStep.Summary)?.summary ?: return
        val index = item.originalIndex
        if (!snapshot.isDialogOpen || index < 0 || summary.unmatchedItems.none {
                it.originalIndex == index && it.original == item.original
            }) return
        val generation = importGeneration.get()
        val job = viewModelScope.launch(AppDispatchers.CpuBounded, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val work = com.example.performance.BackgroundWorkCoordinator.busy()
            try {
                val result = PlaylistImportEngine.retryOnYouTube(item.original).copy(originalIndex = index)
                ensureActive()
                _importState.update { current ->
                    val currentSummary = (current.step as? PlaylistImportStep.Summary)?.summary
                    if (generation != importGeneration.get() || !current.isDialogOpen || currentSummary == null ||
                        currentSummary.unmatchedItems.none { it.originalIndex == index && it.original == item.original }) current
                    else if (result.matchStatus == MatchStatus.NOT_FOUND || result.matchedTrack == null) {
                        current.copy(retryMessages = current.retryMessages + (index to "No verified YouTube Music match found."))
                    } else {
                        val updated = currentSummary.copy(
                            matchedItems = (currentSummary.matchedItems + result).sortedBy { it.originalIndex },
                            unmatchedItems = currentSummary.unmatchedItems.filterNot { it.originalIndex == index })
                        current.copy(step = PlaylistImportStep.Summary(updated), lastSummary = updated,
                            retryMessages = current.retryMessages - index)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _importState.update { current -> if (generation != importGeneration.get()) current else
                    current.copy(retryMessages = current.retryMessages +
                        (index to (e.message ?: "Couldn't search YouTube Music. Tap to retry."))) }
            } finally {
                importRetryJobs.remove(index, kotlin.coroutines.coroutineContext[Job])
                _importState.update { current -> if (generation != importGeneration.get()) current else
                    current.copy(retryingRows = current.retryingRows - index) }
                work.close()
            }
        }
        if (importRetryJobs.putIfAbsent(index, job) != null) { job.cancel(); return }
        _importState.update { current -> if (generation != importGeneration.get()) current else
            current.copy(retryingRows = current.retryingRows + index, retryMessages = current.retryMessages - index) }
        if (generation == importGeneration.get()) job.start() else {
            importRetryJobs.remove(index, job)
            job.cancel()
        }
    }

    fun saveImportedPlaylist(onSaved: ((Long) -> Unit)? = null) {
        val snapshot = _importState.value
        if (snapshot.retryingRows.isNotEmpty()) return
        val summary = (snapshot.step as? PlaylistImportStep.Summary)?.summary ?: return
        _importState.update { it.copy(step = PlaylistImportStep.Saving(summary.playlistTitle)) }

        viewModelScope.launch(AppDispatchers.CpuBounded) {
            val work = com.example.performance.BackgroundWorkCoordinator.busy()
            try {
                val matchedTracks = withContext(AppDispatchers.CpuBounded) {
                    // Native IDs reconcile with the existing library; keep the first playlist position.
                    summary.matchedItems.mapNotNull { it.matchedTrack }.distinctBy { it.id }
                }
                if (matchedTracks.isEmpty()) {
                    _importState.update { it.copy(step = PlaylistImportStep.Error("Cannot save empty playlist. No tracks were matched.")) }
                    return@launch
                }
                val title = _importState.value.customTitle.ifBlank { summary.playlistTitle }
                val desc = _importState.value.customDescription.ifBlank { summary.playlistDescription }
                val cover = summary.coverUrl.ifBlank { matchedTracks.firstOrNull()?.coverUrl.orEmpty() }

                val playlistId = withContext(AppDispatchers.Io) { repository.createPlaylistWithTracks(
                    title = title,
                    description = desc,
                    coverUrl = cover,
                    tracks = matchedTracks
                ) }

                _importState.update {
                    it.copy(
                        step = PlaylistImportStep.Success(
                            playlistId = playlistId,
                            playlistTitle = title,
                            trackCount = matchedTracks.size
                        )
                    )
                }
                withContext(Dispatchers.Main.immediate) { onSaved?.invoke(playlistId) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _importState.update {
                    it.copy(step = PlaylistImportStep.Error("Error saving playlist to library: ${e.message}"))
                }
            } finally { work.close() }
        }
    }

    fun resetImportStep() {
        importJob?.cancel()
        cancelImportRetries()
        _importState.update { it.copy(step = PlaylistImportStep.Idle, customTitle = "", customDescription = "") }
    }

    override fun onCleared() {
        super.onCleared()
        lyricsJob?.cancel()
        searchJob?.cancel()
        playlistTracksJob?.cancel()
        importJob?.cancel()
        cancelImportRetries()
    }

    companion object {
        fun provideFactory(
            repository: MusicRepository,
            playbackManager: PlaybackManager,
            application: android.app.Application = com.example.XtremeMusicApp.getInstance()
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlayerViewModel(repository, playbackManager, application) as T
            }
        }
    }
}
