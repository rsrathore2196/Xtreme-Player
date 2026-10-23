package com.example.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeAudioStreamService
import com.example.playback.AudioQualityCeiling

/** Pure, cheap queue-item transformation. Call only for new or upcoming items, never active audio. */
internal object NetworkAudioQualityPolicy {
    fun preparedFor(item: MediaItem, policy: com.example.playback.PlaybackSourcePolicy,
        now: Long = System.currentTimeMillis()): Boolean {
        val extras = item.mediaMetadata.extras ?: return false
        return extras.getString("audio_policy_tier") == policy.ceiling.audioQuality.name &&
            extras.getString("source_policy_key") == policy.key &&
            now - extras.getLong("audio_policy_checked_at", 0L) in 0L..300_000L
    }

    fun constrain(item: MediaItem, ceilingBps: Int): MediaItem {
        val uri = item.localConfiguration?.uri ?: return item
        if (!uri.isHierarchical) return item
        if (uri.scheme in setOf("file", "content")) return item
        val extras = item.mediaMetadata.extras
        // Ignore legacy per-item global settings, including restored queues.
        val requestedKbps = 320
        val host = uri.host.orEmpty()
        val saavn = host == "saavncdn.com" || host.endsWith(".saavncdn.com")
        var target = uri
        var bitrate: Int? = null
        var resetMime = false
        if (saavn) {
            bitrate = AudioQualityCeiling.saavnKbps(requestedKbps,
                extras?.getInt("bitrate", 320)?.takeIf { it in 1..320 } ?: 320, ceilingBps)
            target = Uri.parse(OnlineMusicApiService.formatUrlForQuality(uri.toString(), bitrate.toString()))
        } else if (uri.scheme == "xtreme-youtube" || uri.scheme == "xtreme-sabr") {
            val cap = minOf(ceilingBps, minOf(requestedKbps, 320) * 1000)
            val oldCap = YouTubeAudioStreamService.bitrateCapFromUri(uri)
            if (oldCap == cap) return item
            if (uri.scheme == "xtreme-sabr" || uri.lastPathSegment?.endsWith(".m3u8") == true) {
                // A different ceiling may need a direct low-bitrate format even
                // when the old item used HLS. Let native extraction choose again.
                val video = YouTubeAudioStreamService.videoIdFromUri(uri) ?: return item
                target = Uri.Builder().scheme("xtreme-youtube").authority("audio").appendPath(video)
                    .appendPath("stream.audio").appendQueryParameter("cap", cap.toString()).build()
                resetMime = true
            } else {
                // A new bitrate may select a different itag/container. No old byte
                // offsets or representation checks may carry into this upcoming item.
                target = uri.buildUpon().clearQuery().apply {
                    uri.queryParameterNames.filterNot { it in setOf("cap", "itag", "length", "session", "codec") }.forEach { key ->
                        uri.getQueryParameters(key).forEach { appendQueryParameter(key, it) }
                    }
                    appendQueryParameter("cap", cap.toString())
                }.build()
            }
        }
        if (target == uri) return item
        val metadata = Bundle(extras ?: Bundle()).apply {
            putInt("network_ceiling_bps", ceilingBps)
            if (bitrate != null) {
                putInt("bitrate", bitrate)
                putString("qualityBadge", YouTubeAudioStreamService.displayQuality("AAC", bitrate))
            }
        }
        return item.buildUpon().setUri(target).setCustomCacheKey(null).apply { if (resetMime) setMimeType(null) }
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(target).build())
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(metadata).build()).build()
    }
}
