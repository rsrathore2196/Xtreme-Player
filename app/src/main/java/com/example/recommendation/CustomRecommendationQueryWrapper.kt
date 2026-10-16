package com.example.recommendation

import com.example.data.model.MusicTrack

/** Shared metadata helpers. Discovery and matching live in UnifiedRecommendationEngine. */
object CustomRecommendationQueryWrapper {
    fun extractPrimaryArtist(track: MusicTrack): String = HomeRecommendationPolicy.rawArtists(track).firstOrNull().orEmpty()
    fun extractExactLanguage(track: MusicTrack): String = track.language.takeIf(HomeRecommendationPolicy::known).orEmpty()
    fun extractReleaseYear(track: MusicTrack): String = track.year.takeIf { (it.toIntOrNull() ?: 0) in 1900..2100 }.orEmpty()
}
