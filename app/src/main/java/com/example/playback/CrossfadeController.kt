package com.example.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
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
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sourcePolicy: () -> String = { "" }
) : AutoCloseable {
    companion object {
        internal const val FADE_MS = 3_000L
        private const val WARM_MS = 8_000L
        /** Media3 preloads only the next period, after current audio has its buffer.
         * Reusing that period avoids another queue replacement/resolution owner. */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        internal fun enableQueueReadAhead(player: ExoPlayer) {
            player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(FADE_MS * 1_000L))
        }

        /** The buffer must hold the entire outgoing warm window PLUS the overlap.
         * A 1s/8s hysteresis could stop with <3s of the next track, then never
         * resume loading before the fade window passed. */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        internal fun tailLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs((WARM_MS + FADE_MS).toInt(), (WARM_MS + 2 * FADE_MS).toInt(), 500, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
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
    private var warmedPolicy: String? = null
    private var expectedNext: String? = null
    private var failedKey: String? = null
    private var syncAttempts = 0
    private var nextSyncAt = 0L
    private var baseVolume = player.volume
    private var commandedVolume = baseVolume
    private var changingItem = false
    private var cancelling = false
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
        if (enabled == value) {
            // Audio-setting changes cancel the old tail before reapplying the
            // preference. Restart admission even when crossfade remains enabled.
            if (value) update()
            return
        }
        enabled = value
        if (!value) cancelTransition()
        update()
    }

    /** Also used before an explicit output-route change. Never seeks or resets the queue. */
    fun cancelTransition() {
        if (cancelling) return
        cancelling = true
        try {
            ++generation
            watch?.cancel(); watch = null
            releaseTail()
            setVolume(baseVolume)
        } finally { cancelling = false }
    }

    fun outputRouteChanging() {
        skipCurrentTransition()
    }

    private fun skipCurrentTransition() {
        failedKey = key(player.currentMediaItem)
        cancelTransition()
    }

    private fun update() {
        // Restoring gain emits Player events synchronously. The old configuration
        // must stay cancelled until the caller has applied all new audio settings.
        if (cancelling) return
        val state = player.playbackState
        val running = enabled && player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            (state == Player.STATE_READY || state == Player.STATE_BUFFERING) && player.playerError == null
        if (!running) { cancelTransition(); return }
        if (watch?.isActive == true) return
        val request = ++generation
        watch = scope.launch {
            try {
                while (isActive && enabled && player.playWhenReady) {
                    if (tail != null && warmedPolicy != sourcePolicy()) { skipCurrentTransition(); continue }
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
                    val nextItem = player.getMediaItemAt(next)
                    val nextKey = key(nextItem)
                    if (tail == null) warmTail(current, next)
                    if (expectedNext != null && expectedNext != nextKey) { releaseTail(); continue }
                    expectedNext = nextKey
                    val outgoing = tail ?: continue
                    if (outgoing.playerError != null || key(outgoing.currentMediaItem) != outgoingKey) {
                        failedKey = current; releaseTail(); continue
                    }
                    if (outgoing.isPlaying) {
                        val drift = outgoing.currentPosition - player.currentPosition
                        if (abs(drift) > 100L && remaining > FADE_MS + 300L && now() >= nextSyncAt && syncAttempts < 2) {
                            syncAttempts++; nextSyncAt = now() + 500L
                            outgoing.seekTo(player.currentPosition)
                        } else if (remaining <= FADE_MS && abs(drift) <= 100L && incomingPrepared(outgoing, nextItem)) {
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

    private fun warmTail(current: String?, next: Int) {
        val item = player.currentMediaItem ?: return
        outgoingKey = current
        warmedPolicy = sourcePolicy()
        baseVolume = player.volume
        syncAttempts = 0; nextSyncAt = 0L
        tail = createTail()
        tail?.also {
            it.volume = 0f
            it.repeatMode = Player.REPEAT_MODE_OFF
            // Preparing both periods independently proves that the next source
            // has usable buffered audio. The shared media cache also warms the
            // session player's handoff without changing its queue or ownership.
            it.setMediaItems(listOf(item, player.getMediaItemAt(next)), 0, player.currentPosition)
            it.prepare()
            it.play()
        }
    }

    private fun incomingPrepared(outgoing: Player, next: MediaItem): Boolean {
        if (outgoing.mediaItemCount != 2 || outgoing.currentMediaItemIndex != 0 ||
            outgoing.getMediaItemAt(1).mediaId != next.mediaId || outgoing.duration == C.TIME_UNSET ||
            warmedPolicy != sourcePolicy()) return false
        // The lazy source may replace a catalog URI with its verified transport.
        // Its slot/recording identity remains stable; queue edits are checked separately.
        val outgoingRemaining = (outgoing.duration - outgoing.currentPosition).coerceAtLeast(0L)
        return outgoing.totalBufferedDuration - outgoingRemaining >= FADE_MS
    }

    private suspend fun beginOverlap(outgoing: Player, next: Int) {
        val nextItem = player.getMediaItemAt(next)
        val nextKey = key(nextItem)
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
            if (!targetStillValid() || !incomingPrepared(outgoing, nextItem)) { skipCurrentTransition(); return }
            val fraction = step / 3f
            setVolume(baseVolume * (1f - fraction))
            outgoing.volume = baseVolume * fraction
            delay(20L)
        }
        currentCoroutineContext().ensureActive()
        if (!targetStillValid() || !incomingPrepared(outgoing, nextItem)) { skipCurrentTransition(); return }
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
        // pauseAtEndOfMediaItems leaves the tail READY but paused. It must not
        // hold the incoming player muted, or advance and play the next song twice.
        if (!outgoing.isPlaying || key(outgoing.currentMediaItem) != outgoingKey || outgoing.playerError != null) {
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
        tail = null; outgoingKey = null; warmedPolicy = null; expectedNext = null; overlap = false; fadeStarted = 0L
        if (old != null) runCatching { old.release() }
            .onFailure { android.util.Log.w("Crossfade", "Tail cleanup failed", it) }
    }
    override fun close() {
        enabled = false
        player.removeListener(listener)
        cancelTransition()
    }
}
