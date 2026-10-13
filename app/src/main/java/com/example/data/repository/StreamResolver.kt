package com.example.data.repository

import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.OnlineMusicApiService
import com.example.playback.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

class TrackUnavailableException(title: String) : IOException("No matching playable stream is available for '$title'.")

/** Resolves only matching recordings; catalog/demo audio is never a fallback. */
internal class StreamResolver(
    private val searchStandard: suspend (String, Boolean) -> List<MusicTrack> = { query, refresh ->
        OnlineMusicApiService.searchSongs(query, limit = 10, forceRefresh = refresh)
    },
    private val searchLossless: suspend (String) -> List<MusicTrack> = {
        InternetArchiveApiService.searchLosslessTracks(it, maxResults = 3)
    },
    private val fetchById: suspend (String, Boolean) -> MusicTrack? = { id, refresh ->
        OnlineMusicApiService.fetchSongDetails(id, refresh)
    }
) {
    private data class CachedStream(val track: MusicTrack, val expiresAt: Long)
    private val cache = LinkedHashMap<Pair<String, AudioQuality>, CachedStream>(128, 0.75f, true)
    val cacheSize: Int get() = synchronized(cache) { cache.size }
    fun clear() = synchronized(cache) { cache.clear() }

    suspend fun resolve(
        track: MusicTrack,
        quality: AudioQuality,
        forceRefresh: Boolean = false,
        failedUrl: String? = null
    ): MusicTrack = withContext(Dispatchers.IO) {
        val key = track.id to quality
        if (forceRefresh) {
            synchronized(cache) { cache.keys.removeAll { it.first == track.id } }
        } else {
            val cached = synchronized(cache) {
                cache[key]?.let {
                    if (it.expiresAt > System.currentTimeMillis()) it.track else { cache.remove(key); null }
                }
            }
            cached?.takeIf { it.audioUrl != failedUrl && isValidStreamUrl(it.audioUrl) }?.let {
                return@withContext withStream(track, it)
            }
            if (isValidStreamUrl(track.audioUrl) && track.audioUrl != failedUrl &&
                !track.audioUrl.startsWith("yt_") && !track.audioUrl.contains("placeholder")
            ) {
                return@withContext track
            }
        }

        val rejectedUrl = failedUrl ?: track.audioUrl.takeIf { forceRefresh }
        val cleanTitle = extractCleanTitle(track.title)
        val cleanArtist = extractCleanArtist(track.artist)

        suspend fun exactRecording(): MusicTrack? {
            if (!track.id.startsWith("online_")) return null
            val id = track.id.removePrefix("online_")
            val exact = try { withTimeoutOrNull(8_000L) { fetchById(id, forceRefresh) } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            return exact?.takeIf { it.id == track.id && isValidStreamUrl(it.audioUrl) &&
                it.audioUrl != rejectedUrl && it.audioUrl != failedUrl }
        }

        suspend fun find(search: suspend (String) -> List<MusicTrack>, text: String): MusicTrack? {
            if (text.isBlank()) return null
            val candidates = try {
                withTimeoutOrNull(8_000L) {
                    search(text)
                } ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            coroutineContext.ensureActive()
            return candidates.firstOrNull { candidate ->
                isValidStreamUrl(candidate.audioUrl) &&
                    candidate.audioUrl != rejectedUrl &&
                    candidate.audioUrl != failedUrl &&
                    matches(track, candidate)
            }
        }

        val standard: suspend (String) -> List<MusicTrack> = { searchStandard(it, forceRefresh) }

        // Progressive targeted queries:
        // 1. Clean Title + Clean Artist (highest precision, stripped of video/audio tags)
        val primaryQuery = "$cleanTitle $cleanArtist".trim()
        // 2. Clean Title only
        val titleOnlyQuery = cleanTitle.trim()
        // 3. Reversed Artist + Title
        val artistFirstQuery = if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) "$cleanArtist $cleanTitle".trim() else ""

        val candidate = (if (quality == AudioQuality.HI_RES_LOSSLESS) find(searchLossless, primaryQuery) else null)
            ?: exactRecording()
            ?: find(standard, primaryQuery)
            ?: (if (titleOnlyQuery != primaryQuery && titleOnlyQuery.isNotBlank()) find(standard, titleOnlyQuery) else null)
            ?: (if (artistFirstQuery.isNotBlank() && artistFirstQuery != primaryQuery) find(standard, artistFirstQuery) else null)
            ?: throw TrackUnavailableException(track.title)

        coroutineContext.ensureActive()
        synchronized(cache) {
            cache[key] = CachedStream(candidate, System.currentTimeMillis() + 300_000L)
            while (cache.size > 128) cache.remove(cache.keys.first())
        }
        withStream(track, candidate)
    }

    private fun withStream(track: MusicTrack, stream: MusicTrack) = track.copy(
        audioUrl = stream.audioUrl,
        bitrateKbps = stream.bitrateKbps,
        qualityBadge = stream.qualityBadge,
        isLossless = stream.isLossless
    )

    companion object {
        private fun isValidStreamUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            if (url.startsWith("yt_") || url.contains("placeholder")) return false
            return url.startsWith("http://") || url.startsWith("https://")
        }

        internal fun extractCleanTitle(title: String): String {
            var cleaned = title
            // If title is in "Artist - Song Name" format, extract song name if feasible
            if (cleaned.contains(" - ")) {
                val parts = cleaned.split(" - ")
                if (parts.size >= 2 && parts[1].isNotBlank()) {
                    cleaned = parts[1]
                }
            }
            // Remove parenthetical noise common in YouTube Music and online sources
            cleaned = cleaned.replace(
                Regex("(?i)\\s*[\\[(](?:official\\s+)?(?:music\\s+)?(?:video|audio|lyric\\s+video|visualizer|hd|4k|remaster(?:ed)?(?:\\s+\\d{4})?|live|single|radio\\s+edit)[)\\]]"),
                ""
            )
            // Remove featured artists inside parentheses/brackets
            cleaned = cleaned.replace(
                Regex("(?i)\\s*[\\[(](?:feat\\.?|ft\\.?|with)\\s+[^)\\]]+[)\\]]"),
                ""
            )
            return cleaned.trim()
        }

        internal fun extractCleanArtist(artist: String): String {
            return artist
                .replace(Regex("(?i)\\s*-\\s*topic\\b"), "")
                .replace(Regex("(?i)\\b(?:various\\s+artists|online\\s+artist)\\b"), "")
                .trim()
        }

        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace(Regex("(?i)\\b(?:official\\s+)?(?:music\\s+)?(?:video|audio)\\b"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        internal fun matches(requested: MusicTrack, candidate: MusicTrack): Boolean {
            if (requested.isrc.isNotBlank() && candidate.isrc.isNotBlank()) {
                return requested.isrc.equals(candidate.isrc, ignoreCase = true)
            }

            val reqClean = normalize(extractCleanTitle(requested.title))
            val candClean = normalize(extractCleanTitle(candidate.title))

            if (reqClean.isBlank() || candClean.isBlank()) return false

            val reqWords = reqClean.split(" ").filter { it.length > 1 }
            val candWords = candClean.split(" ").filter { it.length > 1 }

            // Enhanced title verification: exact clean match, prefix match, or complete token coverage
            val titleMatches = reqClean == candClean ||
                candClean.startsWith(reqClean) ||
                reqClean.startsWith(candClean) ||
                (reqWords.isNotEmpty() && reqWords.all { candClean.contains(it) }) ||
                (candWords.isNotEmpty() && candWords.all { reqClean.contains(it) })

            if (!titleMatches) return false

            // Artist verification
            fun parseArtists(value: String): Set<String> = value
                .split(Regex("(?i),|&|;|/|\\bfeat\\.?|\\bft\\.?|\\bwith\\b|•"))
                .map { normalize(extractCleanArtist(it)) }
                .filter { it.isNotBlank() && it != "various artists" && it != "online artist" }
                .toSet()

            val reqArtists = parseArtists(requested.artist)
            val candArtists = parseArtists(candidate.artist)

            val artistMatches = if (reqArtists.isEmpty() || candArtists.isEmpty()) {
                true
            } else {
                reqArtists.intersect(candArtists).isNotEmpty() ||
                    reqArtists.any { reqArt -> candArtists.any { candArt -> reqArt.contains(candArt) || candArt.contains(reqArt) } } ||
                    candArtists.any { reqClean.contains(it) } ||
                    reqArtists.any { candClean.contains(it) } ||
                    (reqClean == candClean && (requested.durationMs <= 0 || candidate.durationMs <= 0 || abs(requested.durationMs - candidate.durationMs) < 30_000L))
            }

            if (!artistMatches) return false

            // Duration check: only if valid non-placeholder durations exist on both sides
            val isReqDurationPlaceholder = requested.durationMs <= 0 || requested.durationMs == 210000L
            val isCandDurationPlaceholder = candidate.durationMs <= 0 || candidate.durationMs == 210000L

            if (!isReqDurationPlaceholder && !isCandDurationPlaceholder) {
                val diff = abs(requested.durationMs - candidate.durationMs)
                val tolerance = maxOf(60_000L, (requested.durationMs * 0.35).toLong())
                if (diff > tolerance) return false
            }

            return true
        }
    }
}

