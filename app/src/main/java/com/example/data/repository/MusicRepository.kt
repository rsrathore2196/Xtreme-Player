package com.example.data.repository

import com.example.data.local.MusicDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.remote.MusicDataSource
import com.example.data.remote.OnlineMusicApiService
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
        val likedEntities = try {
            musicDao.getFavoriteTracks().first()
        } catch (e: Exception) {
            emptyList()
        }
        val likedIds = likedEntities.map { it.id }.toSet()

        // Fetch online trending tracks to enrich the catalog with real streaming music
        val onlineTrending = try {
            OnlineMusicApiService.getTrendingSongs(limit = 25)
        } catch (e: Exception) {
            emptyList()
        }

        val combined = if (onlineTrending.isNotEmpty()) {
            onlineTrending + MusicDataSource.curatedTracks
        } else {
            MusicDataSource.curatedTracks
        }

        return combined.distinctBy { it.id }.map { track ->
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

    suspend fun search(query: String): SearchResultCategory {
        if (query.isBlank()) {
            return SearchResultCategory(
                topResult = null,
                songs = emptyList(),
                albums = emptyList(),
                artists = emptyList()
            )
        }

        val likedEntities = try {
            musicDao.getFavoriteTracks().first()
        } catch (e: Exception) {
            emptyList()
        }
        val likedIds = likedEntities.map { it.id }.toSet()

        // 1. Search online JioSaavn library for any song in the world
        val onlineTracks = try {
            OnlineMusicApiService.searchSongs(query.trim(), limit = 30)
        } catch (e: Exception) {
            emptyList()
        }

        // 2. Search local curated catalog as well
        val q = query.trim().lowercase()
        val localMatches = MusicDataSource.curatedTracks.filter {
            it.title.lowercase().contains(q) ||
            it.artist.lowercase().contains(q) ||
            it.album.lowercase().contains(q) ||
            it.genre.lowercase().contains(q)
        }

        // Combine: Online results first, followed by unique local tracks
        val allMatches = (onlineTracks + localMatches)
            .distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }

        val top = allMatches.firstOrNull()
        val songs = allMatches
        val albums = allMatches.map { it.album }.filter { it.isNotBlank() && it != "Single" && it != "Online Stream" }.distinct()
        val artists = allMatches.map { it.artist }.filter { it.isNotBlank() && it != "Unknown Artist" }.distinct()

        return SearchResultCategory(
            topResult = top,
            songs = songs,
            albums = albums,
            artists = artists
        )
    }

    suspend fun getTracksByGenre(genre: String): List<MusicTrack> {
        val likedEntities = try {
            musicDao.getFavoriteTracks().first()
        } catch (e: Exception) {
            emptyList()
        }
        val likedIds = likedEntities.map { it.id }.toSet()

        val onlineGenreTracks = try {
            OnlineMusicApiService.getSongsByGenre(genre, limit = 25)
        } catch (e: Exception) {
            emptyList()
        }

        val localGenreTracks = if (genre.equals("All", ignoreCase = true)) {
            MusicDataSource.curatedTracks
        } else {
            MusicDataSource.curatedTracks.filter { it.genre.equals(genre, ignoreCase = true) }
        }

        return (onlineGenreTracks + localGenreTracks)
            .distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }
    }
}
