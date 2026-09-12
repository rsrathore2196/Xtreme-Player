package com.example.data.repository

import com.example.data.local.MusicDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.remote.MusicDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class SearchResultCategory(
    val topResult: MusicTrack?,
    val songs: List<MusicTrack>,
    val albums: List<String>,
    val artists: List<String>
)

class MusicRepository(private val musicDao: MusicDao) {

    suspend fun getInitialCatalog(): List<MusicTrack> {
        val likedEntities = musicDao.getFavoriteTracks().first()
        val likedIds = likedEntities.map { it.id }.toSet()
        return MusicDataSource.curatedTracks.map { track ->
            track.copy(isLiked = likedIds.contains(track.id))
        }
    }

    fun getFavoriteTracks(): Flow<List<MusicTrack>> {
        return musicDao.getFavoriteTracks().map { list ->
            list.map { it.toMusicTrack() }
        }
    }

    fun getRecentlyPlayedTracks(): Flow<List<MusicTrack>> {
        return musicDao.getRecentlyPlayedTracks().map { list ->
            list.map { it.toMusicTrack() }
        }
    }

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> {
        return musicDao.getAllPlaylists()
    }

    fun getPlaylistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?> {
        return musicDao.getPlaylistWithTracks(playlistId)
    }

    suspend fun toggleFavorite(track: MusicTrack): Boolean {
        val newLiked = !track.isLiked
        val existing = musicDao.getTrackById(track.id)
        if (existing != null) {
            musicDao.updateFavorite(track.id, newLiked)
        } else {
            val entity = TrackEntity.fromMusicTrack(track.copy(isLiked = newLiked))
            musicDao.insertOrUpdateTrack(entity)
        }
        return newLiked
    }

    suspend fun markTrackPlayed(track: MusicTrack) {
        val now = System.currentTimeMillis()
        val existing = musicDao.getTrackById(track.id)
        if (existing != null) {
            musicDao.updateLastPlayed(track.id, now)
        } else {
            val entity = TrackEntity.fromMusicTrack(track, lastPlayedAt = now)
            musicDao.insertOrUpdateTrack(entity)
        }
    }

    suspend fun createPlaylist(title: String, description: String, coverUrl: String = ""): Long {
        val defaultCover = coverUrl.ifEmpty {
            "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80"
        }
        val entity = PlaylistEntity(
            title = title,
            description = description,
            coverUrl = defaultCover
        )
        return musicDao.insertPlaylist(entity)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        musicDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: MusicTrack) {
        // Ensure track is stored in DB
        val existing = musicDao.getTrackById(track.id)
        if (existing == null) {
            musicDao.insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
        }
        musicDao.insertPlaylistTrackRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = track.id
            )
        )
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        musicDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun search(query: String): SearchResultCategory {
        if (query.isBlank()) {
            return SearchResultCategory(
                topResult = null,
                songs = emptyList(),
                albums = emptyList(),
                artists = emptyList()
            )
        }
        val q = query.trim().lowercase()
        val matchedTracks = MusicDataSource.curatedTracks.filter {
            it.title.lowercase().contains(q) ||
            it.artist.lowercase().contains(q) ||
            it.album.lowercase().contains(q) ||
            it.genre.lowercase().contains(q)
        }

        val top = matchedTracks.firstOrNull()
        val songs = matchedTracks
        val albums = matchedTracks.map { it.album }.distinct()
        val artists = matchedTracks.map { it.artist }.distinct()

        return SearchResultCategory(
            topResult = top,
            songs = songs,
            albums = albums,
            artists = artists
        )
    }
}
