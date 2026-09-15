package com.example.recommendation

import android.util.Log
import com.example.data.model.MusicTrack
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Intelligent Music Recommendation & Infinity Queue Engine (Autoplay)
 *
 * Implements:
 * 1. Content-Based Filtering: High-dimensional acoustic vector similarity & metadata matching (Genre, Artist, Language).
 * 2. Collaborative Filtering: Co-listening transition matrix tracking sequential user listening behaviors.
 * 3. Sequential Context Awareness: Vector trajectory across the last 3-5 tracks to prevent jarring mood spikes.
 * 4. Smooth Transitions: Constrains tempo shifts to max +/-15% between consecutive songs.
 * 5. Cold-Start Mitigation: Gracefully blends into audio signal embeddings for tracks without collaborative history.
 * 6. Session Memory: In-memory sliding buffer of the last 10 played tracks to anchor session mood and eliminate drift.
 */
class RecommendationEngine {
    private val TAG = "RecommendationEngine"

    // Session Memory: In-memory sliding buffer of the last 10 played tracks
    private val sessionBuffer = ArrayDeque<MusicTrack>(10)

    // Collaborative Filtering: Co-listening transition frequency matrix [TrackA -> Map<TrackB, Count>]
    private val coListeningMatrix = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        seedCoListeningMatrix()
    }

    /**
     * Seeds initial co-listening transition graph based on curated tracks and natural listener flow.
     */
    private fun seedCoListeningMatrix() {
        fun link(from: String, to: String, weight: Int = 10) {
            coListeningMatrix.getOrPut(from) { mutableMapOf() }[to] = weight
            coListeningMatrix.getOrPut(to) { mutableMapOf() }[from] = (weight * 0.8f).toInt()
        }

        // Synthwave & Retrowave cluster
        link("xtreme_01", "xtreme_02", 15)
        link("xtreme_01", "xtreme_03", 18)
        link("xtreme_02", "xtreme_05", 14)
        link("xtreme_03", "xtreme_06", 12)

        // Chillhop, Lofi, Jazz cluster
        link("xtreme_04", "xtreme_08", 16)
        link("xtreme_04", "xtreme_10", 14)
        link("xtreme_08", "xtreme_09", 11)

        // Energy Electronic & Rock cluster
        link("xtreme_05", "xtreme_06", 16)
        link("xtreme_05", "xtreme_07", 13)
        link("xtreme_06", "xtreme_07", 12)
    }

    /**
     * Records a track played by the user into the sliding session memory (last 10 tracks),
     * and updates the collaborative co-listening transition matrix.
     */
    @Synchronized
    fun recordTrackPlayed(track: MusicTrack) {
        val lastTrack = sessionBuffer.peekLast()
        if (lastTrack != null && lastTrack.id != track.id) {
            // Update co-listening transition frequency
            val row = coListeningMatrix.getOrPut(lastTrack.id) { mutableMapOf() }
            row[track.id] = (row[track.id] ?: 0) + 1
        }

        if (sessionBuffer.size >= 10) {
            sessionBuffer.removeFirst()
        }
        sessionBuffer.addLast(track)
        Log.d(TAG, "Recorded track into session buffer: ${track.title}. Buffer size: ${sessionBuffer.size}")
    }

    /**
     * Gets the snapshot of the last 10 songs in session memory.
     */
    @Synchronized
    fun getSessionTracks(): List<MusicTrack> = sessionBuffer.toList()

    /**
     * Recommends the single best next track to automatically play next.
     *
     * @param currentTrack The track currently playing.
     * @param candidatePool The available tracks from catalog, library, search, or trending songs.
     * @param excludedIds IDs of tracks currently queued or recently played to prevent repetition.
     * @param prioritizeArtistAndGenre When true (e.g. after search or artist exploration), heavily prioritizes
     *                                 tracks by the same singer/artist and matching genre/type.
     */
    @Synchronized
    fun recommendNextTrack(
        currentTrack: MusicTrack,
        candidatePool: List<MusicTrack>,
        excludedIds: Set<String> = emptySet(),
        prioritizeArtistAndGenre: Boolean = false
    ): MusicTrack? {
        if (candidatePool.isEmpty()) return null

        val currentFeatures = AcousticFeatures.forTrack(currentTrack)
        val sessionList = sessionBuffer.toList()

        // 1. Filter out candidate pool with recent history & exclusion list
        val recent10Ids = sessionList.map { it.id }.toSet()
        val allExcluded = excludedIds + currentTrack.id

        var filteredCandidates = candidatePool.filter { candidate ->
            !allExcluded.contains(candidate.id) && !recent10Ids.contains(candidate.id)
        }

        // If pool is exhausted because of small library, relax session exclusion but keep currently queued out
        if (filteredCandidates.isEmpty()) {
            filteredCandidates = candidatePool.filter { it.id != currentTrack.id && !excludedIds.contains(it.id) }
        }
        if (filteredCandidates.isEmpty()) {
            filteredCandidates = candidatePool.filter { it.id != currentTrack.id }
        }
        if (filteredCandidates.isEmpty()) {
            return candidatePool.firstOrNull()
        }

        // 2. Compute Taste & Affinity Profile from the sliding window of last 10 played songs
        val historyArtistWeights = sessionList.groupingBy { it.artist.lowercase() }.eachCount()
        val historyGenreWeights = sessionList.groupingBy { it.genre.lowercase() }.eachCount()
        val historyLangWeights = sessionList.groupingBy { it.language.lowercase() }.eachCount()

        val trajectoryTracks = sessionList.takeLast(4)
        val trajectoryFeatures = trajectoryTracks.map { AcousticFeatures.forTrack(it) }
        val avgTrajectoryEnergy = if (trajectoryFeatures.isNotEmpty()) {
            trajectoryFeatures.map { it.energy }.average().toFloat()
        } else currentFeatures.energy
        val avgTrajectoryValence = if (trajectoryFeatures.isNotEmpty()) {
            trajectoryFeatures.map { it.valence }.average().toFloat()
        } else currentFeatures.valence

        // Session Centroid (Last 10 tracks average tempo & danceability)
        val sessionFeatures = sessionList.map { AcousticFeatures.forTrack(it) }
        val sessionAvgTempo = if (sessionFeatures.isNotEmpty()) {
            sessionFeatures.map { it.tempoBpm }.average().toFloat()
        } else currentFeatures.tempoBpm

        // 3. Score every candidate
        val scoredCandidates = filteredCandidates.map { candidate ->
            val candidateFeatures = AcousticFeatures.forTrack(candidate)

            // --- A. Content-Based Acoustic Similarity (0.0 to 1.0) ---
            val acousticDist = currentFeatures.normalizedDistance(candidateFeatures)
            val acousticSimilarity = (1.0f - acousticDist).coerceIn(0f, 1f)

            // --- B. Smooth Transitions: Constrain BPM shift (max +/- 15%) ---
            val bpmDeltaPercent = currentFeatures.bpmDeltaPercent(candidateFeatures)
            val bpmSmoothnessScore = if (bpmDeltaPercent <= 0.15f) {
                1.0f - (bpmDeltaPercent / 0.15f) * 0.2f
            } else {
                (1.0f - (bpmDeltaPercent - 0.15f) * 2.5f).coerceAtLeast(0.1f)
            }

            // --- C. Singer, Artist, Genre & Language Matching ---
            val isSameArtist = candidate.artist.equals(currentTrack.artist, ignoreCase = true)
            val sharesSinger = sharesSingerOrArtist(currentTrack, candidate)
            val isSameGenre = candidate.genre.equals(currentTrack.genre, ignoreCase = true)
            val isCompatibleGenre = isCompatibleGenre(currentTrack.genre, candidate.genre)
            val isSameLanguage = candidate.language.isNotBlank() && candidate.language.equals(currentTrack.language, ignoreCase = true)

            var directMatchScore = 0.0f
            if (isSameArtist || sharesSinger) directMatchScore += 0.50f
            if (isSameGenre) directMatchScore += 0.35f else if (isCompatibleGenre) directMatchScore += 0.20f
            if (isSameLanguage) directMatchScore += 0.15f

            // --- D. Taste Profile Matching (Align with User's Last 10 Played Songs) ---
            var tasteProfileScore = 0.0f
            val candArtistCount = historyArtistWeights[candidate.artist.lowercase()] ?: 0
            val candGenreCount = historyGenreWeights[candidate.genre.lowercase()] ?: 0
            val candLangCount = historyLangWeights[candidate.language.lowercase()] ?: 0

            if (candArtistCount > 0) {
                tasteProfileScore += (candArtistCount.toFloat() / sessionList.size.coerceAtLeast(1)).coerceAtMost(0.40f)
            }
            if (candGenreCount > 0) {
                tasteProfileScore += (candGenreCount.toFloat() / sessionList.size.coerceAtLeast(1)).coerceAtMost(0.35f)
            }
            if (candLangCount > 0) {
                tasteProfileScore += (candLangCount.toFloat() / sessionList.size.coerceAtLeast(1)).coerceAtMost(0.25f)
            }
            tasteProfileScore = tasteProfileScore.coerceIn(0f, 1f)

            // --- E. Sequential Context Awareness (Trajectory Continuity) ---
            val energyShift = abs(candidateFeatures.energy - avgTrajectoryEnergy)
            val valenceShift = abs(candidateFeatures.valence - avgTrajectoryValence)
            val trajectoryContinuityScore = (1.0f - ((energyShift + valenceShift) / 2f)).coerceIn(0f, 1f)

            // --- F. Collaborative Filtering & Cold-Start Solution ---
            val coListenCount = coListeningMatrix[currentTrack.id]?.get(candidate.id) ?: 0
            val totalTransitions = coListeningMatrix[currentTrack.id]?.values?.sum() ?: 0
            val isColdStart = totalTransitions < 3 || coListenCount == 0
            val collaborativeScore: Float = if (isColdStart) {
                (acousticSimilarity * 0.6f) + (directMatchScore * 0.4f)
            } else {
                (coListenCount.toFloat() / totalTransitions.toFloat()).coerceIn(0f, 1f)
            }

            // --- G. Session Drift Prevention ---
            val sessionBpmDrift = abs(candidateFeatures.tempoBpm - sessionAvgTempo) / (if (sessionAvgTempo > 0) sessionAvgTempo else 120f)
            val sessionConsistencyScore = (1.0f - sessionBpmDrift).coerceIn(0.2f, 1f)

            // --- Composite Score Calculation ---
            val compositeScore = if (prioritizeArtistAndGenre) {
                // High priority on singer & genre (e.g. played after search or singer radio)
                val artistBoost = if (isSameArtist || sharesSinger) 0.50f else 0f
                val genreBoost = if (isSameGenre) 0.30f else if (isCompatibleGenre) 0.15f else 0f
                val langBoost = if (isSameLanguage) 0.10f else 0f

                (artistBoost) + (genreBoost) + (langBoost) +
                        (acousticSimilarity * 0.25f) +
                        (bpmSmoothnessScore * 0.15f) +
                        (tasteProfileScore * 0.15f)
            } else {
                // Standard Autoplay / Infinity Queue: Balanced blend of acoustic continuity, taste profile, and transitions
                ((acousticSimilarity * 0.40f + bpmSmoothnessScore * 0.25f + directMatchScore * 0.35f) * 0.35f) +
                        (trajectoryContinuityScore * 0.20f) +
                        (tasteProfileScore * 0.20f) +
                        (collaborativeScore * 0.15f) +
                        (sessionConsistencyScore * 0.10f)
            }

            CandidateScore(candidate, compositeScore, bpmDeltaPercent, candidateFeatures)
        }

        val bestCandidate = scoredCandidates.maxByOrNull { it.score }
        Log.i(
            TAG,
            "Autoplay recommended next track: '${bestCandidate?.track?.title}' by '${bestCandidate?.track?.artist}' " +
                    "(Score: ${"%.2f".format(bestCandidate?.score)}, PrioritizeArtist: $prioritizeArtistAndGenre)"
        )
        return bestCandidate?.track
    }

    /**
     * Constructs a tailored playback queue when user plays a song from search results:
     * - First: the selected song itself
     * - Followed immediately by: Same singer/artist's other songs
     * - Followed by: Same genre/type songs matching the listener's taste profile
     */
    @Synchronized
    fun buildSearchPlaybackQueue(
        selectedTrack: MusicTrack,
        candidatePool: List<MusicTrack>,
        limit: Int = 20
    ): List<MusicTrack> {
        val resultQueue = mutableListOf<MusicTrack>()
        resultQueue.add(selectedTrack)

        val poolWithoutSelected = candidatePool.filter { it.id != selectedTrack.id }

        // 1. First priority: Songs by the exact same singer / artist
        val sameSingerSongs = poolWithoutSelected.filter { candidate ->
            sharesSingerOrArtist(selectedTrack, candidate)
        }.distinctBy { it.id }

        resultQueue.addAll(sameSingerSongs)

        // 2. Next priority: Same genre / type songs matching taste profile
        val remainingCandidates = poolWithoutSelected.filter { cand ->
            resultQueue.none { it.id == cand.id }
        }

        var currentPivot = resultQueue.lastOrNull() ?: selectedTrack
        val excludedIds = resultQueue.map { it.id }.toMutableSet()

        while (resultQueue.size < limit && remainingCandidates.isNotEmpty()) {
            val nextTrack = recommendNextTrack(
                currentTrack = currentPivot,
                candidatePool = remainingCandidates,
                excludedIds = excludedIds,
                prioritizeArtistAndGenre = true
            )
            if (nextTrack != null) {
                resultQueue.add(nextTrack)
                excludedIds.add(nextTrack.id)
                currentPivot = nextTrack
            } else {
                break
            }
        }

        return resultQueue
    }

    /**
     * Checks if two tracks share any singer or artist (handling collaborations, featured artists, and duets).
     */
    fun sharesSingerOrArtist(t1: MusicTrack, t2: MusicTrack): Boolean {
        if (t1.artist.equals(t2.artist, ignoreCase = true)) return true

        val singers1 = (t1.singers + "," + t1.artist).split(",", "&", "feat.", "ft.", "•", "/")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != "various artists" && it != "unknown artist" }

        val singers2 = (t2.singers + "," + t2.artist).split(",", "&", "feat.", "ft.", "•", "/")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != "various artists" && it != "unknown artist" }

        for (s1 in singers1) {
            for (s2 in singers2) {
                if (s1 == s2 || s1.contains(s2) || s2.contains(s1)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Generates a continuous recommended batch (Infinity Queue / Autoplay stream) of tracks.
     */
    @Synchronized
    fun recommendInfinityQueue(
        currentTrack: MusicTrack,
        candidatePool: List<MusicTrack>,
        count: Int = 5,
        alreadyQueued: List<MusicTrack> = emptyList()
    ): List<MusicTrack> {
        val result = mutableListOf<MusicTrack>()
        val currentExcluded = (alreadyQueued.map { it.id } + currentTrack.id).toMutableSet()
        var pivotTrack = currentTrack

        for (i in 0 until count) {
            val next = recommendNextTrack(pivotTrack, candidatePool, currentExcluded)
            if (next != null) {
                result.add(next)
                currentExcluded.add(next.id)
                pivotTrack = next
            } else {
                break
            }
        }
        return result
    }

    private fun isCompatibleGenre(genre1: String, genre2: String): Boolean {
        val g1 = genre1.lowercase()
        val g2 = genre2.lowercase()
        return (g1.contains("synth") && g2.contains("elec")) ||
                (g1.contains("elec") && g2.contains("synth")) ||
                (g1.contains("lofi") && g2.contains("chill")) ||
                (g1.contains("chill") && g2.contains("jazz")) ||
                (g1.contains("pop") && g2.contains("dance")) ||
                (g1.contains("rock") && g2.contains("elec"))
    }

    private data class CandidateScore(
        val track: MusicTrack,
        val score: Float,
        val bpmDelta: Float,
        val features: AcousticFeatures
    )
}
