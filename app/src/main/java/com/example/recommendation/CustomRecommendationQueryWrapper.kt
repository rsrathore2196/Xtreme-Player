package com.example.recommendation

import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeMusicApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Related-song radio plus contextual searches, with bounded caching and no guessed metadata. */
object CustomRecommendationQueryWrapper {
    private data class Cached(val tracks: List<MusicTrack>, val timestamp: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun fetchTargetedRecommendations(currentTrack: MusicTrack, limit: Int = 40): List<MusicTrack> = withContext(Dispatchers.IO) {
        val key = HomeRecommendationPolicy.metadataKey(currentTrack)
        val cached = cache[key]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 10 * 60_000) return@withContext cached.tracks.take(limit)
        val artist = extractPrimaryArtist(currentTrack)
        val language = extractExactLanguage(currentTrack)
        val genre = currentTrack.genre.takeIf(HomeRecommendationPolicy::known).orEmpty()
        val queries = buildList {
            if (artist.isNotBlank()) { add("$artist songs"); add("$artist collaborations") }
            if (language.isNotBlank() || genre.isNotBlank()) add("$language $genre original songs".trim())
        }.distinct().take(3)
        val results = coroutineScope {
            val radio = async {
                val videoId = if (currentTrack.id.startsWith("yt_")) currentTrack.id.removePrefix("yt_") else {
                    val seedMatches = YouTubeMusicApiService.searchSongs("${currentTrack.title} $artist", limit = 5, songsOnly = true)
                    val identity = HomeRecommendationPolicy.Recordings().apply { add(currentTrack) }
                    seedMatches.firstOrNull { identity.contains(it) }?.id?.removePrefix("yt_")
                }
                videoId?.let { YouTubeMusicApiService.getRadioRecommendations(it, limit = 40) }.orEmpty()
            }
            val searches = queries.map { query -> async {
                try { YouTubeMusicApiService.searchSongs(query, limit = 20, songsOnly = true) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            } }
            radio.await() + HomeRecommendationPolicy.interleave(searches.awaitAll())
        }
        val candidates = withContext(Dispatchers.Default) {
            val seed = HomeRecommendationPolicy.Recordings().apply { add(currentTrack) }
            HomeRecommendationPolicy.originals(results).filter { track ->
                !seed.contains(track) && (language.isBlank() || !HomeRecommendationPolicy.known(track.language) ||
                    track.language.equals(language, true))
            }.take(limit.coerceAtLeast(60))
        }
        if (candidates.isNotEmpty()) {
            if (cache.size >= 24) cache.entries.minByOrNull { it.value.timestamp }?.let { cache.remove(it.key) }
            cache[key] = Cached(candidates, System.currentTimeMillis())
        }
        candidates.ifEmpty { cached?.tracks.orEmpty() }.take(limit)
    }

    fun extractPrimaryArtist(track: MusicTrack): String = HomeRecommendationPolicy.artists(track).firstOrNull().orEmpty()
    fun extractExactLanguage(track: MusicTrack): String = track.language.takeIf(HomeRecommendationPolicy::known).orEmpty()
    fun extractReleaseYear(track: MusicTrack): String = track.year.takeIf { (it.toIntOrNull() ?: 0) in 1900..2100 }.orEmpty()
}
