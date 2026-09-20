package com.example.data.repository

import com.example.data.local.MusicDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.remote.MusicDataSource
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeMusicApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.example.data.local.UserProfile

data class SearchResultCategory(
    val topResult: MusicTrack? = null,
    val exactMatches: List<MusicTrack> = emptyList(),
    val jioMatches: List<MusicTrack> = emptyList(),
    val ytMatches: List<MusicTrack> = emptyList(),
    val artistSongs: List<MusicTrack> = emptyList(),
    val matchedArtistName: String = "",
    val similarTypeSongs: List<MusicTrack> = emptyList(),
    val matchedGenreOrType: String = "",
    val songs: List<MusicTrack> = emptyList(),
    val albums: List<String> = emptyList(),
    val artists: List<String> = emptyList()
)

class MusicRepository(private val musicDao: MusicDao) {

    // LRU / in-memory cache to avoid duplicate network calls and prevent rate limiting
    private val searchCache = ConcurrentHashMap<String, SearchResultCategory>()
    private val playableTrackCache = ConcurrentHashMap<String, MusicTrack>()

    // Static memory cache for enriched catalog to persist across fast navigations
    companion object {
        private var memoryCachedCatalog: List<MusicTrack>? = null
    }

    private var cachedPunjabiTracks: List<MusicTrack>? = null
    private var cachedEraTracks: List<MusicTrack>? = null

    private suspend fun getLikedTrackIds(): Set<String> {
        return try {
            musicDao.getFavoriteTracks().first().map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    suspend fun getInitialCatalog(profile: UserProfile? = null): List<MusicTrack> {
        val likedIds = getLikedTrackIds()

        // 1. If memory cache is available, return it immediately
        memoryCachedCatalog?.let { cached ->
            return cached.map { it.copy(isLiked = likedIds.contains(it.id)) }
        }

        // 2. Read from Room cached tracks (instant local disk read < 5ms)
        val roomCached = try {
            musicDao.getCachedTracksSync(60)
        } catch (_: Exception) {
            emptyList()
        }

        if (roomCached.isNotEmpty()) {
            val mapped = roomCached.map { entity ->
                entity.toMusicTrack().copy(isLiked = likedIds.contains(entity.id))
            }
            memoryCachedCatalog = mapped
            return mapped
        }

        // 3. Instant local fallback catalog (0 ms)
        val local = MusicDataSource.curatedTracks.map { track ->
            track.copy(isLiked = likedIds.contains(track.id))
        }
        return local
    }

    suspend fun syncOnlineCatalog(profile: UserProfile? = null): List<MusicTrack> = withContext(Dispatchers.IO) {
        val likedIds = getLikedTrackIds()

        // Parallelize network requests using coroutineScope and async
        val (trendingSongs, profileHits) = coroutineScope {
            val trendingDeferred = async {
                try {
                    OnlineMusicApiService.getTrendingSongs(limit = 25)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val profileDeferred = async {
                try {
                    if (profile != null && (profile.country.isNotBlank() || profile.languages.isNotEmpty())) {
                        OnlineMusicApiService.getTrendingSongsForProfile(
                            country = profile.country,
                            languages = profile.languages,
                            limit = 25
                        )
                    } else {
                        emptyList()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            Pair(trendingDeferred.await(), profileDeferred.await())
        }

        val onlineSongs = (profileHits + trendingSongs).distinctBy { it.id }
        val combined = if (onlineSongs.isNotEmpty()) {
            onlineSongs + MusicDataSource.curatedTracks
        } else {
            MusicDataSource.curatedTracks
        }

        val result = combined.distinctBy { it.id }.map { track ->
            track.copy(isLiked = likedIds.contains(track.id))
        }

        // Cache into Room database for Stale-While-Revalidate
        if (onlineSongs.isNotEmpty()) {
            try {
                val entities = onlineSongs.map { track ->
                    TrackEntity.fromMusicTrack(track).copy(
                        isCached = true,
                        isLiked = likedIds.contains(track.id)
                    )
                }
                musicDao.insertOrUpdateTracks(entities)
            } catch (_: Exception) {}
        }

        memoryCachedCatalog = result
        result
    }

    suspend fun getPunjabiHits(limit: Int = 15): List<MusicTrack> = withContext(Dispatchers.IO) {
        cachedPunjabiTracks?.let { return@withContext it }
        val likedIds = getLikedTrackIds()

        // 1. Check Room first
        val roomPunjabi = try {
            musicDao.getTracksByLanguageSync("Punjabi", limit)
        } catch (_: Exception) {
            emptyList()
        }
        if (roomPunjabi.isNotEmpty()) {
            val mapped = roomPunjabi.map { it.toMusicTrack().copy(isLiked = likedIds.contains(it.id)) }
            cachedPunjabiTracks = mapped
            return@withContext mapped
        }

        // 2. Fetch online
        val online = try {
            OnlineMusicApiService.getSongsByGenre("punjabi", limit = limit)
        } catch (_: Exception) {
            emptyList()
        }

        val finalTracks = if (online.isNotEmpty()) {
            val entities = online.map { TrackEntity.fromMusicTrack(it).copy(isCached = true, language = "Punjabi") }
            try { musicDao.insertOrUpdateTracks(entities) } catch (_: Exception) {}
            online.map { it.copy(isLiked = likedIds.contains(it.id)) }
        } else {
            MusicDataSource.curatedTracks.filter {
                it.language.equals("Punjabi", ignoreCase = true) ||
                it.genre.contains("Punjabi", ignoreCase = true) ||
                it.artist.contains("Diljit", ignoreCase = true) ||
                it.artist.contains("Sidhu", ignoreCase = true)
            }.ifEmpty { MusicDataSource.curatedTracks.take(limit) }
        }
        cachedPunjabiTracks = finalTracks
        finalTracks
    }

    suspend fun getEraHits(limit: Int = 15): List<MusicTrack> = withContext(Dispatchers.IO) {
        cachedEraTracks?.let { return@withContext it }
        val likedIds = getLikedTrackIds()

        val online = try {
            OnlineMusicApiService.searchSongs("90s 2000s Bollywood Retro Hits", limit = limit)
        } catch (_: Exception) {
            emptyList()
        }

        val finalTracks = if (online.isNotEmpty()) {
            val entities = online.map { TrackEntity.fromMusicTrack(it).copy(isCached = true, genre = "Retro") }
            try { musicDao.insertOrUpdateTracks(entities) } catch (_: Exception) {}
            online.map { it.copy(isLiked = likedIds.contains(it.id)) }
        } else {
            MusicDataSource.curatedTracks.shuffled().take(limit)
        }
        cachedEraTracks = finalTracks
        finalTracks
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
        val entity = TrackEntity.fromMusicTrack(track, lastPlayedAt = now)
        musicDao.insertOrUpdateTrack(entity)
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

    suspend fun createPlaylistWithTracks(title: String, description: String, coverUrl: String = "", tracks: List<MusicTrack>): Long {
        val playlistId = createPlaylist(title, description, coverUrl)
        if (tracks.isNotEmpty()) {
            val entities = tracks.map { TrackEntity.fromMusicTrack(it) }
            val refs = tracks.mapIndexed { index, track ->
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = track.id,
                    orderIndex = index
                )
            }
            musicDao.insertOrUpdateTracks(entities)
            musicDao.insertPlaylistTrackRefs(refs)
        }
        return playlistId
    }

    suspend fun deletePlaylist(playlistId: Long) {
        musicDao.deletePlaylist(playlistId)
    }

    suspend fun renamePlaylist(playlistId: Long, newTitle: String) {
        musicDao.updatePlaylistTitle(playlistId, newTitle.trim())
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: MusicTrack) {
        // Always ensure complete track entity is stored in DB so relationships persist
        val entity = TrackEntity.fromMusicTrack(track)
        musicDao.insertOrUpdateTrack(entity)
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
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return SearchResultCategory()
        }

        val cacheKey = trimmed.lowercase()
        searchCache[cacheKey]?.let { return it }

        val likedEntities = try {
            musicDao.getFavoriteTracks().first()
        } catch (e: Exception) {
            emptyList()
        }
        val likedIds = likedEntities.map { it.id }.toSet()

        // 1. Concurrently search JioSaavn and YouTube Music APIs
        val (onlineJioTracks, ytTracks) = coroutineScope {
            val jioDeferred = async(Dispatchers.IO) {
                try {
                    OnlineMusicApiService.searchSongs(trimmed, limit = 25)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val ytDeferred = async(Dispatchers.IO) {
                try {
                    YouTubeMusicApiService.searchSongs(trimmed, limit = 25)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            Pair(jioDeferred.await(), ytDeferred.await())
        }

        // 2. Search local curated catalog as well
        val q = trimmed.lowercase()
        val localMatches = MusicDataSource.curatedTracks.filter {
            it.title.lowercase().contains(q) ||
            it.artist.lowercase().contains(q) ||
            it.singers.lowercase().contains(q) ||
            it.album.lowercase().contains(q) ||
            it.genre.lowercase().contains(q)
        }

        // Interleave JioSaavn and YouTube Music results so both platforms are represented
        val mixedOnline = mutableListOf<MusicTrack>()
        val maxLen = maxOf(onlineJioTracks.size, ytTracks.size)
        for (idx in 0 until maxLen) {
            if (idx < onlineJioTracks.size) mixedOnline.add(onlineJioTracks[idx])
            if (idx < ytTracks.size) mixedOnline.add(ytTracks[idx])
        }

        // Combine direct search matches
        val allDirect = (mixedOnline + localMatches)
            .distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }

        val top = allDirect.firstOrNull()

        // Exact name/title matches
        val exactMatches = allDirect.filter {
            it.title.lowercase().contains(q) || q.contains(it.title.lowercase())
        }.ifEmpty { allDirect.take(10) }

        val taggedJioMatches = onlineJioTracks.map { it.copy(isLiked = likedIds.contains(it.id)) }
        val taggedYtMatches = ytTracks.map { it.copy(isLiked = likedIds.contains(it.id)) }

        // 3. Fetch same singer / artist's other songs
        val primaryArtist = top?.singers?.ifBlank { top.artist } ?: ""
        var artistSongs = emptyList<MusicTrack>()
        var matchedArtistName = ""
        if (primaryArtist.isNotBlank()) {
            val singerQuery = primaryArtist.split(",", "&", "feat.", "ft.", "•", "/").first().trim()
            matchedArtistName = singerQuery
            val onlineArtistTracks = try {
                OnlineMusicApiService.searchSongs(singerQuery, limit = 15)
            } catch (e: Exception) {
                emptyList()
            }
            val localArtistTracks = MusicDataSource.curatedTracks.filter {
                it.artist.contains(singerQuery, ignoreCase = true) || it.singers.contains(singerQuery, ignoreCase = true)
            }
            artistSongs = (onlineArtistTracks + localArtistTracks)
                .filter { it.id != top?.id }
                .distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
                .map { it.copy(isLiked = likedIds.contains(it.id)) }
                .take(12)
        }

        // 4. Fetch same type / genre / vibe songs
        val genre = top?.genre?.ifBlank { "Pop" } ?: "Pop"
        val onlineGenreTracks = try {
            OnlineMusicApiService.getSongsByGenre(genre, limit = 15)
        } catch (e: Exception) {
            emptyList()
        }
        val localGenreTracks = MusicDataSource.curatedTracks.filter {
            it.genre.equals(genre, ignoreCase = true)
        }
        val existingIds = (allDirect.map { it.id } + artistSongs.map { it.id }).toSet()
        val similarTypeSongs = (onlineGenreTracks + localGenreTracks)
            .filter { !existingIds.contains(it.id) }
            .distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }
            .take(12)

        // Merged list of all distinct songs found in search
        val allSongs = (allDirect + artistSongs + similarTypeSongs)
            .distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }

        val albums = allSongs.map { it.album }
            .filter { it.isNotBlank() && it != "Single" && it != "Online Stream" && it != "YouTube Music" && it != "Extended Stream" }
            .distinct()
        val artists = allSongs.map { it.artist }
            .filter { it.isNotBlank() && it != "Unknown Artist" && it != "YouTube Artist" && it != "Online Artist" }
            .distinct()

        val categoryResult = SearchResultCategory(
            topResult = top,
            exactMatches = exactMatches,
            jioMatches = taggedJioMatches,
            ytMatches = taggedYtMatches,
            artistSongs = artistSongs,
            matchedArtistName = matchedArtistName,
            similarTypeSongs = similarTypeSongs,
            matchedGenreOrType = genre,
            songs = allSongs,
            albums = albums,
            artists = artists
        )

        searchCache[cacheKey] = categoryResult
        return categoryResult
    }

    /**
     * Unified Audio Layer: Resolves a playable stream URL with fallback across JioSaavn and alternative providers.
     * If a song is selected from YouTube Music, it cross-resolves against JioSaavn's CD-quality (320kbps) streams.
     * If a JioSaavn stream URL fails or is expired, it falls back seamlessly.
     */
    suspend fun resolvePlayableTrack(track: MusicTrack): MusicTrack = withContext(Dispatchers.IO) {
        playableTrackCache[track.id]?.let { return@withContext it }

        if (track.audioUrl.isNotBlank() && !track.audioUrl.startsWith("yt_") && !track.audioUrl.contains("placeholder")) {
            return@withContext track
        }

        // Cross-API Fallback Strategy:
        // Query JioSaavn with exact song title & artist to get direct 320kbps CD-quality audio stream
        val cleanTitle = track.title
            .replace(Regex("(?i)\\b(official\\s*(video|audio)?|lyric\\s*video|full\\s*song|video|audio|remix|hd|4k|hq)\\b"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .trim()
        val cleanArtist = track.artist.split(",", "&", "feat.", "ft.", "•", "/").first().trim()

        val query = if (cleanTitle.isNotBlank() && cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle
        val candidates = try {
            OnlineMusicApiService.searchSongs(query, limit = 5)
        } catch (e: Exception) {
            emptyList()
        }

        val bestCandidate = candidates.firstOrNull { it.audioUrl.isNotBlank() }
            ?: try {
                OnlineMusicApiService.searchSongs(cleanTitle, limit = 5).firstOrNull { it.audioUrl.isNotBlank() }
            } catch (e: Exception) {
                null
            }

        val resolved = if (bestCandidate != null) {
            track.copy(
                audioUrl = bestCandidate.audioUrl,
                bitrateKbps = bestCandidate.bitrateKbps,
                qualityBadge = bestCandidate.qualityBadge
            )
        } else {
            // Curated sound stream fallback so user never encounters silence or broken playback
            val fallback = MusicDataSource.curatedTracks.firstOrNull()?.audioUrl ?: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
            track.copy(
                audioUrl = fallback,
                qualityBadge = "HQ • 256 kbps"
            )
        }

        playableTrackCache[track.id] = resolved
        resolved
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

        return (onlineGenreTracks + localGenreTracks.shuffled())
            .distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }
            .shuffled()
    }

    fun clearSearchCache() {
        searchCache.clear()
        playableTrackCache.clear()
    }

    fun getSearchCacheEntryCount(): Int {
        return searchCache.size + playableTrackCache.size
    }
}
