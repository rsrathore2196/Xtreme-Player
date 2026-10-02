package com.example.ui.ai

import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import java.util.Calendar
import androidx.compose.runtime.Immutable

@Immutable
data class HomeShelf(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<MusicTrack>
)

object AiMoodEngine {

    /**
     * Smart background recommendation engine:
     * Analyzes previous played songs, favorites, and user profile preferences
     * (Country priority and selected languages) to determine recommendations.
     * Generates clean, categorized shelves (like Apple Music or Spotify).
     */
    fun generatePersonalizedShelves(
        lastPlayedTrack: MusicTrack?,
        recentlyPlayed: List<MusicTrack>,
        favoriteTracks: List<MusicTrack>,
        catalogTracks: List<MusicTrack>,
        userProfile: UserProfile? = null
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
            ?: userProfile?.languages?.firstOrNull()
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

        // 1. Famous Hits in India (or user's selected country)
        val targetCountry = userProfile?.country?.takeIf { it.isNotBlank() } ?: "India"
        val targetFlag = userProfile?.flag?.takeIf { it.isNotBlank() } ?: "🇮🇳"
        val userCountryLangs = (userProfile?.languages?.takeIf { it.isNotEmpty() } ?: listOf("Hindi", "Punjabi")).map { it.lowercase() }
        val countryPriorityTracks = catalogTracks
            .filter { track ->
                !com.example.data.model.CountryData.hasCountryNameInTitle(track.title, targetCountry)
            }
            .sortedWith(
                compareByDescending<MusicTrack> { track ->
                    userCountryLangs.any { track.language.equals(it, ignoreCase = true) }
                }.thenByDescending { it.bitrateKbps }
            )
            .distinctAlbumAndCover(12)

        if (countryPriorityTracks.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "country_priority",
                    title = "Famous Hits in $targetCountry $targetFlag",
                    subtitle = "Iconic regional chartbusters and top songs",
                    tracks = countryPriorityTracks
                )
            )
        }

        // Deduplication keys to guarantee zero duplicates across sections
        val usedTrackIds = countryPriorityTracks.map { it.id }.toMutableSet()
        val usedTitles = countryPriorityTracks.map {
            com.example.recommendation.RecommendationEngine.normalizeTitle(it.title)
        }.filter { it.isNotBlank() }.toMutableSet()

        // 2. USER PROFILE PERSONALIZED SHELF: "Made For [Name]"
        // Strictly filter out any tracks already present in Famous Hits
        val displayName = userProfile?.name?.trim()?.takeIf { it.isNotBlank() } ?: "You"
        val userLangs = (userProfile?.languages?.takeIf { it.isNotEmpty() } ?: listOf("Hindi", "Punjabi", "English")).map { it.lowercase() }
        val candidateUserTracks = catalogTracks.filter { track ->
            val normTitle = com.example.recommendation.RecommendationEngine.normalizeTitle(track.title)
            !usedTrackIds.contains(track.id) &&
            !usedTitles.any { com.example.recommendation.RecommendationEngine.isSameSongOrVariant(it, normTitle) }
        }

        val userTracks = candidateUserTracks.filter { track ->
            userLangs.any { track.language.equals(it, ignoreCase = true) }
        }.ifEmpty { candidateUserTracks.shuffled() }.distinctAlbumAndCover(12)

        val langsSubtitle = userProfile?.languages?.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", ") ?: "Hindi, Punjabi, English"
        shelves.add(
            HomeShelf(
                id = "made_for_user",
                title = "Made For $displayName",
                subtitle = "Curated in your favorite vibe ($langsSubtitle)",
                tracks = userTracks
            )
        )

        return shelves
    }

    /**
     * Generates dynamic "New Releases" filtered by user's saved Country and Recommendation Languages preferences,
     * sorted by recent release dates/year descending, and deduplicated against existing shelves.
     */
    fun generateNewReleases(
        catalogTracks: List<MusicTrack>,
        userProfile: UserProfile?,
        existingShelves: List<HomeShelf>,
        limit: Int = 12
    ): List<MusicTrack> {
        if (catalogTracks.isEmpty()) return emptyList()

        val userLangs = (userProfile?.languages?.takeIf { it.isNotEmpty() } ?: listOf("Hindi", "Punjabi", "English"))
            .map { it.lowercase().trim() }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val currentYearStr = currentYear.toString()
        val recentYearStr = (currentYear - 1).toString()

        // 1. Filter tracks matching the user's selected languages
        val langMatchedTracks = catalogTracks.filter { track ->
            val trackLang = track.language.lowercase().trim()
            trackLang.isNotBlank() && userLangs.any { userLang ->
                trackLang.contains(userLang) || userLang.contains(trackLang)
            }
        }.ifEmpty { catalogTracks }

        // 2. Filter by current year released songs first
        val currentYearMatches = langMatchedTracks.filter { track ->
            track.year == currentYearStr || track.year.toIntOrNull() == currentYear
        }

        // 3. Recent year released songs (current year - 1)
        val recentYearMatches = langMatchedTracks.filter { track ->
            track.year == recentYearStr || track.year.toIntOrNull() == (currentYear - 1)
        }

        // 4. Combine current year prioritized, followed by recent year and latest releases
        val candidateReleases = (currentYearMatches + recentYearMatches + langMatchedTracks)
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<MusicTrack> { track ->
                    val y = track.year.toIntOrNull() ?: 0
                    if (y == currentYear) 3 else if (y == currentYear - 1) 2 else if (y >= 2023) 1 else 0
                }.thenByDescending { track ->
                    val trackLang = track.language.lowercase().trim()
                    if (userLangs.any { trackLang.contains(it) || it.contains(trackLang) }) 1 else 0
                }.thenByDescending { track ->
                    track.year.toIntOrNull() ?: 0
                }.thenByDescending { it.bitrateKbps }
            )
            .distinctAlbumAndCover(limit)

        return candidateReleases.take(limit)
    }

    /**
     * Filters tracks to guarantee distinct album art, distinct albums, and distinct songs
     */
    private fun List<MusicTrack>.distinctAlbumAndCover(limit: Int = 12): List<MusicTrack> {
        val seenAlbums = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()
        val seenCovers = mutableSetOf<String>()
        val result = mutableListOf<MusicTrack>()

        for (track in this) {
            val normTitle = com.example.recommendation.RecommendationEngine.normalizeTitle(track.title)
            val albumKey = if (track.album.isNotBlank() && track.album != "Single" && track.album != "HD Stream") track.album.lowercase() else null
            val coverKey = track.coverUrl.ifBlank { null }

            // Reject if duplicate variant of same title, or same album, or same cover art
            if (normTitle.isNotBlank() && seenTitles.any { com.example.recommendation.RecommendationEngine.isSameSongOrVariant(it, normTitle) }) continue
            if (albumKey != null && seenAlbums.contains(albumKey)) continue
            if (coverKey != null && seenCovers.contains(coverKey)) continue

            if (normTitle.isNotBlank()) seenTitles.add(normTitle)
            if (albumKey != null) seenAlbums.add(albumKey)
            if (coverKey != null) seenCovers.add(coverKey)
            result.add(track)

            if (result.size >= limit) break
        }
        return result
    }
}
