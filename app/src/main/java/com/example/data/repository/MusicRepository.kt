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
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
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
    val artists: List<String> = emptyList(),
    val catalogAlbums: List<CatalogAlbum> = emptyList()
)

/** Publish usable songs without waiting for album discovery; preserve their exact snapshot. */
internal suspend fun loadGenreResults(genre: String, loadTracks: suspend () -> List<MusicTrack>,
    loadAlbums: suspend (List<MusicTrack>) -> List<CatalogAlbum>,
    onSongs: suspend (SearchResultCategory) -> Unit): SearchResultCategory {
    val tracks = loadTracks()
    currentCoroutineContext().ensureActive()
    val songs = SearchResultCategory(topResult = tracks.firstOrNull(), exactMatches = tracks,
        songs = tracks, matchedGenreOrType = genre)
    onSongs(songs)
    val albums = try { loadAlbums(tracks) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    currentCoroutineContext().ensureActive()
    return songs.copy(albums = albums.map { it.title }.ifEmpty {
        tracks.map { it.album }.filter { it.isNotBlank() && it !in setOf("Single", "Online Stream", "Unknown") }.distinct()
    }, catalogAlbums = albums)
}

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
    private val rejectedLosslessUrls = android.util.LruCache<String, Long>(64)
    private val streamResolver = StreamResolver(resolveAddon = { source, track, policy, rejected ->
        context?.let { com.example.data.remote.StreamingAddons.resolve(it, track, policy, rejected, source) }
    })
    internal suspend fun searchArchive(query: String, page: Int,
        policy: com.example.playback.PlaybackSourcePolicy, recordings: List<MusicTrack>): InternetArchiveApiService.LosslessSearchPage = withContext(AppDispatchers.Io) {
        val app = context ?: return@withContext InternetArchiveApiService.LosslessSearchPage()
        val monitor = com.example.playback.NetworkQualityMonitor.getInstance(app)
        if (!policy.allowsArchive || monitor.sourcePolicySnapshot().key != policy.key)
            return@withContext InternetArchiveApiService.LosslessSearchPage()
        var result = InternetArchiveApiService.LosslessSearchPage(isSuccessful = false, exhaustive = false)
        val playable = mutableListOf<MusicTrack>()
        val complete = kotlinx.coroutines.withTimeoutOrNull(40_000L) {
            result = InternetArchiveApiService.searchLosslessPage(query, page, pageSize = 4, playableOnly = true)
            // Collection titles can omit song names. Reuse bounded recording/album
            // discovery only for a genuinely empty first page, never after downgrade.
            if (page == 1 && result.tracks.isEmpty()) {
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    for (recording in recordings.take(2)) {
                        if (monitor.sourcePolicySnapshot().key != policy.key) break
                        val matched = findLosslessUpgrade(recording)
                        if (matched != null) {
                            result = result.copy(tracks = listOf(matched))
                            break
                        }
                    }
                }
            }
            var incomplete = !result.isSuccessful
            for (track in result.tracks.take(4)) {
                if (monitor.sourcePolicySnapshot().key != policy.key) break
                val item = androidx.media3.common.MediaItem.Builder().setMediaId(track.id).setUri(track.audioUrl).build()
                val prepared = kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                    com.example.playback.LosslessStreamPrebuffer.prepare(app, item, track.durationMs,
                        currentPosition = { 0L }, stillCurrent = { monitor.sourcePolicySnapshot().key == policy.key })
                }
                if (prepared != null) playable.add(track.copy(qualityBadge = prepared.audio.badge,
                    bitrateKbps = prepared.audio.bitrateKbps)) else incomplete = true
            }
            if (incomplete) result = result.copy(isSuccessful = false, exhaustive = false)
            true
        }
        // A slow/unavailable later file cannot erase already validated files, or
        // poison the provider cache with a falsely exhaustive empty result.
        if (complete == null) result = result.copy(isSuccessful = false, exhaustive = false,
            failure = com.example.playback.LosslessDiagnostics.Reason.NETWORK_TIMEOUT)
        result.copy(tracks = playable.toList())
    }
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
            UserTasteProfile.fromEvents(events, metadata, favoriteSnapshotOrRetained())
        } ?: UserTasteProfile.COLD_START_PROFILE
        recommendations.radio(seed, profile, actualTaste, alreadyQueued, limit)
    }

    fun observeFavoriteTrackIds(): Flow<Set<String>> = flow { emitAll(musicDao.observeFavoriteTrackIds()) }
        .flowOn(AppDispatchers.Database).map { it.toSet() }.distinctUntilChanged().flowOn(homeDispatcher)

    // Replay authoritative snapshots; multiple screens share both Room reads and
    // entity conversion. No initial empty value is mistaken for a loaded library.
    private val libraryRetry = MutableStateFlow(0L)
    private val _libraryFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    val libraryFailures = _libraryFailures.asStateFlow()

    init {
        initialCatalogScope.launch {
            BackgroundWorkCoordinator.uiForeground.collect { if (it) retryLocalLibrary() }
        }
    }

    /** Retry only failed observations. Healthy, shared Room collectors are never restarted. */
    fun retryLocalLibrary() {
        if (_libraryFailures.value.isNotEmpty()) libraryRetry.update { it + 1L }
    }

    private fun <T> sharedLibrarySnapshots(name: String, rows: Flow<T>, retain: (T) -> Unit) =
        libraryRetry.map { revision -> if (revision == 0L || name in _libraryFailures.value) revision else -1L }
            .let { triggers -> kotlinx.coroutines.flow.flow {
                var accepted = 0L
                triggers.collect { revision ->
                    if (revision >= 0L) accepted = revision
                    emit(accepted)
                }
            } }.distinctUntilChanged().flatMapLatest {
            rows.distinctUntilChanged().onEach { value ->
                retain(value)
                _libraryFailures.update { failures -> failures - name }
            }.retryWhen { error, attempt ->
                if (error is CancellationException) throw error
                if (attempt >= 2) false else { kotlinx.coroutines.delay(250L * (attempt + 1)); true }
            }.catch { error ->
                if (error is CancellationException) throw error
                _libraryFailures.update { failures -> failures + (name to "Local library unavailable. Please retry.") }
            }
        }.shareIn(initialCatalogScope, SharingStarted.Lazily, replay = 1)

    private val favoriteSnapshots by lazy {
        sharedTrackSnapshots("favorites", flow { emitAll(musicDao.getFavoriteTracks()) }) { retainedFavoriteTracks = it }
    }
    private val recentSnapshots by lazy {
        sharedTrackSnapshots("recents", flow { emitAll(musicDao.getRecentlyPlayedTracks()) }) { retainedRecentlyPlayed = it }
    }
    private fun sharedTrackSnapshots(name: String, rows: Flow<List<TrackEntity>>, retain: (List<MusicTrack>) -> Unit) =
        sharedLibrarySnapshots(name, rows.flowOn(AppDispatchers.Database).distinctUntilChanged()
            .map { list -> list.map { it.toMusicTrack() } }.distinctUntilChanged().flowOn(AppDispatchers.CpuBounded), retain)

    fun getFavoriteTracks(): Flow<List<MusicTrack>> = favoriteSnapshots
    /** Optional ranking must not wait indefinitely for unavailable local storage. */
    suspend fun favoriteSnapshotOrRetained(): List<MusicTrack> =
        if ("favorites" in libraryFailures.value) retainedFavoriteTracks
        else kotlinx.coroutines.withTimeoutOrNull(1_500L) { getFavoriteTracks().first() } ?: retainedFavoriteTracks

    fun getRecentlyPlayedTracks(): Flow<List<MusicTrack>> = recentSnapshots

    private val playlistSnapshots by lazy {
        sharedLibrarySnapshots("playlists", flow { emitAll(musicDao.getAllPlaylists()) }
            .flowOn(AppDispatchers.Database)) { retainedPlaylists = it }
    }
    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistSnapshots

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
        val rows = withContext(AppDispatchers.CpuBounded) { tracks.map { TrackEntity.fromMusicTrack(it) } }
        return withContext(AppDispatchers.Database) {
            musicDao.createPlaylistWithEntries(PlaylistEntity(title = title, description = description, coverUrl = coverUrl), rows)
        }
    }

    suspend fun deletePlaylist(playlistId: Long) {
        musicDao.deletePlaylist(playlistId)
    }

    suspend fun renamePlaylist(playlistId: Long, newTitle: String) {
        musicDao.updatePlaylistTitle(playlistId, newTitle.trim())
    }

    suspend fun addTrackToPlaylist(playlistId: Long, track: MusicTrack) {
        musicDao.appendPlaylistTrack(playlistId, TrackEntity.fromMusicTrack(track))
    }

    suspend fun removePlaylistEntry(playlistId: Long, entryId: Long) = musicDao.removePlaylistEntry(playlistId, entryId)

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
        rejectedUrls: Set<String> = emptySet(),
        onAddonLookupFinished: (suspend (Long) -> Unit)? = null,
        preferredSourceId: String? = null,
        manualUpgrade: Boolean = false
    ): MusicTrack = withContext(AppDispatchers.Io) {
        repeat(3) {
            context?.let { com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(it) }
            val monitor = context?.let { com.example.playback.NetworkQualityMonitor.getInstance(it) }
            val sourcePolicy = monitor?.sourcePolicySnapshot()
            val quality = sourcePolicy?.ceiling?.audioQuality ?: AudioQuality.ULTRA_HD_320
            try {
                val identity = withContext(AppDispatchers.CpuBounded) { RecordingIdentity.key(track) }
                val requestKey = "playback-source:${track.id}:$identity:${sourcePolicy?.key}:$forceRefresh:$currentSourceOnly:$preferYouTube:$preferredSourceId:$manualUpgrade:${failedUrl.orEmpty()}:${rejectedUrls.sorted().joinToString()}"
                val result = com.example.recommendation.DiscoveryRequestCoordinator.shared(requestKey, essential = true, network = false) {
                    com.example.recommendation.DiscoveryRequestCoordinator.playback {
                        streamResolver.resolve(track, quality, forceRefresh, failedUrl, preferYouTube = preferYouTube, jioDeadlineMs = jioDeadlineMs,
                            currentSourceOnly = currentSourceOnly, rejectedUrls = rejectedUrls,
                            maxBitrateBps = sourcePolicy?.ceiling?.maxBitrateBps ?: Int.MAX_VALUE, sourcePolicy = sourcePolicy,
                            preferredSourceId = preferredSourceId, manualUpgrade = manualUpgrade)
                    }.let(::listOf)
                }.firstOrNull() ?: throw TrackUnavailableException(track.title)
                currentCoroutineContext().ensureActive()
                if (sourcePolicy != null && monitor?.sourcePolicySnapshot()?.key != sourcePolicy.key) return@repeat
                if (sourcePolicy != null && !sourcePolicy.acceptsRemoteTrack(result)) {
                    com.example.playback.PlaybackDiagnostics.record(track.id, sourcePolicy, null,
                        com.example.playback.PlaybackDiagnostics.Stage.VALIDATION, com.example.playback.PlaybackDiagnostics.Reason.POLICY)
                    throw TrackUnavailableException(track.title, com.example.playback.PlaybackDiagnostics.unavailable(track.id, track.title, sourcePolicy))
                }
                // Sharing transports must never roll back newer cover/playlist metadata.
                return@withContext track.copy(audioUrl = result.audioUrl, source = result.source, bitrateKbps = result.bitrateKbps,
                    qualityBadge = result.qualityBadge, isLossless = result.isLossless,
                    primaryArtists = track.primaryArtists.ifEmpty { result.primaryArtists })
            } catch (e: TrackUnavailableException) {
                if (sourcePolicy != null && monitor?.sourcePolicySnapshot()?.key != sourcePolicy.key) return@repeat
                if (track.recommendationOnly && !currentSourceOnly && !e.temporary) recommendations.markUnavailable(track)
                throw e
            }
        }
        throw TrackUnavailableException(track.title, "Streaming settings changed during resolution. Please try again.")
    }

    suspend fun resolveYouTubeFallback(
        track: MusicTrack,
        quality: AudioQuality = AudioQuality.ULTRA_HD_320
    ): MusicTrack? = withContext(AppDispatchers.Io) {
        if (context != null) try { resolvePlayableTrack(track, forceRefresh = true, failedUrl = track.audioUrl, preferYouTube = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        else streamResolver.resolveYouTubeFallback(track, quality)
    }

    suspend fun getTracksByGenre(genre: String, profile: UserProfile? = null): List<MusicTrack> = withContext(AppDispatchers.CpuBounded) {
        val userProfile = homeProfile(profile)
        val likedEntities = try {
            withContext(AppDispatchers.Database) { musicDao.getFavoriteTracks().first() }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        val likedIds = likedEntities.map { it.id }.toSet()

        val targetLanguages = userProfile.languages.filter { it.isNotBlank() }
        val targetCountry = userProfile.country

        // Query YouTube Music API with genre + language / country preferences
        val onlineGenreTracks = try {
            val queries = mutableListOf<String>()
            val primaryLang = targetLanguages.firstOrNull() ?: ""
            if (genre.equals("All", ignoreCase = true)) {
                if (primaryLang.isNotBlank()) queries.add("$primaryLang Top Hit Songs")
                queries.add("$targetCountry Top Hits")
                queries.add("Top Hit Songs")
            } else {
                if (primaryLang.isNotBlank() && !genre.equals(primaryLang, true)) {
                    queries.add("$primaryLang $genre hits")
                }
                if (targetLanguages.size > 1 && !genre.equals(targetLanguages[1], true)) {
                    queries.add("${targetLanguages[1]} $genre hits")
                }
                if (targetCountry.isNotBlank() && !CountryData.hasCountryNameInTitle(genre, targetCountry)) {
                    queries.add("$genre hits $targetCountry")
                }
                queries.add("$genre top hits")
            }

            val fetched = mutableListOf<MusicTrack>()
            for (q in queries.distinct().take(3)) {
                try {
                    fetched.addAll(YouTubeMusicApiService.searchSongs(q, limit = 15))
                } catch (ce: CancellationException) { throw ce } catch (_: Exception) {}
                if (fetched.size >= 25) break
            }
            if (fetched.isEmpty()) {
                YouTubeMusicApiService.getGenreTracks(genre, limit = 25)
            } else {
                fetched
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }

        val localGenreTracks = if (genre.equals("All", ignoreCase = true)) {
            MusicDataSource.curatedTracks
        } else {
            MusicDataSource.curatedTracks.filter { it.genre.equals(genre, ignoreCase = true) }
        }

        val allTracks = (onlineGenreTracks + localGenreTracks)
            .distinctBy { "${it.title.lowercase().trim()}_${it.artist.lowercase().trim()}" }
            .map { it.copy(isLiked = likedIds.contains(it.id)) }

        // Filter & prioritize songs based on country & language preferences
        val scored = allTracks.map { track ->
            var score = 0
            val lowerTitle = track.title.lowercase()
            val lowerArtist = track.artist.lowercase()
            val lowerLang = track.language.lowercase()

            for (lang in targetLanguages) {
                val l = lang.lowercase()
                if (lowerLang == l || lowerTitle.contains(l) || lowerArtist.contains(l)) {
                    score += 50
                    break
                }
            }
            if (targetCountry.isNotBlank() && !CountryData.hasCountryNameInTitle(track.title, targetCountry)) {
                val c = targetCountry.lowercase()
                if (lowerTitle.contains(c) || lowerArtist.contains(c)) {
                    score += 20
                }
            }
            track to score
        }

        val prioritized = scored.sortedByDescending { it.second }.map { it.first }
        prioritized.ifEmpty { allTracks }
    }

    suspend fun getGenreAlbums(genre: String, profile: UserProfile? = null,
        fallbackTracks: List<MusicTrack>? = null): List<CatalogAlbum> = withContext(AppDispatchers.CpuBounded) {
        val userProfile = homeProfile(profile)
        val albumsFromCatalog = try {
            catalogBrowser.getGenreAlbums(
                genre = genre,
                country = userProfile.country,
                languages = userProfile.languages,
                limit = 12
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        if (albumsFromCatalog.isNotEmpty()) return@withContext albumsFromCatalog

        // Fallback: extract albums from tracks related to the genre
        val genreTracks = fallbackTracks ?: getTracksByGenre(genre, userProfile)
        genreTracks.filter { it.album.isNotBlank() && it.album != "Single" && it.album != "Online Stream" && it.album != "Unknown" }
            .distinctBy { it.album.lowercase().trim() }
            .take(10)
            .map { track ->
                CatalogAlbum(
                    id = if (track.id.startsWith("online_")) track.id.removePrefix("online_") else "album_${track.album.hashCode()}",
                    title = track.album,
                    artists = listOf(CatalogArtist(id = "", name = track.artist)),
                    image = track.coverUrl,
                    year = track.year.ifBlank { "2024" }
                )
            }
    }

    internal suspend fun hasRecentLosslessAttempt(track: MusicTrack) = streamResolver.hasRecentLosslessAttempt(track)

    suspend fun findLosslessUpgrade(track: MusicTrack, automatic: Boolean = false): MusicTrack? = withContext(AppDispatchers.Io) {
        val monitor = context?.let(com.example.playback.NetworkQualityMonitor::getInstance)
        context?.let { com.example.data.local.OtherSettingsPreferences.loadPlaybackSources(it) }
        val policy = monitor?.sourcePolicySnapshot()
        if (policy?.allowsArchive != true) {
            com.example.playback.LosslessDiagnostics.record(track.id,
                com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "discovery")
            return@withContext null
        }
        fun allowed() = monitor?.sourcePolicySnapshot()?.let { it.allowsArchive && it.key == policy.key } == true
        val key = withContext(AppDispatchers.CpuBounded) { RecordingIdentity.key(track) }
        if (track.id.startsWith("ia_")) {
            val originalUrl = InternetArchiveApiService.originalFileUrl(track)
            if (originalUrl != null) InternetArchiveApiService.verifyDirectRecording(track.copy(audioUrl = originalUrl)) {
                allowed()
            }?.takeUnless { StreamResolver.isLosslessUrlRejected(it.audioUrl) ||
                (rejectedLosslessUrls.get(it.audioUrl) ?: 0L) > android.os.SystemClock.elapsedRealtime() }
                ?.let { return@withContext it }
        }
        fun missedRecently() = losslessMisses.get(key)?.let { android.os.SystemClock.elapsedRealtime() < it } == true
        if (missedRecently()) return@withContext null
        if (losslessPageSearch == null) {
            val cached = StreamResolver.getCachedLosslessMatch(track)
            if (cached != null && (rejectedLosslessUrls.get(cached.audioUrl) ?: 0L) <= android.os.SystemClock.elapsedRealtime() &&
                !StreamResolver.isLosslessUrlRejected(cached.audioUrl)) {
                return@withContext cached
            }
        }
        // Reuse the process-wide reference-counted request sharing. Cancelling one
        // consumer cannot cancel a lookup another consumer still needs.
        val found = com.example.recommendation.DiscoveryRequestCoordinator.shared("archive-recording:$key:${policy.key}",
            essential = !automatic, network = false) {
            run lookup@{
                if (missedRecently() || !allowed()) return@lookup emptyList()
                val rejected = (rejectedLosslessUrls.snapshot().filterValues {
                    it > android.os.SystemClock.elapsedRealtime()
                }.keys + StreamResolver.getActiveRejectedUrls()).toSet()
                val matches = if (losslessPageSearch == null) {
                    val match = streamResolver.discoverLosslessBackground(track, rejected, ::allowed)
                    if (match == null) {
                        val reason = com.example.playback.LosslessDiagnostics.last(track.id)?.reason
                        val ttl = if (reason == com.example.playback.LosslessDiagnostics.Reason.NO_RESULTS) 90_000L else 10_000L
                        losslessMisses.put(key, android.os.SystemClock.elapsedRealtime() + ttl)
                        emptyList()
                    } else {
                        listOf(match)
                    }
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
                        if (!allowed()) break
                        val page = losslessPageSearch.invoke(query, if (index == 0) 4 else 6)
                        successful = successful && page.isSuccessful
                        failure = page.failure ?: failure
                        exhaustive = exhaustive && page.exhaustive && page.nextPage == null
                        tracks.addAll(page.tracks)
                        if (page.tracks.any { StreamResolver.matchesLosslessUpgrade(track, it) }) break
                    }
                    val result = InternetArchiveApiService.LosslessSearchPage(tracks, isSuccessful = successful,
                        failure = failure, exhaustive = exhaustive)
                    if (!allowed()) {
                        com.example.playback.LosslessDiagnostics.record(track.id,
                            com.example.playback.LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, "discovery-policy")
                        return@lookup emptyList()
                    }
                    val candidates = withContext(AppDispatchers.CpuBounded) {
                        result.tracks.mapNotNull { candidate ->
                            if (candidate.audioUrl == track.audioUrl ||
                                candidate.audioUrl in rejected ||
                                (rejectedLosslessUrls.get(candidate.audioUrl) ?: 0L) > android.os.SystemClock.elapsedRealtime() ||
                                StreamResolver.isLosslessUrlRejected(candidate.audioUrl) ||
                                !StreamResolver.matchesLosslessUpgrade(track, candidate)) {
                                if (candidate.audioUrl != track.audioUrl && !StreamResolver.matchesLosslessUpgrade(track, candidate)) {
                                    com.example.playback.LosslessDiagnostics.record(track.id,
                                        com.example.playback.LosslessDiagnostics.Reason.SOURCE_VERIFICATION_FAILED, "candidate-verification")
                                }
                                null
                            } else RecordingIdentity.losslessConfidence(track, candidate)?.let { candidate to it }
                        }.sortedByDescending { it.second }.map { it.first }
                    }
                    if (candidates.isEmpty()) {
                        val reason = when {
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
                    candidates
                }
                matches
            }
        }.firstOrNull()
        if (!allowed()) return@withContext null
        if (found != null && losslessPageSearch == null) {
            StreamResolver.cacheVerifiedLosslessMatch(track, found)
        }
        found
    }

    internal suspend fun rejectLosslessUpgrade(track: MusicTrack, candidateUrl: String? = null) = withContext(AppDispatchers.CpuBounded) {
        // Reject the failed representation, not every authorized copy of this recording.
        val ttl = com.example.playback.LosslessDiagnostics.rejectionTtlMs(
            com.example.playback.LosslessDiagnostics.last(track.id)?.reason)
        if (candidateUrl != null) {
            rejectedLosslessUrls.put(candidateUrl, android.os.SystemClock.elapsedRealtime() + ttl)
            StreamResolver.rejectLosslessUrl(candidateUrl, ttl)
        }
        StreamResolver.invalidateLosslessMatch(track, candidateUrl)
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
