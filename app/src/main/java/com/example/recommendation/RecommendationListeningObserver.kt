package com.example.recommendation

import android.os.SystemClock
import com.example.data.local.ListeningEventDao
import com.example.data.local.ListeningEventEntity
import com.example.data.model.MusicTrack
import com.example.playback.PlaybackManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Observes existing player flows; never controls playback. One updatable row per actual play. */
class RecommendationListeningObserver(private val dao: ListeningEventDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun start(player: PlaybackManager) {
        scope.launch {
            var track: MusicTrack? = null
            var previousPosition = 0L
            var previousTime = SystemClock.elapsedRealtime()
            var wasPlaying = false
            var listened = 0L
            var duration = 0L
            var savedDuration = 0L
            var rowId = 0L
            var playId = UUID.randomUUID().toString()
            suspend fun save(finished: Boolean = false) {
                val current = track ?: return
                if (listened == 0L && !finished) return
                val event = ListeningEventEntity.fromTrack(current, playId, listened, duration,
                    "ACTUAL_PLAY", completed = duration > 0 && listened >= duration * 0.9,
                    skipped = finished && listened < 15_000L, liked = current.isLiked).copy(id = rowId)
                try {
                    rowId = withContext(Dispatchers.IO) { dao.insertListeningEvent(event) }
                    savedDuration = listened
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    // Recommendation persistence must never interrupt the player's application scope.
                }
            }
            combine(player.uiState, player.playbackPosition) { state, position -> state to position }
                .collect { (state, position) ->
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = (now - previousTime).coerceAtLeast(0)
                    val delta = position - previousPosition
                    val changed = track?.id != state.currentTrack?.id
                    // A restart near zero after substantial playback is a new play, not a seek credit.
                    val restarted = !changed && previousPosition > 10_000 && position < 1_000 && delta < 0
                    if (changed || restarted) {
                        save(finished = true)
                        track = state.currentTrack
                        listened = 0; savedDuration = 0; rowId = 0
                        playId = UUID.randomUUID().toString()
                    } else if (wasPlaying && delta > 0 && delta <= elapsed + 750 && elapsed <= 5_000) {
                        listened += minOf(delta, elapsed)
                    }
                    duration = state.durationMs.takeIf { it > 0 } ?: (track?.durationMs ?: 0)
                    val meaningful = listened >= 30_000 || (duration > 0 && listened >= (duration + 1) / 2)
                    if ((meaningful && savedDuration < 30_000 && (duration <= 0 || savedDuration < (duration + 1) / 2)) || listened - savedDuration >= 30_000 ||
                        (wasPlaying && !state.isPlaying && listened > savedDuration)) save()
                    previousPosition = position
                    previousTime = now
                    wasPlaying = state.isPlaying && !state.isLoading
                }
        }
    }
}
