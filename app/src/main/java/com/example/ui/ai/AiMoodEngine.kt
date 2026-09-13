package com.example.ui.ai

import com.example.data.model.MusicTrack
import java.util.Calendar

data class AiMoodCategory(
    val id: String,
    val title: String,
    val emoji: String,
    val description: String,
    val targetGenres: List<String>
)

data class AiMoodProfile(
    val activeMoodId: String,
    val detectedMoodTitle: String,
    val moodEmoji: String,
    val reasoning: String,
    val detectedLanguage: String,
    val detectedMusicType: String,
    val lastSongTitle: String?,
    val matchingTracks: List<MusicTrack>
)

object AiMoodEngine {

    val availableMoods: List<AiMoodCategory> = listOf(
        AiMoodCategory(
            id = "auto",
            title = "✨ Smart AI",
            emoji = "✨",
            description = "Adapts in real-time to your listening history & time of day",
            targetGenres = emptyList()
        ),
        AiMoodCategory(
            id = "chill",
            title = "🌙 Chill & Relax",
            emoji = "🌙",
            description = "Smooth ambient, lo-fi & peaceful twilight sounds",
            targetGenres = listOf("Lo-Fi", "Lofi", "Ambient", "Chill", "Acoustic", "Soul", "Slow")
        ),
        AiMoodCategory(
            id = "energetic",
            title = "⚡ Workout & Boost",
            emoji = "⚡",
            description = "High-octane electronic, synthwave & pulse-pounding beats",
            targetGenres = listOf("Electronic", "Synthwave", "EDM", "Dance", "Upbeat", "Workout")
        ),
        AiMoodCategory(
            id = "romantic",
            title = "💖 Soulful & Romantic",
            emoji = "💖",
            description = "Heartfelt melodies, sweet vocals & passionate love songs",
            targetGenres = listOf("Romantic", "Bollywood", "Acoustic", "Pop", "Melodic", "Love")
        ),
        AiMoodCategory(
            id = "focus",
            title = "🎯 Deep Focus",
            emoji = "🎯",
            description = "Subtle rhythms & minimal distractions for peak flow",
            targetGenres = listOf("Synthwave", "Electronic", "Ambient", "Instrumental", "Focus")
        ),
        AiMoodCategory(
            id = "party",
            title = "🔥 Party & Dance",
            emoji = "🔥",
            description = "Heavy bass, club anthems & viral party tracks",
            targetGenres = listOf("Electronic", "Dance", "Hip-Hop", "HipHop", "Club", "Party", "Remix")
        ),
        AiMoodCategory(
            id = "melodic",
            title = "🌧️ Acoustic & Vibe",
            emoji = "🌧️",
            description = "Raw acoustic guitar, soothing piano & introspective vocals",
            targetGenres = listOf("Acoustic", "Melodic", "Indie", "Folk", "Lo-Fi", "Lofi")
        )
    )

    fun evaluateMood(
        selectedMoodId: String = "auto",
        lastPlayedTrack: MusicTrack?,
        recentlyPlayed: List<MusicTrack>,
        favoriteTracks: List<MusicTrack>,
        allTracks: List<MusicTrack>
    ): AiMoodProfile {
        val lastTrack = lastPlayedTrack ?: recentlyPlayed.firstOrNull()
        val language = lastTrack?.language?.ifBlank { "Hindi" } ?: "Hindi"
        val musicType = lastTrack?.genre?.ifBlank { "Electronic" } ?: "Pop"

        // Analyze current time of day
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeMoodId = when (hour) {
            in 5..10 -> "energetic"
            in 11..16 -> "focus"
            in 17..21 -> "party"
            else -> "chill"
        }

        // Determine effective mood
        val resolvedMoodId = if (selectedMoodId == "auto") {
            // Factor in last played track's genre if recent
            val genreLower = musicType.lowercase()
            when {
                genreLower.contains("lo-fi") || genreLower.contains("lofi") || genreLower.contains("ambient") -> "chill"
                genreLower.contains("romantic") || genreLower.contains("love") || genreLower.contains("acoustic") -> "romantic"
                genreLower.contains("edm") || genreLower.contains("dance") || genreLower.contains("party") -> "party"
                genreLower.contains("synth") || genreLower.contains("electronic") -> timeMoodId
                else -> timeMoodId
            }
        } else {
            selectedMoodId
        }

        val targetCategory = availableMoods.firstOrNull { it.id == resolvedMoodId }
            ?: availableMoods[1]

        val reasoning = if (selectedMoodId == "auto") {
            if (lastTrack != null) {
                "Smart AI detected: ${targetCategory.emoji} ${targetCategory.title} based on your last play \"${lastTrack.title}\" ($language • $musicType) & current vibe."
            } else {
                "Smart AI selected: ${targetCategory.emoji} ${targetCategory.title} tailored for this hour of the day."
            }
        } else {
            "Tuned to ${targetCategory.emoji} ${targetCategory.title} • Filtering only songs matching this mood in $language & $musicType."
        }

        // Filter and rank songs matching the mood & user liking
        val targetGenres = targetCategory.targetGenres
        val matchingTracks = allTracks.filter { track ->
            if (targetGenres.isEmpty()) true
            else {
                targetGenres.any { g ->
                    track.genre.contains(g, ignoreCase = true) ||
                    track.title.contains(g, ignoreCase = true) ||
                    track.album.contains(g, ignoreCase = true)
                } || (track.language.equals(language, ignoreCase = true) && (track.isLiked || track.bitrateKbps >= 320))
            }
        }.sortedWith(
            compareByDescending<MusicTrack> { it.language.equals(language, ignoreCase = true) }
                .thenByDescending { it.genre.equals(musicType, ignoreCase = true) }
                .thenByDescending { it.isLiked }
        ).ifEmpty {
            allTracks
        }

        return AiMoodProfile(
            activeMoodId = selectedMoodId,
            detectedMoodTitle = targetCategory.title,
            moodEmoji = targetCategory.emoji,
            reasoning = reasoning,
            detectedLanguage = language,
            detectedMusicType = musicType,
            lastSongTitle = lastTrack?.title,
            matchingTracks = matchingTracks
        )
    }
}
