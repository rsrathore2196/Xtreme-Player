package com.example.data.model

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
    val audioUrl: String,
    val bitrateKbps: Int = 320,
    val qualityBadge: String = "HD • 320 kbps",
    val genre: String = "Electronic",
    val isLiked: Boolean = false,
    val isCached: Boolean = false,
    val singers: String = "",
    val writer: String = "",
    val language: String = "Hindi",
    val year: String = "",
    val source: String = "HD Stream",
    val isrc: String = ""
) {
    fun formatDuration(): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
