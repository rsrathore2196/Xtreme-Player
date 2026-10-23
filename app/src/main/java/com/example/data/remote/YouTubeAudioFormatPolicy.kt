package com.example.data.remote

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import com.metrolist.innertubex.extraction.selectBestAudioFormat
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Restrict each vocal-track inventory before native selection; never replace the extractor. */
internal class YouTubeAudioFormatPolicy(private val maxBitrateBps: Int = Int.MAX_VALUE,
    private val opusOnly: Boolean = false) : Interceptor {
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
        // Choose the original/default vocal track before filtering by bitrate.
        // Otherwise a fitting dub can replace an over-cap original recording.
        val audio = listOf("formats", "adaptiveFormats").flatMap { name ->
            val entries = data.optJSONArray(name) ?: return@flatMap emptyList()
            (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
        }.filter { it.optString("mimeType").startsWith("audio/") }
        val nativeFormats = audio.mapIndexed { index, entry ->
            Format(itag = index, mimeType = entry.optString("mimeType"), bitrate = entry.optInt("bitrate"),
                xtags = entry.optString("xtags").takeIf(String::isNotBlank),
                audioTrack = vocalTrack(entry))
        }
        val primaryTrack = selectBestAudioFormat(nativeFormats, requireUrl = false)?.audioTrack
        var changed = false
        for (name in listOf("formats", "adaptiveFormats")) {
            val entries = data.optJSONArray(name) ?: continue
            val safe = JSONArray()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if (opusOnly && !entry.optString("mimeType").contains("opus", true)) {
                    changed = true; continue
                }
                if ((entry.optJSONArray("drmFamilies")?.length() ?: 0) > 0 ||
                    entry.optString("drmTrackType").isNotBlank()) { changed = true; continue }
                val otherVocalTrack = primaryTrack?.let { primary ->
                    if (primary.id != null) vocalTrack(entry)?.id != primary.id else vocalTrack(entry) != primary
                } == true
                if (entry.optString("mimeType").startsWith("audio/") && otherVocalTrack) {
                    changed = true; continue
                }
                val bitrate = entry.optInt("bitrate", 0)
                val rejected = entry.optString("mimeType").startsWith("audio/") && !acceptsBitrate(bitrate, maxBitrateBps)
                if (rejected) changed = true
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

    private fun vocalTrack(entry: JSONObject): Format.AudioTrack? = entry.optJSONObject("audioTrack")?.let { track ->
        Format.AudioTrack(id = track.optString("id").takeIf(String::isNotBlank),
            displayName = track.optString("displayName").takeIf(String::isNotBlank),
            audioIsDefault = track.optBoolean("audioIsDefault").takeIf { track.has("audioIsDefault") })
    }

    companion object {
        private const val MAX_PLAYER_BYTES = 4L * 1024 * 1024
        fun quality(cap: Int, attempt: Int): AudioQuality = when {
            cap <= 96_000 -> AudioQuality.LOW
            attempt == 0 -> AudioQuality.AUTO
            attempt == 1 -> AudioQuality.MP4
            else -> AudioQuality.LOW
        }
        fun acceptsBitrate(bitrate: Int?, cap: Int) = if (bitrate == null || bitrate <= 0) cap >= 320_000 else bitrate <= cap
    }
}
