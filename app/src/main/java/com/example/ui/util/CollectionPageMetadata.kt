package com.example.ui.util

import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogNames
import java.util.Locale

/** Display-only cleanup for collection pages. Provider and saved metadata stay intact. */
internal object CollectionPageMetadata {
    private val spaces = Regex("[\\s\\u00a0]+")
    private const val promo = "(?:official (?:audio|video|music video|album|playlist)|full album|audio jukebox|download (?:mp3 )?songs|songs download)"
    private val bracketPromo = Regex("(?i)\\s*(?:\\($promo\\)|\\[$promo\\])\\s*$")
    private val suffixPromo = Regex("(?i)\\s+[|•·–—-]\\s*(?:$promo|YouTube Music|JioSaavn|Spotify)\\s*$")
    private const val edition = "(?:(?:(?:18|19|20)\\d{2}\\s+)?(?:super\\s+)?(?:deluxe(?:\\s+(?:edition|version))?|remaster(?:ed)?(?:\\s+(?:edition|version))?)(?:\\s+(?:18|19|20)\\d{2})?)"
    private val bracketEdition = Regex("(?i)\\s*(?:\\($edition\\)|\\[$edition\\])")
    private val suffixEdition = Regex("(?i)\\s+(?:[|•·–—-]\\s*)?$edition\\s*$")
    private val year = Regex("(?<!\\d)(?:18|19|20)\\d{2}(?!\\d)")
    private val topicSuffix = Regex("(?i)\\s+-\\s+Topic$")
    private val placeholders = setOf("null", "undefined", "n/a", "unknown", "unknown artist", "unknown album")
    private val missingTitles = setOf("null", "undefined", "n/a")

    fun text(raw: String): String = CatalogNames.clean(raw).replace(spaces, " ").trim()

    fun title(raw: String, fallback: String): String {
        var clean = text(raw).replace(bracketEdition, "").replace(spaces, " ").trim()
        while (true) {
            val next = clean.replace(bracketPromo, "").replace(suffixPromo, "").replace(suffixEdition, "").trim()
            if (next == clean || next.isBlank()) break
            clean = next
        }
        return clean.takeUnless { it.lowercase(Locale.ROOT) in missingTitles }.orEmpty().ifBlank { fallback }
    }

    fun description(raw: String, title: String): String {
        val clean = text(raw)
        return clean.takeUnless { it.equals(title, ignoreCase = true) || it.lowercase(Locale.ROOT) in placeholders }.orEmpty()
    }

    fun albumSubtitle(album: CatalogAlbum): String {
        val artists = album.artists.map { text(it.name).replace(topicSuffix, "").trim() }
            .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in placeholders }
            .distinctBy { it.lowercase(Locale.ROOT) }.joinToString(", ")
        val releaseYear = year.find(text(album.year))?.value.orEmpty()
        return listOf(artists, releaseYear).filter(String::isNotBlank).joinToString(" • ")
    }

    fun trackSummary(tracks: List<MusicTrack>, complete: Boolean = true): String {
        val count = tracks.size
        val countText = "$count ${if (count == 1) "track" else "tracks"}${if (complete) "" else " loaded"}"
        val duration = tracks.sumOf { it.durationMs.coerceAtLeast(0) } / 60_000
        val time = when {
            duration >= 60 -> "${duration / 60} hr${(duration % 60).takeIf { it > 0 }?.let { " $it min" }.orEmpty()}"
            duration > 0 -> "$duration min"
            else -> ""
        }
        return listOf(countText, time).filter(String::isNotBlank).joinToString(" • ")
    }
}
