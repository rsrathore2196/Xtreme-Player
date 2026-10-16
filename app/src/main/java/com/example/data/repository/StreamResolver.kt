package com.example.data.repository

import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.OnlineMusicApiService
import com.example.playback.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
        failedUrl: String? = null,
        deferLossless: Boolean = false
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

        suspend fun <T> attempt(timeoutMs: Long, block: suspend () -> T): T? = try {
            withTimeoutOrNull(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            // A provider's own deadline is a miss, not cancellation of the song
            // selection. A parent timeout/track change must still propagate.
            coroutineContext.ensureActive()
            null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }

        fun usableYouTube(result: MusicTrack): Boolean {
            if (!isValidStreamUrl(result.audioUrl)) return false
            // A stable URI can stay identical after its signed URL was refreshed
            // and probed. Rejecting it would prevent recovery of the same audio.
            val refreshed = forceRefresh && com.example.data.remote.YouTubeAudioStreamService.isPlaybackUri(result.audioUrl)
            return refreshed || result.audioUrl != rejectedUrl && result.audioUrl != failedUrl
        }

        suspend fun exactRecording(): MusicTrack? {
            if (!track.id.startsWith("online_")) return null
            val id = track.id.removePrefix("online_")
            val exact = attempt(8_000L) { fetchById(id, forceRefresh) }
            return exact?.takeIf { it.id == track.id && isValidStreamUrl(it.audioUrl) &&
                it.audioUrl != rejectedUrl && it.audioUrl != failedUrl }
        }

        suspend fun find(search: suspend (String) -> List<MusicTrack>, text: String, timeoutMs: Long = 8_000L): MusicTrack? {
            if (text.isBlank()) return null
            val candidates = attempt(timeoutMs) { search(text) }.orEmpty()
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
            if ((!track.id.startsWith("yt_") && !com.example.data.remote.YouTubeAudioStreamService.isPlaybackUri(track.audioUrl)) ||
                !attemptedYouTubeTracks.add(track.id)) return null
            val resolved = attempt(40_000L) { resolveYouTube(track, quality, forceRefresh) }
            return resolved?.takeIf { it.id == track.id && usableYouTube(it) }?.copy(source = "Extended Stream")
        }

        suspend fun searchYouTube(query: String, songsOnly: Boolean = true): MusicTrack? {
            if (query.isBlank()) return null
            return attempt(40_000L) {
                val ytTracks = try {
                    val initial = com.example.data.remote.YouTubeMusicApiService.searchSongs(query, limit = 12, songsOnly = songsOnly)
                    if (initial.isEmpty()) {
                        com.example.data.remote.YouTubeMusicApiService.searchSongsPage(query).tracks
                    } else initial
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    try { com.example.data.remote.YouTubeMusicApiService.searchSongsPage(query).tracks }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
                for (cand in ytTracks) {
                    if (!cand.id.startsWith("yt_") || !matches(track, cand)) continue
                    if (!attemptedYouTubeTracks.add(cand.id)) continue
                    val resolved = try {
                        resolveYouTube(cand, quality, forceRefresh)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (resolved != null && usableYouTube(resolved)) {
                        return@attempt withStream(track, resolved)
                    }
                }
                null
            }
        }

        // Progressive targeted queries:
        // 1. Clean Title + Clean Artist (highest precision, stripped of video/audio tags)
        val primaryQuery = "$cleanTitle $cleanArtist".trim()
        val primaryArtist = cleanArtist.split(Regex("(?i),|;|&|•|feat\\.?|ft\\.?|featuring|/")).firstOrNull()?.trim().orEmpty()
        val primaryArtistQuery = if (primaryArtist.isNotBlank() && primaryArtist != cleanArtist) "$cleanTitle $primaryArtist".trim() else ""
        // 2. Clean Title only
        val titleOnlyQuery = cleanTitle.trim()
        // 3. Reversed Artist + Title
        val artistFirstQuery = if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) "$cleanArtist $cleanTitle".trim() else ""

        suspend fun findProviderRecording(losslessTimeoutMs: Long = 8_000L): MusicTrack? =
            (if (quality == AudioQuality.HI_RES_LOSSLESS && !deferLossless) find(searchLossless, primaryQuery, losslessTimeoutMs) else null)
            ?: exactRecording()
            ?: find(standard, primaryQuery)
            ?: (if (primaryArtistQuery.isNotBlank() && primaryArtistQuery != primaryQuery) find(standard, primaryArtistQuery) else null)
            ?: (if (titleOnlyQuery != primaryQuery && titleOnlyQuery.isNotBlank()) find(standard, titleOnlyQuery) else null)
            ?: (if (artistFirstQuery.isNotBlank() && artistFirstQuery != primaryQuery) find(standard, artistFirstQuery) else null)

        // A YouTube catalogue selection keeps its original video as a playable fallback.
        // Share one deadline across all upgrade queries; Archive cannot consume the entire
        // budget before JioSaavn gets a chance. Only this timeout becomes a miss: switching
        // tracks or canceling playback still cancels resolution and its HTTP requests.
        val immediateYouTube = if (deferLossless && quality == AudioQuality.HI_RES_LOSSLESS) exactYouTube() else null
        val providerRecording = if (immediateYouTube != null) null else if (track.id.startsWith("yt_")) {
            withTimeoutOrNull(ALTERNATE_SOURCE_TIMEOUT_MS) {
                findProviderRecording(LOSSLESS_LOOKUP_TIMEOUT_MS)
            }
        } else findProviderRecording()

        val candidate = immediateYouTube ?: providerRecording
            ?: exactYouTube()
            ?: searchYouTube(primaryQuery, songsOnly = true)
            ?: searchYouTube(primaryQuery, songsOnly = false)
            ?: (if (primaryArtistQuery.isNotBlank() && primaryArtistQuery != primaryQuery) searchYouTube(primaryArtistQuery, songsOnly = true) else null)
            ?: (if (primaryArtistQuery.isNotBlank() && primaryArtistQuery != primaryQuery) searchYouTube(primaryArtistQuery, songsOnly = false) else null)
            ?: (if (artistFirstQuery.isNotBlank() && artistFirstQuery != primaryQuery) searchYouTube(artistFirstQuery, songsOnly = true) else null)
            ?: (if (artistFirstQuery.isNotBlank() && artistFirstQuery != primaryQuery) searchYouTube(artistFirstQuery, songsOnly = false) else null)
            ?: (if (titleOnlyQuery != primaryQuery && titleOnlyQuery.isNotBlank()) searchYouTube(titleOnlyQuery, songsOnly = true) else null)
            ?: (if (titleOnlyQuery != primaryQuery && titleOnlyQuery.isNotBlank()) searchYouTube(titleOnlyQuery, songsOnly = false) else null)
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
        private const val ALTERNATE_SOURCE_TIMEOUT_MS = 6_000L
        private const val LOSSLESS_LOOKUP_TIMEOUT_MS = 3_000L

        internal fun matchesLosslessUpgrade(requested: MusicTrack, candidate: MusicTrack): Boolean {
            if (!candidate.isLossless || candidate.audioUrl.isBlank() || requested.durationMs <= 0 || candidate.durationMs <= 0) return false
            if (abs(requested.durationMs - candidate.durationMs) > 2_000L) return false
            if (normalize(extractCleanTitle(requested.title, requested.artist)) != normalize(extractCleanTitle(candidate.title, candidate.artist))) return false
            val requestedIsrc = isrc(requested.isrc); val candidateIsrc = isrc(candidate.isrc)
            val requestedArtists = artists(requested.artist); val candidateArtists = artists(candidate.artist)
            if (requestedArtists.isNotEmpty() && candidateArtists.isNotEmpty() && requestedArtists != candidateArtists) return false
            if (requestedIsrc != null && candidateIsrc != null) return requestedIsrc == candidateIsrc
            return requestedArtists.isNotEmpty() && requestedArtists == candidateArtists
        }

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
                    } || reqArtists.any { r -> candTitle.contains(r) } || candArtists.any { c -> reqTitle.contains(c) }
                    if (!sharedArtist) return false
                }
            }

            if (requested.durationMs > 0 && candidate.durationMs > 0) {
                val diff = abs(requested.durationMs - candidate.durationMs)
                val tolerance = maxOf(45_000L, (requested.durationMs * 0.25).toLong())
                if (diff > tolerance) return false
            }

            return true
        }
    }
}

