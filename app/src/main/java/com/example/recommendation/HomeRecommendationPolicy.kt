package com.example.recommendation

import android.util.LruCache
import com.example.data.model.MusicTrack
import java.text.Normalizer
import java.util.Locale

/** Recording identity is independent of provider IDs and artwork. Keep real version labels. */
object HomeRecommendationPolicy {
    const val MAX_SHELF_TRACKS = 12
    private const val NORMALIZATION_CACHE_SIZE = 4096
    private val normalizedValues = LruCache<String, String>(NORMALIZATION_CACHE_SIZE)
    private data class MetadataInput(val title: String, val artistNames: List<String>)
    private val metadataKeys = LruCache<MetadataInput, String>(NORMALIZATION_CACHE_SIZE)
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val artistSeparator = Regex("(?i),|;|\\s+(?:feat\\.?|ft\\.?|featuring|&)\\s+")
    private val topicSuffix = Regex("(?i)\\s*-\\s*topic$")
    private val displayNoise = Regex("(?i)\\b(?:official\\s+(?:music\\s+)?(?:video|audio)|lyrics?|lyrical\\s+video|full\\s+(?:song|audio)|hd|4k)\\b")
    private val soundtrackTag = Regex("(?i)\\(\\s*from\\s+.*?\\)")
    private val isrcPattern = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
    private val unknowns = setOf("", "unknown", "unknown artist", "various artists", "online artist", "youtube artist", "single", "online stream")
    fun normalize(value: String): String = synchronized(normalizedValues) {
        normalizedValues.get(value) ?: Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(punctuation, " ").trim()
            .also { normalizedValues.put(value, it) }
    }

    fun known(value: String): Boolean = normalize(value) !in unknowns

    // Weak, transparent genre-to-mood hints; never fabricated audio measurements.
    fun moodForGenre(genre: String): String? = when (normalize(genre)) {
        "ambient", "meditation", "chillout" -> "calm"
        "dance", "edm", "house", "techno" -> "energetic"
        "ballad", "romantic" -> "romantic"
        "blues", "sad" -> "melancholic"
        else -> null
    }

    fun rawArtists(track: MusicTrack): List<String> = (track.artist + "," + track.singers)
        .replace(topicSuffix, "")
        .split(artistSeparator)
        .map { it.trim() }
        .filter(::known)
        .distinct()

    fun artists(track: MusicTrack): List<String> = rawArtists(track)
        .map(::normalize).distinct().sorted()

    fun metadataKey(track: MusicTrack): String = metadataKey(track, artists(track))

    private fun metadataKey(track: MusicTrack, artistNames: List<String>): String {
        val input = MetadataInput(track.title, artistNames)
        return synchronized(metadataKeys) {
            metadataKeys.get(input) ?: run {
                var title = track.title.replace(displayNoise, " ")
                    .replace(soundtrackTag, " ")
                title = normalize(title)
                // Provider video titles often prefix the credited artist.
                artistNames.forEach { if (title.startsWith("$it ")) title = title.removePrefix("$it ") }
                "$title|${artistNames.joinToString(",")}".also { metadataKeys.put(input, it) }
            }
        }
    }

    fun isrc(track: MusicTrack): String? = track.isrc.replace("-", "").uppercase(Locale.ROOT)
        .takeIf { it.matches(isrcPattern) && !it.endsWith("0000000") }

    fun aliases(track: MusicTrack): List<String> {
        val artistNames = artists(track)
        val metadata = metadataKey(track, artistNames)
        val title = metadata.substringBefore('|')
        return listOf(metadata) + listOfNotNull(isrc(track)?.let { "isrc:$it" }) + artistNames.map { "$title|$it" }
    }

    class Recordings {
        val artistCounts = mutableMapOf<String, Int>()
        val albumCounts = mutableMapOf<String, Int>()

        private val codes = mutableSetOf<String>()
        private val names = mutableSetOf<String>()
        fun contains(track: MusicTrack): Boolean = isrc(track)?.let { it in codes } == true || aliases(track).any { it in names }
        fun add(track: MusicTrack): Boolean {
            val code = isrc(track)
            val recordingNames = aliases(track)
            val fresh = (code == null || code !in codes) && recordingNames.none { it in names }
            code?.let(codes::add)
            names.addAll(recordingNames)
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
            credits.getValue(next).forEach { artists[it] = (artists[it] ?: 0) + 1 }
            albumKeys[next]?.let { albums.merge(it, 1, Int::plus) }
        }
        return result
    }

    fun interleave(groups: List<List<MusicTrack>>): List<MusicTrack> =
        (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { index -> groups.mapNotNull { it.getOrNull(index) } }
}
