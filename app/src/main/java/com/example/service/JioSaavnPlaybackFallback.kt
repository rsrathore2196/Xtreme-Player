package com.example.service

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.data.remote.YouTubeAudioStreamService
import com.example.playback.isAudioPipelineFailure
import kotlinx.coroutines.*

/** Service-owned buffering watchdog. Source replacement belongs to the active transition owner. */
internal class JioSaavnPlaybackFallback(
    private val player: Player,
    private val scope: CoroutineScope,
    private val requestRecovery: (String, String) -> Unit,
    private val status: (Bundle) -> Unit,
    private val requestOutputRecovery: (String, String) -> Boolean = { _, _ -> false }
) {
    private var key = ""
    private var generation = 0L
    private var deadline = 0L
    private var attempted = false
    private var lastStatus = ""
    private var recordingId = ""
    private var hasPlayed = false
    private var selectionDeadline = 0L
    private var watchdog: Job? = null
    private var audioConfirmed = false
    val audioStarted: Boolean get() = audioConfirmed && currentKey() == key

    companion object {
        fun handles(item: MediaItem?): Boolean {
            // Library/imported tracks and verified cross-provider matches can keep
            // their original IDs. Detect the active Jio transport, not an ID prefix.
            if (item == null) return false
            if (item.mediaMetadata.extras?.getBoolean("source_transition_pending") == true) return false
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
        if (player.playerError?.isAudioPipelineFailure() == true) {
            cancel()
            return
        }
        val item = player.currentMediaItem
        val nextKey = currentKey()
        if (key != nextKey) {
            cancel(); key = nextKey; attempted = false; audioConfirmed = false
            val requestedDeadline = item?.mediaMetadata?.extras?.getLong("jio_start_deadline", 0L) ?: 0L
            if (recordingId != item?.mediaId || requestedDeadline > 0L && requestedDeadline != selectionDeadline) hasPlayed = false
            recordingId = item?.mediaId.orEmpty()
            selectionDeadline = requestedDeadline
            // Quality changes/seeks of an already playing recording get a fresh
            // buffering window, not the old initial-selection deadline.
            deadline = if (hasPlayed) 0L else requestedDeadline
            if (handles(item)) notifyStatus(Bundle().apply { putString("track_id", item?.mediaId); putString("state", "idle") })
        }
        if (!handles(item)) { watchdog?.cancel(); watchdog = null; return }
        if (player.playbackState == Player.STATE_IDLE) audioConfirmed = false
        if (player.isPlaying && audioConfirmed) {
            hasPlayed = true
            watchdog?.cancel(); watchdog = null
            deadline = 0L
            if (handles(item)) notifyStatus(Bundle().apply { putString("track_id", item?.mediaId); putString("state", "ready") })
            return
        }
        if (!player.playWhenReady) {
            watchdog?.cancel(); watchdog = null
            if (attempted) deadline = 0L
            return
        }
        if (attempted || watchdog?.isActive == true ||
            player.playbackState !in setOf(Player.STATE_BUFFERING, Player.STATE_READY)) return
        val token = generation
        val originalKey = key
        val timeout = 6_000L
        if (deadline <= 0L) deadline = SystemClock.elapsedRealtime() + timeout
        watchdog = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceIn(0L, timeout))
            watchdog = null
            if (generation != token || currentKey() != originalKey || player.isPlaying && audioConfirmed || !player.playWhenReady ||
                player.playerError?.isAudioPipelineFailure() == true) return@launch
            // READY/isPlaying can precede the first advancing AudioTrack sample.
            // Repair a stalled output before re-extracting an otherwise valid source.
            if (player.playbackState == Player.STATE_READY && !audioConfirmed &&
                requestOutputRecovery(recordingId, player.currentMediaItem?.localConfiguration?.uri.toString())) {
                deadline = 0L
                return@launch
            }
            recover()
        }
    }

    fun onAudioPositionAdvancing() {
        if (!handles(player.currentMediaItem)) return
        update()
        audioConfirmed = true
        hasPlayed = true
        update()
    }

    fun onError(): Boolean {
        if (!handles(player.currentMediaItem)) return false
        audioConfirmed = false
        recover()
        return true
    }
    private fun recover() {
        if (attempted) return
        val item = player.currentMediaItem?.takeIf(::handles) ?: return
        attempted = true
        watchdog?.cancel(); watchdog = null
        val originalUri = item.localConfiguration?.uri ?: return
        notifyStatus(Bundle().apply { putString("track_id", item.mediaId); putString("state", "resolving"); putString("source_uri", originalUri.toString()) })
        requestRecovery(item.mediaId, originalUri.toString())
    }
    fun cancel() {
        generation++
        watchdog?.cancel(); watchdog = null
    }
}
