package com.example.data.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.example.playback.AudioQuality
import com.example.service.SabrSessionRegistry
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.requireAllowedSabrUrl
import com.metrolist.innertubex.extraction.strategy.AuthenticationPolicy
import com.metrolist.innertubex.extraction.strategy.ClientLifecycle
import com.metrolist.innertubex.extraction.strategy.ClientSelectionMode
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackTransport
import kotlinx.coroutines.flow.Flow
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Media3-facing adapter. InnerTubeX is the only player/cipher/audio extraction implementation. */
@OptIn(ExperimentalSabrApi::class)
object YouTubeAudioStreamService {
    data class AudioStream(
        val url: String, val bitrateKbps: Int, val isHls: Boolean,
        val cacheKey: String, val expiresAt: Long, val headers: Map<String, String>,
        val codec: String, val itag: Int, val contentLength: Long?, val videoId: String,
        val useRangeChunks: Boolean, val rangeChunkSize: Long, val clientId: String,
        val sabrBootstrap: SabrBootstrap? = null
    ) {
        val isSabr: Boolean get() = sabrBootstrap != null
        val qualityBadge: String get() = displayQuality(codec, bitrateKbps)
        override fun toString() = "AudioStream(codec=" + codec + ", bitrateKbps=" + bitrateKbps + ", hls=" + isHls + ")"
    }
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val mediaHttp = http.newBuilder().followRedirects(false).build()
    private val cache = android.util.LruCache<String, AudioStream>(128)
    private val locks = Array(16) { Mutex() }
    private val slots = Semaphore(2)
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var runtime: Runtime? = null
    private val runtimeLock = Any()
    private val mediaHeaders = setOf("user-agent", "accept", "accept-language", "origin", "referer", "authorization", "cookie", "x-goog-visitor-id", "x-youtube-client-name", "x-youtube-client-version")

    private class Runtime {
        private fun client(network: OkHttpClient, streaming: Boolean = false) = HttpClient(OkHttp) {
            engine {
                // Ktor shuts down its OkHttp dispatcher/pool on close. Keep each
                // engine separate from the probe client and from later sessions.
                preconfigured = network.newBuilder().dispatcher(Dispatcher())
                    .connectionPool(ConnectionPool()).build()
            }
            install(HttpTimeout) {
                // SABR responses can remain open while downstream disk buffering applies
                // backpressure. Bound network inactivity, not the song's playback time.
                requestTimeoutMillis = if (streaming) HttpTimeoutConfig.INFINITE_TIMEOUT_MS else 12_000L
                connectTimeoutMillis = 8_000L; socketTimeoutMillis = if (streaming) 15_000L else 10_000L
            }
        }
        private val sabrHttp = client(http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build(), streaming = true)
        private val cipherHttp = client(http)
        private val config = RemotePlayerConfigStore(cipherHttp, PlayerConfigRepository.disabled())
        private val cipher = YouTubeCipherService(cipherHttp, config)
        private data class Engine(val http: HttpClient, val innerTube: InnerTube, val extractor: InnerTubeExtractor)
        private val engines = mutableMapOf<String, Engine>()
        private fun engine(cap: Int, hlsOnly: Boolean?, sabr: Boolean = false): Engine = synchronized(engines) {
            engines.getOrPut(cap.toString() + ":" + hlsOnly + ":" + sabr) {
                val network = http.newBuilder().addInterceptor(YouTubeAudioFormatPolicy()).build()
                val apiHttp = client(network)
                val innerTube = InnerTube(apiHttp)
                Engine(apiHttp, innerTube, InnerTubeExtractor(
                    configParser = YtConfigParserImpl(apiHttp, innerTube, config, cipherService = cipher),
                    // Keep the native strategy: the library enables visitor-backed and
                    // automatic fallback paths by its actual strategy type.
                    cipherService = cipher, innerTube = innerTube))
            }
        }
        fun extractor(cap: Int, hlsOnly: Boolean?, sabr: Boolean = false): InnerTubeExtractor = engine(cap, hlsOnly, sabr).extractor
        fun sabrChunks(bootstrap: SabrBootstrap, startMs: Long): Flow<SabrChunk> =
            SabrAudioStream(sabrHttp, bootstrap, maxRequestsWithoutProgress = 3, initialPlayerTimeMs = startMs).chunks()

        suspend fun searchSongs(query: String, continuation: String?): String {
            val response = engine(320_000, false).innerTube.search(
                client = YouTubeClient.WEB_REMIX, query = query.takeIf { continuation == null },
                params = "EgWKAQIIAWoKEAkQBRAKEAMQBA==".takeIf { continuation == null },
                continuation = continuation, setLogin = false)
            val body = response.bodyAsTextLimited(4 * 1024 * 1024)
            if (response.status.value !in 200..299) throw IOException("YouTube Music search is unavailable")
            return body
        }
        suspend fun close() {
            synchronized(engines) { engines.values.toList().also { engines.clear() } }.forEach {
                it.innerTube.close(); it.http.close()
            }
            try { cipher.dispose() } finally { cipherHttp.close(); sabrHttp.close() }
        }
    }
    private fun runtime(): Runtime = runtime ?: synchronized(runtimeLock) { runtime ?: Runtime().also { runtime = it } }

    /** Import uses the same InnerTubeX session/client stack as audio extraction. */
    internal suspend fun searchSongsResponse(query: String, continuation: String?): String =
        withContext(Dispatchers.IO) { runtime().searchSongs(query, continuation) }

    fun isVideoId(value: String) = videoIdPattern.matches(value)
    fun isMediaUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            (it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com") ||
                (it.host == "youtube.com" || it.host.endsWith(".youtube.com")) &&
                (it.encodedPath == "/videoplayback" || it.encodedPath.startsWith("/manifest/") ||
                    it.encodedPath.startsWith("/api/manifest/")))
    } == true
    fun isYouTubeAudio(trackId: String, url: String) = isPlaybackUri(url) || isMediaUrl(url) ||
        trackId.startsWith("yt_") && (url.isBlank() || url.startsWith("yt_"))
    fun isPlaybackUri(url: String) = url.startsWith("xtreme-youtube://audio/") || url.startsWith("xtreme-sabr://audio/")
    fun videoIdFromUri(uri: Uri): String? = uri.takeIf {
        it.scheme in setOf("xtreme-youtube", "xtreme-sabr") && it.host == "audio"
    }?.pathSegments?.firstOrNull()?.takeIf(::isVideoId)
    private fun isHls(url: String, mime: String?) = mime?.contains("mpegurl", true) == true ||
        url.contains("/hls", true) || url.substringBefore('?').endsWith(".m3u8", true)
    fun displayQuality(codec: String, bitrate: Int): String =
        (codec.takeIf(String::isNotBlank) ?: "HQ Stream") + if (bitrate > 0) " • " + bitrate + " kbps" else ""
    fun displayCodec(mime: String?, codec: String?): String = when {
        codec?.contains("opus", true) == true || mime?.contains("opus", true) == true -> "Opus"
        codec?.contains("vorbis", true) == true || mime?.contains("vorbis", true) == true -> "Vorbis"
        codec?.contains("mp4a", true) == true || mime?.contains("mp4a", true) == true ||
            mime?.substringBefore(';') == "audio/aac" ||
            codec.isNullOrBlank() && mime?.substringBefore(';') == "audio/mp4" -> "AAC"
        else -> codec.orEmpty().take(40)
    }
    fun playbackUri(trackId: String, url: String): String {
        if (isPlaybackUri(url)) return url
        // An exact matching JioSaavn/FLAC fallback keeps its own transport, even if the
        // catalog identity is a YouTube video. Never reroute another provider's URL.
        val address = url.toHttpUrlOrNull()
        val youtubePage = trackId.startsWith("yt_") && address?.let {
            it.host == "youtu.be" || it.host == "youtube.com" || it.host.endsWith(".youtube.com")
        } == true
        if (url.isNotBlank() && !url.startsWith("yt_") && !url.contains("placeholder", true) &&
            !isMediaUrl(url) && !youtubePage) return url
        val resolved = cachedForUrl(url)
        val id = resolved?.videoId?.takeIf(::isVideoId)
            ?: trackId.removePrefix("yt_").takeIf { trackId.startsWith("yt_") && isVideoId(it) }.orEmpty()
        if (!isVideoId(id)) return url
        // A selected HLS audio rendition need not have an .m3u8 suffix.
        val hls = resolved?.isHls ?: isHls(url, null)
        // Queue resumption may hold an empty or expired signed URL; identity still resolves on the loader.
        val itag = resolved?.itag ?: url.toHttpUrlOrNull()?.queryParameter("itag")?.toIntOrNull()
        return Uri.Builder().scheme("xtreme-youtube").authority("audio").appendPath(id)
            .appendPath(if (hls) "stream.m3u8" else "stream.audio")
            .apply {
                itag?.let { appendQueryParameter("itag", it.toString()) }
                resolved?.contentLength?.let { appendQueryParameter("length", it.toString()) }
                if (hls) {
                    // A refreshed HLS session needs a new playlist tracker. The digest
                    // distinguishes sessions without persisting the signed media URL.
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                    appendQueryParameter("session", digest.take(8).joinToString("") { "%02x".format(it) })
                }
            }.build().toString()
    }
    fun cachedForUrl(url: String): AudioStream? = synchronized(cache) { cache.snapshot().values.firstOrNull { it.url == url } }
    fun currentStream(videoId: String, quality: AudioQuality): AudioStream? = synchronized(cache) {
        val prefix = videoId + ":" + minOf(quality.kbps, 320) * 1000 + ":"
        cache.snapshot().entries.lastOrNull { it.key.startsWith(prefix) }?.value
    }
    fun headersFor(url: String): Map<String, String> = synchronized(cache) {
        fun client(address: String): String? = address.toHttpUrlOrNull()?.let { parsed ->
            parsed.queryParameter("c") ?: parsed.pathSegments.let { path ->
                path.indexOf("c").takeIf { it >= 0 }?.let { path.getOrNull(it + 1) }
            }
        }
        val requestedClient = client(url)
        cache.snapshot().values.lastOrNull { it.url == url ||
            requestedClient != null && client(it.url) == requestedClient }?.headers
            ?: mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.youtube.com/")
    }
    private data class Rejection(val clients: Set<String>, val until: Long)
    private val rejected = android.util.LruCache<String, Rejection>(64)
    /** Called only after a real transport rejection, never for a speculative probe timeout. */
    fun reject(videoId: String, url: String? = null, clientId: String? = null) {
        val stream = url?.let(::cachedForUrl) ?: synchronized(cache) {
            cache.snapshot().values.lastOrNull { it.videoId == videoId }
        }
        (clientId ?: stream?.clientId)?.let {
            rejected.put(videoId, Rejection(rejectedClients(videoId) + it,
                android.os.SystemClock.elapsedRealtime() + 90_000L))
        }
        invalidate(videoId)
    }
    private fun rejectedClients(videoId: String): Set<String> = rejected.get(videoId)?.takeIf {
        it.until > android.os.SystemClock.elapsedRealtime()
    }?.clients.orEmpty()

    fun invalidate(videoId: String) = synchronized(cache) {
        cache.snapshot().keys.filter { it.startsWith(videoId + ":") }.forEach(cache::remove)
    }

    suspend fun resolve(videoId: String, quality: AudioQuality, forceRefresh: Boolean = false,
        hlsOnly: Boolean? = null, excludedClient: String? = null): AudioStream = withContext(Dispatchers.IO) {
        require(isVideoId(videoId)) { "Invalid YouTube video identity" }
        val cap = minOf(quality.kbps, 320) * 1000
        val key = videoId + ":" + cap + ":" + hlsOnly
        locks[(("$videoId:$cap").hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (forceRefresh) invalidate(videoId)
            cache.get(key)?.takeIf { it.clientId !in (rejectedClients(videoId) + setOfNotNull(excludedClient)) &&
                it.expiresAt > System.currentTimeMillis() + 15_000L }?.let { return@withLock it }
            slots.withPermit {
                withTimeoutOrNull(35_000L) lookup@{
                    // Prefer a directly playable audio-only format. An HLS-only client can
                    // expose a muxed master which this audio player must reject; that is a
                    // transport miss, not proof the selected recording is unavailable.
                    val transports = if (hlsOnly == true) listOf(true) else listOf(false, true)
                    var failure: Exception? = null
                    for (transport in transports) {
                        val budget = if (hlsOnly == true) 33_000L else if (transport) 8_000L else 10_000L
                        val result = withTimeoutOrNull(budget) transport@{
                            val excluded = rejectedClients(videoId).toMutableSet().apply {
                                excludedClient?.let(::add)
                                if (transport) addAll(PlaybackClientCatalog.manifests.filter {
                                    PlaybackTransport.HLS !in it.transports
                                }.map { it.id })
                            }
                            val extractor = runtime().extractor(cap, transport)
                            val hints = ContentHints(wantVideo = false).withStreamCapabilities(
                                allowHls = transport, allowSabr = false, allowBoundedRange = true)
                            repeat(3) { attempt ->
                                currentCoroutineContext().ensureActive()
                                val extracted = try {
                                    extractor.extract(videoId, hints, excludedClients = excluded, audioQuality = YouTubeAudioFormatPolicy.quality(cap, attempt))
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { failure = e; return@transport null }
                                    ?: return@transport null
                                if (extracted.videoId != videoId || !isMediaUrl(extracted.audioUrl) || extracted.sabrBootstrap != null) {
                                    excluded.add(extracted.profileId)
                                    return@repeat
                                }
                                val hls = isHls(extracted.audioUrl, extracted.mimeType)
                                if (!hls && (extracted.mimeType?.startsWith("audio/") != true ||
                                        extracted.videoUrl != null || extracted.videoWidth != null || extracted.videoHeight != null)) {
                                    excluded.add(extracted.profileId)
                                    return@repeat
                                }
                                val expiry = minOf(extracted.expiresAt?.toEpochMilliseconds() ?: Long.MAX_VALUE,
                                    extracted.audioUrl.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
                                        ?.let { it * 1000L } ?: Long.MAX_VALUE,
                                    System.currentTimeMillis() + 300_000L)
                                val bitrate = extracted.bitrate?.coerceAtLeast(0) ?: 0
                                // Retry native MP4/LOW selection on the same client before excluding it.
                                if (!hls && !YouTubeAudioFormatPolicy.acceptsBitrate(extracted.bitrate, cap)) return@repeat
                                val selectedCodec = displayCodec(extracted.mimeType, extracted.codecs)
                                if (!hls && selectedCodec !in setOf("AAC", "Opus", "Vorbis")) {
                                    excluded.add(extracted.profileId); return@repeat
                                }
                                val headers = extracted.headers.filter { (name, value) ->
                                    name.lowercase() in mediaHeaders && '\r' !in value && '\n' !in value }
                                val verified = if (transport == hls && expiry > System.currentTimeMillis() + 15_000L)
                                    probeAudio(extracted.audioUrl, headers, hls, cap, selectedCodec) else null
                                if (verified != null) {
                                    val codec = verified.codec ?: displayCodec(extracted.mimeType, extracted.codecs)
                                    return@transport AudioStream(verified.url, (verified.bitrate ?: bitrate) / 1000, hls,
                                        "youtube:" + videoId + ":" + extracted.itag + ":" + extracted.contentLengthBytes,
                                        expiry - 5_000L, headers, codec, extracted.itag, extracted.contentLengthBytes, videoId,
                                        !hls && (extracted.requireBoundedRange || extracted.useRangeChunks),
                                        extracted.rangeChunkSizeBytes.coerceIn(64 * 1024L, 1024 * 1024L), extracted.profileId)
                                }
                                // Let InnerTubeX pick a different supported client for this transport.
                                excluded.add(extracted.profileId)
                            }
                            null
                        }
                        if (result != null) {
                            cache.put(key, result)
                            cache.put(videoId + ":" + cap + ":" + result.isHls, result)
                            return@lookup result
                        }
                    }
                    if (hlsOnly != true) {
                        val sabr = lookupSabr(videoId, quality, rejectedClients(videoId) + setOfNotNull(excludedClient))
                        cache.put(key, sabr)
                        cache.put(videoId + ":" + cap + ":false", sabr)
                        return@lookup sabr
                    }
                    throw IOException("YouTube did not provide an accessible compatible audio stream", failure)
                } ?: throw IOException("YouTube audio resolution timed out")
            }
        }
    }


    /** A bounded audio-only fallback, using only the library's anonymous automatic profiles. */
    suspend fun resolveSabr(videoId: String, quality: AudioQuality, excludedClients: Set<String> = emptySet()): AudioStream =
        withContext(Dispatchers.IO) {
            require(isVideoId(videoId)) { "Invalid YouTube video identity" }
            val cap = minOf(quality.kbps, 320) * 1000
            val key = "$videoId:$cap:sabr"
            locks[(("$videoId:$cap").hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                cache.get(key)?.takeIf { it.clientId !in (excludedClients + rejectedClients(videoId)) &&
                    it.expiresAt > System.currentTimeMillis() + 45_000L }?.let { return@withLock it }
                slots.withPermit { lookupSabr(videoId, quality, excludedClients + rejectedClients(videoId)) }
            }
        }

    private suspend fun lookupSabr(videoId: String, quality: AudioQuality, excludedClients: Set<String>): AudioStream {
        val cap = minOf(quality.kbps, 320) * 1000
        return withTimeoutOrNull(16_000L) {
            val excluded = PlaybackClientCatalog.manifests.filter {
                PlaybackTransport.SABR !in it.transports || it.authentication == AuthenticationPolicy.REQUIRED ||
                    it.selectionMode != ClientSelectionMode.AUTOMATIC ||
                    it.lifecycle in setOf(ClientLifecycle.BROKEN, ClientLifecycle.DEPRECATED)
            }.mapTo(excludedClients.toMutableSet()) { it.id }
            val hints = ContentHints(wantVideo = false, sabrFirst = true).withStreamCapabilities(
                allowHls = false, allowSabr = true, allowBoundedRange = true)
            val extractor = runtime().extractor(cap, false, sabr = true)
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                val extracted = extractor.extract(videoId, hints, excludedClients = excluded,
                    audioQuality = YouTubeAudioFormatPolicy.quality(cap, attempt))
                    ?: throw IOException("No anonymous SABR audio client is available for this recording")
                if (!YouTubeAudioFormatPolicy.acceptsBitrate(extracted.bitrate, cap)) return@repeat
                excluded.add(extracted.profileId)
                val bootstrap = extracted.sabrBootstrap ?: return@repeat
                if (extracted.videoId != videoId || bootstrap.videoId != videoId ||
                    extracted.videoUrl != null || bootstrap.selectedVideoFormat != null ||
                    bootstrap.audioFormat.itag != extracted.itag ||
                    bootstrap.durationMs <= 0 || bootstrap.contentLengthBytes?.let { it <= 0 } == true ||
                    !bootstrap.mimeType.startsWith("audio/")) return@repeat
                requireAllowedSabrUrl(bootstrap.serverAbrStreamingUrl)
                val codec = displayCodec(extracted.mimeType, extracted.codecs)
                if (codec !in setOf("AAC", "Opus", "Vorbis")) return@repeat
                val expiry = minOf(extracted.expiresAt?.toEpochMilliseconds() ?: Long.MAX_VALUE,
                    bootstrap.serverAbrStreamingUrl.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
                        ?.let { it * 1000L } ?: Long.MAX_VALUE, System.currentTimeMillis() + 300_000L) - 30_000L
                if (expiry <= System.currentTimeMillis() + 15_000L) return@repeat
                val stream = AudioStream(
                    SabrSessionRegistry.uriFor(bootstrap, cap / 1000).toString(), (extracted.bitrate ?: 0).coerceAtLeast(0) / 1000, false,
                    SabrSessionRegistry.cacheKeyFor(bootstrap), expiry, emptyMap(), codec, extracted.itag,
                    bootstrap.contentLengthBytes, videoId, false, 0, extracted.profileId, bootstrap)
                SabrSessionRegistry.register(stream, quality)
                cache.put("$videoId:$cap:sabr", stream)
                return@withTimeoutOrNull stream
            }
            throw IOException("YouTube did not provide a compatible SABR audio representation")
        } ?: throw IOException("SABR audio resolution timed out")
    }

    internal fun sabrChunks(stream: AudioStream, startMs: Long): Flow<SabrChunk> =
        runtime().sabrChunks(requireNotNull(stream.sabrBootstrap), startMs)

    private data class AudioProbe(val url: String, val bitrate: Int? = null, val codec: String? = null)
    private data class MediaProbe(val url: String, val bytes: ByteArray, val type: String, val redirect: String? = null,
        val transient: Boolean = false)

    /** Only verified audio playlists are handed to Media3; never a muxed video master. */
    private suspend fun probeAudio(url: String, headers: Map<String, String>, hls: Boolean, cap: Int, codec: String): AudioProbe? {
        val response = if (hls) fetchMediaProbe(url, headers, manifest = true) else {
            // Bound speculative latency separately from extraction. A definite HTTP
            // rejection is still fatal; a probe timeout is not proof of unplayability.
            val result = withTimeoutOrNull(1_500L) {
                Result.success(fetchMediaProbe(url, headers, manifest = false))
            } ?: return AudioProbe(url, codec = codec)
            result.getOrNull()
        } ?: return null
        // A range probe can be unsupported or temporarily throttled even though the
        // library already verified audio-only metadata. Let the real loader decide then.
        if (!hls) return response.takeIf { it.transient ||
            it.bytes.isNotEmpty() && !it.type.contains("text/") && !it.type.contains("json")
        }?.let { AudioProbe(it.url, codec = codec) }
        if (response.transient) return null // An unparsed HLS master could contain video.
        val playlist = try { withContext(Dispatchers.Default) {
            HlsPlaylistParser().parse(Uri.parse(response.url), response.bytes.inputStream())
        } } catch (e: CancellationException) { throw e } catch (_: IOException) { return null }
        if (playlist is HlsMediaPlaylist) {
            if (playlist.protectionSchemes != null) return null
            // A media playlist has no CODECS declaration. Sniff a bounded first segment
            // rather than rejecting it or trusting a potentially muxed video playlist.
            val segment = playlist.segments.firstOrNull() ?: return null
            val init = segment.initializationSegment?.url ?: segment.url
            val address = response.url.toHttpUrlOrNull()?.resolve(init)?.toString() ?: return null
            val probe = fetchMediaProbe(address, headers, manifest = false) ?: return null
            if (probe.type.startsWith("audio/") && probe.bytes.isNotEmpty()) return AudioProbe(response.url)
            val bytes = probe.bytes
            val audioContainer = bytes.size >= 12 &&
                (String(bytes, Charsets.ISO_8859_1).contains("OpusHead") ||
                 String(bytes, Charsets.ISO_8859_1).let { "mp4a" in it && "vide" !in it } ||
                 bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 246 == 240)
            return AudioProbe(response.url).takeIf { audioContainer }
        }
        if (playlist !is HlsMultivariantPlaylist || playlist.sessionKeyDrmInitData.isNotEmpty()) return null
        val renditions = playlist.audios.mapNotNull { audio -> audio.url?.let { it.toString() to audio.format } }
        val candidates = renditions.ifEmpty {
            playlist.variants.filter { variant ->
                val format = variant.format
                format.width <= 0 && format.height <= 0 &&
                    MimeTypes.getVideoMediaMimeType(format.codecs) == null &&
                    MimeTypes.getAudioMediaMimeType(format.codecs) != null
            }.map { it.url.toString() to it.format }
        }
        val originals = candidates.filter { it.second.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }.ifEmpty { candidates }
        fun bitrate(format: Format) = format.averageBitrate.takeIf { it > 0 } ?: format.peakBitrate.takeIf { it > 0 }
        val selected = originals.filter { (address, format) ->
            isMediaUrl(address) && format.drmInitData == null &&
                MimeTypes.getVideoMediaMimeType(format.codecs) == null && (bitrate(format) ?: 0) <= cap
        }.maxByOrNull { bitrate(it.second) ?: 0 } ?: return null
        val audio = fetchMediaProbe(selected.first, headers, manifest = true) ?: return null
        val media = try { withContext(Dispatchers.Default) {
            HlsPlaylistParser().parse(Uri.parse(audio.url), audio.bytes.inputStream())
        } } catch (e: CancellationException) { throw e } catch (_: IOException) { return null }
        if (media !is HlsMediaPlaylist || media.protectionSchemes != null) return null
        return AudioProbe(audio.url, bitrate(selected.second),
            displayCodec(selected.second.sampleMimeType, selected.second.codecs).takeIf { it.isNotBlank() })
    }

    private suspend fun fetchMediaProbe(url: String, headers: Map<String, String>, manifest: Boolean): MediaProbe? {
        var address = url
        repeat(4) {
            if (!isMediaUrl(address)) return null
            val response = readMediaProbe(address, headers, manifest) ?: return null
            val redirect = response.redirect ?: return response
            // Follow a small number of CDN redirects only after validating each target.
            address = redirect
        }
        return null
    }

    private suspend fun readMediaProbe(url: String, headers: Map<String, String>, manifest: Boolean): MediaProbe? =
        suspendCancellableCoroutine { result ->
            val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }
                .apply { if (!manifest) header("Range", "bytes=0-16383") }
                .header("Accept-Encoding", "identity").build()
            val call = mediaHttp.newCall(request)
            result.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (result.isActive) result.resume(MediaProbe(url, byteArrayOf(), "", transient = true))
                }
                override fun onResponse(call: Call, response: Response) {
                    val probe = response.use {
                        if (it.code in setOf(301, 302, 303, 307, 308)) {
                            val next = it.header("Location")?.let(it.request.url::resolve)?.toString()
                            return@use next?.takeIf(::isMediaUrl)?.let { redirect ->
                                MediaProbe(url, byteArrayOf(), "", redirect)
                            }
                        }
                        if (it.code in setOf(405, 408, 416, 429) || it.code in 500..599)
                            return@use MediaProbe(url, byteArrayOf(), "", transient = true)
                        if (it.code != 200 && it.code != 206) return@use null
                        try {
                            val body = it.body ?: return@use null
                            val source = body.source()
                            val limit = if (manifest) 256 * 1024L else 16 * 1024L
                            source.request(limit + 1)
                            if (manifest && source.buffer.size > limit) return@use null
                            val bytes = source.readByteArray(minOf(source.buffer.size, limit))
                            if (manifest && !bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) return@use null
                            MediaProbe(url, bytes, it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase())
                        } catch (_: IOException) { null }
                    }
                    if (result.isActive) result.resume(probe)
                }
            })
        }
    /** Only the service's final teardown closes networking/cipher resources, never a screen disposal. */
    fun releaseResources() {
        SabrSessionRegistry.releaseAll()
        val old = synchronized(runtimeLock) { runtime.also { runtime = null } } ?: return
        synchronized(cache) { cache.evictAll() }
        rejected.evictAll()
        cleanupScope.launch { old.close() }
    }
}
