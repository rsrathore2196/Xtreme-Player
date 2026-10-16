package com.example.data.remote

import com.metrolist.innertubex.extraction.AudioQuality
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Restrict each vocal-track inventory before native selection; never replace the extractor. */
internal class YouTubeAudioFormatPolicy(private val maxBitrateBps: Int = Int.MAX_VALUE) : Interceptor {
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
            val audioGroups = (0 until entries.length()).mapNotNull(entries::optJSONObject)
                .filter { it.optString("mimeType").startsWith("audio/") &&
                    (it.optJSONArray("drmFamilies")?.length() ?: 0) == 0 && it.optString("drmTrackType").isBlank() }
                // Do not drop the original vocal track just because a dub fits the cap.
                .groupBy { it.optJSONObject("audioTrack")?.optString("id").orEmpty() }
            val lowestByTrack = audioGroups.mapValues { (_, formats) ->
                formats.map { it.optInt("bitrate", 0) }.filter { it > 0 }.minOrNull()
            }
            val fittingTracks = audioGroups.filterValues { formats -> formats.any { it.optInt("bitrate", 0) in 1..maxBitrateBps } }.keys
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if ((entry.optJSONArray("drmFamilies")?.length() ?: 0) > 0 ||
                    entry.optString("drmTrackType").isNotBlank()) { changed = true; continue }
                val group = entry.optJSONObject("audioTrack")?.optString("id").orEmpty()
                val bitrate = entry.optInt("bitrate", 0)
                val exceeds = entry.optString("mimeType").startsWith("audio/") && bitrate > maxBitrateBps
                if (exceeds && (group in fittingTracks || bitrate != lowestByTrack[group])) changed = true
                else safe.put(entry)
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
        fun quality(@Suppress("UNUSED_PARAMETER") cap: Int, attempt: Int): AudioQuality = when {
            attempt == 0 -> AudioQuality.HIGH
            attempt == 1 -> AudioQuality.MP4
            else -> AudioQuality.LOW
        }
        fun acceptsBitrate(bitrate: Int?, cap: Int) = bitrate == null || bitrate <= 0 || bitrate <= cap
    }
}
