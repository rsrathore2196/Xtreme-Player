package com.example.data.repository

import com.example.performance.AppDispatchers

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.flow.onEach
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
    private val context: Context?,
    private val losslessPageSearch: (suspend (String, Int) -> InternetArchiveApiService.LosslessSearchPage)? = null
) {
    constructor(musicDao: MusicDao, context: Context? = null) : this({ musicDao }, context)
    constructor(context: Context) : this({ MusicDatabase.getDatabase(context.applicationContext).musicDao() }, context.applicationContext)
    internal constructor(musicDao: MusicDao, context: Context,
        losslessPageSearch: suspend (String, Int) -> InternetArchiveApiService.LosslessSearchPage
    ) : this({ musicDao }, context, losslessPageSearch)
    // Constructing the UI's repository never opens Room. Cold flows acquire it on IO.
    private val musicDao: MusicDao by lazy(daoProvider)
    private val initialCatalogMutex = Mutex()
    private val initialCatalogScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + AppDispatchers.Io)
    private class InitialCatalogFlight(val result: kotlinx.coroutines.Deferred<List<MusicTrack>>,
        val urgent: java.util.concurrent.atomic.AtomicBoolean)
    private var initialCatalogFlight: InitialCatalogFlight? = null
    private data class PreparedCatalog(val tracks: List<MusicTrack>, val liked: Set<String>, val result: List<MusicTrack>)
    private var preparedCatalog: PreparedCatalog? = null
    // App-owned snapshots outlive an Activity/ViewModel. Warm recreation can
    // compose real local rows immediately; the next Room emission remains authoritative.
    @Volatile var retainedFavoriteTracks: List<MusicTrack> = emptyList()
        private set
    @Volatile var retainedRecentlyPlayed: List<MusicTrack> = emptyList()
        private set
    @Volatile var retainedPlaylists: List<PlaylistEntity> = emptyList()
        private set

    val catalogBrowser = CatalogRepository()
    // Home's owners and workers share this cap, including discovery ranking.
    internal val homeDispatcher = BackgroundWorkCoordinator.optionalCpuDispatcher
    val recommendations by lazy { com.example.recommendation.UnifiedRecommendationEngine(context) }

    internal suspend fun cacheRecommendationTracks(tracks: List<MusicTrack>) = withContext(homeDispatcher) {
        val rows = tracks.filter(com.example.recommendation.UnifiedRecommendationEngine::verified)
            .distinctBy { it.id }.map { TrackEntity.fromMusicTrack(it).copy(isCached = true) }
        if (rows.isNotEmpty()) withContext(AppDispatchers.Database) { musicDao.mergeTracksMetadata(rows) }
    }

    // LRU / in-memory cache to avoid duplicate network calls and prevent rate limiting
    private val searchCache = android.util.LruCache<String, Pair<Long, SearchResultCategory>>(80)
    private val losslessMisses = android.util.LruCache<String, Long>(128)
    private val losslessLookup = kotlinx.coroutines.sync.Mutex()
    private val rejectedLosslessUrls = android.util.LruCache<String, Long>(64)
    private val streamResolver = StreamResolver()
    private val chartClient by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }

    // Static memory cache for enriched catalog to persist across fast navigations
    companion object {
        @Volatile private var memoryCachedCatalog: List<MusicTrack>? = null
    }

    private suspend fun getLikedTrackIds(): Set<String> = withContext(homeDispatcher) {
        observeFavoriteTrackIds().first()
    }

    suspend fun getInitialCatalog(profile: UserProfile? = null,
        priority: BackgroundWorkCoordinator.StartupPriority = BackgroundWorkCoordinator.StartupPriority.CRITICAL): List<MusicTrack> {
        // Home and Player join one repository-owned load; disposing one UI owner
        // cannot cancel the database work still needed by another caller.
        val flight = initialCatalogMutex.withLock {
            initialCatalogFlight?.takeUnless { it.result.isCompleted }?.also {
                if (priority == BackgroundWorkCoordinator.StartupPriority.CRITICAL) it.urgent.set(true)
            } ?: run {
                val urgent = java.util.concurrent.atomic.AtomicBoolean(priority == BackgroundWorkCoordinator.StartupPriority.CRITICAL)
                InitialCatalogFlight(initialCatalogScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    // The shared flight owns the admission. A caller must not hold
                    // an optional slot while a detached child does unadmitted CPU work.
                    BackgroundWorkCoordinator.startup(priority, urgent = { urgent.get() },
                        label = "Initial catalog preparation") { prepareInitialCatalog() }
                }, urgent).also { initialCatalogFlight = it }
            }
        }
        try {
            flight.result.start()
            return flight.result.await()
        } finally {
            if (flight.result.isCompleted) initialCatalogMutex.withLock {
                if (initialCatalogFlight === flight) initialCatalogFlight = null
            }
        }
    }

    private suspend fun prepareInitialCatalog(): List<MusicTrack> = withContext(homeDispatcher) {
        val liked = getLikedTrackIds()
        val cached = memoryCachedCatalog.orEmpty().ifEmpty {
            withContext(AppDispatchers.Database) { musicDao.getCachedTracksSync(250) }.map { it.toMusicTrack() }
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

    private suspend fun homeProfile(profile: UserProfile? = null): UserProfile = profile ?: withContext(AppDispatchers.Io) {
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
        taste: UserTasteProfile? = null): List<MusicTrack> = withContext(AppDispatchers.CpuBounded) {
        val profile = homeProfile()
        val actualTaste = taste ?: context?.let {
            val events = withContext(AppDispatchers.Database) { MusicDatabase.getDatabase(it).listeningEventDao().getActualPlays(250) }
            val ids = events.map { event -> event.trackId }.distinct()
            val metadata = withContext(AppDispatchers.Database) { musicDao.getTracksByIds(ids) }.map { row -> row.toMusicTrack() }
            UserTasteProfile.fromEvents(events, metadata, getFavoriteTracks().first())
        } ?: UserTasteProfile.COLD_START_PROFILE
        recommendations.radio(seed, profile, actualTaste, alreadyQueued, limit)
    }

    fun observeFavoriteTrackIds(): Flow<Set<String>> = flow { emitAll(musicDao.observeFavoriteTrackIds()) }
        .flowOn(AppDispatchers.Database).map { it.toSet() }.distinctUntilChanged().flowOn(homeDispatcher)

    fun getFavoriteTracks(preparationDispatcher: kotlinx.coroutines.CoroutineDispatcher = homeDispatcher): Flow<List<MusicTrack>> {
        return flow { emitAll(musicDao.getFavoriteTracks()) }
            .flowOn(AppDispatchers.Database)
            // Room invalidates this query for any track write, including unrelated playback updates.
            .distinctUntilChanged()
            .map { list -> list.map { it.toMusicTrack() } }
            .distinctUntilChanged()
            .onEach { retainedFavoriteTracks = it }
            .flowOn(preparationDispatcher)
    }

    fun getRecentlyPlayedTracks(): Flow<List<MusicTrack>> {
        return flow { emitAll(musicDao.getRecentlyPlayedTracks()) }.flowOn(AppDispatchers.Database).distinctUntilChanged()
            .map { list -> list.map { it.toMusicTrack() } }
            .onEach { retainedRecentlyPlayed = it }.flowOn(AppDispatchers.CpuBounded)
    }

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> {
        return flow { emitAll(musicDao.getAllPlaylists()) }
            .flowOn(AppDispatchers.Database)
            .distinctUntilChanged()
            .onEach { retainedPlaylists = it }
            .flowOn(AppDispatchers.CpuBounded)
    }

    fun getPlaylistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?> {
        return flow { emitAll(musicDao.getPlaylistWithTracks(playlistId)) }
            .flowOn(AppDispatchers.Database).distinctUntilChanged()
            .map { content -> content?.also { it.musicTracks } }.flowOn(AppDispatchers.CpuBounded)
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
    suspend fun search(query: String, context: android.content.Context? = null): SearchResultCategory = withContext(AppDispatchers.CpuBounded) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResultCategory()
        val cacheKey = trimmed.lowercase(java.util.Locale.ROOT)
        searchCache.get(cacheKey)?.takeIf { System.currentTimeMillis() - it.first < 300_000L }
            ?.let { return@withContext it.second }
        val likedIds = try {
            withContext(AppDispatchers.Database) { musicDao.getFavoriteTracks().first() }.mapTo(mutableSetOf()) { it.id }
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
    ): MusicTrack = withContext(AppDispatchers.Io) {
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
    ): MusicTrack? = withContext(AppDispatchers.Io) {
        streamResolver.resolveYouTubeFallback(track, quality)
    }

    suspend fun getTracksByGenre(genre: String): List<MusicTrack> = withContext(AppDispatchers.CpuBounded) {
        val likedEntities = try {
            withContext(AppDispatchers.Database) { musicDao.getFavoriteTracks().first() }
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

    suspend fun findLosslessUpgrade(track: MusicTrack): MusicTrack? = withContext(AppDispatchers.Io) {
        val monitor = context?.let(com.example.playback.NetworkQualityMonitor::getInstance)
        if (monitor?.snapshot()?.allowsLosslessUpgrade != true) {
            com.example.playback.LosslessDiagnostics.record(track.id,
                com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "discovery")
            return@withContext null
        }
        val key = withContext(AppDispatchers.CpuBounded) { RecordingIdentity.key(track) }
        fun missedRecently() = losslessMisses.get(key)?.let { android.os.SystemClock.elapsedRealtime() < it } == true
        if (missedRecently()) return@withContext null
        val cached = StreamResolver.getCachedLosslessMatch(track)
        if (cached != null && (rejectedLosslessUrls.get(cached.audioUrl) ?: 0L) <= android.os.SystemClock.elapsedRealtime()) {
            return@withContext cached
        }
        // Reuse the process-wide reference-counted request sharing. Cancelling one
        // consumer cannot cancel a lookup another consumer still needs.
        val found = com.example.recommendation.DiscoveryRequestCoordinator.shared("archive-recording:$key",
            essential = true, network = false) {
            losslessLookup.withLock {
                if (missedRecently() || !monitor.snapshot().allowsLosslessUpgrade) return@withLock emptyList()
                val result = if (losslessPageSearch == null) {
                    val rejected = rejectedLosslessUrls.snapshot().filterValues {
                        it > android.os.SystemClock.elapsedRealtime()
                    }.keys
                    InternetArchiveApiService.findLosslessRecording(track, rejected) { monitor.snapshot().allowsLosslessUpgrade }
                } else {
                    val artist = RecordingIdentity.artistNames(track).firstOrNull()
                        ?: StreamResolver.extractCleanArtist(track.artist)
                    val title = StreamResolver.extractCleanTitle(track.title, track.artist)
                    val tracks = mutableListOf<MusicTrack>()
                    var successful = true
                    var failure: com.example.playback.LosslessDiagnostics.Reason? = null
                    var exhaustive = true
                    for ((index, query) in listOf("$title $artist".trim(), artist)
                        .filter(String::isNotBlank).distinct().withIndex()) {
                        if (!monitor.snapshot().allowsLosslessUpgrade) break
                        val page = losslessPageSearch.invoke(query, if (index == 0) 4 else 6)
                        successful = successful && page.isSuccessful
                        failure = page.failure ?: failure
                        exhaustive = exhaustive && page.exhaustive && page.nextPage == null
                        tracks.addAll(page.tracks)
                        if (page.tracks.any { StreamResolver.matchesLosslessUpgrade(track, it) }) break
                    }
                    InternetArchiveApiService.LosslessSearchPage(tracks, isSuccessful = successful,
                        failure = failure, exhaustive = exhaustive)
                }
                if (!monitor.snapshot().allowsLosslessUpgrade) {
                    com.example.playback.LosslessDiagnostics.record(track.id,
                        com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "discovery-policy")
                    return@withLock emptyList()
                }
                val matches = withContext(AppDispatchers.CpuBounded) {
                    result.tracks.mapNotNull { candidate ->
                        if (candidate.audioUrl == track.audioUrl ||
                            (rejectedLosslessUrls.get(candidate.audioUrl) ?: 0L) > android.os.SystemClock.elapsedRealtime() ||
                            !StreamResolver.matchesLosslessUpgrade(track, candidate)) {
                            if (candidate.audioUrl != track.audioUrl && !StreamResolver.matchesLosslessUpgrade(track, candidate)) {
                                com.example.playback.LosslessDiagnostics.record(track.id,
                                    com.example.playback.LosslessDiagnostics.Reason.SOURCE_VERIFICATION_FAILED, "candidate-verification")
                            }
                            null
                        } else RecordingIdentity.losslessConfidence(track, candidate)?.let { candidate to it }
                    }.sortedByDescending { it.second }.map { it.first }
                }
                if (matches.isEmpty()) {
                    val reason = when {
                        result.failure == com.example.playback.LosslessDiagnostics.Reason.NETWORK_TIMEOUT ->
                            com.example.playback.LosslessDiagnostics.Reason.LOOKUP_TIMEOUT
                        result.failure != null -> result.failure
                        result.tracks.isNotEmpty() -> com.example.playback.LosslessDiagnostics.Reason.NO_MATCH
                        else -> com.example.playback.LosslessDiagnostics.Reason.NO_RESULTS
                    }
                    com.example.playback.LosslessDiagnostics.record(track.id, reason, "discovery")
                    // Never persist timeouts/partial searches as confirmed absence.
                    val ttl = if (result.isSuccessful && result.exhaustive && result.nextPage == null &&
                        reason == com.example.playback.LosslessDiagnostics.Reason.NO_RESULTS) 90_000L else 10_000L
                    losslessMisses.put(key, android.os.SystemClock.elapsedRealtime() + ttl)
                }
                matches
            }
        }.firstOrNull()
        if (found != null) {
            StreamResolver.cacheVerifiedLosslessMatch(track, found)
        }
        found
    }

    internal suspend fun rejectLosslessUpgrade(track: MusicTrack, candidateUrl: String? = null) = withContext(AppDispatchers.CpuBounded) {
        // Reject the failed representation, not every authorized copy of this recording.
        val ttl = when (com.example.playback.LosslessDiagnostics.last(track.id)?.reason) {
            com.example.playback.LosslessDiagnostics.Reason.RECORDING_MISMATCH,
            com.example.playback.LosslessDiagnostics.Reason.DECODER_UNSUPPORTED,
            com.example.playback.LosslessDiagnostics.Reason.INVALID_FORMAT,
            com.example.playback.LosslessDiagnostics.Reason.SOURCE_VERIFICATION_FAILED -> 300_000L
            com.example.playback.LosslessDiagnostics.Reason.ACTIVATION_FAILED,
            com.example.playback.LosslessDiagnostics.Reason.TRANSITION_FAILED -> 30_000L
            else -> 10_000L // A slow connection is not proof that a valid file is unavailable.
        }
        if (candidateUrl != null) rejectedLosslessUrls.put(candidateUrl, android.os.SystemClock.elapsedRealtime() + ttl)
        losslessMisses.put(RecordingIdentity.key(track), android.os.SystemClock.elapsedRealtime() + 5_000L)
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
