package com.example.data.remote

import com.example.data.model.MusicTrack

/**
 * Recommendation Service strictly isolated to the JioSaavn API.
 *
 * ARCHITECTURAL RULE (STRICT):
 * All home screen song recommendations, trending charts, featured playlists, and
 * album arts MUST be fetched EXCLUSIVELY from the default JioSaavn API.
 * The Internet Archive API is NEVER queried for HomeScreen recommendations, carousels,
 * or album art displays.
 */
object JioSaavnRecommendationService {

    /**
     * Fetches trending recommendation songs for the Home Screen exclusively via JioSaavn.
     */
    suspend fun getTrendingRecommendations(limit: Int = 30): List<MusicTrack> {
        return OnlineMusicApiService.getTrendingSongs(limit = limit)
    }

    /**
     * Fetches profile/language/country tailored recommendation tracks exclusively via JioSaavn.
     */
    suspend fun getProfileRecommendations(
        country: String,
        languages: List<String>,
        limit: Int = 30
    ): List<MusicTrack> {
        return OnlineMusicApiService.getTrendingSongsForProfile(
            country = country,
            languages = languages,
            limit = limit
        )
    }

    /**
     * Fetches Punjabi hits exclusively via JioSaavn.
     */
    suspend fun getPunjabiRecommendations(limit: Int = 20): List<MusicTrack> {
        return OnlineMusicApiService.getSongsByGenre(genre = "punjabi", limit = limit)
    }

    /**
     * Fetches genre-specific recommendation shelves exclusively via JioSaavn.
     */
    suspend fun getGenreRecommendations(genre: String, limit: Int = 20): List<MusicTrack> {
        return OnlineMusicApiService.getSongsByGenre(genre = genre, limit = limit)
    }

    /**
     * Fetches mood / decade recommendation shelves exclusively via JioSaavn.
     */
    suspend fun getDecadeRecommendations(decade: String, limit: Int = 20): List<MusicTrack> {
        return OnlineMusicApiService.getSongsByGenre(genre = decade, limit = limit)
    }
}
