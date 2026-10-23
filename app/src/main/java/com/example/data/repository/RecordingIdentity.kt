package com.example.data.repository

import com.example.data.model.MusicTrack
import java.util.Locale
import kotlin.math.abs

/** Recording identity, rather than upload identity. Never discard a recording's version. */
internal object RecordingIdentity {
    private val isrcPattern = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
    private val codeSeparators = Regex("[-\\s]")
    private val artistSeparators = Regex("(?i),|;|•|\\s*&\\s*|\\s+(?:and|feat\\.?|ft\\.?|featuring|with|x)\\s+")
    private val featured = Regex("(?i)\\s*(?:\\(|\\[)?\\b(?:feat\\.?|ft\\.?|featuring)\\s+([^\\)\\]]+)(?:\\)|\\])?\\s*$")
    private val versions = Regex("(?i)\\b(?:live|re[ -]?mix|rmx|remaster(?:ed)?|radio[ -]?edit|acoustic|unplugged|instrumental|karaoke|cover|slowed|reverb|sped[ -]?up|nightcore|extended|demo|mono|stereo|edit)\\b")
    private val albumEdition = Regex("(?i)\\s*(?:\\(|\\[)(?:deluxe|expanded|bonus|anniversary)[^\\)\\]]*(?:\\)|\\])\\s*$")
    private val unknown = setOf("", "unknown", "unknown artist", "various artists", "online artist", "youtube artist", "internet archive")
    private val unknownAlbums = setOf("", "unknown", "single", "online stream", "extended stream", "youtube music", "youtube playlist")

    fun isrc(value: String): String? = value.uppercase(Locale.ROOT).replace(codeSeparators, "")
        .takeIf { isrcPattern.matches(it) }

    fun title(track: MusicTrack): String = CatalogNames.normalize(
        StreamResolver.extractCleanTitle(track.title, track.artist).replace(featured, ""))

    fun artistNames(track: MusicTrack): List<String> {
        val credits = track.primaryArtists.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: track.artist.takeUnless {
            CatalogNames.normalize(StreamResolver.extractCleanArtist(it)) in unknown
        }.orEmpty()
        val feature = featured.find(track.title)?.groupValues?.get(1).orEmpty()
        return (artistSeparators.split(credits) + artistSeparators.split(feature))
            .map { CatalogNames.normalize(StreamResolver.extractCleanArtist(it)) }
            .filter { it !in unknown }.distinct()
    }

    private fun artistKey(value: String) = value.replace(" ", "")
    private fun compatibleArtists(first: List<String>, second: List<String>): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        // A shared guest singer never proves that two different primary artists match.
        if (!CatalogNames.spellingEquivalent(first.first(), second.first()) && first.toSet() != second.toSet()) return false
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

    /** Fallback scoring is separate from catalog deduplication and strict Archive validation.
     * Missing metadata contributes no evidence; conflicting identity is always rejected. */
    fun fallbackConfidence(requested: MusicTrack, candidate: MusicTrack,
        durationToleranceMs: Long? = null, requireDuration: Boolean = false): Int? {
        val a = signature(requested); val b = signature(candidate)
        if (a.versions != b.versions || a.title.isBlank() || b.title.isBlank()) return null
        if (a.isrc != null && b.isrc != null && a.isrc != b.isrc) return null
        val sameCode = a.isrc != null && a.isrc == b.isrc
        val exactTitle = a.title.replace(" ", "") == b.title.replace(" ", "")
        if (!exactTitle && !CatalogNames.spellingEquivalent(a.title, b.title)) return null
        val artistsKnown = a.artists.isNotEmpty() && b.artists.isNotEmpty()
        val exactArtist = artistsKnown && (a.artists.first() == b.artists.first() || a.artists.toSet() == b.artists.toSet())
        val closeArtist = artistsKnown && CatalogNames.spellingEquivalent(a.artists.first(), b.artists.first())
        val primaryCredit = artistsKnown && (a.artists.first() in b.artists || b.artists.first() in a.artists)
        if (artistsKnown && !exactArtist && !closeArtist && !(sameCode && primaryCredit)) return null
        if (artistsKnown && a.artists.first() == b.artists.first()) {
            val guestsA = a.artists.drop(1); val guestsB = b.artists.drop(1)
            if (guestsA.isNotEmpty() && guestsB.isNotEmpty() && guestsA.none { first ->
                guestsB.any { second -> CatalogNames.spellingEquivalent(first, second) }
            }) return null
        }
        if (!artistsKnown && !sameCode) return null
        if (a.language !in unknown && b.language !in unknown && a.language != b.language) return null
        val timed = a.durationMs > 0 && b.durationMs > 0
        if (requireDuration && !timed) return null
        val delta = if (timed) abs(a.durationMs - b.durationMs) else Long.MAX_VALUE
        val tolerance = durationToleranceMs ?: (a.durationMs / 40).coerceIn(3_000L, 8_000L)
        if (timed && delta > tolerance) return null
        // Typos in both title and artist require much stronger independent evidence.
        if (!exactTitle && !sameCode && (!exactArtist || !timed || delta > 2_000L)) return null
        if (!exactArtist && !sameCode && (!exactTitle || !timed || delta > 3_000L)) return null
        if (!timed && !sameCode && (!exactTitle || !exactArtist || a.title.length < 5)) return null
        return (if (sameCode) 110 else 0) + (if (exactTitle) 45 else 30) +
            (if (exactArtist) 35 else if (closeArtist) 25 else 0) +
            (if (timed) 20 - (delta / 1_000L).toInt() else 0) +
            (if (a.album != null && a.album == b.album) 5 else 0)
    }

    private val indic = Regex("[\u0900-\u097f\u0a00-\u0a7f]")
    @androidx.annotation.RequiresApi(29)
    private object IndicRomanizer {
        private val converter = android.icu.text.Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
        fun convert(track: MusicTrack): MusicTrack = synchronized(converter) {
            track.copy(title = converter.transliterate(track.title), artist = converter.transliterate(track.artist),
                primaryArtists = track.primaryArtists.map { converter.transliterate(it) })
        }
    }

    /** Alternate spelling is evidence only with matching versions/artists and a tight duration. */
    fun providerConfidence(requested: MusicTrack, candidate: MusicTrack, durationToleranceMs: Long? = null): Int? {
        fallbackConfidence(requested, candidate, durationToleranceMs)?.let { return it }
        if (android.os.Build.VERSION.SDK_INT < 29 ||
            !indic.containsMatchIn(requested.title + requested.artist + requested.primaryArtists.joinToString() +
                candidate.title + candidate.artist + candidate.primaryArtists.joinToString())) return null
        val first = IndicRomanizer.convert(requested)
        val second = IndicRomanizer.convert(candidate)
        return fallbackConfidence(first, second, durationToleranceMs = minOf(durationToleranceMs ?: 3_000L, 3_000L), requireDuration = true)
    }

    /** Archive file tags may be sparse; require independent recording evidence, not upload names.
     * This is deliberately separate from catalog/search deduplication and provider fallback rules. */
    fun losslessConfidence(requested: MusicTrack, candidate: MusicTrack): Int? {
        val a = signature(requested); val b = signature(candidate)
        if (a.title.isBlank() || b.title.isBlank() || a.versions != b.versions) return null
        if (a.isrc != null && b.isrc != null && a.isrc != b.isrc) return null
        val sameCode = a.isrc != null && a.isrc == b.isrc
        val exactTitle = a.title.replace(" ", "") == b.title.replace(" ", "")
        if (!exactTitle && !CatalogNames.spellingEquivalent(a.title, b.title)) return null
        val knownArtists = a.artists.isNotEmpty() && b.artists.isNotEmpty()
        val sameArtist = knownArtists && (a.artists.first() == b.artists.first() || a.artists.toSet() == b.artists.toSet())
        val closeArtist = knownArtists && CatalogNames.spellingEquivalent(a.artists.first(), b.artists.first())
        if (knownArtists && !sameArtist && !closeArtist) return null
        if (!sameCode && a.artists.size > 1 && b.artists.size > 1 && a.artists.drop(1).none { first ->
            b.artists.drop(1).any { second -> CatalogNames.spellingEquivalent(first, second) }
        }) return null
        if (a.language !in unknown && b.language !in unknown && a.language != b.language) return null
        val sameAlbum = a.album != null && a.album == b.album
        val timed = a.durationMs > 0L && b.durationMs > 0L
        val delta = if (timed) abs(a.durationMs - b.durationMs) else Long.MAX_VALUE
        val tolerance = when {
            sameCode -> 8_000L
            exactTitle && sameArtist -> 5_000L
            else -> 2_000L
        }
        if (timed && delta > tolerance) return null
        // Missing artist/duration is not a conflict, but must be replaced by strong evidence.
        if (!knownArtists && !sameCode && !(exactTitle && sameAlbum && timed && delta <= 2_000L)) return null
        if (!timed && !sameCode) return null
        if (!exactTitle && !sameCode && !(sameArtist && timed && delta <= 2_000L)) return null
        if (!sameArtist && !sameCode && !(exactTitle && timed && delta <= 2_000L)) return null
        // Album editions/compilations can differ; an explicitly different version cannot.
        val archiveVersion = candidate.album.lowercase(Locale.ROOT)
        if (a.versions.isEmpty() && (archiveVersion.contains("remaster") ||
            archiveVersion.startsWith("live at ") || archiveVersion.endsWith(" live"))) return null
        return (if (sameCode) 150 else 0) + (if (exactTitle) 50 else 25) +
            (if (sameArtist) 40 else if (closeArtist) 25 else 0) + (if (sameAlbum) 10 else 0) +
            (if (timed) 20 - (delta / 1000L).toInt() else 0)
    }

    fun key(track: MusicTrack): String = listOf(title(track),
        artistNames(track).joinToString("|") { artistKey(it) }, version(track).sorted().joinToString("|"),
        album(track).orEmpty(), isrc(track.isrc).orEmpty(), track.durationMs.toString()).joinToString("\u0000")
}
