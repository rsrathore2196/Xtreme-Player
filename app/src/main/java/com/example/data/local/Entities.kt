package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.data.model.MusicTrack

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
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
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toMusicTrack(): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        audioUrl = audioUrl,
        bitrateKbps = bitrateKbps,
        qualityBadge = qualityBadge,
        genre = genre,
        isLiked = isLiked,
        isCached = isCached
    )

    companion object {
        fun fromMusicTrack(track: MusicTrack, lastPlayedAt: Long = 0L): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            coverUrl = track.coverUrl,
            audioUrl = track.audioUrl,
            bitrateKbps = track.bitrateKbps,
            qualityBadge = track.qualityBadge,
            genre = track.genre,
            isLiked = track.isLiked,
            isCached = track.isCached,
            lastPlayedAt = lastPlayedAt
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0L,
    val title: String,
    val description: String = "",
    val coverUrl: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_track_cross_ref",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index(value = ["trackId"])]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: String,
    val orderIndex: Int = 0
)

data class PlaylistWithTracks(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistTrackCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "trackId"
        )
    )
    val tracks: List<TrackEntity>
)
