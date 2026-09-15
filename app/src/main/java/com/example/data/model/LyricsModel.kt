package com.example.data.model

data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val durationMs: Long = 0L
)

data class TrackLyrics(
    val trackId: String,
    val title: String,
    val artist: String,
    val isSynced: Boolean,
    val lines: List<LyricLine>
)
