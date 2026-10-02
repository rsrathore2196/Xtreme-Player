package com.example.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val durationMs: Long = 0L
)

@Immutable
data class TrackLyrics(
    val trackId: String,
    val title: String,
    val artist: String,
    val isSynced: Boolean,
    val lines: List<LyricLine>
)
