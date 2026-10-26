package com.example.recommendation

import android.util.LruCache
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogNames
import com.example.data.repository.ArtistPhotoRepository
import java.text.Normalizer
import java.util.Locale

/** Recording identity is independent of provider IDs and artwork. Keep real version labels. */
object HomeRecommendationPolicy {
    const val MAX_SHELF_TRACKS = 12
    private const val NORMALIZATION_CACHE_SIZE = 4096
    private val normalizedValues = LruCache<String, String>(NORMALIZATION_CACHE_SIZE)
    private data class MetadataInput(val title: String, val artistNames: List<String>)
    private val metadataKeys = LruCache<MetadataInput, String>(NORMALIZATION_CACHE_SIZE)
    private data class EligibilityInput(val id: String, val title: String, val artist: String,
        val album: String, val duration: Long, val audio: String, val source: String)
    private val eligibility = LruCache<EligibilityInput, Boolean>(4096)
    fun isOriginalRecommendationTrack(track: MusicTrack): Boolean {
        val key = EligibilityInput(track.id, track.title, track.artist, track.album,
            track.durationMs, track.audioUrl, track.source)
        synchronized(eligibility) { eligibility.get(key) }?.let { return it }
        return OriginalMusicFilter.isHomeRecommendation(track).also { result ->
            synchronized(eligibility) { eligibility.put(key, result) }
        }
    }
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

    private val moodKeywords = listOf(
        "melancholic" to setOf("sad", "heartbreak", "melancholic", "dard", "judaai", "bewafa"),
        "romantic" to setOf("romantic", "love", "ishq", "pyaar", "mohabbat"),
        "energetic" to setOf("party", "dance", "bhangra", "workout", "energetic"),
        "calm" to setOf("calm", "chill", "ambient", "meditation", "relax"),
        "devotional" to setOf("devotional", "bhajan", "gurbani", "qawwali", "sufi")
    )

    /** Conservative metadata hints only; never pretend to measure acoustic features. */
    fun moodForTrack(track: MusicTrack): String? {
        moodForGenre(track.genre)?.let { return it }
        val words = normalize("${track.title} ${track.album} ${track.genre}").split(' ')
        return moodKeywords.firstOrNull { (_, keywords) -> words.any(keywords::contains) }?.first
    }

    fun rawArtists(track: MusicTrack): List<String> = (track.artist + "," + track.singers)
        .replace(topicSuffix, "")
        .split(artistSeparator)
        .map { it.trim() }
        .filter(::known)
        .distinct()

    fun artists(track: MusicTrack): List<String> = rawArtists(track)
        .map(::normalize).distinct().sorted()

    private val guestCredit = Regex("(?i)\\s*[(\\[]?\\b(?:feat\\.?|ft\\.?|featuring)\\s+")
    private val mainCollaboration = Regex("(?i)\\s+(?:&|and|x|×)\\s+|;")
    private val secondaryRole = Regex("(?i)\\b(?:written|lyrics|lyricist|composed|composer|producer|produced|music director|background vocals)\\b")

    /** Ranking only: keep recording identity/recommendation matching rules independent.
     * Structured primary credits retain every credited collaborator. Older combined
     * contributor fields cannot establish that every comma-separated person is primary. */
    fun mainArtists(track: MusicTrack): List<String> {
        val verified = track.primaryArtists.isNotEmpty()
        val writers = track.writer.split(artistSeparator).map(CatalogNames::normalize).toSet()
        fun eligibleLegacy(name: String) = known(name) && !secondaryRole.containsMatchIn(name) &&
            CatalogNames.normalize(name) !in writers
        val names = if (verified) track.primaryArtists else {
            // Exclude explicit writers/guests and require independent performer
            // evidence before trusting an older combined contributor list.
            val credits = track.artist.replace(topicSuffix, "").split(guestCredit, limit = 2).first()
                .split(',').map(CatalogNames::clean)
            val singers = track.singers.replace(topicSuffix, "").split(guestCredit, limit = 2).first()
                .split(artistSeparator).map(CatalogNames::normalize).toSet()
            // A clean performer list independently corroborated by singer credits
            // can retain every collaborator. Mixed writer/producer lists remain conservative.
            if (credits.size > 1 && credits.all(::eligibleLegacy) &&
                credits.all { CatalogNames.normalize(it) in singers }) credits
            else credits.firstOrNull(::eligibleLegacy).orEmpty().split(mainCollaboration)
        }
        return names.map(CatalogNames::clean).map { it.replace(topicSuffix, "").trim() }
            .filter { known(it) && !guestCredit.containsMatchIn(it) && (verified || eligibleLegacy(it)) }
            .distinctBy(::mainArtistKey)
    }

    fun mainArtistKey(name: String): String = CatalogNames.normalize(ArtistPhotoRepository.canonicalName(name))

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
        return tracks.filter { isOriginalRecommendationTrack(it) && seen.add(it) }
    }

    /** Cache checkpoints need only their retained prefix, not all historical metadata. */
    suspend fun originalsCooperatively(tracks: Sequence<MusicTrack>, limit: Int,
        checkpoint: suspend () -> Unit): List<MusicTrack> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val seen = Recordings()
        val result = ArrayList<MusicTrack>(minOf(limit, 400))
        for ((index, track) in tracks.withIndex()) {
            if (index % 64 == 0) checkpoint()
            if (isOriginalRecommendationTrack(track) && seen.add(track)) {
                result.add(track)
                if (result.size == limit) break
            }
        }
        return result
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
