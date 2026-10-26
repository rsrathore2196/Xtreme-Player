package com.example.data.remote

import com.example.performance.AppDispatchers
import com.example.playback.PlaybackDiagnostics

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
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackTransport
import kotlinx.coroutines.flow.Flow
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object YouTubeAudioStreamService {
    internal const val RESOLUTION_TIMEOUT_MS = 40_000L
    internal val playerJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    data class AudioStream(
        val url: String, val bitrateKbps: Int, val isHls: Boolean,
        val cacheKey: String, val expiresAt: Long, val headers: Map<String, String>,
        val codec: String, val itag: Int, val contentLength: Long?, val videoId: String,
        val useRangeChunks: Boolean, val rangeChunkSize: Long, val clientId: String,
        val sabrBootstrap: SabrBootstrap? = null,
        val selectionCapBps: Int = 320_000,
        val opusOnly: Boolean = false,
        /** Cache-only descriptors carry transport identity, never session tokens/bootstrap. */
        val cachedSabr: Boolean = false
    ) {
        val isSabr: Boolean get() = sabrBootstrap != null || cachedSabr
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
            // InnerTubeX passes @Serializable PlayerBody/SearchBody/BrowseBody
            // objects to setBody(). Without this plugin Ktor rejects every typed
            // request before HTTP, while the separate raw metadata API still works.
            install(ContentNegotiation) {
                json(playerJson)
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
        // Constructing the provider does not construct a WebView. Metadata search
        // and recommendation discovery never initialize attestation.
        private val tokens = YouTubePoTokenProvider(openBrowser = {
            YouTubeTokenWebView.open(com.example.XtremeMusicApp.getInstance(), mediaHttp)
        })
        private val config = RemotePlayerConfigStore(cipherHttp, PlayerConfigRepository.disabled(), nativeLogger)
        private val cipher = YouTubeCipherService(cipherHttp, config, nativeLogger)
        private data class Engine(val http: HttpClient, val innerTube: InnerTube, val extractor: InnerTubeExtractor)
        private val engines = mutableMapOf<String, Engine>()
        private fun engine(cap: Int, opusOnly: Boolean = false): Engine = synchronized(engines) {
            // Transport is a per-extraction ContentHints capability, not session identity.
            // Reuse visitor/config/cipher state across Direct -> HLS -> SABR recovery.
            engines.getOrPut("$cap:$opusOnly") {
                val network = http.newBuilder().addInterceptor(YouTubeAudioFormatPolicy(cap, opusOnly)).build()
                val apiHttp = client(network)
                val innerTube = InnerTube(apiHttp, logger = nativeLogger)
                Engine(apiHttp, innerTube, InnerTubeExtractor(
                    configParser = YtConfigParserImpl(apiHttp, innerTube, config, logger = nativeLogger, cipherService = cipher),
                    // Keep the native strategy: the library enables visitor-backed and
                    // automatic fallback paths by its actual strategy type.
                    cipherService = cipher, innerTube = innerTube, tokenProvider = tokens, logger = nativeLogger))
            }
        }
        fun extractor(cap: Int, opusOnly: Boolean = false): InnerTubeExtractor = engine(cap, opusOnly).extractor
        fun sabrChunks(bootstrap: SabrBootstrap, startMs: Long): Flow<SabrChunk> =
            SabrAudioStream(sabrHttp, bootstrap, maxRequestsWithoutProgress = 3, initialPlayerTimeMs = startMs).chunks()

        suspend fun searchSongs(query: String, continuation: String?): String {
            val response = engine(320_000).innerTube.search(
                client = YouTubeClient.WEB_REMIX, query = query.takeIf { continuation == null },
                params = "EgWKAQIIAWoKEAkQBRAKEAMQBA==".takeIf { continuation == null },
                continuation = continuation, setLogin = false)
            val body = response.bodyAsTextLimited(4 * 1024 * 1024)
            if (response.status.value !in 200..299) throw IOException("YouTube Music search is unavailable")
            return body
        }
        suspend fun discovery(operation: String, value: String, countryCode: String,
            params: String? = null, continuation: String? = null, languageCode: String = "en"): String {
            // Isolate locale/account state; recommendations must not mutate the playback
            // extractor's session or another concurrent search's regional context.
            val session = engine(320_000).innerTube.createIsolatedSession(includeAccount = false)
            try {
                session.locale = com.metrolist.innertubex.models.YouTubeLocale(
                    gl = countryCode.uppercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty(),
                    hl = languageCode.takeIf { it.matches(Regex("[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*")) } ?: "en")
                session.regionOverrideActive = true
                return requestDiscovery(session, operation, value, params, continuation)
            } finally { session.close() }
        }
        suspend fun close() {
            tokens.close()
            synchronized(engines) { engines.values.toList().also { engines.clear() } }.forEach {
                it.innerTube.close(); it.http.close()
            }
            try { cipher.dispose() } finally { cipherHttp.close(); sabrHttp.close() }
        }
    }
    /** Native messages/bodies are not safe to log. Only known event names and typed fields leave this adapter. */
    internal val nativeLogger = InnerTubeLogger { event ->
        val code = when (event.message) {
            "player request failed" -> "PLAYER_REQUEST_FAILED"
            "player response decoded" -> "PLAYER_RESPONSE"
            "player response decode failed", "invalid player response" -> "PLAYER_RESPONSE_INVALID"
            "player response rejected" -> "PLAYER_IDENTITY_REJECTED"
            "token fetch completed" -> if (event.details["tokenPresent"] == "true") "TOKEN_READY" else "TOKEN_UNAVAILABLE"
            "token binding rejected" -> "TOKEN_BINDING_REJECTED"
            "no playable clients" -> "NO_PLAYABLE_CLIENTS"
            "watch page config ready", "embedded config ready" -> "PLAYER_CONFIG_READY"
            else -> null
        }
        if (code != null) {
            val client = event.details["client"].orEmpty().takeIf { value ->
                PlaybackClientCatalog.manifests.any { it.id == value || it.client.clientName == value }
            }.orEmpty()
            PlaybackDiagnostics.youtubeEvent(event.mediaId.orEmpty(), code, client,
                httpStatus = event.details["httpStatus"]?.toIntOrNull()?.takeIf { it in 100..599 })
        }
    }
    private fun runtime(): Runtime = runtime ?: synchronized(runtimeLock) { runtime ?: Runtime().also { runtime = it } }

    /** Import uses the same InnerTubeX session/client stack as audio extraction. */
    internal suspend fun searchSongsResponse(query: String, continuation: String?): String =
        withContext(Dispatchers.IO) { runtime().searchSongs(query, continuation) }

    internal suspend fun discoveryResponse(operation: String, value: String, countryCode: String,
        params: String? = null, continuation: String? = null, languageCode: String = "en"): String =
        withContext(Dispatchers.IO) { runtime().discovery(operation, value, countryCode, params, continuation, languageCode) }

    internal suspend fun requestDiscovery(session: InnerTube, operation: String, value: String,
        params: String? = null, continuation: String? = null): String {
        val response = when (operation) {
            "related" -> session.next(client = YouTubeClient.WEB_REMIX, videoId = value)
            "browse" -> session.browse(client = YouTubeClient.WEB_REMIX,
                browseId = value.takeIf { continuation == null }, params = params.takeIf { continuation == null },
                continuation = continuation, setLogin = false)
            else -> session.search(client = YouTubeClient.WEB_REMIX, query = value.takeIf { continuation == null },
                // Artist lookups must retain their artist filter, rather than searching songs.
                params = (params ?: "EgWKAQIIAWoKEAkQBRAKEAMQBA==").takeIf { continuation == null },
                continuation = continuation, setLogin = false)
        }
        val body = response.bodyAsTextLimited(4 * 1024 * 1024)
        if (response.status.value !in 200..299) throw IOException("Music discovery is temporarily unavailable")
        return body
    }

    fun isVideoId(value: String) = videoIdPattern.matches(value)
    fun isMediaUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            (it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com") ||
                (it.host == "youtube.com" || it.host.endsWith(".youtube.com")) &&
                (it.encodedPath == "/videoplayback" || it.encodedPath.startsWith("/manifest/") ||
                    it.encodedPath.startsWith("/api/manifest/")))
    } == true
    fun isYouTubeAudio(trackId: String, url: String) = isPlaybackUri(url) || isMediaUrl(url) ||
        runCatching { pageVideoId(Uri.parse(url)) != null }.getOrDefault(false) ||
        trackId.startsWith("yt_") && (url.isBlank() || url.startsWith("yt_"))
    fun isPlaybackUri(url: String) = url.startsWith("xtreme-youtube://audio/") || url.startsWith("xtreme-sabr://audio/")
    /** A different ceiling alone does not require restarting identical audio bytes. */
    internal fun sameRepresentation(first: String, second: String): Boolean {
        if (!first.startsWith("xtreme-youtube://") || !second.startsWith("xtreme-youtube://")) return false
        return runCatching {
            val a = Uri.parse(first); val b = Uri.parse(second)
            val itag = a.getQueryParameter("itag")?.toIntOrNull()?.takeIf { it > 0 } ?: return@runCatching false
            val length = a.getQueryParameter("length")?.toLongOrNull()?.takeIf { it > 0 } ?: return@runCatching false
            videoIdFromUri(a) != null && videoIdFromUri(a) == videoIdFromUri(b) &&
                itag == b.getQueryParameter("itag")?.toIntOrNull() && length == b.getQueryParameter("length")?.toLongOrNull() &&
                a.path == b.path && a.getQueryParameter("session") == b.getQueryParameter("session")
        }.getOrDefault(false)
    }
    fun videoIdFromUri(uri: Uri): String? = uri.takeIf {
        it.scheme in setOf("xtreme-youtube", "xtreme-sabr") && it.host == "audio"
    }?.pathSegments?.firstOrNull()?.takeIf(::isVideoId)
    /** Catalog IDs and shared/legacy page URLs are inputs, never playable URLs. */
    fun videoIdFromReference(trackId: String, url: String): String? =
        trackId.removePrefix("yt_").takeIf { trackId.startsWith("yt_") && isVideoId(it) }
            ?: runCatching { Uri.parse(url).let { videoIdFromUri(it) ?: pageVideoId(it) } }.getOrNull()

    private fun pageVideoId(uri: Uri): String? {
        if (uri.scheme !in setOf("https", "http")) return null
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        val id = when {
            host == "youtu.be" -> uri.pathSegments.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") ->
                if (uri.pathSegments.firstOrNull() in setOf("shorts", "embed", "live")) uri.pathSegments.getOrNull(1)
                else uri.getQueryParameter("v")
            else -> null
        }
        return id?.takeIf(::isVideoId)
    }
    /** Direct references use bits/s; SABR references persist kilobits/s. */
    fun bitrateCapFromUri(uri: Uri): Int? {
        if (videoIdFromUri(uri) == null) return null
        val cap = uri.getQueryParameter("cap")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val bits = if (uri.scheme == "xtreme-sabr") cap.takeIf { it <= Int.MAX_VALUE / 1000L }?.times(1000L)
            ?: return null else cap
        return bits.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
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
    fun playbackUri(trackId: String, url: String, opusOnly: Boolean = false): String {
        if (isPlaybackUri(url)) return if (opusOnly && Uri.parse(url).getQueryParameter("codec") != "opus")
            Uri.parse(url).buildUpon().appendQueryParameter("codec", "opus").build().toString() else url
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
                resolved?.selectionCapBps?.let { appendQueryParameter("cap", it.toString()) }
                if (opusOnly || resolved?.opusOnly == true) appendQueryParameter("codec", "opus")
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
    fun cachedForUrl(url: String): AudioStream? = synchronized(cache) { cache.snapshot().values.lastOrNull { it.url == url } }

    /** Representation-declared average rate, not an itag estimate or another source's extras. */
    fun bitrateForPlaybackUri(uri: Uri, decodedCodec: String): Int = synchronized(cache) {
        val address = uri.toString()
        val video = videoIdFromUri(uri)
        val itag = uri.getQueryParameter("itag")?.toIntOrNull()
        val length = uri.getQueryParameter("length")?.toLongOrNull()
        val cap = uri.getQueryParameter("cap")?.toIntOrNull()
        val sabrKey = if (uri.scheme == "xtreme-sabr") runCatching {
            SabrSessionRegistry.cacheKeyFromUri(uri)
        }.getOrNull() else null
        val opusOnly = uri.getQueryParameter("codec") == "opus"
        val rates = cache.snapshot().values.asSequence().filter { stream ->
            stream.bitrateKbps > 0 && stream.codec.equals(decodedCodec, true) && !stream.isHls &&
                (stream.url == address || sabrKey != null && stream.isSabr && stream.cacheKey == sabrKey &&
                    cap?.toLong()?.times(1000) == stream.selectionCapBps.toLong() ||
                    uri.scheme == "xtreme-youtube" && video == stream.videoId &&
                    !stream.isSabr && itag != null && itag == stream.itag &&
                    cap != null && cap == stream.selectionCapBps &&
                    (length == null || length == stream.contentLength)) &&
                (!opusOnly || stream.codec.equals("opus", true))
        }.map { it.bitrateKbps }.distinct().take(2).toList()
        // Ambiguous/stale references stay unknown. Signed URL expiry doesn't
        // invalidate bitrate evidence for a representation already being decoded.
        rates.singleOrNull() ?: 0
    }
    internal fun rememberOfflineRepresentation(stream: AudioStream) {
        synchronized(cache) { cache.put("offline:${stream.videoId}:${stream.cacheKey}", stream) }
    }
    fun cachedStreams(videoId: String): List<AudioStream> = synchronized(cache) {
        cache.snapshot().values.filter { it.videoId == videoId }.distinctBy { it.cacheKey }
    }
    fun currentStream(videoId: String, quality: AudioQuality, maxBitrateBps: Int = Int.MAX_VALUE): AudioStream? = synchronized(cache) {
        val prefix = videoId + ":" + minOf(minOf(quality.kbps, 320) * 1000, maxBitrateBps) + ":"
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

    private fun selectionCap(quality: AudioQuality, pinnedCap: Int?): Int {
        val network = pinnedCap ?: com.example.playback.NetworkQualityMonitor.getInstance(
            com.example.XtremeMusicApp.getInstance()).snapshot().effectiveCeiling.maxBitrateBps
        // A loader's explicit cap pins its representation across seeks/renewals.
        // Do not silently apply a newly selected profile to an already-audible source.
        return minOf(if (pinnedCap != null) 320_000 else minOf(quality.kbps, 320) * 1000,
            network.coerceAtLeast(1))
    }

    suspend fun resolve(videoId: String, quality: AudioQuality, forceRefresh: Boolean = false,
        hlsOnly: Boolean? = null, excludedClient: String? = null, maxBitrateBps: Int? = null,
        requireOpus: Boolean = false): AudioStream = withContext(Dispatchers.IO) {
        require(isVideoId(videoId)) { "Invalid YouTube video identity" }
        val cap = selectionCap(quality, maxBitrateBps)
        val key = videoId + ":" + cap + ":" + hlsOnly + if (requireOpus) ":opus" else ""
        locks[(("$videoId:$cap").hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (forceRefresh) invalidate(videoId)
            cache.get(key)?.takeIf { YouTubeAudioFormatPolicy.acceptsBitrate(it.bitrateKbps.takeIf { rate -> rate > 0 }?.times(1000), cap) &&
                it.clientId !in (rejectedClients(videoId) + setOfNotNull(excludedClient)) &&
                it.expiresAt > System.currentTimeMillis() + 15_000L }?.let { return@withLock it }
            slots.withPermit {
                val stream = withTimeoutOrNull(RESOLUTION_TIMEOUT_MS) {
                    extractPlayable(videoId, quality, cap, hlsOnly, requireOpus,
                        rejectedClients(videoId) + setOfNotNull(excludedClient), runtime().extractor(cap, requireOpus))
                } ?: throw PlaybackDiagnostics.Failure(PlaybackDiagnostics.Reason.TIMEOUT, PlaybackDiagnostics.Stage.EXTRACTION)
                cache.put(key, stream)
                cache.put(videoId + ":" + cap + ":" + stream.isHls + if (requireOpus) ":opus" else "", stream)
                stream
            }
        }
    }

    /** Use native client/transport fallback under ONE caller-owned deadline. */
    internal suspend fun extractPlayable(videoId: String, quality: AudioQuality, cap: Int, hlsOnly: Boolean?,
        requireOpus: Boolean, excludedClients: Set<String>, extractor: StreamExtractor,
        sabrOnly: Boolean = false,
        probe: suspend (String, Map<String, String>, Boolean, Int, String) -> AudioProbe? = ::probeAudio): AudioStream {
        val excluded = excludedClients.toMutableSet()
        if (hlsOnly == true || sabrOnly) excluded.addAll(PlaybackClientCatalog.manifests.filter {
            (if (sabrOnly) PlaybackTransport.SABR else PlaybackTransport.HLS) !in it.transports
        }.map { it.id })
        val hints = ContentHints(wantVideo = false, sabrFirst = sabrOnly).withStreamCapabilities(
            allowHls = !sabrOnly, allowSabr = hlsOnly != true, allowBoundedRange = true)
        var failure: Exception? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            val extracted = try {
                extractor.extract(videoId, hints, excludedClients = excluded,
                    audioQuality = YouTubeAudioFormatPolicy.quality(cap, attempt))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error; null }
            if (extracted == null) {
                throw PlaybackDiagnostics.Failure(failure?.let(PlaybackDiagnostics::reason) ?: PlaybackDiagnostics.Reason.NO_FORMAT,
                    PlaybackDiagnostics.Stage.EXTRACTION, failure)
            }
            val transport = if (extracted.sabrBootstrap != null) "SABR" else
                if (isHls(extracted.audioUrl, extracted.mimeType)) "HLS" else "DIRECT"
            try {
                val stream = prepareExtracted(videoId, quality, cap, hlsOnly, requireOpus, sabrOnly, extracted, probe)
                PlaybackDiagnostics.youtubeEvent(videoId, "STREAM_READY", extracted.profileId, transport,
                    reason = PlaybackDiagnostics.Reason.SUCCESS)
                return stream
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = error
                PlaybackDiagnostics.youtubeEvent(videoId, "STREAM_REJECTED", extracted.profileId, transport,
                    reason = PlaybackDiagnostics.reason(error))
                excluded.add(extracted.profileId)
            }
        }
        throw PlaybackDiagnostics.Failure(failure?.let(PlaybackDiagnostics::reason) ?: PlaybackDiagnostics.Reason.NO_FORMAT,
            PlaybackDiagnostics.Stage.VALIDATION, failure)
    }

    private suspend fun prepareExtracted(videoId: String, quality: AudioQuality, cap: Int, hlsOnly: Boolean?,
        requireOpus: Boolean, sabrOnly: Boolean, extracted: ExtractedStream,
        probe: suspend (String, Map<String, String>, Boolean, Int, String) -> AudioProbe?): AudioStream {
        fun reject(reason: PlaybackDiagnostics.Reason): Nothing = throw PlaybackDiagnostics.Failure(reason, PlaybackDiagnostics.Stage.VALIDATION)
        if (extracted.videoId != videoId || extracted.videoUrl != null || extracted.videoWidth != null || extracted.videoHeight != null)
            reject(PlaybackDiagnostics.Reason.UNSUPPORTED_TRANSPORT)
        val codec = displayCodec(extracted.mimeType, extracted.codecs)
        val bootstrap = extracted.sabrBootstrap
        val hls = bootstrap == null && isHls(extracted.audioUrl, extracted.mimeType)
        if (hlsOnly == true && !hls || sabrOnly && bootstrap == null) reject(PlaybackDiagnostics.Reason.UNSUPPORTED_TRANSPORT)
        if (!hls && (extracted.mimeType?.startsWith("audio/") != true || codec !in setOf("AAC", "Opus", "Vorbis")))
            reject(PlaybackDiagnostics.Reason.NO_FORMAT)
        val url = bootstrap?.serverAbrStreamingUrl ?: extracted.audioUrl
        val expiry = minOf(extracted.expiresAt?.toEpochMilliseconds() ?: Long.MAX_VALUE,
            url.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
                ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000) ?: Long.MAX_VALUE,
            System.currentTimeMillis() + 300_000L) - if (bootstrap != null) 30_000L else 5_000L
        if (expiry <= System.currentTimeMillis() + 15_000L) reject(PlaybackDiagnostics.Reason.INVALID_URL)
        if (bootstrap != null) {
            if (bootstrap.videoId != videoId || bootstrap.selectedVideoFormat != null || bootstrap.audioFormat.itag != extracted.itag ||
                bootstrap.durationMs <= 0 || bootstrap.contentLengthBytes?.let { it <= 0 } == true || !bootstrap.mimeType.startsWith("audio/"))
                reject(PlaybackDiagnostics.Reason.UNSUPPORTED_TRANSPORT)
            requireAllowedSabrUrl(url)
            if (!YouTubeAudioFormatPolicy.acceptsBitrate(extracted.bitrate, cap) ||
                requireOpus && (codec != "Opus" || (extracted.bitrate ?: 0) !in 1..cap)) reject(PlaybackDiagnostics.Reason.NO_FORMAT)
            return AudioStream(SabrSessionRegistry.uriFor(bootstrap, cap / 1000).toString(),
                (extracted.bitrate ?: 0).coerceAtLeast(0) / 1000, false, SabrSessionRegistry.cacheKeyFor(bootstrap), expiry,
                emptyMap(), codec, extracted.itag, bootstrap.contentLengthBytes, videoId, false, 0,
                extracted.profileId, bootstrap, cap, requireOpus).also { SabrSessionRegistry.register(it, quality) }
        }
        if (!isMediaUrl(url)) reject(PlaybackDiagnostics.Reason.INVALID_URL)
        val headers = extracted.headers.filter { (name, value) -> name.lowercase() in mediaHeaders && '\r' !in value && '\n' !in value }
        val verified = probe(url, headers, hls, cap, codec) ?: reject(PlaybackDiagnostics.Reason.INVALID_URL)
        val bitrate = verified.bitrate ?: extracted.bitrate
        val actualCodec = verified.codec ?: codec
        if (!YouTubeAudioFormatPolicy.acceptsBitrate(bitrate, cap) ||
            requireOpus && (actualCodec != "Opus" || (bitrate ?: 0) !in 1..cap)) reject(PlaybackDiagnostics.Reason.NO_FORMAT)
        return AudioStream(verified.url, (bitrate ?: 0).coerceAtLeast(0) / 1000, hls,
            "youtube:$videoId:${extracted.itag}:${extracted.contentLengthBytes}", expiry, headers, actualCodec,
            extracted.itag, extracted.contentLengthBytes, videoId, !hls && (extracted.requireBoundedRange || extracted.useRangeChunks),
            extracted.rangeChunkSizeBytes.coerceIn(64 * 1024L, 1024 * 1024L), extracted.profileId,
            selectionCapBps = cap, opusOnly = requireOpus)
    }

    /** Renewal stays on the SABR transport; a representation change uses existing time-based recovery. */
    suspend fun resolveSabr(videoId: String, quality: AudioQuality, excludedClients: Set<String> = emptySet(),
        maxBitrateBps: Int? = null, requireOpus: Boolean = false): AudioStream = withContext(Dispatchers.IO) {
        require(isVideoId(videoId)) { "Invalid YouTube video identity" }
        val cap = selectionCap(quality, maxBitrateBps)
        val key = "$videoId:$cap:sabr" + if (requireOpus) ":opus" else ""
        locks[(("$videoId:$cap").hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cache.get(key)?.takeIf { YouTubeAudioFormatPolicy.acceptsBitrate(it.bitrateKbps.takeIf { rate -> rate > 0 }?.times(1000), cap) &&
                it.clientId !in (excludedClients + rejectedClients(videoId)) &&
                it.expiresAt > System.currentTimeMillis() + 45_000L }?.let { return@withLock it }
            slots.withPermit {
                val stream = withTimeoutOrNull(RESOLUTION_TIMEOUT_MS) {
                    extractPlayable(videoId, quality, cap, false, requireOpus, excludedClients + rejectedClients(videoId),
                        runtime().extractor(cap, requireOpus), sabrOnly = true)
                } ?: throw PlaybackDiagnostics.Failure(PlaybackDiagnostics.Reason.TIMEOUT, PlaybackDiagnostics.Stage.EXTRACTION)
                cache.put(key, stream)
                stream
            }
        }
    }

    internal fun sabrChunks(stream: AudioStream, startMs: Long): Flow<SabrChunk> =
        runtime().sabrChunks(requireNotNull(stream.sabrBootstrap), startMs)

    internal data class AudioProbe(val url: String, val bitrate: Int? = null, val codec: String? = null)
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
        val playlist = try { withContext(AppDispatchers.CpuBounded) {
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
        val compatible = originals.filter { (address, format) ->
            isMediaUrl(address) && format.drmInitData == null && MimeTypes.getVideoMediaMimeType(format.codecs) == null
        }
        val selected = compatible.filter { (bitrate(it.second) ?: 0) <= cap }
            .filter { YouTubeAudioFormatPolicy.acceptsBitrate(bitrate(it.second), cap) }
            .maxByOrNull { bitrate(it.second) ?: 0 }
            ?: return null
        val audio = fetchMediaProbe(selected.first, headers, manifest = true) ?: return null
        val media = try { withContext(AppDispatchers.CpuBounded) {
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
