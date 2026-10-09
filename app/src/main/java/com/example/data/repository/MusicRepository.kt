package com.example.data.repository

import android.content.Context
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.MusicDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.remote.AudioRoutingService
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.MusicDataSource
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeMusicApiService
import com.example.playback.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.example.data.local.UserProfile
import com.example.data.model.CountryData
import com.example.recommendation.AlbumArtDeduplicator
import com.example.recommendation.AlbumArtDeduplicator.distinctAlbumAndCover
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.util.Log

data class SearchResultCategory(
    val topResult: MusicTrack? = null,
    val exactMatches: List<MusicTrack> = emptyList(),
    val recommendedTracks: List<MusicTrack> = emptyList(),
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

class MusicRepository(
    private val musicDao: MusicDao,
    private val context: Context? = null
) {

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

        // HomeScreen Audio Feeds & Recommendation Engine:
        // Fetched EXCLUSIVELY from YouTube Music API per strict architectural rules
        val (trendingSongs, profileHits) = coroutineScope {
            val trendingDeferred = async {
                try {
                    YouTubeMusicApiService.getTrendingSongs(limit = 25)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val profileDeferred = async {
                try {
                    if (profile != null && (profile.country.isNotBlank() || profile.languages.isNotEmpty())) {
                        YouTubeMusicApiService.getTrendingSongsForProfile(
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
            musicDao.getTracksByLanguageSync("Punjabi", limit * 2)
        } catch (_: Exception) {
            emptyList()
        }
        val roomDiversified = roomPunjabi
            .map { it.toMusicTrack().copy(isLiked = likedIds.contains(it.id)) }
            .distinctAlbumAndCover(limit)

        if (roomDiversified.size >= 8) {
            cachedPunjabiTracks = roomDiversified
            return@withContext roomDiversified
        }

        // 2. Fetch online across diverse Punjabi artists exclusively via YouTube Music API
        val queries = listOf("Diljit Dosanjh hits", "Sidhu Moose Wala hits", "Karan Aujla hits", "AP Dhillon hits", "Amrinder Gill hits", "Top Punjabi Hits")
        val onlineResults = coroutineScope {
            queries.map { q ->
                async {
                    try {
                        YouTubeMusicApiService.searchSongs(q, limit = 8)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val diversifiedOnline = AlbumArtDeduplicator.interleaveAndDiversify(onlineResults, limit)

        val finalTracks = if (diversifiedOnline.isNotEmpty()) {
            val entities = diversifiedOnline.map { TrackEntity.fromMusicTrack(it).copy(isCached = true, language = "Punjabi") }
            try { musicDao.insertOrUpdateTracks(entities) } catch (_: Exception) {}
            diversifiedOnline.map { it.copy(isLiked = likedIds.contains(it.id)) }
        } else if (roomDiversified.isNotEmpty()) {
            roomDiversified
        } else {
            MusicDataSource.curatedTracks.filter {
                it.language.equals("Punjabi", ignoreCase = true) ||
                it.genre.contains("Punjabi", ignoreCase = true) ||
                it.artist.contains("Diljit", ignoreCase = true) ||
                it.artist.contains("Sidhu", ignoreCase = true)
            }.ifEmpty { MusicDataSource.curatedTracks }.distinctAlbumAndCover(limit)
        }
        cachedPunjabiTracks = finalTracks
        finalTracks
    }

    suspend fun getEraHits(limit: Int = 15, forceRefresh: Boolean = false): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedEraTracks?.let { return@withContext it }
        }
        val likedIds = getLikedTrackIds()

        // Fetch online via YouTube Music API
        val queries = listOf("90s Bollywood Classics", "2000s Bollywood Hits", "Retro Golden Hits", "Kumar Sanu hits", "Udit Narayan hits", "Sonu Nigam hits", "90s Nostalgia hits")
        val onlineResults = coroutineScope {
            queries.map { q ->
                async {
                    try {
                        YouTubeMusicApiService.searchSongs(q, limit = 8)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val diversified = AlbumArtDeduplicator.interleaveAndDiversify(onlineResults, limit)

        val finalTracks = if (diversified.isNotEmpty()) {
            val entities = diversified.map { TrackEntity.fromMusicTrack(it).copy(isCached = true, genre = "Retro") }
            try { musicDao.insertOrUpdateTracks(entities) } catch (_: Exception) {}
            diversified.map { it.copy(isLiked = likedIds.contains(it.id)) }
        } else {
            val roomCached = try { musicDao.getCachedTracksSync(30).filter { it.genre.equals("Retro", ignoreCase = true) } } catch (_: Exception) { emptyList() }
            if (roomCached.isNotEmpty()) {
                roomCached.map { it.toMusicTrack().copy(isLiked = likedIds.contains(it.id)) }
            } else {
                MusicDataSource.curatedTracks.shuffled().distinctAlbumAndCover(limit)
            }
        }
        cachedEraTracks = finalTracks
        finalTracks
    }

    suspend fun getYtmQuickPicks(limit: Int = 15, forceRefresh: Boolean = false): List<MusicTrack> = withContext(Dispatchers.IO) {
        val likedIds = getLikedTrackIds()
        val queries = listOf("Trending Music Hits", "Global Top 50 Songs", "Viral Hits Today", "Quick Picks Songs")
        val onlineResults = coroutineScope {
            queries.map { q ->
                async {
                    try {
                        YouTubeMusicApiService.searchSongs(q, limit = 8)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        val diversified = AlbumArtDeduplicator.interleaveAndDiversify(onlineResults, limit)
        if (diversified.isNotEmpty()) {
            val entities = diversified.map { TrackEntity.fromMusicTrack(it).copy(isCached = true) }
            try { musicDao.insertOrUpdateTracks(entities) } catch (_: Exception) {}
            diversified.map { it.copy(isLiked = likedIds.contains(it.id)) }
        } else {
            val roomCached = try { musicDao.getCachedTracksSync(limit) } catch (_: Exception) { emptyList() }
            if (roomCached.isNotEmpty()) {
                roomCached.map { it.toMusicTrack().copy(isLiked = likedIds.contains(it.id)) }
            } else {
                MusicDataSource.curatedTracks.take(limit)
            }
        }
    }

    suspend fun getCountryAndLanguageHits(
        profile: UserProfile?,
        limit: Int = 15,
        forceRefresh: Boolean = false
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val likedIds = getLikedTrackIds()
        val country = profile?.country?.ifBlank { "Global" } ?: "Global"
        val languages = profile?.languages?.filter { it.isNotBlank() }?.ifEmpty { listOf("Hindi", "English") } ?: listOf("Hindi", "English")

        val queries = mutableListOf<String>()
        for (lang in languages.take(3)) {
            queries.add("$lang Top Hits $country")
            queries.add("Top $lang Trending Songs")
        }
        if (queries.isEmpty()) {
            queries.add("Top Hits $country")
        }

        val onlineResults = coroutineScope {
            queries.map { q ->
                async {
                    try {
                        OnlineMusicApiService.searchSongs(q, limit = 8).ifEmpty {
                            YouTubeMusicApiService.searchSongs(q, limit = 8)
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val diversified = AlbumArtDeduplicator.interleaveAndDiversify(onlineResults, limit)
        if (diversified.isNotEmpty()) {
            val entities = diversified.map { TrackEntity.fromMusicTrack(it).copy(isCached = true, language = languages.firstOrNull() ?: "Hindi") }
            try { musicDao.insertOrUpdateTracks(entities) } catch (_: Exception) {}
            diversified.map { it.copy(isLiked = likedIds.contains(it.id)) }
        } else {
            val roomCached = try {
                musicDao.getTracksByLanguageSync(languages.firstOrNull() ?: "Hindi", limit)
            } catch (_: Exception) { emptyList() }
            if (roomCached.isNotEmpty()) {
                roomCached.map { it.toMusicTrack().copy(isLiked = likedIds.contains(it.id)) }
            } else {
                MusicDataSource.curatedTracks.filter { t -> languages.any { l -> t.language.contains(l, ignoreCase = true) } }.ifEmpty { MusicDataSource.curatedTracks }.take(limit)
            }
        }
    }

    private val regionalTrendingCache = ConcurrentHashMap<String, List<MusicTrack>>()

    fun getCachedRegionalTrending(country: String): List<MusicTrack>? {
        val cleanCountry = country.trim().ifBlank { "India" }
        return regionalTrendingCache[cleanCountry.lowercase()]?.takeIf { it.isNotEmpty() }
    }

    /**
     * Genuine regional trending / chart source for selected country.
     * When selected country is India: fetches JioSaavn's official editorial chart "India Superhits Top 50" (50 ranked songs).
     * For other countries: loads genuine regional chartbusters via YouTube Music / JioSaavn using authentic country queries.
     * Preserves provider's ranking, excludes filler, separates caches per country.
     */
    suspend fun getRegionalTrending(
        country: String,
        countryCode: String = "IN",
        limit: Int = 20,
        forceRefresh: Boolean = false
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val cleanCountry = country.trim().ifBlank { "India" }
        val cacheKey = cleanCountry.lowercase()

        if (!forceRefresh) {
            regionalTrendingCache[cacheKey]?.let { cached ->
                if (cached.isNotEmpty()) return@withContext cached
            }
        }

        val isIndia = cleanCountry.equals("India", ignoreCase = true) || countryCode.equals("IN", ignoreCase = true)
        val likedIds = getLikedTrackIds()

        if (isIndia) {
            // Priority 1: Genuine JioSaavn "India Superhits Top 50" editorial chart
            try {
                val chartSongs = fetchJioSaavnIndiaSuperhitsChart()
                if (chartSongs.isNotEmpty()) {
                    val result = chartSongs.take(limit).map { it.copy(isLiked = likedIds.contains(it.id)) }
                    regionalTrendingCache[cacheKey] = result
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w("MusicRepository", "Failed to fetch JioSaavn India Superhits chart: ${e.message}")
            }

            // Fallback for India if playlist detail is temporarily unreachable: top ranked search for Indian chartbusters
            try {
                val queryResults = OnlineMusicApiService.searchSongs("India Superhits Top 50", limit = limit)
                if (queryResults.isNotEmpty()) {
                    val result = queryResults.take(limit).map { it.copy(isLiked = likedIds.contains(it.id)) }
                    regionalTrendingCache[cacheKey] = result
                    return@withContext result
                }
            } catch (_: Exception) {}
        } else {
            // For other countries: Query authentic regional chart sources
            val famousQueries = CountryData.getFamousMusicQueriesForCountry(cleanCountry)
            val queriesToTry = if (famousQueries.isNotEmpty()) famousQueries.take(3) else listOf("Top 50 $cleanCountry", "Top Hits $cleanCountry")

            for (q in queriesToTry) {
                try {
                    val ytResults = YouTubeMusicApiService.searchSongs(q, limit = limit)
                    if (ytResults.isNotEmpty()) {
                        val result = ytResults.take(limit).map { it.copy(isLiked = likedIds.contains(it.id)) }
                        regionalTrendingCache[cacheKey] = result
                        return@withContext result
                    }
                } catch (e: Exception) {
                    Log.w("MusicRepository", "Failed query $q for $cleanCountry: ${e.message}")
                }
            }
        }

        emptyList()
    }

    private fun fetchJioSaavnIndiaSuperhitsChart(): List<MusicTrack> {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        // JioSaavn Editorial Chart ID for "India Superhits Top 50"
        val url = "https://www.jiosaavn.com/api.php?__call=playlist.getDetails&_format=json&listid=1134543272"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val songsArray = json.optJSONArray("songs") ?: return emptyList()
        val tracks = mutableListOf<MusicTrack>()
        for (i in 0 until songsArray.length()) {
            val item = songsArray.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val title = item.optString("song", "")
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").trim()
            val artist = item.optString("primary_artists", "").ifBlank { item.optString("singers", "Various Artists") }
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'").trim()
            val album = item.optString("album", "").replace("&quot;", "\"").replace("&amp;", "&").trim()
            val rawImage = item.optString("image", "")
            val cleanImage = rawImage.replace("\\/", "/").replace("150x150.jpg", "500x500.jpg")
            val encMediaUrl = item.optString("encrypted_media_url", "")
            val audioUrl = OnlineMusicApiService.decryptMediaUrl(encMediaUrl) ?: item.optString("media_preview_url", "")
            if (audioUrl.isBlank()) continue
            val durationSec = item.optString("duration", "210").toLongOrNull() ?: 210L
            val durationMs = durationSec * 1000L
            val language = item.optString("language", "Hindi").replaceFirstChar { it.uppercase() }

            tracks.add(
                MusicTrack(
                    id = "chart_in_$id",
                    title = title.ifBlank { "Track $id" },
                    artist = artist.ifBlank { "Various Artists" },
                    album = album.ifBlank { "India Superhits Top 50" },
                    durationMs = durationMs,
                    coverUrl = cleanImage,
                    audioUrl = audioUrl,
                    language = language,
                    source = "JioSaavn Official Chart",
                    bitrateKbps = 320,
                    qualityBadge = "HD • 320 kbps"
                )
            )
        }
        return tracks
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
        val entity = if (existing != null) {
            existing.copy(lastPlayedAt = now)
        } else {
            TrackEntity.fromMusicTrack(track, lastPlayedAt = now)
        }
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

    suspend fun search(query: String, context: android.content.Context? = null): SearchResultCategory {
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

        val (routedOnlineTracks, ytTracks) = coroutineScope {
            val routedDeferred = async(Dispatchers.IO) {
                val ctx = context ?: this@MusicRepository.context
                if (ctx != null) {
                    try {
                        val routed = com.example.data.remote.AudioRoutingService.resolveSearchTracks(ctx, trimmed, limit = 25)
                        routed.tracks
                    } catch (e: Exception) {
                        OnlineMusicApiService.searchSongs(trimmed, limit = 25)
                    }
                } else {
                    try {
                        OnlineMusicApiService.searchSongs(trimmed, limit = 25)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            val ytDeferred = async(Dispatchers.IO) {
                try {
                    YouTubeMusicApiService.searchSongs(trimmed, limit = 25)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            Pair(routedDeferred.await(), ytDeferred.await())
        }

        val onlineJioTracks = routedOnlineTracks

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

        // 5. Intelligent Recommendations based on search query/top track:
        // Recommends 5 to 6 songs according to mood, language, genre, vibe and artist,
        // strictly avoiding same song names or duplicates as requested by user.
        val targetTrack = top ?: exactMatches.firstOrNull() ?: allSongs.firstOrNull()
        val recommendedTracks = if (targetTrack != null) {
            val engine = com.example.recommendation.RecommendationEngine()
            val candidatePool = (similarTypeSongs + artistSongs + allSongs + MusicDataSource.curatedTracks).distinctBy { it.id }
            val excluded = (exactMatches.map { it.id } + targetTrack.id).toSet()

            val scored = engine.evaluateAndScoreCandidates(
                currentTrack = targetTrack,
                candidatePool = candidatePool,
                excludedIds = excluded
            )

            scored.map { it.track }
                .filter { cand ->
                    cand.id != targetTrack.id &&
                    !com.example.recommendation.RecommendationEngine.isSameSongOrVariant(cand.title, targetTrack.title) &&
                    exactMatches.none { com.example.recommendation.RecommendationEngine.isSameSongOrVariant(cand.title, it.title) }
                }
                .take(6)
        } else {
            emptyList()
        }

        val categoryResult = SearchResultCategory(
            topResult = top,
            exactMatches = exactMatches,
            recommendedTracks = recommendedTracks,
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
     * Unified Audio Layer: Resolves a playable stream URL enforcing the 3-Tier source fallback routing:
     *
     * SCENARIO A (Hi-Res Lossless Enabled):
     *   Tier 1: Internet Archive API (verified lossless FLAC/WAV/AIFF/ALAC/APE/WavPack/DSD stream)
     *   Tier 2: JioSaavn API (320kbps CD-quality stream)
     *   Tier 3: YouTube Music / audio fallback stream
     *
     * SCENARIO B (Lossy Streaming):
     *   Tier 1: JioSaavn API (320kbps stream)
     *   Tier 2: YouTube Music / audio fallback stream
     */
    suspend fun resolvePlayableTrack(track: MusicTrack): MusicTrack = withContext(Dispatchers.IO) {
        playableTrackCache[track.id]?.let { return@withContext it }

        if (track.audioUrl.isNotBlank() && !track.audioUrl.startsWith("yt_") && !track.audioUrl.contains("placeholder")) {
            return@withContext track
        }

        val cleanTitle = track.title
            .replace(Regex("(?i)\\b(official\\s*(video|audio)?|lyric\\s*video|full\\s*song|video|audio|remix|hd|4k|hq)\\b"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .trim()
        val cleanArtist = track.artist.split(",", "&", "feat.", "ft.", "•", "/").first().trim()

        val query = if (cleanTitle.isNotBlank() && cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle

        val selectedQuality = context?.let { AudioQualityPreferences.getSelectedQuality(it) } ?: AudioQuality.ULTRA_HD_320

        if (selectedQuality == AudioQuality.HI_RES_LOSSLESS) {
            // Tier 1: Internet Archive API (Lossless verification)
            try {
                val losslessCandidates = InternetArchiveApiService.searchLosslessTracks(query, maxResults = 3)
                val bestLossless = losslessCandidates.firstOrNull { it.audioUrl.isNotBlank() }
                if (bestLossless != null) {
                    val resolved = track.copy(
                        audioUrl = bestLossless.audioUrl,
                        bitrateKbps = bestLossless.bitrateKbps,
                        qualityBadge = "Hi-Res Lossless",
                        isLossless = true
                    )
                    playableTrackCache[track.id] = resolved
                    return@withContext resolved
                }
            } catch (_: Exception) {}

            // Tier 2: JioSaavn API Fallback
            try {
                val candidates = OnlineMusicApiService.searchSongs(query, limit = 5)
                val bestCandidate = candidates.firstOrNull { it.audioUrl.isNotBlank() }
                    ?: OnlineMusicApiService.searchSongs(cleanTitle, limit = 5).firstOrNull { it.audioUrl.isNotBlank() }
                if (bestCandidate != null) {
                    val resolved = track.copy(
                        audioUrl = bestCandidate.audioUrl,
                        bitrateKbps = bestCandidate.bitrateKbps,
                        qualityBadge = bestCandidate.qualityBadge,
                        isLossless = false
                    )
                    playableTrackCache[track.id] = resolved
                    return@withContext resolved
                }
            } catch (_: Exception) {}

            // Tier 3: YouTube Music / curated audio stream fallback
            val fallback = MusicDataSource.curatedTracks.firstOrNull()?.audioUrl ?: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
            val resolved = track.copy(
                audioUrl = fallback,
                qualityBadge = "HQ • 256 kbps",
                isLossless = false
            )
            playableTrackCache[track.id] = resolved
            return@withContext resolved
        } else {
            // SCENARIO B: Standard / High / Ultra HD (Lossy Streaming)
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
                    qualityBadge = bestCandidate.qualityBadge,
                    isLossless = false
                )
            } else {
                val fallback = MusicDataSource.curatedTracks.firstOrNull()?.audioUrl ?: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
                track.copy(
                    audioUrl = fallback,
                    qualityBadge = "HQ • 256 kbps",
                    isLossless = false
                )
            }

            playableTrackCache[track.id] = resolved
            return@withContext resolved
        }
    }

    suspend fun getTracksByGenre(genre: String): List<MusicTrack> {
        val likedEntities = try {
            musicDao.getFavoriteTracks().first()
        } catch (e: Exception) {
            emptyList()
        }
        val likedIds = likedEntities.map { it.id }.toSet()

        // Feeds exclusively from YouTube Music API per architectural rules
        val onlineGenreTracks = try {
            YouTubeMusicApiService.getGenreTracks(genre, limit = 25)
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
