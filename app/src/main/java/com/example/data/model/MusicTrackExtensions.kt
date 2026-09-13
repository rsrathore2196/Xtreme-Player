package com.example.data.model

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

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
        .setArtworkUri(
            if (this.coverUrl.isNotBlank()) {
                android.net.Uri.parse(this.coverUrl)
            } else {
                null
            }
        )
        .build()

    return MediaItem.Builder()
        .setUri(this.audioUrl)
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
