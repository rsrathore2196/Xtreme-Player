package com.example.playback

/** UI animation reads an immutable anchor, not the thread-confined MediaController.
 * The application looper samples Media3 at 500ms and on discontinuities/state changes. */
internal class PlaybackPositionClock(private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime) {
    internal data class ServiceSample(val trackId: String, val uri: String, val positionMs: Long, val running: Boolean)
    private data class Anchor(val position: Long, val sampledAt: Long, val speed: Float, val running: Boolean)
    @Volatile private var anchor: Anchor? = null
    fun sample(position: Long, running: Boolean, speed: Float = 1f) {
        anchor = Anchor(position.coerceAtLeast(0L), nowMs(), speed, running)
    }
    fun position(fallback: Long, duration: Long): Long {
        val sample = anchor ?: return fallback
        val elapsed = if (sample.running) ((nowMs() - sample.sampledAt).coerceIn(0L, 750L) * sample.speed).toLong() else 0L
        // A stalled UI/service callback must not invent seconds of progress.
        return (sample.position + elapsed).coerceAtLeast(0L)
            .let { if (duration > 0L) it.coerceAtMost(duration) else it }
    }
}
