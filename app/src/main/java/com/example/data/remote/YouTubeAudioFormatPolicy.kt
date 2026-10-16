package com.example.data.remote

import com.metrolist.innertubex.extraction.AudioQuality
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Preserve InnerTubeX's native inventory, original-vocal selection, cipher and transport fallback.
 * Only explicitly DRM-protected formats are removed; quality is checked after extraction. */
internal class YouTubeAudioFormatPolicy : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful || chain.request().url.encodedPath != "/youtubei/v1/player") return response
        val preview = response.peekBody(MAX_PLAYER_BYTES + 1).string()
        if (preview.toByteArray().size > MAX_PLAYER_BYTES) {
            response.close()
            throw IOException("Player response exceeds the size limit")
        }
        val root = try { JSONObject(preview) } catch (_: Exception) { return response }
        val data = root.optJSONObject("streamingData") ?: return response
        var changed = false
        for (name in listOf("formats", "adaptiveFormats")) {
            val entries = data.optJSONArray(name) ?: continue
            val safe = JSONArray()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if ((entry.optJSONArray("drmFamilies")?.length() ?: 0) > 0 ||
                    entry.optString("drmTrackType").isNotBlank()) changed = true else safe.put(entry)
            }
            data.put(name, safe)
        }
        if (!changed) return response
        val type = response.body.contentType()
        response.close()
        return response.newBuilder().removeHeader("Content-Length")
            .body(root.toString().toResponseBody(type)).build()
    }

    companion object {
        private const val MAX_PLAYER_BYTES = 4L * 1024 * 1024
        fun quality(cap: Int, attempt: Int): AudioQuality = when {
            cap < 128_000 -> AudioQuality.LOW
            attempt == 0 -> AudioQuality.HIGH
            attempt == 1 -> AudioQuality.MP4
            else -> AudioQuality.LOW
        }
        fun acceptsBitrate(bitrate: Int?, cap: Int) = bitrate == null || bitrate <= 0 || bitrate <= cap
    }
}
