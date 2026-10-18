package com.example.recommendation

import com.example.performance.AppDispatchers

import android.os.SystemClock
import com.example.data.local.ListeningEventDao
import com.example.data.local.ListeningEventEntity
import com.example.data.local.MusicDao
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.playback.PlaybackManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Observes existing player flows; never controls playback. One updatable row per actual play. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationListeningObserver(private val dao: ListeningEventDao, private val musicDao: MusicDao) {
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.CpuBounded)
    fun start(player: PlaybackManager) {
        scope.launch {
            var track: MusicTrack? = null
            var previousTime = SystemClock.elapsedRealtime()
            var previousSequence = -1L
            var wasPlaying = false
            var listened = 0L
            var duration = 0L
            var savedDuration = 0L
            var rowId = 0L
            var playId = UUID.randomUUID().toString()
            suspend fun save(finished: Boolean = false) {
                val current = track ?: return
                if (listened <= 0L) return
                val event = ListeningEventEntity.fromTrack(current, playId, listened, duration,
                    "ACTUAL_PLAY", completed = duration > 0 && listened >= duration * 0.9,
                    skipped = finished && listened < 15_000L, liked = current.isLiked).copy(id = rowId)
                try {
                    rowId = withContext(Dispatchers.IO) {
                        // Publish metadata first so Home can resolve the very first history event.
                        if (rowId == 0L) musicDao.mergeTrackMetadata(TrackEntity.fromMusicTrack(current))
                        dao.insertListeningEvent(event)
                    }
                    savedDuration = listened
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    // Recommendation persistence must never interrupt the player's application scope.
                }
            }
            // Native playback events own the clock. Coarse persistence wakeups
            // preserve background listening without polling or emitting UI positions.
            player.listeningState.flatMapLatest { signal -> flow {
                emit(signal)
                if (signal.isPlaying) {
                    delay(1_000L)
                    emit(signal)
                    val milestone = minOf(30_000L, signal.durationMs.takeIf { it > 0L }?.let { (it + 1L) / 2L } ?: 30_000L)
                    delay((milestone - listened).coerceAtLeast(1_000L))
                    while (true) { emit(signal); delay(30_000L) }
                }
            } }.collect { state ->
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = (now - previousTime).coerceAtLeast(0)
                    if (wasPlaying && track != null) listened += elapsed
                    val changed = track?.id != state.track?.id || previousSequence != state.playSequence
                    if (changed) {
                        save(finished = true)
                        track = state.track
                        listened = 0; savedDuration = 0; rowId = 0
                        playId = state.playSessionId.ifBlank { UUID.randomUUID().toString() }
                    } else {
                        track = state.track
                    }
                    duration = state.durationMs.takeIf { it > 0 } ?: (track?.durationMs ?: 0)
                    val meaningful = listened >= 30_000 || (duration > 0 && listened >= (duration + 1) / 2)
                    track?.let { current -> withContext(Dispatchers.Main.immediate) {
                        player.recordMeaningfulListening(current.id, state.playSequence, listened, duration)
                    } }
                    if ((rowId == 0L && listened > 0L) || (meaningful && savedDuration < 30_000 && (duration <= 0 || savedDuration < (duration + 1) / 2)) || listened - savedDuration >= 30_000 ||
                        (wasPlaying && !state.isPlaying && listened > savedDuration)) save()
                    previousTime = now
                    previousSequence = state.playSequence
                    wasPlaying = state.isPlaying
                }
        }
    }
}
