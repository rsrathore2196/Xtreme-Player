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
    },
    private val resolveYouTube: suspend (MusicTrack, AudioQuality, Boolean) -> MusicTrack? = { track, quality, refresh ->
        com.example.data.remote.YouTubeMusicApiService.resolveAudio(track, quality, refresh)
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
                !needsResolution(track)
            ) {
                return@withContext track
            }
        }

        val rejectedUrl = failedUrl ?: track.audioUrl.takeIf { forceRefresh }
        val cleanTitle = extractCleanTitle(track.title, track.artist)
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
        val attemptedYouTubeTracks = mutableSetOf<String>()
        suspend fun exactYouTube(): MusicTrack? {
            if (!track.id.startsWith("yt_") || !attemptedYouTubeTracks.add(track.id)) return null
            return try { withTimeoutOrNull(40_000L) { resolveYouTube(track, quality, forceRefresh) } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }

        suspend fun searchYouTube(query: String): MusicTrack? {
            if (query.isBlank()) return null
            return try {
                withTimeoutOrNull(40_000L) {
                    val ytTracks = com.example.data.remote.YouTubeMusicApiService.searchSongs(query, limit = 5, songsOnly = true)
                    for (cand in ytTracks) {
                        if (!cand.id.startsWith("yt_") || !matches(track, cand)) continue
                        if (!attemptedYouTubeTracks.add(cand.id)) continue
                        val resolved = try {
                            resolveYouTube(cand, quality, forceRefresh)
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        if (resolved != null && isValidStreamUrl(resolved.audioUrl) &&
                            resolved.audioUrl != rejectedUrl && resolved.audioUrl != failedUrl) {
                            return@withTimeoutOrNull withStream(track, resolved)
                        }
                    }
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        // Progressive targeted queries:
        // 1. Clean Title + Clean Artist (highest precision, stripped of video/audio tags)
        val primaryQuery = "$cleanTitle $cleanArtist".trim()
        // 2. Clean Title only
        val titleOnlyQuery = cleanTitle.trim()
        // 3. Reversed Artist + Title
        val artistFirstQuery = if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) "$cleanArtist $cleanTitle".trim() else ""

        val exactYt = if (track.id.startsWith("yt_")) exactYouTube() else null
        val candidate = exactYt?.takeIf { it.id == track.id && isValidStreamUrl(it.audioUrl) && it.audioUrl != rejectedUrl }
            ?: (if (quality == AudioQuality.HI_RES_LOSSLESS) find(searchLossless, primaryQuery) else null)
            ?: exactRecording()
            ?: find(standard, primaryQuery)
            ?: (if (titleOnlyQuery != primaryQuery && titleOnlyQuery.isNotBlank()) find(standard, titleOnlyQuery) else null)
            ?: (if (artistFirstQuery.isNotBlank() && artistFirstQuery != primaryQuery) find(standard, artistFirstQuery) else null)
            ?: searchYouTube(primaryQuery)
            ?: (if (titleOnlyQuery != primaryQuery && titleOnlyQuery.isNotBlank()) searchYouTube(titleOnlyQuery) else null)
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
        isLossless = stream.isLossless,
        source = stream.source
    )

    companion object {
        internal fun needsResolution(track: MusicTrack): Boolean {
            if (track.audioUrl.isBlank() || track.audioUrl.startsWith("yt_") || track.audioUrl.contains("placeholder")) return true
            // Old cross-provider mappings cannot establish which recording a stored URL plays.
            if (track.id.startsWith("yt_") && track.audioUrl.startsWith("http")) {
                val host = android.net.Uri.parse(track.audioUrl).host.orEmpty()
                return host != "googlevideo.com" && !host.endsWith(".googlevideo.com")
            }
            return false
        }
        private fun isValidStreamUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            if (url.startsWith("yt_") || url.contains("placeholder") || url == "about:blank") return false
            return true
        }

        internal fun extractCleanTitle(title: String, artist: String = ""): String {
            var cleaned = title
            // Strip an uploader's artist prefix only when it is the actual credited artist.
            if (cleaned.contains(" - ")) {
                val prefix = cleaned.substringBefore(" - ")
                if (normalize(extractCleanArtist(prefix)) in artists(artist)) {
                    cleaned = cleaned.substringAfter(" - ")
                }
            }
            // Remove parenthetical noise common in YouTube Music and online sources
            cleaned = cleaned.replace(
                Regex("(?i)\\s*[\\[(](?:official\\s+)?(?:music\\s+)?(?:video|audio|lyric\\s+video|lyrics|visualizer|hd|4k|full\\s+song|from\\s+\"[^\"]+\"|hindi|punjabi|tamil|telugu|bhojpuri)[)\\]]"),
                ""
            )
            cleaned = cleaned.replace(
                Regex("(?i)\\s*-\\s*(?:official\\s+)?(?:music\\s+)?(?:video|audio|lyric\\s+video|lyrics|visualizer|hd|4k|hindi|punjabi|tamil|telugu|bhojpuri)\\b.*"),
                ""
            )
            // Live, edits, remasters, featured credits and other version text are identity.
            return cleaned.trim()
        }

        internal fun extractCleanArtist(artist: String): String {
            return artist
                .replace(Regex("(?i)\\s*-\\s*topic\\b"), "")
                .replace(Regex("(?i)\\b(?:various\\s+artists|online\\s+artist)\\b"), "")
                .trim()
        }

        private fun normalize(value: String): String = java.text.Normalizer.normalize(value,
            java.text.Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

        private fun artists(value: String): Set<String> = value
            .split(Regex("(?i),|;|•|\\s+(?:&|and|feat\\.?|ft\\.?|featuring|with)\\s+"))
            .map { normalize(extractCleanArtist(it)) }
            .filter { it !in setOf("", "unknown", "unknown artist", "various artists", "online artist", "youtube artist") }.toSet()

        private fun isrc(value: String): String? = value.uppercase(Locale.ROOT)
            .replace(Regex("[-\\s]"), "").takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }

        internal fun matches(requested: MusicTrack, candidate: MusicTrack): Boolean {
            val requestedIsrc = isrc(requested.isrc)
            val candidateIsrc = isrc(candidate.isrc)
            val fingerprintMatches = requestedIsrc != null && candidateIsrc != null && requestedIsrc == candidateIsrc
            if (requestedIsrc != null && candidateIsrc != null && !fingerprintMatches) return false

            val reqTitle = normalize(extractCleanTitle(requested.title, requested.artist))
            val candTitle = normalize(extractCleanTitle(candidate.title, candidate.artist))
            if (reqTitle.isBlank() || candTitle.isBlank()) return false

            val version = Regex("(?i)\\b(?:live|remix|remaster(?:ed)?|radio edit|acoustic|instrumental|karaoke|cover|slowed|reverb|extended|demo)\\b")
            val reqVersions = version.findAll(requested.title).map { it.value.lowercase(Locale.ROOT) }.toSet()
            val candVersions = version.findAll(candidate.title).map { it.value.lowercase(Locale.ROOT) }.toSet()
            if (reqVersions != candVersions) return false

            if (!fingerprintMatches) {
                val titleMatches = reqTitle == candTitle ||
                    reqTitle.startsWith(candTitle) || candTitle.startsWith(reqTitle) ||
                    reqTitle.contains(candTitle) || candTitle.contains(reqTitle) ||
                    (reqTitle.split(" ").filter { it.length > 2 }.let { words ->
                        words.isNotEmpty() && words.count { candTitle.contains(it) } >= (words.size + 1) / 2
                    })
                if (!titleMatches) return false

                val reqArtists = artists(requested.artist)
                val candArtists = artists(candidate.artist)
                if (reqArtists.isNotEmpty() && candArtists.isNotEmpty()) {
                    val sharedArtist = reqArtists.any { r ->
                        candArtists.any { c -> r == c || r.contains(c) || c.contains(r) }
                    }
                    if (!sharedArtist) return false
                }
            }

            if (requested.durationMs > 0 && candidate.durationMs > 0) {
                val diff = abs(requested.durationMs - candidate.durationMs)
                val tolerance = maxOf(30_000L, (requested.durationMs * 0.20).toLong())
                if (diff > tolerance) return false
            }

            return true
        }
    }
}

