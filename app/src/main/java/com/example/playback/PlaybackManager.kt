package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.data.remote.MusicDataSource
import com.example.recommendation.CustomRecommendationQueryWrapper
import com.example.recommendation.RecommendationEngine
import com.example.service.MusicService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val currentTrack: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val queue: List<MusicTrack> = emptyList(),
    val currentIndex: Int = 0,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isFavorite: Boolean = false,
    val selectedQuality: AudioQuality = AudioQuality.EXTREME_320,
    val qualityBadge: String = "HD • 320 kbps",
    val errorMessage: String? = null,
    val equalizerPreset: String = "Crystal Clarity",
    val isAutoplayEnabled: Boolean = true,
    val nextRecommendedTrack: MusicTrack? = null,
    val infiniteAutoplayTracks: List<MusicTrack> = emptyList(),
    val sessionMemoryCount: Int = 0
)

enum class RepeatMode {
    OFF, ALL, ONE
}

class PlaybackManager(
    private val context: Context,
    var repository: com.example.data.repository.MusicRepository? = null
) {
    private val TAG = "PlaybackManager"

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressTickerJob: Job? = null

    val recommendationEngine = RecommendationEngine()
    private val candidateCatalogPool = mutableListOf<MusicTrack>()
    private var recommendationFetchJob: Job? = null

    // Playback history stack for accurate previous song navigation
    private val playbackHistory = mutableListOf<MusicTrack>()
    private var lastPreviousPressTimeMs: Long = 0L

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var activeQueue = mutableListOf<MusicTrack>()
    private var isCurrentSessionExplicitPlaylist: Boolean = false

    init {
        initMediaController()
    }

    private fun initMediaController() {
        try {
            val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture?.addListener({
                try {
                    controller = controllerFuture?.get()
                    setupPlayerListener()
                    Log.i(TAG, "MediaController initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize MediaController: ${e.message}", e)
                    _uiState.update { it.copy(errorMessage = "Player initialization failed") }
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaController: ${e.message}", e)
            _uiState.update { it.copy(errorMessage = "Failed to initialize player: ${e.message}") }
        }
    }

    private fun setupPlayerListener() {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot setup listener: player is null")
            return
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    startProgressTicker()
                } else {
                    stopProgressTicker()
                }
                Log.d(TAG, "Playing state changed: $isPlaying")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val isLoading = playbackState == Player.STATE_BUFFERING
                val duration = if (player.duration > 0) player.duration else _uiState.value.durationMs
                _uiState.update {
                    it.copy(
                        isLoading = isLoading,
                        durationMs = duration,
                        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
                    )
                }
                Log.d(TAG, "Playback state changed: $playbackState, duration: $duration")

                // Handle natural song completion
                if (playbackState == Player.STATE_ENDED) {
                    Log.i(TAG, "Song completed playback. Autoplay enabled: ${_uiState.value.isAutoplayEnabled}")
                    if (_uiState.value.repeatMode == RepeatMode.ONE) {
                        player.seekTo(0)
                        player.play()
                    } else if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        player.play()
                    } else if (_uiState.value.isAutoplayEnabled) {
                        skipNext()
                    } else if (_uiState.value.repeatMode == RepeatMode.ALL && activeQueue.isNotEmpty()) {
                        player.seekTo(0, 0L)
                        player.play()
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    val trackId = mediaItem.mediaId
                    val matchedTrack = activeQueue.find { it.id == trackId }
                        ?: MusicTrack(
                            id = trackId,
                            title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown",
                            artist = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown",
                            album = mediaItem.mediaMetadata.albumTitle?.toString() ?: "",
                            durationMs = player.duration.coerceAtLeast(0L),
                            coverUrl = mediaItem.mediaMetadata.artworkUri?.toString() ?: "",
                            audioUrl = mediaItem.requestMetadata.mediaUri?.toString() ?: ""
                        )
                    val index = activeQueue.indexOfFirst { it.id == trackId }.coerceAtLeast(0)

                    // Track previous song into playback history before updating current
                    val oldTrack = _uiState.value.currentTrack
                    if (oldTrack != null && oldTrack.id != matchedTrack.id) {
                        playbackHistory.add(oldTrack)
                        if (playbackHistory.size > 50) playbackHistory.removeAt(0)
                    }

                    // Record track into session memory & co-listening matrix
                    recommendationEngine.recordTrackPlayed(matchedTrack)

                    // The upcoming song in queue is the next recommended track
                    val nextTrackInQueue = activeQueue.getOrNull(index + 1)

                    _uiState.update {
                        it.copy(
                            currentTrack = matchedTrack,
                            currentIndex = index,
                            durationMs = if (player.duration > 0) player.duration else matchedTrack.durationMs,
                            qualityBadge = matchedTrack.qualityBadge,
                            isFavorite = matchedTrack.isLiked,
                            nextRecommendedTrack = nextTrackInQueue,
                            sessionMemoryCount = recommendationEngine.getSessionTracks().size
                        )
                    }

                    // Seamless Infinity Queue: Keep upcoming recommendations stocked
                    if (_uiState.value.isAutoplayEnabled) {
                        refreshAutoplayRecommendation(matchedTrack, isCurrentSessionExplicitPlaylist)
                    }

                    Log.i(TAG, "Media item transitioned to: ${matchedTrack.title}")
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                updateProgressValues()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val player = controller
                val isDecoderReclaim = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                        error.message?.contains("resource", ignoreCase = true) == true

                if (isDecoderReclaim && player != null) {
                    Log.w(TAG, "MediaCodec decoder was reclaimed by resource manager (${error.errorCodeName}). Re-preparing player smoothly...")
                    try {
                        player.prepare()
                        player.play()
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed quick recovery after decoder reclaim: ${e.message}")
                    }
                }

                val current = _uiState.value.currentTrack
                val repo = repository
                if (current != null && repo != null) {
                    Log.w(TAG, "Audio playback error for '${current.title}'. Attempting cross-API fallback resolution...")
                    scope.launch {
                        try {
                            val fallbackTrack = repo.resolvePlayableTrack(current)
                            if (fallbackTrack.audioUrl.isNotBlank() && fallbackTrack.audioUrl != current.audioUrl) {
                                Log.i(TAG, "Fallback stream resolved: ${fallbackTrack.audioUrl}. Retrying playback...")
                                withContext(Dispatchers.Main) {
                                    playTrackInternal(fallbackTrack, activeQueue, isCurrentSessionExplicitPlaylist)
                                }
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Fallback resolution failed: ${e.message}")
                        }
                        val errorMsg = "Audio playback error: ${error.message}"
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isPlaying = false,
                                errorMessage = errorMsg
                            )
                        }
                    }
                    return
                }

                val errorMsg = "Audio playback error: ${error.message}"
                Log.e(TAG, errorMsg, error)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        errorMessage = errorMsg
                    )
                }
            }
        })
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch {
            while (isActive) {
                updateProgressValues()
                delay(300) // 300ms smooth updates with minimal CPU and zero UI thrashing
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        updateProgressValues()
    }

    private fun updateProgressValues() {
        val player = controller
        if (player == null) {
            Log.w(TAG, "Cannot update progress: player is null")
            return
        }

        try {
            val pos = player.currentPosition.coerceAtLeast(0L)
            val dur = if (player.duration > 0) player.duration else _uiState.value.durationMs
            val buf = player.bufferedPosition.coerceAtLeast(0L)
            val current = _uiState.value
            if (current.currentPositionMs != pos || current.durationMs != dur || current.bufferedPositionMs != buf) {
                _uiState.update {
                    it.copy(
                        currentPositionMs = pos,
                        durationMs = dur,
                        bufferedPositionMs = buf
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating progress values: ${e.message}", e)
        }
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = listOf(track), isExplicitPlaylist: Boolean = false) {
        isCurrentSessionExplicitPlaylist = isExplicitPlaylist
        val repo = repository
        if (track.audioUrl.isBlank() && repo != null) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            scope.launch {
                try {
                    val resolved = repo.resolvePlayableTrack(track)
                    val updatedQueue = queue.map { if (it.id == track.id) resolved else it }
                    withContext(Dispatchers.Main) {
                        playTrackInternal(resolved, updatedQueue, isExplicitPlaylist)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed resolving track stream: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        playTrackInternal(track, queue, isExplicitPlaylist)
                    }
                }
            }
            return
        }
        playTrackInternal(track, queue, isExplicitPlaylist)
    }

    private fun playTrackInternal(track: MusicTrack, queue: List<MusicTrack>, isExplicitPlaylist: Boolean = false) {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot play track: player is null")
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    errorMessage = "Player not initialized"
                )
            }
            return
        }

        try {
            val oldTrack = _uiState.value.currentTrack
            if (oldTrack != null && oldTrack.id != track.id) {
                playbackHistory.add(oldTrack)
                if (playbackHistory.size > 50) playbackHistory.removeAt(0)
            }

            recommendationEngine.recordTrackPlayed(track)
            val autoplayEnabled = _uiState.value.isAutoplayEnabled

            if (autoplayEnabled && !isExplicitPlaylist) {
                // Autoplay Session:
                // The queue songs for next play MUST BE the infinite autoplay recommendation songs!
                val localPool = (candidateCatalogPool + queue + MusicDataSource.curatedTracks).distinctBy { it.id }
                val initialRecommendations = recommendationEngine.buildInfiniteAutoplayRecommendations(
                    currentTrack = track,
                    candidatePool = localPool,
                    limit = 15,
                    alreadyQueuedIds = setOf(track.id)
                )

                val unifiedQueue = (listOf(track) + initialRecommendations).toMutableList()
                activeQueue = unifiedQueue

                val mediaItems = unifiedQueue.map { it.toMediaItem() }
                player.setMediaItems(mediaItems, 0, 0L)
                player.prepare()
                player.play()

                val nextRec = initialRecommendations.firstOrNull()

                _uiState.update {
                    it.copy(
                        currentTrack = track,
                        queue = activeQueue.toList(),
                        currentIndex = 0,
                        isPlaying = true,
                        isLoading = true,
                        durationMs = track.durationMs,
                        currentPositionMs = 0L,
                        qualityBadge = track.qualityBadge,
                        isFavorite = track.isLiked,
                        errorMessage = null,
                        nextRecommendedTrack = nextRec,
                        infiniteAutoplayTracks = initialRecommendations,
                        sessionMemoryCount = recommendationEngine.getSessionTracks().size
                    )
                }
            } else {
                activeQueue = queue.toMutableList()
                val startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

                val mediaItems = queue.map { it.toMediaItem() }
                player.setMediaItems(mediaItems, startIndex, 0L)
                player.prepare()
                player.play()

                val nextInQueue = activeQueue.getOrNull(startIndex + 1)

                _uiState.update {
                    it.copy(
                        currentTrack = track,
                        queue = activeQueue.toList(),
                        currentIndex = startIndex,
                        isPlaying = true,
                        isLoading = true,
                        durationMs = track.durationMs,
                        currentPositionMs = 0L,
                        qualityBadge = track.qualityBadge,
                        isFavorite = track.isLiked,
                        errorMessage = null,
                        nextRecommendedTrack = nextInQueue,
                        sessionMemoryCount = recommendationEngine.getSessionTracks().size
                    )
                }
            }

            // Always trigger background targeted analysis to refresh and refine infinite recommendations
            refreshAutoplayRecommendation(track, isExplicitPlaylist)
            Log.i(TAG, "Playing track: ${track.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing track: ${e.message}", e)
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    errorMessage = "Failed to play track: ${e.message}"
                )
            }
        }
    }

    /**
     * Deep Infinity Autoplay Recommendation:
     * 1. Uses CustomRecommendationQueryWrapper to fetch targeted tracks from backend
     *    with exact Artist, Language, and Release Year.
     * 2. Runs the Weightage Scoring System to locally rank candidates and verify mood matching.
     * 3. Unifies upcoming queue so the songs queued for next play and the infinite autoplay songs are identical.
     */
    fun refreshAutoplayRecommendation(track: MusicTrack, isExplicitPlaylist: Boolean = false) {
        recommendationFetchJob?.cancel()
        recommendationFetchJob = scope.launch(Dispatchers.IO) {
            try {
                val targetedTracks = CustomRecommendationQueryWrapper.fetchTargetedRecommendations(track, limit = 20)
                if (targetedTracks.isNotEmpty()) {
                    synchronized(candidateCatalogPool) {
                        for (item in targetedTracks) {
                            if (candidateCatalogPool.none { it.id == item.id }) {
                                candidateCatalogPool.add(item)
                            }
                        }
                    }
                }

                val currentPool = (targetedTracks + candidateCatalogPool + activeQueue + MusicDataSource.curatedTracks).distinctBy { it.id }

                // Build complete infinite autoplay list matching mood, artist, language, and era
                val recommendedTracks = recommendationEngine.buildInfiniteAutoplayRecommendations(
                    currentTrack = track,
                    candidatePool = currentPool,
                    limit = 15,
                    alreadyQueuedIds = setOf(track.id)
                )

                // Resolve playable streams for top recommendations
                val resolvedRecs = recommendedTracks.map { rec ->
                    if (rec.audioUrl.isBlank() && repository != null) {
                        repository?.resolvePlayableTrack(rec) ?: rec
                    } else rec
                }

                val bestTrack = resolvedRecs.firstOrNull()

                withContext(Dispatchers.Main) {
                    val player = controller
                    val autoplayEnabled = _uiState.value.isAutoplayEnabled

                    if (autoplayEnabled && player != null) {
                        if (!isExplicitPlaylist) {
                            // Perfect synchronization: Upcoming queue IS the infinite autoplay songs list!
                            val playerCurrentIndex = player.currentMediaItemIndex.coerceAtLeast(0)

                            // Keep current playing item and remove subsequent stale items
                            while (player.mediaItemCount > playerCurrentIndex + 1) {
                                player.removeMediaItem(playerCurrentIndex + 1)
                            }

                            // Add fresh infinite play recommendations to ExoPlayer
                            for (rec in resolvedRecs) {
                                player.addMediaItem(rec.toMediaItem())
                            }

                            activeQueue = (activeQueue.take(playerCurrentIndex + 1) + resolvedRecs).toMutableList()

                            _uiState.update {
                                it.copy(
                                    queue = activeQueue.toList(),
                                    nextRecommendedTrack = bestTrack,
                                    infiniteAutoplayTracks = resolvedRecs
                                )
                            }
                            Log.i("InfinityAutoplay", "Synchronized upcoming queue with infinite autoplay recommendations: ${resolvedRecs.size} tracks queued.")
                        } else {
                            // Explicit Playlist: Append infinite autoplay recommendations to the end of playlist
                            val existingIds = activeQueue.map { it.id }.toSet()
                            val toAppend = resolvedRecs.filter { !existingIds.contains(it.id) }
                            for (rec in toAppend) {
                                activeQueue.add(rec)
                                player.addMediaItem(rec.toMediaItem())
                            }
                            val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                            val nextTrackInQueue = activeQueue.getOrNull(currentIndex + 1)
                            _uiState.update {
                                it.copy(
                                    queue = activeQueue.toList(),
                                    nextRecommendedTrack = nextTrackInQueue ?: bestTrack,
                                    infiniteAutoplayTracks = resolvedRecs
                                )
                            }
                        }
                    } else {
                        val currentIndex = _uiState.value.currentIndex
                        _uiState.update {
                            it.copy(
                                nextRecommendedTrack = activeQueue.getOrNull(currentIndex + 1) ?: bestTrack,
                                infiniteAutoplayTracks = resolvedRecs
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background recommendation fetch error: ${e.message}")
            }
        }
    }

    /**
     * Plays a track from search results, constructing a personalized recommendation queue
     * that strongly prioritizes songs by the same singer/artist, same genre/type, and matching
     * the user's taste across the last 10 songs, rather than just playing the next arbitrary item in search.
     */
    fun playFromSearch(track: MusicTrack, searchContextPool: List<MusicTrack>) {
        val fullCandidatePool = (searchContextPool + candidateCatalogPool + MusicDataSource.curatedTracks).distinctBy { it.id }
        val prioritizedQueue = recommendationEngine.buildSearchPlaybackQueue(
            selectedTrack = track,
            candidatePool = fullCandidatePool,
            limit = 25
        )
        Log.i(TAG, "playFromSearch: constructed ${prioritizedQueue.size} track prioritized queue for '${track.title}'")
        playTrack(track, prioritizedQueue)
    }

    fun setCandidatePool(pool: List<MusicTrack>) {
        candidateCatalogPool.clear()
        candidateCatalogPool.addAll(pool)
    }

    fun toggleAutoplay(pool: List<MusicTrack> = emptyList()) {
        if (pool.isNotEmpty()) setCandidatePool(pool)
        val newAutoplay = !_uiState.value.isAutoplayEnabled
        _uiState.update { it.copy(isAutoplayEnabled = newAutoplay) }

        if (newAutoplay && _uiState.value.currentTrack != null) {
            val current = _uiState.value.currentTrack!!
            refreshAutoplayRecommendation(current, isCurrentSessionExplicitPlaylist)
        }
        Log.i(TAG, "Infinity Autoplay toggled: $newAutoplay")
    }

    fun playPause() {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot toggle play/pause: player is null")
            return
        }

        try {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.prepare()
                }
                player.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling play/pause: ${e.message}", e)
        }
    }

    fun seekTo(positionMs: Long) {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot seek: player is null")
            return
        }

        try {
            player.seekTo(positionMs)
            _uiState.update { it.copy(currentPositionMs = positionMs) }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking to $positionMs: ${e.message}", e)
        }
    }

    fun skipNext() {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot skip next: player is null")
            return
        }

        try {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.play()
            } else if (_uiState.value.isAutoplayEnabled) {
                // Trigger intelligent Autoplay / Infinity Queue
                val current = _uiState.value.currentTrack
                if (current != null) {
                    scope.launch(Dispatchers.Main) {
                        val pool = (candidateCatalogPool + activeQueue + MusicDataSource.curatedTracks).distinctBy { it.id }
                        val nextTrack = _uiState.value.nextRecommendedTrack
                            ?: recommendationEngine.recommendNextTrack(current, pool, activeQueue.map { it.id }.toSet())

                        if (nextTrack != null) {
                            val resolvedTrack = withContext(Dispatchers.IO) {
                                repository?.resolvePlayableTrack(nextTrack) ?: nextTrack
                            }
                            Log.i("InfinityAutoplay", "Autoplay playing resolved track '${resolvedTrack.title}' by '${resolvedTrack.artist}'")
                            activeQueue.add(resolvedTrack)
                            val mediaItem = resolvedTrack.toMediaItem()
                            player.addMediaItem(mediaItem)
                            _uiState.update { it.copy(queue = activeQueue.toList()) }
                            player.seekTo(activeQueue.lastIndex, 0L)
                            player.prepare()
                            player.play()
                            refreshAutoplayRecommendation(resolvedTrack)
                        } else if (activeQueue.isNotEmpty()) {
                            player.seekTo(0, 0L)
                            player.play()
                        }
                    }
                } else if (activeQueue.isNotEmpty()) {
                    player.seekTo(0, 0L)
                    player.play()
                }
            } else if (activeQueue.isNotEmpty()) {
                // Loop back to first if list has tracks
                player.seekTo(0, 0L)
                player.play()
            }
            Log.d(TAG, "Skipped to next track")
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping next: ${e.message}", e)
        }
    }

    fun skipPrevious() {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot skip previous: player is null")
            return
        }

        try {
            val now = System.currentTimeMillis()
            val currentPos = player.currentPosition
            val isQuickSecondPress = (now - lastPreviousPressTimeMs) < 3000L
            val isNearStart = currentPos <= 2500L

            if (!isQuickSecondPress && !isNearStart) {
                // First press while playing: restart current track from beginning
                player.seekTo(0)
                lastPreviousPressTimeMs = now
                Log.d(TAG, "First press: restarted current song from start")
            } else {
                // Second press (or pressed from start): play the previous song!
                lastPreviousPressTimeMs = 0L
                if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                    player.play()
                    Log.d(TAG, "Second press: skipped to previous media item in queue")
                } else if (playbackHistory.isNotEmpty()) {
                    val prevTrack = playbackHistory.removeAt(playbackHistory.lastIndex)
                    Log.d(TAG, "Second press: playing previous song from history: ${prevTrack.title}")
                    playTrack(prevTrack, activeQueue.ifEmpty { listOf(prevTrack) })
                } else if (activeQueue.size > 1 && player.currentMediaItemIndex > 0) {
                    player.seekTo(player.currentMediaItemIndex - 1, 0L)
                    player.play()
                } else {
                    player.seekTo(0)
                }
            }
            Log.d(TAG, "Handled skip previous action")
        } catch (e: Exception) {
            Log.e(TAG, "Error skipping previous: ${e.message}", e)
        }
    }

    fun toggleShuffle() {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot toggle shuffle: player is null")
            return
        }

        try {
            val newShuffle = !_uiState.value.isShuffle
            player.shuffleModeEnabled = newShuffle
            _uiState.update { it.copy(isShuffle = newShuffle) }
            Log.d(TAG, "Shuffle toggled: $newShuffle")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling shuffle: ${e.message}", e)
        }
    }

    fun toggleRepeat() {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot toggle repeat: player is null")
            return
        }

        try {
            val current = _uiState.value.repeatMode
            val next = when (current) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            player.repeatMode = when (next) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            _uiState.update { it.copy(repeatMode = next) }
            Log.d(TAG, "Repeat mode changed to: $next")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling repeat: ${e.message}", e)
        }
    }

    fun updateFavoriteStatus(trackId: String, isLiked: Boolean) {
        try {
            val current = _uiState.value.currentTrack
            if (current?.id == trackId) {
                _uiState.update {
                    it.copy(
                        currentTrack = current.copy(isLiked = isLiked),
                        isFavorite = isLiked
                    )
                }
            }
            // Also update inside activeQueue
            val idx = activeQueue.indexOfFirst { it.id == trackId }
            if (idx >= 0) {
                activeQueue[idx] = activeQueue[idx].copy(isLiked = isLiked)
                _uiState.update { it.copy(queue = activeQueue.toList()) }
            }
            Log.d(TAG, "Updated favorite status for track $trackId: $isLiked")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating favorite status: ${e.message}", e)
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        try {
            if (fromIndex in activeQueue.indices && toIndex in activeQueue.indices) {
                val item = activeQueue.removeAt(fromIndex)
                activeQueue.add(toIndex, item)
                controller?.moveMediaItem(fromIndex, toIndex)
                val currentIndex = _uiState.value.currentIndex
                val nextRec = activeQueue.getOrNull(currentIndex + 1)
                _uiState.update {
                    it.copy(
                        queue = activeQueue.toList(),
                        nextRecommendedTrack = nextRec ?: it.nextRecommendedTrack
                    )
                }
                Log.d(TAG, "Reordered queue: moved item from $fromIndex to $toIndex")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reordering queue: ${e.message}", e)
        }
    }

    fun setEqualizerPreset(preset: String) {
        try {
            _uiState.update { it.copy(equalizerPreset = preset) }
            Log.d(TAG, "Equalizer preset changed to: $preset")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting equalizer preset: ${e.message}", e)
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot set audio quality: player is null")
            return
        }

        try {
            _uiState.update {
                it.copy(
                    selectedQuality = quality,
                    qualityBadge = quality.badge
                )
            }
            val currentTrack = _uiState.value.currentTrack ?: return
            if (currentTrack.audioUrl.contains("saavncdn.com")) {
                val updatedUrl = com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(
                    currentTrack.audioUrl,
                    quality.id
                )
                val updatedTrack = currentTrack.copy(
                    audioUrl = updatedUrl,
                    bitrateKbps = quality.kbps,
                    qualityBadge = quality.badge
                )
                val currentPos = player.currentPosition
                val wasPlaying = player.isPlaying
                val currentIdx = player.currentMediaItemIndex
                val mediaItem = updatedTrack.toMediaItem()
                if (currentIdx in 0 until player.mediaItemCount) {
                    player.replaceMediaItem(currentIdx, mediaItem)
                    player.seekTo(currentIdx, currentPos)
                    if (wasPlaying) player.play()
                }
                _uiState.update {
                    it.copy(
                        currentTrack = updatedTrack,
                        qualityBadge = quality.badge
                    )
                }
                Log.i(TAG, "Audio quality changed to: ${quality.id}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting audio quality: ${e.message}", e)
        }
    }

    val availableAudioDevices: StateFlow<List<SoundOutputDevice>> = AudioDeviceManager.availableDevices
    val selectedAudioDeviceId: StateFlow<Int> = AudioDeviceManager.selectedDeviceId

    fun selectAudioOutputDevice(deviceId: Int) {
        try {
            val player = controller
            val args = Bundle().apply { putInt("device_id", deviceId) }
            player?.sendCustomCommand(
                SessionCommand(MusicService.COMMAND_SET_OUTPUT_DEVICE, Bundle.EMPTY),
                args
            )
            AudioDeviceManager.selectDevice(context, deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting audio output device: ${e.message}", e)
        }
    }

    fun release() {
        try {
            progressTickerJob?.cancel()
            controller?.release()
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controller = null
            Log.i(TAG, "PlaybackManager released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources: ${e.message}", e)
        }
    }

    private fun MusicTrack.toMediaItem(): MediaItem {
        val targetQuality = _uiState.value.selectedQuality
        val effectiveUrl = if (audioUrl.contains("saavncdn.com")) {
            com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(audioUrl, targetQuality.id)
        } else {
            audioUrl
        }
        val effectiveBitrate = if (audioUrl.contains("saavncdn.com")) targetQuality.kbps else bitrateKbps
        val rawBadge = if (audioUrl.contains("saavncdn.com")) targetQuality.badge else qualityBadge
        val effectiveBadge = rawBadge
            .replace("YouTube Music", "HQ Stream", ignoreCase = true)
            .replace("YouTube", "HQ Stream", ignoreCase = true)
            .replace("YT Music", "HQ", ignoreCase = true)
            .replace("JioSaavn", "HD Stream", ignoreCase = true)
            .replace("Saavn", "HD Stream", ignoreCase = true)

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(effectiveUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(Uri.parse(coverUrl))
                    .setExtras(Bundle().apply {
                        putInt("bitrate", effectiveBitrate)
                        putString("qualityBadge", effectiveBadge)
                        putString("genre", genre)
                    })
                    .build()
            )
            .build()
    }
}
