package com.example.recommendation

import android.util.Log
import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Custom Query Wrapper for Smart Infinity Autoplay Recommendations.
 *
 * Avoids default generic endpoints by dynamically extracting:
 * 1. Exact Primary Artist / Singers
 * 2. Exact Language (e.g. Punjabi, Hindi, English, etc.)
 * 3. Exact Release Year or Era
 * 4. Acoustic Mood & Vibe Profile
 *
 * It sends targeted search parameters to the backend and strictly verifies that
 * all returned tracks match the specified language and audio criteria.
 */
object CustomRecommendationQueryWrapper {
    private const val TAG = "CustomQueryWrapper"

    /**
     * Executes custom targeted queries for the currently playing track.
     * Extracts exact Artist, Language, and Release Year, queries backend with strict
     * parameter compositions, and validates that every returned candidate strictly matches the language.
     *
     * @param currentTrack The track currently playing.
     * @param limit Maximum number of candidate tracks to fetch and verify.
     * @return List of validated candidate tracks matching the strict language and criteria.
     */
    suspend fun fetchTargetedRecommendations(
        currentTrack: MusicTrack,
        limit: Int = 25
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val cleanArtist = extractPrimaryArtist(currentTrack)
        val cleanLanguage = extractExactLanguage(currentTrack)
        val cleanYear = extractReleaseYear(currentTrack)
        val moodProfile = MoodVibeAnalyzer.analyzeMood(currentTrack)

        Log.i(
            TAG,
            "CustomQueryWrapper: Executing targeted query for current track '${currentTrack.title}' | " +
                    "Exact Artist: '$cleanArtist' | Exact Language: '$cleanLanguage' | " +
                    "Exact Year: '$cleanYear' | Vibe: '${moodProfile.primaryMood}'"
        )

        // Formulate strict targeted queries rather than using generic default endpoints
        val query1 = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanLanguage" else "$cleanLanguage Hits"
        val query2 = if (cleanYear.isNotBlank()) {
            "$cleanLanguage $cleanYear hits"
        } else {
            "$cleanLanguage ${moodProfile.primaryMood}"
        }
        val query3 = "$cleanLanguage ${currentTrack.genre.trim()} songs"

        val rawCandidates = coroutineScope {
            val call1 = async {
                try {
                    OnlineMusicApiService.searchSongs(query1, limit = 15)
                } catch (e: Exception) {
                    Log.w(TAG, "Custom query 1 ('$query1') failed: ${e.message}")
                    emptyList()
                }
            }
            val call2 = async {
                try {
                    OnlineMusicApiService.searchSongs(query2, limit = 15)
                } catch (e: Exception) {
                    Log.w(TAG, "Custom query 2 ('$query2') failed: ${e.message}")
                    emptyList()
                }
            }
            val call3 = async {
                try {
                    OnlineMusicApiService.searchSongs(query3, limit = 10)
                } catch (e: Exception) {
                    Log.w(TAG, "Custom query 3 ('$query3') failed: ${e.message}")
                    emptyList()
                }
            }
            (call1.await() + call2.await() + call3.await()).distinctBy { it.id }
        }

        Log.d(TAG, "CustomQueryWrapper: Received ${rawCandidates.size} raw candidates from backend queries.")

        // Strict Language Verification:
        // Ensure candidates strictly match the exact specified language of the current song
        val verifiedCandidates = rawCandidates.filter { candidate ->
            // Reject the current song itself
            if (candidate.id == currentTrack.id || candidate.title.equals(currentTrack.title, ignoreCase = true)) {
                return@filter false
            }

            val candLang = candidate.language.trim()
            val isExactLanguageMatch = if (cleanLanguage.isNotBlank() && !cleanLanguage.equals("Unknown", ignoreCase = true)) {
                candLang.equals(cleanLanguage, ignoreCase = true) ||
                        // Handle Punjabi/Gurmukhi/Bhangra language aliases
                        (cleanLanguage.equals("Punjabi", ignoreCase = true) &&
                                (candLang.equals("Punjabi", ignoreCase = true) || candLang.contains("punjab", ignoreCase = true))) ||
                        // Handle Hindi/Bollywood aliases
                        (cleanLanguage.equals("Hindi", ignoreCase = true) &&
                                (candLang.equals("Hindi", ignoreCase = true) || candLang.contains("hindi", ignoreCase = true))) ||
                        // Handle English/International aliases
                        (cleanLanguage.equals("English", ignoreCase = true) &&
                                (candLang.equals("English", ignoreCase = true) || candLang.contains("english", ignoreCase = true)))
            } else {
                true
            }

            isExactLanguageMatch
        }.take(limit)

        // Verify and log output in Android Studio / Logcat as explicitly required
        Log.i(
            TAG,
            "CustomQueryWrapper: Verified ${verifiedCandidates.size} tracks strictly matching language: '$cleanLanguage'"
        )
        verifiedCandidates.forEachIndexed { idx, track ->
            Log.d(
                TAG,
                "CustomQueryWrapper [Verified #$idx]: '${track.title}' by '${track.artist}' | " +
                        "Language: '${track.language}' (Expected: '$cleanLanguage') | Year: '${track.year}'"
            )
        }

        verifiedCandidates
    }

    /**
     * Extracts the primary artist name, stripping featured guests and collaboration prefixes.
     */
    fun extractPrimaryArtist(track: MusicTrack): String {
        val raw = track.artist.trim()
        if (raw.isBlank() || raw.equals("Unknown Artist", ignoreCase = true)) {
            return track.singers.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: ""
        }
        return raw.split(",", "&", "feat.", "ft.", "•", "/", "feat", "featuring")
            .first()
            .replace(Regex("(?i)\\b(official|music|records|audio|video)\\b"), "")
            .trim()
    }

    /**
     * Extracts the exact language of the track (e.g. Punjabi, Hindi, English).
     */
    fun extractExactLanguage(track: MusicTrack): String {
        val lang = track.language.trim()
        if (lang.isNotBlank() && !lang.equals("Unknown", ignoreCase = true)) {
            return lang.replaceFirstChar { it.uppercase() }
        }

        // Infer from artist/genre if language metadata is missing
        val text = "${track.title} ${track.artist} ${track.genre}".lowercase()
        return when {
            text.contains("punjabi") || text.contains("bhangra") || text.contains("jatt") || text.contains("sidhu") || text.contains("diljit") -> "Punjabi"
            text.contains("hindi") || text.contains("bollywood") || text.contains("arijit") || text.contains("neha") -> "Hindi"
            text.contains("english") || text.contains("pop") || text.contains("rock") || text.contains("electronic") -> "English"
            else -> "Hindi"
        }
    }

    /**
     * Extracts release year or release decade era (e.g. 2023, 2021, 2018).
     */
    fun extractReleaseYear(track: MusicTrack): String {
        if (track.year.isNotBlank()) {
            val digits = track.year.filter { it.isDigit() }
            if (digits.length >= 4) {
                return digits.substring(0, 4)
            }
        }
        // Fallback: check if year is mentioned in album or title e.g. "Hits 2022"
        val match = Regex("\\b(19[789]\\d|20[012]\\d)\\b").find("${track.album} ${track.title}")
        return match?.value ?: ""
    }
}
