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
import com.example.service.PlaybackSessionStore
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
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
    val playSequence: Long = 0L,
    val playSessionId: String = ""
)

class PlaybackManager(
    private val context: Context,
    var repository: com.example.data.repository.MusicRepository? = null
) {
    private val TAG = "PlaybackManager"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
        set(value) {
            field = value
            com.example.service.SabrSessionRegistry.controllerHandlesRecovery = value?.let { connected ->
                { connected.applicationLooper == android.os.Looper.myLooper() && connected.isConnected }
            }
        }
    private var controllerReady = CompletableDeferred<MediaController>()
    private val sessionRestored = CompletableDeferred<Unit>()
    private var pendingRestoredSession: PlaybackSessionStore.Snapshot? = null
    private var progressTickerJob: Job? = null
    private var recoveryJob: Job? = null
    private var youtubeQualityJob: Job? = null
    private var losslessUpgradeJob: Job? = null
    private var losslessAttemptKey: String? = null
    private var losslessNetworkBlockedKey: String? = null
    private var losslessLookupStarted = false
    private var losslessTransitionId: String? = null
    private data class LosslessFallback(val generation: Long, val original: MusicTrack,
        val upgraded: MusicTrack, var resumePositionMs: Long, var activated: Boolean = false,
        var audioConfirmed: Boolean = false, var confirmedAudio: Bundle? = null) {
        val upgradedUrl: String get() = upgraded.audioUrl
    }
    private var losslessFallback: LosslessFallback? = null
    private val recoveryGuard = PlaybackRecoveryGuard()
    private var autoplaySuppressedBySleepTimer = false
    private val sleepExpirationListener: () -> Unit = {
        autoplaySuppressedBySleepTimer = true
        selectionGeneration++
        pendingSelectionId = null
        recoveryJob?.cancel()
        youtubeQualityJob?.cancel()
        cancelLosslessUpgrade()
        recoveryGuard.select(_uiState.value.currentTrack?.id, newRequest = true)
        activeSearchPlayJob?.cancel()
        playResolutionJob?.cancel()
        cancelQueueRefill()
        _uiState.update { it.copy(isLoading = false, isPlaying = false) }
    }

    val recommendationEngine = RecommendationEngine()
    @Volatile private var candidateCatalogPool: List<MusicTrack> = emptyList()
    private val candidateDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var recommendationFetchJob: Job? = null
    private var queueGeneration = 0L
    private var advanceWhenQueueReady = false
    private val refillThreshold = 5
    private val upcomingQueueTarget = 12
    private var lastRefillKey: String? = null
    private var lastRefillAt = 0L

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
    @Volatile private var initialJioDeadlineMs = 0L
    private var selectionContext: List<MusicTrack> = emptyList()

    private val initialQuality = (context.applicationContext as? com.example.XtremeMusicApp)
        ?.startupSettings?.quality ?: AudioQuality.EXTREME_320
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
        scope.launch {
            MusicService.liveSession.collectLatest { alive ->
                if (!alive) return@collectLatest
                NetworkQualityMonitor.getInstance(context).activeQualityCeiling.collect { policy ->
                    if (policy.effectiveCeiling != AudioQualityCeiling.MAX) {
                        losslessUpgradeJob?.cancel()
                        losslessUpgradeJob = null
                        losslessAttemptKey = null
                        losslessLookupStarted = false
                        val current = _uiState.value.currentTrack
                        if (pendingSelectionId == null && current != null && controller?.currentMediaItem?.mediaId == current.id)
                            losslessNetworkBlockedKey = "$selectionGeneration|${current.id}"
                    }
                    // Raising a ceiling does not upgrade/restart the active song.
                    // A new selection can use lossless again if its profile allows it.
                }
            }
        }
        SleepTimerManager.addExpirationListener(sleepExpirationListener)
        scope.launch {
            try {
                val saved = PlaybackSessionStore.restore(context)
                val tracks = withContext(Dispatchers.Default) { saved?.items?.map(::controllerTrack).orEmpty() }
                // A tap or an already-running service always wins over the disk snapshot.
                if (saved != null && tracks.isNotEmpty() && selectionGeneration == 0L &&
                    (controller == null || controller?.mediaItemCount == 0)) {
                    pendingRestoredSession = saved
                    activeQueue = tracks.toMutableList()
                    val current = tracks[saved.index]
                    _playbackPosition.value = saved.positionMs
                    _trackDuration.value = current.durationMs
                    _uiState.update { it.copy(currentTrack = current, queue = queueSnapshot(), currentIndex = saved.index,
                        isPlaying = false, isLoading = false, isFavorite = current.isLiked,
                        currentPositionMs = saved.positionMs, durationMs = current.durationMs,
                        qualityBadge = current.qualityBadge.ifBlank { it.selectedQuality.badge }) }
                }
            } finally { sessionRestored.complete(Unit) }
        }
        scope.launch {
            // A service started by a headset/Auto is already paying initialization
            // costs. Observe and reconnect immediately instead of waiting for idle.
            MusicService.liveSession.collect { alive -> if (alive) ensureMediaControllerConnected() }
        }
        scope.launch {
            delay(4_000L)
            com.example.performance.BackgroundWorkCoordinator.awaitIdle(minimumUptimeMillis = 4_000L)
            if (_uiState.value.currentTrack != null || MusicService.liveSession.value)
                ensureMediaControllerConnected()
        }
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
        if (controller != null || controllerFuture != null) return
        if (controllerReady.isCompleted) controllerReady = CompletableDeferred()
        try {
            val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
            controllerFuture = MediaController.Builder(context, sessionToken)
                .setListener(object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        applyJioFallbackStatus(controller, extras.getBundle("jio_fallback_status"))
                        applyActualAudioProperties(controller, extras.getBundle("active_audio"))
                    }
                }).buildAsync()
            controllerFuture?.addListener({
                scope.launch {
                    try {
                        val connected = requireNotNull(controllerFuture?.get())
                        controller = connected
                        if (pendingSelectionId != null) {
                            pendingRestoredSession = null
                            connected.pause()
                            connected.stop()
                            connected.clearMediaItems()
                        } else if (connected.mediaItemCount == 0) {
                            sessionRestored.await()
                            hydrateRestoredSession(connected)
                        }
                        setupPlayerListener()
                        applyJioFallbackStatus(connected, connected.sessionExtras.getBundle("jio_fallback_status"))
                        applyActualAudioProperties(connected, connected.sessionExtras.getBundle("active_audio"))
                        controllerReady.complete(connected)
                        Log.i(TAG, "MediaController initialized successfully")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        controller?.release()
                        controllerFuture?.let(MediaController::releaseFuture)
                        controllerFuture = null
                        controller = null
                        controllerReady.completeExceptionally(e)
                        Log.e(TAG, "Failed to initialize MediaController: ${e.message}", e)
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Player initialization failed") }
                    }
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            controllerFuture = null
            controllerReady.completeExceptionally(e)
            Log.e(TAG, "Error setting up MediaController: ${e.message}", e)
            _uiState.update { it.copy(errorMessage = "Failed to initialize player: ${e.message}") }
        }
    }

    private suspend fun hydrateRestoredSession(player: MediaController) {
        val saved = pendingRestoredSession ?: return
        pendingRestoredSession = null
        val generation = selectionGeneration
        withContext(Dispatchers.IO) { NetworkQualityMonitor.getInstance(context).snapshot() }
        while (pendingSelectionId == null && player.mediaItemCount == 0) {
            val revision = queueRevision
            val quality = _uiState.value.selectedQuality
            val tracks = queueSnapshot()
            if (tracks.isEmpty()) return
            val prepared = withContext(Dispatchers.Default) {
                val items = tracks.map { it.toMediaItem(quality) }
                items to items.map(::controllerTrack)
            }
            if (selectionGeneration != generation || pendingSelectionId != null || player.mediaItemCount > 0) return
            // A settings/favorite edit during hydration must not be lost or apply the old bitrate.
            if (revision != queueRevision || quality != _uiState.value.selectedQuality) continue
            activeQueue = prepared.second.toMutableList()
            player.setMediaItems(prepared.first, saved.index.coerceIn(prepared.first.indices), saved.positionMs)
            player.pause() // Queue metadata only; prepare/play remain explicit user actions.
            return
        }
    }

    /** All connection/hydration and queued player commands stay on the application looper. */
    private fun ensureMediaControllerConnected(guardSelection: Boolean = true,
        onReady: ((MediaController) -> Unit)? = null) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { ensureMediaControllerConnected(guardSelection, onReady) }
            return
        }
        controller?.let {
            if (!it.isConnected) {
                it.release()
                controller = null
                controllerFuture?.let(MediaController::releaseFuture)
                controllerFuture = null
            } else if (controllerReady.isCompleted) { onReady?.invoke(it); return }
        }
        initMediaController()
        if (onReady != null) {
            val generation = selectionGeneration
            val readiness = controllerReady
            scope.launch {
                try {
                    val player = withTimeoutOrNull(8_000L) { readiness.await() } ?: return@launch
                    if (!guardSelection || generation == selectionGeneration) onReady(player)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (generation == selectionGeneration) showPlaybackFailure("Unable to connect to the player. Please try again.")
                }
            }
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
            isLossless = item.mediaId.startsWith("ia_") || extras?.getBoolean("isLossless", false) == true,
            recommendationOnly = extras?.getBoolean("recommendation_only", false) == true)
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
            if (player.isPlaying) { startProgressTicker(); maybeUpgradeToLossless() }
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
                    confirmLosslessActivation(player)
                    maybeUpgradeToLossless()
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
                    if (losslessFallback == null) {
                        losslessUpgradeJob?.cancel()
                        losslessUpgradeJob = null
                        if (!losslessLookupStarted) losslessAttemptKey = null
                    }
                }
                Log.d(TAG, "Playing state changed: $isPlaying")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (pendingSelectionId != null && player.currentMediaItem?.mediaId != pendingSelectionId) return
                val isLoading = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) confirmLosslessActivation(player)
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
                    val serviceSourceTransition = oldTrack?.id == trackId && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                        mediaItem.mediaMetadata.extras?.getBoolean("jio_youtube_fallback") == true
                    val sourceOnlyTransition = oldTrack?.id == trackId && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                        (serviceSourceTransition || losslessTransitionId == trackId || losslessFallback?.let {
                            it.original.id == trackId && it.upgradedUrl == mediaItem.localConfiguration?.uri?.toString()
                        } == true)
                    if (oldTrack?.id != trackId) cancelLosslessUpgrade()
                    if (sourceOnlyTransition) losslessTransitionId = null
                    // Keep the original quality and queue snapshot until this replacement
                    // is genuinely ready. A source change is not a new listening session.
                    if (sourceOnlyTransition && losslessFallback?.activated == false) return
                    val newListeningPlay = _listeningState.value.track?.id != trackId ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    val elapsed = (System.currentTimeMillis() - currentTrackStartTimeMs).coerceAtLeast(0L)
                    val wasMeaningful = hasRecordedMeaningfulPlay
                    val shouldRecordPlayback = player.isPlaying || player.playWhenReady
                    if (activeQueue.size != player.mediaItemCount || activeQueue.getOrNull(player.currentMediaItemIndex)?.id != trackId)
                        synchronizeControllerQueue(player)
                    val matchedTrack = if (serviceSourceTransition) controllerTrack(mediaItem).copy(isLiked = oldTrack?.isLiked ?: false)
                        else activeQueue.getOrNull(player.currentMediaItemIndex)?.takeIf { it.id == trackId } ?: controllerTrack(mediaItem)
                    if (serviceSourceTransition) replaceQueueTrack(player.currentMediaItemIndex, matchedTrack)
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
                            qualityBadge = matchedTrack.qualityBadge.ifBlank { _uiState.value.qualityBadge },
                            isFavorite = matchedTrack.isLiked,
                            nextRecommendedTrack = nextTrackInQueue,
                            queue = queueSnapshot(),
                            infiniteAutoplayTracks = upcomingTracks(index)
                        )
                    }
                    listeningPlayRestart = listeningPlayRestart || newListeningPlay
                    // Changing only the transport preserves the listening session and lyrics identity.
                    if (sourceOnlyTransition) return
                    if (player.isPlaying) maybeUpgradeToLossless()

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
                if (reason == Player.DISCONTINUITY_REASON_SEEK && losslessFallback?.activated == false &&
                    newPosition.mediaItem?.mediaId == losslessFallback?.original?.id)
                    losslessFallback?.resumePositionMs = newPosition.positionMs.coerceAtLeast(0L)
                if (reason == Player.DISCONTINUITY_REASON_SEEK && losslessFallback?.activated != false &&
                    losslessTransitionId == null &&
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
                if (com.example.service.JioSaavnPlaybackFallback.handles(player.currentMediaItem)) {
                    _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                    return
                }
                if (restoreLosslessFallback(player, current)) return
                losslessUpgradeJob?.cancel()
                val repo = repository
                val token = recoveryGuard.claim(current.id)
                if (repo == null || token == null) {
                    if (!advanceFailedYouTubeFallback(player)) showPlaybackFailure("Unable to play '${current.title}'. Please try again.")
                    return
                }
                recoveryJob?.cancel()
                val failedUri = player.currentMediaItem?.playbackUri()
                val youtubeUri = failedUri?.toString()?.let(com.example.data.remote.YouTubeAudioStreamService::isPlaybackUri) == true ||
                    com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(current.id, current.audioUrl)
                val sabrReplacement = failedUri?.toString()?.let(com.example.service.SabrSessionRegistry::takeReplacement)
                val initialTransportSelection = sabrReplacement != null && failedUri?.scheme == "xtreme-youtube" &&
                    failedUri.getQueryParameter("itag") == null
                val failedUrl = if (youtubeUri) current.audioUrl
                    else failedUri?.toString() ?: current.audioUrl
                val transientFailure = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                        ?.responseCode?.let { it in 500..599 } == true
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                recoveryJob = scope.launch {
                    try {
                        // CDN URLs can stay identical after a temporary connection/server failure.
                        // The generation-owned guard bounds retries; rejected YouTube sessions are re-extracted.
                        val resolved = if (sabrReplacement != null) {
                            // Preserve recording metadata/queue identity, and reopen a new
                            // container by playback time rather than reusing old byte offsets.
                            current.copy(
                                audioUrl = com.example.data.remote.YouTubeAudioStreamService.playbackUri(
                                    "yt_${sabrReplacement.videoId}", sabrReplacement.url),
                                bitrateKbps = sabrReplacement.bitrateKbps,
                                qualityBadge = sabrReplacement.qualityBadge,
                                isLossless = false
                            )
                        } else if (transientFailure && !youtubeUri) {
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
                        if (!transientFailure && !youtubeUri &&
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
                        // An unresolved queue item first acquires its concrete
                        // transport here. That one-time URI change must not spend
                        // the later network-recovery budget; the new URI is pinned.
                        if (initialTransportSelection) recoveryGuard.select(current.id, newRequest = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (recoveryGuard.isCurrent(current.id, token)) {
                            if (!advanceFailedYouTubeFallback(player)) showPlaybackFailure(e.message ?: "Unable to resolve a playable stream.")
                        }
                    }
                }
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val current = _uiState.value.currentTrack ?: return
                val extras = mediaMetadata.extras ?: return
                val player = controller ?: return
                if (player.currentMediaItem?.mediaId != current.id || !extras.getBoolean("audio_actual")) return
                val fallback = losslessFallback
                if (fallback != null && player.currentMediaItem?.playbackUri()?.toString() == fallback.upgradedUrl) {
                    applyActualAudioProperties(player, player.sessionExtras.getBundle("active_audio"))
                    return
                }
                val uri = player.currentMediaItem?.playbackUri() ?: return
                val saavn = uri.host == "saavncdn.com" || uri.host?.endsWith(".saavncdn.com") == true
                if (!saavn && !com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(current.id, current.audioUrl)) return
                // JioSaavn keeps its catalog maximum for later quality changes; the
                // actual selected bitrate is carried by the badge and Media3 extras.
                val updated = current.copy(bitrateKbps = if (saavn) current.bitrateKbps else extras.getInt("bitrate"),
                    qualityBadge = extras.getString("qualityBadge").orEmpty(), isLossless = false)
                if (updated == current) return
                val index = activeQueue.indexOfFirst { it.id == current.id }
                replaceQueueTrack(index, updated)
                _uiState.update { it.copy(currentTrack = updated, qualityBadge = updated.qualityBadge,
                    queue = queueSnapshot()) }
            }
        })
    }

    private fun applyJioFallbackStatus(player: MediaController, status: Bundle?) {
        val current = _uiState.value.currentTrack ?: return
        if (status?.getString("track_id") != current.id || player.currentMediaItem?.mediaId != current.id) return
        if (status.getString("source_uri")?.let { it != player.currentMediaItem?.playbackUri()?.toString() } == true) return
        when (status.getString("state")) {
            "ready" -> _uiState.update { it.copy(isLoading = false, errorMessage = null) }
            "resolving" -> {
                cancelLosslessUpgrade()
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }
            "replaced" -> {
                val uri = status.getString("uri") ?: return
                if (player.currentMediaItem?.playbackUri()?.toString() != uri ||
                    current.audioUrl == uri && current.source == status.getString("source")) return
                val updated = current.copy(audioUrl = uri, source = status.getString("source").orEmpty(),
                    bitrateKbps = status.getInt("bitrate"), qualityBadge = status.getString("qualityBadge").orEmpty(), isLossless = false)
                if (updated == current) return
                replaceQueueTrack(player.currentMediaItemIndex, updated)
                _uiState.update { it.copy(currentTrack = updated, queue = queueSnapshot(), qualityBadge = updated.qualityBadge,
                    isLoading = player.playbackState == Player.STATE_BUFFERING, errorMessage = null) }
                publishListeningState(player, newPlay = false)
            }
            "failed" -> if (!player.hasNextMediaItem()) showPlaybackFailure(status.getString("message") ?: "Playback is unavailable.")
        }
    }

    private fun advanceFailedYouTubeFallback(player: MediaController): Boolean {
        if (player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("jio_youtube_fallback") != true ||
            !player.hasNextMediaItem()) return false
        player.seekToNextMediaItem(); player.prepare(); player.play()
        return true
    }

    private fun continueAfterUnavailableSelection(track: MusicTrack, queue: List<MusicTrack>, explicit: Boolean, message: String?) {
        val index = queue.indexOfFirst { it.id == track.id }
        val next = if (index >= 0) queue.drop(index + 1).firstOrNull { it.id != track.id } else null
        if (next != null) playTrack(next, queue, explicit)
        else showPlaybackFailure(message ?: "This track is unavailable.")
    }

    private fun MediaItem.playbackUri(): Uri? = localConfiguration?.uri ?: requestMetadata.mediaUri

    private fun applyActualAudioProperties(player: MediaController, audio: Bundle?) {
        val extras = audio ?: return
        val current = _uiState.value.currentTrack ?: return
        if (!extras.getBoolean("audio_actual") || extras.getString("track_id") != current.id ||
            player.currentMediaItem?.mediaId != current.id) return
        val address = extras.getString("active_audio_uri") ?: return
        if (player.currentMediaItem?.playbackUri()?.toString() != address) return
        val fallback = losslessFallback
        if (fallback != null && fallback.upgradedUrl == address) {
            fallback.audioConfirmed = extras.getBoolean("isLossless")
            fallback.confirmedAudio = Bundle(extras)
            confirmLosslessActivation(player)
            return
        }
        if (fallback != null && !fallback.activated) return
        val updated = current.copy(qualityBadge = extras.getString("qualityBadge").orEmpty(),
            bitrateKbps = if (address.contains("saavncdn.com")) current.bitrateKbps else extras.getInt("bitrate"),
            isLossless = extras.getBoolean("isLossless"))
        if (updated == current) return
        replaceQueueTrack(player.currentMediaItemIndex, updated)
        _uiState.update { it.copy(currentTrack = updated, qualityBadge = updated.qualityBadge, queue = queueSnapshot()) }
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
            previous.playSequence + if (newPlay && track != null) 1L else 0L,
            if (track != null && (newPlay || previous.track?.id != track.id || previous.playSessionId.isBlank()))
                java.util.UUID.randomUUID().toString() else previous.playSessionId) }
    }

    internal fun recordMeaningfulListening(trackId: String, playSequence: Long, listenedMs: Long, durationMs: Long) {
        if (hasRecordedMeaningfulPlay || _listeningState.value.playSequence != playSequence ||
            _uiState.value.currentTrack?.id != trackId ||
            (listenedMs < 30_000L && (durationMs <= 0L || listenedMs < (durationMs + 1L) / 2L))) return
        hasRecordedMeaningfulPlay = true
        val track = _uiState.value.currentTrack ?: return
        val sessionId = currentSessionId
        scope.launch(Dispatchers.IO) {
            try {
                listeningEventDao.insertListeningEvent(ListeningEventEntity.fromTrack(
                    track, sessionId, listenedMs, durationMs, "MEANINGFUL_PLAY",
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
            val upgrade = losslessFallback
            if (upgrade != null && !upgrade.activated &&
                player.currentMediaItem?.playbackUri()?.toString() == upgrade.upgradedUrl &&
                kotlin.math.abs(pos - upgrade.resumePositionMs) > 2_000L) return
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

    private fun cancelLosslessUpgrade() {
        losslessUpgradeJob?.cancel()
        losslessUpgradeJob = null
        losslessAttemptKey = null
        losslessNetworkBlockedKey = null
        losslessLookupStarted = false
        losslessFallback = null
        losslessTransitionId = null
    }

    /** Start only after audible playback, and never let optional Archive work delay selection. */
    private fun maybeUpgradeToLossless() {
        val state = _uiState.value
        if (state.selectedQuality != AudioQuality.HI_RES_LOSSLESS || pendingSelectionId != null) return
        val networkKey = "$selectionGeneration|${state.currentTrack?.id}"
        if (NetworkQualityMonitor.getInstance(context).currentMaxBitrateBps != Int.MAX_VALUE) {
            losslessNetworkBlockedKey = networkKey
            return
        }
        if (losslessNetworkBlockedKey == networkKey) return
        val player = controller ?: return
        // This controller is built on Main; all native reads and mutations stay on its owner.
        if (player.applicationLooper != android.os.Looper.getMainLooper() || !player.isPlaying) return
        val original = state.currentTrack ?: return
        if (original.recommendationOnly) return
        if (player.currentMediaItem?.mediaId != original.id ||
            original.isLossless && original.qualityBadge.startsWith("Hi-Res", ignoreCase = true)) return
        val repo = repository ?: return
        val generation = selectionGeneration
        val key = "$generation|${original.id}"
        if (losslessAttemptKey == key || losslessUpgradeJob?.isActive == true) return
        losslessAttemptKey = key
        losslessLookupStarted = false
        fun stillCurrent(): Boolean = ownsSelection(original.id, generation) &&
            pendingSelectionId == null && _uiState.value.selectedQuality == AudioQuality.HI_RES_LOSSLESS &&
            NetworkQualityMonitor.getInstance(context).currentMaxBitrateBps == Int.MAX_VALUE &&
            player.currentMediaItem?.mediaId == original.id && player.isPlaying &&
            _uiState.value.currentTrack?.audioUrl == original.audioUrl
        losslessUpgradeJob = scope.launch {
            try {
                // Let the chosen provider establish playback and its initial buffer first.
                // A pause/rebuffer cancels this pre-lookup delay and starts it afresh on resume.
                delay(3_000L)
                if (!stillCurrent()) return@launch
                // A short-buffer transport (including SABR) must not permanently disable
                // upgrades. Wait a bounded interval for healthy playback, then attempt once.
                val stable = withTimeoutOrNull(6_000L) {
                    while (stillCurrent() && player.bufferedPosition - player.currentPosition < 1_000L) delay(250L)
                    stillCurrent()
                } == true
                if (!stable) return@launch
                losslessLookupStarted = true
                val duration = player.duration.takeIf { it > 0L } ?: original.durationMs
                if (duration <= 0L) return@launch
                val reference = original.copy(durationMs = duration)
                val lossless = withContext(Dispatchers.IO) { repo.findLosslessUpgrade(reference) } ?: return@launch
                if (!stillCurrent()) return@launch
                // Optional prefetch must not starve the audible provider on a weak link.
                if (player.playbackState != Player.STATE_READY ||
                    duration - player.currentPosition < 8_000L) return@launch
                // Preserve the selected recording's identity, artwork, lyrics and user metadata.
                val candidate = original.copy(audioUrl = lossless.audioUrl, bitrateKbps = lossless.bitrateKbps,
                    qualityBadge = lossless.qualityBadge, isLossless = true, source = lossless.source)
                val warmed = LosslessStreamPrebuffer.prepare(context, candidate.toMediaItem(), duration,
                    currentPosition = { player.currentPosition }, stillCurrent = ::stillCurrent)
                if (warmed == null) {
                    if (stillCurrent()) repo.rejectLosslessUpgrade(reference)
                    return@launch
                }
                val qualityStillSelected = withContext(Dispatchers.IO) {
                    com.example.data.local.AudioQualityPreferences.getSelectedQuality(context) == AudioQuality.HI_RES_LOSSLESS
                }
                if (!qualityStillSelected || !stillCurrent() || !warmed.covers(player.currentPosition)) return@launch
                val index = player.currentMediaItemIndex
                if (index !in activeQueue.indices) return@launch
                val position = player.currentPosition.coerceAtLeast(0L)
                val resume = player.playWhenReady
                val latest = _uiState.value.currentTrack ?: return@launch
                if (latest.isLossless && latest.qualityBadge.startsWith("Hi-Res") && !warmed.audio.isHiRes) return@launch
                val replacement = latest.copy(audioUrl = candidate.audioUrl, bitrateKbps = warmed.audio.bitrateKbps,
                    qualityBadge = warmed.audio.badge, isLossless = true, source = candidate.source)
                losslessFallback = LosslessFallback(generation, latest, replacement, position)
                losslessTransitionId = latest.id
                val resolvedItem = replacement.toMediaItem()
                val item = resolvedItem.buildUpon().setMediaMetadata(
                    resolvedItem.mediaMetadata.buildUpon().setExtras(Bundle(
                        resolvedItem.mediaMetadata.extras ?: Bundle()).apply {
                            // The prebuffer parsed the real container, but the audible source
                            // still needs to confirm decoder/input activation below.
                            putBoolean("audio_actual", false)
                            putInt("sample_rate", warmed.audio.sampleRate)
                            putInt("bit_depth", warmed.audio.bitDepth)
                            putString("audio_codec", warmed.audio.codec)
                        }).build()).build()
                player.replaceMediaItem(index, item)
                player.seekTo(index, position)
                player.prepare()
                player.playWhenReady = resume
                val activated = withTimeoutOrNull(4_000L) {
                    while (ownsSelection(original.id, generation) &&
                        player.currentMediaItem?.playbackUri()?.toString() == replacement.audioUrl) {
                        applyActualAudioProperties(player, player.sessionExtras.getBundle("active_audio"))
                        confirmLosslessActivation(player)
                        if (losslessFallback?.activated == true) return@withTimeoutOrNull true
                        if (player.playerError != null) break
                        delay(50L)
                    }
                    false
                } == true
                if (!activated && ownsSelection(original.id, generation)) {
                    _uiState.value.currentTrack?.let { restoreLosslessFallback(player, it) }
                    repo.rejectLosslessUpgrade(reference)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val current = _uiState.value.currentTrack
                if (current != null && ownsSelection(current.id, generation)) restoreLosslessFallback(player, current)
                Log.d(TAG, "Optional lossless upgrade unavailable: ${e.message}")
            } finally {
                if (losslessAttemptKey == key && !losslessLookupStarted) losslessAttemptKey = null
            }
        }
    }

    /** Commit source/quality only after Media3 reports the matching lossless input ready. */
    private fun confirmLosslessActivation(player: MediaController) {
        val fallback = losslessFallback ?: return
        if (fallback.activated || !fallback.audioConfirmed || fallback.generation != selectionGeneration ||
            _uiState.value.selectedQuality != AudioQuality.HI_RES_LOSSLESS ||
            player.currentMediaItem?.mediaId != fallback.original.id || player.playbackState != Player.STATE_READY ||
            kotlin.math.abs(player.currentPosition - fallback.resumePositionMs) > 2_000L ||
            player.currentMediaItem?.playbackUri()?.toString() != fallback.upgradedUrl || player.playerError != null) return
        val current = _uiState.value.currentTrack?.takeIf { it.id == fallback.original.id } ?: return
        val extras = fallback.confirmedAudio ?: player.mediaMetadata.extras ?: return
        if (!extras.getBoolean("isLossless") || !extras.getBoolean("audio_actual")) return
        val updated = current.copy(audioUrl = fallback.upgradedUrl, bitrateKbps = extras.getInt("bitrate"),
            qualityBadge = extras.getString("qualityBadge") ?: fallback.upgraded.qualityBadge,
            isLossless = true, source = fallback.upgraded.source)
        fallback.activated = true
        replaceQueueTrack(player.currentMediaItemIndex, updated)
        _uiState.update { it.copy(currentTrack = updated, qualityBadge = updated.qualityBadge, queue = queueSnapshot()) }
        Log.i(TAG, "Activated the verified ${updated.qualityBadge} source at the current position")
    }

    /** A failed upgraded transport falls back once to the already-playing original recording. */
    private fun restoreLosslessFallback(player: MediaController, current: MusicTrack): Boolean {
        val fallback = losslessFallback ?: return false
        if (fallback.generation != selectionGeneration || fallback.original.id != current.id ||
            fallback.upgradedUrl != player.currentMediaItem?.playbackUri()?.toString() ||
            player.currentMediaItem?.mediaId != current.id) return false
        losslessFallback = null
        val index = player.currentMediaItemIndex
        if (index !in activeQueue.indices) return false
        val position = if (fallback.activated) player.currentPosition.coerceAtLeast(0L)
            else fallback.resumePositionMs.coerceAtLeast(0L)
        val resume = player.playWhenReady
        val original = current.copy(audioUrl = fallback.original.audioUrl, bitrateKbps = fallback.original.bitrateKbps,
            qualityBadge = fallback.original.qualityBadge, isLossless = fallback.original.isLossless,
            source = fallback.original.source)
        return try {
            losslessTransitionId = original.id
            replaceQueueTrack(index, original)
            _uiState.update { it.copy(currentTrack = original, qualityBadge = original.qualityBadge,
                errorMessage = null, queue = queueSnapshot()) }
            player.replaceMediaItem(index, original.toMediaItem())
            player.seekTo(index, position)
            player.prepare()
            player.playWhenReady = resume
            true
        } catch (e: Exception) {
            losslessTransitionId = null
            Log.w(TAG, "Original stream recovery failed: ${e.message}")
            false
        }
    }

    /** Own the requested identity before native callbacks or asynchronous lookup can run. */
    private fun beginSelection(track: MusicTrack, queue: List<MusicTrack>): Long {
        pendingRestoredSession = null
        cancelLosslessUpgrade()
        val generation = ++selectionGeneration
        pendingSelectionId = track.id
        initialJioDeadlineMs = android.os.SystemClock.elapsedRealtime() + 6_000L
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
            qualityBadge = track.qualityBadge.ifBlank { "HQ Stream" },
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

    private suspend fun awaitController(): MediaController {
        ensureMediaControllerConnected()
        return withTimeoutOrNull(8_000L) { controllerReady.await() }
            ?: throw IllegalStateException("Player connection timed out. Please try again.")
    }

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
        val jioDeadline = initialJioDeadlineMs

        val repo = repository
        val needsResolution = com.example.data.repository.StreamResolver.needsResolution(track)
        // Even already-resolved selections prepare potentially large playlist metadata off Main.
        playResolutionJob = scope.launch {
            try {
                awaitController()
                val resolved = if (needsResolution) withContext(Dispatchers.IO) {
                    (repo ?: throw IllegalStateException("Music catalogue is unavailable. Please try again."))
                        .resolvePlayableTrack(track, jioDeadlineMs = jioDeadline)
                } else track
                if (isActive && ownsSelection(track.id, generation)) {
                    playTrackInternal(resolved, queue, isExplicitPlaylist)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "Failed resolving track stream: ${e.message}", e)
                if (ownsSelection(track.id, generation)) {
                    continueAfterUnavailableSelection(track, queue, isExplicitPlaylist, e.message)
                }
            }
        }
    }

    private suspend fun playTrackInternal(track: MusicTrack, queue: List<MusicTrack>, isExplicitPlaylist: Boolean = false) {
        if (track.audioUrl.isBlank() || Uri.parse(track.audioUrl).scheme !in setOf("http", "https", "file", "content", "xtreme-youtube", "xtreme-sabr")) {
            showPlaybackFailure("No playable stream is available for '${track.title}'.")
            return
        }
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot play track: player is null")
            showPlaybackFailure("Player not initialized")
            return
        }

        val generation = selectionGeneration
        withContext(Dispatchers.IO) { NetworkQualityMonitor.getInstance(context).snapshot() }
        var autoplayEnabled: Boolean
        var targetQuality: AudioQuality
        var prepared: Pair<MutableList<MusicTrack>, List<MediaItem>>
        do {
            autoplayEnabled = _uiState.value.isAutoplayEnabled
            targetQuality = _uiState.value.selectedQuality
            prepared = withContext(Dispatchers.Default) {
                val tracks = if (autoplayEnabled && !isExplicitPlaylist) {
                    val contextTracks = queue.filter { it.id != track.id && it.audioUrl.isNotBlank() }.distinctBy { it.id }
                    (listOf(track) + contextTracks).toMutableList()
                } else {
                    (queue.map { if (it.id == track.id) track else it } + track)
                        .filter { it.audioUrl.isNotBlank() }.distinctBy { it.id }.toMutableList()
                }
                tracks to tracks.map { it.toMediaItem(targetQuality) }
            }
            if (!ownsSelection(track.id, generation)) return
        } while (autoplayEnabled != _uiState.value.isAutoplayEnabled || targetQuality != _uiState.value.selectedQuality)

        try {
            val oldTrack = _uiState.value.currentTrack
            if (oldTrack != null && oldTrack.id != track.id) {
                playbackHistory.add(oldTrack)
                if (playbackHistory.size > 50) playbackHistory.removeAt(0)
            }

            recommendationEngine.recordTrackPlayed(track)

            if (autoplayEnabled && !isExplicitPlaylist) {
                // Autoplay Session:
                // Start playing immediately with playable candidates only
                activeQueue = prepared.first
                val mediaItems = prepared.second
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
                        qualityBadge = mediaItems[0].mediaMetadata.extras?.getString("qualityBadge") ?: track.qualityBadge,
                        isFavorite = track.isLiked,
                        errorMessage = null,
                        sessionMemoryCount = recommendationEngine.getSessionTracks().size
                    )
                }

            } else {
                activeQueue = prepared.first
                val startIndex = activeQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                val mediaItems = prepared.second
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
                        qualityBadge = mediaItems[startIndex].mediaMetadata.extras?.getString("qualityBadge") ?: track.qualityBadge,
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
        scope.launch(candidateDispatcher) {
            val merged = (candidateCatalogPool + tracks).distinctBy { it.id }.takeLast(600)
            ensureActive()
            candidateCatalogPool = merged
        }
    }

    private fun cancelQueueRefill() {
        queueGeneration++
        recommendationFetchJob?.cancel()
        recommendationFetchJob = null
        advanceWhenQueueReady = false
        lastRefillKey = null
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
        val requestKey = "$generation|${track.id}|${player.currentMediaItemIndex}|$remaining"
        val now = android.os.SystemClock.elapsedRealtime()
        if (!advanceWhenQueueReady && requestKey == lastRefillKey && now - lastRefillAt < 2_000L) return
        lastRefillKey = requestKey
        lastRefillAt = now
        recommendationFetchJob = scope.launch {
            val attemptedIds = mutableSetOf<String>()
            try {
                val taste = withContext(Dispatchers.IO) {
                    val events = listeningEventDao.getActualPlays(limit = 5000)
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
                                if (!com.example.data.repository.StreamResolver.needsResolution(candidate)) candidate
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
                val local = candidateCatalogPool.filter { it.recommendationOnly &&
                    com.example.recommendation.UnifiedRecommendationEngine.verified(it) }
                val queued = queueSnapshot()
                val needed = (upcomingQueueTarget - remaining).coerceAtLeast(1)
                val discovery = async(Dispatchers.IO) {
                    withTimeoutOrNull(15_000) {
                        repository?.recommendationRadio(seed, alreadyQueued = queued, limit = needed, taste = taste).orEmpty()
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
        val jioDeadline = initialJioDeadlineMs

        // 4. Asynchronously resolve stream URL on Dispatchers.IO
        activeSearchPlayJob = scope.launch {
            try {
                awaitController()
                val resolvedTrack = if (com.example.data.repository.StreamResolver.needsResolution(track)) {
                    withContext(Dispatchers.IO) {
                        (repository ?: throw IllegalStateException("Music catalogue is unavailable. Please try again."))
                            .resolvePlayableTrack(track, jioDeadlineMs = jioDeadline)
                    }
                } else track
                val fastQueue = withContext(Dispatchers.Default) {
                    val validContext = searchContextPool.filter { it.id != track.id && it.audioUrl.isNotBlank() }.distinctBy { it.id }
                    listOf(resolvedTrack) + validContext
                }
                if (isActive && ownsSelection(track.id, generation)) {
                    playTrackInternal(resolvedTrack, fastQueue, isExplicitPlaylist = false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ownsSelection(track.id, generation)) continueAfterUnavailableSelection(track,
                    listOf(track) + searchContextPool.filterNot { it.id == track.id }, false, e.message)
            }
        }
    }

    fun retryPlayback() {
        val current = _uiState.value.currentTrack ?: return
        recoveryGuard.select(current.id, newRequest = true)
        playTrack(current, activeQueue.ifEmpty { selectionContext }, isCurrentSessionExplicitPlaylist, _uiState.value.isInfiniteRadio)
    }

    fun setCandidatePool(pool: List<MusicTrack>) {
        scope.launch(candidateDispatcher) {
            if (candidateCatalogPool == pool) return@launch
            val snapshot = pool.toList()
            ensureActive()
            candidateCatalogPool = snapshot
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
        if (player == null || !player.isConnected || !controllerReady.isCompleted) {
            ensureMediaControllerConnected { playPause() }
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
        if (player == null || !player.isConnected || !controllerReady.isCompleted) {
            ensureMediaControllerConnected { seekTo(positionMs) }
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
        val player = controller
        if (player == null || !player.isConnected || !controllerReady.isCompleted) {
            ensureMediaControllerConnected { skipNext() }
            return
        }
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
        if (player == null || !player.isConnected || !controllerReady.isCompleted) {
            ensureMediaControllerConnected { skipPrevious() }
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
        if (player == null || !player.isConnected || !controllerReady.isCompleted) {
            ensureMediaControllerConnected(guardSelection = false) { toggleShuffle() }
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
        if (player == null || !player.isConnected || !controllerReady.isCompleted) {
            ensureMediaControllerConnected(guardSelection = false) { toggleRepeat() }
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
        if (controller == null || !controllerReady.isCompleted) {
            ensureMediaControllerConnected { reorderQueue(fromIndex, toIndex) }
            return
        }
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
                    if (!com.example.data.repository.StreamResolver.needsResolution(track)) track
                    else repository?.resolvePlayableTrack(track)
                } ?: return@launch
                if (generation != queueGeneration || resolved.audioUrl.isBlank()) return@launch
                val player = awaitController()
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
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { setAudioQuality(quality) }
            return
        }
        if (quality == _uiState.value.selectedQuality && losslessFallback != null) return
        if (quality != AudioQuality.HI_RES_LOSSLESS) {
            val player = controller
            val current = _uiState.value.currentTrack
            if (player != null && current != null) restoreLosslessFallback(player, current)
        }
        cancelLosslessUpgrade()
        // 1. Immediately persist selection to persistent local storage so preference survives cold restarts
        com.example.data.local.AudioQualityPreferences.setSelectedQuality(context, quality)

        try {
            _uiState.update {
                it.copy(
                    selectedQuality = quality,
                    qualityBadge = it.currentTrack?.qualityBadge?.takeIf(String::isNotBlank) ?: quality.badge
                )
            }

            val player = controller
            if (player == null || !player.isConnected || !controllerReady.isCompleted) {
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
                        if (player.isPlaying) maybeUpgradeToLossless()
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
                    qualityBadge = com.example.data.remote.YouTubeAudioStreamService.displayQuality("AAC", availableBitrate)
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
            if (player.isPlaying) maybeUpgradeToLossless()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting audio quality: ${e.message}", e)
        }
    }

    val availableAudioDevices: StateFlow<List<SoundOutputDevice>> = AudioDeviceManager.availableDevices
    val selectedAudioDeviceId: StateFlow<Int> = AudioDeviceManager.selectedDeviceId

    fun selectAudioOutputDevice(deviceId: Int) {
        if (controller?.isConnected != true || !controllerReady.isCompleted) {
            ensureMediaControllerConnected(guardSelection = false) { selectAudioOutputDevice(deviceId) }
            return
        }
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
        cancelLosslessUpgrade()
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
            candidateCatalogPool = emptyList()
            Log.i(TAG, "PlaybackManager released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources: ${e.message}", e)
        }
    }

    private fun MusicTrack.saavnBitrateFor(quality: AudioQuality): Int {
        val supported = bitrateKbps.takeIf { it in 1..320 } ?: 320
        val requested = minOf(quality.kbps, supported, 320,
            NetworkQualityMonitor.getInstance(context).currentMaxBitrateBps / 1000)
        return when { requested >= 320 -> 320; requested >= 160 -> 160; requested >= 96 -> 96; else -> 48 }
    }

    private fun MusicTrack.toMediaItem(targetQuality: AudioQuality = _uiState.value.selectedQuality): MediaItem {
        val availableBitrate = saavnBitrateFor(targetQuality)
        val effectiveUrl = if (audioUrl.contains("saavncdn.com")) {
            com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(audioUrl, availableBitrate.toString())
        } else {
            audioUrl
        }
        val effectiveBitrate = if (audioUrl.contains("saavncdn.com")) availableBitrate else bitrateKbps
        val rawBadge = if (audioUrl.contains("saavncdn.com")) {
            com.example.data.remote.YouTubeAudioStreamService.displayQuality("AAC", availableBitrate)
        } else qualityBadge.ifBlank { if (isLossless) "Lossless" else "HQ Stream" }
        val effectiveBadge = rawBadge
            .replace("YouTube Music", "HQ Stream", ignoreCase = true)
            .replace("YouTube", "HQ Stream", ignoreCase = true)
            .replace("YT Music", "HQ", ignoreCase = true)
            .replace("JioSaavn", "HD Stream", ignoreCase = true)
            .replace("Saavn", "HD Stream", ignoreCase = true)

        val mediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(com.example.data.remote.YouTubeAudioStreamService.playbackUri(id, effectiveUrl))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(
                com.example.data.remote.YouTubeAudioStreamService.playbackUri(id, effectiveUrl))).build())
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
                        putBoolean("recommendation_only", recommendationOnly)
                        putLong("jio_start_deadline", if (id == _uiState.value.currentTrack?.id) initialJioDeadlineMs else 0L)
                        putLong("duration", durationMs)
                        putBoolean("isLiked", isLiked)
                        putBoolean("isLossless", isLossless)
                        putString("singers", singers); putString("writer", writer)
                        putString("language", language); putString("year", year); putString("isrc", isrc)
                    })
                    .build()
            )
            .build()
        return com.example.service.NetworkAudioQualityPolicy.constrain(mediaItem,
            NetworkQualityMonitor.getInstance(context).currentMaxBitrateBps)
    }
}
