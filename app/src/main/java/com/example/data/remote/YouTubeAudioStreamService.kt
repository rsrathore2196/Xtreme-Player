package com.example.data.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.example.playback.AudioQuality
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
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
    private val mediaHttp = http.newBuilder().followRedirects(false).build()
    private val cache = android.util.LruCache<String, AudioStream>(128)
    private val locks = Array(16) { Mutex() }
    private val slots = Semaphore(2)
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var runtime: Runtime? = null
    private val runtimeLock = Any()
    private val mediaHeaders = setOf("user-agent", "accept", "accept-language", "origin", "referer")

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
                    // Keep the native strategy: the library enables visitor-backed and
                    // automatic fallback paths by its actual strategy type.
                    cipherService = cipher, innerTube = innerTube))
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
                withTimeoutOrNull(35_000L) lookup@{
                    // Prefer a directly playable audio-only format. An HLS-only client can
                    // expose a muxed master which this audio player must reject; that is a
                    // transport miss, not proof the selected recording is unavailable.
                    val transports = if (hlsOnly == null) listOf(false, true) else listOf(hlsOnly)
                    var failure: Exception? = null
                    for (transport in transports) {
                        val result = withTimeoutOrNull(if (transports.size > 1) 18_000L else 33_000L) transport@{
                            val excluded = mutableSetOf<String>().apply { excludedClient?.let(::add) }
                            val extractor = runtime().extractor(cap, transport)
                            val hints = ContentHints(wantVideo = false).withStreamCapabilities(
                                allowHls = transport, allowSabr = false, allowBoundedRange = true)
                            repeat(3) {
                                currentCoroutineContext().ensureActive()
                                val extracted = try {
                                    extractor.extract(videoId, hints, excludedClients = excluded, audioQuality = ExtractionQuality.HIGH)
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { failure = e; return@transport null }
                                    ?: return@transport null
                                if (extracted.videoId != videoId || !isMediaUrl(extracted.audioUrl) || extracted.sabrBootstrap != null)
                                    return@transport null
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
                                val headers = extracted.headers.filter { (name, value) ->
                                    name.lowercase() in mediaHeaders && '\r' !in value && '\n' !in value }
                                val verified = if (transport == hls && (hls || bitrate in 1..cap) &&
                                    expiry > System.currentTimeMillis() + 45_000L) probeAudio(extracted.audioUrl, headers, hls, cap) else null
                                if (verified != null) {
                                    val codec = verified.codec ?: displayCodec(extracted.mimeType, extracted.codecs)
                                    return@transport AudioStream(verified.url, (verified.bitrate ?: bitrate) / 1000, hls,
                                        "youtube:" + videoId + ":" + extracted.itag + ":" + extracted.contentLengthBytes,
                                        expiry - 30_000L, headers, codec, extracted.itag, extracted.contentLengthBytes, videoId,
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
                    throw IOException("YouTube did not provide an accessible compatible audio stream", failure)
                } ?: throw IOException("YouTube audio resolution timed out")
            }
        }
    }

    private data class AudioProbe(val url: String, val bitrate: Int? = null, val codec: String? = null)
    private data class MediaProbe(val url: String, val bytes: ByteArray, val type: String, val redirect: String? = null)

    /** Only verified audio playlists are handed to Media3; never a muxed video master. */
    private suspend fun probeAudio(url: String, headers: Map<String, String>, hls: Boolean, cap: Int): AudioProbe? {
        val response = fetchMediaProbe(url, headers, manifest = hls) ?: return null
        if (!hls) return response.takeIf {
            it.bytes.isNotEmpty() && (it.type.startsWith("audio/") ||
                it.type in setOf("video/mp4", "video/webm", "application/octet-stream"))
        }?.let { AudioProbe(it.url) }
        val playlist = try { withContext(Dispatchers.Default) {
            HlsPlaylistParser().parse(Uri.parse(response.url), response.bytes.inputStream())
        } } catch (e: CancellationException) { throw e } catch (_: IOException) { return null }
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
                .apply { if (!manifest) header("Range", "bytes=0-1023") }
                .header("Accept-Encoding", "identity").build()
            val call = mediaHttp.newCall(request)
            result.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (result.isActive) result.resume(null) }
                override fun onResponse(call: Call, response: Response) {
                    val probe = response.use {
                        if (it.code in setOf(301, 302, 303, 307, 308)) {
                            val next = it.header("Location")?.let(it.request.url::resolve)?.toString()
                            return@use next?.takeIf(::isMediaUrl)?.let { redirect ->
                                MediaProbe(url, byteArrayOf(), "", redirect)
                            }
                        }
                        if (it.code != 200 && it.code != 206) return@use null
                        try {
                            val body = it.body ?: return@use null
                            val source = body.source()
                            val limit = if (manifest) 256 * 1024L else 16L
                            source.request(limit + 1)
                            if (manifest && source.buffer.size > limit) return@use null
                            val bytes = source.readByteArray(minOf(source.buffer.size, limit))
                            if (manifest && !bytes.take(7).toByteArray().contentEquals("#EXTM3U".toByteArray())) return@use null
                            MediaProbe(url, bytes, it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase())
                        } catch (_: IOException) { null }
                    }
                    if (result.isActive) result.resume(probe)
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
