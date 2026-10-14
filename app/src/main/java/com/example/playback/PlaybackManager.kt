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
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.UserTasteProfile
import com.example.recommendation.RecommendationEngine
import com.example.service.MusicService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import com.example.data.repository.TrackUnavailableException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
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
    val isInfiniteRadio: Boolean = false,
    val nextRecommendedTrack: MusicTrack? = null,
    val infiniteAutoplayTracks: List<MusicTrack> = emptyList(),
    val sessionMemoryCount: Int = 0
)

enum class RepeatMode {
    OFF, ALL, ONE
}

/** Event-driven listening clock, independent of the foreground scrubber poll. */
data class ListeningPlaybackState(
    val track: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val playSequence: Long = 0L
)

class PlaybackManager(
    private val context: Context,
    var repository: com.example.data.repository.MusicRepository? = null
) {
    private val TAG = "PlaybackManager"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val controllerReady = CompletableDeferred<MediaController>()
    private var progressTickerJob: Job? = null
    private var recoveryJob: Job? = null
    private var youtubeQualityJob: Job? = null
    private val recoveryGuard = PlaybackRecoveryGuard()
    private var autoplaySuppressedBySleepTimer = false
    private val sleepExpirationListener: () -> Unit = {
        autoplaySuppressedBySleepTimer = true
        selectionGeneration++
        pendingSelectionId = null
        recoveryJob?.cancel()
        youtubeQualityJob?.cancel()
        recoveryGuard.select(_uiState.value.currentTrack?.id, newRequest = true)
        activeSearchPlayJob?.cancel()
        playResolutionJob?.cancel()
        cancelQueueRefill()
        _uiState.update { it.copy(isLoading = false, isPlaying = false) }
    }

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
    private var selectionGeneration = 0L
    private var pendingSelectionId: String? = null
    private var selectionContext: List<MusicTrack> = emptyList()

    private val initialQuality = com.example.data.local.AudioQualityPreferences.getSelectedQuality(context)
    private val _uiState = MutableStateFlow(
        PlayerUiState(
            selectedQuality = initialQuality,
            qualityBadge = initialQuality.badge
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val _listeningState = MutableStateFlow(ListeningPlaybackState())
    val listeningState: StateFlow<ListeningPlaybackState> = _listeningState.asStateFlow()
    private var uiForeground = com.example.performance.BackgroundWorkCoordinator.uiForeground.value

    // Isolated high-frequency position state flows so high-frequency updates do NOT trigger full-screen recompositions
    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _trackDuration = MutableStateFlow(0L)
    val trackDuration: StateFlow<Long> = _trackDuration.asStateFlow()

    val currentPlaybackPositionMs: Long
        get() = if (pendingSelectionId != null) 0L else controller?.currentPosition?.coerceAtLeast(0L) ?: _playbackPosition.value

    private var queueRevision = 0L
    private var snapshotRevision = -1L
    private var retainedQueueSnapshot: List<MusicTrack> = emptyList()
    private var upcomingRevision = -1L
    private var upcomingIndex = -1
    private var retainedUpcoming: List<MusicTrack> = emptyList()
    private var activeQueue = mutableListOf<MusicTrack>()
        set(value) { field = value; queueRevision++ }
    private fun queueSnapshot(): List<MusicTrack> {
        if (snapshotRevision != queueRevision) {
            retainedQueueSnapshot = activeQueue.toList()
            snapshotRevision = queueRevision
        }
        return retainedQueueSnapshot
    }
    private fun upcomingTracks(index: Int): List<MusicTrack> {
        if (upcomingRevision != queueRevision || upcomingIndex != index) {
            val queue = queueSnapshot()
            retainedUpcoming = queue.subList((index + 1).coerceIn(0, queue.size), queue.size)
            upcomingRevision = queueRevision
            upcomingIndex = index
        }
        return retainedUpcoming
    }
    private fun replaceQueueTrack(index: Int, track: MusicTrack) {
        if (index in activeQueue.indices && activeQueue[index] != track) {
            activeQueue[index] = track
            queueRevision++
        }
    }
    private var isCurrentSessionExplicitPlaylist: Boolean = false

    init {
        SleepTimerManager.addExpirationListener(sleepExpirationListener)
        initMediaController()
        scope.launch {
            com.example.performance.BackgroundWorkCoordinator.uiForeground.collect { visible ->
                uiForeground = visible
                if (visible) {
                    updateProgressValues()
                    if (controller?.isPlaying == true) startProgressTicker()
                } else stopProgressTicker()
            }
        }
    }

    private fun initMediaController() {
        try {
            val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture?.addListener({
                scope.launch {
                    try {
                        val connected = requireNotNull(controllerFuture?.get())
                        controller = connected
                        if (pendingSelectionId != null) {
                            connected.pause()
                            connected.stop()
                            connected.clearMediaItems()
                        }
                        setupPlayerListener()
                        controllerReady.complete(connected)
                        Log.i(TAG, "MediaController initialized successfully")
                    } catch (e: Exception) {
                        controllerReady.completeExceptionally(e)
                        Log.e(TAG, "Failed to initialize MediaController: ${e.message}", e)
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Player initialization failed") }
                    }
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            controllerReady.completeExceptionally(e)
            Log.e(TAG, "Error setting up MediaController: ${e.message}", e)
            _uiState.update { it.copy(errorMessage = "Failed to initialize player: ${e.message}") }
        }
    }

    private fun controllerTrack(item: MediaItem): MusicTrack {
        val meta = item.mediaMetadata
        val extras = meta.extras
        val source = extras?.getString("media_source").orEmpty()
        return MusicTrack(id = item.mediaId, title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(), album = meta.albumTitle?.toString().orEmpty(),
            durationMs = extras?.getLong("duration", 0L) ?: 0L,
            coverUrl = meta.artworkUri?.toString().orEmpty(),
            audioUrl = item.localConfiguration?.uri?.toString() ?: item.requestMetadata.mediaUri?.toString().orEmpty(),
            bitrateKbps = extras?.getInt("bitrate", 0) ?: 0,
            qualityBadge = extras?.getString("qualityBadge").orEmpty(),
            genre = meta.genre?.toString() ?: extras?.getString("genre") ?: "Unknown",
            isLiked = extras?.getBoolean("isLiked", false) ?: false,
            singers = extras?.getString("singers").orEmpty(), writer = extras?.getString("writer").orEmpty(),
            language = extras?.getString("language") ?: "Unknown", year = extras?.getString("year").orEmpty(),
            isrc = extras?.getString("isrc").orEmpty(),
            source = source.ifBlank { if (item.mediaId.startsWith("yt_")) "Extended Stream" else "HD Stream" },
            isLossless = item.mediaId.startsWith("ia_") || extras?.getBoolean("isLossless", false) == true)
    }

    private fun synchronizeControllerQueue(player: MediaController): Boolean {
        val count = player.mediaItemCount
        val known by lazy(LazyThreadSafetyMode.NONE) { activeQueue.associateBy { it.id } }
        var revised: MutableList<MusicTrack>? = null
        for (index in 0 until count) {
            val item = player.getMediaItemAt(index)
            val existing = activeQueue.getOrNull(index)
            val track = existing?.takeIf { it.id == item.mediaId } ?: known[item.mediaId] ?: controllerTrack(item)
            if (existing != track) {
                val target = revised ?: activeQueue.toMutableList().also { revised = it }
                if (index < target.size) target[index] = track else target.add(track)
            }
        }
        if (count < activeQueue.size) {
            val target = revised ?: activeQueue.toMutableList().also { revised = it }
            target.subList(count, target.size).clear()
        }
        revised?.let { activeQueue = it }
        return revised != null
    }

    private fun setupPlayerListener() {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot setup listener: player is null")
            return
        }

        synchronizeControllerQueue(player)
        player.currentMediaItem?.let { item ->
            val track = activeQueue.getOrNull(player.currentMediaItemIndex) ?: controllerTrack(item)
            recoveryGuard.select(track.id)
            _uiState.update { it.copy(currentTrack = track, queue = queueSnapshot(), currentIndex = player.currentMediaItemIndex,
                isFavorite = track.isLiked,
                isPlaying = player.isPlaying, isLoading = player.playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration.takeIf { duration -> duration > 0L } ?: track.durationMs) }
            updateProgressValues()
            publishListeningState(player, newPlay = true)
            if (player.isPlaying) startProgressTicker()
        }
        player.addListener(object : Player.Listener {
            private var queueContentsChanged = false
            private var listeningPlayRestart = false
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || timeline.windowCount != activeQueue.size)
                    queueContentsChanged = true
            }
            override fun onEvents(player: Player, events: Player.Events) {
                val controller = this@PlaybackManager.controller ?: return
                if (pendingSelectionId != null && controller.currentMediaItem?.mediaId != pendingSelectionId) return
                if (queueContentsChanged) {
                    queueContentsChanged = false
                    if (synchronizeControllerQueue(controller) ||
                        _uiState.value.currentIndex != controller.currentMediaItemIndex.coerceAtLeast(0)) publishQueueState()
                }
                publishListeningState(controller, newPlay = listeningPlayRestart)
                listeningPlayRestart = false
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (pendingSelectionId != null && player.currentMediaItem?.mediaId != pendingSelectionId) {
                    if (isPlaying) player.pause()
                    return
                }
                if (isPlaying) autoplaySuppressedBySleepTimer = false
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
                if (pendingSelectionId != null && player.currentMediaItem?.mediaId != pendingSelectionId) return
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
                    val endedId = player.currentMediaItem?.mediaId
                    val endedGeneration = selectionGeneration
                    if (pendingSelectionId != null || endedId != _uiState.value.currentTrack?.id) return
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
                        if (selectionGeneration != endedGeneration || pendingSelectionId != null ||
                            player.currentMediaItem?.mediaId != endedId) return@launch
                        if (autoplaySuppressedBySleepTimer) return@launch
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
                    if (player.currentMediaItem?.mediaId != trackId) return
                    if (pendingSelectionId != null && pendingSelectionId != trackId) return
                    if (pendingSelectionId == trackId) pendingSelectionId = null
                    recoveryGuard.select(trackId)
                    val oldTrack = _uiState.value.currentTrack
                    val newListeningPlay = _listeningState.value.track?.id != trackId ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    val elapsed = (System.currentTimeMillis() - currentTrackStartTimeMs).coerceAtLeast(0L)
                    val wasMeaningful = hasRecordedMeaningfulPlay
                    val shouldRecordPlayback = player.isPlaying || player.playWhenReady
                    if (activeQueue.size != player.mediaItemCount || activeQueue.getOrNull(player.currentMediaItemIndex)?.id != trackId)
                        synchronizeControllerQueue(player)
                    val matchedTrack = activeQueue.getOrNull(player.currentMediaItemIndex)?.takeIf { it.id == trackId }
                        ?: controllerTrack(mediaItem)
                    val index = player.currentMediaItemIndex.takeIf {
                        it in activeQueue.indices && activeQueue[it].id == trackId
                    } ?: activeQueue.indexOfFirst { it.id == trackId }.coerceAtLeast(0)

                    // The upcoming song in queue is the next recommended track
                    val nextTrackInQueue = activeQueue.getOrNull(index + 1)

                    _uiState.update {
                        it.copy(
                            currentTrack = matchedTrack,
                            currentIndex = index,
                            durationMs = if (player.duration > 0) player.duration else matchedTrack.durationMs,
                            qualityBadge = if (com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(matchedTrack.id, matchedTrack.audioUrl)) matchedTrack.qualityBadge else _uiState.value.selectedQuality.badge,
                            isFavorite = matchedTrack.isLiked,
                            nextRecommendedTrack = nextTrackInQueue,
                            queue = queueSnapshot(),
                            infiniteAutoplayTracks = upcomingTracks(index)
                        )
                    }
                    listeningPlayRestart = listeningPlayRestart || newListeningPlay

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
                if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                    oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId &&
                    oldPosition.positionMs > 10_000L && newPosition.positionMs < 1_000L)
                    listeningPlayRestart = true
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (pendingSelectionId != null) return
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
                val failedUri = player.currentMediaItem?.localConfiguration?.uri
                val failedUrl = if (failedUri?.scheme == "xtreme-youtube") current.audioUrl
                    else failedUri?.toString() ?: current.audioUrl
                val transientFailure = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        ?.responseCode?.let { it in 500..599 } == true
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                recoveryJob = scope.launch {
                    try {
                        // CDN URLs can stay identical after a temporary connection/server failure.
                        // The existing guard allows just one retry per selection, never a loop.
                        val resolved = if (transientFailure) {
                            delay(300L)
                            current
                        } else withContext(Dispatchers.IO) {
                            repo.resolvePlayableTrack(current, forceRefresh = true, failedUrl = failedUrl)
                        }
                        if (!recoveryGuard.isCurrent(current.id, token) ||
                            _uiState.value.currentTrack?.id != current.id) return@launch
                        val index = player.currentMediaItemIndex
                        val position = player.currentPosition.coerceAtLeast(0L)
                        val resume = player.playWhenReady
                        val replacement = resolved.toMediaItem()
                        if (!transientFailure && failedUri?.scheme != "xtreme-youtube" &&
                            replacement.localConfiguration?.uri?.toString() == failedUrl) {
                            throw TrackUnavailableException(current.title)
                        }
                        replaceQueueTrack(index, resolved)
                        player.replaceMediaItem(index, replacement)
                        player.seekTo(index, position)
                        player.prepare()
                        player.playWhenReady = resume
                        _uiState.update {
                            it.copy(
                                currentTrack = resolved,
                                queue = queueSnapshot(),
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
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val current = _uiState.value.currentTrack ?: return
                val extras = mediaMetadata.extras ?: return
                if (!com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(current.id, current.audioUrl) || controller?.currentMediaItem?.mediaId != current.id ||
                    !extras.getBoolean("audio_actual")) return
                val updated = current.copy(bitrateKbps = extras.getInt("bitrate"),
                    qualityBadge = extras.getString("qualityBadge").orEmpty(), isLossless = false)
                if (updated == current) return
                val index = activeQueue.indexOfFirst { it.id == current.id }
                replaceQueueTrack(index, updated)
                _uiState.update { it.copy(currentTrack = updated, qualityBadge = updated.qualityBadge,
                    queue = queueSnapshot()) }
            }
        })
    }

    private fun showPlaybackFailure(message: String) {
        pendingSelectionId = null
        _uiState.update { it.copy(isLoading = false, isPlaying = false, errorMessage = message) }
    }

    private fun publishListeningState(player: MediaController, newPlay: Boolean = false) {
        if (newPlay) {
            currentTrackStartTimeMs = System.currentTimeMillis()
            hasRecordedMeaningfulPlay = false
        }
        val item = player.currentMediaItem
        val track = item?.let {
            _uiState.value.currentTrack?.takeIf { current -> current.id == it.mediaId }
                ?: activeQueue.getOrNull(player.currentMediaItemIndex)?.takeIf { current -> current.id == it.mediaId }
                ?: controllerTrack(it)
        }
        _listeningState.update { previous -> ListeningPlaybackState(track,
            player.isPlaying && !autoplaySuppressedBySleepTimer,
            player.duration.takeIf { it > 0L } ?: track?.durationMs ?: 0L,
            previous.playSequence + if (newPlay && track != null) 1L else 0L) }
    }

    internal fun recordMeaningfulListening(trackId: String, playSequence: Long, listenedMs: Long, durationMs: Long) {
        if (hasRecordedMeaningfulPlay || _listeningState.value.playSequence != playSequence ||
            _uiState.value.currentTrack?.id != trackId ||
            (listenedMs < 30_000L && (durationMs <= 0L || listenedMs < (durationMs + 1L) / 2L))) return
        hasRecordedMeaningfulPlay = true
        val track = _uiState.value.currentTrack ?: return
        scope.launch(Dispatchers.IO) {
            try {
                listeningEventDao.insertListeningEvent(ListeningEventEntity.fromTrack(
                    track, currentSessionId, listenedMs, durationMs, "MEANINGFUL_PLAY",
                    completed = false, skipped = false, liked = track.isLiked))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Couldn't save meaningful listening: ${e.message}") }
        }
    }

    private fun startProgressTicker() {
        if (!uiForeground || progressTickerJob?.isActive == true) return
        progressTickerJob = scope.launch {
            while (isActive && uiForeground) {
                updateProgressValues()
                delay(500L)
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
        if (uiForeground) updateProgressValues()
    }

    private fun updateProgressValues() {
        if (!uiForeground) return
        if (pendingSelectionId != null) return
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

        } catch (e: Exception) {
            Log.e(TAG, "Error updating progress values: ${e.message}", e)
        }
    }

    /** Own the requested identity before native callbacks or asynchronous lookup can run. */
    private fun beginSelection(track: MusicTrack, queue: List<MusicTrack>): Long {
        val generation = ++selectionGeneration
        pendingSelectionId = track.id
        selectionContext = queue.toList()
        val previous = _uiState.value.currentTrack
        if (previous != null && previous.id != track.id && controller?.currentMediaItem?.mediaId == previous.id) {
            playbackHistory.add(previous)
            if (playbackHistory.size > 50) playbackHistory.removeAt(0)
        }
        stopProgressTicker()
        _listeningState.update { it.copy(isPlaying = false) }
        activeQueue = mutableListOf()
        _playbackPosition.value = 0L
        _bufferedPosition.value = 0L
        _trackDuration.value = track.durationMs
        _uiState.update { it.copy(currentTrack = track, isPlaying = false, isLoading = true,
            currentPositionMs = 0L, bufferedPositionMs = 0L, durationMs = track.durationMs,
            isFavorite = track.isLiked, queue = emptyList(), currentIndex = 0,
            nextRecommendedTrack = null, infiniteAutoplayTracks = emptyList(), errorMessage = null) }
        // stop() retains the old media items. Removing them prevents a resume or a
        // deferred completion callback from replaying the previous song during lookup.
        try {
            controller?.pause()
            controller?.stop()
            controller?.clearMediaItems()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear the previous playback selection: ${e.message}")
        }
        return generation
    }

    private fun ownsSelection(trackId: String, generation: Long): Boolean =
        selectionGeneration == generation && _uiState.value.currentTrack?.id == trackId

    private suspend fun awaitController(): MediaController = controller ?: withTimeoutOrNull(8_000L) {
        controllerReady.await()
    } ?: throw IllegalStateException("Player connection timed out. Please try again.")

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = listOf(track), isExplicitPlaylist: Boolean = false, isInfiniteRadio: Boolean = false) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { playTrack(track, queue, isExplicitPlaylist, isInfiniteRadio) }
            return
        }
        val now = System.currentTimeMillis()
        val currentTrack = _uiState.value.currentTrack
        val isSameTrack = currentTrack?.id == track.id

        // 1. Double-tap on currently playing track:
        // If already playing smoothly, do nothing; if paused, resume playback.
        // If in loading state, reset loading state safely.
        if (isSameTrack && !isInfiniteRadio && _uiState.value.errorMessage == null && now - lastPlayRequestTimeMs < 450L) {
            val player = controller
            // UI selection can precede replacement of the controller's previous media item.
            if (pendingSelectionId == track.id || playResolutionJob?.isActive == true || activeSearchPlayJob?.isActive == true) return
            if (player != null && player.currentMediaItem?.mediaId == track.id) {
                if (!player.isPlaying) {
                    try {
                        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                            player.prepare()
                        }
                        player.play()
                    } catch (_: Exception) {}
                }
                // Ensure loading flag is kept accurate and not stuck
                if (_uiState.value.isLoading && player.isPlaying) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                return
            }
        }

        // 2. Debounce rapid spam clicks on the same track if already loading or processing (<250ms)
        if (lastRequestedTrackId == track.id && !isInfiniteRadio && _uiState.value.errorMessage == null && now - lastPlayRequestTimeMs < 250L) {
            return
        }

        lastPlayRequestTimeMs = now
        lastRequestedTrackId = track.id
        youtubeQualityJob?.cancel()
        recoveryJob?.cancel()
        recoveryGuard.select(track.id, newRequest = true)
        isCurrentSessionExplicitPlaylist = isExplicitPlaylist
        _uiState.update { it.copy(isInfiniteRadio = isInfiniteRadio) }

        // Cancel previous unresolved network/stream resolution job and search queue jobs
        activeSearchPlayJob?.cancel()
        playResolutionJob?.cancel()
        cancelQueueRefill()
        rememberQueueCandidates(queue)
        val generation = beginSelection(track, queue)

        val repo = repository
        val needsResolution = com.example.data.repository.StreamResolver.needsResolution(track)
        if (needsResolution || controller == null) {
            playResolutionJob = scope.launch {
                try {
                    awaitController()
                    val resolved = if (needsResolution) withContext(Dispatchers.IO) {
                        (repo ?: throw IllegalStateException("Music catalogue is unavailable. Please try again."))
                            .resolvePlayableTrack(track)
                    } else track
                    val updatedQueue = queue.map { if (it.id == track.id) resolved else it }
                    if (isActive && ownsSelection(track.id, generation)) {
                        playTrackInternal(resolved, updatedQueue, isExplicitPlaylist)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }
                    Log.e(TAG, "Failed resolving track stream: ${e.message}", e)
                    if (ownsSelection(track.id, generation)) {
                        showPlaybackFailure(e.message ?: "This track is unavailable.")
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
        if (track.audioUrl.isBlank() || Uri.parse(track.audioUrl).scheme !in setOf("http", "https", "file", "content", "xtreme-youtube")) {
            showPlaybackFailure("No playable stream is available for '${track.title}'.")
            return
        }
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot play track: player is null")
            showPlaybackFailure("Player not initialized")
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
                        queue = queueSnapshot(),
                        currentIndex = 0,
                        isPlaying = true,
                        isLoading = true,
                        durationMs = track.durationMs,
                        currentPositionMs = 0L,
                        qualityBadge = if (com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl)) track.qualityBadge else _uiState.value.selectedQuality.badge,
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
                        queue = queueSnapshot(),
                        currentIndex = startIndex,
                        isPlaying = true,
                        isLoading = true,
                        durationMs = track.durationMs,
                        currentPositionMs = 0L,
                        qualityBadge = if (com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl)) track.qualityBadge else _uiState.value.selectedQuality.badge,
                        isFavorite = track.isLiked,
                        errorMessage = null,
                        nextRecommendedTrack = nextInQueue,
                        sessionMemoryCount = recommendationEngine.getSessionTracks().size
                    )
                }
            }

            if (pendingSelectionId == track.id && player.currentMediaItem?.mediaId == track.id) pendingSelectionId = null
            publishQueueState()
            refreshAutoplayRecommendation(track)
            Log.i(TAG, "Playing track: ${track.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing track: ${e.message}", e)
            showPlaybackFailure("Failed to play track: ${e.message}")
        }
    }

    private fun rememberQueueCandidates(tracks: List<MusicTrack>) {
        val merged = (candidateCatalogPool.toList() + tracks).distinctBy { it.id }.takeLast(600)
        candidateCatalogPool.clear()
        candidateCatalogPool.addAll(merged)
    }

    private fun cancelQueueRefill() {
        queueGeneration++
        recommendationFetchJob?.cancel()
        recommendationFetchJob = null
        advanceWhenQueueReady = false
    }

    private fun publishQueueState() {
        val index = controller?.currentMediaItemIndex?.coerceAtLeast(0) ?: 0
        val upcoming = upcomingTracks(index)
        _uiState.update {
            it.copy(
                queue = queueSnapshot(),
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
        if (pendingSelectionId != null) return
        val player = controller ?: return
        if (!_uiState.value.isAutoplayEnabled || recommendationFetchJob?.isActive == true) return
        val remaining = activeQueue.size - player.currentMediaItemIndex - 1
        if (remaining > refillThreshold) return
        val generation = queueGeneration
        recommendationFetchJob = scope.launch {
            val attemptedIds = mutableSetOf<String>()
            try {
                val taste = withContext(Dispatchers.IO) {
                    val events = listeningEventDao.getActualPlays()
                    val metadata = events.map { it.trackId }.distinct().chunked(900)
                        .flatMap { database.musicDao().getTracksByIds(it) }.map { it.toMusicTrack() }
                    val favorites = repository?.getFavoriteTracks()?.first().orEmpty()
                    withContext(Dispatchers.Default) { UserTasteProfile.fromEvents(events, metadata, favorites) }
                }
                suspend fun appendCandidates(pool: List<MusicTrack>, targetSize: Int = upcomingQueueTarget) {
                    val excludedIds = activeQueue.mapTo(mutableSetOf()) { it.id } + attemptedIds
                    val queuedSnapshot = queueSnapshot()
                    val ranked = withContext(Dispatchers.Default) {
                        recommendationEngine.buildInfiniteAutoplayRecommendations(
                            currentTrack = track,
                            candidatePool = pool.filterNot { it.audioUrl.contains("soundhelix.com", ignoreCase = true) },
                            limit = 40,
                            alreadyQueuedIds = excludedIds,
                            alreadyQueuedTracks = queuedSnapshot,
                            tasteProfile = taste
                        )
                    }
                    for (candidate in ranked) {
                        if (!isActive || queueGeneration != generation || !_uiState.value.isAutoplayEnabled) return
                        if (activeQueue.size - player.currentMediaItemIndex - 1 >= targetSize) return
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
                        val currentQueue = queueSnapshot()
                        val duplicate = withContext(Dispatchers.Default) {
                            val queued = HomeRecommendationPolicy.Recordings()
                            currentQueue.forEach { queued.add(it) }
                            queued.contains(resolved)
                        }
                        if (!isActive || queueGeneration != generation || !_uiState.value.isAutoplayEnabled) return
                        if (duplicate || activeQueue.any { it.id == resolved.id }) continue

                        // Set the backing list before adding: player callbacks may run synchronously.
                        activeQueue.add(resolved)
                        queueRevision++
                        try {
                            player.addMediaItem(resolved.toMediaItem())
                        } catch (e: Exception) {
                            activeQueue.removeAt(activeQueue.lastIndex)
                            queueRevision++
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

                val seed = _uiState.value.currentTrack ?: track
                val local = candidateCatalogPool.toList()
                val discovery = async(Dispatchers.IO) {
                    withTimeoutOrNull(15_000) {
                        CustomRecommendationQueryWrapper.fetchTargetedRecommendations(seed, limit = 60)
                    }.orEmpty()
                }
                // Keep an exhausted queue moving while discovery runs; do not fill all slots with generic catalog tracks.
                if (activeQueue.size - player.currentMediaItemIndex - 1 == 0) appendCandidates(local, targetSize = 1)
                val targeted = discovery.await()
                rememberQueueCandidates(targeted)
                appendCandidates(targeted)
                if (activeQueue.size - player.currentMediaItemIndex - 1 < upcomingQueueTarget) appendCandidates(local)
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
        youtubeQualityJob?.cancel()
        recoveryJob?.cancel()
        recoveryGuard.select(track.id, newRequest = true)

        // 1. Cancel previous in-flight search play jobs, resolution jobs, and recommendation fetches
        activeSearchPlayJob?.cancel()
        playResolutionJob?.cancel()
        cancelQueueRefill()
        rememberQueueCandidates(searchContextPool)
        isCurrentSessionExplicitPlaylist = false
        _uiState.update { it.copy(isInfiniteRadio = false) }
        val generation = beginSelection(track, searchContextPool)

        // 4. Asynchronously resolve stream URL on Dispatchers.IO
        activeSearchPlayJob = scope.launch {
            try {
                awaitController()
                val resolvedTrack = if (com.example.data.repository.StreamResolver.needsResolution(track)) {
                    withContext(Dispatchers.IO) {
                        (repository ?: throw IllegalStateException("Music catalogue is unavailable. Please try again."))
                            .resolvePlayableTrack(track)
                    }
                } else track
                if (isActive && ownsSelection(track.id, generation)) {
                    val validContext = searchContextPool.filter { it.id != track.id && it.audioUrl.isNotBlank() }.distinctBy { it.id }
                    val fastQueue = listOf(resolvedTrack) + validContext
                    playTrackInternal(resolvedTrack, fastQueue, isExplicitPlaylist = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ownsSelection(track.id, generation)) showPlaybackFailure(e.message ?: "This track is unavailable.")
            }
        }
    }

    fun retryPlayback() {
        val current = _uiState.value.currentTrack ?: return
        recoveryGuard.select(current.id, newRequest = true)
        playTrack(current, activeQueue.ifEmpty { selectionContext }, isCurrentSessionExplicitPlaylist, _uiState.value.isInfiniteRadio)
    }

    fun setCandidatePool(pool: List<MusicTrack>) {
        synchronized(candidateCatalogPool) {
            if (candidateCatalogPool == pool) return
            candidateCatalogPool.clear()
            candidateCatalogPool.addAll(pool)
        }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isAutoplayEnabled = enabled, isInfiniteRadio = enabled && it.isInfiniteRadio) }
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

        // A loading UI selection may not yet exist in the native player. Never
        // prepare/play a different item or trigger resumption of the saved queue.
        if (pendingSelectionId != null) return
        val selected = _uiState.value.currentTrack
        if (selected != null && player.currentMediaItem?.mediaId != selected.id) {
            playTrack(selected, _uiState.value.queue.ifEmpty { selectionContext.ifEmpty { listOf(selected) } },
                isCurrentSessionExplicitPlaylist, _uiState.value.isInfiniteRadio)
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
                    playTrack(prevTrack, activeQueue.ifEmpty { listOf(prevTrack) }, isInfiniteRadio = _uiState.value.isInfiniteRadio)
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
                replaceQueueTrack(idx, activeQueue[idx].copy(isLiked = isLiked))
                _uiState.update { it.copy(queue = queueSnapshot()) }
            }
            controller?.let { publishListeningState(it) }
            Log.d(TAG, "Updated favorite status for track $trackId: $isLiked")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating favorite status: ${e.message}", e)
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        try {
            if (fromIndex in activeQueue.indices && toIndex in activeQueue.indices) {
                val item = activeQueue.removeAt(fromIndex)
                queueRevision++
                activeQueue.add(toIndex, item)
                queueRevision++
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
                queueRevision++
                try {
                    player.addMediaItem(insertIndex, resolved.toMediaItem())
                } catch (e: Exception) {
                    activeQueue.removeAt(insertIndex)
                    queueRevision++
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
                    qualityBadge = it.currentTrack?.takeIf { track -> com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl) }
                        ?.qualityBadge ?: quality.badge
                )
            }

            val player = controller
            if (player == null) {
                Log.d(TAG, "Quality preference persisted to storage; player controller not yet initialized")
                return
            }

            val currentTrack = _uiState.value.currentTrack ?: return
            if (com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(currentTrack.id, currentTrack.audioUrl)) {
                youtubeQualityJob?.cancel()
                youtubeQualityJob = scope.launch {
                    try {
                        val resolved = withContext(Dispatchers.IO) {
                            if (currentTrack.id.startsWith("yt_")) com.example.data.remote.YouTubeMusicApiService.resolveAudio(currentTrack, quality)
                            else repository?.resolvePlayableTrack(currentTrack, forceRefresh = true)
                        } ?: throw TrackUnavailableException(currentTrack.title)
                        if (_uiState.value.currentTrack?.id != currentTrack.id || _uiState.value.selectedQuality != quality ||
                            player.currentMediaItem?.mediaId != currentTrack.id) return@launch
                        val index = player.currentMediaItemIndex
                        val position = player.currentPosition
                        val resume = player.playWhenReady
                        val updated = resolved.copy(isLiked = _uiState.value.currentTrack?.isLiked ?: resolved.isLiked)
                        replaceQueueTrack(index, updated)
                        player.replaceMediaItem(index, updated.toMediaItem())
                        player.seekTo(index, position); player.prepare(); player.playWhenReady = resume
                        _uiState.update { it.copy(currentTrack = updated, qualityBadge = updated.qualityBadge,
                            queue = queueSnapshot()) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { _uiState.update { it.copy(errorMessage = "Couldn't change audio quality. Current playback is preserved.") } }
                }
                return
            }
            if (currentTrack.audioUrl.contains("saavncdn.com")) {
                val availableBitrate = currentTrack.saavnBitrateFor(quality)
                val updatedUrl = com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(
                    currentTrack.audioUrl,
                    availableBitrate.toString()
                )
                val updatedTrack = currentTrack.copy(
                    audioUrl = updatedUrl,
                    // Keep the catalog's supported maximum when changing the playback preference.
                    qualityBadge = if (availableBitrate < 96) "$availableBitrate kbps" else AudioQuality.fromId(availableBitrate.toString()).badge
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
                        qualityBadge = updatedTrack.qualityBadge
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
        selectionGeneration++
        pendingSelectionId = null
        controllerReady.cancel()
        SleepTimerManager.removeExpirationListener(sleepExpirationListener)
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

    private fun MusicTrack.saavnBitrateFor(quality: AudioQuality): Int {
        val supported = bitrateKbps.takeIf { it in 1..320 } ?: 320
        val requested = minOf(quality.kbps, supported, 320)
        return when { requested >= 320 -> 320; requested >= 160 -> 160; requested >= 96 -> 96; else -> 48 }
    }

    private fun MusicTrack.toMediaItem(): MediaItem {
        val targetQuality = _uiState.value.selectedQuality
        val availableBitrate = saavnBitrateFor(targetQuality)
        val effectiveUrl = if (audioUrl.contains("saavncdn.com")) {
            com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(audioUrl, availableBitrate.toString())
        } else {
            audioUrl
        }
        val effectiveBitrate = if (audioUrl.contains("saavncdn.com")) availableBitrate else bitrateKbps
        val rawBadge = if (audioUrl.contains("saavncdn.com")) {
            if (availableBitrate < 96) "$availableBitrate kbps" else AudioQuality.fromId(availableBitrate.toString()).badge
        } else if (com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(id, audioUrl)) qualityBadge.ifBlank { "HQ Stream" } else targetQuality.badge
        val effectiveBadge = rawBadge
            .replace("YouTube Music", "HQ Stream", ignoreCase = true)
            .replace("YouTube", "HQ Stream", ignoreCase = true)
            .replace("YT Music", "HQ", ignoreCase = true)
            .replace("JioSaavn", "HD Stream", ignoreCase = true)
            .replace("Saavn", "HD Stream", ignoreCase = true)

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(com.example.data.remote.YouTubeAudioStreamService.playbackUri(id, effectiveUrl))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(Uri.parse(coverUrl))
                    .setExtras(Bundle().apply {
                        putInt("bitrate", effectiveBitrate)
                        putInt("requested_audio_kbps", targetQuality.kbps)
                        putString("qualityBadge", effectiveBadge)
                        putString("genre", genre)
                        putString("media_source", source)
                        putLong("duration", durationMs)
                        putBoolean("isLiked", isLiked)
                        putBoolean("isLossless", isLossless)
                        putString("singers", singers); putString("writer", writer)
                        putString("language", language); putString("year", year); putString("isrc", isrc)
                    })
                    .build()
            )
            .build()
    }
}
