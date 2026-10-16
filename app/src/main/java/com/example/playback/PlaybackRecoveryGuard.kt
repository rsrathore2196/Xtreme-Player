package com.example.playback

/** Main-thread-owned retry budget and generation for asynchronous stream recovery. */
internal class PlaybackRecoveryGuard {
    private var trackId: String? = null
    private var generation = 0L
    private var attempts = 0

    fun select(id: String?, newRequest: Boolean = false) {
        if (newRequest || trackId != id) {
            trackId = id
            generation++
            attempts = 0
        }
    }

    fun claim(id: String): Long? {
        if (trackId != id || attempts >= 2) return null
        attempts++
        return generation
    }

    fun isCurrent(id: String, token: Long): Boolean = trackId == id && generation == token
}
