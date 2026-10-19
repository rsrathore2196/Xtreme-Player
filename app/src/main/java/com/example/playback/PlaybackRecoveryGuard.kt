package com.example.playback

import androidx.media3.common.PlaybackException

/** Output/DSP failures are not evidence of an unavailable catalog recording. */
internal fun PlaybackException.isAudioPipelineFailure(): Boolean =
    errorCode in 5000..5999 || errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK

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

    fun isCurrent(id: String, token: Long): Boolean = trackId == id && generation == token
}
