package com.example.downloads

import com.example.data.model.MusicTrack
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETE }
enum class DownloadQuality(val label: String, val kbps: Int) {
    STANDARD("Standard · up to 128 kbps", 128), HIGH("High · best available audio", 320),
    LOSSLESS("Lossless when available · best audio otherwise", Int.MAX_VALUE)
}
data class DownloadPreferences(val wifiOnly: Boolean = true, val quality: DownloadQuality = DownloadQuality.HIGH)
data class OfflinePlan(val uri: String, val mime: String?, val representation: String, val progressive: Boolean)
data class DownloadRecord(
    val track: MusicTrack, val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytes: Long = 0, val total: Long = -1, val error: String? = null,
    val plan: OfflinePlan? = null, val artwork: String = "", val collection: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

internal fun downloadKey(id: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
    val digits = "0123456789abcdef"
    return CharArray(bytes.size * 2).also { chars ->
        bytes.forEachIndexed { index, value ->
            val byte = value.toInt() and 255
            chars[index * 2] = digits[byte ushr 4]
            chars[index * 2 + 1] = digits[byte and 15]
        }
    }.concatToString()
}

/** Deliberately stores song metadata only; account credentials never enter download requests. */
internal object OfflineJson {
    fun track(track: MusicTrack): JSONObject = JSONObject().apply {
        put("id", track.id); put("title", track.title); put("artist", track.artist); put("album", track.album)
        put("duration", track.durationMs); put("cover", track.coverUrl); put("audio", track.audioUrl)
        put("bitrate", track.bitrateKbps); put("quality", track.qualityBadge); put("source", track.source)
        put("isrc", track.isrc); put("lossless", track.isLossless); put("year", track.year)
        put("genre", track.genre); put("language", track.language); put("singers", track.singers)
        put("primary", JSONArray(track.primaryArtists)); put("originalTitle", track.originalProviderTitle)
    }
    fun track(value: JSONObject) = MusicTrack(value.getString("id"), value.getString("title"),
        value.optString("artist"), value.optString("album"), value.optLong("duration"),
        value.optString("cover"), value.optString("audio"), value.optInt("bitrate", 0),
        value.optString("quality"), source = value.optString("source"), isrc = value.optString("isrc"),
        isLossless = value.optBoolean("lossless"), year = value.optString("year"),
        genre = value.optString("genre"), language = value.optString("language"), singers = value.optString("singers"),
        primaryArtists = value.optJSONArray("primary")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        originalProviderTitle = value.optString("originalTitle"))
    fun record(record: DownloadRecord) = JSONObject().apply {
        put("track", track(record.track)); put("status", record.status.name); put("bytes", record.bytes)
        put("total", record.total); put("error", record.error); put("artwork", record.artwork)
        put("collection", record.collection); put("added", record.addedAt)
        record.plan?.let { put("plan", JSONObject().put("uri", it.uri).put("mime", it.mime)
            .put("representation", it.representation).put("progressive", it.progressive)) }
    }
    fun record(value: JSONObject): DownloadRecord {
        val plan = value.optJSONObject("plan")?.let { OfflinePlan(it.getString("uri"),
            it.optString("mime").takeIf(String::isNotBlank), it.getString("representation"), it.getBoolean("progressive")) }
        return DownloadRecord(track(value.getJSONObject("track")), DownloadStatus.valueOf(value.getString("status")),
            value.optLong("bytes"), value.optLong("total", -1), value.optString("error").takeIf(String::isNotBlank),
            plan, value.optString("artwork"), value.optString("collection"), value.optLong("added"))
    }
}
