package com.example.recommendation

import android.content.Context
import com.example.data.repository.ListeningStatsRepository
import com.example.performance.AppDispatchers
import com.example.playback.PlaybackManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull

/** Home milestones reuse service-owned measurements, with no second clock or persistence loop. */
class RecommendationListeningObserver(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    fun start(player: PlaybackManager) {
        scope.launch {
            ListeningStatsRepository.get(context).progress.filterNotNull().collect { snapshot ->
                withContext(Dispatchers.Main.immediate) {
                    val state = player.listeningState.value
                    if (!snapshot.finished && state.playSessionId == snapshot.occurrence && state.track?.id == snapshot.track.id) player.recordMeaningfulListening(
                        snapshot.track.id, state.playSequence, snapshot.listenedMs, snapshot.track.durationMs)
                }
            }
        }
    }
}
