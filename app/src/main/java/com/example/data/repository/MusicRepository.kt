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
import com.example.data.local.MusicDatabase
import com.example.data.local.UserProfilePreferences
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.UserTasteProfile
import com.example.performance.BackgroundWorkCoordinator
import com.example.recommendation.DiscoveryRequestCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.remote.catalogRequest
import com.example.playback.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.example.data.local.UserProfile
import com.example.data.model.CountryData
import com.example.recommendation.AlbumArtDeduplicator
import com.example.recommendation.AlbumArtDeduplicator.distinctAlbumAndCover
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

@OptIn(ExperimentalCoroutinesApi::class)
class MusicRepository(
    private val musicDao: MusicDao,
    private val context: Context? = null
) {

    val catalogBrowser = CatalogRepository()
    // Home's owners and workers share this cap, including discovery ranking.
    internal val homeDispatcher = Dispatchers.Default.limitedParallelism(2)
    val lastFmHome by lazy { com.example.recommendation.LastFmHomeRecommendationEngine(this) }

    /** Catalogue lookup only: Last.fm, not either catalogue's search ranking, chooses the song. */
    internal suspend fun lastFmCatalogCandidates(query: String, force: Boolean): List<MusicTrack> = withContext(Dispatchers.IO) {
        val key = "lastfm-recording:${DiscoveryRequestCoordinator.normalize(query)}"
        val hit = discoveryCache.get(key)
        if (hit != null && System.currentTimeMillis() - hit.fetchedAt < if (force) 15_000L else discoveryTtlMs)
            return@withContext hit.tracks
        DiscoveryRequestCoordinator.shared(key, essential = false) {
            val existing = discoveryCache.get(key)
            if (existing != null && System.currentTimeMillis() - existing.fetchedAt < if (force) 15_000L else discoveryTtlMs)
                return@shared existing.tracks
            val found = coroutineScope {
                val jio = async { try { kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    OnlineMusicApiService.searchSongs(query, limit = 8, forceRefresh = force)
                }.orEmpty() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
                val youtube = async { try { kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    YouTubeMusicApiService.searchSongsPage(query, useInnerTubeX = true, forceRefresh = force).tracks.take(10)
                }.orEmpty() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
                jio.await() + youtube.await()
            }
            val result = withContext(homeDispatcher) { HomeRecommendationPolicy.originals(found) }
            if (result.isNotEmpty()) discoveryCache.put(key, DiscoveryCache(result, System.currentTimeMillis()))
            result.ifEmpty { existing?.tracks.orEmpty() }
        }
    }

    internal suspend fun cacheLastFmTracks(tracks: List<MusicTrack>) = withContext(homeDispatcher) {
        val rows = tracks.distinctBy { it.id }.map { TrackEntity.fromMusicTrack(it).copy(isCached = true) }
        if (rows.isNotEmpty()) withContext(Dispatchers.IO) { musicDao.mergeTracksMetadata(rows) }
    }

    // LRU / in-memory cache to avoid duplicate network calls and prevent rate limiting
    private val searchCache = android.util.LruCache<String, SearchResultCategory>(80)
    private val streamResolver = StreamResolver()
    private val chartClient by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }

    // Static memory cache for enriched catalog to persist across fast navigations
    companion object {
        @Volatile private var memoryCachedCatalog: List<MusicTrack>? = null
    }

    private data class DiscoveryCache(val tracks: List<MusicTrack>, val fetchedAt: Long)
    private val discoveryCache = android.util.LruCache<String, DiscoveryCache>(80)
    private val queryLocks = Array(16) { Mutex() }
    private val discoveryTtlMs = 10 * 60_000L
    private val discoveryOwners = Array(8) { Mutex() }
    private val discoveryBatches = android.util.LruCache<String, DiscoveryCache>(16)

    private suspend fun getLikedTrackIds(): Set<String> = withContext(homeDispatcher) {
        withContext(Dispatchers.IO) { musicDao.getFavoriteTracks().first() }.map { it.id }.toSet()
    }

    suspend fun getInitialCatalog(profile: UserProfile? = null): List<MusicTrack> = withContext(homeDispatcher) {
        val liked = getLikedTrackIds()
        val cached = memoryCachedCatalog.orEmpty().ifEmpty {
            withContext(Dispatchers.IO) { musicDao.getCachedTracksSync(250) }.map { it.toMusicTrack() }
        }
        // Discard legacy fabricated YouTube defaults when reading old recommendation caches.
        val corrected = cached.map { track ->
            if (track.id.startsWith("yt_") && track.genre == "Pop" && track.language == "Hindi")
                track.copy(genre = "Unknown", language = "Unknown") else track
        }
        HomeRecommendationPolicy.originals(corrected).map { it.copy(isLiked = it.id in liked) }
    }

    private suspend fun homeProfile(profile: UserProfile? = null): UserProfile = profile ?: withContext(Dispatchers.IO) {
        context?.let { UserProfilePreferences.getUserProfile(it) } ?: UserProfile()
    }

    private suspend fun discoveryQueries(profile: UserProfile, era: Boolean = false): List<String> = withContext(homeDispatcher) {
        val favoriteTracks = withContext(Dispatchers.IO) { musicDao.getFavoriteTracks().first() }.map { it.toMusicTrack() }
        val playedIds = withContext(Dispatchers.IO) {
            context?.let { MusicDatabase.getDatabase(it).listeningEventDao().getRecentActualTrackIds() }.orEmpty()
        }
        val listened = withContext(Dispatchers.IO) { musicDao.getTracksByIds(playedIds) }.map { it.toMusicTrack() }
        val seeds = (favoriteTracks + listened).filter { HomeRecommendationPolicy.known(it.artist) }
        val artists = seeds.flatMap(HomeRecommendationPolicy::artists).distinct().shuffled().take(2)
        val languages = (profile.languages + seeds.map { it.language }).filter(HomeRecommendationPolicy::known).distinct().shuffled().take(2)
        val genres = seeds.map { it.genre }.filter(HomeRecommendationPolicy::known).distinct().shuffled().take(1)
        val queries = if (era) {
            val decades = listOf("1990s", "2000s").shuffled()
            languages.flatMap { language -> decades.map { "$language $it original songs" } }
        } else {
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            artists.map { "$it ${listOf("songs", "singles", "collaborations", "album").random()}" } +
                languages.map { "$it ${genres.firstOrNull().orEmpty()} ${listOf("new releases $year", "original songs", "new artists").random()}" } +
                genres.map { "$it ${profile.country} original songs" }
        }
        queries.ifEmpty { listOf("${profile.country} ${if (era) "1990s original songs" else "new music releases"}") }.distinct().take(4)
    }

    private suspend fun discoverySearch(query: String, force: Boolean, era: Boolean): List<MusicTrack> {
        val key = "$era:${com.example.recommendation.DiscoveryRequestCoordinator.normalize(query)}"
        return queryLocks[(key.hashCode() and Int.MAX_VALUE) % queryLocks.size].withLock {
            val cached = discoveryCache.get(key)
            val age = System.currentTimeMillis() - (cached?.fetchedAt ?: 0)
            // Coalesce simultaneous card requests, even during an explicit refresh.
            if (cached != null && age < if (force) 30_000L else discoveryTtlMs) return@withLock cached.tracks
            val result = try {
                val tracks = com.example.recommendation.CustomRecommendationQueryWrapper.searchDiscovery(query, limit = 35,
                    era = era, forceRefresh = force)
                withContext(homeDispatcher) { HomeRecommendationPolicy.originals(tracks)
                    .filter { !era || (it.year.toIntOrNull() ?: 0) in 1990..2009 } }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            if (result.isNotEmpty()) {
                discoveryCache.put(key, DiscoveryCache(result, System.currentTimeMillis()))
                result
            } else cached?.tracks.orEmpty()
        }
    }

    private suspend fun discover(profile: UserProfile, era: Boolean, limit: Int, force: Boolean,
        deferRoomMergeUntilIdle: Boolean = false): List<MusicTrack> = withContext(homeDispatcher) {
      val liked = getLikedTrackIds()
      val recent = withContext(Dispatchers.IO) { context?.let {
          MusicDatabase.getDatabase(it).listeningEventDao().getRecentActualTrackIds()
      }.orEmpty() }
      val key = "$era|${profile.countryCode}|${profile.languages}|${liked.sorted()}|$recent"
      var freshForRoom = emptyList<TrackEntity>()
      val ranked = discoveryOwners[(key.hashCode() and Int.MAX_VALUE) % discoveryOwners.size].withLock {
        val previous = discoveryBatches.get(key)
        val now = System.currentTimeMillis()
        if (previous != null && now - previous.fetchedAt < if (force) 1_000L else discoveryTtlMs)
            return@withLock previous.tracks.take(limit).map { it.copy(isLiked = it.id in liked) }
        coroutineScope {
        val queries = discoveryQueries(profile, era)
        val groups = arrayOfNulls<List<MusicTrack>>(queries.size)
        val searches = queries.mapIndexed { index, query -> launch {
            groups[index] = discoverySearch(query, force, era)
        } }
        try {
            val refresh = currentCoroutineContext()[com.example.recommendation.DiscoveryRequestCoordinator.HomeRefresh]
            if (refresh != null) {
                // One slow query must not hold every refreshed card hostage. Keep
                // completed groups, and cancel only this refresh's unfinished readers.
                refresh.withBudget(12_000L) { searches.joinAll() }
            } else searches.joinAll()
        } finally {
            searches.forEach { if (!it.isCompleted) it.cancel() }
            searches.joinAll()
        }
        val fresh = HomeRecommendationPolicy.originals(HomeRecommendationPolicy.interleave(groups.map { it.orEmpty() }))
        freshForRoom = fresh.map { TrackEntity.fromMusicTrack(it).copy(isCached = true, isLiked = it.id in liked) }
        val cached = getInitialCatalog(profile).filter { !era || (it.year.toIntOrNull() ?: 0) in 1990..2009 }
        val ranked = HomeRecommendationPolicy.diversify(fresh.ifEmpty { cached }.shuffled(), 160)
            .map { it.copy(isLiked = it.id in liked) }
        if (ranked.isNotEmpty()) discoveryBatches.put(key, DiscoveryCache(ranked, System.currentTimeMillis()))
        ranked.take(limit)
        }
      }
      if (freshForRoom.isNotEmpty()) {
          // Release discovery ownership before optional persistence waits. Radio
          // can reuse the completed batch immediately, including the same key.
          // Home's caller holds no busy lease here; a pull can bypass idle.
          if (deferRoomMergeUntilIdle) {
              val refresh = currentCoroutineContext()[DiscoveryRequestCoordinator.HomeRefresh]
              BackgroundWorkCoordinator.awaitIdle(userRequested = { refresh?.requested?.value == true })
          }
          withContext(Dispatchers.IO) { musicDao.mergeTracksMetadata(freshForRoom) }
      }
      ranked
    }

    suspend fun syncOnlineCatalog(profile: UserProfile? = null): List<MusicTrack> = withContext(Dispatchers.IO) {
        val fresh = discover(homeProfile(profile), era = false, limit = 120, force = false)
        if (fresh.isNotEmpty()) memoryCachedCatalog = fresh
        fresh.ifEmpty { getInitialCatalog(profile) }
    }

    suspend fun getPunjabiHits(limit: Int = 15): List<MusicTrack> = getCountryAndLanguageHits(
        homeProfile().copy(languages = listOf("Punjabi")), limit)

    suspend fun getEraHits(limit: Int = 15, forceRefresh: Boolean = false,
        deferRoomMergeUntilIdle: Boolean = false): List<MusicTrack> = withContext(Dispatchers.IO) {
        discover(homeProfile(), era = true, limit = limit, force = forceRefresh,
            deferRoomMergeUntilIdle = deferRoomMergeUntilIdle)
    }

    suspend fun getYtmQuickPicks(limit: Int = 15, forceRefresh: Boolean = false,
        deferRoomMergeUntilIdle: Boolean = false): List<MusicTrack> = withContext(Dispatchers.IO) {
        discover(homeProfile(), era = false, limit = limit, force = forceRefresh,
            deferRoomMergeUntilIdle = deferRoomMergeUntilIdle)
    }

    suspend fun getCountryAndLanguageHits(profile: UserProfile?, limit: Int = 15,
        forceRefresh: Boolean = false): List<MusicTrack> = withContext(Dispatchers.IO) {
        discover(homeProfile(profile), era = false, limit = limit, force = forceRefresh)
    }

    private val regionalFetchedAt = ConcurrentHashMap<String, Long>()
    private val regionalTrendingCache = ConcurrentHashMap<String, List<MusicTrack>>()
    private val regionalOwners = Array(8) { Mutex() }

    private val chartPreferences by lazy { context?.getSharedPreferences("home_chart_cache_v2", Context.MODE_PRIVATE) }

    private suspend fun saveChart(key: String, tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        regionalTrendingCache[key] = tracks
        val now = System.currentTimeMillis()
        regionalFetchedAt[key] = now
        musicDao.mergeTracksMetadata(tracks.map { TrackEntity.fromMusicTrack(it).copy(isCached = true) })
        chartPreferences?.edit()?.putString(key, org.json.JSONArray(tracks.map { it.id }).toString())
            ?.putLong("${key}_time", now)?.apply()
    }

    fun getCachedRegionalTrending(country: String): List<MusicTrack>? {
        val cleanCountry = country.trim().ifBlank { "India" }
        return regionalTrendingCache[cleanCountry.lowercase()]?.takeIf { it.isNotEmpty() && System.currentTimeMillis() - (regionalFetchedAt[cleanCountry.lowercase()] ?: 0L) < 30 * 60_000L }
    }

    fun getRegionalTrendingFetchedAt(country: String): Long =
        regionalFetchedAt[country.trim().ifBlank { "India" }.lowercase()] ?: 0L

    /**
     * Genuine regional trending / chart source for selected country.
     * When selected country is India: fetches JioSaavn's official editorial chart "India Superhits Top 50" (50 ranked songs).
     * For other countries: resolves the published Apple Music regional chart to playable recordings.
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
      regionalOwners[(cacheKey.hashCode() and Int.MAX_VALUE) % regionalOwners.size].withLock {
        if (regionalTrendingCache[cacheKey].isNullOrEmpty()) {
            val saved = chartPreferences?.getString(cacheKey, null)
            if (saved != null) try {
                val ids = org.json.JSONArray(saved)
                val orderedIds = (0 until ids.length()).map { ids.getString(it) }
                val byId = musicDao.getTracksByIds(orderedIds).associateBy { it.id }
                regionalTrendingCache[cacheKey] = HomeRecommendationPolicy.originals(orderedIds.mapNotNull { byId[it]?.toMusicTrack() })
                regionalFetchedAt[cacheKey] = chartPreferences?.getLong("${cacheKey}_time", 0L) ?: 0L
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }

        if (!forceRefresh) {
            getCachedRegionalTrending(cleanCountry)?.let { cached ->
                if (cached.isNotEmpty()) return@withContext cached.take(limit)
            }
        }

        val isIndia = cleanCountry.equals("India", ignoreCase = true) || countryCode.equals("IN", ignoreCase = true)
        val likedIds = getLikedTrackIds()

        if (isIndia) {
            // Priority 1: Genuine JioSaavn "India Superhits Top 50" editorial chart
            try {
                val chartSongs = com.example.recommendation.DiscoveryRequestCoordinator.shared("chart:jio:IN", essential = false) {
                    fetchJioSaavnIndiaSuperhitsChart(forceRefresh)
                }
                if (chartSongs.isNotEmpty()) {
                    val result = HomeRecommendationPolicy.originals(chartSongs).map { it.copy(isLiked = likedIds.contains(it.id)) }
                    saveChart(cacheKey, result)
                    return@withContext result.take(limit)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w("MusicRepository", "Failed to fetch JioSaavn India Superhits chart: ${e.message}")
            }

        }
        // India needs the same genuine-chart fallback as other regions when its
        // editorial playlist is unavailable. Never substitute generic search hits.
        run {
            // A search result is not a ranked chart. Use the country's published RSS chart.
            try {
                val code = countryCode.lowercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2}")) }
                if (code != null) {
                    val request = Request.Builder().url("https://rss.marketingtools.apple.com/api/v2/$code/music/most-played/50/songs.json").build()
                    val body = com.example.recommendation.DiscoveryRequestCoordinator.network { chartClient.catalogRequest(request) }
                    val entries = withContext(homeDispatcher) { JSONObject(body).optJSONObject("feed")?.optJSONArray("results") }
                    val ranked = coroutineScope { (0 until minOf(entries?.length() ?: 0, limit)).chunked(4).flatMap { batch ->
                        batch.map { index -> async {
                        val entry = entries?.optJSONObject(index) ?: return@async null
                        val title = entry.optString("name")
                        val artist = entry.optString("artistName")
                        // Resolve provider playback IDs while preserving chart order and exact recording identity.
                        val candidates = discoverySearch("$title $artist", forceRefresh, era = false)
                        candidates.firstOrNull {
                            HomeRecommendationPolicy.normalize(it.cleanTitle) == HomeRecommendationPolicy.normalize(title) &&
                                HomeRecommendationPolicy.artists(it).contains(HomeRecommendationPolicy.normalize(artist))
                        }
                        } }.awaitAll().filterNotNull()
                    } }
                    if (ranked.isNotEmpty()) {
                        val result = HomeRecommendationPolicy.originals(ranked).take(limit)
                        saveChart(cacheKey, result)
                        return@withContext result
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        // Transient failures retain the last genuine chart for this country only.
        regionalTrendingCache[cacheKey].orEmpty().take(limit)
      }
    }

    private suspend fun fetchJioSaavnIndiaSuperhitsChart(forceRefresh: Boolean): List<MusicTrack> {
        // JioSaavn Editorial Chart ID for "India Superhits Top 50"
        val chartId = "1134543272"
        val url = "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("__call", "playlist.getDetails").addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0").addQueryParameter("api_version", "4")
            .addQueryParameter("ctx", "web6dot0").addQueryParameter("cc", "in")
            .addQueryParameter("listid", chartId).addQueryParameter("p", "1").addQueryParameter("n", "50").build()
        val request = Request.Builder()
            .url(url)
            .apply { if (forceRefresh) cacheControl(CacheControl.FORCE_NETWORK) }
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .addHeader("Accept", "application/json")
            .build()
        val body = chartClient.catalogRequest(request)
        val response = JSONObject(body)
        val json = response.optJSONObject("data") ?: response
        val returnedId = json.optString("id").ifBlank { json.optString("listid") }
        val title = CatalogNames.normalize(json.optString("title").ifBlank { json.optString("listname") })
        if (returnedId.isNotBlank() && returnedId != chartId ||
            !title.contains("india") || !(title.contains("superhits") || title.contains("top 50"))) return emptyList()
        val songs = json.optJSONArray("list") ?: json.optJSONArray("songs") ?: return emptyList()
        // The shared v4 parser reads real song IDs and credits, nested
        // metadata and full audio, and leaves missing streams resolvable by exact ID.
        // Preserve the playlist's order and its native, resolvable playback IDs.
        return (0 until songs.length()).mapNotNull { index ->
            songs.optJSONObject(index)?.let { OnlineMusicApiService.parseSong(it, allowMissingStream = true) }
                ?.copy(source = "JioSaavn Official Chart")
        }
    }

    fun getFavoriteTracks(): Flow<List<MusicTrack>> {
        return musicDao.getFavoriteTracks()
            .flowOn(Dispatchers.IO)
            // Room invalidates this query for any track write, including unrelated playback updates.
            .distinctUntilChanged()
            .map { list -> list.map { it.toMusicTrack() } }
            .distinctUntilChanged()
            .flowOn(homeDispatcher)
    }

    fun getRecentlyPlayedTracks(): Flow<List<MusicTrack>> {
        return musicDao.getRecentlyPlayedTracks().flowOn(Dispatchers.IO).distinctUntilChanged()
            .map { list -> list.map { it.toMusicTrack() } }.flowOn(Dispatchers.Default)
    }

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> {
        return musicDao.getAllPlaylists()
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    fun getPlaylistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?> {
        return musicDao.getPlaylistWithTracks(playlistId)
            .flowOn(Dispatchers.IO).distinctUntilChanged()
            .map { content -> content?.also { it.musicTracks } }.flowOn(Dispatchers.Default)
    }

    suspend fun toggleFavorite(track: MusicTrack): Boolean =
        musicDao.toggleTrackFavorite(TrackEntity.fromMusicTrack(track))

    suspend fun markTrackPlayed(track: MusicTrack) {
        musicDao.recordTrackPlayed(TrackEntity.fromMusicTrack(track), System.currentTimeMillis())
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
            musicDao.mergeTracksMetadata(entities)
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
        musicDao.mergeTrackMetadata(entity)
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

    suspend fun search(query: String, context: android.content.Context? = null): SearchResultCategory = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext SearchResultCategory()
        }

        val cacheKey = trimmed.lowercase()
        searchCache.get(cacheKey)?.let { return@withContext it }

        val likedEntities = try {
            withContext(Dispatchers.IO) { musicDao.getFavoriteTracks().first() }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
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
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        OnlineMusicApiService.searchSongs(trimmed, limit = 25)
                    }
                } else {
                    try {
                        OnlineMusicApiService.searchSongs(trimmed, limit = 25)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            val ytDeferred = async(Dispatchers.IO) {
                try {
                    YouTubeMusicApiService.searchSongs(trimmed, limit = 25)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
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

        // Apply originality verification and scoring BEFORE deduplication
        // so an unofficial remix/bootleg cannot overshadow or replace the genuine original recording
        val isExplicitAlt = com.example.recommendation.OriginalMusicFilter.isExplicitAlternativeQuery(trimmed)
        val allRawDirect = (mixedOnline + localMatches).filter {
            // Exclude demo tracks completely
            !it.id.startsWith("xtreme_") && !it.title.contains("SoundHelix", ignoreCase = true)
        }

        // Sort by originality score descending (highest quality genuine studio recording first)
        val sortedDirect = allRawDirect.sortedWith(
            compareByDescending<MusicTrack> { track ->
                var score = com.example.recommendation.OriginalMusicFilter.calculateOriginalityScore(track, trimmed)
                val trackTitleLower = track.title.lowercase(java.util.Locale.ROOT)
                val trackArtistLower = track.artist.lowercase(java.util.Locale.ROOT)
                // Exact title query bonus
                if (trackTitleLower == q) score += 40.0
                else if (trackTitleLower.startsWith(q) || q.startsWith(trackTitleLower)) score += 20.0
                // Artist match bonus
                if (trackArtistLower.contains(q)) score += 15.0
                score
            }
        )

        // Deduplicate using clean title and primary artist AFTER sorting by originality
        val allDirect = sortedDirect
            .distinctBy {
                val cleanTitleKey = it.cleanTitle.lowercase(java.util.Locale.ROOT).trim()
                val primaryArtistKey = it.artist.split(",", "&", "feat.", "ft.", "•", "/").first().trim().lowercase(java.util.Locale.ROOT)
                "${cleanTitleKey}_$primaryArtistKey"
            }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }

        val top = allDirect.firstOrNull()

        // Exact name/title matches
        val exactMatches = allDirect.filter {
            it.title.lowercase(java.util.Locale.ROOT).contains(q) || q.contains(it.title.lowercase(java.util.Locale.ROOT))
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
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
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

        searchCache.put(cacheKey, categoryResult)
        categoryResult
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
    suspend fun resolvePlayableTrack(
        track: MusicTrack,
        forceRefresh: Boolean = false,
        failedUrl: String? = null
    ): MusicTrack = withContext(Dispatchers.IO) {
        val quality = context?.let { AudioQualityPreferences.getSelectedQuality(it) }
            ?: AudioQuality.ULTRA_HD_320
        com.example.recommendation.DiscoveryRequestCoordinator.playback {
            streamResolver.resolve(track, quality, forceRefresh, failedUrl,
                deferLossless = quality == AudioQuality.HI_RES_LOSSLESS)
        }
    }

    suspend fun getTracksByGenre(genre: String): List<MusicTrack> = withContext(Dispatchers.Default) {
        val likedEntities = try {
            withContext(Dispatchers.IO) { musicDao.getFavoriteTracks().first() }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        val likedIds = likedEntities.map { it.id }.toSet()

        // Feeds exclusively from YouTube Music API per architectural rules
        val onlineGenreTracks = try {
            YouTubeMusicApiService.getGenreTracks(genre, limit = 25)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }

        val localGenreTracks = if (genre.equals("All", ignoreCase = true)) {
            MusicDataSource.curatedTracks
        } else {
            MusicDataSource.curatedTracks.filter { it.genre.equals(genre, ignoreCase = true) }
        }

        (onlineGenreTracks + localGenreTracks.shuffled())
            .distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }
            .shuffled()
    }

    suspend fun findLosslessUpgrade(track: MusicTrack): MusicTrack? = withContext(Dispatchers.IO) {
        if (context == null || AudioQualityPreferences.getSelectedQuality(context) != AudioQuality.HI_RES_LOSSLESS) return@withContext null
        kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            val query = "${StreamResolver.extractCleanTitle(track.title, track.artist)} ${StreamResolver.extractCleanArtist(track.artist)}".trim()
            val candidates = InternetArchiveApiService.searchLosslessPage(query, pageSize = 8, playableOnly = true).tracks
            withContext(Dispatchers.Default) { candidates.filter { candidate ->
                candidate.audioUrl != track.audioUrl && StreamResolver.matchesLosslessUpgrade(track, candidate) &&
                    (!track.isLossless || !track.qualityBadge.startsWith("Hi-Res") && candidate.qualityBadge.startsWith("Hi-Res"))
            }.sortedByDescending { it.qualityBadge.startsWith("Hi-Res") }.firstOrNull() }
        }
    }

    fun clearSearchCache() {
        searchCache.evictAll()
        discoveryCache.evictAll()
        streamResolver.clear()
    }

    fun getSearchCacheEntryCount(): Int {
        return searchCache.size() + discoveryCache.size() + streamResolver.cacheSize
    }
}
