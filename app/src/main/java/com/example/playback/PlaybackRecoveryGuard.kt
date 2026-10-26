package com.example.playback

import androidx.media3.common.PlaybackException

/** Output/DSP failures are not evidence of an unavailable catalog recording. */
internal fun PlaybackException.isAudioPipelineFailure(): Boolean =
    errorCode in 5000..5999 || errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK

internal fun PlaybackException.canRetryAudioOutput(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)

internal fun keepResolvedSource(currentUri: String?, resolvedUri: String, failedUri: String?): Boolean =
    failedUri == null && (currentUri == resolvedUri || currentUri != null &&
        com.example.data.remote.YouTubeAudioStreamService.sameRepresentation(currentUri, resolvedUri))

/** Application-looper only. Rebuild the output on the same player/source and keep the queue intact. */
internal fun restartAudioOutput(player: androidx.media3.common.Player) {
    val index = player.currentMediaItemIndex
    val position = player.currentPosition.coerceAtLeast(0L)
    val resume = player.playWhenReady
    player.stop()
    player.seekTo(index, position)
    player.prepare()
    player.playWhenReady = resume
}

/** Main-thread-owned retry budget and generation for asynchronous stream recovery. */
internal class PlaybackRecoveryGuard {
    private var trackId: String? = null
    private var generation = 0L
    private var attempts = 0
    private var initialSelectionUsed = false
    private val failedUrls = linkedSetOf<String>()

    fun select(id: String?, newRequest: Boolean = false) {
        if (newRequest || trackId != id) {
            trackId = id
            generation++
            attempts = 0
            initialSelectionUsed = false
            failedUrls.clear()
        }
    }

    fun claim(id: String, initialSelection: Boolean = false): Long? {
        if (trackId != id) return null
        // Selecting a real transport for an unresolved queue item is not recovery.
        // Allow this exemption only once; repeated unpinned URIs cannot reset the budget.
        if (initialSelection && !initialSelectionUsed && attempts == 0) {
            initialSelectionUsed = true
            return generation
        }
        if (attempts >= 2) return null
        attempts++
        return generation
    }

    fun stage(id: String, token: Long): Int = if (isCurrent(id, token)) attempts else 2

    fun reject(id: String, token: Long, url: String) {
        if (isCurrent(id, token) && url.isNotBlank()) failedUrls.add(url)
    }

    fun rejectedUrls(id: String, token: Long): Set<String> =
        if (isCurrent(id, token)) failedUrls.toSet() else emptySet()

    fun invalidate() {
        generation++
        attempts = 2
    }

    fun isCurrent(id: String, token: Long): Boolean = !trackId.isNullOrBlank() && trackId == id && generation == token
}
