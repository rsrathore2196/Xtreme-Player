package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.data.model.MusicTrack
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.UnifiedRecommendationEngine.Shelf

@Immutable
data class SectionState(
    val title: String = "",
    val subtitle: String = "",
    val tracks: List<MusicTrack> = emptyList(),
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String = ""
)

internal fun SectionState.limitShelf() = if (tracks.size <= HomeRecommendationPolicy.MAX_SHELF_TRACKS) this
    else copy(tracks = tracks.take(HomeRecommendationPolicy.MAX_SHELF_TRACKS))

/** Verification finishing before presentation is still loading, not an empty/error result. */
internal fun SectionState.finishPreparation(hasVerifiedCandidate: Boolean): SectionState {
    val next = when {
        tracks.isNotEmpty() -> copy(isLoading = false)
        hasVerifiedCandidate -> copy(isLoading = true, isError = false, errorMessage = "")
        else -> copy(isLoading = false, isError = true,
            errorMessage = "Recommendations are temporarily unavailable. Tap Retry to try again.")
    }
    return if (next == this) this else next
}

@Immutable
data class RecommendationCards(val quick: SectionState, val era: SectionState,
    val chart: SectionState, val personalized: SectionState,
    val similarArtists: SectionState = SectionState(), val inspiredTrack: SectionState = SectionState())

@Immutable
data class MostListenedArtist(
    val name: String,
    val playCount: Int,
    val tracks: List<MusicTrack>
)
internal fun RecommendationCards.section(shelf: Shelf): SectionState = when (shelf) {
    Shelf.QUICK -> quick
    Shelf.ERA -> era
    Shelf.CHART -> chart
    Shelf.PERSONALIZED -> personalized
    Shelf.SIMILAR -> similarArtists
    Shelf.INSPIRED -> inspiredTrack
}

internal fun RecommendationCards.withSection(shelf: Shelf, section: SectionState): RecommendationCards {
    if (this.section(shelf) == section) return this
    return when (shelf) {
        Shelf.QUICK -> copy(quick = section)
        Shelf.ERA -> copy(era = section)
        Shelf.CHART -> copy(chart = section)
        Shelf.PERSONALIZED -> copy(personalized = section)
        Shelf.SIMILAR -> copy(similarArtists = section)
        Shelf.INSPIRED -> copy(inspiredTrack = section)
    }
}

/** Full cache reads may fill untouched sections, never replace a newer live result. */
internal fun RecommendationCards.enrichFromCache(cached: RecommendationCards,
    preview: RecommendationCards): RecommendationCards = Shelf.entries.fold(this) { result, shelf ->
    val current = result.section(shelf)
    val replacement = cached.section(shelf)
    if (current != preview.section(shelf) || replacement.tracks.isEmpty()) result
    else result.withSection(shelf, replacement)
}
