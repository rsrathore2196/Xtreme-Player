package com.example.data.repository

import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.OnlineMusicApiService
import com.example.playback.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

class TrackUnavailableException(title: String) : IOException("No matching playable stream is available for '$title'.")

/** Resolves only matching recordings; catalog/demo audio is never a fallback. */
internal class StreamResolver(
    private val searchStandard: suspend (String, Boolean) -> List<MusicTrack> = { query, refresh ->
        OnlineMusicApiService.searchSongs(query, limit = 5, forceRefresh = refresh)
    },
    private val searchLossless: suspend (String) -> List<MusicTrack> = {
        InternetArchiveApiService.searchLosslessTracks(it, maxResults = 3)
    }
) {
    private val cache = ConcurrentHashMap<Pair<String, AudioQuality>, MusicTrack>()
    val cacheSize: Int get() = cache.size
    fun clear() = cache.clear()

    suspend fun resolve(
        track: MusicTrack,
        quality: AudioQuality,
        forceRefresh: Boolean = false,
        failedUrl: String? = null
    ): MusicTrack {
        val key = track.id to quality
        if (forceRefresh) cache.keys.removeAll { it.first == track.id }
        else {
            cache[key]?.takeIf { it.audioUrl != failedUrl }?.let { return withStream(track, it) }
            if (track.audioUrl.isNotBlank() && track.audioUrl != failedUrl &&
                !track.audioUrl.startsWith("yt_") && !track.audioUrl.contains("placeholder")) return track
        }

        val rejectedUrl = failedUrl ?: track.audioUrl.takeIf { forceRefresh }
        val query = "${track.title} ${track.artist}".trim()
        suspend fun find(search: suspend (String) -> List<MusicTrack>, text: String): MusicTrack? {
            val candidates = try {
                search(text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            coroutineContext.ensureActive()
            return candidates.firstOrNull {
                it.audioUrl.isNotBlank() && it.audioUrl != rejectedUrl && matches(track, it)
            }
        }
        val standard: suspend (String) -> List<MusicTrack> = { searchStandard(it, forceRefresh) }
        val candidate = (if (quality == AudioQuality.HI_RES_LOSSLESS) find(searchLossless, query) else null)
            ?: find(standard, query)
            ?: find(standard, track.title)
            ?: throw TrackUnavailableException(track.title)
        coroutineContext.ensureActive()
        cache[key] = candidate
        return withStream(track, candidate)
    }

    private fun withStream(track: MusicTrack, stream: MusicTrack) = track.copy(
        audioUrl = stream.audioUrl,
        bitrateKbps = stream.bitrateKbps,
        qualityBadge = stream.qualityBadge,
        isLossless = stream.isLossless
    )

    companion object {
        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace(Regex("\\bofficial\\s+(music\\s+)?(video|audio)\\b"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        internal fun matches(requested: MusicTrack, candidate: MusicTrack): Boolean {
            if (requested.isrc.isNotBlank() && candidate.isrc.isNotBlank()) {
                return requested.isrc.equals(candidate.isrc, ignoreCase = true)
            }
            val title = normalize(requested.title)
            if (title.isBlank() || title != normalize(candidate.title)) return false
            fun artists(value: String) = value.split(Regex("(?i),|&|;|/|\\bfeat\\.?|\\bft\\.?|•"))
                .map(::normalize).filter { it.isNotBlank() }.toSet()
            if (artists(requested.artist).intersect(artists(candidate.artist)).isEmpty()) return false
            return requested.durationMs <= 0 || candidate.durationMs <= 0 ||
                abs(requested.durationMs - candidate.durationMs) <= maxOf(10_000L, requested.durationMs / 10)
        }
    }
}
