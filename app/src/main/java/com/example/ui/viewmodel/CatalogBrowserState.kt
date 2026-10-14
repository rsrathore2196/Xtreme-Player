package com.example.ui.viewmodel

import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.playback.AudioQuality
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CatalogSearchState(
    val query: String = "", val type: String = "All", val page: CatalogPage = CatalogPage(),
    val loading: Boolean = false, val loadingMore: Boolean = false, val error: String? = null
)
data class CatalogDetailState(
    val destination: CatalogDestination, val tracks: List<MusicTrack> = emptyList(),
    val nextPage: Int? = null, val pending: List<MusicTrack> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val warning: String? = null,
    val scrollIndex: Int = 0, val scrollOffset: Int = 0, val initialized: Boolean = false,
    val artistContinuation: ArtistCatalogContinuation? = null
)

/** Owned by PlayerViewModel's scope; browsing never changes playback state. */
class CatalogBrowserState(private val repository: CatalogRepository, private val scope: CoroutineScope,
    initialQuality: AudioQuality = AudioQuality.ULTRA_HD_320) {
    private var audioQuality = initialQuality
    private val _search = MutableStateFlow(CatalogSearchState())
    val search = _search.asStateFlow()
    private val _detail = MutableStateFlow<CatalogDetailState?>(null)
    val detail = _detail.asStateFlow()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var favoriteIds: Set<String> = emptySet()
    private fun liked(tracks: List<MusicTrack>) = tracks.map { it.copy(isLiked = it.id in favoriteIds) }
    fun updateFavorites(ids: Set<String>) {
        if (ids == favoriteIds) return
        favoriteIds = ids
        _search.update { it.copy(page = it.page.copy(tracks = liked(it.page.tracks))) }
        _detail.update { it?.copy(tracks = liked(it.tracks), pending = liked(it.pending)) }
        details.replaceAll { _, state -> state.copy(tracks = liked(state.tracks), pending = liked(state.pending)) }
    }
    private var searchGeneration = 0
    private var detailGeneration = 0
    private val scrollPositions = object : android.util.LruCache<String, Pair<Int, Int>>(20) {}
    private val detailAliases = object : LinkedHashMap<String, String>(40, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 40
    }
    private val details = object : LinkedHashMap<String, CatalogDetailState>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogDetailState>?) = size > 10
    }

    fun queryChanged(query: String) { startSearch(query, _search.value.type, debounce = true) }
    fun selectType(type: String) {
        if (type != _search.value.type) startSearch(_search.value.query, type, debounce = false)
    }
    fun retrySearch() { startSearch(_search.value.query, _search.value.type, debounce = false) }
    fun setAudioQuality(quality: AudioQuality) {
        if (quality == audioQuality) return
        audioQuality = quality
        if (_search.value.query.isNotBlank() && _search.value.type in setOf("All", "Tracks"))
            startSearch(_search.value.query, _search.value.type, debounce = false)
    }
    private fun startSearch(query: String, type: String, debounce: Boolean) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        _search.value = CatalogSearchState(query = query, type = type, loading = query.isNotBlank())
        if (query.isBlank()) return
        val quality = audioQuality
        searchJob = scope.launch {
            try {
                if (debounce) delay(300)
                val result = repository.search(query.trim(), type, quality = quality)
                if (generation == searchGeneration) _search.update { it.copy(page = result.copy(tracks = liked(result.tracks)), loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == searchGeneration) _search.update { it.copy(loading = false, error = "Couldn't load results. Tap to retry.") } }
        }
    }
    fun loadMoreSearch() {
        val state = _search.value
        val page = state.page.nextPage ?: return
        if (state.loading || state.loadingMore || searchJob?.isActive == true) return
        val generation = searchGeneration
        val quality = audioQuality
        _search.update { it.copy(loadingMore = true, error = null) }
        searchJob = scope.launch {
            try {
                val result = repository.search(state.query.trim(), state.type, page, quality, state.page)
                if (generation != searchGeneration) return@launch
                val tracks = withContext(Dispatchers.Default) {
                    repository.uniqueTracks(state.page.tracks + result.tracks,
                        preferLossless = quality == AudioQuality.HI_RES_LOSSLESS)
                }
                if (generation != searchGeneration) return@launch
                val merged = CatalogPage(
                    tracks = liked(tracks),
                    albums = (state.page.albums + result.albums).distinctBy { it.id },
                    artists = (state.page.artists + result.artists).distinctBy { it.id },
                    playlists = (state.page.playlists + result.playlists).distinctBy { it.id }, nextPage = result.nextPage,
                    warning = result.warning, losslessContinuation = result.losslessContinuation)
                val repeated = result.tracks.isNotEmpty() && result.tracks.all { song -> state.page.tracks.any { it.id == song.id } } ||
                    result.albums.isNotEmpty() && result.albums.all { album -> state.page.albums.any { it.id == album.id } } ||
                    result.artists.isNotEmpty() && result.artists.all { artist -> state.page.artists.any { it.id == artist.id } } ||
                    result.playlists.isNotEmpty() && result.playlists.all { playlist -> state.page.playlists.any { it.id == playlist.id } }
                _search.update { it.copy(page = merged.copy(nextPage =
                    if (repeated && result.losslessContinuation == null) null else merged.nextPage), loadingMore = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == searchGeneration) _search.update { it.copy(loadingMore = false, error = "Couldn't load more. Tap to retry.") } }
        }
    }
    fun open(destination: CatalogDestination) {
        saveDetail()
        detailJob?.cancel(); detailGeneration++
        val key = detailAliases[destination.key] ?: destination.key
        val position = scrollPositions.get(key)
        _detail.value = details[key]?.let { it.copy(loading = false,
            scrollIndex = position?.first ?: it.scrollIndex, scrollOffset = position?.second ?: it.scrollOffset) } ?: CatalogDetailState(destination)
        if (_detail.value?.initialized != true) loadMoreDetail()
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
        detailJob = scope.launch {
            try {
                var destination = state.destination
                val page: CatalogPage = if (state.pending.isNotEmpty()) CatalogPage(tracks = state.pending,
                    nextPage = state.nextPage, warning = state.warning, artistContinuation = state.artistContinuation)
                else when (val selected = destination) {
                    is CatalogDestination.Artist -> {
                        val artist = if (state.artistContinuation != null) selected.artist else repository.resolveArtist(selected.artist)
                        destination = CatalogDestination.Artist(artist)
                        repository.artistSongs(artist, state.nextPage ?: 0, state.artistContinuation, state.tracks)
                    }
                    is CatalogDestination.Album -> repository.albumSongs(selected.album)
                    is CatalogDestination.Playlist -> repository.playlistSongs(selected.playlist, state.nextPage ?: 1)
                }
                if (generation != detailGeneration) return@launch
                val fresh = withContext(Dispatchers.Default) {
                    if (destination is CatalogDestination.Album) page.tracks else repository.uniqueTracks(state.tracks + page.tracks).drop(state.tracks.size)
                }
                if (generation != detailGeneration) return@launch
                val album = destination is CatalogDestination.Album
                val added = if (album) fresh else fresh.take(10)
                val pending = if (album) emptyList() else fresh.drop(10)
                // A fallback search can begin at page one after an artist-endpoint
                // failure. Its first page may overlap loaded songs; keep its own cursor
                // so later, genuinely new matches remain reachable without duplicates.
                val repeated = page.tracks.isNotEmpty() && fresh.isEmpty() && (page.nextPage ?: 0) >= 0 &&
                    page.artistContinuation == null
                _detail.update { current -> current?.copy(destination = destination, tracks = liked(state.tracks + added),
                    nextPage = if (repeated) null else page.nextPage, pending = liked(pending), loading = false,
                    initialized = true, error = null, warning = page.warning,
                    artistContinuation = page.artistContinuation) }
                // Store both name-only Home routes and provider-ID routes against the same loaded data.
                detailAliases[state.destination.key] = destination.key
                _detail.value?.let { details[destination.key] = it }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == detailGeneration) _detail.update { it?.copy(loading = false, error = "Couldn't load this catalog page. Tap to retry.") } }
        }
    }
}
