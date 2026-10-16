package com.example.data.remote

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.selectBestAudioFormat
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Host quality/transport policy only. InnerTubeX owns requests, selection and deciphering. */
internal class YouTubeAudioFormatPolicy(
    private val maximumBitrate: Int,
    private val hlsOnly: Boolean?,
    private val sabr: Boolean = false
) : Interceptor {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        val url = chain.request().url
        if (url.encodedPath != "/youtubei/v1/player" ||
            url.host !in setOf("www.youtube.com", "music.youtube.com", "m.youtube.com", "studio.youtube.com")) return response
        val body = response.body ?: return response
        val type = body.contentType()
        try {
            if (body.contentLength() > MAX_PLAYER_BYTES) throw IOException("Player response exceeds the size limit")
            val buffer = Buffer()
            val source = body.source()
            while (true) {
                val count = source.read(buffer, 8192)
                if (count == -1L) break
                if (buffer.size > MAX_PLAYER_BYTES) throw IOException("Player response exceeds the size limit")
            }
            val root = JSONObject(buffer.readUtf8())
            root.optJSONObject("streamingData")?.let { data ->
                val arrays = listOfNotNull(data.optJSONArray("formats"), data.optJSONArray("adaptiveFormats"))
                val parsed = arrays.flatMap { entries -> (0 until entries.length()).mapNotNull { index ->
                    val item = entries.optJSONObject(index) ?: return@mapNotNull null
                    runCatching { json.decodeFromString(Format.serializer(), item.toString()) }.getOrNull()
                } }.filter { it.isAudio && it.mimeType.startsWith("audio/") }
                // Select the original/default recording before applying the cap, so a lower
                // bitrate dub cannot replace the vocals just because the original is larger.
                val original = selectBestAudioFormat(parsed, AudioQuality.HIGH, requireUrl = false)
                for (name in listOf("formats", "adaptiveFormats")) {
                    val entries = data.optJSONArray(name) ?: continue
                    val allowed = JSONArray()
                    if (hlsOnly != true) for (index in 0 until entries.length()) {
                        val item = entries.optJSONObject(index) ?: continue
                        val format = runCatching { json.decodeFromString(Format.serializer(), item.toString()) }.getOrNull() ?: continue
                        // InnerTubeX needs the discard-video format's identity to bootstrap
                        // SABR audio. Retain metadata only; wantVideo remains false and
                        // SabrAudioStream never requests or emits a video track.
                        if (sabr && !format.isAudio && format.height != null) {
                            allowed.put(item)
                            continue
                        }
                        val sameVocals = original?.audioTrack?.id?.let { it == format.audioTrack?.id }
                            ?: original?.xtags?.let { it == format.xtags } ?: true
                        val supportedCodec = format.mimeType.contains("mp4a", true) ||
                            format.mimeType.contains("opus", true) || format.mimeType.contains("vorbis", true)
                        if (format.isAudio && sameVocals && supportedCodec && format.bitrate in 1..maximumBitrate &&
                            item.optString("type") != "FORMAT_STREAM_TYPE_OTF" &&
                            (item.optJSONArray("drmFamilies")?.length() ?: 0) == 0)
                            allowed.put(item)
                    }
                    data.put(name, allowed)
                }
                if (hlsOnly == false) data.remove("hlsManifestUrl")
            }
            return response.newBuilder().removeHeader("Content-Length")
                .body(root.toString().toResponseBody(type)).build()
        } catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException("Invalid player format metadata", e) }
        finally { body.close() }
    }

    private companion object { const val MAX_PLAYER_BYTES = 4L * 1024 * 1024 }
}
