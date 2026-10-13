package com.example.data.repository

import android.text.Html
import java.text.Normalizer
import java.util.Locale
import com.example.data.model.MusicTrack

/** Provider IDs are kept separate from display names and from TheAudioDB IDs. */
data class CatalogArtist(val id: String, val name: String, val musicBrainzId: String = "", val aliases: List<String> = emptyList(), val image: String = "")
data class CatalogAlbum(val id: String, val title: String, val artists: List<CatalogArtist>, val image: String, val year: String)
data class CatalogPlaylist(val id: String, val title: String, val image: String)
/** Independent provider cursors and verified collection tracks waiting for the next ten rows. */
data class LosslessCatalogContinuation(
    val query: String, val standardPage: Int? = null, val archivePage: Int? = null,
    val pendingTracks: List<MusicTrack> = emptyList()
)
data class CatalogPage(
    val tracks: List<MusicTrack> = emptyList(), val albums: List<CatalogAlbum> = emptyList(),
    val artists: List<CatalogArtist> = emptyList(), val playlists: List<CatalogPlaylist> = emptyList(),
    val nextPage: Int? = null, val warning: String? = null,
    val losslessContinuation: LosslessCatalogContinuation? = null
)
sealed interface CatalogDestination {
    val key: String
    data class Artist(val artist: CatalogArtist) : CatalogDestination { override val key = "artist:${artist.id.ifBlank { CatalogNames.normalize(artist.name) }}" }
    data class Album(val album: CatalogAlbum) : CatalogDestination { override val key = "album:${album.id}" }
    data class Playlist(val playlist: CatalogPlaylist) : CatalogDestination { override val key = "playlist:${playlist.id}" }
}
object CatalogNames {
    fun clean(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    fun normalize(value: String): String = Normalizer.normalize(clean(value), Normalizer.Form.NFKD)
        .replace(Regex("(?<=\\p{IsLatin})\\p{M}+"), "").lowercase(Locale.ROOT).replace('&', ' ')
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
        .replace(Regex("\\b(?:\\p{L} ){1,3}\\p{L}\\b")) { it.value.replace(" ", "") }
    fun same(a: String, b: String): Boolean = normalize(a).isNotEmpty() && normalize(a) == normalize(b)
    fun score(query: String, title: String, credits: List<String> = emptyList()): Int {
        val q = normalize(query); val t = normalize(title)
        if (q.isBlank()) return 0
        if (q == t) return 100
        if (" $t ".contains(" $q ")) return 80
        if (q.split(' ').all { token -> t.split(' ').any { it.startsWith(token) } }) return 60
        return if (credits.any { score(q, it) >= 60 }) 40 else 0
    }
}
