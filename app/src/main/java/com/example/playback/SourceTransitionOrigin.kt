package com.example.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.data.remote.YouTubeAudioStreamService

/** A transport bookmark, never a second queue or a replacement catalog identity. */
internal data class SourceTransitionOrigin(val track: MusicTrack, val sourceId: String) {
    companion object {
        val keys = listOf("enhancer_origin_track", "enhancer_origin_uri", "enhancer_origin_provider",
            "enhancer_origin_id", "enhancer_origin_bitrate", "enhancer_origin_badge", "enhancer_origin_lossless")
        fun capture(track: MusicTrack, policy: PlaybackSourcePolicy): SourceTransitionOrigin? {
            val source = sourceId(track.id, Uri.parse(track.audioUrl), policy) ?: return null
            return SourceTransitionOrigin(track, source)
        }
        fun sourceId(trackId: String, uri: Uri, policy: PlaybackSourcePolicy): String? {
            val host = uri.host.orEmpty()
            return when {
                YouTubeAudioStreamService.isYouTubeAudio(trackId, uri.toString()) -> policy.sourceId(PlaybackSourceKind.YOUTUBE)
                host == "saavncdn.com" || host.endsWith(".saavncdn.com") -> policy.sourceId(PlaybackSourceKind.JIOSAAVN)
                host == "archive.org" || host.endsWith(".archive.org") -> policy.sourceId(PlaybackSourceKind.INTERNET_ARCHIVE)
                else -> policy.playbackSources.firstOrNull { it.kind == PlaybackSourceKind.ADDON &&
                    StreamingAddons.fromSource(uri.toString(), it.id) }?.id
            }
        }
        fun read(item: MediaItem?, track: MusicTrack): SourceTransitionOrigin? {
            val extras = item?.mediaMetadata?.extras ?: return null
            if (extras.getString(keys[0]) != track.id) return null
            val uri = extras.getString(keys[1]).orEmpty().takeIf { it.isNotBlank() } ?: return null
            val source = extras.getString(keys[3]).orEmpty().takeIf { it.isNotBlank() } ?: return null
            return SourceTransitionOrigin(track.copy(audioUrl = uri, source = extras.getString(keys[2]).orEmpty(),
                bitrateKbps = extras.getInt(keys[4]), qualityBadge = extras.getString(keys[5]).orEmpty(),
                isLossless = extras.getBoolean(keys[6])), source)
        }
        fun write(extras: Bundle, origin: SourceTransitionOrigin?) {
            keys.forEach(extras::remove)
            if (origin == null) return
            extras.putString(keys[0], origin.track.id); extras.putString(keys[1], origin.track.audioUrl)
            extras.putString(keys[2], origin.track.source); extras.putString(keys[3], origin.sourceId)
            extras.putInt(keys[4], origin.track.bitrateKbps); extras.putString(keys[5], origin.track.qualityBadge)
            extras.putBoolean(keys[6], origin.track.isLossless)
        }
    }
}
