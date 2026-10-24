package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.data.model.MusicTrack
import com.example.recommendation.HomeUserMode
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import com.example.recommendation.UserTasteProfile
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Inspect only emitted shelves and retain their lists when no failed recording is removed. */
internal fun com.example.recommendation.UnifiedRecommendationEngine.Batch.availableShelfTracks(
    unavailable: Map<String, Long>, now: Long, sameSeed: Boolean
): Map<Shelf, List<MusicTrack>> = requested.mapNotNull { shelf ->
    if (!sameSeed && (shelf == Shelf.SIMILAR || shelf == Shelf.INSPIRED)) return@mapNotNull null
    val tracks = when (shelf) {
        Shelf.QUICK -> quick; Shelf.ERA -> era; Shelf.CHART -> chart.orEmpty()
        Shelf.PERSONALIZED -> personalized; Shelf.SIMILAR -> similarArtists; Shelf.INSPIRED -> inspiredTrack
    }
    val usable = if (tracks.any { (unavailable[it.id] ?: 0L) > now })
        tracks.filter { (unavailable[it.id] ?: 0L) <= now } else tracks
    (shelf to usable).takeIf { usable.isNotEmpty() }
}.toMap()

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

internal fun SectionState.limitShelf(): SectionState {
    val unique = tracks.distinctBy { it.id }.take(HomeRecommendationPolicy.MAX_SHELF_TRACKS)
    return if (unique.size == tracks.size) this else copy(tracks = unique)
}

/** Also upgrades retained/older cache headings without changing their tracks or keys. */
internal fun SectionState.withSeedHeading(shelf: Shelf, seed: String): SectionState {
    val (heading, legacySubtitle) = when (shelf) {
        Shelf.SIMILAR -> "More From" to "Related to "
        Shelf.INSPIRED -> "Because You Listened" to "Inspired by "
        else -> return this
    }
    val name = seed.trim().ifBlank {
        subtitle.takeIf { it.startsWith(legacySubtitle) }?.removePrefix(legacySubtitle)?.trim().orEmpty()
    }
    if (name.isBlank()) return this
    val title = "$heading '$name'"
    return if (this.title == title && subtitle.isBlank()) this else copy(title = title, subtitle = "")
}

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
    val artists = cached.associateByTo(linkedMapOf()) { HomeRecommendationPolicy.mainArtistKey(it.name) }
    for (track in tracks) for (name in HomeRecommendationPolicy.mainArtists(track)) {
        val key = HomeRecommendationPolicy.mainArtistKey(name)
        val previous = artists[key]
        val display = previous?.name ?: name
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

/** Bounded memoization of the existing policy, not a second recommendation engine.
 * Upstream recording/artist/album constraints are part of the key: an earlier shelf
 * may change diversity downstream, but an unrelated later shelf cannot rerank it.
 * Each preparation owns its memo copy, so no lock is held across interaction yields. */
internal class IncrementalShelfPreparation {
    private data class LocalKey(val tracks: List<MusicTrack>, val taste: UserTasteProfile,
        val recent: List<MusicTrack>, val exposure: Set<String>, val enabled: Boolean)
    private data class Constraint(val aliases: List<String>, val artists: List<String>, val album: String)
    private data class Entry(val key: LocalKey, val local: List<MusicTrack>,
        val upstream: List<Constraint>, val selected: List<MusicTrack>)
    private val memo = AtomicReference<Map<Shelf, Entry>>(emptyMap())

    suspend fun prepare(pools: Map<Shelf, List<MusicTrack>>, eligible: Set<Shelf>,
        taste: UserTasteProfile, recent: List<MusicTrack>, exposure: Set<String>,
        relevantSeed: Boolean, checkpoint: suspend () -> Unit): Map<Shelf, List<MusicTrack>> {
        val before = memo.get()
        val next = mutableMapOf<Shelf, Entry>()
        val selected = linkedMapOf<Shelf, List<MusicTrack>>()
        val seen = HomeRecommendationPolicy.Recordings()
        val excluded = HomeRecommendationPolicy.Recordings().apply { recent.forEach { add(it) } }
        val upstream = mutableListOf<Constraint>()
        for (shelf in listOf(Shelf.CHART, Shelf.ERA, Shelf.PERSONALIZED, Shelf.QUICK, Shelf.SIMILAR, Shelf.INSPIRED)) {
            checkpoint()
            val enabled = shelf in eligible && (shelf != Shelf.PERSONALIZED || !taste.isColdStart) &&
                (shelf !in setOf(Shelf.SIMILAR, Shelf.INSPIRED) || relevantSeed)
            val key = LocalKey(pools[shelf].orEmpty(), taste, recent,
                if (shelf == Shelf.CHART) emptySet() else exposure, enabled)
            val previous = before[shelf]
            val local = if (previous?.key == key) previous.local else if (!enabled) emptyList() else {
                fun notRecent(track: MusicTrack) = !excluded.contains(track) && taste.keyFor(track) !in taste.recentRecordings
                if (shelf == Shelf.CHART) HomeRecommendationPolicy.originals(key.tracks).filter(::notRecent)
                else key.tracks.filter { track ->
                    val recording = taste.keyFor(track)
                    notRecent(track) && ((taste.skipCounts[recording] ?: 0) < 2 || recording in taste.favoriteRecordings)
                }.sortedBy { HomeRecommendationPolicy.metadataKey(it) in exposure }
            }
            val constraints = upstream.toList()
            val tracks = if (previous?.key == key && previous.upstream == constraints) {
                previous.selected.also { result -> result.forEach { seen.add(it) } }
            } else if (shelf == Shelf.CHART) {
                local.take(HomeRecommendationPolicy.MAX_SHELF_TRACKS).also { result -> result.forEach { seen.add(it) } }
            } else HomeRecommendationPolicy.diversify(local, HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
            next[shelf] = Entry(key, local, constraints, tracks)
            selected[shelf] = tracks
            tracks.forEach { track -> upstream.add(Constraint(HomeRecommendationPolicy.aliases(track),
                HomeRecommendationPolicy.artists(track), HomeRecommendationPolicy.normalize(track.album))) }
        }
        currentCoroutineContext().ensureActive()
        memo.compareAndSet(before, next)
        return selected
    }
}

/** Only track/exclusion changes invalidate a section's final recent-song filter. */
internal class RecentRecommendationFilter {
    private data class Entry(val tracks: List<MusicTrack>, val recent: List<MusicTrack>,
        val recordings: Set<String>, val aliases: Map<String, String>, val result: List<MusicTrack>)
    private val memo = AtomicReference<Map<Shelf, Entry>>(emptyMap())
    fun prepare(cards: RecommendationCards, recent: List<MusicTrack>, taste: UserTasteProfile): RecommendationCards {
        val before = memo.get()
        val next = mutableMapOf<Shelf, Entry>()
        val excluded = HomeRecommendationPolicy.Recordings().apply { recent.forEach { add(it) } }
        val result = Shelf.entries.fold(cards) { prepared, shelf ->
            val section = cards.section(shelf)
            val old = before[shelf]
            val tracks = if (old != null && old.tracks == section.tracks && old.recent == recent &&
                old.recordings == taste.recentRecordings && old.aliases == taste.recordingAliases) old.result
            else section.tracks.filterNot { excluded.contains(it) || taste.keyFor(it) in taste.recentRecordings }
                .let { if (it.size == section.tracks.size) section.tracks else it }
            next[shelf] = Entry(section.tracks, recent, taste.recentRecordings, taste.recordingAliases, tracks)
            prepared.withSection(shelf, if (tracks === section.tracks) section else
                section.copy(tracks = tracks, isLoaded = tracks.isNotEmpty()))
        }
        memo.compareAndSet(before, next)
        return result
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
