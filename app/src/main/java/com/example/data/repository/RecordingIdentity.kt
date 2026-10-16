package com.example.data.repository

import com.example.data.model.MusicTrack
import java.util.Locale
import kotlin.math.abs

/** Recording identity, rather than upload identity. Never discard a recording's version. */
internal object RecordingIdentity {
    private val isrcPattern = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
    private val codeSeparators = Regex("[-\\s]")
    private val artistSeparators = Regex("(?i),|;|•|\\s+(?:&|and|feat\\.?|ft\\.?|featuring|with|x)\\s+")
    private val featured = Regex("(?i)\\s*(?:\\(|\\[)?\\b(?:feat\\.?|ft\\.?|featuring)\\s+([^\\)\\]]+)(?:\\)|\\])?\\s*$")
    private val versions = Regex("(?i)\\b(?:live|re[ -]?mix|rmx|remaster(?:ed)?|radio[ -]?edit|acoustic|unplugged|instrumental|karaoke|cover|slowed|reverb|sped[ -]?up|nightcore|extended|demo|mono|stereo|edit)\\b")
    private val albumEdition = Regex("(?i)\\s*(?:\\(|\\[)(?:deluxe|expanded|bonus|anniversary)[^\\)\\]]*(?:\\)|\\])\\s*$")
    private val unknown = setOf("", "unknown", "unknown artist", "various artists", "online artist", "youtube artist")
    private val unknownAlbums = setOf("", "unknown", "single", "online stream", "extended stream", "youtube music", "youtube playlist")

    fun isrc(value: String): String? = value.uppercase(Locale.ROOT).replace(codeSeparators, "")
        .takeIf { isrcPattern.matches(it) }

    fun title(track: MusicTrack): String = CatalogNames.normalize(
        StreamResolver.extractCleanTitle(track.title, track.artist).replace(featured, ""))

    fun artistNames(track: MusicTrack): List<String> {
        val credits = track.artist.ifBlank { track.singers }
        val feature = featured.find(track.title)?.groupValues?.get(1).orEmpty()
        return (artistSeparators.split(credits) + artistSeparators.split(feature))
            .map { CatalogNames.normalize(StreamResolver.extractCleanArtist(it)) }
            .filter { it !in unknown }.distinct()
    }

    private fun artistKey(value: String) = value.replace(" ", "")
    private fun compatibleArtists(first: List<String>, second: List<String>): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        // A shared guest singer never proves that two different primary artists match.
        if (first.first() != second.first() && first.toSet() != second.toSet()) return false
        val guestsA = first.drop(1).toSet(); val guestsB = second.drop(1).toSet()
        return guestsA.isEmpty() || guestsB.isEmpty() || guestsA == guestsB
    }

    private fun version(track: MusicTrack): Set<String> = versions.findAll(track.title).map {
        when (val value = it.value.lowercase(Locale.ROOT)) {
            "remastered", "remaster" -> "remaster"
            "re-mix", "re mix", "rmx" -> "remix"
            "radio-edit" -> "radio edit"
            "sped-up" -> "sped up"
            else -> value
        }
    }.toSet()

    private fun album(track: MusicTrack): String? = CatalogNames.normalize(track.album.replace(albumEdition, ""))
        .takeUnless { it in unknownAlbums }

    private fun compatibleAlbums(first: String?, second: String?): Boolean {
        return first == null || second == null || first == second
    }

    data class Signature(val title: String, val artists: List<String>, val versions: Set<String>,
        val album: String?, val isrc: String?, val durationMs: Long, val language: String)
    fun signature(track: MusicTrack) = Signature(title(track), artistNames(track).map(::artistKey),
        version(track), album(track), isrc(track.isrc), track.durationMs, CatalogNames.normalize(track.language))

    fun same(a: MusicTrack, b: MusicTrack, durationToleranceMs: Long = 3_000L,
        requireDuration: Boolean = false, requireAlbum: Boolean = false): Boolean =
        same(signature(a), signature(b), durationToleranceMs, requireDuration, requireAlbum)

    fun same(a: Signature, b: Signature, durationToleranceMs: Long = 3_000L,
        requireDuration: Boolean = false, requireAlbum: Boolean = false): Boolean {
        if (a.versions != b.versions || !compatibleArtists(a.artists, b.artists)) return false
        if (a.language !in unknown && b.language !in unknown && a.language != b.language) return false
        val codeA = a.isrc; val codeB = b.isrc
        if (codeA != null && codeB != null && codeA != codeB) return false
        val timed = a.durationMs > 0L && b.durationMs > 0L
        if (requireDuration && !timed) return false
        if (timed && abs(a.durationMs - b.durationMs) > durationToleranceMs) return false
        val sameCode = codeA != null && codeA == codeB
        if (!sameCode && (a.title.isBlank() || a.title != b.title)) return false
        if (!sameCode && requireAlbum && !compatibleAlbums(a.album, b.album)) return false
        // Without duration or an ISRC, contradictory release metadata is not enough
        // evidence to collapse distinct songs that happen to share title and artist.
        return sameCode || timed || compatibleAlbums(a.album, b.album)
    }

    fun key(track: MusicTrack): String = listOf(title(track),
        artistNames(track).joinToString("|") { artistKey(it) }, version(track).sorted().joinToString("|"),
        album(track).orEmpty(), isrc(track.isrc).orEmpty(), track.durationMs.toString()).joinToString("\u0000")
}
