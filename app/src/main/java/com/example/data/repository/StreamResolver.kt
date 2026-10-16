package com.example.data.repository

import android.net.Uri
import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeMusicApiService
import com.example.playback.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext

class TrackUnavailableException(title: String) : IOException("No matching playable stream is available for '$title'.")

/** Selected source first, followed only by verified recordings. Archive is never initial playback. */
internal class StreamResolver(
    private val searchStandard: suspend (String, Boolean) -> List<MusicTrack> = { query, refresh ->
        OnlineMusicApiService.searchSongs(query, limit = 20, forceRefresh = refresh)
    },
    // Retain the injection signature for existing callers; quality upgrades have their own lifecycle.
    @Suppress("UNUSED_PARAMETER") searchLossless: suspend (String) -> List<MusicTrack> = { emptyList() },
    private val fetchById: suspend (String, Boolean) -> MusicTrack? = { id, refresh ->
        OnlineMusicApiService.fetchSongDetails(id, refresh)
    },
    private val resolveYouTube: suspend (MusicTrack, AudioQuality, Boolean) -> MusicTrack? = { track, quality, refresh ->
        YouTubeMusicApiService.resolveAudio(track, quality, refresh)
    }
) {
    private data class CachedStream(val track: MusicTrack, val expiresAt: Long)
    private val cache = LinkedHashMap<Triple<String, AudioQuality, Boolean>, CachedStream>(128, 0.75f, true)
    val cacheSize: Int get() = synchronized(cache) { cache.size }
    fun clear() = synchronized(cache) { cache.clear() }

    suspend fun resolve(track: MusicTrack, quality: AudioQuality, forceRefresh: Boolean = false,
        failedUrl: String? = null, @Suppress("UNUSED_PARAMETER") deferLossless: Boolean = true
    ): MusicTrack = withContext(Dispatchers.IO) {
        val key = Triple(track.id, quality, track.recommendationOnly)
        val youtubeSelection = !track.recommendationOnly && selectedYouTube(track)
        fun providerAllowed(candidate: MusicTrack) = true
        if (forceRefresh) synchronized(cache) { cache.keys.removeAll { it.first == track.id } }
        else {
            // A working selected provider must not be displaced by an older cached fallback.
            if (isValidStreamUrl(track.audioUrl) && track.audioUrl != failedUrl && !needsResolution(track))
                return@withContext track
            val cached = synchronized(cache) { cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.track }
            if (cached != null && providerAllowed(cached) && cached.audioUrl != failedUrl && isValidStreamUrl(cached.audioUrl)) {
                return@withContext withStream(track, cached)
            }
        }
        val rejected = failedUrl ?: track.audioUrl.takeIf { forceRefresh }
        if (forceRefresh && failedUrl != null && youtubeSelection) selectedVideoId(track)?.let {
            YouTubeAudioStreamService.reject(it, rejected)
        }
        suspend fun <T> attempt(timeoutMs: Long, block: suspend () -> T): T? = try {
            withTimeoutOrNull(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) { coroutineContext.ensureActive(); null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        fun usable(candidate: MusicTrack): Boolean = providerAllowed(candidate) && isValidStreamUrl(candidate.audioUrl) &&
            candidate.audioUrl != rejected && candidate.audioUrl != failedUrl

        val triedVideos = mutableSetOf<String>()
        suspend fun youtubeExact(request: MusicTrack): MusicTrack? {
            val id = selectedVideoId(request) ?: return null
            if (!triedVideos.add(id)) return null
            val resolved = attempt(15_000L) { resolveYouTube(request, quality, forceRefresh) } ?: return null
            if (resolved.id != request.id || !isValidStreamUrl(resolved.audioUrl) ||
                YouTubeAudioStreamService.videoIdFromUri(Uri.parse(resolved.audioUrl)) != id) return null
            return resolved.copy(isLossless = false, source = "YouTube Music")
        }
        suspend fun jioExact(): MusicTrack? {
            if (!track.id.startsWith("online_")) return null
            return attempt(5_000L) { fetchById(track.id.removePrefix("online_"), forceRefresh) }
                ?.takeIf { it.id == track.id && usable(it) }
        }
        val title = extractCleanTitle(track.title, track.artist)
        val artist = extractCleanArtist(track.artist)
        val primary = RecordingIdentity.artistNames(track).firstOrNull().orEmpty()
        val queries = listOf("$title $artist".trim(), "$title $primary".trim(), title)
            .filter(String::isNotBlank).distinctBy(CatalogNames::normalize).take(3)
        suspend fun standardFallback(): MusicTrack? = attempt(6_000L) {
            for (query in queries) {
                val candidates = attempt(3_500L) { searchStandard(query, forceRefresh) }.orEmpty()
                val ranked = withContext(Dispatchers.Default) { candidates.mapNotNull { candidate ->
                    RecordingIdentity.fallbackConfidence(track, candidate)?.let { candidate to it }
                }.sortedByDescending { it.second }.take(8).map { it.first } }
                for (candidate in ranked) {
                    if (usable(candidate)) return@attempt candidate
                    if (!candidate.id.startsWith("online_") || candidate.audioUrl.isNotBlank()) continue
                    val detailed = attempt(2_500L) { fetchById(candidate.id.removePrefix("online_"), forceRefresh) }
                    if (detailed != null && matches(track, detailed) && usable(detailed)) return@attempt detailed
                }
            }
            null
        }
        suspend fun youtubeFallback(): MusicTrack? = attempt(15_000L) {
            var extractions = 0
            for (query in queries.take(2)) {
                val candidates = attempt(5_000L) {
                    YouTubeMusicApiService.searchSongsPage(query, useInnerTubeX = true).tracks
                }.orEmpty()
                val ranked = withContext(Dispatchers.Default) { candidates.mapNotNull { candidate ->
                    RecordingIdentity.fallbackConfidence(track, candidate)?.let { candidate to it }
                }.sortedByDescending { it.second }.take(8).map { it.first } }
                val targetCandidates = if (ranked.isNotEmpty()) ranked else candidates.take(3)
                for (candidate in targetCandidates) {
                    if (selectedVideoId(candidate) in triedVideos) continue
                    if (extractions++ >= 3) return@attempt null
                    youtubeExact(candidate)?.let { return@attempt it }
                }
            }
            null
        }
        val resolved = if (youtubeSelection) {
            youtubeExact(track) ?: youtubeFallback()
        } else {
            // 1. Maintain JioSaavn as primary source within strict 6-second window
            val jioResult = attempt(6_000L) {
                jioExact() ?: standardFallback()
            }
            // 2. If JioSaavn is unavailable, restricted or fails within 6s, fallback to YouTube Music via InnertubeX
            jioResult ?: youtubeFallback()
        } ?: throw TrackUnavailableException(track.title)

        coroutineContext.ensureActive()
        val result = withStream(track, resolved)
        synchronized(cache) {
            cache[key] = CachedStream(result, System.currentTimeMillis() + 300_000L)
            while (cache.size > 128) cache.remove(cache.keys.first())
        }
        result
    }

    suspend fun resolveYouTubeFallback(track: MusicTrack, quality: AudioQuality = AudioQuality.ULTRA_HD_320): MusicTrack? = withContext(Dispatchers.IO) {
        val title = extractCleanTitle(track.title, track.artist)
        val artist = extractCleanArtist(track.artist)
        val primary = RecordingIdentity.artistNames(track).firstOrNull().orEmpty()
        val queries = listOf("$title $artist".trim(), "$title $primary".trim(), title)
            .filter(String::isNotBlank).distinctBy(CatalogNames::normalize).take(2)

        for (query in queries) {
            val candidates = try {
                withTimeoutOrNull(5_000L) {
                    YouTubeMusicApiService.searchSongsPage(query, useInnerTubeX = true).tracks
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }.orEmpty()

            val ranked = withContext(Dispatchers.Default) {
                candidates.mapNotNull { candidate ->
                    RecordingIdentity.fallbackConfidence(track, candidate)?.let { candidate to it }
                }.sortedByDescending { it.second }.take(5).map { it.first }
            }
            val targetCandidates = if (ranked.isNotEmpty()) ranked else candidates.take(3)

            for (candidate in targetCandidates) {
                val resolved = try {
                    withTimeoutOrNull(6_000L) {
                        resolveYouTube(candidate, quality, false)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

                if (resolved != null && isValidStreamUrl(resolved.audioUrl)) {
                    return@withContext track.copy(
                        audioUrl = resolved.audioUrl,
                        bitrateKbps = resolved.bitrateKbps,
                        qualityBadge = resolved.qualityBadge.ifBlank { "HQ Stream" },
                        isLossless = false,
                        source = "YouTube Music"
                    )
                }
            }
        }
        null
    }

    private fun withStream(track: MusicTrack, stream: MusicTrack) = track.copy(audioUrl = stream.audioUrl,
        bitrateKbps = stream.bitrateKbps, qualityBadge = stream.qualityBadge,
        isLossless = stream.isLossless, source = stream.source)

    companion object {
        private val presentation = Regex("(?i)\\s*[\\[(](?:official\\s+)?(?:music\\s+)?(?:video|audio|lyric\\s+video|lyrics|visualizer|hd|4k|full\\s+song|from\\s+\"[^\"]+\")[)\\]]")
        private val presentationSuffix = Regex("(?i)\\s*[-|•]\\s*(?:official\\s+)?(?:music\\s+)?(?:video|audio|lyric\\s+video|lyrics|visualizer|hd|4k)\\b(?=\\s*(?:$|[-|•\\[(]))")
        private val officialSuffix = Regex("(?i)\\s+(?:official\\s+(?:music\\s+)?(?:audio|video)|lyric\\s+video)\\b(?:\\s+(?:hd|4k)\\b)?")
        private val topicSuffix = Regex("(?i)\\s*-\\s*topic\\b")
        private val unknownArtists = Regex("(?i)\\b(?:various\\s+artists|online\\s+artist)\\b")

        internal fun matchesLosslessUpgrade(requested: MusicTrack, candidate: MusicTrack): Boolean =
            candidate.isLossless && candidate.audioUrl.startsWith("https://") &&
                (RecordingIdentity.title(requested) == RecordingIdentity.title(candidate) ||
                    RecordingIdentity.isrc(requested.isrc)?.let { it == RecordingIdentity.isrc(candidate.isrc) } == true &&
                    CatalogNames.spellingEquivalent(RecordingIdentity.title(requested), RecordingIdentity.title(candidate))) &&
                RecordingIdentity.fallbackConfidence(requested, candidate, durationToleranceMs = 2_000L,
                    requireDuration = true) != null

        private fun selectedVideoId(track: MusicTrack): String? =
            track.id.removePrefix("yt_").takeIf { track.id.startsWith("yt_") && YouTubeAudioStreamService.isVideoId(it) }
                ?: YouTubeAudioStreamService.videoIdFromUri(Uri.parse(track.audioUrl))

        private fun selectedYouTube(track: MusicTrack): Boolean =
            YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl) ||
                track.id.startsWith("yt_") && (track.source != "JIOSAAVN" || archiveTransport(track.audioUrl))

        private fun archiveTransport(url: String): Boolean = Uri.parse(url).host?.let {
            it == "archive.org" || it.endsWith(".archive.org")
        } == true

        internal fun needsResolution(track: MusicTrack): Boolean {
            if (!isValidStreamUrl(track.audioUrl)) return true
            // Downloaded files must continue to work offline. Persisted optional upgrades,
            // however, must not become the initial source on a later catalog selection.
            if (Uri.parse(track.audioUrl).scheme in setOf("file", "content")) return false
            return selectedYouTube(track) || archiveTransport(track.audioUrl) &&
                (track.id.startsWith("online_") || track.id.startsWith("yt_"))
        }

        internal fun isValidStreamUrl(url: String?): Boolean {
            if (url.isNullOrBlank() || url.contains("placeholder", true) || url.startsWith("yt_")) return false
            val uri = Uri.parse(url)
            return when (uri.scheme) {
                "xtreme-youtube", "xtreme-sabr" -> YouTubeAudioStreamService.videoIdFromUri(uri) != null
                "https", "http" -> !uri.host.isNullOrBlank() &&
                    (!(uri.host == "youtube.com" || uri.host?.endsWith(".youtube.com") == true || uri.host == "youtu.be") ||
                        YouTubeAudioStreamService.isMediaUrl(url))
                "file", "content" -> !uri.path.isNullOrBlank()
                else -> false
            }
        }

        internal fun extractCleanTitle(title: String, artist: String = ""): String {
            var cleaned = CatalogNames.clean(title)
            if (cleaned.contains(" - ")) {
                val prefix = cleaned.substringBefore(" - ")
                if (CatalogNames.normalize(extractCleanArtist(prefix)) == CatalogNames.normalize(extractCleanArtist(artist)))
                    cleaned = cleaned.substringAfter(" - ")
            }
            // Live, remix, acoustic, edits, featured credits and remaster years remain identity.
            return cleaned.replace(presentation, "").replace(presentationSuffix, "")
                .replace(officialSuffix, "").trim()
        }
        internal fun extractCleanArtist(artist: String): String = artist.replace(topicSuffix, "")
            .replace(unknownArtists, "").trim()
        internal fun matches(requested: MusicTrack, candidate: MusicTrack): Boolean =
            RecordingIdentity.fallbackConfidence(requested, candidate) != null
    }
}
