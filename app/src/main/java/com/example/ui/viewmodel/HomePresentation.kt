package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.data.model.MusicTrack
import com.example.recommendation.HomeUserMode
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
    else copy(tracks = tracks.subList(0, HomeRecommendationPolicy.MAX_SHELF_TRACKS))

/** Prioritize bootstrap only while it is eligible; failed attempts cannot starve other cards. */
internal fun Set<Shelf>.initialCardDemand(bootstrap: Set<Shelf>, automaticStartup: Boolean): Set<Shelf> =
    if (automaticStartup) intersect(bootstrap).takeIf { it.isNotEmpty() } ?: this else this

/** Pending verification is not an error; silent work does not introduce a new skeleton. */
internal fun SectionState.finishPreparation(hasVerifiedCandidate: Boolean, showPending: Boolean = true): SectionState {
    val next = when {
        tracks.isNotEmpty() -> copy(isLoading = false)
        hasVerifiedCandidate -> copy(isLoading = showPending, isError = false, errorMessage = "")
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

/** Preserve long-term cached counts until the complete historical baseline is ready. */
internal fun addPendingArtistPlays(cached: List<MostListenedArtist>, tracks: Collection<MusicTrack>): List<MostListenedArtist> {
    if (tracks.isEmpty()) return cached
    val artists = cached.associateByTo(linkedMapOf()) { HomeRecommendationPolicy.normalize(it.name) }
    for (track in tracks) for (key in HomeRecommendationPolicy.artists(track)) {
        val previous = artists[key]
        val display = previous?.name ?: (track.artist + "," + track.singers).split(',')
            .firstOrNull { HomeRecommendationPolicy.normalize(it) == key }?.trim() ?: key
        artists[key] = MostListenedArtist(display, (previous?.playCount ?: 0) + 1,
            (listOf(track) + previous?.tracks.orEmpty()).distinctBy { it.id }.take(3))
    }
    return artists.values.sortedByDescending { it.playCount }.take(20)
}
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

/** Eligibility uses authoritative history, not the initially empty taste StateFlow.
 * Retained verified sections survive a history error or an incomplete taste load. */
internal fun eligibleHomeShelves(
    mode: HomeUserMode,
    isColdStart: Boolean,
    hasSeed: Boolean,
    cards: RecommendationCards
): Set<com.example.recommendation.UnifiedRecommendationEngine.Shelf> {
    return buildSet {
        add(Shelf.QUICK); add(Shelf.CHART)
        if (mode == HomeUserMode.NEW_USER) return@buildSet
        if (mode != HomeUserMode.LOADING || cards.era.tracks.isNotEmpty()) add(Shelf.ERA)
        if (!isColdStart || cards.personalized.tracks.isNotEmpty()) add(Shelf.PERSONALIZED)
        if (hasSeed || cards.similarArtists.tracks.isNotEmpty()) add(Shelf.SIMILAR)
        if (hasSeed || cards.inspiredTrack.tracks.isNotEmpty()) add(Shelf.INSPIRED)
    }
}
