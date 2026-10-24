package com.example.data.model

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.Locale

/** Prefer the actual audio transport over the catalog ID when a provider fallback plays. */
val MusicTrack.playbackSourceName: String
    get() {
        val uri = android.net.Uri.parse(audioUrl)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        return when {
            uri.scheme == "xtreme-youtube" || uri.scheme == "xtreme-sabr" || host == "googlevideo.com" ||
                host.endsWith(".googlevideo.com") -> "YouTube Music"
            host == "archive.org" || host.endsWith(".archive.org") -> "Internet Archive"
            host == "saavncdn.com" || host.endsWith(".saavncdn.com") -> "JioSaavn"
            else -> when (source.trim().uppercase(Locale.ROOT)) {
                "JIOSAAVN", "JIOSAAVN OFFICIAL CHART", "SAAVN" -> "JioSaavn"
                "YOUTUBE", "YOUTUBE MUSIC", "YOUTUBE_MUSIC", "EXTENDED STREAM" -> "YouTube Music"
                "INTERNET_ARCHIVE", "INTERNET ARCHIVE" -> "Internet Archive"
                "", "HD STREAM" -> "Unknown"
                else -> source
            }
        }
    }

/**
 * Extension function to convert MusicTrack to MediaItem.
 * This extension is used by PlaybackManager to create media items for playback.
 */
fun MusicTrack.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(this.title)
        .setArtist(this.artist)
        .setAlbumTitle(this.album)
        .setGenre(this.genre)
        .setExtras(android.os.Bundle().apply {
            putBoolean("recommendation_only", recommendationOnly)
            putString("media_source", source)
            putStringArrayList("primaryArtists", ArrayList(primaryArtists))
            putLong("playlist_entry_id", playlistEntryId)
        })
        .setArtworkUri(
            if (this.coverUrl.isNotBlank()) {
                android.net.Uri.parse(this.coverUrl)
            } else {
                null
            }
        )
        .build()

    return MediaItem.Builder()
        .setMediaId(this.id)
        .setUri(com.example.data.remote.YouTubeAudioStreamService.playbackUri(this.id, this.audioUrl))
        .setMediaMetadata(metadata)
        .build()
}

/**
 * Extension function to get a human-readable duration string.
 * Converts milliseconds to "MM:SS" format.
 */
fun Long.toFormattedDuration(): String {
    val totalSeconds = this / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
