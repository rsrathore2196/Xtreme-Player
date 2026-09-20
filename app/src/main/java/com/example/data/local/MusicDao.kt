package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE isLiked = 1 ORDER BY addedAt DESC")
    fun getFavoriteTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 20")
    fun getRecentlyPlayedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isCached = 1 ORDER BY addedAt DESC")
    fun getCachedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isCached = 1 ORDER BY addedAt DESC LIMIT :limit")
    suspend fun getCachedTracksSync(limit: Int = 100): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE language = :language OR genre LIKE '%' || :language || '%' ORDER BY addedAt DESC LIMIT :limit")
    suspend fun getTracksByLanguageSync(language: String, limit: Int = 30): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrackById(trackId: String): TrackEntity?

    @Query("UPDATE tracks SET isLiked = :isLiked WHERE id = :trackId")
    suspend fun updateFavorite(trackId: String, isLiked: Boolean)

    @Query("UPDATE tracks SET lastPlayedAt = :timestamp WHERE id = :trackId")
    suspend fun updateLastPlayed(trackId: String, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("UPDATE playlists SET title = :newTitle WHERE playlistId = :playlistId")
    suspend fun updatePlaylistTitle(playlistId: Long, newTitle: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrackRef(crossRef: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String)

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylistsSync(): List<PlaylistEntity>

    @Query("SELECT * FROM tracks WHERE isLiked = 1 ORDER BY addedAt DESC")
    suspend fun getAllFavoriteTracksSync(): List<TrackEntity>

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksSync(): List<TrackEntity>

    @Query("SELECT * FROM playlist_track_cross_ref")
    suspend fun getAllPlaylistCrossRefsSync(): List<PlaylistTrackCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrackRefs(refs: List<PlaylistTrackCrossRef>)
}
