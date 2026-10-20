package com.example.data.local

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Ignore
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.ColumnInfo
import com.example.data.model.MusicTrack
import org.json.JSONArray

@Immutable
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
    val addedAt: Long = System.currentTimeMillis(),
    val singers: String = "",
    val writer: String = "",
    val language: String = "",
    val year: String = "",
    val source: String = "HD Stream",
    val isrc: String = "",
    @ColumnInfo(defaultValue = "'[]'") val primaryArtists: String = "[]"
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
        isCached = isCached,
        singers = singers,
        writer = writer,
        language = language,
        year = year,
        source = when {
            source.equals("YouTube Music", ignoreCase = true) || id.startsWith("yt_") -> "Extended Stream"
            source.equals("JioSaavn", ignoreCase = true) || source.isBlank() -> "HD Stream"
            else -> source
        },
        isrc = isrc,
        primaryArtists = runCatching { JSONArray(primaryArtists).let { names ->
            (0 until names.length()).map { names.getString(it) }.filter(String::isNotBlank)
        } }.getOrDefault(emptyList())
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
            lastPlayedAt = lastPlayedAt,
            singers = track.singers,
            writer = track.writer,
            language = track.language,
            year = track.year,
            source = when {
                track.source.equals("YouTube Music", ignoreCase = true) || track.id.startsWith("yt_") -> "Extended Stream"
                track.source.equals("JioSaavn", ignoreCase = true) || track.source.isBlank() -> "HD Stream"
                else -> track.source
            },
            isrc = track.isrc,
            primaryArtists = JSONArray(track.primaryArtists).toString()
        )
    }
}

@Immutable
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0L,
    val title: String,
    val description: String = "",
    val coverUrl: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Immutable
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

@Immutable
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
) {
    // A derived UI snapshot only: Room does not persist it or change the relation schema.
    @get:Ignore
    @delegate:Ignore
    @delegate:Transient
    val musicTracks: List<MusicTrack> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        tracks.map { it.toMusicTrack() }
    }
}
