package com.example.playback

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
 * Automix & Infinite Queue Manager using direct YouTube Music server radio.
 *
 * Extends the playback queue continuously in the background:
 * - Trigger Condition: Evaluates when remaining queue length <= 3 or on track transitions.
 * - Directly queries native YouTube Music Radio (/youtubei/v1/next with RDAMVM{videoId}).
 * - Preserves server recommendation order without artificial rankings or candidate pools.
 * - Deduplicates against session history and existing queue tracks.
 * - Appends up to 10 server recommendations metadata-first to ExoPlayer.
 * - Concurrency protected via Mutex; never interrupts active playback.
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
     */
    fun checkAndExtendQueueIfNeeded(
        player: Player?,
        currentTrack: MusicTrack?,
        currentQueue: List<MusicTrack>,
        sessionId: String,
        @Suppress("UNUSED_PARAMETER") candidatePool: List<MusicTrack> = emptyList(),
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
                    Log.i(TAG, "Remaining queue tracks ($remainingTracks) <= 3. Triggering YouTube Music radio refill...")

                    // 1. Fetch session history to avoid repeating songs played in this session
                    val sessionPlayedIds = try {
                        listeningEventDao.getSessionPlayedTrackIds(sessionId).toSet()
                    } catch (_: Exception) {
                        emptySet()
                    }

                    val existingQueueIds = currentQueue.map { it.id }.toSet()
                    val excludedIds = sessionPlayedIds + existingQueueIds + setOf(currentTrack.id)

                    // 2. Fetch server radio recommendations directly
                    val radioRecommendations = repository?.fetchYouTubeRadio(
                        seed = currentTrack,
                        excludedIds = excludedIds,
                        limit = 10
                    ).orEmpty()

                    if (radioRecommendations.isEmpty()) {
                        Log.w(TAG, "No server radio tracks available for seed '${currentTrack.title}'.")
                        return@withLock
                    }

                    // 3. Append to player media items on Main thread (metadata-first)
                    withContext(Dispatchers.Main) {
                        for (track in radioRecommendations) {
                            player.addMediaItem(track.toMediaItem())
                        }
                        onTracksAppended(radioRecommendations)
                        Log.i(TAG, "Appended ${radioRecommendations.size} YouTube radio tracks to ExoPlayer queue.")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Radio queue refill failed: ${e.message}", e)
                }
            }
        }
    }
}
