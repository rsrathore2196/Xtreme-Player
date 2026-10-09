package com.example.data.remote

import com.example.data.model.MusicTrack

/**
 * Recommendation Service strictly isolated to the YouTube Music API.
 *
 * ARCHITECTURAL RULE (STRICT):
 * All home screen song recommendations, trending charts, featured playlists, album arts,
 * and Infinite Play / Autoplay next-track suggestions MUST be fetched EXCLUSIVELY
 * from the YouTube Music API.
 * Under NO circumstances should the Internet Archive API or JioSaavn API be queried
 * for HomeScreen recommendations, carousels, album arts, or autoplay queues.
 * The multi-source fallback mechanism is strictly reserved for user-initiated search queries.
 */
object YouTubeMusicRecommendationService {

    /**
     * Fetches trending recommendation songs for the Home Screen exclusively via YouTube Music API.
     */
    suspend fun getTrendingRecommendations(limit: Int = 30): List<MusicTrack> {
        return YouTubeMusicApiService.getTrendingSongs(limit = limit)
    }

    /**
     * Fetches profile/language/country tailored recommendation tracks exclusively via YouTube Music API.
     */
    suspend fun getProfileRecommendations(
        country: String,
        languages: List<String>,
        limit: Int = 30
    ): List<MusicTrack> {
        return YouTubeMusicApiService.getTrendingSongsForProfile(
            country = country,
            languages = languages,
            limit = limit
        )
    }

    /**
     * Fetches Punjabi hits exclusively via YouTube Music API.
     */
    suspend fun getPunjabiRecommendations(limit: Int = 20): List<MusicTrack> {
        return YouTubeMusicApiService.getPunjabiHits(limit = limit)
    }

    /**
     * Fetches Era hits exclusively via YouTube Music API.
     */
    suspend fun getEraRecommendations(limit: Int = 20): List<MusicTrack> {
        return YouTubeMusicApiService.getEraHits(limit = limit)
    }

    /**
     * Fetches genre-specific recommendation shelves exclusively via YouTube Music API.
     */
    suspend fun getGenreRecommendations(genre: String, limit: Int = 20): List<MusicTrack> {
        return YouTubeMusicApiService.getGenreTracks(genre = genre, limit = limit)
    }
}
