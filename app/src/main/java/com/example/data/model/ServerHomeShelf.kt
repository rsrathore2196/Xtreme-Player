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
    data class Song(val track: MusicTrack, val videoType: String = "", val landscapeThumbnail: String = "",
        val squareSongThumbnail: String = "") : ServerHomeItem { override val key = "song:${track.id}" }
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

enum class NativeShelfKind { STANDARD, QUICK_PICKS, MUSIC_VIDEOS }

/** Native song Trending shelves keep square cards; playlist charts keep their own art. */
fun ServerHomeShelf.requiresSongArtwork(): Boolean {
    val heading = HomeShelfIdentity.title(title)
    return (heading == "trending" || heading.startsWith("trending ")) &&
        (tracks.isNotEmpty() || items.any { it is ServerHomeItem.Song })
}

/** Exact native headings plus provider music-video metadata; song titles never classify shelves. */
fun ServerHomeShelf.nativeKind(): NativeShelfKind {
    val entries = items.ifEmpty { tracks.map { ServerHomeItem.Song(it) } }
    if (entries.isEmpty() || entries.any { it !is ServerHomeItem.Song }) return NativeShelfKind.STANDARD
    val heading = HomeShelfIdentity.title(title)
    if (heading == "quick picks") return NativeShelfKind.QUICK_PICKS
    return if (heading in setOf("music videos", "new music videos") && entries.all {
        (it as ServerHomeItem.Song).videoType in setOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC")
    }) NativeShelfKind.MUSIC_VIDEOS else NativeShelfKind.STANDARD
}
