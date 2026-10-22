package com.example.data.repository

import com.example.performance.AppDispatchers

import android.net.Uri
import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeMusicApiService
import com.example.playback.AudioQuality
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackSourcePolicy
import com.example.playback.AudioQualityCeiling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext

class TrackUnavailableException(title: String) : IOException("No matching playable stream is available for '$title'.")

/** Quality-tier routing; every remote substitution must verify the same recording. */
internal class StreamResolver(
    private val searchStandard: suspend (String, Boolean) -> List<MusicTrack> = { query, refresh ->
        OnlineMusicApiService.searchSongs(query, limit = 20, forceRefresh = refresh)
    },
    private val searchLossless: suspend (String) -> List<MusicTrack> = { query ->
        val page = com.example.data.remote.InternetArchiveApiService.searchLosslessPage(query, pageSize = 8, playableOnly = true)
        if (!page.isSuccessful) throw IOException("Incomplete Archive lookup")
        page.tracks
    },
    private val fetchById: suspend (String, Boolean) -> MusicTrack? = { id, refresh ->
        OnlineMusicApiService.fetchSongDetails(id, refresh)
    },
    private val resolveYouTube: suspend (MusicTrack, AudioQuality, Boolean, Int) -> MusicTrack? = { track, quality, refresh, cap ->
        YouTubeMusicApiService.resolveAudio(track, quality, refresh, maxBitrateBps = cap)
    },
    private val searchYouTube: suspend (String) -> List<MusicTrack> = { query ->
        YouTubeMusicApiService.searchSongsPage(query, useInnerTubeX = true).tracks
    },
    private val supportsLossless: suspend (MusicTrack) -> Boolean = { track ->
        withContext(Dispatchers.IO) {
            val path = Uri.parse(track.audioUrl).path.orEmpty().lowercase(java.util.Locale.ROOT)
            val mime = when {
                path.endsWith(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
                path.endsWith(".m4a") -> androidx.media3.common.MimeTypes.AUDIO_ALAC
                else -> null
            }
            path.endsWith(".wav") || mime != null && runCatching {
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty()
            }.getOrDefault(false)
        }
    },
    private val resolveOpus: suspend (MusicTrack, AudioQuality, Boolean, Int) -> MusicTrack? = { track, quality, refresh, cap ->
        YouTubeMusicApiService.resolveAudio(track, quality, refresh, requireOpus = true, maxBitrateBps = cap)
    },
    private val verifyArchive: suspend (MusicTrack) -> MusicTrack? = { track ->
        com.example.data.remote.InternetArchiveApiService.verifyDirectRecording(track) { true }
    },
    private val resolveAddon: suspend (PlaybackSource, MusicTrack, PlaybackSourcePolicy, Set<String>) -> MusicTrack? = { _, _, _, _ -> null }
) {
    private data class CachedStream(val track: MusicTrack, val expiresAt: Long)
    private data class StreamKey(val id: String, val quality: AudioQuality, val recommendation: Boolean, val cap: Int,
        val recording: String, val policy: String?, val selectedArchiveUrl: String? = null)
    private val archiveAttempts = LinkedHashMap<String, Long>()
    // Catalog identities outlive signed URLs. Playback re-extracts the transport
    // for these exact, previously verified cross-provider matches.
    private val providerMatches = android.util.LruCache<String, Pair<Long, MusicTrack>>(256)
    suspend fun hasRecentLosslessAttempt(track: MusicTrack): Boolean = withContext(AppDispatchers.CpuBounded) {
        val key = RecordingIdentity.key(track)
        synchronized(archiveAttempts) { (archiveAttempts[key] ?: 0L) > android.os.SystemClock.elapsedRealtime() }
    }
    private val archiveMisses = LinkedHashMap<String, Long>()
    private val cache = LinkedHashMap<StreamKey, CachedStream>(128, 0.75f, true)
    val cacheSize: Int get() = synchronized(cache) { cache.size }
    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(archiveMisses) { archiveMisses.clear() }
        synchronized(archiveAttempts) { archiveAttempts.clear() }
        providerMatches.evictAll()
        clearVerifiedLosslessCache()
    }

    suspend fun resolve(track: MusicTrack, preference: com.example.playback.AudioQualityPreference): MusicTrack =
        resolve(track, preference.legacy)

    suspend fun resolve(track: MusicTrack, quality: AudioQuality, forceRefresh: Boolean = false,
        failedUrl: String? = null, @Suppress("UNUSED_PARAMETER") deferLossless: Boolean = true,
        preferYouTube: Boolean = false, @Suppress("UNUSED_PARAMETER") jioDeadlineMs: Long = 0L, maxBitrateBps: Int = Int.MAX_VALUE,
        currentSourceOnly: Boolean = false, rejectedUrls: Set<String> = emptySet(),
        sourcePolicy: com.example.playback.PlaybackSourcePolicy? = null,
        preferredSourceId: String? = null, manualUpgrade: Boolean = false
    ): MusicTrack = withContext(Dispatchers.IO) {
        val ceilingBps = minOf(maxBitrateBps, if (quality == AudioQuality.HI_RES_LOSSLESS) Int.MAX_VALUE else quality.kbps * 1000)
        val policy = sourcePolicy ?: PlaybackSourcePolicy(AudioQualityCeiling.entries.lastOrNull { it.maxBitrateBps <= ceilingBps } ?: AudioQualityCeiling.DATA_SAVER)
        val recordingKey = withContext(AppDispatchers.CpuBounded) { RecordingIdentity.key(track) }
        if (manualUpgrade) synchronized(archiveMisses) { archiveMisses.remove(recordingKey) }
        val archiveUrl = com.example.data.remote.InternetArchiveApiService.originalFileUrl(track)
            ?: track.audioUrl.takeIf { archiveTransport(it) }
        val selectedArchive = archiveUrl != null &&
            (track.id.startsWith("ia_") || track.source.equals("INTERNET_ARCHIVE", true))
        // A catalog identity can survive a verified cross-provider match. An
        // explicitly selected Archive file must not hit that identity's AAC cache.
        val key = StreamKey(track.id, quality, track.recommendationOnly, ceilingBps, recordingKey, sourcePolicy?.key,
            archiveUrl.takeIf { selectedArchive })
        fun capped(candidate: MusicTrack): MusicTrack {
            val host = uriHost(candidate.audioUrl).orEmpty()
            if (host != "saavncdn.com" && !host.endsWith(".saavncdn.com")) return candidate
            val bitrate = com.example.playback.AudioQualityCeiling.saavnKbps(quality.kbps,
                candidate.bitrateKbps.takeIf { it in 1..320 } ?: 320, ceilingBps)
            return candidate.copy(audioUrl = OnlineMusicApiService.formatUrlForQuality(candidate.audioUrl, bitrate.toString()),
                bitrateKbps = bitrate, qualityBadge = YouTubeAudioStreamService.displayQuality("AAC", bitrate))
        }
        val youtubeSelection = YouTubeAudioStreamService.isYouTubeAudio(track.id, track.audioUrl)
        fun providerAllowed(candidate: MusicTrack): Boolean {
            if (!isValidStreamUrl(candidate.audioUrl)) return false
            // Custom transports are admitted only by the opt-in add-on resolver.
            // A saved URL must not bypass a subsequently disabled add-on.
            if (candidate.source.startsWith("Streaming Addon:")) return false
            if (archiveTransport(candidate.audioUrl)) return policy.maximum && policy.allows(PlaybackSourceKind.INTERNET_ARCHIVE)
            if (candidate.isLossless) return false
            if (YouTubeAudioStreamService.isYouTubeAudio(candidate.id, candidate.audioUrl))
                return !candidate.isLossless && (candidate.bitrateKbps <= 0 ||
                    candidate.bitrateKbps.toLong() * 1000 <= ceilingBps)
            val host = uriHost(candidate.audioUrl).orEmpty()
            return policy.allows(PlaybackSourceKind.JIOSAAVN) &&
                (host == "saavncdn.com" || host.endsWith(".saavncdn.com"))
        }
        // Local/downloaded audio remains usable offline and never needs provider substitution.
        if (uriScheme(track.audioUrl) in setOf("file", "content")) return@withContext track
        if (sourcePolicy != null && !sourcePolicy.online) {
            // Let the existing Media3 cache serve the original transport offline;
            // never turn offline policy into provider discovery/extraction requests.
            if (providerAllowed(track)) return@withContext capped(track)
            throw TrackUnavailableException(track.title)
        }
        if (forceRefresh || preferYouTube) synchronized(cache) { cache.keys.removeAll { it.id == track.id } }
        else {
            val cached = synchronized(cache) { cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.track }
            if (cached != null && providerAllowed(cached) && cached.audioUrl != failedUrl &&
                cached.audioUrl !in rejectedUrls && (!selectedArchive ||
                    cached.audioUrl == track.audioUrl && cached.isLossless)) {
                if (currentSourceOnly && !YouTubeAudioStreamService.isYouTubeAudio(cached.id, cached.audioUrl))
                    return@withContext withStream(track, capped(cached))

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
            candidate.audioUrl != rejected && candidate.audioUrl != failedUrl &&
            capped(candidate).audioUrl != failedUrl && capped(candidate).audioUrl != rejected &&
            candidate.audioUrl !in rejectedUrls && capped(candidate).audioUrl !in rejectedUrls &&
            !isLosslessUrlRejected(candidate.audioUrl)

        fun matchedProvider(name: String): MusicTrack? = providerMatches.get("$name:$recordingKey")
            ?.takeIf { it.first > System.currentTimeMillis() }?.second
        fun retainProvider(name: String, match: MusicTrack) {
            val identity = if (name == "youtube") match.copy(id = "yt_${selectedVideoId(match) ?: return}") else match
            providerMatches.put("$name:$recordingKey", (System.currentTimeMillis() + 24 * 60 * 60_000L) to
                identity.copy(audioUrl = "", bitrateKbps = 0))
        }
        val triedVideos = mutableSetOf<String>()
        suspend fun youtubeExact(request: MusicTrack): MusicTrack? {
            val id = selectedVideoId(request) ?: return null
            if (!triedVideos.add(id)) return null
            val resolved = attempt(20_000L) {
                val opus = if (sourcePolicy?.preferOpus == true || quality == AudioQuality.ULTRA_HD_320)
                    attempt(10_000L) { resolveOpus(request, quality, forceRefresh, ceilingBps) }
                        ?.takeIf { it.qualityBadge.contains("Opus", true) && providerAllowed(it) } else null
                opus ?: resolveYouTube(request, quality, forceRefresh, ceilingBps)
            } ?: return null
            if (resolved.id != request.id || !isValidStreamUrl(resolved.audioUrl) ||
                safeVideoIdFromUri(resolved.audioUrl) != id) return null
            // A virtual reference can stay stable after client/signed-URL refresh.
            // Literal failed HTTP URLs must never be replayed as a fresh candidate.
            if (!YouTubeAudioStreamService.isPlaybackUri(resolved.audioUrl) && resolved.audioUrl in rejectedUrls) return null
            // The stable reference can stay identical after its underlying signed URL is refreshed.
            return resolved.copy(isLossless = false, source = "YouTube Music").takeIf(::providerAllowed)
                ?.also { retainProvider("youtube", request) }
        }
        suspend fun jioExact(): MusicTrack? {
            val already = track.takeIf { !forceRefresh && !YouTubeAudioStreamService.isYouTubeAudio(it.id, it.audioUrl) && !archiveTransport(it.audioUrl) && usable(it) }
            if (already != null) return already
            val catalogId = track.id.takeIf { it.startsWith("online_") }
                ?: matchedProvider("jio")?.id?.takeIf { it.startsWith("online_") } ?: return null
            return attempt(8_000L) { fetchById(catalogId.removePrefix("online_"), forceRefresh) }
                ?.takeIf { it.id == catalogId && matches(track, it) && usable(it) }
        }
        val title = extractCleanTitle(track.title, track.artist)
        val artist = extractCleanArtist(track.artist)
        val primary = RecordingIdentity.artistNames(track).firstOrNull().orEmpty()
        val queries = listOf("$title $artist".trim(), "$title $primary".trim(), title)
            .filter(String::isNotBlank).distinctBy(CatalogNames::normalize).take(3)
        suspend fun standardFallback(): MusicTrack? = attempt(12_000L) {
            matchedProvider("jio")?.let { previous ->
                if (!forceRefresh && usable(previous)) return@attempt previous
                val updated = attempt(3_000L) { fetchById(previous.id.removePrefix("online_"), forceRefresh) }
                if (updated != null && matches(track, updated) && usable(updated)) return@attempt updated
            }
            for (query in queries) {
                val candidates = attempt(5_000L) { searchStandard(query, forceRefresh) }.orEmpty()
                val ranked = withContext(AppDispatchers.CpuBounded) { candidates.mapNotNull { candidate ->
                    RecordingIdentity.fallbackConfidence(track, candidate)?.let { candidate to it }
                }.sortedByDescending { it.second }.take(8).map { it.first } }
                for (candidate in ranked) {
                    if (usable(candidate)) { retainProvider("jio", candidate); return@attempt candidate }
                    if (!candidate.id.startsWith("online_") || candidate.audioUrl.isNotBlank()) continue
                    val detailed = attempt(3_000L) { fetchById(candidate.id.removePrefix("online_"), forceRefresh) }
                    if (detailed != null && matches(track, detailed) && usable(detailed)) { retainProvider("jio", detailed); return@attempt detailed }
                }
            }
            null
        }
        suspend fun youtubeFallback(): MusicTrack? = attempt(24_000L) {
            matchedProvider("youtube")?.let { youtubeExact(it)?.let { resolved -> return@attempt resolved } }
            var extractions = 0
            for (query in queries.take(2)) {
                val candidates = attempt(6_000L) {
                    searchYouTube(query)
                }.orEmpty()
                val ranked = withContext(AppDispatchers.CpuBounded) { candidates.mapNotNull { candidate ->
                    RecordingIdentity.fallbackConfidence(track, candidate)?.let { candidate to it }
                }.sortedByDescending { it.second }.take(8).map { it.first } }
                for (candidate in ranked) {
                    if (selectedVideoId(candidate) in triedVideos) continue
                    if (extractions++ >= 3) return@attempt null
                    youtubeExact(candidate)?.let { return@attempt it }
                }
            }
            null
        }
        suspend fun archive(): MusicTrack? {
            if (quality != AudioQuality.HI_RES_LOSSLESS || ceilingBps != Int.MAX_VALUE ||
                !policy.allowsArchive || currentSourceOnly || track.durationMs <= 0L ||
                title.isBlank() || primary.isBlank()) return null
            val identity = recordingKey
            // Fast cache check: return verified lossless match if already discovered
            val cachedLossless = getCachedLosslessMatch(track)
            if (cachedLossless != null && usable(cachedLossless) && supportsLossless(cachedLossless) && !isLosslessUrlRejected(cachedLossless.audioUrl)) {
                return cachedLossless
            }
            if (synchronized(archiveMisses) { (archiveMisses[identity] ?: 0L) > android.os.SystemClock.elapsedRealtime() }) return null
            var completed = false
            // Initial playback keeps its short deadline. An explicit enhancement
            // can finish the same bounded lookup while the existing stream plays.
            val found = attempt(if (manualUpgrade) MANUAL_LOSSLESS_LOOKUP_TIMEOUT_MS else LOSSLESS_LOOKUP_TIMEOUT_MS) {
                val candidates = searchLossless("$title ${primary.ifBlank { artist }}".trim())
                withContext(AppDispatchers.CpuBounded) { candidates.firstOrNull { usable(it) && matchesInitialLossless(track, it) && supportsLossless(it) && !isLosslessUrlRejected(it.audioUrl) } }
                    .also { completed = true }
            }
            // A timeout/partial response is not proof of absence. Only verified
            // results are retained for later selections or explicit enhancements.
            if (found != null) {
                cacheVerifiedLosslessMatch(track, found)
                synchronized(archiveAttempts) {
                    archiveAttempts[identity] = android.os.SystemClock.elapsedRealtime() + 180_000L
                    while (archiveAttempts.size > 128) archiveAttempts.remove(archiveAttempts.keys.first())
                }
            }
            if (completed && found == null) synchronized(archiveMisses) {
                // A first-page startup probe is not an exhaustive recording search.
                // Keep brief TTL so a later explicit enhancement can retry.
                archiveMisses[identity] = android.os.SystemClock.elapsedRealtime() + 10_000L
                while (archiveMisses.size > 128) archiveMisses.remove(archiveMisses.keys.first())
            }
            return found
        }
        val resolved = withTimeoutOrNull(65_000L) {
            suspend fun boundedJio(): MusicTrack? {
                if (preferYouTube && sourcePolicy == null) return null
                if (!forceRefresh) {
                    val saved = synchronized(cache) { cache[key]
                        ?.takeIf { it.expiresAt > System.currentTimeMillis() }?.track }
                    if (saved != null && !YouTubeAudioStreamService.isYouTubeAudio(saved.id, saved.audioUrl) &&
                        !archiveTransport(saved.audioUrl) && usable(saved)) return saved
                }
                // Each provider owns its bounded budget. Earlier Archive/YouTube
                // work cannot spend the fallback provider's resolution window.
                return attempt(6_000L) { jioExact() ?: standardFallback() }
            }
            if (currentSourceOnly) {
                if (track.source.startsWith("Streaming Addon:")) policy.playbackSources
                    .filter { it.kind == PlaybackSourceKind.ADDON &&
                        com.example.data.remote.StreamingAddons.fromSource(track.audioUrl, it.id) }.firstNotNullOfOrNull { source ->
                        attempt(10_000L) { resolveAddon(source, track, policy, rejectedUrls + listOfNotNull(failedUrl)) }
                    }
                else if (archiveTransport(track.audioUrl)) null
                else if (youtubeSelection) youtubeExact(track) else attempt(6_000L) { jioExact() ?: standardFallback() }
            } else {
                val providers = preferredSourceId?.let { id -> policy.playbackSources.firstOrNull { it.id == id } }
                    ?.let(::listOf) ?: if (preferredSourceId != null) policy.playbackSources else policy.resolutionSources
                suspend fun walk(ordered: List<PlaybackSource>): MusicTrack? {
                  for (provider in ordered) {
                    coroutineContext.ensureActive()
                    val answer = when (provider.kind) {
                        PlaybackSourceKind.YOUTUBE -> if (selectedVideoId(track) != null) youtubeExact(track) else youtubeFallback()
                        PlaybackSourceKind.JIOSAAVN -> boundedJio()
                        PlaybackSourceKind.INTERNET_ARCHIVE -> if (selectedArchive) {
                            val direct = attempt(12_000L) { verifyArchive(track.copy(audioUrl = archiveUrl,
                                isLossless = true, source = "INTERNET_ARCHIVE")) }
                            direct?.takeIf { it.audioUrl == archiveUrl && usable(it) && supportsLossless(it) }
                        } else archive()
                        PlaybackSourceKind.ADDON -> attempt(10_000L) {
                            resolveAddon(provider, track, policy, rejectedUrls + listOfNotNull(failedUrl))
                        }
                    }
                    if (answer != null) return answer
                  }
                  return null
                }
                // Reserve a real fallback budget: several unavailable custom
                // servers must not consume YouTube's entire extraction window.
                val youtubeLast = providers.lastOrNull()?.kind == PlaybackSourceKind.YOUTUBE
                val beforeYoutube = if (youtubeLast) providers.dropLast(1) else providers
                val answer = withTimeoutOrNull(35_000L) { walk(beforeYoutube) }
                answer ?: if (youtubeLast) walk(providers.takeLast(1)) else null
            }
        } ?: throw TrackUnavailableException(track.title)
        coroutineContext.ensureActive()
        val result = withStream(track, capped(resolved))
        synchronized(cache) {
            val youtube = YouTubeAudioStreamService.isYouTubeAudio(result.id, result.audioUrl)
            cache[key] = CachedStream(result, System.currentTimeMillis() + if (youtube) 24 * 60 * 60_000L else 300_000L)
            while (cache.size > 128) cache.remove(cache.keys.first())
        }
        result
    }

    suspend fun resolveYouTubeFallback(track: MusicTrack, quality: AudioQuality = AudioQuality.ULTRA_HD_320): MusicTrack? = try {
        resolve(track, quality, forceRefresh = true, failedUrl = track.audioUrl, preferYouTube = true)
    } catch (e: CancellationException) { throw e }
    catch (_: TrackUnavailableException) { null }

    suspend fun discoverLosslessBackground(
        track: MusicTrack,
        excludedUrls: Set<String> = emptySet(),
        allowed: () -> Boolean = { true }
    ): MusicTrack? = withContext(AppDispatchers.Io) {
        val cached = getCachedLosslessMatch(track)
        if (cached != null && cached.audioUrl !in excludedUrls && !isLosslessUrlRejected(cached.audioUrl) && supportsLossless(cached)) {
            return@withContext cached
        }
        val allExcluded = (excludedUrls + getActiveRejectedUrls()).toSet()
        val result = com.example.data.remote.InternetArchiveApiService.findLosslessRecording(
            track = track,
            excludedUrls = allExcluded,
            allowed = allowed
        )
        val matches = withContext(AppDispatchers.CpuBounded) {
            result.tracks.mapNotNull { candidate ->
                if (candidate.audioUrl in allExcluded ||
                    isLosslessUrlRejected(candidate.audioUrl) ||
                    !matchesLosslessUpgrade(track, candidate) ||
                    !supportsLossless(candidate)) {
                    if (candidate.audioUrl !in allExcluded && !matchesLosslessUpgrade(track, candidate)) {
                        com.example.playback.LosslessDiagnostics.record(track.id,
                            com.example.playback.LosslessDiagnostics.Reason.SOURCE_VERIFICATION_FAILED, "candidate-verification")
                    }
                    null
                } else RecordingIdentity.losslessConfidence(track, candidate)?.let { candidate to it }
            }.sortedByDescending { it.second }.map { it.first }
        }
        val match = matches.firstOrNull()
        if (match != null) {
            cacheVerifiedLosslessMatch(track, match)
        } else {
            val reason = when {
                result.failure != null -> result.failure
                result.tracks.isNotEmpty() -> com.example.playback.LosslessDiagnostics.Reason.NO_MATCH
                else -> com.example.playback.LosslessDiagnostics.Reason.NO_RESULTS
            }
            com.example.playback.LosslessDiagnostics.record(track.id, reason, "discovery")
        }
        match
    }

    private fun withStream(track: MusicTrack, stream: MusicTrack) = track.copy(audioUrl = stream.audioUrl,
        bitrateKbps = stream.bitrateKbps, qualityBadge = stream.qualityBadge,
        isLossless = stream.isLossless, source = stream.source,
        primaryArtists = track.primaryArtists.ifEmpty { stream.primaryArtists })

    companion object {
        const val LOSSLESS_LOOKUP_TIMEOUT_MS = 2_500L
        private const val MANUAL_LOSSLESS_LOOKUP_TIMEOUT_MS = 20_000L
        private val verifiedLosslessCache = LinkedHashMap<String, Pair<MusicTrack, Long>>(128, 0.75f, true)
        private val rejectedLosslessUrls = LinkedHashMap<String, Long>(256, 0.75f, true)

        fun isLosslessUrlRejected(url: String): Boolean = synchronized(rejectedLosslessUrls) {
            val expiresAt = rejectedLosslessUrls[url] ?: return false
            if (expiresAt <= android.os.SystemClock.elapsedRealtime()) {
                rejectedLosslessUrls.remove(url)
                false
            } else {
                true
            }
        }

        fun rejectLosslessUrl(url: String, ttlMs: Long) = synchronized(rejectedLosslessUrls) {
            rejectedLosslessUrls[url] = android.os.SystemClock.elapsedRealtime() + ttlMs
            while (rejectedLosslessUrls.size > 256) rejectedLosslessUrls.remove(rejectedLosslessUrls.keys.first())
        }

        fun getActiveRejectedUrls(): Set<String> = synchronized(rejectedLosslessUrls) {
            val now = android.os.SystemClock.elapsedRealtime()
            rejectedLosslessUrls.entries.filter { it.value > now }.map { it.key }.toSet()
        }

        fun invalidateLosslessMatch(track: MusicTrack, candidateUrl: String? = null) = synchronized(verifiedLosslessCache) {
            val key = RecordingIdentity.key(track)
            val entry = verifiedLosslessCache[key]
            if (entry != null && (candidateUrl == null || entry.first.audioUrl == candidateUrl)) {
                verifiedLosslessCache.remove(key)
            }
        }

        fun getCachedLosslessMatch(track: MusicTrack): MusicTrack? = synchronized(verifiedLosslessCache) {
            val key = RecordingIdentity.key(track)
            val entry = verifiedLosslessCache[key] ?: return null
            if (entry.second < android.os.SystemClock.elapsedRealtime()) {
                verifiedLosslessCache.remove(key)
                null
            } else if (isLosslessUrlRejected(entry.first.audioUrl)) {
                verifiedLosslessCache.remove(key)
                null
            } else {
                entry.first
            }
        }

        fun cacheVerifiedLosslessMatch(track: MusicTrack, lossless: MusicTrack) = synchronized(verifiedLosslessCache) {
            val key = RecordingIdentity.key(track)
            verifiedLosslessCache[key] = Pair(lossless, android.os.SystemClock.elapsedRealtime() + 600_000L)
            while (verifiedLosslessCache.size > 128) verifiedLosslessCache.remove(verifiedLosslessCache.keys.first())
        }

        fun clearVerifiedLosslessCache() = synchronized(verifiedLosslessCache) {
            verifiedLosslessCache.clear()
            synchronized(rejectedLosslessUrls) {
                rejectedLosslessUrls.clear()
            }
        }

        private val presentation = Regex("(?i)\\s*[\\[(](?:official\\s+)?(?:music\\s+)?(?:video|audio|lyric\\s+video|lyrics|visualizer|hd|4k|full\\s+song|from\\s+\"[^\"]+\")[)\\]]")
        private val presentationSuffix = Regex("(?i)\\s*[-|•]\\s*(?:official\\s+)?(?:music\\s+)?(?:video|audio|lyric\\s+video|lyrics|visualizer|hd|4k)\\b(?=\\s*(?:$|[-|•\\[(]))")
        private val officialSuffix = Regex("(?i)\\s+(?:official\\s+(?:music\\s+)?(?:audio|video)|lyric\\s+video)\\b(?:\\s+(?:hd|4k)\\b)?")
        private val topicSuffix = Regex("(?i)\\s*-\\s*topic\\b")
        private val unknownArtists = Regex("(?i)\\b(?:various\\s+artists|online\\s+artist)\\b")
        private val unknownArtistNames = setOf("", "unknown", "unknown artist", "various", "various artists", "internet archive")

        internal fun matchesInitialLossless(requested: MusicTrack, candidate: MusicTrack): Boolean =
            candidate.isLossless && candidate.audioUrl.startsWith("https://") && archiveTransport(candidate.audioUrl) &&
                uriPath(candidate.audioUrl).let { path -> path.endsWith(".flac") || path.endsWith(".wav") || path.endsWith(".m4a") } &&
                RecordingIdentity.losslessConfidence(requested, candidate) != null

        internal fun matchesLosslessUpgrade(requested: MusicTrack, candidate: MusicTrack): Boolean =
            candidate.isLossless && candidate.audioUrl.startsWith("https://") && archiveTransport(candidate.audioUrl) &&
                uriPath(candidate.audioUrl).let { it.endsWith(".flac") || it.endsWith(".wav") || it.endsWith(".m4a") } &&
                RecordingIdentity.losslessConfidence(requested, candidate) != null

        private fun uriPath(url: String): String = runCatching { Uri.parse(url).path.orEmpty().lowercase(java.util.Locale.ROOT) }.getOrDefault("")

        private fun uriHost(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return runCatching { Uri.parse(url)?.host }.getOrNull() ?: run {
                val withoutScheme = url.substringAfter("://", "")
                if (withoutScheme.isEmpty()) null
                else withoutScheme.substringBefore('/').substringBefore('?').substringBefore(':')
            }
        }

        private fun uriScheme(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return runCatching { Uri.parse(url)?.scheme }.getOrNull() ?: run {
                val idx = url.indexOf("://")
                if (idx != -1) url.substring(0, idx).lowercase() else null
            }
        }

        private fun safeVideoIdFromUri(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            return if (uri != null) {
                YouTubeAudioStreamService.videoIdFromUri(uri)
            } else {
                val q = url.substringAfter('?', "")
                q.split('&').firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                    ?: if (url.startsWith("https://youtu.be/")) url.substringAfter("youtu.be/").substringBefore('?').substringBefore('/')
                    else null
            }
        }

        private fun selectedVideoId(track: MusicTrack): String? =
            track.id.removePrefix("yt_").takeIf { track.id.startsWith("yt_") && YouTubeAudioStreamService.isVideoId(it) }
                ?: safeVideoIdFromUri(track.audioUrl)

        private fun archiveTransport(url: String): Boolean = uriHost(url)?.let {
            it == "archive.org" || it.endsWith(".archive.org")
        } == true

        internal fun needsResolution(track: MusicTrack): Boolean {
            if (!isValidStreamUrl(track.audioUrl)) return true
            // Downloaded files must continue to work offline. Persisted optional upgrades,
            // however, must not become the initial source on a later catalog selection.
            val scheme = uriScheme(track.audioUrl)
            if (scheme in setOf("file", "content")) return false
            return true // Every remote entry point must apply the currently active quality tier.
        }

        internal fun isValidStreamUrl(url: String?): Boolean {
            if (url.isNullOrBlank() || url.contains("placeholder", true) || url.startsWith("yt_")) return false
            val scheme = uriScheme(url)
            val host = uriHost(url)
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            return when (scheme) {
                "xtreme-youtube", "xtreme-sabr" -> (uri?.let { YouTubeAudioStreamService.videoIdFromUri(it) != null } ?: false)
                    || safeVideoIdFromUri(url) != null
                "https", "http" -> !host.isNullOrBlank() &&
                    (!(host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be") ||
                        YouTubeAudioStreamService.isMediaUrl(url))
                "file", "content" -> (uri?.path?.isNotBlank() == true) || !url.substringAfter("://", "").isBlank()
                else -> false
            }
        }

        internal fun extractCleanTitle(title: String, artist: String = ""): String {
            var cleaned = CatalogNames.clean(title)
            if (cleaned.contains(" - ")) {
                val artistKey = CatalogNames.normalize(extractCleanArtist(artist))
                val prefix = cleaned.substringBefore(" - ")
                if (artistKey !in unknownArtistNames && CatalogNames.normalize(extractCleanArtist(prefix)) == artistKey)
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
