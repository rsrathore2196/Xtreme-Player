package com.example.service

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.playback.isAudioPipelineFailure
import kotlinx.coroutines.*

/** One service-owned watchdog, independent of UI lifecycle. All Player access stays on its looper. */
internal class JioSaavnPlaybackFallback(
    private val player: Player,
    private val scope: CoroutineScope,
    private val resolve: suspend (MusicTrack, String) -> MusicTrack,
    private val status: (Bundle) -> Unit,
    private val changed: () -> Unit
) {
    private var key = ""
    private var generation = 0L
    private var deadline = 0L
    private var attempted = false
    private var applying = false
    private var lastStatus = ""
    private var recordingId = ""
    private var hasPlayed = false
    private var selectionDeadline = 0L
    private var watchdog: Job? = null
    private var recovery: Job? = null

    companion object {
        fun handles(item: MediaItem?): Boolean {
            // Library/imported tracks and verified cross-provider matches can keep
            // their original IDs. Detect the active Jio transport, not an ID prefix.
            if (item == null) return false
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: return false
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
            return uri.scheme in setOf("https", "http") && !YouTubeAudioStreamService.isYouTubeAudio(item.mediaId, uri.toString()) &&
                host != "archive.org" && !host.endsWith(".archive.org") &&
                (host == "saavncdn.com" || host.endsWith(".saavncdn.com") ||
                    item.mediaMetadata.extras?.getString("media_source").equals("JIOSAAVN", true))
        }
    }
    private fun notifyStatus(bundle: Bundle) {
        val signature = "${bundle.getString("track_id")}|${bundle.getString("state")}|${bundle.getString("uri")}|${bundle.getString("source_uri")}"
        if (lastStatus == signature) return
        lastStatus = signature
        status(bundle)
    }

    private fun currentKey() = player.currentMediaItem?.let {
        "${it.mediaId}|${it.localConfiguration?.uri}|${player.currentMediaItemIndex}|${it.mediaMetadata.extras?.getLong("jio_start_deadline", 0L)}"
    }.orEmpty()

    fun update() {
        if (applying) return
        if (player.playerError?.isAudioPipelineFailure() == true) {
            cancel()
            return
        }
        val item = player.currentMediaItem
        val nextKey = currentKey()
        if (key != nextKey) {
            cancel(); key = nextKey; attempted = false
            val requestedDeadline = item?.mediaMetadata?.extras?.getLong("jio_start_deadline", 0L) ?: 0L
            if (recordingId != item?.mediaId || requestedDeadline > 0L && requestedDeadline != selectionDeadline) hasPlayed = false
            recordingId = item?.mediaId.orEmpty()
            selectionDeadline = requestedDeadline
            // Quality changes/seeks of an already playing recording get a fresh
            // buffering window, not the old initial-selection deadline.
            deadline = if (hasPlayed) 0L else requestedDeadline
            if (handles(item)) notifyStatus(Bundle().apply { putString("track_id", item?.mediaId); putString("state", "idle") })
        }
        val fallback = item?.mediaMetadata?.extras?.getBoolean("jio_youtube_fallback") == true
        if (!handles(item) && !fallback) { watchdog?.cancel(); watchdog = null; return }
        if (player.isPlaying) {
            hasPlayed = true
            watchdog?.cancel(); watchdog = null
            // Keep a Jio stream that recovered while the metadata lookup was pending.
            if (handles(item)) { recovery?.cancel(); recovery = null }
            deadline = 0L
            if (handles(item)) notifyStatus(Bundle().apply { putString("track_id", item?.mediaId); putString("state", "ready") })
            return
        }
        if (!player.playWhenReady) {
            watchdog?.cancel(); watchdog = null
            if (attempted) deadline = 0L
            recovery?.cancel(); recovery = null
            return
        }
        if (recovery?.isActive == true || watchdog?.isActive == true || player.playbackState != Player.STATE_BUFFERING) return
        attempted = true
        val token = generation
        val originalKey = key
        val timeout = if (fallback) 20_000L else 6_000L
        if (deadline <= 0L || fallback) deadline = SystemClock.elapsedRealtime() + timeout
        watchdog = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceIn(0L, timeout))
            watchdog = null
            if (generation != token || currentKey() != originalKey || player.isPlaying || !player.playWhenReady ||
                player.playerError?.isAudioPipelineFailure() == true) return@launch
            if (fallback) fail(item, "The recording is unavailable from both sources.") else recover()
        }
    }

    fun onError(): Boolean {
        if (!handles(player.currentMediaItem)) return false
        recover()
        return true
    }
    private fun recover() {
        if (recovery?.isActive == true) return
        val item = player.currentMediaItem?.takeIf(::handles) ?: return
        watchdog?.cancel(); watchdog = null
        val token = generation
        val originalKey = currentKey()
        val originalUri = item.localConfiguration?.uri ?: return
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        val track = MusicTrack(item.mediaId, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
            metadata.albumTitle?.toString().orEmpty(), extras?.getLong("duration", 0L) ?: 0L,
            metadata.artworkUri?.toString().orEmpty(), originalUri.toString(), source = "JIOSAAVN",
            singers = extras?.getString("singers").orEmpty(), language = extras?.getString("language") ?: "Unknown",
            isrc = extras?.getString("isrc").orEmpty(), recommendationOnly = extras?.getBoolean("recommendation_only", false) == true)
        notifyStatus(Bundle().apply { putString("track_id", item.mediaId); putString("state", "resolving"); putString("source_uri", originalUri.toString()) })
        recovery = scope.launch {
            try {
                val resolved = withTimeoutOrNull(40_000L) { withContext(Dispatchers.IO) { resolve(track, originalUri.toString()) } }
                    ?: throw java.io.IOException("YouTube Music could not resolve this recording.")
                if (generation != token || currentKey() != originalKey || player.isPlaying) return@launch
                val index = player.currentMediaItemIndex
                val position = player.currentPosition.coerceAtLeast(0L)
                val resume = player.playWhenReady
                val uri = Uri.parse(resolved.audioUrl)
                val replacement = item.buildUpon().setUri(uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .setMimeType(if (uri.lastPathSegment?.endsWith(".m3u8") == true) MimeTypes.APPLICATION_M3U8 else null)
                    .setMediaMetadata(metadata.buildUpon().setExtras(Bundle(extras ?: Bundle()).apply {
                        putBoolean("jio_youtube_fallback", true); putLong("jio_start_deadline", 0L)
                        putString("media_source", resolved.source); putBoolean("isLossless", false)
                        putInt("bitrate", resolved.bitrateKbps); putString("qualityBadge", resolved.qualityBadge)
                        putBoolean("audio_actual", false)
                    }).build()).build()
                applying = true
                try {
                    player.replaceMediaItem(index, replacement)
                    player.seekTo(index, position); player.prepare(); player.playWhenReady = resume
                    notifyStatus(Bundle().apply {
                        putString("track_id", item.mediaId); putString("state", "replaced")
                        putString("uri", resolved.audioUrl); putString("source", resolved.source)
                        putInt("bitrate", resolved.bitrateKbps); putString("qualityBadge", resolved.qualityBadge)
                    })
                    changed()
                } finally { applying = false }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == token && currentKey() == originalKey) fail(item, e.message ?: "Playback is unavailable.")
            } finally {
                if (generation == token) { recovery = null; update() }
            }
        }
    }
    private fun fail(item: MediaItem, message: String) {
        notifyStatus(Bundle().apply { putString("track_id", item.mediaId); putString("state", "failed"); putString("message", message); putString("source_uri", item.localConfiguration?.uri?.toString()) })
        val resume = player.playWhenReady
        if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.playWhenReady = resume }
        else { player.stop(); player.playWhenReady = false }
    }
    fun cancel() {
        generation++
        watchdog?.cancel(); watchdog = null
        recovery?.cancel(); recovery = null
    }
}
