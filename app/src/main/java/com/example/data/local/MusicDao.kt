package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // Catalog metadata must never replace persisted user choices or listening history.
    @Transaction
    suspend fun mergeTrackMetadata(track: TrackEntity) {
        val existing = getTrackById(track.id)
        val merged = if (existing == null) track else track.copy(
            isLiked = existing.isLiked,
            isCached = existing.isCached || track.isCached,
            lastPlayedAt = existing.lastPlayedAt,
            addedAt = existing.addedAt
        )
        if (merged != existing) insertOrUpdateTrack(merged)
    }

    @Transaction
    suspend fun mergeTracksMetadata(tracks: List<TrackEntity>) {
        val rows = tracks.distinctBy { it.id }
        val existing = rows.map { it.id }.chunked(900).flatMap { getTracksByIds(it) }.associateBy { it.id }
        val changed = rows.mapNotNull { track ->
            val local = existing[track.id]
            val merged = if (local == null) track else track.copy(isLiked = local.isLiked,
                isCached = local.isCached || track.isCached, lastPlayedAt = local.lastPlayedAt, addedAt = local.addedAt)
            merged.takeIf { it != local }
        }
        if (changed.isNotEmpty()) insertOrUpdateTracks(changed)
    }

    @Transaction
    suspend fun recordTrackPlayed(track: TrackEntity, timestamp: Long) {
        mergeTrackMetadata(track)
        updateLastPlayed(track.id, timestamp)
    }

    @Transaction
    suspend fun toggleTrackFavorite(track: TrackEntity): Boolean {
        val existing = getTrackById(track.id)
        val liked = !(existing?.isLiked ?: track.isLiked)
        if (existing == null) insertOrUpdateTrack(track.copy(isLiked = liked))
        else updateFavorite(track.id, liked)
        return liked
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE isLiked = 1 ORDER BY addedAt DESC")
    fun getFavoriteTracks(): Flow<List<TrackEntity>>

    // Browser status needs identities, not every artwork/stream/metadata column.
    @Query("SELECT id FROM tracks WHERE isLiked = 1 ORDER BY addedAt DESC")
    fun observeFavoriteTrackIds(): Flow<List<String>>

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

    @Query("SELECT * FROM tracks WHERE id IN (:trackIds)")
    suspend fun getTracksByIds(trackIds: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id IN (:trackIds)")
    fun observeTracksByIds(trackIds: List<String>): Flow<List<TrackEntity>>

    @Query("UPDATE tracks SET isLiked = :isLiked WHERE id = :trackId")
    suspend fun updateFavorite(trackId: String, isLiked: Boolean)

    @Query("UPDATE tracks SET lastPlayedAt = :timestamp WHERE id = :trackId")
    suspend fun updateLastPlayed(trackId: String, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Transaction
    suspend fun deletePlaylist(playlistId: Long) {
        deletePlaylistTrackRefs(playlistId)
        deletePlaylistRow(playlistId)
    }

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun deletePlaylistTrackRefs(playlistId: Long)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylistRow(playlistId: Long)

    @Query("DELETE FROM playlist_track_cross_ref WHERE NOT EXISTS (SELECT 1 FROM playlists p WHERE p.playlistId = playlist_track_cross_ref.playlistId) OR NOT EXISTS (SELECT 1 FROM tracks t WHERE t.id = playlist_track_cross_ref.trackId)")
    suspend fun removeOrphanPlaylistTrackRefs()

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

    @Query("SELECT * FROM playlist_track_cross_ref ORDER BY playlistId, orderIndex, trackId")
    suspend fun getAllPlaylistCrossRefsSync(): List<PlaylistTrackCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrackRefs(refs: List<PlaylistTrackCrossRef>)

    // Backup import must fail atomically on unexpected collisions, never delete and replace rows.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRestoredPlaylists(playlists: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRestoredTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRestoredPlaylistTrackRefs(refs: List<PlaylistTrackCrossRef>)

    @Transaction
    suspend fun deleteAllPlaylists() {
        deleteAllPlaylistCrossRefs()
        deleteAllPlaylistRows()
    }

    @Query("DELETE FROM playlists")
    suspend fun deleteAllPlaylistRows()

    @Query("DELETE FROM playlist_track_cross_ref")
    suspend fun deleteAllPlaylistCrossRefs()
}
