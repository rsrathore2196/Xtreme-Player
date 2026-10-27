package com.example.data.repository

import com.example.performance.AppDispatchers

import android.net.Uri
import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeMusicApiService
import com.example.playback.AudioQuality
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackSourcePolicy
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.coroutineContext

class TrackUnavailableException(title: String, details: String? = null, val temporary: Boolean = true) :
    IOException(details ?: "No matching playable stream is available for '$title'.")

/** Quality-tier routing; every remote substitution must verify the same recording. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StreamResolver(
    private val searchStandard: suspend (String, Boolean) -> List<MusicTrack> = { query, refresh ->
        OnlineMusicApiService.searchSongs(query, limit = 20, forceRefresh = refresh)
    },
    private val searchLossless: (suspend (String) -> List<MusicTrack>)? = null,
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
    private val verifyArchive: suspend (MusicTrack) -> MusicTrack? = { track ->
        com.example.data.remote.InternetArchiveApiService.verifyDirectRecording(track) { true }
    },
    private val resolveAddon: suspend (PlaybackSource, MusicTrack, PlaybackSourcePolicy, Set<String>) -> MusicTrack? = { _, _, _, _ -> null },
    private val ioDispatcher: CoroutineDispatcher = AppDispatchers.Io,
    private val findLossless: suspend (MusicTrack, Set<String>, () -> Boolean) -> InternetArchiveApiService.LosslessSearchPage =
        { request, excluded, allowed -> InternetArchiveApiService.findLosslessRecording(request, excluded, allowed) }
) {
    private data class CachedStream(val track: MusicTrack, val expiresAt: Long)
    private data class StreamKey(val id: String, val quality: AudioQuality, val recommendation: Boolean, val cap: Int,
        val recording: String, val policy: String?, val selectedArchiveUrl: String? = null,
        val preferredSource: String? = null, val currentOnly: Boolean = false, val manualUpgrade: Boolean = false)
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
    ): MusicTrack = withContext(ioDispatcher) {
        val ceilingBps = minOf(maxBitrateBps, if (quality == AudioQuality.HI_RES_LOSSLESS) Int.MAX_VALUE else quality.kbps * 1000)
        val policy = sourcePolicy ?: PlaybackSourcePolicy(AudioQualityCeiling.entries.lastOrNull { it.maxBitrateBps <= ceilingBps } ?: AudioQualityCeiling.DATA_SAVER)
        var failureReason: PlaybackDiagnostics.Reason? = null
        var failureStage = PlaybackDiagnostics.Stage.RESOLUTION
        val attemptFailures = mutableListOf<PlaybackDiagnostics.Reason>()
        fun unavailable() = TrackUnavailableException(track.title, PlaybackDiagnostics.unavailable(track.id, track.title, policy),
            temporary = attemptFailures.isEmpty() || attemptFailures.any { it in setOf(PlaybackDiagnostics.Reason.NETWORK,
                PlaybackDiagnostics.Reason.TIMEOUT, PlaybackDiagnostics.Reason.INVALID_URL, PlaybackDiagnostics.Reason.POLICY,
                PlaybackDiagnostics.Reason.INCOMPLETE_METADATA) })
        val recordingKey = withContext(AppDispatchers.CpuBounded) { RecordingIdentity.key(track) }
        if (manualUpgrade) synchronized(archiveMisses) { archiveMisses.remove(recordingKey) }
        val archiveUrl = com.example.data.remote.InternetArchiveApiService.originalFileUrl(track)
            ?: track.audioUrl.takeIf { archiveTransport(it) }
        val selectedArchive = archiveUrl != null
        // Cross-provider playback retains its catalog ID. Revalidate a known
        // Archive file before reusing that identity's cached lossy fallback.
        val key = StreamKey(track.id, quality, track.recommendationOnly, ceilingBps, recordingKey, policy.key,
            archiveUrl.takeIf { selectedArchive }, preferredSourceId, currentSourceOnly, manualUpgrade)
        fun capped(candidate: MusicTrack): MusicTrack {
            val host = uriHost(candidate.audioUrl).orEmpty()
            if (candidate.source.startsWith("Streaming Addon:")) return candidate
            if (host != "saavncdn.com" && !host.endsWith(".saavncdn.com")) return candidate
            val bitrate = com.example.playback.AudioQualityCeiling.saavnKbps(quality.kbps,
                candidate.bitrateKbps.takeIf { it in 1..320 } ?: 320, ceilingBps)
            return candidate.copy(audioUrl = OnlineMusicApiService.formatUrlForQuality(candidate.audioUrl, bitrate.toString()),
                bitrateKbps = bitrate, qualityBadge = YouTubeAudioStreamService.displayQuality("AAC", bitrate), source = "JIOSAAVN", isLossless = false)
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
                    candidate.bitrateKbps.toLong() * 1000 <= ceilingBps) && policy.acceptsRemoteTrack(candidate)
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
            PlaybackDiagnostics.record(track.id, policy, null, PlaybackDiagnostics.Stage.VALIDATION, PlaybackDiagnostics.Reason.POLICY)
            throw unavailable()
        }
        if (policy.playbackSources.isEmpty()) {
            PlaybackDiagnostics.record(track.id, policy, null, PlaybackDiagnostics.Stage.VALIDATION, PlaybackDiagnostics.Reason.POLICY)
            throw unavailable()
        }
        synchronized(cache) { cache.entries.removeAll { it.key.policy != policy.key && !providerAllowed(it.value.track) } }
        if (forceRefresh || preferYouTube) synchronized(cache) { cache.keys.removeAll { it.id == track.id } }
        val cached = if (forceRefresh || preferYouTube) null else synchronized(cache) { cache[key] }
        val cachedSourceId = cached?.track?.let {
            com.example.playback.SourceTransitionOrigin.sourceId(it.id, Uri.parse(it.audioUrl), policy)
        }
        fun cachedFor(sourceId: String?): MusicTrack? {
            if (sourceId == null || sourceId != cachedSourceId || cached == null || cached.expiresAt <= System.currentTimeMillis()) return null
            val candidate = capped(cached.track)
            val addon = candidate.source.startsWith("Streaming Addon:") &&
                com.example.data.remote.StreamingAddons.isVerified(candidate.audioUrl, policy)
            if ((!providerAllowed(candidate) && !addon) || !policy.acceptsRemoteTrack(candidate) ||
                candidate.audioUrl == failedUrl || candidate.audioUrl in rejectedUrls || isLosslessUrlRejected(candidate.audioUrl) ||
                selectedArchive && (candidate.audioUrl != archiveUrl || !candidate.isLossless)) return null
            if (YouTubeAudioStreamService.isYouTubeAudio(candidate.id, candidate.audioUrl) &&
                !YouTubeAudioStreamService.canReuseStream(candidate.audioUrl, ceilingBps)) return null
            return candidate
        }
        if (!manualUpgrade) {
            val target = if (currentSourceOnly) com.example.playback.SourceTransitionOrigin.sourceId(track.id, Uri.parse(track.audioUrl), policy)
                else preferredSourceId ?: policy.resolutionSourcesFor(track.id).firstOrNull()?.id
            cachedFor(target)?.let { return@withContext withStream(track, it) }
        }
        val rejected = failedUrl ?: track.audioUrl.takeIf { forceRefresh }
        if (forceRefresh && failedUrl != null && youtubeSelection) selectedVideoId(track)?.let {
            // A quality change/timeout is not a CDN client refusal. The loader
            // records actual 401/403/410 refusals; only renew this representation.
            YouTubeAudioStreamService.invalidate(it)
        }
        suspend fun <T> attempt(timeoutMs: Long, block: suspend () -> T): T? = try {
            val completed = withTimeoutOrNull(timeoutMs) { Result.success(block()) }
            if (completed == null) { failureReason = PlaybackDiagnostics.Reason.TIMEOUT; null } else completed.getOrThrow()
        } catch (e: TimeoutCancellationException) { coroutineContext.ensureActive(); failureReason = PlaybackDiagnostics.Reason.TIMEOUT; null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            failureReason = PlaybackDiagnostics.reason(e)
            failureStage = (e as? PlaybackDiagnostics.Failure)?.stage ?: failureStage
            null
        }
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
            // Native AUTO already prefers WebM/Opus. A separate forced-Opus
            // extraction used to consume the fallback's entire time budget.
            failureStage = PlaybackDiagnostics.Stage.EXTRACTION
            failureReason = PlaybackDiagnostics.Reason.NO_FORMAT
            val resolved = attempt(YouTubeAudioStreamService.RESOLUTION_TIMEOUT_MS + 5_000L) {
                resolveYouTube(request, quality, forceRefresh, ceilingBps)
            } ?: return null
            if (resolved.id != request.id || safeVideoIdFromUri(resolved.audioUrl) != id) {
                failureReason = PlaybackDiagnostics.Reason.NO_MATCH; failureStage = PlaybackDiagnostics.Stage.VALIDATION; return null
            }
            if (!isValidStreamUrl(resolved.audioUrl)) {
                failureReason = PlaybackDiagnostics.Reason.INVALID_URL; failureStage = PlaybackDiagnostics.Stage.VALIDATION; return null
            }
            // A virtual reference can stay stable after client/signed-URL refresh.
            // Literal failed HTTP URLs must never be replayed as a fresh candidate.
            if (!YouTubeAudioStreamService.isPlaybackUri(resolved.audioUrl) && resolved.audioUrl in rejectedUrls) return null
            // The stable reference can stay identical after its underlying signed URL is refreshed.
            return resolved.copy(isLossless = false, source = "YouTube Music").takeIf {
                providerAllowed(it).also { allowed -> if (!allowed) {
                    failureReason = PlaybackDiagnostics.Reason.POLICY; failureStage = PlaybackDiagnostics.Stage.VALIDATION
                } }
            }
                ?.also { retainProvider("youtube", request) }
        }
        val detailLookups = mutableMapOf<String, MusicTrack?>()
        suspend fun detail(id: String, timeoutMs: Long): MusicTrack? {
            if (detailLookups.containsKey(id)) return detailLookups[id]
            val result = attempt(timeoutMs) { fetchById(id, forceRefresh) }
            detailLookups[id] = result
            return result
        }
        suspend fun jioExact(): MusicTrack? {
            val already = track.takeIf { !forceRefresh && !YouTubeAudioStreamService.isYouTubeAudio(it.id, it.audioUrl) && !archiveTransport(it.audioUrl) && usable(it) }
            if (already != null) return already
            val catalogId = track.id.takeIf { it.startsWith("online_") }
                ?: matchedProvider("jio")?.id?.takeIf { it.startsWith("online_") } ?: return null
            val detailed = detail(catalogId.removePrefix("online_"), 8_000L) ?: return null
            if (!isValidStreamUrl(detailed.audioUrl) && detailed.audioUrl.isNotBlank()) {
                failureReason = PlaybackDiagnostics.Reason.INVALID_URL
                failureStage = PlaybackDiagnostics.Stage.VALIDATION
            }
            return detailed.takeIf { it.id == catalogId && usable(it) && withContext(AppDispatchers.CpuBounded) { matches(track, it) } }
        }
        val title = extractCleanTitle(track.title, track.artist)
        val artist = extractCleanArtist(track.artist)
        val primary = RecordingIdentity.artistNames(track).firstOrNull().orEmpty()
        val queries = listOf("$title $artist".trim(), "$title $primary".trim(), title)
            .filter(String::isNotBlank).distinctBy(CatalogNames::normalize).take(3)
        suspend fun standardFallback(): MusicTrack? = attempt(12_000L) {
            matchedProvider("jio")?.let { previous ->
                if (!forceRefresh && usable(previous)) return@attempt previous
                val updated = detail(previous.id.removePrefix("online_"), 3_000L)
                if (updated != null && matches(track, updated) && usable(updated)) return@attempt updated
            }
            for (query in queries) {
                val candidates = attempt(5_000L) { searchStandard(query, forceRefresh) }.orEmpty()
                val ranked = withContext(AppDispatchers.CpuBounded) { candidates.mapNotNull { candidate ->
                    RecordingIdentity.providerConfidence(track, candidate)?.let { candidate to it }
                }.sortedByDescending { it.second }.take(8).map { it.first } }
                for (candidate in ranked) {
                    if (usable(candidate)) { retainProvider("jio", candidate); return@attempt candidate }
                    if (!candidate.id.startsWith("online_") || candidate.audioUrl.isNotBlank()) continue
                    val detailed = detail(candidate.id.removePrefix("online_"), 3_000L)
                    if (detailed != null && usable(detailed) && withContext(AppDispatchers.CpuBounded) { matches(track, detailed) }) { retainProvider("jio", detailed); return@attempt detailed }
                }
            }
            null
        }
        // All metadata queries/candidates share one provider window, rather than
        // granting every candidate a new extraction-length timeout.
        suspend fun youtubeFallback(): MusicTrack? = attempt(YouTubeAudioStreamService.RESOLUTION_TIMEOUT_MS + 11_000L) {
            matchedProvider("youtube")?.let { youtubeExact(it)?.let { resolved -> return@attempt resolved } }
            var extractions = 0
            for (query in queries.take(2)) {
                val candidates = attempt(6_000L) {
                    searchYouTube(query)
                }.orEmpty()
                val ranked = withContext(AppDispatchers.CpuBounded) { candidates.mapNotNull { candidate ->
                    RecordingIdentity.providerConfidence(track, candidate)?.let { candidate to it }
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
                !policy.allowsArchive || currentSourceOnly || title.isBlank() ||
                (RecordingIdentity.isrc(track.isrc) == null && (track.durationMs <= 0L || primary.isBlank()))) return null
            val identity = recordingKey
            // Fast cache check: return verified lossless match if already discovered
            val cachedLossless = getCachedLosslessMatch(track)
            if (cachedLossless != null && usable(cachedLossless) && supportsLossless(cachedLossless) && !isLosslessUrlRejected(cachedLossless.audioUrl)) {
                return cachedLossless
            }
            if (synchronized(archiveMisses) { (archiveMisses[identity] ?: 0L) > android.os.SystemClock.elapsedRealtime() }) return null
            var completed = false
            var page: InternetArchiveApiService.LosslessSearchPage? = null
            failureStage = PlaybackDiagnostics.Stage.DISCOVERY
            // Initial playback keeps its short deadline. An explicit enhancement
            // can finish the same bounded lookup while the existing stream plays.
            val found = attempt(if (manualUpgrade) MANUAL_LOSSLESS_LOOKUP_TIMEOUT_MS else LOSSLESS_LOOKUP_TIMEOUT_MS) {
                val candidates = if (searchLossless != null) searchLossless.invoke("$title ${primary.ifBlank { artist }}".trim())
                    else findLossless(track, rejectedUrls + listOfNotNull(failedUrl)) { policy.allowsArchive }
                        .also { page = it }.tracks
                withContext(AppDispatchers.CpuBounded) { candidates.firstOrNull { usable(it) && matchesInitialLossless(track, it) && supportsLossless(it) && !isLosslessUrlRejected(it.audioUrl) } }
                    .also { completed = page?.let { it.isSuccessful && it.exhaustive } ?: true }
            }
            if (found == null) page?.failure?.let { reason ->
                com.example.playback.LosslessDiagnostics.record(track.id, reason, "initial-discovery")
                failureReason = when (reason) {
                    com.example.playback.LosslessDiagnostics.Reason.NETWORK_TIMEOUT,
                    com.example.playback.LosslessDiagnostics.Reason.LOOKUP_TIMEOUT -> PlaybackDiagnostics.Reason.TIMEOUT
                    com.example.playback.LosslessDiagnostics.Reason.METADATA_INCOMPLETE -> PlaybackDiagnostics.Reason.INCOMPLETE_METADATA
                    com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED,
                    com.example.playback.LosslessDiagnostics.Reason.UNAUTHORIZED -> PlaybackDiagnostics.Reason.POLICY
                    com.example.playback.LosslessDiagnostics.Reason.DECODER_UNSUPPORTED -> PlaybackDiagnostics.Reason.DECODER
                    else -> PlaybackDiagnostics.Reason.NO_MATCH
                }
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
        val resolved = withTimeoutOrNull(PLAYBACK_RESOLUTION_TIMEOUT_MS) {
            suspend fun boundedJio(): MusicTrack? {
                if (preferYouTube && sourcePolicy == null) return null
                cachedFor(policy.sourceId(PlaybackSourceKind.JIOSAAVN))?.let { return it }
                // Each provider owns its bounded budget. Earlier Archive/YouTube
                // work cannot spend the fallback provider's resolution window.
                return attempt(6_000L) { jioExact() ?: standardFallback() }
            }
            if (currentSourceOnly) {
                if (track.source.startsWith("Streaming Addon:")) policy.playbackSources
                    .filter { it.kind == PlaybackSourceKind.ADDON &&
                        com.example.data.remote.StreamingAddons.fromSource(track.audioUrl, it.id) }.firstNotNullOfOrNull { source ->
                        attempt(16_000L) { resolveAddon(source, track, policy, rejectedUrls + listOfNotNull(failedUrl)) }
                    }
                else if (archiveTransport(track.audioUrl)) null
                else if (youtubeSelection && policy.allows(PlaybackSourceKind.YOUTUBE)) youtubeExact(track)
                else if (!youtubeSelection && policy.allows(PlaybackSourceKind.JIOSAAVN)) attempt(6_000L) { jioExact() ?: standardFallback() }
                else null
            } else {
                val currentProvider = com.example.playback.SourceTransitionOrigin.sourceId(track.id,
                    android.net.Uri.parse(track.audioUrl), policy)
                val providers = if (preferredSourceId != null) policy.playbackSources.filter { it.id == preferredSourceId }
                    else if (manualUpgrade && currentProvider != null)
                        policy.upgradeSources.takeWhile { it.id != currentProvider }
                    else policy.resolutionSourcesFor(track.id)
                suspend fun walk(ordered: List<PlaybackSource>): MusicTrack? {
                  for (provider in ordered) {
                    coroutineContext.ensureActive()
                    failureReason = null
                    failureStage = PlaybackDiagnostics.Stage.RESOLUTION
                    val answer = cachedFor(provider.id) ?: try { when (provider.kind) {
                        PlaybackSourceKind.YOUTUBE -> if (selectedVideoId(track) != null) youtubeExact(track) else youtubeFallback()
                        PlaybackSourceKind.JIOSAAVN -> boundedJio()
                        PlaybackSourceKind.INTERNET_ARCHIVE -> if (selectedArchive) {
                            val direct = attempt(if (manualUpgrade) 12_000L else 6_000L) { verifyArchive(track.copy(audioUrl = archiveUrl,
                                isLossless = true, source = "INTERNET_ARCHIVE")) }
                            direct?.takeIf { it.audioUrl == archiveUrl && usable(it) && supportsLossless(it) }
                        } else archive()
                        PlaybackSourceKind.ADDON -> attempt(16_000L) {
                            resolveAddon(provider, track, policy, rejectedUrls + listOfNotNull(failedUrl))
                        }
                    } } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failureReason = PlaybackDiagnostics.reason(error); null }
                    PlaybackDiagnostics.record(track.id, policy, provider.kind, failureStage,
                        if (answer != null) PlaybackDiagnostics.Reason.SUCCESS else failureReason ?: PlaybackDiagnostics.Reason.NO_MATCH, provider.id)
                    if (answer == null) attemptFailures.add(failureReason ?: PlaybackDiagnostics.Reason.NO_MATCH)
                    if (answer != null) return answer
                  }
                  return null
                }
                // Reserve a real fallback budget: several unavailable custom
                // servers must not consume YouTube's entire extraction window.
                val custom = providers.takeWhile { it.kind == PlaybackSourceKind.ADDON }
                // One full add-on lookup (15s internally), not 30s of serial
                // failed servers before native extraction even starts. Explicit
                // upgrades retain a larger window while existing audio plays.
                val answer = withTimeoutOrNull(if (manualUpgrade) 30_000L else ADDON_STARTUP_TIMEOUT_MS) { walk(custom) }
                // Native extraction owns its full deadline even when YouTube is
                // first. A timed-out provider must not cancel the later fallbacks.
                answer ?: walk(providers.drop(custom.size))
            }
        } ?: track.takeIf { manualUpgrade && preferredSourceId == null && providerAllowed(it) &&
            policy.acceptsRemoteTrack(it) } ?: run {
                if (currentSourceOnly || preferredSourceId != null && policy.playbackSources.none { it.id == preferredSourceId })
                    PlaybackDiagnostics.record(track.id, policy, null, failureStage, failureReason ?: PlaybackDiagnostics.Reason.POLICY)
                throw unavailable()
            }
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
        // 16s add-on group + 2.5s Archive + 6s Jio + the native 40s
        // extraction deadline, with handoff slack; never the former 105s.
        const val PLAYBACK_RESOLUTION_TIMEOUT_MS = 70_000L
        const val ADDON_STARTUP_TIMEOUT_MS = 16_000L
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

        private fun safeVideoIdFromUri(url: String?): String? = url?.let { YouTubeAudioStreamService.videoIdFromReference("", it) }

        private fun selectedVideoId(track: MusicTrack): String? =
            YouTubeAudioStreamService.videoIdFromReference(track.id, track.audioUrl)

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
            RecordingIdentity.providerConfidence(requested, candidate) != null
    }
}
