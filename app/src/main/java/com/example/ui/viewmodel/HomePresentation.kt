package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.data.model.MusicTrack
import com.example.data.model.ServerHomeShelf
import com.example.data.model.ServerHomeItem
import com.example.recommendation.HomeRecommendationPolicy

internal const val RECENTLY_PLAYED_CARD_LIMIT = 4
internal const val INITIAL_SERVER_SHELF_TARGET = 3

private fun ServerHomeShelf.entries(): List<ServerHomeItem> = items.ifEmpty { tracks.map { ServerHomeItem.Song(it) } }

/** The legacy broad revision is not a Home input; every actual event remains compared. */
internal fun sameLocalHomeSnapshot(previous: List<com.example.data.local.ActualPlaySnapshotRow>,
    next: List<com.example.data.local.ActualPlaySnapshotRow>): Boolean {
    if (previous.size != next.size) return false
    val old = previous.firstOrNull(); val new = next.firstOrNull()
    if (old?.total != new?.total || old?.artistInputRevision != new?.artistInputRevision ||
        old?.trackMetadataRevision != new?.trackMetadataRevision || old?.artistMetadataRevision != new?.artistMetadataRevision) return false
    return previous.indices.all { previous[it].event == next[it].event }
}

/** Keep provider ordering and existing instances, merging only an identified continuation of the same shelf. */
internal fun appendServerShelves(previous: List<ServerHomeShelf>, incoming: List<ServerHomeShelf>): List<ServerHomeShelf> {
    val result = previous.toMutableList()
    for (shelf in incoming) {
        val index = result.indexOfFirst { it.id == shelf.id }
        if (index >= 0) {
            val old = result[index]
            val oldItems = old.entries()
            val ids = oldItems.mapTo(mutableSetOf()) { it.key }
            val added = shelf.entries().filter { ids.add(it.key) }
            if (added.isNotEmpty()) result[index] = old.copy(
                tracks = old.tracks + added.filterIsInstance<ServerHomeItem.Song>().map { it.track },
                items = if (old.items.isEmpty() && shelf.items.isEmpty()) emptyList() else oldItems + added)
        } else if (result.none { it.title == shelf.title && it.entries().map { item -> item.key } == shelf.entries().map { item -> item.key } }) {
            result.add(shelf)
        }
    }
    return if (result == previous) previous else result
}

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
