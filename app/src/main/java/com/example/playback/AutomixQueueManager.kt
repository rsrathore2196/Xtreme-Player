package com.example.playback

import android.util.Log
import androidx.media3.common.Player
import com.example.data.local.ListeningEventDao
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.data.repository.MusicRepository
import com.example.recommendation.AcousticFeatures
import com.example.recommendation.CustomRecommendationQueryWrapper
import com.example.recommendation.MoodVibeAnalyzer
import com.example.recommendation.RecommendationEngine
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
 * - Queries targeted candidates via YouTube Music API exclusively per architectural rules.
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
                    val targetedCandidates = CustomRecommendationQueryWrapper.fetchTargetedRecommendations(
                        currentTrack = currentTrack,
                        limit = 20
                    )

                    val pool = (targetedCandidates + candidatePool).distinctBy { it.id }

                    // 3. Rank and score candidates using acoustic and vibe similarity on Dispatchers.Default
                    val rankedCandidates: List<MusicTrack> = withContext(Dispatchers.Default) {
                        val seedMood = MoodVibeAnalyzer.analyzeMood(currentTrack)
                        val seedAcoustic = AcousticFeatures.forTrack(currentTrack)

                        pool.filter { candidate ->
                            !excludedIds.contains(candidate.id) &&
                            candidate.id != currentTrack.id &&
                            !RecommendationEngine.isSameSongOrVariant(candidate.title, currentTrack.title) &&
                            currentQueue.none { RecommendationEngine.isSameSongOrVariant(candidate.title, it.title) }
                        }.map { candidate: MusicTrack ->
                            val candMood = MoodVibeAnalyzer.analyzeMood(candidate)
                            val candAcoustic = AcousticFeatures.forTrack(candidate)

                            val vibeResult = MoodVibeAnalyzer.computeVibeMatch(seedMood, candMood)
                            val acousticSim = AcousticFeatures.calculateAcousticSimilarity(seedAcoustic, candAcoustic)

                            val artistBonus = if (candidate.artist.equals(currentTrack.artist, ignoreCase = true)) 15.0 else 0.0
                            val langBonus = if (candidate.language.equals(currentTrack.language, ignoreCase = true)) 10.0 else 0.0

                            val totalScore = vibeResult.vibeScore + vibeResult.bpmScore + (acousticSim * 20.0) + artistBonus + langBonus
                            Pair(candidate, totalScore)
                        }.sortedByDescending { it.second }
                        .map { it.first }
                        .take(5)
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
                            } catch (_: Exception) {
                                resolvedTracks.add(cand)
                            }
                        } else {
                            resolvedTracks.add(cand)
                        }
                    }

                    // 5. Append to player media items on Main thread
                    withContext(Dispatchers.Main) {
                        for (track in resolvedTracks) {
                            player.addMediaItem(track.toMediaItem())
                        }
                        onTracksAppended(resolvedTracks)
                        Log.i(TAG, "Successfully appended ${resolvedTracks.size} infinite play tracks to ExoPlayer queue.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extending queue: ${e.message}", e)
                }
            }
        }
    }
}
