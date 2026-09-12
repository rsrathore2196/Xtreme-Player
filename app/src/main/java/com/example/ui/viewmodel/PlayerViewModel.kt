package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistWithTracks
import com.example.data.model.MusicTrack
import com.example.data.remote.MusicDataSource
import com.example.data.repository.MusicRepository
import com.example.data.repository.SearchResultCategory
import com.example.playback.PlaybackManager
import com.example.playback.PlayerUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val selectedGenre: String = "All",
    val result: SearchResultCategory = SearchResultCategory(null, emptyList(), emptyList(), emptyList())
)

class PlayerViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager
) : ViewModel() {

    val playerUiState: StateFlow<PlayerUiState> = playbackManager.uiState

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

    init {
        loadCatalog()
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            val initial = repository.getInitialCatalog()
            _catalogTracks.value = initial
            // If nothing playing yet, pre-populate player with top track ready to play
            if (playbackManager.uiState.value.currentTrack == null && initial.isNotEmpty()) {
                // Initialize queue without auto-starting audio
            }
        }
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = _catalogTracks.value) {
        viewModelScope.launch {
            repository.markTrackPlayed(track)
            playbackManager.playTrack(track, queue)
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
            if (genre == "All") {
                val results = repository.search(_searchState.value.query)
                _searchState.update { it.copy(result = results, isSearching = false) }
            } else {
                val tracks = repository.getTracksByGenre(genre)
                val results = SearchResultCategory(
                    topResult = tracks.firstOrNull(),
                    songs = tracks,
                    albums = tracks.map { it.album }.filter { it.isNotBlank() && it != "Single" && it != "Online Stream" }.distinct(),
                    artists = tracks.map { it.artist }.filter { it.isNotBlank() && it != "Unknown Artist" }.distinct()
                )
                _searchState.update { it.copy(result = results, isSearching = false) }
            }
        }
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

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        playbackManager.reorderQueue(fromIndex, toIndex)
    }

    companion object {
        fun provideFactory(
            repository: MusicRepository,
            playbackManager: PlaybackManager
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlayerViewModel(repository, playbackManager) as T
            }
        }
    }
}
