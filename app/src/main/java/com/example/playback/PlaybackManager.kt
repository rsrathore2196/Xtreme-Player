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
import com.example.data.local.ListeningEventDao
import com.example.data.local.ListeningEventEntity
import com.example.data.local.MusicDatabase
import com.example.data.local.TrackEntity
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import com.example.data.repository.TrackUnavailableException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Immutable

@Immutable
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

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressTickerJob: Job? = null
    private var recoveryJob: Job? = null
    private val recoveryGuard = PlaybackRecoveryGuard()

    val recommendationEngine = RecommendationEngine()
    private val candidateCatalogPool = java.util.concurrent.CopyOnWriteArrayList<MusicTrack>()
    private var recommendationFetchJob: Job? = null
    private var queueGeneration = 0L
    private var advanceWhenQueueReady = false
    private val refillThreshold = 5
    private val upcomingQueueTarget = 12

    private val database by lazy { MusicDatabase.getDatabase(context) }
    val listeningEventDao: ListeningEventDao by lazy { database.listeningEventDao() }
    private var currentSessionId: String = java.util.UUID.randomUUID().toString()
    private var currentTrackStartTimeMs: Long = 0L
    private var hasRecordedMeaningfulPlay: Boolean = false

    // Playback history stack for accurate previous song navigation
    private val playbackHistory = mutableListOf<MusicTrack>()
    private var lastPreviousPressTimeMs: Long = 0L

    private var playResolutionJob: Job? = null
    private var activeSearchPlayJob: Job? = null
    private var lastPlayRequestTimeMs: Long = 0L
    private var lastRequestedTrackId: String? = null

    private val initialQuality = com.example.data.local.AudioQualityPreferences.getSelectedQuality(context)
    private val _uiState = MutableStateFlow(
        PlayerUiState(
            selectedQuality = initialQuality,
            qualityBadge = initialQuality.badge
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Isolated high-frequency position state flows so high-frequency updates do NOT trigger full-screen recompositions
    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _trackDuration = MutableStateFlow(0L)
    val trackDuration: StateFlow<Long> = _trackDuration.asStateFlow()

    val currentPlaybackPositionMs: Long
        get() = controller?.currentPosition?.coerceAtLeast(0L) ?: _playbackPosition.value

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
                    // Genuinely playing: persist track into Room DB listening history immediately
                    val current = _uiState.value.currentTrack
                    if (current != null) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val now = System.currentTimeMillis()
                                val entity = TrackEntity.fromMusicTrack(current, lastPlayedAt = now)
                                database.musicDao().recordTrackPlayed(entity, now)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed updating track history in Room: ${e.message}")
                            }
                        }
                    }
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

                if (player.isPlaying || (player.playWhenReady && playbackState == Player.STATE_READY)) {
                    if (progressTickerJob == null || progressTickerJob?.isActive != true) {
                        startProgressTicker()
                    }
                }

                // Handle natural song completion
                if (playbackState == Player.STATE_ENDED) {
                    val completedTrack = _uiState.value.currentTrack
                    if (completedTrack != null) {
                        val elapsed = (System.currentTimeMillis() - currentTrackStartTimeMs).coerceAtLeast(0L)
                        scope.launch(Dispatchers.IO) {
                            val event = ListeningEventEntity.fromTrack(
                                track = completedTrack,
                                sessionId = currentSessionId,
                                playedDurationMs = elapsed,
                                trackDurationMs = completedTrack.durationMs,
                                eventType = "COMPLETED",
                                completed = true,
                                skipped = false,
                                liked = completedTrack.isLiked
                            )
                            listeningEventDao.insertListeningEvent(event)
                        }
                    }

                    Log.i(TAG, "Song completed playback. Autoplay enabled: ${_uiState.value.isAutoplayEnabled}")
                    scope.launch(Dispatchers.Main) {
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
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    val trackId = mediaItem.mediaId
                    recoveryGuard.select(trackId)
                    val oldTrack = _uiState.value.currentTrack
                    val elapsed = (System.currentTimeMillis() - currentTrackStartTimeMs).coerceAtLeast(0L)
                    val wasMeaningful = hasRecordedMeaningfulPlay
                    val shouldRecordPlayback = player.isPlaying || player.playWhenReady
                    currentTrackStartTimeMs = System.currentTimeMillis()
                    hasRecordedMeaningfulPlay = false
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

                    // The upcoming song in queue is the next recommended track
                    val nextTrackInQueue = activeQueue.getOrNull(index + 1)

                    _uiState.update {
                        it.copy(
                            currentTrack = matchedTrack,
                            currentIndex = index,
                            durationMs = if (player.duration > 0) player.duration else matchedTrack.durationMs,
                            qualityBadge = _uiState.value.selectedQuality.badge,
                            isFavorite = matchedTrack.isLiked,
                            nextRecommendedTrack = nextTrackInQueue,
                            infiniteAutoplayTracks = activeQueue.drop(index + 1)
                        )
                    }

                    // Keep player/queue access on Main; only persistence runs on IO.
                    scope.launch {

                        if (oldTrack != null && oldTrack.id != matchedTrack.id) {
                            playbackHistory.add(oldTrack)
                            if (playbackHistory.size > 50) playbackHistory.removeAt(0)

                            // Early Skip check: skipped before 15 seconds without meaningful play
                            if (elapsed < 15_000L && !wasMeaningful) {
                                val skipEvent = ListeningEventEntity.fromTrack(
                                    track = oldTrack,
                                    sessionId = currentSessionId,
                                    playedDurationMs = elapsed,
                                    trackDurationMs = oldTrack.durationMs,
                                    eventType = "EARLY_SKIP",
                                    completed = false,
                                    skipped = true,
                                    liked = oldTrack.isLiked
                                )
                                withContext(Dispatchers.IO) { listeningEventDao.insertListeningEvent(skipEvent) }
                            }
                        }


                        // Record STARTED event
                        val startEvent = ListeningEventEntity.fromTrack(
                            track = matchedTrack,
                            sessionId = currentSessionId,
                            playedDurationMs = 0L,
                            trackDurationMs = matchedTrack.durationMs,
                            eventType = "STARTED",
                            completed = false,
                            skipped = false,
                            liked = matchedTrack.isLiked
                        )
                        withContext(Dispatchers.IO) { listeningEventDao.insertListeningEvent(startEvent) }

                        // If playback is actively running or set to play immediately, persist lastPlayedAt
                        if (shouldRecordPlayback) {
                            try {
                                val now = System.currentTimeMillis()
                                val entity = TrackEntity.fromMusicTrack(matchedTrack, lastPlayedAt = now)
                                withContext(Dispatchers.IO) { database.musicDao().recordTrackPlayed(entity, now) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed updating track history on transition: ${e.message}")
                            }
                        }

                        // Record track into session memory & co-listening matrix
                        recommendationEngine.recordTrackPlayed(matchedTrack)

                        _uiState.update {
                            it.copy(sessionMemoryCount = recommendationEngine.getSessionTracks().size)
                        }

                        // One append-only refill owns both the player queue and its UI snapshot.
                        if (_uiState.value.currentTrack?.id == matchedTrack.id) {
                            refreshAutoplayRecommendation(matchedTrack)
                        }
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
                val player = controller ?: run {
                    showPlaybackFailure("Player not initialized")
                    return
                }
                val current = _uiState.value.currentTrack ?: run {
                    showPlaybackFailure("No active track to play")
                    return
                }
                // An old player's error must not supersede a newer pending selection.
                val curMediaId = player.currentMediaItem?.mediaId
                if (!curMediaId.isNullOrBlank() && curMediaId != current.id) return
                val repo = repository
                val token = recoveryGuard.claim(current.id)
                if (repo == null || token == null) {
                    showPlaybackFailure("Unable to play '${current.title}'. Please try again.")
                    return
                }
                recoveryJob?.cancel()
                val failedUrl = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: current.audioUrl
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                recoveryJob = scope.launch {
                    try {
                        val resolved = withContext(Dispatchers.IO) {
                            repo.resolvePlayableTrack(current, forceRefresh = true, failedUrl = failedUrl)
                        }
                        if (!recoveryGuard.isCurrent(current.id, token) ||
                            _uiState.value.currentTrack?.id != current.id) return@launch
                        val index = player.currentMediaItemIndex
                        val position = player.currentPosition.coerceAtLeast(0L)
                        val resume = player.playWhenReady
                        val replacement = resolved.toMediaItem()
                        if (replacement.localConfiguration?.uri?.toString() == failedUrl) {
                            throw TrackUnavailableException(current.title)
                        }
                        if (index in activeQueue.indices) activeQueue[index] = resolved
                        player.replaceMediaItem(index, replacement)
                        player.seekTo(index, position)
                        player.prepare()
                        player.playWhenReady = resume
                        _uiState.update {
                            it.copy(
                                currentTrack = resolved,
                                queue = activeQueue.toList(),
                                isLoading = false,
                                isPlaying = resume,
                                errorMessage = null
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (recoveryGuard.isCurrent(current.id, token)) {
                            showPlaybackFailure(e.message ?: "Unable to resolve a playable stream.")
                        }
                    }
                }
            }
        })
    }

    private fun showPlaybackFailure(message: String) {
        _uiState.update { it.copy(isLoading = false, isPlaying = false, errorMessage = message) }
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

            // Update isolated position StateFlows for high-performance lambda reads
            if (_playbackPosition.value != pos) _playbackPosition.value = pos
            if (_bufferedPosition.value != buf) _bufferedPosition.value = buf
            if (_trackDuration.value != dur) _trackDuration.value = dur

            val current = _uiState.value
            if (current.durationMs != dur || Math.abs(current.currentPositionMs - pos) >= 1000L || !current.isPlaying) {
                _uiState.update {
                    it.copy(
                        currentPositionMs = pos,
                        durationMs = dur,
                        bufferedPositionMs = buf
                    )
                }
            }

            // Record MEANINGFUL_PLAY only once per track when threshold passes (>=30s or >=50%), zero writes on general ticker
            if (!hasRecordedMeaningfulPlay && (pos >= 30_000L || (dur > 0 && pos.toFloat() / dur >= 0.5f))) {
                hasRecordedMeaningfulPlay = true
                val curTrack = _uiState.value.currentTrack
                if (curTrack != null) {
                    scope.launch(Dispatchers.IO) {
                        val meaningfulEvent = ListeningEventEntity.fromTrack(
                            track = curTrack,
                            sessionId = currentSessionId,
                            playedDurationMs = pos,
                            trackDurationMs = dur,
                            eventType = "MEANINGFUL_PLAY",
                            completed = false,
                            skipped = false,
                            liked = curTrack.isLiked
                        )
                        listeningEventDao.insertListeningEvent(meaningfulEvent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating progress values: ${e.message}", e)
        }
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = listOf(track), isExplicitPlaylist: Boolean = false) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { playTrack(track, queue, isExplicitPlaylist) }
            return
        }
        val now = System.currentTimeMillis()
        val currentTrack = _uiState.value.currentTrack
        val isSameTrack = currentTrack?.id == track.id

        // 1. Double-tap on currently playing track:
        // If already playing smoothly, do nothing; if paused, resume playback.
        // If in loading state, reset loading state safely.
        if (isSameTrack && now - lastPlayRequestTimeMs < 450L) {
            val player = controller
            if (player != null) {
                if (!player.isPlaying) {
                    try {
                        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                            player.prepare()
                        }
                        player.play()
                    } catch (_: Exception) {}
                }
            }
            // Ensure loading flag is kept accurate and not stuck
            if (_uiState.value.isLoading && controller?.isPlaying == true) {
                _uiState.update { it.copy(isLoading = false) }
            }
            return
        }

        // 2. Debounce rapid spam clicks on the same track if already loading or processing (<250ms)
        if (lastRequestedTrackId == track.id && now - lastPlayRequestTimeMs < 250L) {
            return
        }

        lastPlayRequestTimeMs = now
        lastRequestedTrackId = track.id
        recoveryJob?.cancel()
        recoveryGuard.select(track.id, newRequest = true)
        isCurrentSessionExplicitPlaylist = isExplicitPlaylist

        // Cancel previous unresolved network/stream resolution job and search queue jobs
        activeSearchPlayJob?.cancel()
        playResolutionJob?.cancel()
        cancelQueueRefill()
        rememberQueueCandidates(queue)

        val repo = repository
        if (track.audioUrl.isBlank() && repo != null) {
            try { controller?.stop() } catch (_: Exception) {}
            _uiState.update { it.copy(currentTrack = track, isLoading = true, errorMessage = null) }
            playResolutionJob = scope.launch(Dispatchers.IO) {
                try {
                    val resolved = repo.resolvePlayableTrack(track)
                    val updatedQueue = queue.map { if (it.id == track.id) resolved else it }
                    withContext(Dispatchers.Main) {
                        if (isActive && _uiState.value.currentTrack?.id == track.id) {
                            playTrackInternal(resolved, updatedQueue, isExplicitPlaylist)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }
                    Log.e(TAG, "Failed resolving track stream: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        if (_uiState.value.currentTrack?.id == track.id) {
                            showPlaybackFailure(e.message ?: "This track is unavailable.")
                        }
                    }
                }
            }
            return
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            playTrackInternal(track, queue, isExplicitPlaylist)
        } else {
            scope.launch(Dispatchers.Main) {
                playTrackInternal(track, queue, isExplicitPlaylist)
            }
        }
    }

    private fun playTrackInternal(track: MusicTrack, queue: List<MusicTrack>, isExplicitPlaylist: Boolean = false) {
        if (track.audioUrl.isBlank()) {
            showPlaybackFailure("No playable stream is available for '${track.title}'.")
            return
        }
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot play track: player is null")
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    isLoading = false,
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
                // Start playing immediately with playable candidates only
                val validContext = queue.filter { it.id != track.id && it.audioUrl.isNotBlank() }.distinctBy { it.id }
                val initialQueue = (listOf(track) + validContext).toMutableList()
                activeQueue = initialQueue

                val mediaItems = initialQueue.map { it.toMediaItem() }
                player.setMediaItems(mediaItems, 0, 0L)
                player.prepare()
                player.play()

                _playbackPosition.value = 0L
                _uiState.update {
                    it.copy(
                        currentTrack = track,
                        queue = activeQueue.toList(),
                        currentIndex = 0,
                        isPlaying = true,
                        isLoading = true,
                        durationMs = track.durationMs,
                        currentPositionMs = 0L,
                        qualityBadge = _uiState.value.selectedQuality.badge,
                        isFavorite = track.isLiked,
                        errorMessage = null,
                        sessionMemoryCount = recommendationEngine.getSessionTracks().size
                    )
                }

            } else {
                val playableQueue = (queue.map { if (it.id == track.id) track else it } + track)
                    .filter { it.audioUrl.isNotBlank() }.distinctBy { it.id }
                activeQueue = playableQueue.toMutableList()
                val startIndex = playableQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

                val mediaItems = playableQueue.map { it.toMediaItem() }
                player.setMediaItems(mediaItems, startIndex, 0L)
                player.prepare()
                player.play()

                val nextInQueue = activeQueue.getOrNull(startIndex + 1)

                _playbackPosition.value = 0L
                _uiState.update {
                    it.copy(
                        currentTrack = track,
                        queue = activeQueue.toList(),
                        currentIndex = startIndex,
                        isPlaying = true,
                        isLoading = true,
                        durationMs = track.durationMs,
                        currentPositionMs = 0L,
                        qualityBadge = _uiState.value.selectedQuality.badge,
                        isFavorite = track.isLiked,
                        errorMessage = null,
                        nextRecommendedTrack = nextInQueue,
                        sessionMemoryCount = recommendationEngine.getSessionTracks().size
                    )
                }
            }

            publishQueueState()
            refreshAutoplayRecommendation(track)
            Log.i(TAG, "Playing track: ${track.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing track: ${e.message}", e)
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    isLoading = false,
                    errorMessage = "Failed to play track: ${e.message}"
                )
            }
        }
    }

    private fun rememberQueueCandidates(tracks: List<MusicTrack>) {
        val knownIds = candidateCatalogPool.mapTo(mutableSetOf()) { it.id }
        candidateCatalogPool.addAll(tracks.filter { knownIds.add(it.id) })
    }

    private fun cancelQueueRefill() {
        queueGeneration++
        recommendationFetchJob?.cancel()
        recommendationFetchJob = null
        advanceWhenQueueReady = false
    }

    private fun publishQueueState() {
        val index = controller?.currentMediaItemIndex?.coerceAtLeast(0) ?: 0
        val upcoming = activeQueue.drop(index + 1)
        _uiState.update {
            it.copy(
                queue = activeQueue.toList(),
                currentIndex = index,
                nextRecommendedTrack = upcoming.firstOrNull(),
                infiniteAutoplayTracks = upcoming
            )
        }
    }

    /**
     * Refill rather than replace the queue. A single job survives track transitions, resolves
     * beyond unavailable candidates, and publishes each playable song immediately. New playback
     * selections cancel the job so a previous session cannot append to the new queue.
     */
    fun refreshAutoplayRecommendation(track: MusicTrack) {
        val player = controller ?: return
        if (!_uiState.value.isAutoplayEnabled || recommendationFetchJob?.isActive == true) return
        val remaining = activeQueue.size - player.currentMediaItemIndex - 1
        if (remaining > refillThreshold) return
        val generation = queueGeneration
        recommendationFetchJob = scope.launch {
            val attemptedIds = mutableSetOf<String>()
            try {
                suspend fun appendCandidates(pool: List<MusicTrack>) {
                    val excludedIds = activeQueue.mapTo(mutableSetOf()) { it.id } + attemptedIds
                    val ranked = withContext(Dispatchers.Default) {
                        recommendationEngine.buildInfiniteAutoplayRecommendations(
                            currentTrack = track,
                            candidatePool = pool.filterNot { it.audioUrl.contains("soundhelix.com", ignoreCase = true) },
                            limit = 40,
                            alreadyQueuedIds = excludedIds
                        )
                    }
                    for (candidate in ranked) {
                        if (!isActive || queueGeneration != generation || !_uiState.value.isAutoplayEnabled) return
                        if (activeQueue.size - player.currentMediaItemIndex - 1 >= upcomingQueueTarget) return
                        if (!attemptedIds.add(candidate.id) || activeQueue.any { it.id == candidate.id }) continue
                        val resolved = try {
                            withContext(Dispatchers.IO) {
                                if (candidate.audioUrl.startsWith("https://") || candidate.audioUrl.startsWith("http://")) candidate
                                else repository?.resolvePlayableTrack(candidate)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping unavailable queue candidate '${candidate.title}': ${e.message}")
                            null
                        } ?: continue
                        if (resolved.audioUrl.isBlank()) continue
                        if (!isActive || queueGeneration != generation || !_uiState.value.isAutoplayEnabled) return
                        if (activeQueue.any { it.id == resolved.id }) continue

                        // Set the backing list before adding: player callbacks may run synchronously.
                        activeQueue.add(resolved)
                        try {
                            player.addMediaItem(resolved.toMediaItem())
                        } catch (e: Exception) {
                            activeQueue.removeAt(activeQueue.lastIndex)
                            throw e
                        }
                        publishQueueState()
                        if (advanceWhenQueueReady && player.hasNextMediaItem()) {
                            advanceWhenQueueReady = false
                            player.seekToNextMediaItem()
                            player.prepare()
                            player.play()
                        }
                    }
                }

                // Preserve unresolved context tracks as candidates instead of dropping them.
                appendCandidates(candidateCatalogPool.toList().distinctBy { it.id })
                if (activeQueue.size - player.currentMediaItemIndex - 1 < upcomingQueueTarget) {
                    val seed = _uiState.value.currentTrack ?: track
                    val targeted = withContext(Dispatchers.IO) {
                        CustomRecommendationQueryWrapper.fetchTargetedRecommendations(seed, limit = 40)
                    }
                    rememberQueueCandidates(targeted)
                    appendCandidates(targeted)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Queue refill failed: ${e.message}", e)
            } finally {
                if (queueGeneration == generation) {
                    if (advanceWhenQueueReady) {
                        advanceWhenQueueReady = false
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "No playable recommendations are available. Tap next to try again."
                            )
                        }
                    }
                    publishQueueState()
                }
            }
        }
    }

    /**
     * Plays a track from search results, resolving its stream asynchronously on Dispatchers.IO
     * and starting playback without freezing the UI or racing on MediaController.
     */
    fun playFromSearch(track: MusicTrack, searchContextPool: List<MusicTrack>) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main) { playFromSearch(track, searchContextPool) }
            return
        }
        recoveryJob?.cancel()
        recoveryGuard.select(track.id, newRequest = true)

        // 1. Cancel previous in-flight search play jobs, resolution jobs, and recommendation fetches
        activeSearchPlayJob?.cancel()
        playResolutionJob?.cancel()
        cancelQueueRefill()
        rememberQueueCandidates(searchContextPool)
        isCurrentSessionExplicitPlaylist = false

        // 2. Pause audio if actively playing without freezing the Main thread with synchronous stops
        try {
            if (controller?.isPlaying == true) {
                controller?.pause()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing controller on search selection: ${e.message}")
        }

        // 3. Update UI state immediately so user receives instant visual feedback
        _uiState.update {
            it.copy(
                currentTrack = track,
                isLoading = true,
                isPlaying = false,
                errorMessage = null
            )
        }

        // 4. Asynchronously resolve stream URL on Dispatchers.IO
        activeSearchPlayJob = scope.launch(Dispatchers.IO) {
            val resolvedTrack = if (track.audioUrl.isBlank()) {
                val repo = repository
                if (repo != null) {
                    try {
                        repo.resolvePlayableTrack(track)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed resolving track stream: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            if (_uiState.value.currentTrack?.id == track.id) {
                                showPlaybackFailure(e.message ?: "This track is unavailable.")
                            }
                        }
                        return@launch
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (_uiState.value.currentTrack?.id == track.id) {
                            showPlaybackFailure("This track is unavailable.")
                        }
                    }
                    return@launch
                }
            } else track

            if (!isActive) return@launch

            // Immediate fast queue: only include playable items with valid URLs
            val validContext = searchContextPool.filter { it.id != track.id && it.audioUrl.isNotBlank() }.distinctBy { it.id }
            val fastQueue = listOf(resolvedTrack) + validContext

            withContext(Dispatchers.Main) {
                if (isActive && _uiState.value.currentTrack?.id == track.id) {
                    playTrackInternal(resolvedTrack, fastQueue, isExplicitPlaylist = false)
                }
            }
        }
    }

    fun retryPlayback() {
        val current = _uiState.value.currentTrack ?: return
        recoveryGuard.select(current.id, newRequest = true)
        playTrack(current, activeQueue, isCurrentSessionExplicitPlaylist)
    }

    fun setCandidatePool(pool: List<MusicTrack>) {
        synchronized(candidateCatalogPool) {
            candidateCatalogPool.clear()
            candidateCatalogPool.addAll(pool)
        }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isAutoplayEnabled = enabled) }
        if (enabled) {
            _uiState.value.currentTrack?.let { refreshAutoplayRecommendation(it) }
        } else {
            val wasWaiting = advanceWhenQueueReady
            cancelQueueRefill()
            if (wasWaiting) _uiState.update { it.copy(isLoading = false) }
        }
        Log.i(TAG, "setAutoplayEnabled: $enabled")
    }

    fun toggleAutoplay(pool: List<MusicTrack> = emptyList()) {
        if (pool.isNotEmpty()) rememberQueueCandidates(pool)
        setAutoplayEnabled(!_uiState.value.isAutoplayEnabled)
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
            _playbackPosition.value = positionMs
            _uiState.update { it.copy(currentPositionMs = positionMs) }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking to $positionMs: ${e.message}", e)
        }
    }

    fun skipNext() {
        val player = controller ?: return
        try {
            if (player.hasNextMediaItem()) {
                advanceWhenQueueReady = false
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
                _uiState.value.currentTrack?.let { refreshAutoplayRecommendation(it) }
            } else if (_uiState.value.isAutoplayEnabled) {
                val current = _uiState.value.currentTrack ?: return
                // A rapid next press joins the existing refill instead of racing another resolver.
                advanceWhenQueueReady = true
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                refreshAutoplayRecommendation(current)
            } else if (_uiState.value.repeatMode == RepeatMode.ALL && activeQueue.isNotEmpty()) {
                player.seekTo(0, 0L)
                player.prepare()
                player.play()
            }
        } catch (e: Exception) {
            advanceWhenQueueReady = false
            _uiState.update { it.copy(isLoading = false, errorMessage = "Unable to play the next song.") }
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
                publishQueueState()
                Log.d(TAG, "Reordered queue: moved item from $fromIndex to $toIndex")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reordering queue: ${e.message}", e)
        }
    }

    /**
     * Appends a selected track into the next-up playback queue and the Infinite Autoplay pool.
     */
    fun addToQueue(track: MusicTrack) {
        val generation = queueGeneration
        scope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) {
                    if (track.audioUrl.startsWith("https://") || track.audioUrl.startsWith("http://")) track
                    else repository?.resolvePlayableTrack(track)
                } ?: return@launch
                if (generation != queueGeneration || resolved.audioUrl.isBlank()) return@launch
                val player = controller ?: return@launch
                val insertIndex = (player.currentMediaItemIndex + 1).coerceIn(0, activeQueue.size)
                activeQueue.add(insertIndex, resolved)
                try {
                    player.addMediaItem(insertIndex, resolved.toMediaItem())
                } catch (e: Exception) {
                    activeQueue.removeAt(insertIndex)
                    throw e
                }
                rememberQueueCandidates(listOf(resolved))
                publishQueueState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Unable to add '${track.title}' to the queue.") }
                Log.w(TAG, "Unable to queue track", e)
            }
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
        // 1. Immediately persist selection to persistent local storage so preference survives cold restarts
        com.example.data.local.AudioQualityPreferences.setSelectedQuality(context, quality)

        try {
            _uiState.update {
                it.copy(
                    selectedQuality = quality,
                    qualityBadge = quality.badge
                )
            }

            val player = controller
            if (player == null) {
                Log.d(TAG, "Quality preference persisted to storage; player controller not yet initialized")
                return
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
                Log.i(TAG, "Audio quality changed to: ${quality.id} (${quality.badge})")
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
            recommendationFetchJob?.cancel()
            playResolutionJob?.cancel()
            recoveryGuard.select(null, newRequest = true)
            scope.cancel()
            controller?.release()
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controller = null
            synchronized(candidateCatalogPool) {
                candidateCatalogPool.clear()
            }
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
        val rawBadge = targetQuality.badge
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
