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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
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
class MusicRepository private constructor(
    private val daoProvider: () -> MusicDao,
    private val context: Context?
) {
    constructor(musicDao: MusicDao, context: Context? = null) : this({ musicDao }, context)
    constructor(context: Context) : this({ MusicDatabase.getDatabase(context.applicationContext).musicDao() }, context.applicationContext)
    // Constructing the UI's repository never opens Room. Cold flows acquire it on IO.
    private val musicDao: MusicDao by lazy(daoProvider)
    private val initialCatalogMutex = Mutex()
    private val initialCatalogScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var initialCatalogFlight: kotlinx.coroutines.Deferred<List<MusicTrack>>? = null
    private data class PreparedCatalog(val tracks: List<MusicTrack>, val liked: Set<String>, val result: List<MusicTrack>)
    private var preparedCatalog: PreparedCatalog? = null

    val catalogBrowser = CatalogRepository()
    // Home's owners and workers share this cap, including discovery ranking.
    internal val homeDispatcher = BackgroundWorkCoordinator.optionalCpuDispatcher
    val recommendations by lazy { com.example.recommendation.UnifiedRecommendationEngine(context) }

    internal suspend fun cacheRecommendationTracks(tracks: List<MusicTrack>) = withContext(homeDispatcher) {
        val rows = tracks.filter(com.example.recommendation.UnifiedRecommendationEngine::verified)
            .distinctBy { it.id }.map { TrackEntity.fromMusicTrack(it).copy(isCached = true) }
        if (rows.isNotEmpty()) withContext(Dispatchers.IO) { musicDao.mergeTracksMetadata(rows) }
    }

    // LRU / in-memory cache to avoid duplicate network calls and prevent rate limiting
    private val searchCache = android.util.LruCache<String, Pair<Long, SearchResultCategory>>(80)
    private val losslessMisses = android.util.LruCache<String, Long>(128)
    private val losslessLookup = kotlinx.coroutines.sync.Mutex()
    private val streamResolver = StreamResolver()
    private val chartClient by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }

    // Static memory cache for enriched catalog to persist across fast navigations
    companion object {
        @Volatile private var memoryCachedCatalog: List<MusicTrack>? = null
    }

    private suspend fun getLikedTrackIds(): Set<String> = withContext(homeDispatcher) {
        observeFavoriteTrackIds().first()
    }

    suspend fun getInitialCatalog(profile: UserProfile? = null): List<MusicTrack> {
        // Home and Player join one repository-owned load; disposing one UI owner
        // cannot cancel the database work still needed by another caller.
        val flight = initialCatalogMutex.withLock {
            initialCatalogFlight?.takeUnless { it.isCompleted } ?: initialCatalogScope.async(
                start = kotlinx.coroutines.CoroutineStart.LAZY) { prepareInitialCatalog() }
                .also { initialCatalogFlight = it }
        }
        try {
            flight.start()
            return flight.await()
        } finally {
            if (flight.isCompleted) initialCatalogMutex.withLock {
                if (initialCatalogFlight === flight) initialCatalogFlight = null
            }
        }
    }

    private suspend fun prepareInitialCatalog(): List<MusicTrack> = withContext(homeDispatcher) {
        val liked = getLikedTrackIds()
        val cached = memoryCachedCatalog.orEmpty().ifEmpty {
            withContext(Dispatchers.IO) { musicDao.getCachedTracksSync(250) }.map { it.toMusicTrack() }
                .also { if (it.isNotEmpty() && memoryCachedCatalog.isNullOrEmpty()) memoryCachedCatalog = it }
        }.ifEmpty {
            MusicDataSource.curatedTracks
        }
        preparedCatalog?.takeIf { it.tracks == cached && it.liked == liked }?.let { return@withContext it.result }
        // Discard legacy fabricated YouTube defaults when reading old recommendation caches.
        val corrected = cached.map { track ->
            if (track.id.startsWith("yt_") && track.genre == "Pop" && track.language == "Hindi")
                track.copy(genre = "Unknown", language = "Unknown") else track
        }
        HomeRecommendationPolicy.originals(corrected).map { it.copy(isLiked = it.id in liked) }
            .also { preparedCatalog = PreparedCatalog(cached, liked, it) }
    }

    private suspend fun homeProfile(profile: UserProfile? = null): UserProfile = profile ?: withContext(Dispatchers.IO) {
        context?.let { UserProfilePreferences.getUserProfile(it) } ?: UserProfile()
    }

    /** Compatibility entry points delegate to the same engine; no independent discovery. */
    private suspend fun recommendationShelf(profile: UserProfile, shelf: com.example.recommendation.UnifiedRecommendationEngine.Shelf,
        limit: Int, force: Boolean = false): List<MusicTrack> {
        var result = emptyList<MusicTrack>()
        kotlinx.coroutines.withTimeoutOrNull(20_000L) {
            recommendations.refresh(profile, profile.country, UserTasteProfile.COLD_START_PROFILE,
                null, emptyList(), emptyList(), getInitialCatalog(profile), force, setOf(shelf)).collect { batch ->
                result = when (shelf) {
                    com.example.recommendation.UnifiedRecommendationEngine.Shelf.ERA -> batch.era
                    com.example.recommendation.UnifiedRecommendationEngine.Shelf.CHART -> batch.chart.orEmpty()
                    else -> batch.quick
                }
            }
        }
        return result.take(limit)
    }
    suspend fun syncOnlineCatalog(profile: UserProfile? = null): List<MusicTrack> =
        getYtmQuickPicks().ifEmpty { getInitialCatalog(profile) }
    suspend fun getPunjabiHits(limit: Int = 15): List<MusicTrack> = getCountryAndLanguageHits(homeProfile(), limit)
    suspend fun getEraHits(limit: Int = 15, forceRefresh: Boolean = false,
        @Suppress("UNUSED_PARAMETER") deferRoomMergeUntilIdle: Boolean = false): List<MusicTrack> =
        recommendationShelf(homeProfile(), com.example.recommendation.UnifiedRecommendationEngine.Shelf.ERA, limit, forceRefresh)
    suspend fun getYtmQuickPicks(limit: Int = 15, forceRefresh: Boolean = false,
        @Suppress("UNUSED_PARAMETER") deferRoomMergeUntilIdle: Boolean = false): List<MusicTrack> =
        recommendationShelf(homeProfile(), com.example.recommendation.UnifiedRecommendationEngine.Shelf.QUICK, limit, forceRefresh)
    suspend fun getCountryAndLanguageHits(profile: UserProfile?, limit: Int = 15, forceRefresh: Boolean = false): List<MusicTrack> =
        recommendationShelf(homeProfile(profile), com.example.recommendation.UnifiedRecommendationEngine.Shelf.CHART, limit, forceRefresh)

    suspend fun recommendationRadio(seed: MusicTrack, alreadyQueued: List<MusicTrack>, limit: Int,
        taste: UserTasteProfile? = null): List<MusicTrack> = withContext(Dispatchers.Default) {
        val profile = homeProfile()
        val actualTaste = taste ?: context?.let {
            val events = withContext(Dispatchers.IO) { MusicDatabase.getDatabase(it).listeningEventDao().getActualPlays(250) }
            val ids = events.map { event -> event.trackId }.distinct()
            val metadata = withContext(Dispatchers.IO) { musicDao.getTracksByIds(ids) }.map { row -> row.toMusicTrack() }
            UserTasteProfile.fromEvents(events, metadata, getFavoriteTracks().first())
        } ?: UserTasteProfile.COLD_START_PROFILE
        recommendations.radio(seed, profile, actualTaste, alreadyQueued, limit)
    }

    fun observeFavoriteTrackIds(): Flow<Set<String>> = flow { emitAll(musicDao.observeFavoriteTrackIds()) }
        .flowOn(Dispatchers.IO).map { it.toSet() }.distinctUntilChanged().flowOn(homeDispatcher)

    fun getFavoriteTracks(): Flow<List<MusicTrack>> {
        return flow { emitAll(musicDao.getFavoriteTracks()) }
            .flowOn(Dispatchers.IO)
            // Room invalidates this query for any track write, including unrelated playback updates.
            .distinctUntilChanged()
            .map { list -> list.map { it.toMusicTrack() } }
            .distinctUntilChanged()
            .flowOn(homeDispatcher)
    }

    fun getRecentlyPlayedTracks(): Flow<List<MusicTrack>> {
        return flow { emitAll(musicDao.getRecentlyPlayedTracks()) }.flowOn(Dispatchers.IO).distinctUntilChanged()
            .map { list -> list.map { it.toMusicTrack() } }.flowOn(Dispatchers.Default)
    }

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> {
        return flow { emitAll(musicDao.getAllPlaylists()) }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    fun getPlaylistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?> {
        return flow { emitAll(musicDao.getPlaylistWithTracks(playlistId)) }
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

    /** User search returns matching catalog entities; discovery remains in its own flows. */
    suspend fun search(query: String, context: android.content.Context? = null): SearchResultCategory = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResultCategory()
        val cacheKey = trimmed.lowercase(java.util.Locale.ROOT)
        searchCache.get(cacheKey)?.takeIf { System.currentTimeMillis() - it.first < 300_000L }
            ?.let { return@withContext it.second }
        val likedIds = try {
            withContext(Dispatchers.IO) { musicDao.getFavoriteTracks().first() }.mapTo(mutableSetOf()) { it.id }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptySet<String>() }
        val tracks = AudioRoutingService.resolveSearchTracks(context ?: this@MusicRepository.context,
            trimmed, limit = 25).tracks.map { it.copy(isLiked = it.id in likedIds) }
        val exact = tracks.filter { CatalogNames.score(trimmed,
            StreamResolver.extractCleanTitle(it.title, it.artist)) >= 55 }.ifEmpty { tracks.take(10) }
        val result = SearchResultCategory(topResult = tracks.firstOrNull(), exactMatches = exact,
            jioMatches = tracks.filter { it.id.startsWith("online_") },
            ytMatches = tracks.filter { it.id.startsWith("yt_") }, songs = tracks,
            albums = tracks.map { it.album }.filter { CatalogNames.normalize(it) !in
                setOf("", "unknown", "single", "online stream", "extended stream", "youtube music") }.distinct(),
            artists = tracks.map { it.artist }.filter { CatalogNames.normalize(it) !in
                setOf("", "unknown", "unknown artist", "various artists", "online artist", "youtube artist") }.distinct())
        searchCache.put(cacheKey, System.currentTimeMillis() to result)
        result
    }

    /** Apply the active quality tier consistently to every playback entry point. */
    suspend fun resolvePlayableTrack(
        track: MusicTrack,
        forceRefresh: Boolean = false,
        failedUrl: String? = null,
        preferYouTube: Boolean = false,
        jioDeadlineMs: Long = 0L,
        currentSourceOnly: Boolean = false,
        rejectedUrls: Set<String> = emptySet()
    ): MusicTrack = withContext(Dispatchers.IO) {
        val profile = context?.let { com.example.playback.NetworkQualityMonitor.getInstance(it).snapshot() }
        val preference = com.example.playback.AudioQualityPreference.from(profile?.effectiveCeiling?.audioQuality
            ?: AudioQuality.ULTRA_HD_320)
        val quality = preference.legacy
        try {
            com.example.recommendation.DiscoveryRequestCoordinator.playback {
                streamResolver.resolve(track, quality, forceRefresh, failedUrl, preferYouTube = preferYouTube, jioDeadlineMs = jioDeadlineMs,
                    currentSourceOnly = currentSourceOnly, rejectedUrls = rejectedUrls,
                    maxBitrateBps = profile?.effectiveCeiling?.maxBitrateBps ?: Int.MAX_VALUE)
            }
        } catch (e: TrackUnavailableException) {
            if (track.recommendationOnly && !currentSourceOnly) recommendations.markUnavailable(track)
            throw e
        }
    }

    suspend fun resolveYouTubeFallback(
        track: MusicTrack,
        quality: AudioQuality = AudioQuality.ULTRA_HD_320
    ): MusicTrack? = withContext(Dispatchers.IO) {
        streamResolver.resolveYouTubeFallback(track, quality)
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

    internal suspend fun hasRecentLosslessAttempt(track: MusicTrack) = streamResolver.hasRecentLosslessAttempt(track)

    suspend fun findLosslessUpgrade(track: MusicTrack): MusicTrack? = withContext(Dispatchers.IO) {
        val monitor = context?.let(com.example.playback.NetworkQualityMonitor::getInstance)
        monitor?.snapshot()
        if (monitor?.allowsLosslessUpgrade != true) return@withContext null
        if (context == null || AudioQualityPreferences.getSelectedQuality(context) != AudioQuality.HI_RES_LOSSLESS) return@withContext null
        val key = withContext(Dispatchers.Default) { RecordingIdentity.key(track) }
        fun missedRecently() = losslessMisses.get(key)?.let { android.os.SystemClock.elapsedRealtime() < it } == true
        if (missedRecently()) return@withContext null
        // One optional Archive lookup at a time, and recheck after waiting for its permit.
        losslessLookup.withLock {
            if (missedRecently() || AudioQualityPreferences.getSelectedQuality(context) != AudioQuality.HI_RES_LOSSLESS ||
                !monitor.allowsLosslessUpgrade)
                return@withLock null
            var completed = false
            val result = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                // Archive often credits only the primary artist. Optional featured
                // credits must not turn a targeted query into an impossible AND search.
                val artist = RecordingIdentity.artistNames(track).firstOrNull()
                    ?: StreamResolver.extractCleanArtist(track.artist)
                val title = StreamResolver.extractCleanTitle(track.title, track.artist)
                var successful = true
                // Reuse the initial page/cache first. Album collections may index
                // their creator and album, without indexing each individual title.
                for ((index, query) in listOf("$title $artist".trim(), artist).filter(String::isNotBlank).distinct().withIndex()) {
                    val page = InternetArchiveApiService.searchLosslessPage(query,
                        pageSize = if (index == 0) 4 else 6, playableOnly = true)
                    successful = successful && page.isSuccessful
                    val match = withContext(Dispatchers.Default) { page.tracks.filter { candidate ->
                        candidate.audioUrl != track.audioUrl && StreamResolver.matchesLosslessUpgrade(track, candidate) &&
                            (!track.isLossless || !track.qualityBadge.startsWith("Hi-Res") && candidate.qualityBadge.startsWith("Hi-Res"))
                    }.sortedByDescending { it.qualityBadge.startsWith("Hi-Res") }.firstOrNull() }
                    if (match != null) { completed = true; return@withTimeoutOrNull match }
                }
                completed = successful
                null
            }
            if (completed && result == null) losslessMisses.put(key, android.os.SystemClock.elapsedRealtime() + 180_000L)
            result
        }
    }

    internal suspend fun rejectLosslessUpgrade(track: MusicTrack) = withContext(Dispatchers.Default) {
        losslessMisses.put(RecordingIdentity.key(track), android.os.SystemClock.elapsedRealtime() + 180_000L)
        Unit
    }

    fun clearSearchCache() {
        searchCache.evictAll()
        streamResolver.clear()
    }

    fun getSearchCacheEntryCount(): Int {
        return searchCache.size() + streamResolver.cacheSize
    }
}
