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

/** Owned by PlayerViewModel's scope; browsing never changes playback state. */
class CatalogBrowserState(private val repository: CatalogRepository, private val scope: CoroutineScope,
    initialQuality: AudioQuality = AudioQuality.ULTRA_HD_320) {
    // CPU preparation shares the process budget; generation checks and atomic state
    // updates protect publication. UI control stays on the caller's application scope.
    private val dataScope = scope + AppDispatchers.CpuBounded
    @Volatile private var audioQuality = initialQuality
    private val _search = MutableStateFlow(CatalogSearchState())
    val search = _search.asStateFlow()
    private val _detail = MutableStateFlow<CatalogDetailState?>(null)
    val detail = _detail.asStateFlow()
    private var searchJob: Job? = null
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
        if (quality == audioQuality) return
        audioQuality = quality
    }
    private fun startSearch(query: String, type: String, debounce: Boolean) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        _search.value = CatalogSearchState(query = query, type = type, loading = query.isNotBlank(), requestId = generation)
        if (query.isBlank()) return
        val quality = audioQuality
        searchJob = dataScope.launch {
            try {
                if (debounce) delay(300)
                val result = repository.search(query.trim(), type, quality = quality)
                ensureActive()
                _search.update { if (it.requestId != generation) it else
                    it.withPage(result.copy(tracks = liked(result.tracks))).copy(loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _search.update { if (it.requestId != generation) it else
                it.copy(loading = false, error = "Couldn't load results. Tap to retry.") } }
        }
    }
    fun loadMoreSearch() {
        val state = _search.value
        val page = state.page.nextPage ?: return
        if (state.loading || state.loadingMore || searchJob?.isActive == true) return
        val generation = searchGeneration
        val quality = audioQuality
        _search.update { it.copy(loadingMore = true, error = null) }
        searchJob = dataScope.launch {
            try {
                val result = repository.search(state.query.trim(), state.type, page, quality, state.page)
                if (generation != _search.value.requestId) return@launch
                val tracks = withContext(AppDispatchers.CpuBounded) {
                    com.example.data.remote.AudioRoutingService.mergeTracks(state.page.tracks + result.tracks)
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
                _search.update { if (it.requestId != generation) it else it.withPage(merged.copy(nextPage =
                    if (repeated && result.trackSearchContinuation == null) null else merged.nextPage)).copy(loadingMore = false) }
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
