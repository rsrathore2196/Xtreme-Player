package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.importer.ImportedTrackMeta
import com.example.data.importer.PlaylistImportEngine
import com.example.data.importer.PlaylistImportSummary
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistWithTracks
import com.example.data.model.MusicTrack
import com.example.data.model.TrackLyrics
import com.example.data.remote.LyricsProvider
import com.example.data.remote.MusicDataSource
import com.example.data.repository.MusicRepository
import com.example.data.repository.SearchResultCategory
import com.example.playback.PlaybackManager
import com.example.playback.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

data class PlaylistImportUiState(
    val isDialogOpen: Boolean = false,
    val selectedPlatform: String = "Link", // "Link", "Spotify", "YouTube Music", "Apple Music", "CSV/Text"
    val inputUrlOrText: String = "",
    val directApiKeyOrToken: String = "",
    val step: PlaylistImportStep = PlaylistImportStep.Idle,
    val customTitle: String = "",
    val customDescription: String = "",
    val lastSummary: PlaylistImportSummary? = null
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val selectedGenre: String = "All",
    val selectedSource: String = "All", // "All", "HD Stream", "Extended Stream"
    val result: SearchResultCategory = SearchResultCategory()
)

class PlayerViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    private val application: android.app.Application = com.example.XtremeMusicApp.getInstance()
) : ViewModel() {

    val playerUiState: StateFlow<PlayerUiState> = playbackManager.uiState

    private val _currentLyrics = MutableStateFlow<TrackLyrics?>(null)
    val currentLyrics: StateFlow<TrackLyrics?> = _currentLyrics.asStateFlow()
    private var lyricsJob: Job? = null
    private var lastObservedTrackId: String? = null

    private val _catalogTracks = MutableStateFlow<List<MusicTrack>>(emptyList())
    val catalogTracks: StateFlow<List<MusicTrack>> = _catalogTracks.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _selectedPlaylistTracks = MutableStateFlow<PlaylistWithTracks?>(null)
    val selectedPlaylistTracks: StateFlow<PlaylistWithTracks?> = _selectedPlaylistTracks.asStateFlow()

    private var searchJob: Job? = null
    private var playlistTracksJob: Job? = null

    val favoriteTracks: StateFlow<List<MusicTrack>> = repository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val recentlyPlayed: StateFlow<List<MusicTrack>> = repository.getRecentlyPlayedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val initialThemeMode = com.example.data.local.ThemePreferences.getThemeMode(application)
    private val _themeMode = MutableStateFlow(initialThemeMode)
    val themeMode: StateFlow<com.example.data.local.AppThemeMode> = _themeMode.asStateFlow()

    private val _textScale = MutableStateFlow(com.example.data.local.OtherSettingsPreferences.getTextScale(application))
    val textScale: StateFlow<Float> = _textScale.asStateFlow()

    private val _textScaleIndex = MutableStateFlow(com.example.data.local.OtherSettingsPreferences.getTextSizeIndex(application))
    val textScaleIndex: StateFlow<Int> = _textScaleIndex.asStateFlow()

    private val _uiScale = MutableStateFlow(com.example.data.local.OtherSettingsPreferences.getUiScale(application))
    val uiScale: StateFlow<Float> = _uiScale.asStateFlow()

    private val _uiScaleIndex = MutableStateFlow(com.example.data.local.OtherSettingsPreferences.getUiSizeIndex(application))
    val uiScaleIndex: StateFlow<Int> = _uiScaleIndex.asStateFlow()

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

    private val _userProfile = MutableStateFlow(com.example.data.local.UserProfilePreferences.getUserProfile(application))
    val userProfile: StateFlow<com.example.data.local.UserProfile> = _userProfile.asStateFlow()

    private val _isOnboardingCompleted = MutableStateFlow(com.example.data.local.UserProfilePreferences.isOnboardingCompleted(application))
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()

    fun completeOnboarding(
        name: String,
        languages: List<String>,
        country: String,
        countryCode: String,
        flag: String
    ) {
        val profile = com.example.data.local.UserProfile(
            name = name,
            languages = languages,
            country = country,
            countryCode = countryCode,
            flag = flag,
            isOnboardingCompleted = true
        )
        _userProfile.value = profile
        _isOnboardingCompleted.value = true
        com.example.data.local.UserProfilePreferences.saveUserProfile(application, profile)
        loadCatalog()
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
        when (mode) {
            com.example.data.local.AppThemeMode.DARK -> {
                _isDarkMode.value = true
                val studioNight = com.example.data.local.ThemePresets.StudioNight.toCustomThemeState()
                _customThemeState.value = studioNight
                com.example.data.local.ThemePreferences.saveCustomThemeState(application, studioNight)
            }
            com.example.data.local.AppThemeMode.LIGHT -> {
                _isDarkMode.value = false
                val cleanDay = com.example.data.local.ThemePresets.CleanDay.toCustomThemeState()
                _customThemeState.value = cleanDay
                com.example.data.local.ThemePreferences.saveCustomThemeState(application, cleanDay)
            }
            com.example.data.local.AppThemeMode.SYSTEM -> {
                val nightModeFlags = application.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val isSystemDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                _isDarkMode.value = isSystemDark
                val defaultPreset = if (isSystemDark) com.example.data.local.ThemePresets.StudioNight else com.example.data.local.ThemePresets.CleanDay
                val defaultState = defaultPreset.toCustomThemeState()
                _customThemeState.value = defaultState
                com.example.data.local.ThemePreferences.saveCustomThemeState(application, defaultState)
            }
        }
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

    private val _customThemeState = MutableStateFlow(com.example.data.local.ThemePreferences.getCustomThemeState(application))
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

    val homeShelves: StateFlow<List<com.example.ui.ai.HomeShelf>> = kotlinx.coroutines.flow.combine(
        playbackManager.uiState,
        recentlyPlayed,
        favoriteTracks,
        _catalogTracks,
        _userProfile
    ) { uiState, recent, favs, catalog, profile ->
        com.example.ui.ai.AiMoodEngine.generatePersonalizedShelves(
            lastPlayedTrack = uiState.currentTrack,
            recentlyPlayed = recent,
            favoriteTracks = favs,
            catalogTracks = catalog,
            userProfile = profile
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        emptyList()
    )

    init {
        loadCatalog()
        observeCurrentTrackForLyrics()
    }

    private fun observeCurrentTrackForLyrics() {
        viewModelScope.launch {
            playbackManager.uiState.collect { state ->
                val track = state.currentTrack
                if (track != null) {
                    if (track.id != lastObservedTrackId) {
                        lastObservedTrackId = track.id
                        fetchLyricsForTrack(track, forceRefresh = false)
                    }
                } else {
                    lastObservedTrackId = null
                    lyricsJob?.cancel()
                    _currentLyrics.value = null
                }
            }
        }
    }

    fun retryLyrics() {
        val track = playbackManager.uiState.value.currentTrack ?: return
        fetchLyricsForTrack(track, forceRefresh = true)
    }

    private fun fetchLyricsForTrack(track: MusicTrack, forceRefresh: Boolean) {
        lyricsJob?.cancel()
        _currentLyrics.value = null
        if (forceRefresh) {
            LyricsProvider.clearCacheForTrack(track.id)
        }
        lyricsJob = viewModelScope.launch {
            try {
                val lyrics = LyricsProvider.getLyricsForTrack(track)
                if (playbackManager.uiState.value.currentTrack?.id == track.id) {
                    _currentLyrics.value = lyrics
                }
            } catch (e: Exception) {
                if (playbackManager.uiState.value.currentTrack?.id == track.id) {
                    _currentLyrics.value = TrackLyrics(
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        isSynced = false,
                        lines = emptyList()
                    )
                }
            }
        }
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            // Load instantly from cache/local database (<5ms)
            val initial = repository.getInitialCatalog(_userProfile.value)
            _catalogTracks.value = initial
            playbackManager.setCandidatePool(initial)

            // Concurrently sync fresh online trending tracks in the background without blocking UI
            launch(Dispatchers.IO) {
                try {
                    val enriched = repository.syncOnlineCatalog(_userProfile.value)
                    if (enriched.isNotEmpty()) {
                        _catalogTracks.value = enriched
                        playbackManager.setCandidatePool(enriched)
                    }
                } catch (e: Exception) {
                    // Graceful fallback: initial local catalog remains active
                }
            }
        }
    }

    fun renamePlaylist(playlistId: Long, newTitle: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newTitle)
            val current = _selectedPlaylistTracks.value
            if (current != null && current.playlist.playlistId == playlistId) {
                _selectedPlaylistTracks.value = current.copy(
                    playlist = current.playlist.copy(title = newTitle.trim())
                )
            }
        }
    }

    fun reloadAfterRestore() {
        viewModelScope.launch {
            _userProfile.value = com.example.data.local.UserProfilePreferences.getUserProfile(application)
            loadCatalog()
            val currentId = _selectedPlaylistTracks.value?.playlist?.playlistId
            if (currentId != null) {
                openPlaylist(currentId)
            }
        }
    }

    fun toggleAutoplay() {
        playbackManager.toggleAutoplay(_catalogTracks.value)
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = _catalogTracks.value, isExplicitPlaylist: Boolean = false) {
        viewModelScope.launch {
            repository.markTrackPlayed(track)
            playbackManager.playTrack(track, queue, isExplicitPlaylist)
        }
    }

    fun playPlaylistTrack(track: MusicTrack, tracks: List<MusicTrack>) {
        viewModelScope.launch {
            repository.markTrackPlayed(track)
            playbackManager.playTrack(track, tracks, isExplicitPlaylist = true)
        }
    }

    /**
     * Plays a track selected from search results.
     * Prioritizes the same singer/artist's other songs and matching genre/type
     * rather than blindly following the raw search list.
     */
    fun playFromSearch(track: MusicTrack, searchPool: List<MusicTrack>) {
        viewModelScope.launch {
            repository.markTrackPlayed(track)
            playbackManager.playFromSearch(track, searchPool)
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
        viewModelScope.launch {
            val newLikedStatus = repository.toggleFavorite(track)
            playbackManager.updateFavoriteStatus(track.id, newLikedStatus)
            _catalogTracks.update { list ->
                list.map { if (it.id == track.id) it.copy(isLiked = newLikedStatus) else it }
            }
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        _searchState.update { it.copy(query = newQuery, isSearching = newQuery.isNotBlank()) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300) // 300ms debouncing as specified in architectural requirements
            val results = repository.search(newQuery)
            _searchState.update { it.copy(result = results, isSearching = false) }
        }
    }

    fun selectGenre(genre: String) {
        _searchState.update { it.copy(selectedGenre = genre, isSearching = true) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (genre.equals("All", ignoreCase = true)) {
                val results = repository.search(_searchState.value.query)
                _searchState.update { it.copy(selectedGenre = "All", result = results, isSearching = false) }
            } else {
                val tracks = repository.getTracksByGenre(genre).shuffled()
                val results = SearchResultCategory(
                    topResult = tracks.firstOrNull(),
                    exactMatches = tracks,
                    similarTypeSongs = emptyList(),
                    matchedGenreOrType = genre,
                    songs = tracks,
                    albums = tracks.map { it.album }.filter { it.isNotBlank() && it != "Single" && it != "Online Stream" }.distinct(),
                    artists = tracks.map { it.artist }.filter { it.isNotBlank() && it != "Unknown Artist" }.distinct()
                )
                _searchState.update { it.copy(result = results, isSearching = false) }
            }
        }
    }

    fun selectSource(source: String) {
        _searchState.update { it.copy(selectedSource = source) }
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            val freshCatalog = repository.getInitialCatalog()
            _catalogTracks.value = freshCatalog
        }
    }

    fun createPlaylist(title: String, description: String, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val id = repository.createPlaylist(title, description)
            onCreated?.invoke(id)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (_selectedPlaylistTracks.value?.playlist?.playlistId == playlistId) {
                _selectedPlaylistTracks.value = null
            }
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: MusicTrack) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, track)
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun openPlaylist(playlistId: Long) {
        playlistTracksJob?.cancel()
        playlistTracksJob = viewModelScope.launch {
            repository.getPlaylistWithTracks(playlistId).collect {
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
    private var importJob: Job? = null

    fun openImportDialog() {
        _importState.update { it.copy(isDialogOpen = true) }
    }

    fun closeImportDialog() {
        importJob?.cancel()
        _importState.update { it.copy(isDialogOpen = false, step = PlaylistImportStep.Idle) }
    }

    fun onImportInputChanged(input: String) {
        val detected = if (input.isNotBlank()) PlaylistImportEngine.detectPlatform(input) else _importState.value.selectedPlatform
        _importState.update {
            it.copy(
                inputUrlOrText = input,
                selectedPlatform = detected,
                // Clear any previous sample title when pasting a new link
                customTitle = if (it.customTitle.startsWith("Spotify:") || it.customTitle.startsWith("Apple Music:") || it.customTitle.startsWith("YouTube Music:") || it.customTitle.startsWith("CSV:")) "" else it.customTitle,
                customDescription = if (it.customDescription.contains("Official Global Chart") || it.customDescription.contains("Top trending releases") || it.customDescription.contains("Most replayed music videos")) "" else it.customDescription
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
        importJob = viewModelScope.launch {
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

                val finalTitle = if (currentState.customTitle.isNotBlank() && !currentState.customTitle.startsWith("Spotify:") && !currentState.customTitle.startsWith("Apple Music:") && !currentState.customTitle.startsWith("YouTube Music:") && !currentState.customTitle.startsWith("CSV:")) {
                    currentState.customTitle
                } else {
                    header.title
                }
                val finalDesc = if (currentState.customDescription.isNotBlank() && !currentState.customDescription.contains("Official Global Chart") && !currentState.customDescription.contains("Top trending releases") && !currentState.customDescription.contains("Most replayed music videos")) {
                    currentState.customDescription
                } else {
                    header.description
                }
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
            } catch (e: Exception) {
                _importState.update {
                    it.copy(step = PlaylistImportStep.Error(e.message ?: "Failed to import playlist."))
                }
            }
        }
    }

    fun saveImportedPlaylist(onSaved: ((Long) -> Unit)? = null) {
        val summary = _importState.value.lastSummary ?: return
        val matchedTracks = summary.matchedItems.mapNotNull { it.matchedTrack }
        if (matchedTracks.isEmpty()) {
            _importState.update { it.copy(step = PlaylistImportStep.Error("Cannot save empty playlist. No tracks were matched.")) }
            return
        }

        viewModelScope.launch {
            _importState.update { it.copy(step = PlaylistImportStep.Saving(summary.playlistTitle)) }
            try {
                val title = _importState.value.customTitle.ifBlank { summary.playlistTitle }
                val desc = _importState.value.customDescription.ifBlank { summary.playlistDescription }
                val cover = summary.coverUrl.ifBlank { matchedTracks.firstOrNull()?.coverUrl.orEmpty() }

                val playlistId = repository.createPlaylistWithTracks(
                    title = title,
                    description = desc,
                    coverUrl = cover,
                    tracks = matchedTracks
                )

                _importState.update {
                    it.copy(
                        step = PlaylistImportStep.Success(
                            playlistId = playlistId,
                            playlistTitle = title,
                            trackCount = matchedTracks.size
                        )
                    )
                }
                onSaved?.invoke(playlistId)
            } catch (e: Exception) {
                _importState.update {
                    it.copy(step = PlaylistImportStep.Error("Error saving playlist to library: ${e.message}"))
                }
            }
        }
    }

    fun resetImportStep() {
        _importState.update { it.copy(step = PlaylistImportStep.Idle) }
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
