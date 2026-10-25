package com.example.data.repository

import android.text.Html
import java.text.Normalizer
import java.util.Locale
import com.example.data.model.MusicTrack

/** Provider IDs are kept separate from display names and from TheAudioDB IDs. */
data class CatalogArtist(val id: String, val name: String, val musicBrainzId: String = "", val aliases: List<String> = emptyList(), val image: String = "")
enum class CatalogProvider { JIOSAAVN, YOUTUBE_MUSIC }
enum class ExploreCategoryKind { MOOD, GENRE, OTHER }

@androidx.compose.runtime.Immutable
data class ExploreCategory(val title: String, val browseId: String, val params: String? = null,
    val kind: ExploreCategoryKind = ExploreCategoryKind.OTHER, val sectionTitle: String = "",
    val provider: CatalogProvider = CatalogProvider.YOUTUBE_MUSIC) {
    val key: String get() = "${provider.name}:$browseId:${params.orEmpty()}"
}

data class CatalogAlbum(val id: String, val title: String, val artists: List<CatalogArtist>, val image: String, val year: String,
    val provider: CatalogProvider = CatalogProvider.JIOSAAVN, val browseParams: String? = null,
    val countryCode: String = "US", val languageCode: String = "en")
@androidx.compose.runtime.Immutable
data class CatalogPlaylist(val id: String, val title: String, val image: String,
    val provider: CatalogProvider = CatalogProvider.JIOSAAVN, val browseParams: String? = null,
    val countryCode: String = "US", val languageCode: String = "en")
/** Immutable next-page request; a token is never consumed before its result is accepted. */
data class CatalogBrowseContinuation(val browseId: String, val params: String?, val token: String,
    val countryCode: String = "US", val languageCode: String = "en",
    val seenTokens: Set<String> = emptySet(), val page: Int = 2)
/** Independent song-provider cursors and results waiting for the next ten rows. */
data class TrackSearchContinuation(
    val query: String, val standardPage: Int? = null,
    val pendingTracks: List<MusicTrack> = emptyList(),
    val youtubeContinuation: String? = null, val youtubeHasMore: Boolean = true
)
/** Opaque YouTube Music cursor, kept independent of JioSaavn's numeric pages. */
@androidx.compose.runtime.Immutable
data class ArtistCatalogContinuation(val query: String, val token: String)
data class CatalogPage(
    val tracks: List<MusicTrack> = emptyList(), val albums: List<CatalogAlbum> = emptyList(),
    val artists: List<CatalogArtist> = emptyList(), val playlists: List<CatalogPlaylist> = emptyList(),
    val nextPage: Int? = null, val warning: String? = null,
    val trackSearchContinuation: TrackSearchContinuation? = null,
    val artistContinuation: ArtistCatalogContinuation? = null,
    val resolvedArtist: CatalogArtist? = null,
    val browseContinuation: CatalogBrowseContinuation? = null
)
sealed interface CatalogDestination {
    val key: String
    data class Artist(val artist: CatalogArtist) : CatalogDestination { override val key = "artist:${artist.id.ifBlank { CatalogNames.normalize(artist.name) }}" }
    data class Album(val album: CatalogAlbum) : CatalogDestination { override val key =
        if (album.provider == CatalogProvider.JIOSAAVN) "album:${album.id}" else "album:${album.provider}:${album.id}:${album.browseParams.orEmpty()}" }
    data class Playlist(val playlist: CatalogPlaylist) : CatalogDestination { override val key =
        if (playlist.provider == CatalogProvider.JIOSAAVN) "playlist:${playlist.id}" else "playlist:${playlist.provider}:${playlist.id}:${playlist.browseParams.orEmpty()}" }
}
object CatalogNames {
    private val latinText = Regex("[a-z0-9 ]+")
    private val latinMarks = Regex("(?<=\\p{IsLatin})\\p{M}+")
    private val apostrophes = Regex("[’'ʼ]")
    private val punctuation = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val initials = Regex("\\b(?:\\p{L} ){1,3}\\p{L}\\b")
    fun clean(value: String): String = runCatching {
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)?.toString()
    }.getOrNull()?.trim() ?: unescapeHtml(value).trim()

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("<[^>]*>"), "")
    fun normalize(value: String): String = Normalizer.normalize(clean(value), Normalizer.Form.NFKD)
        .replace(latinMarks, "").replace(apostrophes, "").lowercase(Locale.ROOT).replace('&', ' ')
        .replace(punctuation, " ").trim().replace(spaces, " ")
        .replace(initials) { it.value.replace(" ", "") }
    fun same(a: String, b: String): Boolean = normalize(a).isNotEmpty() && normalize(a) == normalize(b)

    /** Small spelling errors only; this is not a substring/recording-identity check. */
    fun spellingEquivalent(a: String, b: String): Boolean {
        val first = normalize(a).replace(" ", "")
        val second = normalize(b).replace(" ", "")
        if (first.isEmpty() || second.isEmpty()) return false
        if (first == second) return true
        // Track/part numbers distinguish songs and must never be treated as typos.
        if (first.filter(Char::isDigit) != second.filter(Char::isDigit)) return false
        // Preserve Indic vowel marks and short names/titles. Never fuzzy-match arbitrary scripts.
        if (!latinText.matches(first) || !latinText.matches(second)) return false
        val length = maxOf(first.length, second.length)
        if (minOf(first.length, second.length) < 5 || length > 160) return false
        val budget = if (length >= 14) 2 else 1
        if (kotlin.math.abs(first.length - second.length) > budget) return false
        var previousPrevious = IntArray(second.length + 1)
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        for (i in 1..first.length) {
            current[0] = i
            for (j in 1..second.length) {
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1,
                    previous[j - 1] + if (first[i - 1] == second[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && first[i - 1] == second[j - 2] && first[i - 2] == second[j - 1])
                    current[j] = minOf(current[j], previousPrevious[j - 2] + 1)
            }
            val reusable = previousPrevious
            previousPrevious = previous
            previous = current
            current = reusable
        }
        return previous.last() <= budget
    }

    fun score(query: String, title: String, credits: List<String> = emptyList()): Int {
        val q = normalize(query); val t = normalize(title)
        if (q.isBlank()) return 0
        if (q == t) return 100
        if (" $t ".contains(" $q ")) return 80
        if (q.split(' ').all { token -> t.split(' ').any { it.startsWith(token) } }) return 60
        if (spellingEquivalent(q, t)) return 55
        if (credits.any { score(q, it) >= 55 }) return 40
        // Providers may correct a typo or distribute a query across title and artist.
        // Require every query term rather than allowing a popular, unrelated title through.
        val words = normalize((listOf(title) + credits).joinToString(" ")).split(' ')
        return if (q.split(' ').all { term -> words.any { it.startsWith(term) || spellingEquivalent(term, it) } }) 30 else 0
    }
}
