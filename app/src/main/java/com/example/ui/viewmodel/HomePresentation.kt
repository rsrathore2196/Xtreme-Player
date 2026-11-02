package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.data.model.ServerHomeItem
import com.example.data.model.HomeShelfIdentity
import com.example.recommendation.HomeRecommendationPolicy

internal const val RECENTLY_PLAYED_CARD_LIMIT = 4
enum class ShelfLoadStatus { LOADING, SUCCESS, EMPTY, ERROR }

@Immutable
data class HomeShelfPresentation(val key: String, val shelf: ServerHomeShelf? = null,
    val status: ShelfLoadStatus = ShelfLoadStatus.LOADING, val message: String = "")

internal fun initialHomeSkeletons() = List(3) { HomeShelfPresentation("native_initial_slot_$it") }

/** Keep the loading slot's identity at first publication and native identities on subsequent updates. */
internal fun presentNativeShelves(previous: List<HomeShelfPresentation>, shelves: List<ServerHomeShelf>,
    status: ShelfLoadStatus, message: String = ""): List<HomeShelfPresentation> {
    if (shelves.isEmpty()) return if (status == ShelfLoadStatus.LOADING) initialHomeSkeletons() else emptyList()
    val existing = previous.mapNotNull { it.shelf?.id?.let { id -> id to it } }.toMap()
    val cards = shelves.mapIndexed { index, shelf ->
        val old = existing[shelf.id]
        val key = old?.key ?: previous.getOrNull(index)?.takeIf { it.shelf == null }?.key ?: "server_shelf_${shelf.id}"
        val next = HomeShelfPresentation(key, shelf, ShelfLoadStatus.SUCCESS)
        if (old == next) old else next
    }
    return if (status == ShelfLoadStatus.LOADING) cards + HomeShelfPresentation(
        previous.lastOrNull()?.takeIf { it.shelf == null }?.key ?: "native_pending_page") else cards
}

/** The legacy broad revision is not a Home input; every actual event remains compared. */
internal fun sameLocalHomeSnapshot(previous: List<com.example.data.local.ActualPlaySnapshotRow>,
    next: List<com.example.data.local.ActualPlaySnapshotRow>): Boolean {
    if (previous.size != next.size) return false
    val old = previous.firstOrNull(); val new = next.firstOrNull()
    if (old?.total != new?.total || old?.artistInputRevision != new?.artistInputRevision ||
        old?.trackMetadataRevision != new?.trackMetadataRevision || old?.artistMetadataRevision != new?.artistMetadataRevision) return false
    return previous.indices.all { previous[it].event == next[it].event }
}

internal fun appendServerShelves(previous: List<ServerHomeShelf>, incoming: List<ServerHomeShelf>): List<ServerHomeShelf> =
    HomeShelfIdentity.deduplicate(previous + incoming).let { if (it == previous) previous else it }

internal fun refreshServerShelves(previous: List<ServerHomeShelf>, incoming: List<ServerHomeShelf>): List<ServerHomeShelf> =
    HomeShelfIdentity.refresh(previous, incoming)

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
    return artists.values.sortedByDescending { it.playCount }.take(15)
}
