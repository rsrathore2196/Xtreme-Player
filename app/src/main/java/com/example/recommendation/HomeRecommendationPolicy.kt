package com.example.recommendation

import com.example.data.model.MusicTrack
import java.text.Normalizer
import java.util.Locale

/** Recording identity is independent of provider IDs and artwork. Keep real version labels. */
object HomeRecommendationPolicy {
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val artistSeparator = Regex("(?i),|;|\\s+(?:feat\\.?|ft\\.?|featuring|&)\\s+")
    private val topicSuffix = Regex("(?i)\\s*-\\s*topic$")
    private val displayNoise = Regex("(?i)\\b(?:official\\s+(?:music\\s+)?(?:video|audio)|lyrics?|lyrical\\s+video|full\\s+(?:song|audio)|hd|4k)\\b")
    private val soundtrackTag = Regex("(?i)\\(\\s*from\\s+.*?\\)")
    private val isrcPattern = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
    private val unknowns = setOf("", "unknown", "unknown artist", "various artists", "online artist", "youtube artist", "single", "online stream")
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(punctuation, " ").trim()

    fun known(value: String): Boolean = normalize(value) !in unknowns

    // Weak, transparent genre-to-mood hints; never fabricated audio measurements.
    fun moodForGenre(genre: String): String? = when (normalize(genre)) {
        "ambient", "meditation", "chillout" -> "calm"
        "dance", "edm", "house", "techno" -> "energetic"
        "ballad", "romantic" -> "romantic"
        "blues", "sad" -> "melancholic"
        else -> null
    }

    fun artists(track: MusicTrack): List<String> = (track.artist + "," + track.singers)
        .replace(topicSuffix, "")
        .split(artistSeparator)
        .filter(::known).map(::normalize).distinct().sorted()

    fun metadataKey(track: MusicTrack): String {
        val artistNames = artists(track)
        var title = track.title.replace(displayNoise, " ")
            .replace(soundtrackTag, " ")
        title = normalize(title)
        // Provider video titles often prefix the credited artist.
        artistNames.forEach { if (title.startsWith("$it ")) title = title.removePrefix("$it ") }
        return "$title|${artistNames.joinToString(",")}"
    }

    fun isrc(track: MusicTrack): String? = track.isrc.replace("-", "").uppercase(Locale.ROOT)
        .takeIf { it.matches(isrcPattern) && !it.endsWith("0000000") }

    fun aliases(track: MusicTrack): List<String> {
        val title = metadataKey(track).substringBefore('|')
        return listOf(metadataKey(track)) + listOfNotNull(isrc(track)?.let { "isrc:$it" }) + artists(track).map { "$title|$it" }
    }

    class Recordings {
        val artistCounts = mutableMapOf<String, Int>()
        val albumCounts = mutableMapOf<String, Int>()

        private val codes = mutableSetOf<String>()
        private val names = mutableSetOf<String>()
        fun contains(track: MusicTrack): Boolean = isrc(track)?.let { it in codes } == true || aliases(track).any { it in names }
        fun add(track: MusicTrack): Boolean {
            val fresh = !contains(track)
            isrc(track)?.let(codes::add)
            names.addAll(aliases(track))
            if (fresh) {
                artists(track).forEach { artistCounts.merge(it, 1, Int::plus) }
                if (known(track.album)) albumCounts.merge(normalize(track.album), 1, Int::plus)
            }
            return fresh
        }
    }

    fun originals(tracks: List<MusicTrack>): List<MusicTrack> {
        val seen = Recordings()
        return tracks.filter { OriginalMusicFilter.isHomeRecommendation(it) && seen.add(it) }
    }

    /** Soft diversity penalties: never discard a legitimate song solely for sharing an album. */
    fun diversify(ranked: List<MusicTrack>, limit: Int, seen: Recordings = Recordings()): List<MusicTrack> {
        val remaining = originals(ranked).filterNot(seen::contains).toMutableList()
        val artists = seen.artistCounts.toMutableMap()
        val albums = seen.albumCounts.toMutableMap()
        val credits = remaining.associateWith { artists(it) }
        val albumKeys = remaining.associateWith { normalize(it.album).takeIf { _ -> known(it.album) } }
        val result = mutableListOf<MusicTrack>()
        while (remaining.isNotEmpty() && result.size < limit) {
            val next = remaining.withIndex().minBy { (index, track) ->
                index + credits.getValue(track).sumOf { artists[it] ?: 0 } * 12 +
                    (albumKeys[track]?.let { albums[it] } ?: 0) * 9
            }.value
            remaining.remove(next)
            if (!seen.add(next)) continue
            result.add(next)
            artists(next).forEach { artists[it] = (artists[it] ?: 0) + 1 }
            if (known(next.album)) albums.merge(normalize(next.album), 1, Int::plus)
        }
        return result
    }

    fun interleave(groups: List<List<MusicTrack>>): List<MusicTrack> =
        (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { index -> groups.mapNotNull { it.getOrNull(index) } }
}
