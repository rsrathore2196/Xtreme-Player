package com.example.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class SongIdentity(
    val trackId: String?,
    val title: String,
    val artist: String?,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null
) {
    companion object {
        fun from(track: MusicTrack): SongIdentity = SongIdentity(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            isrc = track.isrc
        )
    }
}

@Immutable
data class LyricSyllable(
    val timestampMs: Long,
    val durationMs: Long = 0L,
    val text: String,
    val isReliableTiming: Boolean = true
) {
    val endTimeMs: Long get() = timestampMs + durationMs.coerceAtLeast(0L)
}

@Immutable
data class LyricWord(
    val timestampMs: Long,
    val durationMs: Long = 0L,
    val text: String,
    val syllables: List<LyricSyllable> = emptyList(),
    val language: String? = null,
    val isReliableTiming: Boolean = true
) {
    val endTimeMs: Long get() = timestampMs + durationMs.coerceAtLeast(0L)
}

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val durationMs: Long = 0L,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val romanization: String? = null,
    val agent: String? = null,
    val isBackground: Boolean = false,
    val isReliableDuration: Boolean = false
) {
    val endTimeMs: Long get() = timestampMs + durationMs.coerceAtLeast(0L)
}

@Immutable
data class TrackLyrics(
    val trackId: String,
    val title: String,
    val artist: String,
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val provider: String = "",
    val language: String? = null,
    val hasWordTiming: Boolean = false,
    val hasSyllableTiming: Boolean = false,
    val hasTranslation: Boolean = false,
    val hasRomanization: Boolean = false,
    val songWriters: List<String> = emptyList()
)

