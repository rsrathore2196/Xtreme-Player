package com.example.playback

import com.example.performance.AppDispatchers

import android.util.Log
import androidx.media3.common.Player
import com.example.data.local.ListeningEventDao
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Automix & Infinite Queue Manager inspired by BitChord.
 *
 * Extends the playback queue continuously in the background:
 * - Trigger Condition: Evaluates when remaining queue length <= 3 or on track transitions.
 * - Extracts seed metadata (artist, genre, language, acoustic features).
 * - Uses the unified YouTube Music discovery and verified JioSaavn matching pipeline.
 * - Deduplicates against the current session history in [ListeningEventDao].
 * - Appends top 5 diverse recommended tracks to the queue without stutter or UI stalls.
 * - Completely decoupled from progress ticker updates.
 */
class AutomixQueueManager(
    private val listeningEventDao: ListeningEventDao,
    private val repository: MusicRepository?,
    private val scope: CoroutineScope
) {
    private val TAG = "AutomixQueueManager"
    private val extensionMutex = Mutex()
    private var extensionJob: Job? = null

    @Volatile
    var isEnabled: Boolean = true

    /**
     * Checks whether queue extension is required and runs in background if needed.
     * Guaranteed never to run on progress ticker.
     *
     * @param player ExoPlayer / MediaController instance.
     * @param currentTrack The currently active track.
     * @param currentQueue The currently queued tracks.
     * @param sessionId Active listening session identifier.
     * @param candidatePool Optional in-memory fallback pool.
     * @param onTracksAppended Callback invoked with newly appended resolved tracks.
     */
    fun checkAndExtendQueueIfNeeded(
        player: Player?,
        currentTrack: MusicTrack?,
        currentQueue: List<MusicTrack>,
        sessionId: String,
        candidatePool: List<MusicTrack> = emptyList(),
        onTracksAppended: (List<MusicTrack>) -> Unit
    ) {
        if (!isEnabled || player == null || currentTrack == null) return

        val currentIndex = currentQueue.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)
        val remainingTracks = currentQueue.size - (currentIndex + 1)

        // Trigger condition: queue length remaining is 3 or fewer
        if (remainingTracks > 3) {
            return
        }

        if (extensionMutex.isLocked || extensionJob?.isActive == true) {
            return
        }

        extensionJob = scope.launch(Dispatchers.IO) {
            extensionMutex.withLock {
                try {
                    Log.i(TAG, "Remaining queue tracks ($remainingTracks) <= 3. Triggering background automix queue extension...")

                    // 1. Fetch session history to avoid repeating songs played in this session
                    val sessionPlayedIds = try {
                        listeningEventDao.getSessionPlayedTrackIds(sessionId).toSet()
                    } catch (_: Exception) {
                        emptySet()
                    }

                    val existingQueueIds = currentQueue.map { it.id }.toSet()
                    val excludedIds = sessionPlayedIds + existingQueueIds + setOf(currentTrack.id)

                    // 2. Fetch targeted candidates using CustomRecommendationQueryWrapper (YouTube Music API)
                    val targetedCandidates = repository?.recommendationRadio(currentTrack, currentQueue, 12).orEmpty()

                    val pool = (targetedCandidates + candidatePool.filter { it.recommendationOnly }).distinctBy { it.id }

                    // The unified engine already ranked these recordings. Keep that
                    // order; this layer only guards queue/session identity and availability.
                    val rankedCandidates = withContext(AppDispatchers.CpuBounded) {
                        val seen = com.example.recommendation.HomeRecommendationPolicy.Recordings()
                        (currentQueue + currentTrack).forEach { seen.add(it) }
                        pool.filter { candidate -> candidate.id !in excludedIds && candidate.recommendationOnly &&
                            com.example.recommendation.UnifiedRecommendationEngine.verified(candidate) && seen.add(candidate) }.take(5)
                    }

                    if (rankedCandidates.isEmpty()) {
                        Log.w(TAG, "No suitable automix candidates found for seed track '${currentTrack.title}'.")
                        return@withLock
                    }

                    // 4. Resolve playable stream URLs for candidates via repository
                    val resolvedTracks = mutableListOf<MusicTrack>()
                    for (cand in rankedCandidates) {
                        if (cand.audioUrl.isBlank() && repository != null) {
                            try {
                                resolvedTracks.add(repository.resolvePlayableTrack(cand))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Unavailable tracks must not enter the playable queue.
                            }
                        } else if (cand.audioUrl.isNotBlank()) {
                            resolvedTracks.add(cand)
                        }
                    }

                    // 5. Append to player media items on Main thread
                    val playableTracks = resolvedTracks.filter { it.audioUrl.isNotBlank() }
                    withContext(Dispatchers.Main) {
                        for (track in playableTracks) {
                            player.addMediaItem(track.toMediaItem())
                        }
                        onTracksAppended(playableTracks)
                        Log.i(TAG, "Successfully appended ${playableTracks.size} infinite play tracks to ExoPlayer queue.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extending queue: ${e.message}", e)
                }
            }
        }
    }
}
