package com.example.ui.viewmodel

import com.example.performance.AppDispatchers

import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.playback.AudioQuality
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CatalogSearchState(
    val query: String = "", val type: String = "All", val page: CatalogPage = CatalogPage(),
    val loading: Boolean = false, val loadingMore: Boolean = false, val error: String? = null,
    internal val requestId: Int = 0,
    internal val sourcePolicyKey: String = "",
    val display: CatalogSearchDisplay = CatalogSearchDisplay()
)
data class CatalogTopResult(val key: String, val score: Int,
    val track: MusicTrack? = null, val destination: CatalogDestination? = null)
data class CatalogSearchDisplay(val top: List<CatalogTopResult> = emptyList(),
    val songs: List<MusicTrack> = emptyList(), val albums: List<CatalogAlbum> = emptyList(),
    val artists: List<CatalogArtist> = emptyList())

/** Calculated only when search data changes, on the browser's Default dispatcher. */
private fun CatalogSearchState.withPage(page: CatalogPage): CatalogSearchState {
    val top = if (type != "All") emptyList() else
        (page.tracks.map { CatalogTopResult("song:${it.id}", CatalogNames.score(query, it.title, listOf(it.artist)), track = it) } +
            page.albums.map { CatalogTopResult("album:${it.id}", CatalogNames.score(query, it.title, it.artists.map { a -> a.name }), destination = CatalogDestination.Album(it)) } +
            page.artists.map { CatalogTopResult("artist:${it.id}", CatalogNames.score(query, it.name, it.aliases), destination = CatalogDestination.Artist(it)) })
            .filter { it.score > 0 }.sortedByDescending { it.score }.take(3)
    val topKeys = top.mapTo(mutableSetOf()) { it.key }
    val songs = page.tracks.filterNot { "song:${it.id}" in topKeys }.let { if (type == "All") it.take(5) else it }
    val albums = page.albums.filterNot { "album:${it.id}" in topKeys }.let { if (type == "All") it.take(5) else it }
    val artists = page.artists.filterNot { "artist:${it.id}" in topKeys }.let { if (type == "All") it.take(5) else it }
    return copy(page = page, display = CatalogSearchDisplay(top, songs, albums, artists))
}
data class CatalogDetailState(
    val destination: CatalogDestination, val tracks: List<MusicTrack> = emptyList(),
    val nextPage: Int? = null, val pending: List<MusicTrack> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val warning: String? = null,
    val scrollIndex: Int = 0, val scrollOffset: Int = 0, val initialized: Boolean = false,
    val artistContinuation: ArtistCatalogContinuation? = null,
    internal val requestId: Int = 0,
    val playlistContent: com.example.data.local.PlaylistWithTracks? = null
)
private fun CatalogDetailState.withTracks(tracks: List<MusicTrack>, pending: List<MusicTrack> = this.pending): CatalogDetailState {
    val playlist = (destination as? CatalogDestination.Playlist)?.playlist
    val content = playlist?.let { com.example.data.local.PlaylistWithTracks(
        com.example.data.local.PlaylistEntity(playlistId = it.id.toLongOrNull() ?: 0,
            title = it.title, coverUrl = it.image),
        tracks.map { track -> com.example.data.local.TrackEntity.fromMusicTrack(track) }).also { it.musicTracks } }
    return copy(tracks = tracks, pending = pending, playlistContent = content)
}

/** Preserve catalog ordering/first metadata, without deduplicating a large playlist on Main. */
internal suspend fun CatalogDetailState.preparePlaybackQueue(fallback: MusicTrack): List<MusicTrack> =
    withContext(AppDispatchers.CpuBounded) {
        (tracks + pending).distinctBy { it.id }.ifEmpty { listOf(fallback) }
    }

/** Owned by PlayerViewModel's scope; browsing never changes playback state. */
class CatalogBrowserState(private val repository: CatalogRepository, private val scope: CoroutineScope,
    initialQuality: AudioQuality = AudioQuality.ULTRA_HD_320,
    private val verifyPlayback: (suspend (MusicTrack, com.example.playback.PlaybackSourcePolicy) -> Boolean)? = null,
    private val archiveSearch: (suspend (String, Int, com.example.playback.PlaybackSourcePolicy, List<MusicTrack>) ->
        com.example.data.remote.InternetArchiveApiService.LosslessSearchPage)? = null,
    private val addonSearch: (suspend (String, com.example.playback.PlaybackSourcePolicy) -> List<MusicTrack>)? = null) {
    // CPU preparation shares the process budget; generation checks and atomic state
    // updates protect publication. UI control stays on the caller's application scope.
    private val dataScope = scope + AppDispatchers.CpuBounded
    @Volatile private var audioQuality = initialQuality
    private val _search = MutableStateFlow(CatalogSearchState())
    val search = _search.asStateFlow()
    private val _detail = MutableStateFlow<CatalogDetailState?>(null)
    val detail = _detail.asStateFlow()
    private var searchJob: Job? = null
    private var policySearchJob: Job? = null
    private var archiveSearchJob: Job? = null
    private var archivePaginationJob: Job? = null
    private var addonSearchJob: Job? = null
    @Volatile private var addonConfigurationKey = ""
    private var addonRequestKey: String? = null
    @Volatile private var addonCandidates = emptyList<MusicTrack>()
    @Volatile private var sourcePolicy = com.example.playback.PlaybackSourcePolicy(
        com.example.playback.AudioQualityCeiling.entries.first { it.audioQuality == initialQuality })
    @Volatile private var policyRevision = 0L
    @Volatile private var candidateRevision = 0L
    @Volatile private var catalogCandidates = emptyList<MusicTrack>()
    @Volatile private var archiveCandidates = emptyList<MusicTrack>()
    @Volatile private var archiveNext: Int? = 1
    @Volatile private var catalogNext: Int? = null
    private val verifiedSearchIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val checkedSearchIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val searchPublicationLock = Any()

    private fun archiveTrack(track: MusicTrack) = com.example.data.remote.AudioRoutingService.isArchiveResult(track)
    private fun eligible(track: MusicTrack, policy: com.example.playback.PlaybackSourcePolicy): Boolean =
        if (track.id.startsWith("addon_")) addonConfigurationKey.isNotBlank() else if (archiveTrack(track)) policy.allowsArchive else policy.maximum || !track.id.startsWith("yt_") ||
            verifyPlayback == null || track.id in verifiedSearchIds

    fun setStreamingAddon(settings: com.example.data.local.PlaybackIntegrationSettings) {
        val key = settings.addonUrl.takeIf { settings.addonEnabled }.orEmpty()
        synchronized(searchPublicationLock) {
            if (addonConfigurationKey == key) return
            addonConfigurationKey = key
            addonSearchJob?.cancel()
            addonRequestKey = null
            addonCandidates = emptyList()
            candidateRevision++
        }
        if (key.isBlank()) _search.update { state -> state.copy(
            page = state.page.copy(tracks = state.page.tracks.filterNot { it.id.startsWith("addon_") }),
            display = state.display.copy(songs = state.display.songs.filterNot { it.id.startsWith("addon_") },
                top = state.display.top.filterNot { it.track?.id?.startsWith("addon_") == true })) }
        dataScope.launch { publishEligibleSearch() }
        enrichSearch(_search.value.requestId, loadArchivePage = false)
    }

    fun setSourcePolicy(policy: com.example.playback.PlaybackSourcePolicy) {
        audioQuality = policy.ceiling.audioQuality
        val unchanged = sourcePolicy.key == policy.key
        if (unchanged) return
        synchronized(searchPublicationLock) {
            sourcePolicy = policy
            policyRevision++
            verifiedSearchIds.clear()
            checkedSearchIds.clear()
            candidateRevision++
            policySearchJob?.cancel()
            archiveSearchJob?.cancel()
            archivePaginationJob?.cancel()
        }
        // Keep the current query and all JioSaavn results. A late Archive response
        // cannot reappear after MAX is disabled; saved candidates remain internal.
        _search.update { state ->
            val page = state.page.copy(tracks = state.page.tracks.filter { eligible(it, policy) },
                albums = state.page.albums.filterNot { !policy.allowsArchive && it.id.startsWith("ia_") },
                artists = state.page.artists.filterNot { !policy.allowsArchive && it.id.startsWith("ia_") },
                playlists = state.page.playlists.filterNot { !policy.allowsArchive && it.id.startsWith("ia_") },
                nextPage = if (state.type in setOf("All", "Tracks")) catalogNext ?:
                    archiveNext.takeIf { archiveSearch != null && policy.allowsArchive } else state.page.nextPage)
            // Filtering existing prepared rows is immediate; relevance preparation
            // stays on CpuBounded and never discards the query or ordinary results.
            state.copy(page = page, sourcePolicyKey = policy.key, loadingMore = false, display = state.display.copy(
                top = state.display.top.filter { result -> result.track?.let { eligible(it, policy) }
                    ?: !(!policy.allowsArchive && result.key.substringAfter(':').startsWith("ia_")) },
                songs = state.display.songs.filter { eligible(it, policy) },
                albums = state.display.albums.filterNot { !policy.allowsArchive && it.id.startsWith("ia_") },
                artists = state.display.artists.filterNot { !policy.allowsArchive && it.id.startsWith("ia_") }))
        }
        dataScope.launch { publishEligibleSearch() }
        enrichSearch(_search.value.requestId, loadArchivePage = archiveCandidates.isEmpty())
    }

    private suspend fun publishEligibleSearch(page: CatalogPage? = null, loading: Boolean? = null,
        loadingMore: Boolean? = null, generation: Int = _search.value.requestId) {
        while (searchGeneration == generation && _search.value.requestId == generation) {
            currentCoroutineContext().ensureActive()
            val state = _search.value
            if (state.type !in setOf("All", "Tracks")) return
            val version = candidateRevision
            val revision = policyRevision
            val policy = sourcePolicy
            val tracks = catalogCandidates.filter { !archiveTrack(it) && eligible(it, policy) } +
                archiveCandidates.takeIf { policy.allowsArchive }.orEmpty() + addonCandidates
            val ranked = com.example.data.remote.AudioRoutingService.rankTracks(state.query, tracks,
                includeArchive = policy.allowsArchive, deduplicateRecordings = false)
            // Build presentation on CpuBounded, outside the publication lock.
            // The lock only protects the final generation/revision check and CAS.
            val prepared = state.withPage((page ?: state.page).copy(tracks = liked(ranked),
                nextPage = catalogNext ?: archiveNext.takeIf { archiveSearch != null && policy.allowsArchive }))
                .copy(sourcePolicyKey = policy.key, loading = loading ?: state.loading,
                    loadingMore = loadingMore ?: state.loadingMore)
            val published = synchronized(searchPublicationLock) {
                if (searchGeneration != generation || _search.value.requestId != generation) return
                candidateRevision == version && policyRevision == revision &&
                    sourcePolicy.key == policy.key && _search.compareAndSet(state, prepared)
            }
            if (published) return
            yield()
        }
    }

    private fun enrichSearch(generation: Int, loadArchivePage: Boolean = true) = synchronized(searchPublicationLock) {
        val state = _search.value
        if (searchGeneration != generation || state.requestId != generation ||
            state.query.isBlank() || state.type !in setOf("All", "Tracks")) return@synchronized
        policySearchJob?.cancel()
        val policy = sourcePolicy
        val revision = policyRevision
        fun current() = searchGeneration == generation && _search.value.requestId == generation &&
            policyRevision == revision && sourcePolicy.key == policy.key
        val addonKey = "$generation|${policy.key}|$addonConfigurationKey"
        if (addonSearch != null && addonConfigurationKey.isNotBlank() && addonRequestKey != addonKey) {
            addonSearchJob?.cancel()
            addonRequestKey = addonKey
            addonCandidates = emptyList()
            candidateRevision++
            addonSearchJob = dataScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val tracks = withContext(AppDispatchers.Io) { addonSearch.invoke(state.query, policy) }
                    synchronized(searchPublicationLock) {
                        if (!current() || addonRequestKey != addonKey) return@launch
                        addonCandidates = tracks.distinctBy { it.id }
                        candidateRevision++
                    }
                    publishEligibleSearch(loading = false, generation = generation)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Optional server failure leaves normal search intact. */ }
            }
            addonSearchJob?.start()
        }
        if (!policy.maximum && verifyPlayback != null) {
            policySearchJob = dataScope.launch(start = CoroutineStart.LAZY) {
                // Small prioritized batches, independently published. Shared repository
                // permits bound matching across repeated queries and both categories.
                for (batch in catalogCandidates.filter { it.id.startsWith("yt_") && it.id !in checkedSearchIds }
                    .chunked(2)) {
                    val verified = batch.map { track -> async(AppDispatchers.Io) {
                        try { track.takeIf { verifyPlayback.invoke(it, policy) } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    } }.awaitAll().filterNotNull()
                    synchronized(searchPublicationLock) {
                        if (!current()) return@launch
                        checkedSearchIds.addAll(batch.map { it.id })
                        verifiedSearchIds.addAll(verified.map { it.id })
                        candidateRevision++
                    }
                    publishEligibleSearch(generation = generation)
                    yield()
                }
            }
            policySearchJob?.start()
        }
        if (policy.allowsArchive && archiveSearch != null && !state.loading && loadArchivePage &&
            archiveNext != null && archiveSearchJob?.isActive != true) {
            val requestedPage = archiveNext!!
            archiveSearchJob = dataScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val page = withTimeoutOrNull(45_000L) { archiveSearch.invoke(state.query.trim(), requestedPage, policy,
                        catalogCandidates.take(2)) }
                        ?: return@launch
                    synchronized(searchPublicationLock) {
                        if (!current()) return@launch
                        archiveCandidates = (archiveCandidates + page.tracks).distinctBy { it.id }
                        archiveNext = page.nextPage ?: requestedPage.takeUnless { page.isSuccessful }
                        candidateRevision++
                    }
                    publishEligibleSearch(generation = generation)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Provider failures never clear usable catalog rows. */ }
                finally { _search.update { if (current() && archivePaginationJob?.isActive != true)
                    it.copy(loadingMore = false) else it } }
            }
            archiveSearchJob?.start()
        }
    }
    private var detailJob: Job? = null
    private var favoritesJob: Job? = null
    @Volatile private var favoriteIds: Set<String> = emptySet()
    private fun liked(tracks: List<MusicTrack>, ids: Set<String> = favoriteIds): List<MusicTrack> =
        if (tracks.none { it.isLiked != (it.id in ids) }) tracks else
            tracks.map { if (it.isLiked == (it.id in ids)) it else it.copy(isLiked = it.id in ids) }
    fun updateFavorites(ids: Set<String>) {
        if (ids == favoriteIds) return
        favoriteIds = ids
        favoritesJob?.cancel()
        favoritesJob = dataScope.launch {
            val retained = synchronized(details) { details.toMap() }
            ensureActive()
            if (favoriteIds !== ids) return@launch
            _search.update { state ->
                if (favoriteIds !== ids) return@update state
                // Favorite flags cannot change search relevance or section membership.
                // Patch prepared rows instead of rescoring every title/artist again.
                val tracks = liked(state.page.tracks, ids)
                if (tracks === state.page.tracks) state else state.copy(
                    page = state.page.copy(tracks = tracks),
                    display = state.display.copy(songs = liked(state.display.songs, ids),
                        top = state.display.top.map { result -> result.track?.let { track ->
                            result.copy(track = track.copy(isLiked = track.id in ids))
                        } ?: result }))
            }
            val refreshed = retained.mapValues { (_, state) ->
                val tracks = liked(state.tracks, ids)
                val pending = liked(state.pending, ids)
                if (tracks === state.tracks && pending === state.pending) state else state.withTracks(tracks, pending)
            }
            synchronized(details) {
                if (favoriteIds === ids) refreshed.forEach { (key, state) ->
                    // A newly loaded page must not be replaced by an older cache snapshot.
                    if (details[key] === retained[key]) details[key] = state
                }
            }
            // A cached destination may have opened while its cache was being refreshed.
            ensureActive()
            if (favoriteIds === ids) _detail.update {
                it?.let { state ->
                    val tracks = liked(state.tracks, ids)
                    val pending = liked(state.pending, ids)
                    if (tracks === state.tracks && pending === state.pending) state else state.withTracks(tracks, pending)
                }
            }
        }
    }
    @Volatile private var searchGeneration = 0
    @Volatile private var detailGeneration = 0
    private val scrollPositions = object : android.util.LruCache<String, Pair<Int, Int>>(20) {}
    private val detailAliases: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(40, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 40
        })
    private val details: MutableMap<String, CatalogDetailState> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, CatalogDetailState>(12, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogDetailState>?) = size > 10
        })

    fun queryChanged(query: String) { startSearch(query, _search.value.type, debounce = true) }
    fun selectType(type: String) {
        if (type != _search.value.type) startSearch(_search.value.query, type, debounce = false)
    }
    fun retrySearch() { startSearch(_search.value.query, _search.value.type, debounce = false) }
    fun setAudioQuality(quality: AudioQuality) {
        setSourcePolicy(sourcePolicy.copy(ceiling = com.example.playback.AudioQualityCeiling.entries.first { it.audioQuality == quality }))
    }
    private fun startSearch(query: String, type: String, debounce: Boolean) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        policySearchJob?.cancel()
        archiveSearchJob?.cancel()
        archivePaginationJob?.cancel()
        addonSearchJob?.cancel()
        synchronized(searchPublicationLock) {
            catalogCandidates = emptyList()
            archiveCandidates = emptyList()
            archiveNext = 1
            catalogNext = null
            addonCandidates = emptyList()
            addonRequestKey = null
            verifiedSearchIds.clear()
            checkedSearchIds.clear()
            candidateRevision++
        }
        _search.value = CatalogSearchState(query = query, type = type, loading = query.isNotBlank(), requestId = generation,
            sourcePolicyKey = sourcePolicy.key)
        if (query.isBlank()) return
        val quality = audioQuality
        searchJob = dataScope.launch {
            try {
                if (debounce) delay(300)
                enrichSearch(generation, loadArchivePage = false)
                val result = repository.search(query.trim(), type, quality = quality)
                ensureActive()
                if (_search.value.requestId != generation) return@launch
                synchronized(searchPublicationLock) {
                    if (_search.value.requestId != generation) return@launch
                    catalogCandidates = result.tracks
                    catalogNext = result.nextPage
                    candidateRevision++
                }
                if (type in setOf("All", "Tracks")) publishEligibleSearch(result, loading = false, generation = generation)
                else _search.update { if (it.requestId != generation) it else it.withPage(result).copy(loading = false) }
                // Publishing ordinary results never awaits the independent Archive
                // flight; its empty-page fallback can now reuse recording evidence.
                enrichSearch(generation)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _search.update { if (it.requestId != generation) it else
                it.copy(loading = false, error = "Couldn't load results. Tap to retry.") } }
        }
    }
    fun loadMoreSearch() {
        val state = _search.value
        val page = state.page.nextPage ?: return
        if (state.loading || state.loadingMore || searchJob?.isActive == true || archivePaginationJob?.isActive == true) return
        if (state.type in setOf("All", "Tracks") && catalogNext == null && archiveSearch != null && sourcePolicy.allowsArchive) {
            _search.update { it.copy(loadingMore = true, error = null) }
            val previous = archiveSearchJob
            val revision = policyRevision
            // A completed page can be visible just before its worker finishes.
            // Join that worker asynchronously rather than dropping the next-page
            // request or restarting the ordinary JioSaavn/YouTube catalog search.
            archivePaginationJob = dataScope.launch(start = CoroutineStart.LAZY) {
                fun current() = searchGeneration == state.requestId && policyRevision == revision
                try {
                    previous?.join()
                    ensureActive()
                    if (!current()) return@launch
                    enrichSearch(state.requestId)
                    archiveSearchJob?.join()
                } finally { _search.update { if (current() && it.requestId == state.requestId)
                    it.copy(loadingMore = false) else it } }
            }
            archivePaginationJob?.start()
            return
        }
        val generation = searchGeneration
        val quality = audioQuality
        _search.update { it.copy(loadingMore = true, error = null) }
        searchJob = dataScope.launch {
            try {
                val result = repository.search(state.query.trim(), state.type, page, quality, state.page)
                if (generation != _search.value.requestId) return@launch
                val tracks = withContext(AppDispatchers.CpuBounded) {
                    com.example.data.remote.AudioRoutingService.mergeTracks(catalogCandidates + result.tracks)
                }
                if (generation != _search.value.requestId) return@launch
                val merged = CatalogPage(
                    tracks = liked(tracks),
                    albums = (state.page.albums + result.albums).distinctBy { it.id },
                    artists = (state.page.artists + result.artists).distinctBy { it.id },
                    playlists = (state.page.playlists + result.playlists).distinctBy { it.id }, nextPage = result.nextPage,
                    warning = result.warning, trackSearchContinuation = result.trackSearchContinuation)
                val repeated = result.tracks.isNotEmpty() && result.tracks.all { song -> state.page.tracks.any { it.id == song.id } } ||
                    result.albums.isNotEmpty() && result.albums.all { album -> state.page.albums.any { it.id == album.id } } ||
                    result.artists.isNotEmpty() && result.artists.all { artist -> state.page.artists.any { it.id == artist.id } } ||
                    result.playlists.isNotEmpty() && result.playlists.all { playlist -> state.page.playlists.any { it.id == playlist.id } }
                ensureActive()
                if (_search.value.requestId != generation) return@launch
                synchronized(searchPublicationLock) {
                    if (_search.value.requestId != generation) return@launch
                    catalogCandidates = tracks
                    catalogNext = if (repeated && result.trackSearchContinuation == null) null else result.nextPage
                    candidateRevision++
                }
                if (state.type in setOf("All", "Tracks")) publishEligibleSearch(merged, loadingMore = false, generation = generation)
                else _search.update { if (it.requestId != generation) it else it.withPage(merged.copy(nextPage =
                    catalogNext)).copy(loadingMore = false) }
                enrichSearch(generation)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _search.update { if (it.requestId != generation) it else
                it.copy(loadingMore = false, error = "Couldn't load more. Tap to retry.") } }
        }
    }
    fun open(destination: CatalogDestination) {
        saveDetail()
        detailJob?.cancel(); detailGeneration++
        val key = detailAliases[destination.key] ?: destination.key
        val position = scrollPositions.get(key)
        _detail.value = details[key]?.let { it.copy(loading = false,
            scrollIndex = position?.first ?: it.scrollIndex, scrollOffset = position?.second ?: it.scrollOffset,
            requestId = detailGeneration) } ?: CatalogDetailState(destination, requestId = detailGeneration)
        if (_detail.value?.initialized != true) loadMoreDetail()
    }
    /** Playback continuation captures catalog identity only; closing the page cannot cancel music. */
    fun playlistPlaybackContinuation(state: CatalogDetailState): com.example.playback.CollectionPlaybackContinuation? {
        val playlist = (state.destination as? CatalogDestination.Playlist)?.playlist ?: return null
        val page = state.nextPage ?: return null
        val catalog = repository
        return com.example.playback.CollectionPlaybackContinuation(page) { cursor ->
            catalog.playlistSongs(playlist, cursor)
        }
    }

    fun close() { saveDetail(); detailJob?.cancel(); detailGeneration++; _detail.value = null }
    private fun saveDetail() { _detail.value?.let { details[it.destination.key] = it.copy(loading = false) } }
    fun rememberScroll(index: Int, offset: Int) {
        // Do not emit Home/player state on every scroll frame.
        _detail.value?.let { scrollPositions.put(it.destination.key, index to offset) }
    }
    fun loadMoreDetail() {
        val state = _detail.value ?: return
        if (state.loading || (state.initialized && state.nextPage == null && state.pending.isEmpty())) return
        val generation = detailGeneration
        _detail.update { it?.copy(loading = true, error = null) }
        detailJob = dataScope.launch {
            try {
                var destination = state.destination
                val page: CatalogPage = if (state.pending.isNotEmpty()) CatalogPage(tracks = state.pending,
                    nextPage = state.nextPage, warning = state.warning, artistContinuation = state.artistContinuation)
                else when (val selected = destination) {
                    is CatalogDestination.Artist -> {
                        val artist = if (state.artistContinuation != null) selected.artist else repository.resolveArtist(selected.artist)
                        repository.artistSongs(artist, state.nextPage ?: 0, state.artistContinuation, state.tracks).also {
                            // Retain verified fallback aliases/IDs for subsequent pages and reopening.
                            destination = CatalogDestination.Artist(it.resolvedArtist ?: artist)
                        }
                    }
                    is CatalogDestination.Album -> repository.albumSongs(selected.album)
                    is CatalogDestination.Playlist -> repository.playlistSongs(selected.playlist, state.nextPage ?: 1)
                }
                if (generation != _detail.value?.requestId) return@launch
                val fresh = withContext(AppDispatchers.CpuBounded) {
                    if (destination is CatalogDestination.Album) page.tracks else repository.uniqueTracks(state.tracks + page.tracks).drop(state.tracks.size)
                }
                if (generation != _detail.value?.requestId) return@launch
                val album = destination is CatalogDestination.Album
                val added = if (album) fresh else fresh.take(10)
                val pending = if (album) emptyList() else fresh.drop(10)
                // A fallback search can begin at page one after an artist-endpoint
                // failure. Its first page may overlap loaded songs; keep its own cursor
                // so later, genuinely new matches remain reachable without duplicates.
                val repeated = page.tracks.isNotEmpty() && fresh.isEmpty() && (page.nextPage ?: 0) >= 0 &&
                    page.artistContinuation == null
                ensureActive()
                _detail.update { current -> if (current?.requestId != generation) current else current.copy(destination = destination,
                    nextPage = if (repeated) null else page.nextPage, loading = false,
                    initialized = true, error = null, warning = page.warning,
                    artistContinuation = page.artistContinuation).withTracks(liked(state.tracks + added), liked(pending)) }
                // Store both name-only Home routes and provider-ID routes against the same loaded data.
                synchronized(details) {
                    if (generation == _detail.value?.requestId) {
                        detailAliases[state.destination.key] = destination.key
                        _detail.value?.let { details[destination.key] = it }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _detail.update { if (it?.requestId != generation) it else
                it.copy(loading = false, error = "Couldn't load this catalog page. Tap to retry.") } }
        }
    }
}
