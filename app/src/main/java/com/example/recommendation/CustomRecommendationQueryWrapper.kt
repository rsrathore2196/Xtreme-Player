package com.example.recommendation

import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.remote.OnlineMusicApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/** Related-song radio plus contextual searches, with bounded caching and no guessed metadata. */
object CustomRecommendationQueryWrapper {
    private data class Cached(val tracks: List<MusicTrack>, val timestamp: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun searchDiscovery(query: String, limit: Int = 35, era: Boolean = false,
        forceRefresh: Boolean = false, essential: Boolean = false): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        val pageSize = maxOf(40, limit).coerceAtMost(100)
        val key = "query:$era:$pageSize:${DiscoveryRequestCoordinator.normalize(query)}"
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.timestamp < if (forceRefresh) 30_000L else 600_000L)
            return@withContext hit.tracks.take(limit)
        DiscoveryRequestCoordinator.shared(key, essential) {
            val latest = cache[key]
            if (latest != null && System.currentTimeMillis() - latest.timestamp < if (forceRefresh) 30_000L else 600_000L)
                return@shared latest.tracks
            val tracks = if (era) OnlineMusicApiService.searchSongs(query, limit = pageSize, forceRefresh = forceRefresh)
                else YouTubeMusicApiService.searchSongs(query, limit = pageSize, songsOnly = true)
            currentCoroutineContext().ensureActive()
            if (tracks.isNotEmpty()) remember(key, tracks)
            tracks.ifEmpty { latest?.tracks.orEmpty() }
        }.take(limit)
    }

    private fun remember(key: String, tracks: List<MusicTrack>) {
        if (cache.size >= 80) cache.entries.minByOrNull { it.value.timestamp }?.let { cache.remove(it.key) }
        cache[key] = Cached(tracks, System.currentTimeMillis())
    }

    suspend fun fetchTargetedRecommendations(currentTrack: MusicTrack, limit: Int = 40,
        alreadyQueued: List<MusicTrack> = emptyList(), minimumUsable: Int = 20): List<MusicTrack> = withContext(Dispatchers.IO) {
        val key = "seed:${HomeRecommendationPolicy.metadataKey(currentTrack)}:${currentTrack.id}"
        val cached = cache[key]
        val artist = extractPrimaryArtist(currentTrack)
        val language = extractExactLanguage(currentTrack)
        val genre = currentTrack.genre.takeIf(HomeRecommendationPolicy::known).orEmpty()
        val queries = buildList {
            if (artist.isNotBlank()) { add("$artist songs"); add("$artist collaborations") }
            if (language.isNotBlank() || genre.isNotBlank()) add("$language $genre original songs".trim())
        }.distinct().take(3)
        suspend fun usable(tracks: List<MusicTrack>) = withContext(Dispatchers.Default) {
            val excluded = HomeRecommendationPolicy.Recordings().apply { add(currentTrack); alreadyQueued.forEach { add(it) } }
            HomeRecommendationPolicy.originals(tracks).filter { candidate ->
                !excluded.contains(candidate) && (language.isBlank() || !HomeRecommendationPolicy.known(candidate.language) || candidate.language.equals(language, true))
            }
        }
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 600_000L &&
            usable(cached.tracks).size >= minOf(minimumUsable, limit)) return@withContext usable(cached.tracks).take(limit)
        val results = DiscoveryRequestCoordinator.shared("$key:$limit:${alreadyQueued.map { it.id }.sorted()}", true, network = false) {
            val existing = cache[key]
            val radio = if (existing != null && System.currentTimeMillis() - existing.timestamp < 600_000L) existing.tracks else {
                val videoId = if (currentTrack.id.startsWith("yt_") && com.example.data.remote.YouTubeAudioStreamService.isVideoId(currentTrack.id.removePrefix("yt_"))) currentTrack.id.removePrefix("yt_") else {
                    val seedMatches = searchDiscovery("${currentTrack.title} $artist", limit = 40, essential = true)
                    val identity = HomeRecommendationPolicy.Recordings().apply { add(currentTrack) }
                    withContext(Dispatchers.Default) { seedMatches.firstOrNull { identity.contains(it) }?.id?.removePrefix("yt_") }
                }
                videoId?.let { id -> DiscoveryRequestCoordinator.shared("radio:$id:60", true) {
                    YouTubeMusicApiService.getRadioRecommendations(id, limit = 60)
                } }.orEmpty()
            }
            var combined = radio
            for (batch in queries.chunked(2)) {
                if (usable(combined).size >= minOf(minimumUsable, limit)) break
                val additions = coroutineScope { batch.map { query -> async {
                try { searchDiscovery(query, limit = 40, essential = true) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                } }.awaitAll() }
                combined = combined + withContext(Dispatchers.Default) { HomeRecommendationPolicy.interleave(additions) }
            }
            combined
        }
        val candidates = withContext(Dispatchers.Default) {
            val seed = HomeRecommendationPolicy.Recordings().apply { add(currentTrack) }
            HomeRecommendationPolicy.originals(results).filter { track ->
                !seed.contains(track) && (language.isBlank() || !HomeRecommendationPolicy.known(track.language) ||
                    track.language.equals(language, true))
            }.take(limit.coerceAtLeast(60))
        }
        if (candidates.isNotEmpty()) {
            remember(key, candidates)
        }
        usable(candidates.ifEmpty { cached?.tracks.orEmpty() }).take(limit)
    }

    fun extractPrimaryArtist(track: MusicTrack): String = HomeRecommendationPolicy.rawArtists(track).firstOrNull().orEmpty()
    fun extractExactLanguage(track: MusicTrack): String = track.language.takeIf(HomeRecommendationPolicy::known).orEmpty()
    fun extractReleaseYear(track: MusicTrack): String = track.year.takeIf { (it.toIntOrNull() ?: 0) in 1900..2100 }.orEmpty()
}
