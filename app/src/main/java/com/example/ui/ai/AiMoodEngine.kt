package com.example.ui.ai

import com.example.data.model.MusicTrack
import java.util.Calendar

data class HomeShelf(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<MusicTrack>
)

object AiMoodEngine {

    /**
     * Smart background recommendation engine:
     * Analyzes previous played songs, favorites, and last played track to determine
     * the listener's favorite singer, dominant language, and musical genres.
     * Generates clean, categorized shelves (like Apple Music or Spotify) without
     * any user-facing mood toggles or manual switches.
     */
    fun generatePersonalizedShelves(
        lastPlayedTrack: MusicTrack?,
        recentlyPlayed: List<MusicTrack>,
        favoriteTracks: List<MusicTrack>,
        catalogTracks: List<MusicTrack>
    ): List<HomeShelf> {
        if (catalogTracks.isEmpty()) return emptyList()

        // 1. Compile listening history
        val historyTracks = mutableListOf<MusicTrack>()
        if (lastPlayedTrack != null) historyTracks.add(lastPlayedTrack)
        historyTracks.addAll(recentlyPlayed)
        historyTracks.addAll(favoriteTracks)

        // 2. Extract Taste Profiles (Singer, Language, Genre)
        val singerFrequency = mutableMapOf<String, Int>()
        val languageFrequency = mutableMapOf<String, Int>()
        val genreFrequency = mutableMapOf<String, Int>()

        for (track in historyTracks) {
            // Count artist / singers
            val artistName = track.artist.trim()
            if (artistName.isNotBlank() && !artistName.equals("Unknown Artist", ignoreCase = true)) {
                singerFrequency[artistName] = singerFrequency.getOrDefault(artistName, 0) + 1
            }
            if (track.singers.isNotBlank()) {
                val singersList = track.singers.split(",", "&", "feat.", "ft.")
                for (s in singersList) {
                    val cleanS = s.trim()
                    if (cleanS.isNotBlank() && cleanS.length > 2) {
                        singerFrequency[cleanS] = singerFrequency.getOrDefault(cleanS, 0) + 1
                    }
                }
            }

            // Count language
            val lang = track.language.trim()
            if (lang.isNotBlank()) {
                languageFrequency[lang] = languageFrequency.getOrDefault(lang, 0) + 1
            }

            // Count genre
            val genre = track.genre.trim()
            if (genre.isNotBlank()) {
                genreFrequency[genre] = genreFrequency.getOrDefault(genre, 0) + 1
            }
        }

        // Top detected attributes
        val topSinger = singerFrequency.maxByOrNull { it.value }?.key
            ?: lastPlayedTrack?.artist?.ifBlank { null }
        val topLanguage = languageFrequency.maxByOrNull { it.value }?.key
            ?: lastPlayedTrack?.language?.ifBlank { "Hindi" } ?: "Hindi"
        val topGenre = genreFrequency.maxByOrNull { it.value }?.key
            ?: lastPlayedTrack?.genre?.ifBlank { "Electronic" } ?: "Electronic"

        // Time of day detection
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeShelfTitle = when (hour) {
            in 5..11 -> "Morning Focus & Flow"
            in 12..16 -> "Afternoon Boost"
            in 17..21 -> "Evening Wind Down"
            else -> "Late Night Chill & Relax"
        }
        val timeShelfSubtitle = when (hour) {
            in 5..11 -> "Fresh acoustic, lo-fi and uplifting sounds to start your day"
            in 12..16 -> "High-tempo beats and energetic rhythms"
            in 17..21 -> "Relaxing melodies and soulful transitions"
            else -> "Deep synthwave, ambient and peaceful night frequencies"
        }

        val shelves = mutableListOf<HomeShelf>()

        // SHELF 1: "Because you listened to [Last Song]" (if played)
        if (lastPlayedTrack != null) {
            val matchingVibe = catalogTracks.filter { track ->
                track.id != lastPlayedTrack.id && (
                    track.artist.equals(lastPlayedTrack.artist, ignoreCase = true) ||
                    track.genre.equals(lastPlayedTrack.genre, ignoreCase = true) ||
                    track.language.equals(lastPlayedTrack.language, ignoreCase = true)
                )
            }.ifEmpty {
                catalogTracks.filter { it.id != lastPlayedTrack.id }
            }.take(10)

            if (matchingVibe.isNotEmpty()) {
                shelves.add(
                    HomeShelf(
                        id = "because_last_played",
                        title = "Because you listened to \"${lastPlayedTrack.title}\"",
                        subtitle = "More in ${lastPlayedTrack.genre} • ${lastPlayedTrack.language}",
                        tracks = matchingVibe
                    )
                )
            }
        }

        // SHELF 2: "For Fans of [Top Singer]"
        if (topSinger != null) {
            val singerTracks = catalogTracks.filter { track ->
                track.artist.contains(topSinger, ignoreCase = true) ||
                track.singers.contains(topSinger, ignoreCase = true) ||
                track.album.contains(topSinger, ignoreCase = true) ||
                track.language.equals(topLanguage, ignoreCase = true) && track.genre.equals(topGenre, ignoreCase = true)
            }.take(10)

            if (singerTracks.isNotEmpty()) {
                shelves.add(
                    HomeShelf(
                        id = "singer_spotlight",
                        title = "For Fans of $topSinger",
                        subtitle = "Songs and similar artists you might love",
                        tracks = singerTracks
                    )
                )
            }
        }

        // SHELF 3: "Jump Back In" (User's Recently Played & Favorites)
        val jumpBackTracks = (recentlyPlayed + favoriteTracks).distinctBy { it.id }.take(10)
        if (jumpBackTracks.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "jump_back_in",
                    title = "Jump Back In",
                    subtitle = "Pick up right where you left off",
                    tracks = jumpBackTracks
                )
            )
        }

        // SHELF 4: "Recommended For You" (Smart blend of dominant language & genre)
        val recommendedTracks = catalogTracks.filter { track ->
            track.language.equals(topLanguage, ignoreCase = true) ||
            track.genre.equals(topGenre, ignoreCase = true) ||
            track.bitrateKbps >= 320
        }.sortedWith(
            compareByDescending<MusicTrack> { it.language.equals(topLanguage, ignoreCase = true) }
                .thenByDescending { it.genre.equals(topGenre, ignoreCase = true) }
                .thenByDescending { it.isLiked }
        ).take(12)

        if (recommendedTracks.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "recommended_for_you",
                    title = "Recommended For You",
                    subtitle = "Curated automatically from your $topLanguage & $topGenre taste",
                    tracks = recommendedTracks
                )
            )
        }

        // SHELF 5: Time of Day contextual vibe
        val timeTracks = catalogTracks.filter { track ->
            when (hour) {
                in 5..11 -> track.genre.contains("Acoustic", ignoreCase = true) || track.genre.contains("Pop", ignoreCase = true) || track.genre.contains("Chill", ignoreCase = true)
                in 12..16 -> track.genre.contains("Electronic", ignoreCase = true) || track.genre.contains("Rock", ignoreCase = true) || track.genre.contains("Hip-Hop", ignoreCase = true)
                in 17..21 -> track.genre.contains("Jazz", ignoreCase = true) || track.genre.contains("Soul", ignoreCase = true) || track.genre.contains("Pop", ignoreCase = true)
                else -> track.genre.contains("Ambient", ignoreCase = true) || track.genre.contains("Synthwave", ignoreCase = true) || track.genre.contains("Lo-Fi", ignoreCase = true) || track.genre.contains("Chillhop", ignoreCase = true)
            }
        }.ifEmpty { catalogTracks.shuffled() }.take(10)

        shelves.add(
            HomeShelf(
                id = "time_of_day_vibe",
                title = timeShelfTitle,
                subtitle = timeShelfSubtitle,
                tracks = timeTracks
            )
        )

        // SHELF 6: High Fidelity Master Streams
        val highDefTracks = catalogTracks.filter { it.bitrateKbps >= 320 }.take(10)
        if (highDefTracks.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "master_quality_320k",
                    title = "320 kbps Master Quality",
                    subtitle = "Studio precision audio with crisp acoustics",
                    tracks = highDefTracks
                )
            )
        }

        return shelves
    }
}
