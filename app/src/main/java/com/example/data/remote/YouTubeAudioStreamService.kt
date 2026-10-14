package com.example.data.remote

import android.net.Uri
import com.example.playback.AudioQuality
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.strategy.ClientFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ClientSelectionResult
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackTransport
import com.metrolist.innertubex.extraction.strategy.PlaybackTransportPreference
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.extraction.AudioQuality as ExtractionQuality
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
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
object YouTubeAudioStreamService {
    data class AudioStream(
        val url: String, val bitrateKbps: Int, val isHls: Boolean,
        val cacheKey: String, val expiresAt: Long, val headers: Map<String, String>,
        val codec: String, val itag: Int, val contentLength: Long?, val videoId: String,
        val useRangeChunks: Boolean, val rangeChunkSize: Long, val clientId: String
    ) {
        val qualityBadge: String get() = displayQuality(codec, bitrateKbps)
        override fun toString() = "AudioStream(codec=" + codec + ", bitrateKbps=" + bitrateKbps + ", hls=" + isHls + ")"
    }
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val cache = android.util.LruCache<String, AudioStream>(128)
    private val locks = Array(16) { Mutex() }
    private val slots = Semaphore(2)
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var runtime: Runtime? = null
    private val runtimeLock = Any()
    private val mediaHeaders = setOf("user-agent", "accept", "accept-language", "origin", "referer")

    private class Media3FallbackStrategy(private val hlsOnly: Boolean?) : ClientFallbackStrategy {
        private val standard = ContentAwareFallbackStrategy()
        override fun resolveClients(hints: ContentHints): List<YouTubeClient> =
            selectClients(ClientSelectionRequest(hints, authenticated = false)).candidates.map { it.client }
        override fun selectClients(request: ClientSelectionRequest): ClientSelectionResult {
            val preference = when (hlsOnly) {
                true -> PlaybackTransportPreference.HLS
                false -> PlaybackTransportPreference.DIRECT
                null -> request.transportPreference
            }
            val result = standard.selectClients(request.copy(transportPreference = preference))
            val supported = when (hlsOnly) {
                true -> setOf(PlaybackTransport.HLS)
                false -> setOf(PlaybackTransport.DIRECT)
                null -> setOf(PlaybackTransport.DIRECT, PlaybackTransport.HLS)
            }
            return result.copy(candidates = result.candidates.filter { candidate ->
                !candidate.client.useSabr && candidate.manifest?.transports?.any { it in supported } != false
            })
        }
    }

    private class Runtime {
        private fun client(network: OkHttpClient) = HttpClient(OkHttp) {
            engine {
                // Ktor shuts down its OkHttp dispatcher/pool on close. Keep each
                // engine separate from the probe client and from later sessions.
                preconfigured = network.newBuilder().dispatcher(Dispatcher())
                    .connectionPool(ConnectionPool()).build()
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 12_000L; connectTimeoutMillis = 8_000L; socketTimeoutMillis = 10_000L
            }
        }
        private val cipherHttp = client(http)
        private val config = RemotePlayerConfigStore(cipherHttp, PlayerConfigRepository.disabled())
        private val cipher = YouTubeCipherService(cipherHttp, config)
        private data class Engine(val http: HttpClient, val innerTube: InnerTube, val extractor: InnerTubeExtractor)
        private val engines = mutableMapOf<String, Engine>()
        fun extractor(cap: Int, hlsOnly: Boolean?): InnerTubeExtractor = synchronized(engines) {
            engines.getOrPut(cap.toString() + ":" + hlsOnly) {
                val network = http.newBuilder().addInterceptor(YouTubeAudioFormatPolicy(cap, hlsOnly)).build()
                val apiHttp = client(network)
                val innerTube = InnerTube(apiHttp)
                Engine(apiHttp, innerTube, InnerTubeExtractor(
                    configParser = YtConfigParserImpl(apiHttp, innerTube, config, cipherService = cipher),
                    cipherService = cipher, innerTube = innerTube, fallbackStrategy = Media3FallbackStrategy(hlsOnly)))
            }.extractor
        }
        suspend fun close() {
            synchronized(engines) { engines.values.toList().also { engines.clear() } }.forEach {
                it.innerTube.close(); it.http.close()
            }
            try { cipher.dispose() } finally { cipherHttp.close() }
        }
    }
    private fun runtime(): Runtime = runtime ?: synchronized(runtimeLock) { runtime ?: Runtime().also { runtime = it } }

    fun isVideoId(value: String) = videoIdPattern.matches(value)
    fun isMediaUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            (it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com") ||
                (it.host == "youtube.com" || it.host.endsWith(".youtube.com")) &&
                (it.encodedPath == "/videoplayback" || it.encodedPath.startsWith("/manifest/") ||
                    it.encodedPath.startsWith("/api/manifest/")))
    } == true
    fun isYouTubeAudio(trackId: String, url: String) = url.startsWith("xtreme-youtube://") || isMediaUrl(url) ||
        trackId.startsWith("yt_") && (url.isBlank() || url.startsWith("yt_"))
    private fun isHls(url: String, mime: String?) = mime?.contains("mpegurl", true) == true ||
        url.contains("/hls", true) || url.substringBefore('?').endsWith(".m3u8", true)
    fun displayQuality(codec: String, bitrate: Int): String =
        (codec.takeIf(String::isNotBlank) ?: "HQ Stream") + if (bitrate > 0) " • " + bitrate + " kbps" else ""
    fun displayCodec(mime: String?, codec: String?): String = when {
        codec?.contains("opus", true) == true || mime?.contains("opus", true) == true -> "Opus"
        codec?.contains("vorbis", true) == true || mime?.contains("vorbis", true) == true -> "Vorbis"
        codec?.contains("mp4a", true) == true || mime?.contains("mp4a", true) == true -> "AAC"
        else -> codec.orEmpty().take(40)
    }
    fun playbackUri(trackId: String, url: String): String {
        if (url.startsWith("xtreme-youtube://")) return url
        // An exact matching JioSaavn/FLAC fallback keeps its own transport, even if the
        // catalog identity is a YouTube video. Never reroute another provider's URL.
        if (url.isNotBlank() && !url.startsWith("yt_") && !isMediaUrl(url)) return url
        val resolved = cachedForUrl(url)
        val id = if (trackId.startsWith("yt_")) trackId.removePrefix("yt_") else resolved?.videoId.orEmpty()
        if (!isVideoId(id)) return url
        // Queue resumption may hold an empty or expired signed URL; identity still resolves on the loader.
        val itag = resolved?.itag ?: url.toHttpUrlOrNull()?.queryParameter("itag")?.toIntOrNull()
        return Uri.Builder().scheme("xtreme-youtube").authority("audio").appendPath(id)
            .appendPath(if (isHls(url, null)) "stream.m3u8" else "stream.audio")
            .apply {
                itag?.let { appendQueryParameter("itag", it.toString()) }
                resolved?.contentLength?.let { appendQueryParameter("length", it.toString()) }
                if (isHls(url, null)) {
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
        val parsed = url.toHttpUrlOrNull()
        val client = parsed?.queryParameter("c") ?: parsed?.pathSegments?.let { path ->
            path.indexOf("c").takeIf { it >= 0 }?.let { path.getOrNull(it + 1) }
        }
        cache.snapshot().values.lastOrNull { it.url == url ||
            client != null && it.url.toHttpUrlOrNull()?.queryParameter("c") == client }?.headers
            ?: mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.youtube.com/")
    }
    fun invalidate(videoId: String) = synchronized(cache) {
        cache.snapshot().keys.filter { it.startsWith(videoId + ":") }.forEach(cache::remove)
    }

    suspend fun resolve(videoId: String, quality: AudioQuality, forceRefresh: Boolean = false,
        hlsOnly: Boolean? = null, excludedClient: String? = null): AudioStream = withContext(Dispatchers.IO) {
        require(isVideoId(videoId)) { "Invalid YouTube video identity" }
        val cap = minOf(quality.kbps, 320) * 1000
        val key = videoId + ":" + cap + ":" + hlsOnly
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (forceRefresh) invalidate(videoId)
            cache.get(key)?.takeIf { it.expiresAt > System.currentTimeMillis() + 45_000L }?.let { return@withLock it }
            slots.withPermit {
                withTimeout(35_000L) {
                    val excluded = mutableSetOf<String>().apply { excludedClient?.let(::add) }
                    val extractor = runtime().extractor(cap, hlsOnly)
                    val hints = ContentHints(wantVideo = false).withStreamCapabilities(
                        allowHls = hlsOnly != false, allowSabr = false, allowBoundedRange = true)
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val extracted = extractor.extract(videoId, hints, excludedClients = excluded, audioQuality = ExtractionQuality.HIGH)
                            ?: throw IOException("No compatible YouTube audio format is available")
                        if (extracted.videoId != videoId || !isMediaUrl(extracted.audioUrl) || extracted.sabrBootstrap != null)
                            throw IOException("Invalid YouTube audio stream identity or transport")
                        val hls = isHls(extracted.audioUrl, extracted.mimeType)
                        val expiry = minOf(extracted.expiresAt?.toEpochMilliseconds() ?: Long.MAX_VALUE,
                            extracted.audioUrl.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
                                ?.let { it * 1000L } ?: Long.MAX_VALUE,
                            System.currentTimeMillis() + 300_000L)
                        val bitrate = extracted.bitrate?.coerceAtLeast(0) ?: 0
                        val headers = extracted.headers.filter { (name, value) ->
                            name.lowercase() in mediaHeaders && '\r' !in value && '\n' !in value }
                        if ((hlsOnly == null || hlsOnly == hls) && (hls || bitrate in 1..cap) &&
                            expiry > System.currentTimeMillis() + 45_000L && probe(extracted.audioUrl, headers, hls)) {
                            val codec = displayCodec(extracted.mimeType, extracted.codecs)
                            val result = AudioStream(extracted.audioUrl, bitrate / 1000, hls,
                                "youtube:" + videoId + ":" + extracted.itag + ":" + extracted.contentLengthBytes,
                                expiry - 30_000L, headers, codec, extracted.itag, extracted.contentLengthBytes, videoId,
                                !hls && (extracted.requireBoundedRange || extracted.useRangeChunks),
                                extracted.rangeChunkSizeBytes.coerceIn(64 * 1024L, 1024 * 1024L), extracted.profileId)
                            cache.put(key, result); cache.put(videoId + ":" + cap + ":" + hls, result)
                            return@withTimeout result
                        }
                        excluded.add(extracted.profileId)
                    }
                    throw IOException("YouTube did not provide an accessible compatible audio stream")
                }
            }
        }
    }

    private suspend fun probe(url: String, headers: Map<String, String>, hls: Boolean): Boolean = suspendCancellableCoroutine { result ->
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }
            .header("Range", "bytes=0-1023").header("Accept-Encoding", "identity").build()
        val call = http.newBuilder().followRedirects(false).build().newCall(request)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (result.isActive) result.resume(false) }
            override fun onResponse(call: Call, response: Response) {
                val valid = response.use {
                    if (it.code !in listOf(200, 206) || !isMediaUrl(it.request.url.toString())) false
                    else try {
                        val body = it.body ?: return@use false
                        if (hls) body.source().readUtf8(7) == "#EXTM3U"
                        else {
                            val type = it.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                            (type.startsWith("audio/") || type in setOf("video/mp4", "video/webm", "video/mp2t", "application/octet-stream")) &&
                                body.byteStream().read() >= 0
                        }
                    } catch (_: IOException) { false }
                }
                if (result.isActive) result.resume(valid)
            }
        })
    }
    /** Only the service's final teardown closes networking/cipher resources, never a screen disposal. */
    fun releaseResources() {
        val old = synchronized(runtimeLock) { runtime.also { runtime = null } } ?: return
        synchronized(cache) { cache.evictAll() }
        cleanupScope.launch { old.close() }
    }
}
