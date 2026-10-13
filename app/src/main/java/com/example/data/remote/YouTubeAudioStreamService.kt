package com.example.data.remote

import android.net.Uri
import com.example.playback.AudioQuality
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exact-video audio resolution. No proxy, alternate upload, credential or signature guesses. */
object YouTubeAudioStreamService {
    data class AudioStream(val url: String, val bitrateKbps: Int, val isHls: Boolean,
        val cacheKey: String, val expiresAt: Long)
    private data class Client(val name: String, val version: String, val number: String, val agent: String)
    private val clients = listOf(
        Client("ANDROID_VR", "1.65.10", "28", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L) gzip"),
        Client("IOS", "21.26.4", "5", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"),
        Client("ANDROID", "19.34.42", "3", "com.google.android.youtube/19.34.42 (Linux; U; Android 14; en_US) gzip"),
        Client("WEB_REMIX", "1.20240101.01.00", "67", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    )
    val playbackHeaders = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://www.youtube.com/")
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    private val cache = android.util.LruCache<String, AudioStream>(128)
    private val locks = Array(16) { Mutex() }
    private val slots = kotlinx.coroutines.sync.Semaphore(3)
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    fun isVideoId(value: String) = videoIdPattern.matches(value)
    private fun mediaUrl(value: String): HttpUrl? = value.toHttpUrlOrNull()?.takeIf {
        it.scheme == "https" && (it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com"))
    }
    private fun originalAudio(format: JSONObject): Boolean {
        val track = format.optJSONObject("audioTrack") ?: return true
        val label = track.optString("displayName")
        val tags = format.optString("url").toHttpUrlOrNull()?.queryParameter("xtags").orEmpty()
        return label.contains("original", true) || Regex("(?:^|[,:])acont=original(?:$|[,:])").containsMatchIn(tags)
    }
    fun playbackUri(trackId: String, url: String): String {
        val id = trackId.removePrefix("yt_")
        if (!trackId.startsWith("yt_") || !isVideoId(id) || mediaUrl(url) == null) return url
        val hls = url.contains("/hls", true) || url.substringBefore('?').endsWith(".m3u8", true)
        return Uri.Builder().scheme("xtreme-youtube").authority("audio").appendPath(id)
            .appendPath(if (hls) "stream.m3u8" else "stream.audio").build().toString()
    }

    suspend fun resolve(videoId: String, quality: AudioQuality, forceRefresh: Boolean = false,
        hlsOnly: Boolean? = null): AudioStream = withContext(Dispatchers.IO) {
        require(isVideoId(videoId)) { "Invalid YouTube video identity" }
        val key = "$videoId:${quality.id}:$hlsOnly"
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (forceRefresh) synchronized(cache) {
                cache.snapshot().keys.filter { it.startsWith("$videoId:") }.forEach(cache::remove)
            }
            cache.get(key)?.takeIf { it.expiresAt > System.currentTimeMillis() + 15_000L }?.let { return@withLock it }
            slots.acquire()
            try {
                for (client in clients) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val clientContext = JSONObject().put("clientName", client.name).put("clientVersion", client.version)
                            .put("hl", "en").put("gl", "US").put("userAgent", client.agent)
                        when (client.name) {
                            "ANDROID_VR" -> clientContext.put("androidSdkVersion", 32)
                                .put("osName", "Android").put("osVersion", "12L").put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
                            "IOS" -> clientContext.put("osName", "iPhone").put("osVersion", "18.3.2.22D82")
                                .put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
                            "ANDROID" -> clientContext.put("androidSdkVersion", 34)
                                .put("osName", "Android").put("osVersion", "14").put("deviceMake", "Google").put("deviceModel", "Pixel 8")
                            "WEB_REMIX" -> clientContext.put("osName", "Windows").put("osVersion", "10.0")
                        }
                        val body = JSONObject().put("context", JSONObject().put("client", clientContext)).put("videoId", videoId)
                        val request = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                            .header("User-Agent", client.agent).header("X-YouTube-Client-Name", client.number)
                            .header("X-YouTube-Client-Version", client.version)
                            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        val response = JSONObject(http.catalogRequest(request))
                        if (response.optJSONObject("videoDetails")?.optString("videoId") != videoId ||
                            response.optJSONObject("playabilityStatus")?.optString("status") != "OK") continue
                        val data = response.optJSONObject("streamingData") ?: continue
                        if (data.optJSONArray("drmFamilies")?.length()?.let { it > 0 } == true) continue
                        val until = System.currentTimeMillis() + minOf(data.optLong("expiresInSeconds", 300L), 300L) * 1000L
                        if (hlsOnly != true) {
                            val formats = data.optJSONArray("adaptiveFormats")
                            val audio = (0 until (formats?.length() ?: 0)).mapNotNull { formats?.optJSONObject(it) }
                                .filter { it.optString("mimeType").let { mime -> mime.startsWith("audio/mp4") || mime.startsWith("audio/webm") } &&
                                    !it.has("drmFamilies") && it.optString("type") != "FORMAT_STREAM_TYPE_OTF" &&
                                    originalAudio(it) && mediaUrl(it.optString("url")) != null }
                                .sortedByDescending { minOf(it.optInt("bitrate"), quality.kbps.coerceAtMost(320) * 1000) }
                            for (format in audio.take(3)) {
                                val url = format.optString("url")
                                if (!readableMedia(url)) continue
                                val expiry = mediaUrl(url)?.queryParameter("expire")?.toLongOrNull()?.let { it * 1000L - 30_000L } ?: until
                                if (expiry <= System.currentTimeMillis() + 15_000L) continue
                                val result = AudioStream(url, format.optInt("bitrate") / 1000, false,
                                    "youtube:$videoId:${format.optInt("itag")}:${format.optString("lastModified")}", minOf(until, expiry))
                                cache.put(key, result)
                                cache.put("$videoId:${quality.id}:false", result)
                                return@withLock result
                            }
                            // Direct fallback to highest quality googlevideo URL if byte-probe timed out
                            val directFormat = audio.firstOrNull { mediaUrl(it.optString("url")) != null }
                            if (directFormat != null) {
                                val url = directFormat.optString("url")
                                val expiry = mediaUrl(url)?.queryParameter("expire")?.toLongOrNull()?.let { it * 1000L - 30_000L } ?: until
                                val result = AudioStream(url, directFormat.optInt("bitrate") / 1000, false,
                                    "youtube:$videoId:${directFormat.optInt("itag")}:${directFormat.optString("lastModified")}", minOf(until, expiry))
                                cache.put(key, result)
                                cache.put("$videoId:${quality.id}:false", result)
                                return@withLock result
                            }
                        }
                        if (hlsOnly != false) audioHls(videoId, data.optString("hlsManifestUrl"), until)?.let {
                            cache.put(key, it); cache.put("$videoId:${quality.id}:true", it); return@withLock it
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Try the next public client; never substitute a different video. */ }
                }
                throw IOException("YouTube did not provide an accessible audio stream for this video.")
            } finally { slots.release() }
        }
    }

    private fun request(url: String): Request = Request.Builder().url(url)
        .apply { playbackHeaders.forEach { (key, value) -> header(key, value) } }.build()

    private suspend fun readableMedia(url: String): Boolean = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request(url).newBuilder().header("Range", "bytes=0-0").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resume(false) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val type = it.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                        val valid = it.code in listOf(200, 206) && mediaUrl(it.request.url.toString()) != null &&
                            (type.startsWith("audio/") || type in setOf("video/mp4", "video/webm", "video/mp2t", "application/octet-stream")) &&
                            (it.body?.byteStream()?.read() ?: -1) >= 0
                        if (!continuation.isCancelled) continuation.resume(valid)
                    } catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                }
            }
        })
    }

    private fun attributes(line: String): Map<String, String> = Regex("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))")
        .findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].ifBlank { it.groupValues[3] } }

    private suspend fun audioHls(videoId: String, manifest: String, until: Long): AudioStream? {
        val base = mediaUrl(manifest) ?: return null
        val text = http.catalogRequest(request(base.toString()))
        if (!text.startsWith("#EXTM3U")) return null
        val lines = text.lines().map(String::trim)
        val variants = mutableListOf<Pair<String, Int>>()
        val audioGroups = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes).filter { it["TYPE"] == "AUDIO" }
        val multipleLanguages = audioGroups.mapNotNull { it["LANGUAGE"] }.distinct().size > 1
        for ((index, line) in lines.withIndex()) {
            val info = attributes(line)
            if (line.startsWith("#EXT-X-MEDIA:") && info["TYPE"] == "AUDIO" &&
                (info["NAME"].orEmpty().contains("original", true) || (!multipleLanguages && info["DEFAULT"] != "NO")))
                info["URI"]?.let { variants.add(it to 0) }
            if (line.startsWith("#EXT-X-STREAM-INF:") && info["RESOLUTION"] == null &&
                !multipleLanguages &&
                info["CODECS"].orEmpty().let { it.contains("mp4a") || it.contains("opus") } &&
                !Regex("(?i)avc|hev|hvc|av01|vp0?9").containsMatchIn(info["CODECS"].orEmpty()))
                lines.getOrNull(index + 1)?.takeUnless { it.startsWith('#') }?.let { variants.add(it to ((info["BANDWIDTH"]?.toIntOrNull() ?: 0) / 1000)) }
        }
        for ((relative, bitrate) in variants.distinct().sortedByDescending { it.second }.take(3)) {
            val url = base.resolve(relative)?.toString() ?: continue
            if (mediaUrl(url) == null) continue
            val playlist = http.catalogRequest(request(url))
            if (!playlist.startsWith("#EXTM3U") || playlist.contains("SAMPLE-AES")) continue
            val segment = playlist.lines().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') } ?: continue
            val segmentUrl = url.toHttpUrlOrNull()?.resolve(segment)?.toString() ?: continue
            if (mediaUrl(segmentUrl) == null || !readableMedia(segmentUrl)) continue
            return AudioStream(url, bitrate, true, "youtube:$videoId:hls:$bitrate", until)
        }
        return null
    }
}
