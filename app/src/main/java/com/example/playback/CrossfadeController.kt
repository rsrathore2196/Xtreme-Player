package com.example.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * The session/queue always belong to the original player. A short-lived, focus-free
 * tail reader continues the outgoing recording while that player starts the next.
 * It is created only near an enabled transition, never during startup/paused playback.
 * All Player access and volume commands run on the service's application looper.
 */
internal class CrossfadeController(
    private val player: Player,
    private val scope: CoroutineScope,
    private val createTail: () -> Player,
    private val canMix: () -> Boolean = { true },
    private val now: () -> Long = SystemClock::elapsedRealtime
) : AutoCloseable {
    companion object {
        internal const val FADE_MS = 3_000L
        private const val WARM_MS = 8_000L
        internal fun gains(progress: Float): Pair<Float, Float> {
            val angle = progress.coerceIn(0f, 1f) * PI / 2.0
            return cos(angle).toFloat() to sin(angle).toFloat()
        }
        private fun key(item: MediaItem?) = item?.let { "${it.mediaId}|${it.localConfiguration?.uri}" }
    }

    @Volatile var enabled = false
        private set
    private var watch: Job? = null
    private var generation = 0L
    private var tail: Player? = null
    private var outgoingKey: String? = null
    private var expectedNext: String? = null
    private var failedKey: String? = null
    private var syncAttempts = 0
    private var nextSyncAt = 0L
    private var baseVolume = player.volume
    private var commandedVolume = baseVolume
    private var changingItem = false
    private var overlap = false
    private var fadeStarted = 0L
    private var fadeDuration = FADE_MS

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (failedKey != null && key(player.currentMediaItem) != failedKey) failedKey = null
            if (!changingItem && outgoingKey != null) {
                val current = key(player.currentMediaItem)
                if (current != if (overlap) expectedNext else outgoingKey) cancelTransition()
            }
            update()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!changingItem && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) cancelTransition()
        }
        override fun onVolumeChanged(volume: Float) {
            if (abs(volume - commandedVolume) > 0.001f) {
                baseVolume = volume
                skipCurrentTransition()
            }
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { cancelTransition() }
    }
    init { player.addListener(listener) }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) cancelTransition()
        update()
    }

    /** Also used before an explicit output-route change. Never seeks or resets the queue. */
    fun cancelTransition() {
        ++generation
        watch?.cancel(); watch = null
        releaseTail()
        setVolume(baseVolume)
    }

    fun outputRouteChanging() {
        skipCurrentTransition()
    }

    private fun skipCurrentTransition() {
        failedKey = key(player.currentMediaItem)
        cancelTransition()
    }

    private fun update() {
        val state = player.playbackState
        val running = enabled && player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            (state == Player.STATE_READY || state == Player.STATE_BUFFERING) && player.playerError == null
        if (!running) { cancelTransition(); return }
        if (watch?.isActive == true) return
        val request = ++generation
        watch = scope.launch {
            try {
                while (isActive && enabled && player.playWhenReady) {
                    if (overlap) { tickOverlap(); delay(40L); continue }
                    val duration = player.duration
                    val remaining = duration - player.currentPosition
                    val current = key(player.currentMediaItem)
                    val next = player.nextMediaItemIndex
                    if (!player.isPlaying || duration < 30_000L || player.isCurrentMediaItemLive ||
                        player.isCurrentMediaItemDynamic || player.repeatMode == Player.REPEAT_MODE_ONE ||
                        player.playbackParameters != PlaybackParameters.DEFAULT || !canMix() ||
                        next == C.INDEX_UNSET || next == player.currentMediaItemIndex || current == failedKey ||
                        baseVolume <= 0f) {
                        releaseTail(); delay(500L); continue
                    }
                    if (remaining !in 250L..WARM_MS) { releaseTail(); delay(1_000L); continue }
                    val nextKey = key(player.getMediaItemAt(next))
                    if (tail == null) warmTail(current)
                    if (expectedNext != null && expectedNext != nextKey) { releaseTail(); continue }
                    expectedNext = nextKey
                    val outgoing = tail ?: continue
                    if (outgoing.playerError != null) { failedKey = current; releaseTail(); continue }
                    if (outgoing.isPlaying) {
                        val drift = outgoing.currentPosition - player.currentPosition
                        if (abs(drift) > 100L && remaining > FADE_MS + 300L && now() >= nextSyncAt && syncAttempts < 2) {
                            syncAttempts++; nextSyncAt = now() + 500L
                            outgoing.seekTo(player.currentPosition)
                        } else if (remaining <= FADE_MS && abs(drift) <= 100L &&
                            player.totalBufferedDuration >= remaining + 500L) {
                            beginOverlap(outgoing, next)
                        }
                    }
                    delay(50L)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("Crossfade", "Keeping normal queue playback after transition failure", error)
                failedKey = outgoingKey
            } finally { if (request == generation) { releaseTail(); setVolume(baseVolume) } }
        }
    }

    private fun warmTail(current: String?) {
        val item = player.currentMediaItem ?: return
        outgoingKey = current
        baseVolume = player.volume
        syncAttempts = 0; nextSyncAt = 0L
        tail = createTail()
        tail?.also {
            it.volume = 0f
            it.repeatMode = Player.REPEAT_MODE_OFF
            it.setMediaItem(item, player.currentPosition)
            it.prepare()
            it.play()
        }
    }

    private suspend fun beginOverlap(outgoing: Player, next: Int) {
        val nextKey = key(player.getMediaItemAt(next))
        fun targetStillValid() = next in 0 until player.mediaItemCount && player.nextMediaItemIndex == next &&
            key(player.getMediaItemAt(next)) == nextKey
        // Transfer the same outgoing PCM gently before advancing the session player.
        // The tail is already running in sync; neither source starts from the beginning.
        for (step in 1..3) {
            currentCoroutineContext().ensureActive()
            if (tail !== outgoing || !player.isPlaying || !outgoing.isPlaying || key(player.currentMediaItem) != outgoingKey) {
                skipCurrentTransition()
                return
            }
            if (!targetStillValid()) { skipCurrentTransition(); return }
            val fraction = step / 3f
            setVolume(baseVolume * (1f - fraction))
            outgoing.volume = baseVolume * fraction
            delay(20L)
        }
        currentCoroutineContext().ensureActive()
        if (!targetStillValid()) { skipCurrentTransition(); return }
        expectedNext = nextKey
        overlap = true
        fadeStarted = 0L
        changingItem = true
        try { player.seekTo(next, 0L) }
        finally { changingItem = false }
        // If no state event is sent for a retained READY decoder, the next tick starts the fade.
    }

    private fun tickOverlap() {
        val outgoing = tail ?: return
        if (key(player.currentMediaItem) != expectedNext || !canMix() || player.playbackParameters != PlaybackParameters.DEFAULT) {
            releaseTail(); setVolume(baseVolume); return
        }
        if (outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null) {
            releaseTail(); setVolume(baseVolume); return
        }
        if (!player.isPlaying) return // Let the outgoing tail cover a short incoming buffer.
        if (fadeStarted == 0L) {
            fadeStarted = now()
            fadeDuration = minOf(FADE_MS, (outgoing.duration - outgoing.currentPosition).coerceAtLeast(1L))
        }
        val progress = ((now() - fadeStarted).toFloat() / fadeDuration).coerceIn(0f, 1f)
        val (oldGain, newGain) = gains(progress)
        outgoing.volume = baseVolume * oldGain
        setVolume(baseVolume * newGain)
        if (progress >= 1f) { releaseTail(); setVolume(baseVolume) }
    }

    private fun setVolume(value: Float) {
        commandedVolume = value.coerceIn(0f, 1f)
        if (abs(player.volume - commandedVolume) > 0.00001f) player.volume = commandedVolume
    }
    private fun releaseTail() {
        val old = tail
        tail = null; outgoingKey = null; expectedNext = null; overlap = false; fadeStarted = 0L
        if (old != null) runCatching { old.release() }
            .onFailure { android.util.Log.w("Crossfade", "Tail cleanup failed", it) }
    }
    override fun close() {
        enabled = false
        player.removeListener(listener)
        cancelTransition()
    }
}
