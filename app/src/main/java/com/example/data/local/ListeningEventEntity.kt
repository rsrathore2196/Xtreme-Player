package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.MusicTrack

/**
 * Detailed listening history and user taste event entity inspired by BitChord.
 * Records explicit and implicit user feedback signals with millisecond accuracy.
 */
@Entity(
    tableName = "listening_events",
    indices = [
        Index("trackId"),
        Index("artistName"),
        Index("timestamp"),
        Index("sessionId")
    ]
)
data class ListeningEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: String,
    val artistId: String = "",
    val artistName: String = "",
    val album: String = "",
    val genre: String = "",
    val language: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String = "",
    val playedDurationMs: Long = 0L,
    val trackDurationMs: Long = 0L,
    val completionRatio: Float = 0f,
    val completed: Boolean = false,
    val skipped: Boolean = false,
    val liked: Boolean = false,
    val source: String = "JIOSAAVN",
    val eventType: String = "STARTED" // STARTED, MEANINGFUL_PLAY, COMPLETED, EARLY_SKIP
) {
    companion object {
        fun fromTrack(
            track: MusicTrack,
            sessionId: String,
            playedDurationMs: Long,
            trackDurationMs: Long,
            eventType: String,
            completed: Boolean = false,
            skipped: Boolean = false,
            liked: Boolean = false
        ): ListeningEventEntity {
            val ratio = if (trackDurationMs > 0) {
                (playedDurationMs.toFloat() / trackDurationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            return ListeningEventEntity(
                trackId = track.id,
                artistId = track.artist.trim().lowercase(),
                artistName = if (track.singers.isNotBlank()) track.singers else track.artist,
                album = track.album,
                genre = track.genre,
                language = track.language,
                timestamp = System.currentTimeMillis(),
                sessionId = sessionId,
                playedDurationMs = playedDurationMs,
                trackDurationMs = trackDurationMs,
                completionRatio = ratio,
                completed = completed,
                skipped = skipped,
                liked = liked || track.isLiked,
                source = track.source,
                eventType = eventType
            )
        }
    }
}
