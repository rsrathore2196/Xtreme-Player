package com.example.recommendation

import com.example.data.local.ListeningEventDao
import com.example.data.local.ListeningEventEntity
import kotlin.math.exp
import kotlin.math.ln

/**
 * Cached User Taste Profile model inspired by BitChord.
 * Tracks rolling affinity matrices for artists, genres, languages, and acoustic moods
 * applying an exponential time-decay half-life of 14 days.
 */
data class UserTasteProfile(
    val artistAffinities: Map<String, Float> = emptyMap(),
    val genreAffinities: Map<String, Float> = emptyMap(),
    val languageAffinities: Map<String, Float> = emptyMap(),
    val moodAffinities: Map<String, Float> = emptyMap(),
    val topRecentTrackIds: List<String> = emptyList(),
    val meaningfulPlaysCount: Int = 0,
    val isColdStart: Boolean = true,
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    companion object {
        private const val HALF_LIFE_DAYS = 14.0
        private const val HALF_LIFE_MS = HALF_LIFE_DAYS * 24.0 * 60.0 * 60.0 * 1000.0
        private val DECAY_LAMBDA = ln(2.0) / HALF_LIFE_MS

        val COLD_START_PROFILE = UserTasteProfile(
            artistAffinities = emptyMap(),
            genreAffinities = emptyMap(),
            languageAffinities = emptyMap(),
            moodAffinities = emptyMap(),
            topRecentTrackIds = emptyList(),
            meaningfulPlaysCount = 0,
            isColdStart = true,
            lastUpdatedMs = 0L
        )

        /**
         * Computes user taste profile from raw Room events applying time-decay scoring.
         */
        suspend fun buildProfile(
            listeningEventDao: ListeningEventDao,
            nowMs: Long = System.currentTimeMillis()
        ): UserTasteProfile {
            val meaningfulCount = listeningEventDao.getMeaningfulListeningCount()
            if (meaningfulCount < 5) {
                return COLD_START_PROFILE.copy(
                    meaningfulPlaysCount = meaningfulCount,
                    isColdStart = true,
                    lastUpdatedMs = nowMs
                )
            }

            // Fetch events from last 45 days
            val cutoff = nowMs - (45L * 24L * 60L * 60L * 1000L)
            val events = listeningEventDao.getEventsSince(cutoff)

            val artistScores = mutableMapOf<String, Float>()
            val genreScores = mutableMapOf<String, Float>()
            val languageScores = mutableMapOf<String, Float>()
            val moodScores = mutableMapOf<String, Float>()

            for (event in events) {
                val ageMs = (nowMs - event.timestamp).coerceAtLeast(0L)
                val decay = exp(-DECAY_LAMBDA * ageMs).toFloat()

                // Calculate interaction weight:
                // Completed play: 1.0 + completion + likeBonus (2.0)
                // Skipped play: heavily downweighted (0.2)
                var interactionWeight = event.completionRatio
                if (event.completed) interactionWeight += 1.0f
                if (event.liked) interactionWeight += 2.0f
                if (event.skipped) interactionWeight *= 0.2f

                val finalWeight = decay * interactionWeight

                // Artist
                if (event.artistName.isNotBlank() && !event.artistName.equals("Unknown", ignoreCase = true)) {
                    val aKey = event.artistName.trim().lowercase()
                    artistScores[aKey] = artistScores.getOrDefault(aKey, 0f) + finalWeight
                }

                // Genre
                if (event.genre.isNotBlank()) {
                    val gKey = event.genre.trim().lowercase()
                    genreScores[gKey] = genreScores.getOrDefault(gKey, 0f) + finalWeight

                    // Infer Mood from Genre
                    val inferredMood = MoodVibeAnalyzer.inferMoodFromGenre(gKey)
                    moodScores[inferredMood] = moodScores.getOrDefault(inferredMood, 0f) + finalWeight
                }

                // Language
                if (event.language.isNotBlank()) {
                    val lKey = event.language.trim().lowercase()
                    languageScores[lKey] = languageScores.getOrDefault(lKey, 0f) + finalWeight
                }
            }

            val recentTrackIds = listeningEventDao.getRecentUniqueTrackIds(limit = 20)

            return UserTasteProfile(
                artistAffinities = normalizeScores(artistScores),
                genreAffinities = normalizeScores(genreScores),
                languageAffinities = normalizeScores(languageScores),
                moodAffinities = normalizeScores(moodScores),
                topRecentTrackIds = recentTrackIds,
                meaningfulPlaysCount = meaningfulCount,
                isColdStart = false,
                lastUpdatedMs = nowMs
            )
        }

        private fun normalizeScores(scores: Map<String, Float>): Map<String, Float> {
            val maxScore = scores.values.maxOrNull() ?: return emptyMap()
            if (maxScore <= 0f) return emptyMap()
            return scores.mapValues { (_, score) -> (score / maxScore).coerceIn(0f, 1f) }
        }
    }
}
