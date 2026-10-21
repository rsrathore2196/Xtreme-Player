package com.example.service

import com.example.playback.isAudioPipelineFailure

import com.example.performance.AppDispatchers

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
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
    private var queueGeneration = 0L
    private var sabrRecoveryTrackId: String? = null
    private var sabrRecoveryCount = 0
    private var sourceRecoveryJob: Job? = null
    private var jioFallback: JioSaavnPlaybackFallback? = null
    private val networkQuality by lazy { com.example.playback.NetworkQualityMonitor.getInstance(this) }
    private var networkQueueJob: Job? = null
    private var applyingNetworkPolicy = false
    private var policyRevision = 0L
    private var headlessPolicyOwner: String? = null
    private var headlessLosslessAttempts = 0
    private var headlessBackgroundUpgrade = false
    private var headlessTransition: Pair<String, Long>? = null
    private var volumeObserver: android.database.ContentObserver? = null
    private var volumeJob: Job? = null
    private var crossfade: com.example.playback.CrossfadeController? = null
    private var verifiedSignalUri: Uri? = null
    private var verifiedSignalId: String? = null
    private var verifiedSignalFormat: Format? = null
    private var verifiedSignalBitrate = 0
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
            val policy = networkQuality.sourcePolicy.value
            val ceiling = policy.ceiling.maxBitrateBps
            var changed = false
            val start = (player.currentMediaItemIndex + 1).coerceAtLeast(0)
            for (index in start until minOf(player.mediaItemCount, start + 3)) {
                if (index >= player.mediaItemCount) break
                if (index <= player.currentMediaItemIndex) continue
                val before = player.getMediaItemAt(index)
                var after = NetworkAudioQualityPolicy.constrain(before, ceiling)
                val address = before.localConfiguration?.uri ?: Uri.EMPTY
                val extras = before.mediaMetadata.extras
                val checked = NetworkAudioQualityPolicy.preparedFor(before, policy)
                val archive = address.host.orEmpty().let { it == "archive.org" || it.endsWith(".archive.org") }
                // Only the next item gets Archive prefetch; never resolve an entire playlist.
                // New quality settings apply ahead of playback, never to the audible item.
                val needsProvider = !checked && address.scheme !in setOf("file", "content") &&
                    ((ceiling == Int.MAX_VALUE && index == start) ||
                        (policy.preferOpus && index == start) ||
                        YouTubeAudioStreamService.isYouTubeAudio(before.mediaId, address.toString()) ||
                        (archive && ceiling != Int.MAX_VALUE))
                if (needsProvider && networkQuality.activeQualityCeiling.value.connectionType != com.example.playback.NetworkConnectionType.OFFLINE) {
                    val metadata = before.mediaMetadata
                    val selected = MusicTrack(before.mediaId, metadata.title?.toString().orEmpty(),
                        metadata.artist?.toString().orEmpty(), metadata.albumTitle?.toString().orEmpty(),
                        extras?.getLong("duration", 0L) ?: 0L, metadata.artworkUri?.toString().orEmpty(), address.toString(),
                        bitrateKbps = extras?.getInt("bitrate", 0) ?: 0,
                        source = extras?.getString("media_source").orEmpty(), isrc = extras?.getString("isrc").orEmpty(),
                        primaryArtists = extras?.getStringArrayList("primaryArtists")?.toList().orEmpty(),
                        isLossless = extras?.getBoolean("isLossless") == true,
                        recommendationOnly = extras?.getBoolean("recommendation_only") == true)
                    val resolved = try { app.repository.resolvePlayableTrack(selected) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (resolved != null) {
                        val uri = Uri.parse(YouTubeAudioStreamService.playbackUri(resolved.id, resolved.audioUrl))
                        val updated = Bundle(after.mediaMetadata.extras ?: Bundle()).apply {
                            putInt("bitrate", resolved.bitrateKbps); putString("qualityBadge", resolved.qualityBadge)
                            putString("media_source", resolved.source); putBoolean("isLossless", resolved.isLossless)
                            putBoolean("audio_actual", false)
                            putString("audio_policy_tier", policy.ceiling.audioQuality.name)
                            putString("source_policy_key", policy.key)
                            putLong("audio_policy_checked_at", System.currentTimeMillis())
                        }
                        after = after.buildUpon().setUri(uri).setCustomCacheKey(null)
                            .setMimeType(if (uri.lastPathSegment?.endsWith(".m3u8") == true) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
                            .setRequestMetadata(after.requestMetadata.buildUpon().setMediaUri(uri).build())
                            .setMediaMetadata(after.mediaMetadata.buildUpon().setExtras(updated).build()).build()
                    }
                }
                if (after === before) continue
                ensureActive()
                // Navigation/advance or another policy update always wins over this result.
                if (policy.key != networkQuality.sourcePolicy.value.key || index <= player.currentMediaItemIndex ||
                    index >= player.mediaItemCount) continue
                val current = player.getMediaItemAt(index)
                if (current.mediaId != before.mediaId || current.localConfiguration?.uri != before.localConfiguration?.uri) continue
                applyingNetworkPolicy = true
                try { player.replaceMediaItem(index, after); changed = true }
                finally { applyingNetworkPolicy = false }
            }
            if (changed) checkpoint()
        }
    }

    private fun checkpoint() {
        val player = exoPlayer ?: return
        if (player.mediaItemCount == 0) return
        // Immutable timeline capture is O(1). Building/validating its item list is
        // cached by timeline identity on the persistence CPU lane, never in callbacks.
        PlaybackSessionStore.checkpoint(this, player.currentTimeline,
            player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1), player.currentPosition.coerceAtLeast(0L))
    }

    private suspend fun currentQueueForResumption(): MediaSession.MediaItemsWithStartPosition? {
        repeat(3) {
            val player = exoPlayer ?: return null
            val generation = queueGeneration
            val timeline = player.currentTimeline
            if (timeline.isEmpty) return null
            val items = PlaybackSessionStore.validatedTimelineItems(timeline)
            currentCoroutineContext().ensureActive()
            if (exoPlayer !== player || generation != queueGeneration || timeline !== player.currentTimeline) return@repeat
            if (items == null) return null
            return MediaSession.MediaItemsWithStartPosition(items,
                player.currentMediaItemIndex.coerceIn(items.indices), player.currentPosition)
        }
        throw IllegalStateException("Playback queue changed while restoring the session")
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

    /** Reject old sources and read-ahead periods before touching this track's metadata. */
    private fun currentAudioEventItem(player: ExoPlayer,
        event: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime): MediaItem? {
        val item = player.currentMediaItem ?: return null
        val uri = item.localConfiguration?.uri ?: return null
        val timeline = player.currentTimeline
        if (event.timeline.isEmpty || event.windowIndex !in 0 until event.timeline.windowCount ||
            timeline.isEmpty || player.currentMediaItemIndex !in 0 until timeline.windowCount) return null
        val window = event.timeline.getWindow(event.windowIndex, androidx.media3.common.Timeline.Window())
        val currentWindow = timeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        if (window.mediaItem.mediaId != item.mediaId || window.mediaItem.localConfiguration?.uri != uri ||
            window.uid != currentWindow.uid) return null
        event.mediaPeriodId?.let { period ->
            val index = timeline.getIndexOfPeriod(period.periodUid)
            if (index == C.INDEX_UNSET || index != player.currentPeriodIndex) return null
            val current = event.currentMediaPeriodId
            if (current != null && period.windowSequenceNumber != current.windowSequenceNumber) return null
        }
        return item
    }

    private fun audioInputQuality(item: MediaItem, format: Format): com.example.playback.StreamQualityBadge {
        val decoded = com.example.playback.StreamQualityBadge.from(format)
        if (decoded.bitrateKbps > 0 || decoded.codec.isBlank() || decoded.lossless) return decoded
        val uri = item.localConfiguration?.uri ?: return decoded
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        // WebM/Opus often has no bitrate in Media3 Format. Use only the exact
        // resolved representation with a matching decoder codec; never catalog
        // metadata, the selected ceiling, or the previous provider's rate.
        val declaredRate = when {
            YouTubeAudioStreamService.isYouTubeAudio(item.mediaId, uri.toString()) ->
                YouTubeAudioStreamService.bitrateForPlaybackUri(uri, decoded.codec)
            decoded.codec == "AAC" && (host == "saavncdn.com" || host.endsWith(".saavncdn.com")) ->
                SAAVN_AUDIO_RATE.find(uri.path.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            else -> 0
        }
        return if (declaredRate > 0) decoded.copy(bitrateKbps = declaredRate) else decoded
    }

    private fun actualAudioProperties(item: MediaItem, format: Format,
        quality: com.example.playback.StreamQualityBadge, source: String, invalidLossless: Boolean = false): Bundle =
        Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            fun positive(key: String, value: Int) { if (value > 0) putInt(key, value) else remove(key) }
            fun known(key: String, value: String?) { if (!value.isNullOrBlank()) putString(key, value) else remove(key) }
            positive("bitrate", quality.bitrateKbps)
            positive("sample_rate", quality.sampleRateHz)
            positive("bit_depth", if (quality.lossless && !invalidLossless) quality.bitDepth else 0)
            known("audio_codec", quality.codec)
            known("audio_codecs", format.codecs)
            known("audio_mime_type", format.sampleMimeType)
            putString("qualityBadge", quality.label)
            putString("media_source", source)
            if (quality.codec.isNotBlank() || invalidLossless) putBoolean("isLossless", quality.lossless && !invalidLossless)
            else remove("isLossless")
            putBoolean("audio_actual", true)
            if (invalidLossless) putBoolean("lossless_invalid", true) else remove("lossless_invalid")
        }

    @Suppress("DEPRECATION") // Fixed primitive/String extras; presence also distinguishes unknown from zero.
    private fun sameAudioProperties(before: Bundle?, after: Bundle): Boolean = before != null &&
        ACTUAL_AUDIO_KEYS.all { key -> before.containsKey(key) == after.containsKey(key) && before.get(key) == after.get(key) }

    private fun updateActualAudioProperties(player: ExoPlayer, item: MediaItem, audio: Bundle, replaceItem: Boolean = true) {
        val current = player.currentMediaItem ?: return
        if (current.mediaId != item.mediaId || current.localConfiguration?.uri != item.localConfiguration?.uri) return
        val published = mediaLibrarySession?.sessionExtras?.getBundle("active_audio")
        // Session extras are the controller-facing truth: MediaMetadata equality
        // ignores extras values, so comparing only the timeline item can repeat
        // identical replacements after seeks or repeated format callbacks.
        if (published?.getString("track_id") == item.mediaId &&
            published.getString("active_audio_uri") == item.localConfiguration?.uri?.toString() &&
            sameAudioProperties(published, audio)) return
        val changed = replaceItem && !sameAudioProperties(current.mediaMetadata.extras, audio)
        if (changed) player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon()
            .setMediaMetadata(current.mediaMetadata.buildUpon().setExtras(audio).build()).build())
        publishAudioProperties(current, audio)
        if (changed) checkpoint()
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
    /** One transition owner: the connected controller, or this foreground session when headless. */
    private fun reconcileHeadlessPolicy(policy: com.example.playback.PlaybackSourcePolicy, failedUrl: String? = null) {
        if (SabrSessionRegistry.controllerHandlesRecovery?.invoke() == true) {
            sourceRecoveryJob?.cancel()
            headlessPolicyOwner = null
            ++policyRevision
            return
        }
        val player = exoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        val owner = "${item.mediaId}:$uri:${policy.key}:$failedUrl"
        if (headlessPolicyOwner == owner && sourceRecoveryJob?.isActive == true) return
        sourceRecoveryJob?.cancel()
        val revision = ++policyRevision
        headlessTransition = null
        if (headlessPolicyOwner != owner) headlessLosslessAttempts = 0
        headlessPolicyOwner = owner
        headlessBackgroundUpgrade = false
        if (uri.scheme in setOf("file", "content")) return
        if (failedUrl == null && policy.maximum && (!policy.allowsArchive || !player.isPlaying ||
            uri.host.orEmpty().let { it == "archive.org" || it.endsWith(".archive.org") } || headlessLosslessAttempts >= 3)) return
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        val track = MusicTrack(item.mediaId, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
            metadata.albumTitle?.toString().orEmpty(), player.duration.takeIf { it > 0 } ?: extras?.getLong("duration", 0L) ?: 0L,
            metadata.artworkUri?.toString().orEmpty(), uri.toString(), bitrateKbps = extras?.getInt("bitrate") ?: 0,
            qualityBadge = extras?.getString("qualityBadge").orEmpty(), source = extras?.getString("media_source").orEmpty(),
            primaryArtists = extras?.getStringArrayList("primaryArtists")?.toList().orEmpty(),
            isrc = extras?.getString("isrc").orEmpty())
        if (failedUrl == null && !policy.maximum && policy.acceptsRemoteTrack(track) && (!policy.preferOpus ||
            YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl))) return
        fun owns(expected: Uri = uri) = revision == policyRevision && networkQuality.sourcePolicy.value.key == policy.key &&
            player.currentMediaItem?.mediaId == item.mediaId && player.currentMediaItem?.localConfiguration?.uri == expected &&
            SabrSessionRegistry.controllerHandlesRecovery?.invoke() != true
        sourceRecoveryJob = serviceScope.launch {
            var activatedUri: Uri? = null
            try {
                delay(180L)
                val resolved = if (policy.maximum && failedUrl == null) {
                    headlessBackgroundUpgrade = true
                    delay(3_000L) // Stable audible playback, not a startup admission delay.
                    if (!owns() || !player.isPlaying) return@launch
                    headlessLosslessAttempts++
                    withContext(AppDispatchers.Io) { app.repository.findLosslessUpgrade(track) } ?: return@launch
                } else withContext(AppDispatchers.Io) { app.repository.resolvePlayableTrack(track,
                    forceRefresh = failedUrl != null, failedUrl = failedUrl) }
                if (!owns() || resolved.audioUrl == track.audioUrl) return@launch
                val replacementExtras = Bundle(extras ?: Bundle()).apply {
                    putLong("jio_start_deadline", 0L); remove("jio_youtube_fallback")
                    putBoolean("audio_actual", false); putBoolean("isLossless", resolved.isLossless)
                    putBoolean("source_transition_pending", true)
                    putBoolean("lossless_upgrade_pending", resolved.isLossless)
                    putString("media_source", resolved.source); putInt("bitrate", resolved.bitrateKbps)
                    putString("qualityBadge", resolved.qualityBadge); putString("source_policy_key", policy.key)
                    putStringArrayList("primaryArtists", ArrayList(resolved.primaryArtists))
                }
                val replacement = item.buildUpon().setUri(resolved.audioUrl).setMimeType(null)
                    .setMediaMetadata(metadata.buildUpon().setExtras(replacementExtras).build()).build()
                if (resolved.isLossless) {
                    val sameCode = withContext(AppDispatchers.CpuBounded) {
                        com.example.data.repository.RecordingIdentity.isrc(track.isrc)?.let {
                            it == com.example.data.repository.RecordingIdentity.isrc(resolved.isrc)
                        } == true
                    }
                    val prepared = com.example.playback.LosslessStreamPrebuffer.prepare(this@MusicService, replacement,
                        track.durationMs, currentPosition = { player.currentPosition }, stillCurrent = { owns() && player.isPlaying },
                        durationToleranceMs = if (sameCode) 8_000L else 5_000L, allowUnknownDuration = sameCode)
                    if (!owns()) return@launch
                    if (prepared == null || !prepared.covers(player.currentPosition)) {
                        com.example.playback.LosslessDiagnostics.prebufferRejected(track.id, prepared != null)
                        app.repository.rejectLosslessUpgrade(track, resolved.audioUrl)
                        return@launch
                    }
                } else if (!com.example.playback.LosslessStreamPrebuffer.warmSource(this@MusicService, replacement))
                    throw java.io.IOException("Source preparation failed")
                if (!owns()) return@launch
                val index = player.currentMediaItemIndex
                val position = player.currentPosition.coerceAtLeast(0L)
                val resume = player.playWhenReady
                val targetUri = Uri.parse(resolved.audioUrl)
                activatedUri = targetUri
                headlessTransition = resolved.audioUrl to position
                headlessBackgroundUpgrade = false
                player.replaceMediaItem(index, replacement); player.seekTo(index, position)
                player.prepare(); player.playWhenReady = resume
                val ready = withTimeoutOrNull(8_000L) {
                    while (owns(targetUri)) {
                        val format = verifiedSignalFormat
                        if (player.playbackState == Player.STATE_READY && verifiedSignalUri == targetUri && format != null) {
                            val codec = YouTubeAudioStreamService.displayCodec(format.sampleMimeType, format.codecs)
                            val expected = if (resolved.isLossless) com.example.playback.LosslessStreamPrebuffer.audioInfo(format) != null
                                else if (policy.maximum) codec in setOf("AAC", "Opus", "Vorbis")
                                else codec == (if (YouTubeAudioStreamService.isYouTubeAudio(resolved.id, resolved.audioUrl)) "Opus" else "AAC")
                            val rate = verifiedSignalBitrate
                            if (expected && (policy.maximum || rate.toLong() * 1000 <= policy.ceiling.maxBitrateBps))
                                return@withTimeoutOrNull true
                        }
                        if (player.playerError != null) break
                        delay(100L)
                    }
                    false
                } == true
                if (!ready && owns(targetUri) && player.playbackState == Player.STATE_READY &&
                    !player.playWhenReady && player.playerError == null) return@launch
                if (!ready && owns(targetUri)) {
                    com.example.playback.LosslessDiagnostics.record(track.id,
                        com.example.playback.LosslessDiagnostics.Reason.ACTIVATION_FAILED, "headless-policy")
                    if (resolved.isLossless) app.repository.rejectLosslessUpgrade(track, resolved.audioUrl)
                    if (failedUrl == null && policy.acceptsRemoteTrack(track)) {
                        val positionNow = com.example.playback.losslessRecoveryPosition(
                            headlessTransition?.second ?: position, player.currentPosition, false)
                        val resumeNow = player.playWhenReady
                        val restored = item.buildUpon().setMediaMetadata(metadata.buildUpon().setExtras(
                            Bundle(extras ?: Bundle()).apply {
                                putLong("jio_start_deadline", 0L); remove("jio_youtube_fallback")
                            }).build()).build()
                        player.replaceMediaItem(index, restored); player.seekTo(index, positionNow)
                        player.prepare(); player.playWhenReady = resumeNow
                    } else { player.pause(); player.stop() }
                }
                checkpoint()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(activatedUri ?: uri) && (failedUrl != null || !policy.acceptsRemoteTrack(track))) {
                    player.pause(); player.stop()
                    com.example.playback.LosslessDiagnostics.record(track.id,
                        com.example.playback.LosslessDiagnostics.Reason.POLICY_SOURCE_UNAVAILABLE, "headless-policy")
                }
            } finally {
                if (revision == policyRevision) { headlessTransition = null; headlessBackgroundUpgrade = false }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch(AppDispatchers.Io) { com.example.playback.AudioDeviceManager.init(this@MusicService) }

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
        fun audioRenderersFactory(reportSignalPath: Boolean) = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                com.example.playback.BitPerfectAudioSink(context, reportSignalPath = reportSignalPath,
                    allowDirect = { reportSignalPath && crossfade?.enabled != true })
            override fun buildAudioRenderers(context: android.content.Context, extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                audioSink: androidx.media3.exoplayer.audio.AudioSink, eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener, out: java.util.ArrayList<Renderer>) {
                // Request native integer decoding only for an exact supported USB profile.
                // All other routes retain Media3's high-precision float preference.
                out.add(object : androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(context, codecAdapterFactory,
                    mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) {
                    override fun getMediaFormat(format: Format, codecMimeType: String, codecMaxInputSize: Int,
                        codecOperatingRate: Float): android.media.MediaFormat =
                        super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate).apply {
                            (audioSink as? com.example.playback.BitPerfectAudioSink)?.preferredPcmEncoding(format)?.let {
                                setInteger(android.media.MediaFormat.KEY_PCM_ENCODING, it)
                            }
                        }
                    override fun onOutputFormatChanged(format: Format, mediaFormat: android.media.MediaFormat?) {
                        (audioSink as? com.example.playback.BitPerfectAudioSink)?.sourceFormat(format)
                        super.onOutputFormatChanged(format, mediaFormat)
                    }
                })
            }
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
            .setEnableAudioFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(true)
        val audioOnlyRenderersFactory = audioRenderersFactory(reportSignalPath = true)

        // 4. High-Fidelity 320kbps Audio Pipeline with Caching
        val cacheDataSourceFactory = MusicCache.createCacheDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheDataSourceFactory)

        val trackSelector = DefaultTrackSelector(this)
        // Required application-looper work. Trace it rather than moving Media3
        // construction onto IO; paused UI restoration no longer cold-binds us.
        val playerInitialization = com.example.performance.BackgroundWorkCoordinator.trace("MusicService ExoPlayer initialization")
        val player = try {
            ExoPlayer.Builder(this, audioOnlyRenderersFactory)
                .setTrackSelector(trackSelector)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekBackIncrementMs(10000L)
                .setSeekForwardIncrementMs(10000L)
                .build()
        } finally { playerInitialization.close() }

        exoPlayer = player
        crossfade = com.example.playback.CrossfadeController(player, serviceScope,
            canMix = { !com.example.playback.AudioSignalPath.state.value.directApproved },
            createTail = {
                // A cache-backed outgoing tail only; no MediaSession, focus owner,
                // provider search or competing signal-path publication is created.
                ExoPlayer.Builder(this, audioRenderersFactory(reportSignalPath = false))
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setTrackSelector(DefaultTrackSelector(this).apply { parameters = trackSelector.parameters })
                    .setAudioAttributes(audioAttributes, false)
                    .setHandleAudioBecomingNoisy(false)
                    .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(1_000, 8_000, 500, 500).build())
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .build().apply {
                        skipSilenceEnabled = player.skipSilenceEnabled
                        val selected = com.example.playback.AudioDeviceManager.availableDevices.value.firstOrNull { it.isSelected }
                        if (selected != null) {
                            val manager = getSystemService(android.media.AudioManager::class.java)
                            val output = manager?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.id == selected.id }
                            if (output != null) setPreferredAudioDevice(output)
                        }
                    }
            })
        serviceScope.launch {
            com.example.data.local.OtherSettingsPreferences.observePlayerExperience(this@MusicService).collect {
                if (com.example.playback.PsychoacousticAudioProcessor.spatialEnabled != it.spatialAudio ||
                    player.skipSilenceEnabled != it.skipSilence) crossfade?.cancelTransition()
                com.example.playback.PsychoacousticAudioProcessor.spatialEnabled = it.spatialAudio
                player.skipSilenceEnabled = it.skipSilence
                crossfade?.setEnabled(it.crossfade)
            }
        }
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        fun publishVolume() {
            volumeJob?.cancel()
            volumeJob = serviceScope.launch(AppDispatchers.Io) {
                val current = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
                val max = audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
                ensureActive()
                com.example.playback.AudioSignalPath.update { it.copy(systemVolume = current, systemVolumeMax = max) }
            }
        }
        volumeObserver = object : android.database.ContentObserver(android.os.Handler(mainLooper)) {
            override fun onChange(selfChange: Boolean) { publishVolume() }
        }.also { contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, it) }
        publishVolume()
        serviceScope.launch {
            networkQuality.sourcePolicy.collect { policy ->
                // Updating ABR constraints cannot re-prepare a progressive source.
                applyAudioCeiling(trackSelector, player)
                reconcileHeadlessPolicy(policy)
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
                val item = currentAudioEventItem(player, eventTime) ?: return
                val uri = item.localConfiguration?.uri ?: return
                val quality = audioInputQuality(item, format)
                val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
                val archive = host == "archive.org" || host.endsWith(".archive.org")
                if (archive && item.mediaMetadata.extras?.getBoolean("isLossless") == true) {
                    val before = item.mediaMetadata.extras
                    val audio = com.example.playback.LosslessStreamPrebuffer.audioInfo(format, quality.bitrateKbps)
                    if (audio == null) {
                        if (format.sampleMimeType.isNullOrBlank() ||
                            (format.sampleMimeType in setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW) &&
                                (format.sampleRate <= 0 || format.channelCount <= 0))) {
                            com.example.playback.LosslessDiagnostics.record(item.mediaId,
                                com.example.playback.LosslessDiagnostics.Reason.METADATA_INCOMPLETE, "active-format")
                            return
                        }
                        com.example.playback.LosslessDiagnostics.record(item.mediaId,
                            com.example.playback.LosslessDiagnostics.Reason.DECODER_UNSUPPORTED, "active-format")
                        updateActualAudioProperties(player, item,
                            actualAudioProperties(item, format, quality, "INTERNET_ARCHIVE", invalidLossless = true),
                            replaceItem = false)
                        if (SabrSessionRegistry.controllerHandlesRecovery?.invoke() != true && sourceRecoveryJob?.isActive != true)
                            reconcileHeadlessPolicy(networkQuality.sourcePolicy.value, failedUrl = uri.toString())
                        if (before?.getBoolean("lossless_upgrade_pending") != true) {
                            verifiedSignalUri = uri; verifiedSignalId = item.mediaId
                            verifiedSignalFormat = format; verifiedSignalBitrate = 0
                            publishVerifiedSignal()
                        }
                        return
                    }
                    verifiedSignalUri = uri; verifiedSignalId = item.mediaId
                    verifiedSignalFormat = format; verifiedSignalBitrate = audio.bitrateKbps
                    publishVerifiedSignal()
                    updateActualAudioProperties(player, item,
                        actualAudioProperties(item, format, quality, "INTERNET_ARCHIVE"))
                    return
                }
                val mediaSource = when {
                    archive -> "INTERNET_ARCHIVE"
                    YouTubeAudioStreamService.isYouTubeAudio(item.mediaId, uri.toString()) -> "YOUTUBE"
                    uri.scheme in setOf("file", "content") -> "LOCAL"
                    host == "saavncdn.com" || host.endsWith(".saavncdn.com") || item.mediaId.startsWith("online_") -> "JIOSAAVN"
                    else -> item.mediaMetadata.extras?.getString("media_source").orEmpty().ifBlank { "STREAM" }
                }
                verifiedSignalUri = uri; verifiedSignalId = item.mediaId
                verifiedSignalFormat = format; verifiedSignalBitrate = quality.bitrateKbps
                publishVerifiedSignal()
                updateActualAudioProperties(player, item, actualAudioProperties(item, format, quality, mediaSource))
                val policy = networkQuality.sourcePolicy.value
                val invalidRate = quality.bitrateKbps.toLong() * 1000 > policy.ceiling.maxBitrateBps
                val invalidCodec = mediaSource == "YOUTUBE" && quality.codec.isNotBlank() &&
                    (!policy.preferOpus || !quality.codec.equals("opus", true))
                if (!policy.maximum && (invalidRate || invalidCodec) &&
                    SabrSessionRegistry.controllerHandlesRecovery?.invoke() != true && sourceRecoveryJob?.isActive != true)
                    reconcileHeadlessPolicy(policy, failedUrl = uri.toString())
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
            } }, changed = { checkpoint() })
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    queueGeneration++
                    scheduleUpcomingCeilings()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != verifiedSignalId) {
                    verifiedSignalUri = null
                    verifiedSignalId = null
                    verifiedSignalFormat = null
                    verifiedSignalBitrate = 0
                }
                publishSignalSource(mediaItem)
                if (sabrRecoveryTrackId != mediaItem?.mediaId) {
                    sourceRecoveryJob?.cancel()
                    sabrRecoveryTrackId = mediaItem?.mediaId
                    sabrRecoveryCount = 0
                }
                applyAudioCeiling(trackSelector, player)
                scheduleUpcomingCeilings()
                val policy = networkQuality.sourcePolicy.value
                if (mediaItem != null && mediaItem.mediaMetadata.extras?.getString("source_policy_key") != policy.key)
                    reconcileHeadlessPolicy(policy)
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                applyAudioCeiling(trackSelector, player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && headlessTransition == null && networkQuality.sourcePolicy.value.maximum)
                    reconcileHeadlessPolicy(networkQuality.sourcePolicy.value)
                checkpointJob?.cancel()
                checkpoint()
                if (isPlaying) checkpointJob = serviceScope.launch {
                    while (isActive) { delay(15_000L); checkpoint() }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                publishSignalSource(player.currentMediaItem)
                com.example.playback.AudioSignalPath.update { it.copy(buffering = player.playbackState == Player.STATE_BUFFERING,
                    playbackStatus = when {
                        player.playbackState == Player.STATE_BUFFERING -> "Buffering"
                        player.playbackState == Player.STATE_ENDED -> "Ended"
                        player.isPlaying -> "Playing"
                        player.playbackState == Player.STATE_READY -> "Paused"
                        else -> "Idle"
                    }) }
                jioFallback?.update()
                val queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)
                if (queueChanged || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    checkpoint()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    publishVerifiedSignal()
                    val sessionId = player.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0) {
                        com.example.playback.AudioEffectsManager.attachAudioSession(sessionId)
                    }
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                val pending = headlessTransition
                if (pending != null && reason == Player.DISCONTINUITY_REASON_SEEK &&
                    newPosition.mediaItem?.localConfiguration?.uri?.toString() == pending.first)
                    headlessTransition = pending.first to newPosition.positionMs.coerceAtLeast(0L)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (headlessBackgroundUpgrade) {
                    sourceRecoveryJob?.cancel(); sourceRecoveryJob = null; headlessBackgroundUpgrade = false
                }
                if (error.isAudioPipelineFailure()) {
                    jioFallback?.cancel()
                    sourceRecoveryJob?.cancel()
                    sourceRecoveryJob = null
                    android.util.Log.w("MusicService", "Audio pipeline failure; keeping the selected source", error)
                    return
                }
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
                        checkpoint()
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
                            primaryArtists = extras?.getStringArrayList("primaryArtists")?.toList().orEmpty(),
                            isrc = extras?.getString("isrc").orEmpty(),
                            recommendationOnly = extras?.getBoolean("recommendation_only", false) == true)
                        sourceRecoveryJob = serviceScope.launch {
                            try {
                                val resolved = withContext(AppDispatchers.Io) {
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
                                checkpoint()
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

    private fun publishSignalSource(item: MediaItem?) {
        if (item == null) return
        val uri = item.localConfiguration?.uri ?: return
        if (item.mediaMetadata.extras?.getBoolean("lossless_upgrade_pending") == true ||
            item.mediaMetadata.extras?.getBoolean("source_transition_pending") == true) return
        publishSignalSource(item, uri)
    }

    private fun publishSignalSource(item: MediaItem, uri: Uri, format: Format? = null, bitrate: Int = 0) {
        val host = uri.host.orEmpty()
        val provider = when {
            YouTubeAudioStreamService.isYouTubeAudio(item.mediaId, uri.toString()) -> "YouTube Music"
            host == "archive.org" || host.endsWith(".archive.org") -> "Internet Archive"
            host == "saavncdn.com" || host.endsWith(".saavncdn.com") -> "JioSaavn"
            uri.scheme in setOf("file", "content") -> "Local audio"
            else -> item.mediaMetadata.extras?.getString("media_source").orEmpty().ifBlank { "Audio stream" }
        }
        if (format == null) com.example.playback.AudioSignalPath.source(item.mediaId, provider)
        else com.example.playback.AudioSignalPath.verifiedSource(item.mediaId, provider, format, bitrate)
    }

    private fun publishVerifiedSignal() {
        val player = exoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        val format = verifiedSignalFormat ?: return
        if (player.playbackState != Player.STATE_READY || item.mediaId != verifiedSignalId || uri != verifiedSignalUri) return
        publishSignalSource(item, uri, format, verifiedSignalBitrate)
    }

    override fun onDestroy() {
        volumeObserver?.let(contentResolver::unregisterContentObserver)
        volumeObserver = null
        _liveSession.value = false
        checkpoint()
        checkpointJob?.cancel()
        crossfade?.close(); crossfade = null
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
        com.example.playback.AudioSignalPath.reset()
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
            val resumingGeneration = queueGeneration
            return future {
                currentQueueForResumption()?.let { return@future it }
                val saved = PlaybackSessionStore.restore(this@MusicService)
                val ceiling = withContext(AppDispatchers.Io) { networkQuality.snapshot().effectiveCeiling.maxBitrateBps }
                val restoredItems = withContext(AppDispatchers.CpuBounded) {
                    saved?.items.orEmpty().map { NetworkAudioQualityPolicy.constrain(it, ceiling) }
                }
                // A new local selection or queue mutation supersedes this disk restore.
                // Return the current queue instead of installing an older saved song.
                if (queueGeneration != resumingGeneration) {
                    currentQueueForResumption() ?: MediaSession.MediaItemsWithStartPosition(
                        emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
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
                    withContext(AppDispatchers.CpuBounded) {
                        LibraryResult.ofItemList(paginate(tracks, page, pageSize).map { it.toMediaItem() }, params)
                    }
                }
            }
        }

        private suspend fun getTracksForCategory(categoryId: String): List<MusicTrack> = withContext(AppDispatchers.CpuBounded) {
            val records = withContext(AppDispatchers.Database) { app.database.musicDao().getAllTracksSync() }
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

        private suspend fun searchTracks(query: String, requestedPage: Int = 1): List<MusicTrack> = withContext(AppDispatchers.Io) {
            if (query.isBlank()) return@withContext emptyList()
            val records = withContext(AppDispatchers.Database) { app.database.musicDao().getAllTracksSync() }
            val local = withContext(AppDispatchers.CpuBounded) { records.map { it.toMusicTrack() }.filter {
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
            withContext(AppDispatchers.CpuBounded) { (local + remote).filter(::realTrack).distinctBy { it.id }
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
                putStringArrayList("primaryArtists", ArrayList(primaryArtists))
                putString("language", language); putString("year", year); putString("isrc", isrc)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setUri(YouTubeAudioStreamService.playbackUri(id, audioUrl))
                .setMimeType(com.example.data.remote.StreamingAddons.mime(audioUrl))
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
            withContext(AppDispatchers.CpuBounded) {
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
                val record = withContext(AppDispatchers.Database) { app.database.musicDao().getTrackById(mediaId) }
                withContext(AppDispatchers.CpuBounded) {
                    val track = record?.toMusicTrack()
                    if (track != null && realTrack(track)) LibraryResult.ofItem(track.toMediaItem(), null)
                    else LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
                }
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
                    crossfade?.outputRouteChanging()
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
                return Futures.immediateFuture(mediaItems)
            }
            return future { withContext(AppDispatchers.CpuBounded) {
                val items = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    if (item.mediaId.startsWith("category_")) {
                        val tracks = getTracksForCategory(item.mediaId)
                        items.addAll(withContext(AppDispatchers.CpuBounded) {
                            tracks.take(100).map { it.toMediaItem() }
                        })
                    } else items.add(item)
                }
                val expandingCategory = mediaItems.any { it.mediaId.startsWith("category_") }
                coroutineScope {
                    val slots = kotlinx.coroutines.sync.Semaphore(3)
                    items.map { item -> async(AppDispatchers.Io) {
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
            } }
        }

    }

    companion object {
        private val SAAVN_AUDIO_RATE = Regex("_(320|160|96|48)\\.(?:mp4|m4a)$", RegexOption.IGNORE_CASE)
        private val ACTUAL_AUDIO_KEYS = arrayOf("audio_codec", "audio_codecs", "audio_mime_type", "bitrate", "sample_rate",
            "bit_depth", "isLossless", "media_source", "audio_actual", "qualityBadge", "lossless_invalid")
        private val _liveSession = kotlinx.coroutines.flow.MutableStateFlow(false)
        internal val liveSession: kotlinx.coroutines.flow.StateFlow<Boolean> = _liveSession
        const val COMMAND_SET_EQUALIZER = "com.example.COMMAND_SET_EQUALIZER"
        const val COMMAND_SET_OUTPUT_DEVICE = "com.example.COMMAND_SET_OUTPUT_DEVICE"
        const val ROOT_MEDIA_ID = "xtreme_media_root"
    }
}
