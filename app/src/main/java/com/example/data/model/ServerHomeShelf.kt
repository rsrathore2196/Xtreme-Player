package com.example.data.model

import androidx.compose.runtime.Immutable
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogPlaylist

/**
 * Dynamic server-driven recommendation shelf fetched directly from YouTube Music.
 * Preserves server title, subtitle, track order, artwork, and genuine video IDs.
 */
@Immutable
data class ServerHomeShelf(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val tracks: List<MusicTrack> = emptyList(),
    val continuationToken: String? = null,
    val items: List<ServerHomeItem> = emptyList(),
    val providerId: String = "",
    val browseId: String = "",
    val browseParams: String = ""
)

/** A collection retains its browse identity; its play overlay is never a substitute song. */
@Immutable
sealed interface ServerHomeItem {
    val key: String
    data class Song(val track: MusicTrack) : ServerHomeItem { override val key = "song:${track.id}" }
    data class Album(val album: CatalogAlbum, val subtitle: String = "") : ServerHomeItem {
        override val key = "album:${album.id}:${album.browseParams.orEmpty()}"
    }
    data class Playlist(val playlist: CatalogPlaylist, val subtitle: String = "") : ServerHomeItem {
        override val key = "playlist:${playlist.id}:${playlist.browseParams.orEmpty()}"
    }
}

/** A cursor belongs to one native browse response and its actual account. */
data class HomeBrowseSnapshot(
    val browseId: String,
    val accountIdentity: String,
    val shelves: List<ServerHomeShelf> = emptyList(),
    val continuation: String? = null,
    val consumed: Set<String> = emptySet(),
    val fetchedAt: Long = 0L,
    val succeeded: Boolean = false,
    val emptyPages: Int = 0
)
