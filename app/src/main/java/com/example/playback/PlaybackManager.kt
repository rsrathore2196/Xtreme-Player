package com.example.playback

import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.AppDispatchers
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.snapshots.SnapshotStateList

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
import com.example.data.model.queueIdentity
import com.example.data.local.ListeningEventDao
import com.example.data.local.ListeningEventEntity
import com.example.data.local.MusicDatabase
import com.example.data.local.TrackEntity
import com.example.data.model.toMediaItem
import com.example.data.remote.MusicDataSource
import com.example.service.MusicService
import com.example.service.PlaybackSessionStore
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/** Immutable cursor: advance only after its songs have entered the Media3 queue. */
data class CollectionPlaybackContinuation(
    val page: Int,
    val continueWith: ((com.example.data.repository.CatalogPage) -> CollectionPlaybackContinuation?)? = null,
    val fetch: suspend (Int) -> com.example.data.repository.CatalogPage
) {
    /** Opaque provider tokens advance only after the returned songs enter the queue. */
    internal fun after(result: com.example.data.repository.CatalogPage): CollectionPlaybackContinuation? {
        val next = result.nextPage?.takeIf { it > page } ?: return null
        return if (continueWith != null) continueWith.invoke(result) else copy(page = next)
    }
}

/** Accept a real seek position; reject a transient zero reported while the new source initializes. */
internal fun losslessRecoveryPosition(resumePositionMs: Long, reportedPositionMs: Long, activated: Boolean): Long {
    val expected = resumePositionMs.coerceAtLeast(0L)
    if (reportedPositionMs < 0L) return expected
    val reported = reportedPositionMs.coerceAtLeast(0L)
    if (reported == 0L && expected > 2_000L) return expected
    return if (activated || kotlin.math.abs(reported - expected) <= 2_000L) reported else expected
}

/** A blank voice request means resume, even if a previously published UI state is stale. */
internal fun resumeMediaSearchPlayback(player: Player) {
    if (player.mediaItemCount == 0) return
    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) player.prepare()
    player.play()
}

internal suspend fun <T> resolveWhileConnecting(connect: suspend () -> Unit, resolve: suspend () -> T): T =
    kotlinx.coroutines.coroutineScope {
        val connection = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { connect() }
        val result = resolve()
        connection.await()
        result
    }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackManager(
    private val context: Context,
    var repository: com.example.data.repository.MusicRepository? = null
) {
    private val TAG = "PlaybackManager"

    private val scope = CoroutineScope(AppDispatchers.Main + SupervisorJob())
    private var networkQualityMonitor: NetworkQualityMonitor? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var connectionGeneration = 0L
    private var controller: MediaController? = null
        set(value) {
            field = value
            com.example.service.SabrSessionRegistry.controllerHandlesRecovery = value?.let { connected ->
                { connected.applicationLooper == android.os.Looper.myLooper() && connected.isConnected }
            }
            com.example.service.SabrSessionRegistry.requestSourceRecovery = value?.let { connected ->
                { id: String, uri: String ->
                    if (connected.applicationLooper != android.os.Looper.myLooper() || !connected.isConnected ||
                        connected.currentMediaItem?.mediaId != id || connected.currentMediaItem?.playbackUri()?.toString() != uri) false
                    else {
                        if (youtubeQualityJob?.isActive != true) handlePlaybackFailure(
                            androidx.media3.common.PlaybackException("Audio startup timed out", null,
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
                            fromWatchdog = true)
                        true
                    }
                }
            }
        }
    private var controllerReady = CompletableDeferred<MediaController>()
    private val sessionRestored = CompletableDeferred<Unit>()
    private var pendingRestoredSession: PlaybackSessionStore.Snapshot? = null
    private var progressTickerJob: Job? = null
    private var recoveryJob: Job? = null
    private var youtubeQualityJob: Job? = null
    private var losslessDiscoveryJob: Job? = null
    private var losslessDiscoveryKey: String? = null
    private var appliedSourcePolicyKey: String? = null
    private var appliedQualityCeiling: AudioQualityCeiling? = null
    private var appliedEnhancer = false
    private var appliedDolbyAtmos = false
    private var appliedSourceRequestRevision = 0L
    @Volatile private var enhancerOrigin: SourceTransitionOrigin? = null
    private var pendingPolicyRestart: (() -> Unit)? = null
    private var losslessRequestEpoch = 0L
    private var losslessTransitionId: String? = null
    private data class LosslessFallback(val generation: Long, val original: MusicTrack,
        val upgraded: MusicTrack, var resumePositionMs: Long, val requestEpoch: Long,
        var activated: Boolean = false,
        var audioConfirmed: Boolean = false, var confirmedAudio: Bundle? = null,
        val expectedLossless: Boolean = true, val expectedOpus: Boolean = false, val policyKey: String? = null,
        val originalUsable: Boolean = true, val previousOrigin: SourceTransitionOrigin? = null,
        val targetOrigin: SourceTransitionOrigin? = null) {
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

    inner class SessionMemoryTracker {
        private val sessionTracks = java.util.concurrent.CopyOnWriteArrayList<MusicTrack>()
        private val sessionPlayedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun getSessionTracks(): List<MusicTrack> = sessionTracks.toList()
        fun recordTrackPlayed(track: MusicTrack) {
            if (sessionPlayedIds.add(track.id)) {
                sessionTracks.add(track)
            }
        }
        fun clear() {
            sessionTracks.clear()
            sessionPlayedIds.clear()
        }
    }
    val recommendationEngine = SessionMemoryTracker()
    @Volatile private var candidateCatalogPool: List<MusicTrack> = emptyList()
    private val candidateDispatcher = AppDispatchers.CpuBounded
    private val candidateUpdates = kotlinx.coroutines.sync.Mutex()
    private val candidateGeneration = java.util.concurrent.atomic.AtomicLong()
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
    private var selectionStartupTrace = 0L
    /** Read only on the player/application looper, alongside selection commands. */
    internal val currentSelectionRevision: Long get() = selectionGeneration
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
    private val positionClock = PlaybackPositionClock()
    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _trackDuration = MutableStateFlow(0L)
    val trackDuration: StateFlow<Long> = _trackDuration.asStateFlow()

    val currentPlaybackPositionMs: Long
        get() = if (pendingSelectionId != null) 0L else positionClock.position(_playbackPosition.value, _trackDuration.value)

    private var queueRevision = 0L
    private var snapshotRevision = -1L
    private var retainedQueueSnapshot: List<MusicTrack> = emptyList()
    private var upcomingRevision = -1L
    private var upcomingIndex = -1
    private var retainedUpcoming: List<MusicTrack> = emptyList()
    // SnapshotStateList.toList() is an O(1), immutable persistent snapshot. Main
    // can patch a row without copying a large playlist or mutating published lists.
    private var activeQueue = mutableStateListOf<MusicTrack>()
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
    private var collectionRadioStarted = false
    private var pendingCollectionRadio: List<MusicTrack> = emptyList()
    private var collectionContinuation: CollectionPlaybackContinuation? = null

    init {
        scope.launch {
            val monitor = withContext(AppDispatchers.Io) { NetworkQualityMonitor.getInstance(context) }
            networkQualityMonitor = monitor
            monitor.sourcePolicy.collect { policy ->
                if (policy.ready) updateLosslessPolicy(policy.ceiling)
                reconcileSourcePolicy(policy)
            }
        }
        // EQ remains renderer-side DSP. Provider resolution is selection/policy driven,
        // never an effect edit or automatic background Archive upgrade.
        SleepTimerManager.addExpirationListener(sleepExpirationListener)
        scope.launch {
            val startup = com.example.performance.BackgroundWorkCoordinator
            startup.startupTask(com.example.performance.StartupTask.PLAYER_SESSION,
                com.example.performance.StartupStatus.RUNNING)
            try {
                val (saved, tracks) = startup.startup(
                    com.example.performance.BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI,
                    urgent = { MusicService.liveSession.value }, boundToInitialPresentation = false,
                    label = "Player session preparation") {
                    val snapshot = PlaybackSessionStore.restore(context)
                    snapshot to withContext(AppDispatchers.CpuBounded) { snapshot?.items?.map(::controllerTrack).orEmpty().toMutableStateList() }
                }
                // A tap or an already-running service always wins over the disk snapshot.
                if (saved != null && tracks.isNotEmpty() && selectionGeneration == 0L &&
                    (controller == null || controller?.mediaItemCount == 0)) {
                    pendingRestoredSession = saved
                    isCurrentSessionExplicitPlaylist = saved.items[saved.index].mediaMetadata.extras?.getBoolean("queue_collection") == true
                    activeQueue = tracks
                    val current = tracks[saved.index]
                    positionClock.sample(saved.positionMs, false)
                    _playbackPosition.value = saved.positionMs
                    _trackDuration.value = current.durationMs
                    _uiState.update { it.copy(currentTrack = current, queue = queueSnapshot(), currentIndex = saved.index,
                        isPlaying = false, isLoading = false, isFavorite = current.isLiked,
                        currentPositionMs = saved.positionMs, durationMs = current.durationMs,
                        qualityBadge = current.qualityBadge.ifBlank { it.selectedQuality.badge }) }
                }
                startup.startupTask(com.example.performance.StartupTask.PLAYER_SESSION,
                    com.example.performance.StartupStatus.READY)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Saved player metadata unavailable", e)
                startup.startupTask(com.example.performance.StartupTask.PLAYER_SESSION,
                    com.example.performance.StartupStatus.UNAVAILABLE)
            } finally {
                sessionRestored.complete(Unit)
                com.example.performance.BackgroundWorkCoordinator.playbackSessionRestored()
            }
        }
        scope.launch {
            sessionRestored.await()
            val startup = com.example.performance.BackgroundWorkCoordinator
            startup.startupTask(com.example.performance.StartupTask.PLAYER_CONNECTION,
                com.example.performance.StartupStatus.RUNNING)
            // A paused/no-session restore needs metadata only. Never start a
            // service or prepare audio merely to finish the startup popup.
            val connected = if (!MusicService.liveSession.value) true else {
                ensureMediaControllerConnected()
                try { withTimeoutOrNull(8_000L) { controllerReady.await(); true } == true }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            }
            startup.startupTask(com.example.performance.StartupTask.PLAYER_CONNECTION,
                if (connected) com.example.performance.StartupStatus.READY
                else if (_uiState.value.currentTrack != null) com.example.performance.StartupStatus.FALLBACK_READY
                else com.example.performance.StartupStatus.UNAVAILABLE)
        }
        scope.launch {
            // A service started by a headset/Auto is already paying initialization
            // costs. Observe and reconnect immediately instead of waiting for idle.
            MusicService.liveSession.collect { alive -> if (alive) ensureMediaControllerConnected() }
        }
        // A restored paused queue is metadata-only until an explicit player
        // command. Do not cold-create MusicService later just to prebind it.
        // liveSession above reconnects active/external playback immediately;
        // user commands below still use ensureMediaControllerConnected().
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

    /** Source changes are resolved explicitly by reconcileSourcePolicy, never by a playback timer. */
    private fun updateLosslessPolicy(ceiling: AudioQualityCeiling) {
        val quality = ceiling.audioQuality
        _uiState.update { if (it.selectedQuality == quality) it else it.copy(selectedQuality = quality) }
    }

    /** Cancel pending preparation. Active policy reconciliation owns any required downgrade. */
    private fun stopPendingLosslessUpgrade() {
        losslessDiscoveryJob?.cancel()
        losslessDiscoveryJob = null
        ++losslessRequestEpoch
    }

    /** Discovery owns no player mutations. The existing transition owner commits a verified result. */
    private fun scheduleLosslessDiscovery(policy: PlaybackSourcePolicy) {
        val player = controller ?: return
        val track = _uiState.value.currentTrack ?: return
        if (!policy.allowsArchive || policy.returnsToYouTube(track.id) || track.isLossless || !player.isPlaying ||
            pendingSelectionId != null || youtubeQualityJob?.isActive == true || recoveryJob?.isActive == true ||
            losslessFallback?.let { !it.activated } == true) return
        val uri = player.currentMediaItem?.playbackUri()?.toString() ?: return
        if (player.currentMediaItem?.mediaId != track.id || Uri.parse(uri).scheme in setOf("file", "content")) return
        val key = "${track.id}:$uri:${policy.key}"
        if (losslessDiscoveryKey == key) return
        losslessDiscoveryJob?.cancel()
        losslessDiscoveryKey = key
        val generation = selectionGeneration
        val epoch = losslessRequestEpoch
        val repo = repository ?: return
        val recording = if (track.durationMs <= 0L && player.duration > 0L) track.copy(durationMs = player.duration) else track
        losslessDiscoveryJob = scope.launch {
            try {
                val candidate = com.example.performance.BackgroundWorkCoordinator.startup(
                    com.example.performance.BackgroundWorkCoordinator.StartupPriority.DEFERRED, requireForeground = false,
                    label = "Lossless recording discovery") { repo.findLosslessUpgrade(recording, automatic = true) }
                if (candidate == null || epoch != losslessRequestEpoch || !ownsSelection(track.id, generation) ||
                    !player.isPlaying || recoveryJob?.isActive == true || youtubeQualityJob?.isActive == true ||
                    player.currentMediaItem?.playbackUri()?.toString() != uri ||
                    networkQualityMonitor?.sourcePolicy?.value?.key != policy.key) return@launch
                // Release discovery's admission before the prebuffer requests its own slot.
                losslessDiscoveryJob = null
                if (policy.allowsAutomaticReplacement(recording, candidate))
                    reconcileSourcePolicy(policy, force = true, preparedSource = candidate, automatic = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                LosslessDiagnostics.record(track.id, LosslessDiagnostics.Reason.PREBUFFER_FAILED, "background-discovery")
            }
        }
    }

    private fun policyAllows(track: MusicTrack, policy: PlaybackSourcePolicy): Boolean {
        return policy.acceptsRemoteTrack(track)
    }

    /** Same selection/epoch/fallback owner as Archive upgrades; never a second player. */
    private fun reconcileSourcePolicy(policy: PlaybackSourcePolicy, force: Boolean = false, failedUrl: String? = null,
        preparedSource: MusicTrack? = null, automatic: Boolean = false) {
        if (!policy.ready) return
        if (!force && appliedSourcePolicyKey == policy.key) return
        val onlyAtmosChanged = policy.onlyAtmosChanged(appliedSourcePolicyKey)
        val qualityRaised = appliedQualityCeiling?.let { policy.ceiling.maxBitrateBps > it.maxBitrateBps } == true
        val atmosChanged = appliedDolbyAtmos != policy.allowsDolbyAtmos
        appliedDolbyAtmos = policy.allowsDolbyAtmos
        appliedQualityCeiling = policy.ceiling
        val enhancerChangedToOn = policy.enhancer && !appliedEnhancer
        appliedEnhancer = policy.enhancer
        appliedSourcePolicyKey = policy.key
        youtubeQualityJob?.cancel()
        losslessDiscoveryJob?.cancel()
        losslessDiscoveryJob = null
        if (pendingSelectionId != null && playResolutionJob?.isActive == true) {
            val restart = pendingPolicyRestart ?: return
            playResolutionJob?.cancel()
            pendingSelectionId = null
            lastPlayRequestTimeMs = 0L
            restart()
            return
        }
        val player = controller ?: return
        val displayed = _uiState.value.currentTrack ?: return
        val explicitRequest = policy.requestTrackId == displayed.id && policy.requestRevision != appliedSourceRequestRevision
        if (explicitRequest) appliedSourceRequestRevision = policy.requestRevision
        val enhancerTurnedOn = policy.enhancer && (enhancerChangedToOn || explicitRequest)
        // A rapid second toggle can arrive while the previous source is audible
        // but still awaiting its Format acknowledgement. Resolve from the actual
        // installed transport without rolling it back (or pausing it) first.
        val original = (losslessFallback?.takeIf {
            !it.activated && it.original.id == displayed.id &&
                it.upgradedUrl == player.currentMediaItem?.playbackUri()?.toString()
        }?.upgraded ?: displayed).let { track ->
            // Catalog discovery can omit duration. The active decoder's duration
            // provides recording evidence for an explicit source enhancement.
            if (track.durationMs <= 0L && player.duration > 0L) track.copy(durationMs = player.duration) else track
        }
        if (player.currentMediaItem?.mediaId != original.id || Uri.parse(original.audioUrl).scheme in setOf("file", "content")) return
        recoveryJob?.cancel()
        val remembered = enhancerOrigin?.takeIf { it.track.id == original.id }
            ?: SourceTransitionOrigin.read(player.currentMediaItem, original)
        val origin = remembered ?: if (enhancerTurnedOn) SourceTransitionOrigin.capture(original, policy) else null
        val returnToYouTube = policy.shouldReturnToYouTube(original, origin?.sourceId)
        val restoring = !policy.enhancer && (returnToYouTube || !onlyAtmosChanged && origin != null)
        val sameSource = if (onlyAtmosChanged && !returnToYouTube)
            SourceTransitionOrigin.sourceId(original.id, Uri.parse(original.audioUrl), policy)
                ?.takeIf { id -> policy.playbackSources.any { it.id == id } } else null
        if (!restoring && !atmosChanged && policy.maximum && failedUrl == null && original.isLossless && policyAllows(original, policy) && policy.isPreferred(original)) return
        stopPendingLosslessUpgrade()
        if (losslessFallback?.let { it.activated || !it.expectedLossless } == true) losslessFallback = null
        val generation = selectionGeneration
        val epoch = ++losslessRequestEpoch
        val originalUri = player.currentMediaItem?.playbackUri()?.toString()
        val repo = repository ?: return
        if (restoring && !returnToYouTube && origin?.track?.audioUrl == originalUri && policyAllows(original, policy)) {
            enhancerOrigin = null
            val item = player.currentMediaItem ?: return
            val cleared = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                SourceTransitionOrigin.write(this, null); putString("source_policy_key", policy.key)
            }
            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon().setExtras(cleared).build()).build())
            AudioSignalPath.sourceBookmark(original.id, originalUri.orEmpty(), null)
            return
        }
        fun owns() = epoch == losslessRequestEpoch && ownsSelection(original.id, generation) &&
            NetworkQualityMonitor.getInstance(context).sourcePolicy.value.key == policy.key &&
            player.currentMediaItem?.mediaId == original.id
        youtubeQualityJob = scope.launch {
            AudioSignalPath.sourceRequest(original.id, true, "controller:$epoch")
            try {
                if (!automatic) delay(180L) // Coalesce settings changes, never player/touch commands.
                if (!owns()) return@launch
                val isYoutube = com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(original.id, original.audioUrl)
                if (!restoring && !atmosChanged && !enhancerTurnedOn && !qualityRaised && failedUrl == null && policyAllows(original, policy) && policy.isPreferred(original) &&
                    isYoutube && !policy.maximum) return@launch
                val resolved = preparedSource ?: withContext(AppDispatchers.Io) { repo.resolvePlayableTrack(if (restoring && !returnToYouTube) origin!!.track else original,
                    forceRefresh = failedUrl != null, failedUrl = failedUrl,
                    preferredSourceId = sameSource ?: if (returnToYouTube) policy.sourceId(PlaybackSourceKind.YOUTUBE) else origin?.sourceId.takeIf { restoring }, manualUpgrade = enhancerTurnedOn) }
                if (!owns() || player.currentMediaItem?.playbackUri()?.toString() != originalUri) return@launch
                if (keepResolvedSource(originalUri, resolved.audioUrl, failedUrl)) {
                    if (restoring) {
                        enhancerOrigin = null
                        val item = player.currentMediaItem ?: return@launch
                        val saved = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                            SourceTransitionOrigin.write(this, null); putString("source_policy_key", policy.key)
                        }
                        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(
                            item.mediaMetadata.buildUpon().setExtras(saved).build()).build())
                        AudioSignalPath.sourceBookmark(original.id, originalUri.orEmpty(), null)
                    } else if (enhancerTurnedOn) {
                        LosslessDiagnostics.record(original.id, LosslessDiagnostics.Reason.NO_RESULTS, "manual-source-upgrade")
                    }
                    return@launch
                }
                val transport = original.copy(audioUrl = resolved.audioUrl, source = resolved.source,
                    bitrateKbps = resolved.bitrateKbps, qualityBadge = resolved.qualityBadge, isLossless = resolved.isLossless)
                val media = transport.toMediaItem(preparedPolicy = policy).forSourceTransition()
                val sameCode = withContext(AppDispatchers.CpuBounded) {
                    com.example.data.repository.RecordingIdentity.isrc(original.isrc)?.let {
                        it == com.example.data.repository.RecordingIdentity.isrc(resolved.isrc)
                    } == true
                }
                val adaptive = resolved.isLossless && LosslessStreamPrebuffer.isAdaptiveSource(media)
                val warmed = if (resolved.isLossless && !adaptive) LosslessStreamPrebuffer.prepare(context, media, original.durationMs,
                    currentPosition = { losslessRecoveryPosition(_playbackPosition.value, player.currentPosition, true) },
                    stillCurrent = ::owns, durationToleranceMs = if (sameCode) 8_000L else 5_000L,
                    allowUnknownDuration = sameCode, automatic = automatic) else null
                val prepared = if (adaptive) LosslessStreamPrebuffer.prepareAdaptiveSource(context, media, original.durationMs,
                    stillCurrent = ::owns, durationToleranceMs = if (sameCode) 8_000L else 5_000L, automatic = automatic,
                    currentPosition = { losslessRecoveryPosition(_playbackPosition.value, player.currentPosition, true) })
                    else if (resolved.isLossless) warmed?.covers(
                    losslessRecoveryPosition(_playbackPosition.value, player.currentPosition, true)) == true
                    else LosslessStreamPrebuffer.warmSource(context, media)
                if (!owns()) return@launch
                if (!prepared) {
                    if (resolved.isLossless) {
                        LosslessDiagnostics.prebufferRejected(original.id, warmed != null)
                        withContext(AppDispatchers.Io) { repo.rejectLosslessUpgrade(original, resolved.audioUrl) }
                    }
                    throw PlaybackDiagnostics.Failure(PlaybackDiagnostics.Reason.NO_FORMAT, PlaybackDiagnostics.Stage.PREBUFFER)
                }
                val index = player.currentMediaItemIndex
                if (index !in activeQueue.indices || player.currentMediaItem?.playbackUri()?.toString() != originalUri) return@launch
                val position = losslessRecoveryPosition(_playbackPosition.value, player.currentPosition, true)
                val resume = player.playWhenReady
                val replacement = original.copy(audioUrl = media.localConfiguration!!.uri.toString(), source = resolved.source,
                    qualityBadge = resolved.qualityBadge, bitrateKbps = resolved.bitrateKbps, isLossless = resolved.isLossless)
                val targetOrigin = origin.takeUnless { restoring || isYoutubeSource(replacement) }?.copy(policyKey = policy.upgradePolicyKey)
                losslessFallback = LosslessFallback(generation, original, replacement, position, epoch,
                    expectedLossless = resolved.isLossless, expectedOpus = isYoutubeSource(replacement) && resolved.qualityBadge.contains("Opus", true),
                    policyKey = policy.key, originalUsable = failedUrl == null,
                    previousOrigin = remembered, targetOrigin = targetOrigin)
                enhancerOrigin = origin
                losslessTransitionId = original.id
                val extras = Bundle(media.mediaMetadata.extras ?: Bundle()).apply {
                    putBoolean("audio_actual", false)
                    putBoolean("source_transition_pending", true)
                    putBoolean("lossless_upgrade_pending", resolved.isLossless)
                    putString("audio_policy_tier", policy.ceiling.audioQuality.name)
                    SourceTransitionOrigin.write(this, targetOrigin)
                }
                player.replaceMediaItem(index, media.buildUpon().setMediaMetadata(
                    media.mediaMetadata.buildUpon().setExtras(extras).build()).build())
                player.seekTo(index, position)
                player.prepare(); player.playWhenReady = resume
                val activated = withTimeoutOrNull(8_000L) {
                    while (owns() && player.currentMediaItem?.playbackUri()?.toString() == replacement.audioUrl) {
                        applyActualAudioProperties(player, player.sessionExtras.getBundle("active_audio"))
                        confirmLosslessActivation(player)
                        if (losslessFallback?.activated == true) return@withTimeoutOrNull true
                        if (player.playerError != null) break
                        delay(100L)
                    }
                    false
                } == true
                if (activated && restoring) enhancerOrigin = null
                if (!activated && owns() && player.playbackState == Player.STATE_READY &&
                    !player.playWhenReady && player.playerError == null) return@launch
                if (!activated && owns()) {
                    LosslessDiagnostics.record(original.id, LosslessDiagnostics.Reason.ACTIVATION_FAILED, "source-policy")
                    withContext(AppDispatchers.Io) {
                        PlaybackDiagnostics.record(original.id, policy,
                            policy.configuredSources.firstOrNull { it.id == SourceTransitionOrigin.sourceId(original.id, Uri.parse(replacement.audioUrl), policy) }?.kind,
                            PlaybackDiagnostics.Stage.ACTIVATION, PlaybackDiagnostics.Reason.TIMEOUT)
                        if (resolved.isLossless) repo.rejectLosslessUpgrade(original, resolved.audioUrl)
                    }
                    if (!owns()) return@launch
                    restoreLosslessFallback(player, original)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!owns()) return@launch
                if (LosslessDiagnostics.last(original.id)?.stage != "prebuffer")
                    LosslessDiagnostics.record(original.id, LosslessDiagnostics.Reason.POLICY_SOURCE_UNAVAILABLE, "source-policy")
                if (losslessFallback?.requestEpoch == epoch) restoreLosslessFallback(player, original)
                else if (failedUrl != null || !policyAllows(original, policy)) {
                    player.pause(); player.stop()
                    showPlaybackFailure((error as? TrackUnavailableException)?.message
                        ?: "This recording is unavailable at the selected streaming quality.")
                }
            } finally {
                AudioSignalPath.sourceRequest(original.id, false, "controller:$epoch")
                // A fresh discovery uses the same owner, and only enters after the current job releases it.
                if (owns() && preparedSource == null) {
                    youtubeQualityJob = null
                    scheduleLosslessDiscovery(policy)
                }
            }
        }
    }

    private fun isYoutubeSource(track: MusicTrack) =
        com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl)

    private fun initMediaController() {
        if (controller != null || controllerFuture != null) return
        if (!controllerReady.isCompleted) controllerReady.completeExceptionally(
            java.io.IOException("Player connection was replaced"))
        controllerReady = CompletableDeferred()
        val connectionTrace = com.example.performance.BackgroundWorkCoordinator.trace("MediaController connect")
        try {
            val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
            val connection = ++connectionGeneration
            val readiness = controllerReady
            val future = MediaController.Builder(context, sessionToken)
                .setListener(object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        if (connection != connectionGeneration || this@PlaybackManager.controller !== controller) return
                        applyJioFallbackStatus(controller, extras.getBundle("jio_fallback_status"))
                        applyActualAudioProperties(controller, extras.getBundle("active_audio"))
                    }
                }).buildAsync()
            controllerFuture = future
            future.addListener({
                scope.launch {
                    try {
                        val connected = completedConnectionValue(future, controllerFuture, connection == connectionGeneration)
                            ?: return@launch
                        if (connection != connectionGeneration || controllerFuture !== future) {
                            MediaController.releaseFuture(future); return@launch
                        }
                        controller = connected
                        if (pendingSelectionId != null) {
                            pendingRestoredSession = null
                            connected.pause()
                            connected.stop()
                            connected.clearMediaItems()
                        } else if (connected.mediaItemCount == 0) {
                            sessionRestored.await()
                            hydrateRestoredSession(connected, connection)
                        }
                        if (connection != connectionGeneration || controllerFuture !== future) return@launch
                        setupPlayerListener()
                        applyJioFallbackStatus(connected, connected.sessionExtras.getBundle("jio_fallback_status"))
                        applyActualAudioProperties(connected, connected.sessionExtras.getBundle("active_audio"))
                        readiness.complete(connected)
                        networkQualityMonitor?.sourcePolicy?.value?.let { policy ->
                            val current = _uiState.value.currentTrack
                            if (current != null && !policyAllows(current, policy)) reconcileSourcePolicy(policy, force = true)
                        }
                        Log.i(TAG, "MediaController initialized successfully")
                    } catch (e: Exception) {
                        // A canceled connection future is a recoverable failure;
                        // cancellation of this coroutine still propagates immediately.
                        currentCoroutineContext().ensureActive()
                        MediaController.releaseFuture(future)
                        if (connection != connectionGeneration || controllerFuture !== future) return@launch
                        controllerFuture = null
                        controller = null
                        readiness.completeExceptionally(e)
                        Log.e(TAG, "Failed to initialize MediaController: ${e.message}", e)
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Player initialization failed") }
                    } finally { connectionTrace.close() }
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            connectionTrace.close()
            controllerFuture = null
            controllerReady.completeExceptionally(e)
            Log.e(TAG, "Error setting up MediaController: ${e.message}", e)
            _uiState.update { it.copy(errorMessage = "Failed to initialize player: ${e.message}") }
        }
    }

    private suspend fun hydrateRestoredSession(player: MediaController, connection: Long) {
        val saved = pendingRestoredSession ?: return
        val generation = selectionGeneration
        withContext(AppDispatchers.Io) { NetworkQualityMonitor.getInstance(context).snapshot() }
        while (connection == connectionGeneration && controller === player && pendingSelectionId == null && player.mediaItemCount == 0) {
            val revision = queueRevision
            val quality = _uiState.value.selectedQuality
            val tracks = queueSnapshot()
            if (tracks.isEmpty()) return
            val prepared = withContext(AppDispatchers.CpuBounded) {
                val items = tracks.map { it.toMediaItem(quality) }
                items to items.map(::controllerTrack).toMutableStateList()
            }
            if (connection != connectionGeneration || controller !== player || selectionGeneration != generation || pendingSelectionId != null || player.mediaItemCount > 0) return
            // A settings/favorite edit during hydration must not be lost or apply the old bitrate.
            if (revision != queueRevision || quality != _uiState.value.selectedQuality) continue
            pendingRestoredSession = null
            activeQueue = prepared.second
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
            val connection = connectionGeneration
            val readiness = controllerReady
            scope.launch {
                try {
                    val player = withTimeoutOrNull(8_000L) { readiness.await() } ?: return@launch
                    if (connection != connectionGeneration || controller !== player) return@launch
                    if (!guardSelection || generation == selectionGeneration) onReady(player)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (connection == connectionGeneration && generation == selectionGeneration)
                        showPlaybackFailure("Unable to connect to the player. Please try again.")
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
            primaryArtists = extras?.getStringArrayList("primaryArtists")?.toList().orEmpty(),
            playlistEntryId = extras?.getLong("playlist_entry_id", 0L) ?: 0L,
            language = extras?.getString("language") ?: "Unknown", year = extras?.getString("year").orEmpty(),
            isrc = extras?.getString("isrc").orEmpty(),
            source = source.ifBlank { if (item.mediaId.startsWith("yt_")) "Extended Stream" else "HD Stream" },
            isLossless = extras?.getBoolean("isLossless", false) == true,
            recommendationOnly = extras?.getBoolean("recommendation_only", false) == true)
    }

    private var queueSyncJob: Job? = null
    private var queueSyncEpoch = 0L

    private suspend fun synchronizeControllerQueue(player: MediaController): Boolean {
        val revision = queueRevision
        val generation = selectionGeneration
        // Media3 getters are confined to its application looper. Only capture the
        // immutable MediaItems here; metadata conversion/list construction is CPU work.
        val timeline = player.currentTimeline
        // Main can append/patch its mutable queue while this CPU job is running.
        // The retained immutable snapshot is safe to iterate across that handoff.
        val previous = queueSnapshot()
        val prepared = withContext(AppDispatchers.CpuBounded) {
            val window = androidx.media3.common.Timeline.Window()
            val items = List(timeline.windowCount) { index ->
                currentCoroutineContext().ensureActive()
                timeline.getWindow(index, window).mediaItem
            }
            val known = previous.associateBy { it.id }
            val revised = items.map { item ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                known[item.mediaId] ?: controllerTrack(item)
            }.toMutableStateList()
            revised.takeUnless { it == previous }
        }
        if (controller !== player || generation != selectionGeneration || revision != queueRevision) return false
        if (timeline !== player.currentTimeline) {
            requestQueueSynchronization(player)
            return false
        }
        if (prepared == null) return false
        activeQueue = prepared
        return true
    }

    private fun requestQueueSynchronization(player: MediaController) {
        val epoch = ++queueSyncEpoch
        queueSyncJob?.cancel()
        queueSyncJob = scope.launch {
            val changed = synchronizeControllerQueue(player)
            if (epoch == queueSyncEpoch && controller === player &&
                (changed || _uiState.value.currentIndex != player.currentMediaItemIndex.coerceAtLeast(0))) publishQueueState()
        }
    }

    private suspend fun setupPlayerListener() {
        val player = controller
        if (player == null) {
            Log.e(TAG, "Cannot setup listener: player is null")
            return
        }

        synchronizeControllerQueue(player)
        player.currentMediaItem?.let { item ->
            isCurrentSessionExplicitPlaylist = item.mediaMetadata.extras?.getBoolean("queue_collection") == true
            val track = activeQueue.getOrNull(player.currentMediaItemIndex) ?: controllerTrack(item)
            recoveryGuard.select(track.id)
            _uiState.update { it.copy(currentTrack = track, queue = queueSnapshot(), currentIndex = player.currentMediaItemIndex,
                isFavorite = track.isLiked,
                isPlaying = player.isPlaying, isLoading = player.playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration.takeIf { duration -> duration > 0L } ?: track.durationMs) }
            updateProgressValues()
            publishListeningState(player, newPlay = true)
            if (player.isPlaying) {
                startProgressTicker()
            }
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
                rememberLosslessPosition(controller)
                if (queueContentsChanged) {
                    queueContentsChanged = false
                    requestQueueSynchronization(controller)
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
                    // Genuinely playing: persist track into Room DB listening history immediately
                    val current = _uiState.value.currentTrack
                    networkQualityMonitor?.sourcePolicy?.value?.let { policy ->
                        if (current != null && losslessFallback == null && !policyAllows(current, policy))
                            reconcileSourcePolicy(policy, force = true)
                        else scheduleLosslessDiscovery(policy)
                    }
                    if (current != null && losslessFallback == null) {
                        scope.launch(AppDispatchers.Io) {
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
                    if (losslessFallback == null && youtubeQualityJob?.isActive != true) {
                        ++losslessRequestEpoch
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
                updateProgressValues()
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
                        scope.launch(AppDispatchers.Io) {
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
                        } else if (isCurrentSessionExplicitPlaylist && collectionContinuation != null ||
                            _uiState.value.isAutoplayEnabled) {
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
                    val oldTrack = _uiState.value.currentTrack
                    val sameOccurrence = oldTrack?.playlistEntryId == (mediaItem.mediaMetadata.extras?.getLong("playlist_entry_id", 0L) ?: 0L)
                    if (!sameOccurrence) selectionGeneration++
                    if (oldTrack?.id != trackId || !sameOccurrence || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                        recoveryJob?.cancel()
                        recoveryJob = null
                        recoveryGuard.select(trackId, newRequest = true)
                    } else {
                        recoveryGuard.select(trackId)
                    }
                    val serviceSourceTransition = sameOccurrence && oldTrack?.id == trackId && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                        mediaItem.mediaMetadata.extras?.getBoolean("jio_youtube_fallback") == true
                    val sourceOnlyTransition = sameOccurrence && oldTrack?.id == trackId && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                        (serviceSourceTransition || losslessTransitionId == trackId || losslessFallback?.let {
                            it.original.id == trackId && it.upgradedUrl == mediaItem.localConfiguration?.uri?.toString()
                        } == true)
                    if (oldTrack?.id != trackId || !sameOccurrence) {
                        youtubeQualityJob?.cancel()
                        cancelLosslessUpgrade()
                    }
                    if (sourceOnlyTransition) losslessTransitionId = null
                    // Keep the original quality and queue snapshot until this replacement
                    // is genuinely ready. A source change is not a new listening session.
                    if (sourceOnlyTransition && losslessFallback?.activated == false) return
                    val newListeningPlay = _listeningState.value.track?.id != trackId || !sameOccurrence ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    val elapsed = (System.currentTimeMillis() - currentTrackStartTimeMs).coerceAtLeast(0L)
                    val wasMeaningful = hasRecordedMeaningfulPlay
                    val shouldRecordPlayback = player.isPlaying || player.playWhenReady
                    if (activeQueue.size != player.mediaItemCount || activeQueue.getOrNull(player.currentMediaItemIndex)?.id != trackId)
                        requestQueueSynchronization(player)
                    val queuedTrack = activeQueue.getOrNull(player.currentMediaItemIndex)?.takeIf { it.id == trackId }
                    val policyTransition = mediaItem.mediaMetadata.extras?.containsKey("audio_policy_tier") == true &&
                        queuedTrack?.audioUrl != mediaItem.localConfiguration?.uri?.toString()
                    val matchedTrack = if (serviceSourceTransition || policyTransition)
                        controllerTrack(mediaItem).copy(isLiked = queuedTrack?.isLiked ?: oldTrack?.isLiked ?: false)
                        else queuedTrack ?: controllerTrack(mediaItem)
                    val policy = networkQualityMonitor?.sourcePolicy?.value
                    if (!sourceOnlyTransition && policy != null &&
                        mediaItem.mediaMetadata.extras?.getString("source_policy_key") != policy.key) scope.launch {
                        kotlinx.coroutines.yield()
                        if (player.currentMediaItem?.mediaId == trackId && pendingSelectionId == null)
                            reconcileSourcePolicy(policy, force = true)
                    }
                    if (serviceSourceTransition || policyTransition) replaceQueueTrack(player.currentMediaItemIndex, matchedTrack)
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
                                withContext(AppDispatchers.Database) { listeningEventDao.insertListeningEvent(skipEvent) }
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
                        withContext(AppDispatchers.Database) { listeningEventDao.insertListeningEvent(startEvent) }

                        // If playback is actively running or set to play immediately, persist lastPlayedAt
                        if (shouldRecordPlayback) {
                            try {
                                val now = System.currentTimeMillis()
                                val entity = TrackEntity.fromMusicTrack(matchedTrack, lastPlayedAt = now)
                                withContext(AppDispatchers.Database) { database.musicDao().recordTrackPlayed(entity, now) }
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
                    newPosition.mediaItem?.mediaId == losslessFallback?.original?.id)
                    losslessFallback?.resumePositionMs = newPosition.positionMs.coerceAtLeast(0L)
                if (reason == Player.DISCONTINUITY_REASON_SEEK && losslessFallback?.activated != false &&
                    losslessTransitionId == null &&
                    oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId &&
                    oldPosition.positionMs > 10_000L && newPosition.positionMs < 1_000L)
                    listeningPlayRestart = true
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                handlePlaybackFailure(error)
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
                val index = player.currentMediaItemIndex.takeIf { activeQueue.getOrNull(it)?.queueIdentity == current.queueIdentity }
                    ?: activeQueue.indexOfFirst { it.queueIdentity == current.queueIdentity }
                replaceQueueTrack(index, updated)
                _uiState.update { it.copy(currentTrack = updated, qualityBadge = updated.qualityBadge,
                    queue = queueSnapshot()) }
            }
        })
    }

    /** Native errors and the service watchdog share one bounded, generation-owned recovery path. */
    internal fun handlePlaybackFailure(error: androidx.media3.common.PlaybackException, fromWatchdog: Boolean = false) {
        losslessDiscoveryJob?.cancel()
        losslessDiscoveryJob = null
        losslessDiscoveryKey = null
        val failedItem = controller?.currentMediaItem
        val failedAddress = failedItem?.playbackUri()?.toString().orEmpty()
        val failedPolicy = networkQualityMonitor?.sourcePolicy?.value
        if (failedItem != null && failedPolicy != null) scope.launch(AppDispatchers.Io) {
            PlaybackDiagnostics.playerFailure(failedItem.mediaId, failedPolicy, failedAddress, error)
        }
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
        // A play request is not audible playback. Freeze the existing anchor while recovery prepares.
        stopProgressTicker()
        positionClock.sample(_playbackPosition.value, false)
        _uiState.update { it.copy(isPlaying = false) }
        if (current.isLossless && losslessFallback == null && !error.isAudioPipelineFailure() &&
            Uri.parse(current.audioUrl).scheme !in setOf("file", "content")) {
            val reason = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> LosslessDiagnostics.Reason.DECODER_UNSUPPORTED
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> LosslessDiagnostics.Reason.NETWORK_TIMEOUT
                else -> LosslessDiagnostics.Reason.ACTIVATION_FAILED
            }
            scope.launch(AppDispatchers.Io) {
                LosslessDiagnostics.record(current.id, reason, "player-error")
                repository?.rejectLosslessUpgrade(current, current.audioUrl)
            }
        }
        if (error.isAudioPipelineFailure()) {
            // Re-extracting another provider cannot repair an AudioTrack
            // or DSP failure and would unnecessarily change this recording.
            recoveryJob?.cancel()
            recoveryJob = null
            if (error.canRetryAudioOutput() && MusicService.recoveringAudioTrackId == current.id) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                return
            }
            showPlaybackFailure("Audio output failed. Please try playing again.")
            return
        }
        if (!fromWatchdog && com.example.service.JioSaavnPlaybackFallback.handles(player.currentMediaItem)) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            return
        }
        val failedFallback = losslessFallback
        if (failedFallback != null && failedFallback.upgradedUrl == player.currentMediaItem?.playbackUri()?.toString()) {
            val reason = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> LosslessDiagnostics.Reason.DECODER_UNSUPPORTED
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> LosslessDiagnostics.Reason.NETWORK_TIMEOUT
                else -> LosslessDiagnostics.Reason.ACTIVATION_FAILED
            }
            LosslessDiagnostics.record(failedFallback.original.id, reason, "player-error")
            youtubeQualityJob?.cancel()
            restoreLosslessFallback(player, current)
            if (failedFallback.expectedLossless) scope.launch(AppDispatchers.Io) {
                repository?.rejectLosslessUpgrade(failedFallback.original, failedFallback.upgradedUrl)
            }
            return
        }
        if (restoreLosslessFallback(player, current)) return
        val policy = NetworkQualityMonitor.getInstance(context).sourcePolicy.value
        if (!policyAllows(current, policy)) {
            if (youtubeQualityJob?.isActive != true) reconcileSourcePolicy(policy, force = true)
            return
        }
        val repo = repository
        // Duplicate error callbacks must join, not spend another stage or
        // cancel an in-flight extraction for this same recording.
        if (recoveryJob?.isActive == true) return
        val failedUri = player.currentMediaItem?.playbackUri()
        val youtubeUri = failedUri?.toString()?.let(com.example.data.remote.YouTubeAudioStreamService::isPlaybackUri) == true ||
            com.example.data.remote.YouTubeAudioStreamService.isYouTubeAudio(current.id, current.audioUrl)
        val sabrReplacement = failedUri?.toString()?.let(com.example.service.SabrSessionRegistry::takeReplacement)
        val initialTransportSelection = sabrReplacement != null && failedUri?.scheme == "xtreme-youtube" &&
            failedUri.getQueryParameter("itag") == null
        val token = recoveryGuard.claim(current.id, initialSelection = initialTransportSelection)
        if (repo == null || token == null) {
            if (fromWatchdog) { player.pause(); player.stop() }
            showPlaybackFailure("Unable to play '${current.title}'. Please try again.")
            return
        }
        val failedUrl = failedUri?.toString() ?: current.audioUrl
        if (!initialTransportSelection) recoveryGuard.reject(current.id, token, failedUrl)
        val transientFailure = !fromWatchdog && (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                ?.responseCode?.let { it in 500..599 } == true)
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val recoveryToken: Long = token
        recoveryJob = scope.launch {
            var activeToken: Long = recoveryToken
            var transport = sabrReplacement
            while (isActive && recoveryGuard.isCurrent(current.id, activeToken)) {
                val stage = recoveryGuard.stage(current.id, activeToken)
                try {
                    val retryCurrent = stage == 1 && transientFailure && !youtubeUri && transport == null
                    val blocked = recoveryGuard.rejectedUrls(current.id, activeToken)
                    val next = transport
                    val resolved = if (next != null) {
                        current.copy(
                            audioUrl = com.example.data.remote.YouTubeAudioStreamService.playbackUri("yt_${next.videoId}", next.url),
                            bitrateKbps = next.bitrateKbps, qualityBadge = next.qualityBadge, isLossless = false, source = "YouTube Music")
                    } else if (retryCurrent) {
                        delay(300L)
                        current
                    } else withContext(AppDispatchers.Io) {
                        repo.resolvePlayableTrack(current, forceRefresh = true, failedUrl = failedUrl,
                            currentSourceOnly = stage < 2, rejectedUrls = blocked)
                    }
                    if (!recoveryGuard.isCurrent(current.id, activeToken) ||
                        _uiState.value.currentTrack?.id != current.id ||
                        player.currentMediaItem?.mediaId != current.id) return@launch
                    val currentPolicy = NetworkQualityMonitor.getInstance(context).sourcePolicy.value
                    if (currentPolicy.key != policy.key || !policyAllows(resolved, currentPolicy)) return@launch
                    val replacement = resolved.toMediaItem(preparedPolicy = currentPolicy).forSourceTransition()
                    val address = replacement.localConfiguration?.uri?.toString().orEmpty()
                    if (!retryCurrent && !com.example.data.remote.YouTubeAudioStreamService.isPlaybackUri(address) &&
                        address in blocked) throw TrackUnavailableException(current.title)
                    val index = player.currentMediaItemIndex
                    val position = nativeProgress(player)?.positionMs ?: player.currentPosition.coerceAtLeast(0L)
                    val resume = player.playWhenReady
                    replaceQueueTrack(index, resolved)
                    player.replaceMediaItem(index, replacement)
                    player.seekTo(index, position)
                    player.prepare()
                    player.playWhenReady = resume
                    _uiState.update { it.copy(currentTrack = resolved, queue = queueSnapshot(),
                        isLoading = player.playbackState == Player.STATE_BUFFERING, isPlaying = player.isPlaying, errorMessage = null) }
                    updateProgressValues()
                    // A later native playback error claims the next stage.
                    // Initial transport selection does not reset an exhausted budget.
                    return@launch
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!recoveryGuard.isCurrent(current.id, activeToken)) return@launch
                    // Resolution itself can fail before Media3 emits another error.
                    // Advance here so the alternate provider still gets one chance.
                    val nextToken = recoveryGuard.claim(current.id)
                    if (nextToken != null) {
                        activeToken = nextToken
                        transport = null
                        continue
                    }
                    if (fromWatchdog) { player.pause(); player.stop() }
                    showPlaybackFailure(e.message ?: "Unable to resolve a playable stream.")
                    return@launch
                }
            }
        }
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
            "failed" -> showPlaybackFailure(status.getString("message") ?: "Playback is unavailable.")
        }
    }

    internal suspend fun continueAfterUnavailableSelection(track: MusicTrack, @Suppress("UNUSED_PARAMETER") queue: List<MusicTrack>,
        @Suppress("UNUSED_PARAMETER") explicit: Boolean, message: String?) {
        val generation = selectionGeneration
        if (!ownsSelection(track.id, generation)) return
        // Provider fallback must keep the selected recording and queue intact.
        showPlaybackFailure(message ?: "This recording is unavailable from the enabled sources. Please try again.")
    }

    private fun MediaItem.playbackUri(): Uri? = localConfiguration?.uri ?: requestMetadata.mediaUri

    private fun nativeProgress(player: MediaController): PlaybackPositionClock.ServiceSample? =
        MusicService.nativePlaybackProgress?.invoke()?.takeIf {
            it.trackId == player.currentMediaItem?.mediaId && it.uri == player.currentMediaItem?.playbackUri()?.toString()
        }

    private fun applyActualAudioProperties(player: MediaController, audio: Bundle?) {
        val extras = audio ?: return
        val current = _uiState.value.currentTrack ?: return
        if (!extras.getBoolean("audio_actual") || extras.getString("track_id") != current.id ||
            player.currentMediaItem?.mediaId != current.id) return
        val address = extras.getString("active_audio_uri") ?: return
        if (player.currentMediaItem?.playbackUri()?.toString() != address) return
        val fallback = losslessFallback
        val policy = NetworkQualityMonitor.getInstance(context).sourcePolicy.value
        val exceedsCap = !policy.acceptsStreamBitrate(extras.getInt("bitrate", 0), Uri.parse(address))
        val actualTrack = current.copy(audioUrl = address, qualityBadge = extras.getString("qualityBadge").orEmpty(),
            bitrateKbps = extras.getInt("bitrate", current.bitrateKbps))
        val wrongProvider = extras.getString("audio_codec").orEmpty().isNotBlank() && !policyAllows(actualTrack, policy)
        if (fallback == null && (extras.getBoolean("lossless_invalid") || exceedsCap || wrongProvider)) {
            LosslessDiagnostics.record(current.id, LosslessDiagnostics.Reason.SOURCE_VERIFICATION_FAILED, "active-format")
            if (youtubeQualityJob?.isActive != true) reconcileSourcePolicy(policy, force = true, failedUrl = address)
            return
        }
        if (fallback != null && fallback.upgradedUrl == address) {
            if (exceedsCap) {
                LosslessDiagnostics.record(current.id, LosslessDiagnostics.Reason.POLICY_SOURCE_UNAVAILABLE, "active-bitrate")
                restoreLosslessFallback(player, current)
                return
            }
            if (fallback.expectedOpus && !extras.getString("audio_codec").orEmpty().contains("opus", true)) {
                LosslessDiagnostics.record(current.id, LosslessDiagnostics.Reason.OPUS_UNAVAILABLE, "active-format")
                restoreLosslessFallback(player, current)
                return
            }
            if (extras.getBoolean("lossless_invalid")) {
                LosslessDiagnostics.record(current.id, LosslessDiagnostics.Reason.DECODER_UNSUPPORTED, "active-format")
                restoreLosslessFallback(player, current)
                scope.launch(AppDispatchers.Io) {
                    repository?.rejectLosslessUpgrade(fallback.original, fallback.upgradedUrl)
                }
                return
            }
            fallback.audioConfirmed = if (fallback.expectedLossless) extras.getBoolean("isLossless")
                else if (com.example.data.remote.StreamingAddons.hasRegisteredTransport(address))
                    extras.getString("audio_codec").orEmpty().isNotBlank()
                else !extras.getBoolean("isLossless") && (!fallback.expectedOpus ||
                    extras.getString("audio_codec").orEmpty().contains("opus", true))
            fallback.confirmedAudio = Bundle(extras)
            confirmLosslessActivation(player)
            return
        }
        if (fallback != null && !fallback.activated) return
        val updated = current.copy(audioUrl = address,
            source = extras.getString("media_source")?.takeIf { it.isNotBlank() } ?: current.source,
            qualityBadge = extras.getString("qualityBadge").orEmpty(),
            bitrateKbps = if (address.contains("saavncdn.com")) current.bitrateKbps else extras.getInt("bitrate"),
            isLossless = extras.getBoolean("isLossless"))
        if (updated == current) { scheduleLosslessDiscovery(policy); return }
        replaceQueueTrack(player.currentMediaItemIndex, updated)
        _uiState.update { it.copy(currentTrack = updated, qualityBadge = updated.qualityBadge, queue = queueSnapshot()) }
        scheduleLosslessDiscovery(policy)
    }

    private fun showPlaybackFailure(message: String) {
        pendingSelectionId = null
        _uiState.update { it.copy(isLoading = false, isPlaying = false, errorMessage = message) }
    }

    internal fun reportPlaybackLookupFailure(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
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
            track?.id?.let(com.example.data.local.ListeningSessions::forTrack)
                ?: if (track != null && (newPlay || previous.track?.id != track.id || previous.playSessionId.isBlank()))
                    java.util.UUID.randomUUID().toString() else previous.playSessionId) }
    }

    internal fun recordMeaningfulListening(trackId: String, playSequence: Long, listenedMs: Long, durationMs: Long) {
        if (hasRecordedMeaningfulPlay || _listeningState.value.playSequence != playSequence ||
            _uiState.value.currentTrack?.id != trackId ||
            (listenedMs < 30_000L && (durationMs <= 0L || listenedMs < (durationMs + 1L) / 2L))) return
        hasRecordedMeaningfulPlay = true
        val track = _uiState.value.currentTrack ?: return
        val sessionId = currentSessionId
        scope.launch(AppDispatchers.Io) {
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
            val native = nativeProgress(player)
            val pos = native?.positionMs ?: player.currentPosition.coerceAtLeast(0L)
            rememberLosslessPosition(player, pos)
            val upgrade = losslessFallback
            if (upgrade != null && !upgrade.activated &&
                player.currentMediaItem?.playbackUri()?.toString() == upgrade.upgradedUrl &&
                kotlin.math.abs(pos - upgrade.resumePositionMs) > 2_000L) return
            val dur = if (player.duration > 0) player.duration else _uiState.value.durationMs
            val buf = player.bufferedPosition.coerceAtLeast(0L)

            positionClock.sample(pos, native?.running ?: player.isPlaying, player.playbackParameters.speed)
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
        losslessDiscoveryJob?.cancel()
        losslessDiscoveryJob = null
        _uiState.value.currentTrack?.id?.let { AudioSignalPath.sourceRequest(it, false) }
        ++losslessRequestEpoch
        losslessFallback = null
        losslessTransitionId = null
    }

    /** Reuse existing events/UI polling; no extra background position ticker. */
    private fun rememberLosslessPosition(player: MediaController, positionMs: Long = player.currentPosition) {
        val fallback = losslessFallback ?: return
        if (!fallback.activated || fallback.generation != selectionGeneration ||
            player.currentMediaItem?.mediaId != fallback.original.id ||
            player.currentMediaItem?.playbackUri()?.toString() != fallback.upgradedUrl) return
        if (positionMs > 0L || positionMs == 0L && fallback.resumePositionMs <= 2_000L)
            fallback.resumePositionMs = positionMs
    }

    /** Commit source/quality only after Media3 reports the matching lossless input ready. */
    private fun confirmLosslessActivation(player: MediaController) {
        val fallback = losslessFallback ?: return
        if (fallback.activated) return
        val monitor = NetworkQualityMonitor.getInstance(context)
        if (fallback.requestEpoch != losslessRequestEpoch ||
            fallback.expectedLossless && (!monitor.allowsLosslessUpgrade ||
                _uiState.value.selectedQuality != AudioQuality.HI_RES_LOSSLESS) ||
            fallback.policyKey?.let { it != monitor.sourcePolicy.value.key } == true) {
            // A remote controller may acknowledge replacement after cancellation.
            // Its READY/format callback must roll back too, even if the first
            // cancellation check still saw the original URI. Keep active upgrades.
            _uiState.value.currentTrack?.let { restoreLosslessFallback(player, it) }
            return
        }
        val positionDelta = player.currentPosition - fallback.resumePositionMs
        val positionValid = positionDelta >= -2_000L && positionDelta <= 20_000L
        if (!fallback.audioConfirmed || fallback.generation != selectionGeneration ||
            player.currentMediaItem?.mediaId != fallback.original.id || player.playbackState != Player.STATE_READY ||
            !positionValid ||
            player.currentMediaItem?.playbackUri()?.toString() != fallback.upgradedUrl || player.playerError != null) return
        val current = _uiState.value.currentTrack?.takeIf { it.id == fallback.original.id } ?: return
        val extras = fallback.confirmedAudio ?: player.mediaMetadata.extras ?: return
        if (extras.getBoolean("isLossless") != fallback.expectedLossless &&
            !com.example.data.remote.StreamingAddons.hasRegisteredTransport(fallback.upgradedUrl) || !extras.getBoolean("audio_actual")) return
        val updated = current.copy(audioUrl = fallback.upgradedUrl, bitrateKbps = extras.getInt("bitrate"),
            qualityBadge = extras.getString("qualityBadge") ?: fallback.upgraded.qualityBadge,
            isLossless = extras.getBoolean("isLossless"), source = fallback.upgraded.source)
        fallback.activated = true
        enhancerOrigin = fallback.targetOrigin
        if (fallback.targetOrigin == null && player.currentMediaItem?.mediaMetadata?.extras?.containsKey("enhancer_origin_track") == true) {
            val item = player.currentMediaItem ?: return
            val cleared = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { SourceTransitionOrigin.write(this, null) }
            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon().setExtras(cleared).build()).build())
        }
        AudioSignalPath.sourceBookmark(updated.id, updated.audioUrl, fallback.targetOrigin)
        replaceQueueTrack(player.currentMediaItemIndex, updated)
        _uiState.update { it.copy(currentTrack = updated, qualityBadge = updated.qualityBadge, queue = queueSnapshot()) }
        LosslessDiagnostics.clear(updated.id)
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
        val position = losslessRecoveryPosition(fallback.resumePositionMs, player.currentPosition, fallback.activated)
        val resume = player.playWhenReady
        val original = current.copy(audioUrl = fallback.original.audioUrl, bitrateKbps = fallback.original.bitrateKbps,
            qualityBadge = fallback.original.qualityBadge, isLossless = fallback.original.isLossless,
            source = fallback.original.source)
        if (!fallback.originalUsable || !policyAllows(original, NetworkQualityMonitor.getInstance(context).sourcePolicy.value)) {
            player.pause(); player.stop()
            showPlaybackFailure("No verified source is available at the selected streaming quality.")
            return true
        }
        return try {
            losslessTransitionId = original.id
            enhancerOrigin = fallback.previousOrigin
            replaceQueueTrack(index, original)
            _uiState.update { it.copy(currentTrack = original, qualityBadge = original.qualityBadge,
                errorMessage = null, queue = queueSnapshot()) }
            val restored = original.toMediaItem(preparedPolicy = NetworkQualityMonitor.getInstance(context).sourcePolicy.value).forSourceTransition()
            val restoredExtras = Bundle(restored.mediaMetadata.extras ?: Bundle()).apply {
                SourceTransitionOrigin.write(this, fallback.previousOrigin)
            }
            player.replaceMediaItem(index, restored.buildUpon().setMediaMetadata(
                restored.mediaMetadata.buildUpon().setExtras(restoredExtras).build()).build())
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
    private fun beginSelection(track: MusicTrack): Long {
        PlaybackDiagnostics.markStartup(selectionStartupTrace, PlaybackDiagnostics.StartupStage.CANCELLED)
        selectionStartupTrace = PlaybackDiagnostics.beginStartup(track.id)
        losslessDiscoveryKey = null
        youtubeQualityJob?.cancel()
        pendingRestoredSession = null
        cancelLosslessUpgrade()
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryGuard.select(track.id, newRequest = true)
        val generation = ++selectionGeneration
        pendingSelectionId = track.id
        enhancerOrigin = null
        // Jio's watchdog begins only after Jio is selected as the fallback;
        // YouTube/Archive discovery must never consume its buffering window.
        initialJioDeadlineMs = 0L
        selectionContext = listOf(track) // Replaced by the owned CPU-prepared queue below.
        val previous = _uiState.value.currentTrack
        if (previous != null && previous.id != track.id && controller?.currentMediaItem?.mediaId == previous.id) {
            playbackHistory.add(previous)
            if (playbackHistory.size > 50) playbackHistory.removeAt(0)
        }
        stopProgressTicker()
        _listeningState.update { it.copy(isPlaying = false) }
        activeQueue = mutableStateListOf()
        positionClock.sample(0L, false)
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

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = listOf(track), isExplicitPlaylist: Boolean = false,
        isInfiniteRadio: Boolean = false, collectionLoader: CollectionPlaybackContinuation? = null,
        preserveQueue: Boolean = false) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { playTrack(track, queue, isExplicitPlaylist, isInfiniteRadio, collectionLoader, preserveQueue) }
            return
        }
        val now = System.currentTimeMillis()
        val currentTrack = _uiState.value.currentTrack
        val isSameTrack = currentTrack?.queueIdentity == track.queueIdentity

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
        collectionRadioStarted = false
        collectionContinuation = collectionLoader.takeIf { isExplicitPlaylist }
        _uiState.update { it.copy(isInfiniteRadio = isInfiniteRadio) }

        // Cancel previous unresolved network/stream resolution job and search queue jobs
        activeSearchPlayJob?.cancel()
        playResolutionJob?.cancel()
        cancelQueueRefill()
        rememberQueueCandidates(queue)
        val generation = beginSelection(track)
        val startupTrace = selectionStartupTrace
        pendingPolicyRestart = { playTrack(track, queue, isExplicitPlaylist, isInfiniteRadio, collectionLoader, preserveQueue) }
        val jioDeadline = initialJioDeadlineMs

        val repo = repository
        val needsResolution = com.example.data.repository.StreamResolver.needsResolution(track)
        // Even already-resolved selections prepare potentially large playlist metadata off Main.
        playResolutionJob = scope.launch {
            var ownedQueue: List<MusicTrack> = listOf(track)
            try {
                ownedQueue = withContext(AppDispatchers.CpuBounded) { queue.toList() }
                if (!ownsSelection(track.id, generation)) return@launch
                selectionContext = ownedQueue
                val resolved = resolveWhileConnecting(connect = {
                    awaitController()
                    PlaybackDiagnostics.markStartup(startupTrace, PlaybackDiagnostics.StartupStage.CONTROLLER_READY)
                }) {
                    PlaybackDiagnostics.markStartup(startupTrace, PlaybackDiagnostics.StartupStage.RESOLUTION_STARTED)
                    val stream = if (needsResolution) withContext(AppDispatchers.Io) {
                        (repo ?: throw IllegalStateException("Music catalogue is unavailable. Please try again."))
                            .resolvePlayableTrack(track, jioDeadlineMs = jioDeadline, onAddonLookupFinished = { elapsed ->
                                withContext(Dispatchers.Main.immediate) {
                                    if (ownsSelection(track.id, generation)) initialJioDeadlineMs = jioDeadline + elapsed
                                }
                            })
                    } else track
                    PlaybackDiagnostics.markStartup(startupTrace, PlaybackDiagnostics.StartupStage.RESOLVED)
                    stream
                }
                if (isActive && ownsSelection(track.id, generation)) {
                    val host = Uri.parse(resolved.audioUrl).host.orEmpty()
                    initialJioDeadlineMs = if (host == "saavncdn.com" || host.endsWith(".saavncdn.com"))
                        android.os.SystemClock.elapsedRealtime() + 6_000L else 0L
                    playTrackInternal(resolved, ownedQueue, isExplicitPlaylist, preserveQueue)
                    if (ownsSelection(track.id, generation)) pendingPolicyRestart = null
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "Track resolution failed: ${e.javaClass.simpleName}")
                if (ownsSelection(track.id, generation)) {
                    PlaybackDiagnostics.markStartup(startupTrace, PlaybackDiagnostics.StartupStage.FAILED)
                    continueAfterUnavailableSelection(track, ownedQueue, isExplicitPlaylist, e.message)
                }
            }
        }
    }

    private suspend fun playTrackInternal(track: MusicTrack, queue: List<MusicTrack>, isExplicitPlaylist: Boolean = false,
        preserveQueue: Boolean = false) {
        if (track.audioUrl.isBlank() || (Uri.parse(track.audioUrl).scheme !in setOf("http", "https", "file", "content", "xtreme-youtube", "xtreme-sabr") &&
            com.example.data.remote.StreamingAddons.mime(track.audioUrl) != androidx.media3.common.MimeTypes.APPLICATION_MPD)) {
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
        val startupTrace = selectionStartupTrace
        val preparedPolicy = withContext(AppDispatchers.Io) { NetworkQualityMonitor.getInstance(context).sourcePolicySnapshot() }
        if (!ownsSelection(track.id, generation)) return
        if (!preparedPolicy.acceptsRemoteTrack(track)) {
            pendingPolicyRestart?.invoke()
            return
        }
        _uiState.update { it.copy(selectedQuality = preparedPolicy.ceiling.audioQuality) }
        var autoplayEnabled: Boolean
        var targetQuality: AudioQuality
        var prepared: Triple<SnapshotStateList<MusicTrack>, List<MediaItem>, Int>
        do {
            autoplayEnabled = _uiState.value.isAutoplayEnabled
            targetQuality = _uiState.value.selectedQuality
            prepared = withContext(AppDispatchers.CpuBounded) {
                val tracks = if (autoplayEnabled && !isExplicitPlaylist && !preserveQueue) {
                    // A discovery screen is candidate context, not a collection
                    // chosen by the listener. Radio supplies the next recordings.
                    mutableStateListOf(track)
                } else {
                    (queue.map { if (it.queueIdentity == track.queueIdentity) track else it } + track)
                        .filter { it.hasQueueSource() }.distinctBy { it.queueIdentity }.toMutableStateList()
                }
                Triple(tracks, tracks.map { queued -> queued.toMediaItem(targetQuality,
                    preparedPolicy.takeIf { queued.id == track.id }, startupTrace.takeIf { queued.id == track.id } ?: 0L) },
                    tracks.indexOfFirst { it.queueIdentity == track.queueIdentity }.coerceAtLeast(0))
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
            PlaybackDiagnostics.markStartup(startupTrace, PlaybackDiagnostics.StartupStage.PREPARE_REQUESTED)

            if (autoplayEnabled && !isExplicitPlaylist && !preserveQueue) {
                // Autoplay Session:
                // Start playing immediately with playable candidates only
                activeQueue = prepared.first
                val mediaItems = prepared.second
                player.setMediaItems(mediaItems, 0, 0L)
                player.prepare()
                player.play()

                positionClock.sample(0L, false)
                _playbackPosition.value = 0L
                _uiState.update {
                    it.copy(
                        currentTrack = track,
                        queue = queueSnapshot(),
                        currentIndex = 0,
                        isPlaying = player.isPlaying,
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
                val startIndex = prepared.third
                val mediaItems = prepared.second
                player.setMediaItems(mediaItems, startIndex, 0L)
                player.prepare()
                player.play()

                val nextInQueue = activeQueue.getOrNull(startIndex + 1)

                positionClock.sample(0L, false)
                _playbackPosition.value = 0L
                _uiState.update {
                    it.copy(
                        currentTrack = track,
                        queue = queueSnapshot(),
                        currentIndex = startIndex,
                        isPlaying = player.isPlaying,
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
        val generation = candidateGeneration.get()
        scope.launch(candidateDispatcher) {
            candidateUpdates.lock()
            try {
                if (generation != candidateGeneration.get()) return@launch
                val merged = (candidateCatalogPool + tracks).distinctBy { it.id }.takeLast(600)
                ensureActive()
                if (generation == candidateGeneration.get()) candidateCatalogPool = merged
            } finally { candidateUpdates.unlock() }
        }
    }

    private fun cancelQueueRefill() {
        pendingCollectionRadio = emptyList()
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
        if (recommendationFetchJob?.isActive == true) return
        if (isCurrentSessionExplicitPlaylist && !collectionRadioStarted) {
            // Respect Media3's actual order (including shuffle/repeat). Start
            // preparing radio only when the last collection track is active.
            if (player.hasNextMediaItem()) return
            if (collectionContinuation != null) {
                extendCollectionQueue(player, track)
                return
            }
            if (!_uiState.value.isAutoplayEnabled) return
            collectionRadioStarted = true
        }
        if (!_uiState.value.isAutoplayEnabled) return
        fun availableUpcoming(): Int = if (isCurrentSessionExplicitPlaylist) pendingCollectionRadio.size
            else activeQueue.size - player.currentMediaItemIndex - 1
        val remaining = availableUpcoming()
        if (remaining > refillThreshold) return
        val generation = queueGeneration
        val requestKey = "$generation|${track.id}|${player.currentMediaItemIndex}|$remaining"
        val now = android.os.SystemClock.elapsedRealtime()
        if (!advanceWhenQueueReady && requestKey == lastRefillKey && now - lastRefillAt < 2_000L) return
        lastRefillKey = requestKey
        lastRefillAt = now
        recommendationFetchJob = scope.launch {
            try {
                val seed = _uiState.value.currentTrack ?: track
                val nativeSnapshot = queueSnapshot()
                val radioSnapshot = pendingCollectionRadio
                val sessionHistory = try {
                    listeningEventDao.getSessionPlayedTrackIds(currentSessionId).toSet()
                } catch (_: Exception) {
                    emptySet()
                }
                val queuedIds = (nativeSnapshot + radioSnapshot).mapTo(mutableSetOf()) { it.id } +
                    recommendationEngine.getSessionTracks().map { it.id } + sessionHistory + setOf(seed.id)

                val needed = (upcomingQueueTarget - remaining).coerceAtLeast(1)
                val radioTracks = repository?.fetchYouTubeRadio(
                    seed = seed,
                    excludedIds = queuedIds,
                    limit = needed
                ).orEmpty()

                for (candidate in radioTracks) {
                    if (!isActive || queueGeneration != generation || !_uiState.value.isAutoplayEnabled) return@launch
                    if (availableUpcoming() >= upcomingQueueTarget) return@launch
                    if (activeQueue.any { it.id == candidate.id }) continue

                    val resolved = try {
                        withContext(AppDispatchers.Io) {
                            if (!com.example.data.repository.StreamResolver.needsResolution(candidate)) candidate
                            else repository?.resolvePlayableTrack(candidate)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unavailable radio track '${candidate.title}': ${e.message}")
                        null
                    } ?: continue

                    if (resolved.audioUrl.isBlank()) continue
                    if (!isActive || queueGeneration != generation || !_uiState.value.isAutoplayEnabled) return@launch
                    if (activeQueue.any { it.id == resolved.id }) continue

                    if (isCurrentSessionExplicitPlaylist) {
                        pendingCollectionRadio = pendingCollectionRadio + resolved
                        if (advanceWhenQueueReady || player.playbackState == Player.STATE_ENDED) {
                            startCollectionRadio()
                            return@launch
                        }
                        continue
                    }

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "YouTube Music Radio queue refill failed: ${e.message}", e)
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
     * Plays a track from search results, resolving its stream asynchronously on AppDispatchers.Io
     * and starting playback without freezing the UI or racing on MediaController.
     */
    fun playQueuedTrack(track: MusicTrack) {
        playTrack(track, _uiState.value.queue.ifEmpty { listOf(track) }, isExplicitPlaylist = isCurrentSessionExplicitPlaylist,
            isInfiniteRadio = _uiState.value.isInfiniteRadio, collectionLoader = collectionContinuation, preserveQueue = true)
    }

    fun playFromSearch(track: MusicTrack, @Suppress("UNUSED_PARAMETER") searchContextPool: List<MusicTrack>) {
        // Search is a discovery surface, never a playlist. Subsequent songs come
        // from the existing personalized radio pipeline, not neighboring results.
        playTrack(track, listOf(track), isExplicitPlaylist = false)
    }

    fun retryPlayback() {
        val current = _uiState.value.currentTrack ?: return
        recoveryGuard.select(current.id, newRequest = true)
        playTrack(current, activeQueue.ifEmpty { selectionContext }, isCurrentSessionExplicitPlaylist,
            _uiState.value.isInfiniteRadio, collectionContinuation, preserveQueue = true)
    }

    fun setCandidatePool(pool: List<MusicTrack>) {
        val generation = candidateGeneration.incrementAndGet()
        scope.launch(candidateDispatcher) {
            candidateUpdates.lock()
            try {
                if (generation != candidateGeneration.get() || candidateCatalogPool == pool) return@launch
                val snapshot = pool.toList()
                ensureActive()
                if (generation == candidateGeneration.get()) candidateCatalogPool = snapshot
            } finally { candidateUpdates.unlock() }
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

    internal fun resumeFromMediaSearch() {
        ensureMediaControllerConnected { player ->
            if (pendingSelectionId == null) {
                try { resumeMediaSearchPlayback(player) }
                catch (_: Exception) { reportPlaybackLookupFailure("Unable to resume playback. Please try again.") }
            }
        }
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
                isCurrentSessionExplicitPlaylist, _uiState.value.isInfiniteRadio, collectionContinuation, preserveQueue = true)
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
            rememberLosslessPosition(player, positionMs)
            positionClock.sample(positionMs, controller?.isPlaying == true, controller?.playbackParameters?.speed ?: 1f)
            _playbackPosition.value = positionMs
            _uiState.update { it.copy(currentPositionMs = positionMs) }
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking to $positionMs: ${e.message}", e)
        }
    }

    private fun extendCollectionQueue(player: MediaController, seed: MusicTrack) {
        val loader = collectionContinuation ?: return
        val generation = queueGeneration
        recommendationFetchJob = scope.launch {
            try {
                val result = withContext(AppDispatchers.Io) { loader.fetch(loader.page) }
                if (generation != queueGeneration || !isCurrentSessionExplicitPlaylist) return@launch
                val before = queueSnapshot()
                val revision = queueRevision
                val (tracks, mediaItems) = withContext(AppDispatchers.CpuBounded) {
                    val existing = before.mapTo(mutableSetOf()) { it.id }
                    val tracks = result.tracks.filter { it.hasQueueSource() && existing.add(it.id) }
                    tracks to tracks.map { it.toMediaItem() }
                }
                if (generation != queueGeneration) return@launch
                if (revision != queueRevision) return@launch // Retry the same cursor against the latest queue.
                val next = result.nextPage?.takeIf { it > loader.page }
                if (tracks.isEmpty()) {
                    if (next != null) throw java.io.IOException("The next playlist page contained no new playable songs")
                    collectionContinuation = null
                    return@launch
                }
                val advance = advanceWhenQueueReady || player.playbackState == Player.STATE_ENDED
                val resume = player.playWhenReady
                val oldSize = activeQueue.size
                activeQueue.addAll(tracks); queueRevision++
                try { player.addMediaItems(mediaItems) }
                catch (error: Exception) {
                    while (activeQueue.size > oldSize) activeQueue.removeAt(activeQueue.lastIndex)
                    queueRevision++
                    throw error
                }
                // No suspension between native queue mutation and cursor commit.
                // Canceled page requests therefore cannot skip unheard playlist tracks.
                collectionContinuation = loader.after(result)
                publishQueueState()
                if (advance && player.hasNextMediaItem()) {
                    advanceWhenQueueReady = false
                    player.seekToNextMediaItem(); player.prepare(); player.playWhenReady = resume
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Couldn't load the next playlist songs. Tap next to retry.") }
            } finally {
                if (generation == queueGeneration) {
                    recommendationFetchJob = null
                    if (collectionContinuation == null && _uiState.value.isAutoplayEnabled)
                        refreshAutoplayRecommendation(_uiState.value.currentTrack ?: seed)
                }
            }
        }
    }

    /** Launch a new radio session only between songs, never reorder an active collection. */
    private fun startCollectionRadio(): Boolean {
        if (!_uiState.value.isAutoplayEnabled || pendingCollectionRadio.isEmpty()) return false
        val next = pendingCollectionRadio
        val infinite = _uiState.value.isInfiniteRadio
        val selection = selectionGeneration
        cancelQueueRefill() // Invalidates an in-flight producer before scheduling the next session.
        advanceWhenQueueReady = false
        isCurrentSessionExplicitPlaylist = false
        scope.launch {
            if (selectionGeneration == selection)
                playTrack(next.first(), next, isExplicitPlaylist = false, isInfiniteRadio = infinite, preserveQueue = true)
        }
        return true
    }

    fun skipNext() {
        val player = controller
        if (player == null || !player.isConnected || !controllerReady.isCompleted) {
            ensureMediaControllerConnected { skipNext() }
            return
        }
        cancelLosslessUpgrade()
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryGuard.select(null, newRequest = true)
        try {
            if (isCurrentSessionExplicitPlaylist && !player.hasNextMediaItem() && startCollectionRadio()) return
            if (player.hasNextMediaItem()) {
                advanceWhenQueueReady = false
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
                _uiState.value.currentTrack?.let { refreshAutoplayRecommendation(it) }
            } else if (isCurrentSessionExplicitPlaylist && collectionContinuation != null ||
                _uiState.value.isAutoplayEnabled) {
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
        cancelLosslessUpgrade()
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryGuard.select(null, newRequest = true)

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
                } else if (playbackHistory.isNotEmpty() && !isCurrentSessionExplicitPlaylist) {
                    val prevTrack = playbackHistory.removeAt(playbackHistory.lastIndex)
                    Log.d(TAG, "Second press: playing previous song from history: ${prevTrack.title}")
                    playTrack(prevTrack, activeQueue.ifEmpty { listOf(prevTrack) }, isExplicitPlaylist = isCurrentSessionExplicitPlaylist,
                        isInfiniteRadio = _uiState.value.isInfiniteRadio, collectionLoader = collectionContinuation, preserveQueue = true)
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
            // Favorite identity belongs to the recording, including every playlist occurrence.
            scope.launch {
                while (isActive) {
                    val revision = queueRevision
                    val snapshot = queueSnapshot()
                    val prepared = withContext(AppDispatchers.CpuBounded) {
                        snapshot.map { if (it.id == trackId) it.copy(isLiked = isLiked) else it }
                            .takeUnless { it == snapshot }?.toMutableStateList()
                    }
                    if (revision != queueRevision) continue
                    if (prepared != null) {
                        activeQueue = prepared
                        _uiState.update { it.copy(queue = queueSnapshot()) }
                    }
                    break
                }
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
                val resolved = withContext(AppDispatchers.Io) {
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
        // Compatibility with older callers; all writes now target the active
        // network profile and its collector handles badges/optional lossless work.
        com.example.data.local.AudioQualityPreferences.setSelectedQuality(context, quality)
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
        PlaybackDiagnostics.markStartup(selectionStartupTrace, PlaybackDiagnostics.StartupStage.CANCELLED)
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
            candidateGeneration.incrementAndGet()
            candidateCatalogPool = emptyList()
            Log.i(TAG, "PlaybackManager released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources: ${e.message}", e)
        }
    }

    // Persisted YouTube metadata intentionally has no signed URL. Keep these
    // recordings in playlist/search queues; their stable reference resolves on load.
    private fun MusicTrack.hasQueueSource(): Boolean = audioUrl.isNotBlank() ||
        id.startsWith("yt_") && com.example.data.remote.YouTubeAudioStreamService.isVideoId(id.removePrefix("yt_"))

    private fun MusicTrack.saavnBitrateFor(quality: AudioQuality): Int {
        val supported = bitrateKbps.takeIf { it in 1..320 } ?: 320
        val requested = minOf(quality.kbps, supported, 320,
            NetworkQualityMonitor.getInstance(context).currentMaxBitrateBps / 1000)
        return when { requested >= 320 -> 320; requested >= 160 -> 160; requested >= 96 -> 96; else -> 48 }
    }

    /** A source-only change gets a fresh watchdog budget, never the expired song-selection timer. */
    private fun MediaItem.forSourceTransition(): MediaItem = buildUpon().setMediaMetadata(
        mediaMetadata.buildUpon().setExtras(Bundle(mediaMetadata.extras ?: Bundle()).apply {
            putLong("jio_start_deadline", 0L)
            remove("jio_youtube_fallback")
        }).build()).build()

    private fun MusicTrack.toMediaItem(targetQuality: AudioQuality = _uiState.value.selectedQuality,
        preparedPolicy: PlaybackSourcePolicy? = null, startupTrace: Long = 0L): MediaItem {
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
            .setMimeType(com.example.data.remote.StreamingAddons.mime(effectiveUrl))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(
                com.example.data.remote.YouTubeAudioStreamService.playbackUri(id, effectiveUrl))).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(Uri.parse(coverUrl))
                    .setExtras(Bundle().apply {
                        if (startupTrace != 0L) putLong(PlaybackDiagnostics.STARTUP_TRACE_EXTRA, startupTrace)
                        putInt("bitrate", effectiveBitrate)
                        putInt("requested_audio_kbps", targetQuality.kbps)
                        putString("qualityBadge", effectiveBadge)
                        putString("genre", genre)
                        putString("media_source", source)
                        putBoolean("recommendation_only", recommendationOnly)
                        putBoolean("queue_collection", isCurrentSessionExplicitPlaylist)
                        putLong("jio_start_deadline", if (id == _uiState.value.currentTrack?.id) initialJioDeadlineMs else 0L)
                        putLong("duration", durationMs)
                        putBoolean("isLiked", isLiked)
                        putBoolean("isLossless", isLossless)
                        if (preparedPolicy != null) {
                            putString("source_policy_key", preparedPolicy.key)
                            putString("audio_policy_tier", preparedPolicy.ceiling.audioQuality.name)
                            putLong("audio_policy_checked_at", System.currentTimeMillis())
                        }
                        putString("singers", singers); putString("writer", writer)
                        putStringArrayList("primaryArtists", ArrayList(primaryArtists))
                        putLong("playlist_entry_id", playlistEntryId)
                        putString("language", language); putString("year", year); putString("isrc", isrc)
                        SourceTransitionOrigin.write(this, enhancerOrigin?.takeIf { it.track.id == id })
                    })
                    .build()
            )
            .build()
        return com.example.service.NetworkAudioQualityPolicy.constrain(mediaItem,
            NetworkQualityMonitor.getInstance(context).currentMaxBitrateBps)
    }
}

/** A completion for an old attempt can never wait on or adopt a new incomplete future. */
internal fun <T> completedConnectionValue(expected: ListenableFuture<T>, current: ListenableFuture<T>?, ownsGeneration: Boolean): T? =
    if (ownsGeneration && expected === current && expected.isDone) expected.get() else null
