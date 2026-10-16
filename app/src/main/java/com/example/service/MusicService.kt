package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import com.example.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.example.XtremeMusicApp
import com.example.data.model.MusicTrack
import com.example.data.local.TrackEntity
import com.example.data.repository.CatalogNames
import com.example.data.remote.YouTubeAudioStreamService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class MusicService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkpointJob: Job? = null
    private var checkpointItems: List<MediaItem> = emptyList()
    private var queueGeneration = 0L
    private var sabrRecoveryTrackId: String? = null
    private var sabrRecoveryCount = 0
    private var sourceRecoveryJob: Job? = null
    private var jioFallback: JioSaavnPlaybackFallback? = null
    private val networkQuality by lazy { com.example.playback.NetworkQualityMonitor.getInstance(this) }
    private var networkQueueJob: Job? = null
    private var applyingNetworkPolicy = false
    private val app: XtremeMusicApp get() = application as XtremeMusicApp

    private fun applyAudioCeiling(selector: DefaultTrackSelector, player: Player) {
        val item = player.currentMediaItem
        val youtube = item?.let { YouTubeAudioStreamService.isYouTubeAudio(it.mediaId, it.localConfiguration?.uri.toString()) } == true
        selector.parameters = selector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, youtube)
            .setMaxAudioBitrate(minOf(networkQuality.currentMaxBitrateBps, if (youtube) 320_000 else Int.MAX_VALUE))
            .setExceedAudioConstraintsIfNecessary(true).build()
    }

    /** Reconfigure only a small upcoming window. Never touch the current MediaItem. */
    private fun scheduleUpcomingCeilings() {
        if (applyingNetworkPolicy) return
        networkQueueJob?.cancel()
        networkQueueJob = serviceScope.launch {
            yield() // Coalesce a burst of queue/metadata callbacks.
            val player = exoPlayer ?: return@launch
            val ceiling = networkQuality.currentMaxBitrateBps
            var changed = false
            val start = (player.currentMediaItemIndex + 1).coerceAtLeast(0)
            for (index in start until minOf(player.mediaItemCount, start + 3)) {
                if (index >= player.mediaItemCount) break
                if (index <= player.currentMediaItemIndex) continue
                val before = player.getMediaItemAt(index)
                var after = NetworkAudioQualityPolicy.constrain(before, ceiling)
                if (after === before) continue
                val video = YouTubeAudioStreamService.videoIdFromUri(before.localConfiguration?.uri ?: Uri.EMPTY)
                if (video != null) {
                    if (networkQuality.activeQualityCeiling.value.connectionType == com.example.playback.NetworkConnectionType.OFFLINE) continue
                    // Select a real transport/itag before changing an upcoming media
                    // source: a SABR-only song must not masquerade as progressive.
                    val quality = networkQuality.currentAudioQuality
                    val audio = withTimeoutOrNull(12_000L) {
                        try { withContext(Dispatchers.IO) {
                            YouTubeAudioStreamService.resolve(video, quality, maxBitrateBps = ceiling)
                        } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    } ?: continue
                    val uri = Uri.parse(YouTubeAudioStreamService.playbackUri("yt_$video", audio.url))
                    val extras = Bundle(after.mediaMetadata.extras ?: Bundle()).apply {
                        putInt("bitrate", audio.bitrateKbps); putString("qualityBadge", audio.qualityBadge)
                        putBoolean("isLossless", false); putBoolean("audio_actual", false)
                    }
                    after = after.buildUpon().setUri(uri).setMimeType(if (audio.isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
                        .setRequestMetadata(after.requestMetadata.buildUpon().setMediaUri(uri).build())
                        .setMediaMetadata(after.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                }
                ensureActive()
                // Navigation/advance or another policy update always wins over this result.
                if (ceiling != networkQuality.currentMaxBitrateBps || index <= player.currentMediaItemIndex ||
                    index >= player.mediaItemCount) continue
                val current = player.getMediaItemAt(index)
                if (current.mediaId != before.mediaId || current.localConfiguration?.uri != before.localConfiguration?.uri) continue
                applyingNetworkPolicy = true
                try { player.replaceMediaItem(index, after); changed = true }
                finally { applyingNetworkPolicy = false }
            }
            if (changed) checkpoint(rebuild = true)
        }
    }

    private fun checkpoint(rebuild: Boolean = false) {
        val player = exoPlayer ?: return
        if (player.mediaItemCount == 0) return
        if (rebuild || checkpointItems.size != player.mediaItemCount) {
            checkpointItems = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        }
        PlaybackSessionStore.checkpoint(this, PlaybackSessionStore.Snapshot(checkpointItems,
            player.currentMediaItemIndex.coerceIn(0, checkpointItems.lastIndex), player.currentPosition.coerceAtLeast(0L)))
    }

    /** MediaMetadata.equals compares only whether extras exist, not their values.
     * Session extras reliably notify connected controllers when actual decoder data changes. */
    private fun publishAudioProperties(item: MediaItem, audio: Bundle) {
        val session = mediaLibrarySession ?: return
        session.setSessionExtras(Bundle(session.sessionExtras).apply {
            putBundle("active_audio", Bundle(audio).apply {
                putString("track_id", item.mediaId)
                putString("active_audio_uri", item.localConfiguration?.uri?.toString())
            })
        })
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try { result.set(block()) }
            catch (e: CancellationException) { result.cancel(false); throw e }
            catch (e: Exception) { result.setException(e) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }

    private fun realTrack(track: MusicTrack): Boolean {
        val host = Uri.parse(track.audioUrl).host.orEmpty().lowercase(java.util.Locale.ROOT)
        return host != "soundhelix.com" && !host.endsWith(".soundhelix.com") && track.id.isNotBlank() &&
            (track.audioUrl.isNotBlank() || track.id.startsWith("online_") ||
                (track.id.startsWith("yt_") && YouTubeAudioStreamService.isVideoId(track.id.removePrefix("yt_"))))
    }

    private fun paginate(items: List<MusicTrack>, index: Int, size: Int): List<MusicTrack> {
        if (index < 0 || size <= 0) return emptyList()
        val offset = index.toLong() * size
        if (offset >= items.size) return emptyList()
        return items.drop(offset.toInt()).take(size.coerceAtMost(100))
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        serviceScope.launch(Dispatchers.IO) { com.example.playback.AudioDeviceManager.init(this@MusicService) }

        // 1. Audio Focus & Interruption Handling
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 2. Software-Prioritized MediaCodec Selector for Audio
        // Audio decoding needs minimal CPU. Using pure OpenMAX software decoders (OMX.google.*)
        // bypasses CCodec C2 component interface resource queries completely, while
        // excluding hardware-accelerated vendor codecs prevents ResourceManagerService evictions.
        val softwarePrioritizedCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            val softwareDecoders = defaultDecoders.filter {
                it.softwareOnly ||
                it.name.startsWith("OMX.google.", ignoreCase = true) ||
                it.name.startsWith("c2.android.", ignoreCase = true) ||
                !it.hardwareAccelerated
            }.sortedWith(
                compareByDescending<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                    if (it.name.startsWith("OMX.google.", ignoreCase = true)) 3
                    else if (it.softwareOnly || it.name.startsWith("c2.android.", ignoreCase = true)) 2
                    else 1
                }
            )
            if (softwareDecoders.isNotEmpty()) {
                softwareDecoders
            } else {
                defaultDecoders
            }
        }

        // 3. Audio-only Renderers Factory: suppresses video codecs and uses software-prioritized selector
        val audioOnlyRenderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<Renderer>
            ) {
                // Audio player only: do not build video renderers or query video C2 component interfaces
            }
        }
            .setMediaCodecSelector(softwarePrioritizedCodecSelector)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .setEnableAudioFloatOutput(false)

        // 4. High-Fidelity 320kbps Audio Pipeline with Caching
        val cacheDataSourceFactory = MusicCache.createCacheDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheDataSourceFactory)

        val trackSelector = DefaultTrackSelector(this)
        val player = ExoPlayer.Builder(this, audioOnlyRenderersFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000L)
            .setSeekForwardIncrementMs(10000L)
            .build()

        exoPlayer = player
        serviceScope.launch {
            networkQuality.activeQualityCeiling.map { it.effectiveCeiling }.distinctUntilChanged().collect {
                // Updating ABR constraints cannot re-prepare a progressive source.
                applyAudioCeiling(trackSelector, player)
                scheduleUpcomingCeilings()
            }
        }
        com.example.playback.SleepTimerManager.attach(this, player)

        // 4. Audio Effects & Equalizer real-time pipeline (attached on active playback session)
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                val item = player.currentMediaItem ?: return
                val uri = item.localConfiguration?.uri ?: return
                if (!eventTime.timeline.isEmpty && eventTime.windowIndex in 0 until eventTime.timeline.windowCount &&
                    eventTime.timeline.getWindow(eventTime.windowIndex, androidx.media3.common.Timeline.Window())
                        .mediaItem.localConfiguration?.uri != uri) return
                val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
                if ((host == "archive.org" || host.endsWith(".archive.org")) &&
                    item.mediaMetadata.extras?.getBoolean("isLossless") == true) {
                    val before = item.mediaMetadata.extras
                    val audio = com.example.playback.LosslessStreamPrebuffer.audioInfo(format,
                        before?.getInt("bitrate", 0) ?: 0) ?: return
                    if (before?.getBoolean("audio_actual") == true && before.getString("qualityBadge") == audio.badge) return
                    val extras = Bundle(before ?: Bundle()).apply {
                        putInt("bitrate", audio.bitrateKbps); putString("qualityBadge", audio.badge)
                        putInt("sample_rate", audio.sampleRate); putInt("bit_depth", audio.bitDepth)
                        putString("audio_codec", audio.codec)
                        putBoolean("isLossless", true); putBoolean("audio_actual", true)
                    }
                    player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon()
                        .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build())
                    publishAudioProperties(item, extras)
                    checkpoint(rebuild = true)
                    return
                }
                val youtube = YouTubeAudioStreamService.isYouTubeAudio(item.mediaId, uri.toString())
                val saavn = host == "saavncdn.com" || host.endsWith(".saavncdn.com")
                if (!youtube && !saavn) return
                val videoId = YouTubeAudioStreamService.videoIdFromUri(uri) ?: item.mediaId.removePrefix("yt_")
                val resolved = if (youtube) YouTubeAudioStreamService.currentStream(videoId,
                    com.example.data.local.AudioQualityPreferences.getSelectedQuality(this@MusicService),
                    uri.getQueryParameter("cap")?.toIntOrNull()?.let { if (uri.scheme == "xtreme-sabr") it * 1000 else it }
                        ?: Int.MAX_VALUE) else null
                val codec = YouTubeAudioStreamService.displayCodec(format.sampleMimeType, format.codecs)
                    .ifBlank { resolved?.codec.orEmpty() }
                val bitrate = format.averageBitrate.takeIf { it > 0 }?.div(1000)
                    ?: resolved?.bitrateKbps ?: item.mediaMetadata.extras?.getInt("bitrate", 0) ?: 0
                val badge = YouTubeAudioStreamService.displayQuality(codec, bitrate)
                val before = item.mediaMetadata.extras
                if (before?.getString("qualityBadge") == badge && before.getBoolean("audio_actual")) return
                val extras = Bundle(before ?: Bundle()).apply {
                    putInt("bitrate", bitrate); putString("qualityBadge", badge)
                    putBoolean("isLossless", false); putBoolean("audio_actual", true)
                }
                // Metadata-only replacement retains the same source, seek position and decoder.
                player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon()
                    .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build())
                publishAudioProperties(item, extras)
                checkpoint(rebuild = true)
            }
            override fun onAudioSessionIdChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId > 0) {
                    com.example.playback.AudioEffectsManager.attachAudioSession(audioSessionId)
                }
            }
        })

        jioFallback = JioSaavnPlaybackFallback(player, serviceScope,
            resolve = { track, failedUrl -> app.repository.resolvePlayableTrack(track, forceRefresh = true,
                failedUrl = failedUrl, preferYouTube = true) },
            status = { status -> mediaLibrarySession?.let { session ->
                session.setSessionExtras(Bundle(session.sessionExtras).apply { putBundle("jio_fallback_status", status) })
            } }, changed = { checkpoint(rebuild = true) })
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    queueGeneration++
                    scheduleUpcomingCeilings()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (sabrRecoveryTrackId != mediaItem?.mediaId) {
                    sourceRecoveryJob?.cancel()
                    sabrRecoveryTrackId = mediaItem?.mediaId
                    sabrRecoveryCount = 0
                }
                applyAudioCeiling(trackSelector, player)
                scheduleUpcomingCeilings()
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                applyAudioCeiling(trackSelector, player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                checkpointJob?.cancel()
                checkpoint()
                if (isPlaying) checkpointJob = serviceScope.launch {
                    while (isActive) { delay(15_000L); checkpoint() }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                jioFallback?.update()
                val queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)
                if (queueChanged || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    checkpoint(queueChanged)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val sessionId = player.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0) {
                        com.example.playback.AudioEffectsManager.attachAudioSession(sessionId)
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (jioFallback?.onError() == true) return
                // Notification/Android Auto resumption can run without the app's
                // MediaController. Keep representation recovery on the same player,
                // on its owning looper, and leave connected-controller recovery alone.
                if (SabrSessionRegistry.controllerHandlesRecovery?.invoke() != true) {
                    if (sourceRecoveryJob?.isActive == true) return
                    val item = player.currentMediaItem
                    val failedUri = item?.localConfiguration?.uri
                    val next = failedUri?.toString()?.let(SabrSessionRegistry::takeReplacement)
                    val initialTransportSelection = failedUri?.scheme == "xtreme-youtube" &&
                        failedUri.getQueryParameter("itag") == null
                    if (item != null && next != null && (initialTransportSelection || sabrRecoveryCount++ < 1)) {
                        val index = player.currentMediaItemIndex
                        val position = player.currentPosition.coerceAtLeast(0L)
                        val resume = player.playWhenReady
                        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                            putInt("bitrate", next.bitrateKbps)
                            putString("qualityBadge", next.qualityBadge)
                            putBoolean("isLossless", false)
                        }
                        val replacement = item.buildUpon()
                            .setUri(YouTubeAudioStreamService.playbackUri("yt_${next.videoId}", next.url))
                            .setMimeType(if (next.isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
                            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                        player.replaceMediaItem(index, replacement)
                        player.seekTo(index, position)
                        player.prepare()
                        player.playWhenReady = resume
                        checkpoint(rebuild = true)
                        return
                    }
                    if (item != null && failedUri != null && next == null &&
                        (item.mediaId.startsWith("yt_") || item.mediaId.startsWith("online_")) && sabrRecoveryCount++ < 1) {
                        // A headset/Auto session can outlive the UI controller. Reuse the
                        // same exact-recording resolver and one retry budget in that case.
                        val metadata = item.mediaMetadata
                        val extras = metadata.extras
                        val selected = MusicTrack(item.mediaId, metadata.title?.toString().orEmpty(),
                            metadata.artist?.toString().orEmpty(), metadata.albumTitle?.toString().orEmpty(),
                            player.duration.takeIf { it > 0L } ?: extras?.getLong("duration", 0L) ?: 0L,
                            metadata.artworkUri?.toString().orEmpty(), failedUri.toString(),
                            source = extras?.getString("media_source").orEmpty(),
                            isrc = extras?.getString("isrc").orEmpty(),
                            recommendationOnly = extras?.getBoolean("recommendation_only", false) == true)
                        sourceRecoveryJob = serviceScope.launch {
                            try {
                                val resolved = withContext(Dispatchers.IO) {
                                    app.repository.resolvePlayableTrack(selected, forceRefresh = true,
                                        failedUrl = failedUri.toString())
                                }
                                if (player.currentMediaItem?.mediaId != item.mediaId ||
                                    player.currentMediaItem?.localConfiguration?.uri != failedUri || player.playerError == null ||
                                    SabrSessionRegistry.controllerHandlesRecovery?.invoke() == true) return@launch
                                val index = player.currentMediaItemIndex
                                val position = player.currentPosition.coerceAtLeast(0L)
                                val resume = player.playWhenReady
                                val replacementExtras = Bundle(extras ?: Bundle()).apply {
                                    putInt("bitrate", resolved.bitrateKbps); putString("qualityBadge", resolved.qualityBadge)
                                    putString("media_source", resolved.source); putBoolean("isLossless", resolved.isLossless)
                                    putBoolean("audio_actual", false)
                                }
                                val replacement = item.buildUpon().setUri(resolved.audioUrl)
                                    .setRequestMetadata(MediaItem.RequestMetadata.Builder()
                                        .setMediaUri(Uri.parse(resolved.audioUrl)).build())
                                    .setMimeType(if (Uri.parse(resolved.audioUrl).lastPathSegment?.endsWith(".m3u8") == true)
                                        androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
                                    .setMediaMetadata(metadata.buildUpon().setExtras(replacementExtras).build()).build()
                                player.replaceMediaItem(index, replacement)
                                player.seekTo(index, position); player.prepare(); player.playWhenReady = resume
                                checkpoint(rebuild = true)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { android.util.Log.w("MusicService", "Recording recovery unavailable: ${e.message}") }
                        }
                        return
                    }
                }
                if (SabrSessionRegistry.controllerHandlesRecovery?.invoke() != true &&
                    player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("jio_youtube_fallback") == true && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem(); player.prepare(); player.play()
                    return
                }
                android.util.Log.w("MusicService", "Player error encountered: ${error.errorCodeName} - ${error.message}")
                // Other errors keep the controller's existing bounded recovery; no service retry loop.
            }
        })

        // 4. PendingIntent to reopen Xtreme Player UI on notification click
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 5. MediaLibrarySession Setup for Phone, Tablet, and Android Auto Head Units
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, CustomMediaLibrarySessionCallback())
            .setSessionActivity(pendingIntent)
            .build()
        _liveSession.value = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        _liveSession.value = false
        checkpoint()
        checkpointJob?.cancel()
        jioFallback?.cancel(); jioFallback = null
        serviceScope.cancel()
        exoPlayer?.let(com.example.playback.SleepTimerManager::detach)
        com.example.playback.AudioEffectsManager.detachAudioSession()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        exoPlayer = null
        YouTubeAudioStreamService.releaseResources()
        super.onDestroy()
    }

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SET_EQUALIZER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_OUTPUT_DEVICE, Bundle.EMPTY))
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val player = exoPlayer
            if (player != null && player.mediaItemCount > 0 &&
                (0 until player.mediaItemCount).all { PlaybackSessionStore.isReal(player.getMediaItemAt(it)) }) {
                val currentIdx = player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                val currentPos = player.currentPosition
                val items = mutableListOf<MediaItem>()
                for (i in 0 until player.mediaItemCount) {
                    items.add(player.getMediaItemAt(i))
                }
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        currentIdx,
                        currentPos
                    )
                )
            }
            val resumingGeneration = queueGeneration
            return future {
                val saved = PlaybackSessionStore.restore(this@MusicService)
                val restoredItems = withContext(Dispatchers.IO) {
                    val ceiling = networkQuality.snapshot().effectiveCeiling.maxBitrateBps
                    saved?.items.orEmpty().map { NetworkAudioQualityPolicy.constrain(it, ceiling) }
                }
                val current = exoPlayer
                // A new local selection or queue mutation supersedes this disk restore.
                // Return the current queue instead of installing an older saved song.
                if (queueGeneration != resumingGeneration) {
                    val items = current?.let { (0 until it.mediaItemCount).map(it::getMediaItemAt) }.orEmpty()
                    MediaSession.MediaItemsWithStartPosition(items,
                        current?.currentMediaItemIndex?.takeIf { it in items.indices } ?: C.INDEX_UNSET,
                        current?.currentPosition ?: C.TIME_UNSET)
                } else MediaSession.MediaItemsWithStartPosition(restoredItems,
                    saved?.index ?: C.INDEX_UNSET, saved?.positionMs ?: C.TIME_UNSET)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootExtras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putBoolean("androidx.media.contentstyle.SUPPORTED", true)
                putInt("androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT", 1) // Grid
                putInt("androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT", 2) // List
                putInt("androidx.media.contentstyle.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE", 1)
                putInt("androidx.media.contentstyle.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE", 2)
            }
            val libraryParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .setOffline(false)
                .setRecent(false)
                .setSuggested(false)
                .build()

            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_MEDIA_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Xtreme Player Library")
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setExtras(rootExtras)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, libraryParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return when (parentId) {
                ROOT_MEDIA_ID -> {
                    // Android Auto Root Browsable Menu Categories
                    val categories = listOf(
                        createBrowsableCategory("category_driving", "Highway Driving Beats", "Optimized acoustic mixes for car cabins"),
                        createBrowsableCategory("category_recommended", "Recommended For You", "Top smart autoplay picks"),
                        createBrowsableCategory("category_punjabi", "Punjabi Hits", "Sidhu, Karan Aujla, Diljit, AP Dhillon"),
                        createBrowsableCategory("category_bollywood", "Bollywood & Hindi", "Arijit Singh, Shreya Ghoshal, Atif Aslam"),
                        createBrowsableCategory("category_pop", "English & Global Pop", "The Weeknd, Dua Lipa, Ed Sheeran"),
                        createBrowsableCategory("category_synthwave", "Synthwave & Electronic", "320kbps High Octane Driving Beats"),
                        createBrowsableCategory("category_chill", "Chillhop & Lo-Fi", "Relaxing driving sounds & lo-fi grooves"),
                        createBrowsableCategory("category_all", "All Songs", "Full catalog of tracks")
                    )
                    Futures.immediateFuture(LibraryResult.ofItemList(categories, params))
                }

                else -> future {
                    val tracks = getTracksForCategory(parentId)
                    withContext(Dispatchers.Default) {
                        LibraryResult.ofItemList(paginate(tracks, page, pageSize).map { it.toMediaItem() }, params)
                    }
                }
            }
        }

        private suspend fun getTracksForCategory(categoryId: String): List<MusicTrack> = withContext(Dispatchers.Default) {
            val records = withContext(Dispatchers.IO) { app.database.musicDao().getAllTracksSync() }
                .filter { realTrack(it.toMusicTrack()) }
                .sortedWith(compareByDescending<TrackEntity> { it.isLiked }.thenByDescending { it.lastPlayedAt }.thenByDescending { it.addedAt })
            val tracks = records.map { it.toMusicTrack() }
            fun has(track: MusicTrack, vararg words: String) = words.any { word ->
                (word.length > 2 && track.genre.contains(word, true)) || track.language.equals(word, true)
            }
            when (categoryId) {
                "category_all" -> tracks
                "category_driving" -> tracks.filter { has(it, "synth", "electronic", "rock", "punjabi") }
                "category_punjabi" -> tracks.filter { has(it, "punjabi", "pa") }
                "category_bollywood" -> tracks.filter { has(it, "bollywood", "hindi", "hi") }
                "category_pop" -> tracks.filter { has(it, "pop", "rock", "english", "en") }
                "category_synthwave" -> tracks.filter { has(it, "synth", "electronic") }
                "category_chill" -> tracks.filter { has(it, "chill", "ambient", "jazz", "lo-fi") }
                "category_recommended" -> {
                    val discoveries = try { app.repository.getYtmQuickPicks(30) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    (discoveries + records.filter { it.isLiked || it.lastPlayedAt > 0L }.map { it.toMusicTrack() })
                        .filter(::realTrack).distinctBy { it.id }
                }
                else -> emptyList()
            }
        }

        private suspend fun searchTracks(query: String, requestedPage: Int = 1): List<MusicTrack> = withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val records = app.database.musicDao().getAllTracksSync()
            val local = withContext(Dispatchers.Default) { records.map { it.toMusicTrack() }.filter {
                realTrack(it) && CatalogNames.score(query, it.title, listOf(it.artist, it.album, it.genre, it.language)) > 0
            }
            }
            val remote = mutableListOf<MusicTrack>()
            for (providerPage in 1..requestedPage.coerceIn(1, 10)) {
                val result = try { app.repository.catalogBrowser.search(query, "Tracks", providerPage) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { break }
                remote.addAll(result.tracks)
                if (result.nextPage == null) break
            }
            for (track in remote) {
                if (app.database.musicDao().getTrackById(track.id) == null)
                    app.database.musicDao().mergeTrackMetadata(TrackEntity.fromMusicTrack(track))
            }
            withContext(Dispatchers.Default) { (local + remote).filter(::realTrack).distinctBy { it.id }
                .sortedByDescending { CatalogNames.score(query, it.title, listOf(it.artist, it.album)) } }
        }

        private fun createBrowsableCategory(id: String, title: String, subtitle: String): MediaItem {
            val extras = Bundle().apply {
                putInt("androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT", 1)
                putInt("androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT", 2)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }

        private fun com.example.data.model.MusicTrack.toMediaItem(): MediaItem {
            val extras = Bundle().apply {
                putInt("androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT", 2)
                putString("media_source", source)
                putBoolean("recommendation_only", recommendationOnly)
                putInt("bitrate", bitrateKbps)
                putString("qualityBadge", qualityBadge)
                putString("genre", genre)
                putLong("duration", durationMs)
                putBoolean("isLiked", isLiked); putBoolean("isLossless", isLossless)
                putString("singers", singers); putString("writer", writer)
                putString("language", language); putString("year", year); putString("isrc", isrc)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setUri(YouTubeAudioStreamService.playbackUri(id, audioUrl))
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(
                    Uri.parse(YouTubeAudioStreamService.playbackUri(id, audioUrl))).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setGenre(genre)
                        .setArtworkUri(Uri.parse(coverUrl))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            return future {
                val tracks = searchTracks(query.trim())
                session.notifySearchResultChanged(browser, query, tracks.size, params)
                LibraryResult.ofVoid(params)
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val needed = ((page.coerceAtLeast(0).toLong() + 1L) * pageSize.coerceIn(1, 100)).coerceAtMost(100L)
            val tracks = searchTracks(query.trim(), ((needed + 9L) / 10L).toInt())
            withContext(Dispatchers.Default) {
                LibraryResult.ofItemList(paginate(tracks, page, pageSize).map { it.toMediaItem() }, params)
            }
        }

        private fun getCategoryById(id: String): MediaItem? {
            return when (id) {
                "category_driving" -> createBrowsableCategory("category_driving", "Highway Driving Beats", "Optimized acoustic mixes for car cabins")
                "category_recommended" -> createBrowsableCategory("category_recommended", "Recommended For You", "Top smart autoplay picks")
                "category_punjabi" -> createBrowsableCategory("category_punjabi", "Punjabi Hits", "Sidhu, Karan Aujla, Diljit, AP Dhillon")
                "category_bollywood" -> createBrowsableCategory("category_bollywood", "Bollywood & Hindi", "Arijit Singh, Shreya Ghoshal, Atif Aslam")
                "category_pop" -> createBrowsableCategory("category_pop", "English & Global Pop", "The Weeknd, Dua Lipa, Ed Sheeran")
                "category_synthwave" -> createBrowsableCategory("category_synthwave", "Synthwave & Electronic", "320kbps High Octane Driving Beats")
                "category_chill" -> createBrowsableCategory("category_chill", "Chillhop & Lo-Fi", "Relaxing driving sounds & lo-fi grooves")
                "category_all" -> createBrowsableCategory("category_all", "All Songs", "Full catalog of tracks")
                else -> null
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId == ROOT_MEDIA_ID) {
                return onGetLibraryRoot(session, browser, null)
            }
            val category = getCategoryById(mediaId)
            if (category != null) {
                return Futures.immediateFuture(LibraryResult.ofItem(category, null))
            }
            return future {
                val track = withContext(Dispatchers.IO) { app.database.musicDao().getTrackById(mediaId)?.toMusicTrack() }
                if (track != null && realTrack(track)) LibraryResult.ofItem(track.toMediaItem(), null)
                else LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_SET_EQUALIZER) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == COMMAND_SET_OUTPUT_DEVICE) {
                val deviceId = args.getInt("device_id", -1)
                return future {
                    com.example.playback.AudioDeviceManager.routeDevice(this@MusicService, deviceId, exoPlayer)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // In-app selections already carry their resolved URI and exact metadata.
            // Install them immediately so prepare/play cannot race a deferred set
            // against playback resumption of the previous saved session.
            if (controller.packageName == packageName && mediaItems.all {
                    !it.mediaId.startsWith("category_") && PlaybackSessionStore.isReal(it)
                }) {
                queueGeneration++
                return Futures.immediateFuture(mediaItems.toMutableList())
            }
            return future {
                val items = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    if (item.mediaId.startsWith("category_")) {
                        items.addAll(getTracksForCategory(item.mediaId).take(100).map { it.toMediaItem() })
                    } else items.add(item)
                }
                val expandingCategory = mediaItems.any { it.mediaId.startsWith("category_") }
                coroutineScope {
                    val slots = kotlinx.coroutines.sync.Semaphore(3)
                    items.map { item -> async(Dispatchers.IO) {
                        slots.acquire()
                        try {
                            if (PlaybackSessionStore.isReal(item) && controller.packageName == packageName) return@async item
                            val known = app.database.musicDao().getTrackById(item.mediaId)?.toMusicTrack()
                            if (known != null && realTrack(known)) {
                                app.repository.resolvePlayableTrack(known.copy(recommendationOnly =
                                    item.mediaMetadata.extras?.getBoolean("recommendation_only", false) == true)).toMediaItem()
                            } else if (PlaybackSessionStore.isReal(item)) item
                            else throw IllegalArgumentException("No real track found for this media ID")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (expandingCategory) null else throw e }
                        finally { slots.release() }
                    } }.awaitAll().filterNotNull().toMutableList().also {
                        if (it.isEmpty() && items.isNotEmpty()) throw IllegalArgumentException("No playable tracks are available in this category")
                    }
                }
            }
        }

    }

    companion object {
        private val _liveSession = kotlinx.coroutines.flow.MutableStateFlow(false)
        internal val liveSession: kotlinx.coroutines.flow.StateFlow<Boolean> = _liveSession
        const val COMMAND_SET_EQUALIZER = "com.example.COMMAND_SET_EQUALIZER"
        const val COMMAND_SET_OUTPUT_DEVICE = "com.example.COMMAND_SET_OUTPUT_DEVICE"
        const val ROOT_MEDIA_ID = "xtreme_media_root"
    }
}
