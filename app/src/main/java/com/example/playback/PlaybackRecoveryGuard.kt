package com.example.playback

/** Main-thread-owned retry budget and generation for asynchronous stream recovery. */
internal class PlaybackRecoveryGuard {
    private var trackId: String? = null
    private var generation = 0L
    private var attempted = false

    fun select(id: String?, newRequest: Boolean = false) {
        if (newRequest || trackId != id) {
            trackId = id
            generation++
            attempted = false
        }
    }

    fun claim(id: String): Long? {
        if (trackId != id || attempted) return null
        attempted = true
        return generation
    }

    fun isCurrent(id: String, token: Long): Boolean = trackId == id && generation == token
}
