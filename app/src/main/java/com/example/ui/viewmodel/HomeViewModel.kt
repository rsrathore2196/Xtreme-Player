package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserProfile
import com.example.data.local.UserProfilePreferences
import com.example.data.local.MusicDatabase
import com.example.data.local.ListeningEventEntity
import com.example.data.model.CountryData
import com.example.data.model.MusicTrack
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.UserTasteProfile
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onEach
import com.example.data.cache.HomeCardCache
import com.example.data.cache.SavedHomeCards
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.example.recommendation.PersonalizedHomeRecommendationEngine
import com.example.ui.ai.HomeShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.Calendar
import androidx.compose.runtime.Immutable

@Immutable
data class SectionState(
    val title: String = "",
    val subtitle: String = "",
    val tracks: List<MusicTrack> = emptyList(),
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String = ""
)

private fun SectionState.limitShelf() = copy(tracks = tracks.take(HomeRecommendationPolicy.MAX_SHELF_TRACKS))

@Immutable
data class RecommendationCards(val quick: SectionState, val era: SectionState,
    val chart: SectionState, val personalized: SectionState)

@Immutable
data class MostListenedArtist(
    val name: String,
    val playCount: Int,
    val tracks: List<MusicTrack>
)

@Immutable
data class HomeUiState(
    val greeting: String = "",
    val userProfile: UserProfile? = null,
    val heroTrack: MusicTrack? = null,
    val catalogTracks: List<MusicTrack> = emptyList(),
    val recentlyPlayed: List<MusicTrack> = emptyList(),
    val favoriteTracks: List<MusicTrack> = emptyList(),
    val pillsCardTracks: List<MusicTrack> = emptyList(),
    val ytmFeedState: SectionState = SectionState(),
    val eraHitsState: SectionState = SectionState(),
    val countryLanguageState: SectionState = SectionState(),
    val listeningHistoryState: SectionState = SectionState(),
    val personalizedState: SectionState = SectionState(),
    val isRefreshing: Boolean = false
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    application: Application
) : AndroidViewModel(application) {

    // Serialize Home's mutable request/cache ownership off Main. Suspended IO requests
    // still run concurrently, and all jobs remain children of the ViewModel lifecycle.
    private val dataDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val dataScope = viewModelScope + dataDispatcher

    // 1. Instant User Profile (< 1ms)
    private val _userProfile = MutableStateFlow(UserProfilePreferences.getUserProfile(application))
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()
    private val shelfCache = HomeCardCache(application)
    private val initialCards = shelfCache.peek(profileKey())
    private val _currentStage = MutableStateFlow(LoadingStage.PRIORITY_1_INSTANT_SHELL)
    val currentStage: StateFlow<LoadingStage> = _currentStage.asStateFlow()

    // 2. Instant Local Catalog state (Local-First: Room/Memory)
    private val _catalogTracks = MutableStateFlow(initialCards?.catalog.orEmpty())
    val catalogTracks: StateFlow<List<MusicTrack>> = _catalogTracks.asStateFlow()
    private val homeVisible = MutableStateFlow(false)
    private val homeScrolling = MutableStateFlow(false)
    fun setHomeScrolling(active: Boolean) {
        homeScrolling.value = active
        com.example.recommendation.DiscoveryRequestCoordinator.homeScrolling(active)
    }
    private val restored = CompletableDeferred<Unit>()
    private val eventsLoaded = CompletableDeferred<Unit>()
    private val favoritesLoaded = CompletableDeferred<Unit>()
    private var savedSignal: String? = initialCards?.signal
    private var savedGeneration = initialCards?.generation ?: -1L
    private val lastEventStamps = mutableMapOf<Long, com.example.data.local.ActualPlayStamp>()
    private val retainedEvents = mutableMapOf<Long, ListeningEventEntity>()
    private fun profileKey(profile: UserProfile = _userProfile.value) = "${profile.countryCode}|${profile.languages.joinToString(",")}"
    private val cachedHero = MutableStateFlow(initialCards?.history?.tracks?.firstOrNull() ?: initialCards?.catalog?.firstOrNull())
    private val cachedHistory = MutableStateFlow(initialCards?.history?.tracks)

    private val database by lazy { MusicDatabase.getDatabase(application) }
    private val listeningEventDao by lazy { database.listeningEventDao() }
    private val personalizedEngine by lazy { PersonalizedHomeRecommendationEngine(listeningEventDao) }
    val favoriteTracks = homeVisible.flatMapLatest { visible ->
        if (visible) repository.getFavoriteTracks().onEach { favoritesLoaded.complete(Unit) } else emptyFlow()
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    private val actualPlays = homeVisible.flatMapLatest { visible ->
        if (!visible) emptyFlow() else flow {
            restored.await()
            if (savedCandidates != null && !eventsLoaded.isCompleted) awaitHomeIdle()
            var previousCount = -1
            var previousIds: Set<Long>? = null
            combine(listeningEventDao.observeActualPlayStamps(),
                listeningEventDao.observeActualPlayCount().distinctUntilChanged()) { stamps, count -> stamps to count }
                .flowOn(Dispatchers.IO).collect { (stamps, count) ->
                val ids = stamps.mapTo(mutableSetOf()) { it.id }
                val reloadHistory = previousIds != ids || previousCount != count
                // Restore the long-term window once, and on structural history changes only.
                // Playback milestones update at most 250 rows without truncating older preferences.
                if (reloadHistory) {
                    val history = withContext(Dispatchers.IO) { listeningEventDao.getActualPlays() }
                    retainedEvents.clear()
                    history.forEach { retainedEvents[it.id] = it }
                    previousIds = ids
                    previousCount = count
                    lastEventStamps.clear()
                }
                val changed = if (reloadHistory) emptyList() else stamps.filter { lastEventStamps[it.id] != it }.map { it.id }
                val pages = changed.chunked(900)
                val rows = withContext(Dispatchers.IO) { pages.flatMap { listeningEventDao.getEventsByIds(it) } }
                rows.forEach { retainedEvents[it.id] = it }
                lastEventStamps.clear(); stamps.forEach { lastEventStamps[it.id] = it }
                emit(retainedEvents.values.sortedByDescending { it.timestamp }.take(5000))
                if (stamps.isEmpty()) { cachedHero.value = null; cachedHistory.value = null }
                eventsLoaded.complete(Unit)
            }
        }
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.Eagerly, emptyList())
    private data class HistoryRows(val ids: List<String> = emptyList(), val tracks: List<MusicTrack> = emptyList())
    private val historyRows = combine(actualPlays.map { events -> events.map { it.trackId }.distinct() }.distinctUntilChanged(), homeVisible) { ids, visible -> ids to visible }.flatMapLatest { (ids, visible) ->
        if (!visible) return@flatMapLatest emptyFlow()
        val pages = ids.chunked(900).map { database.musicDao().observeTracksByIds(it)
            .flowOn(Dispatchers.IO).distinctUntilChanged() }
        if (pages.isEmpty()) kotlinx.coroutines.flow.flowOf(HistoryRows(ids)) else combine(pages) { groups ->
            HistoryRows(ids, groups.flatMap { rows -> rows.map { it.toMusicTrack() } })
        }
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.Eagerly, HistoryRows())
    private val historyMetadata = historyRows.map { it.tracks }.stateIn(dataScope, SharingStarted.Eagerly, emptyList())
    private data class TasteInputs(val events: List<ListeningEventEntity>, val tracks: List<MusicTrack>,
        val favorites: List<MusicTrack>, val preferences: UserProfile)
    private data class TasteSnapshot(val inputs: TasteInputs? = null,
        val taste: UserTasteProfile = UserTasteProfile.COLD_START_PROFILE)
    private var lastTasteInputs: TasteInputs? = null
    private var retainedTaste = UserTasteProfile.COLD_START_PROFILE
    private val tasteSnapshot = combine(actualPlays, historyMetadata, favoriteTracks, _userProfile) { events, tracks, favorites, preferences ->
        TasteInputs(events, tracks, favorites, preferences)
    }.distinctUntilChanged().mapLatest { inputs ->
        restored.await()
        eventsLoaded.await(); favoritesLoaded.await()
        // Restarting a screen subscription is not a new listening signal. Keep the same decay
        // snapshot until real data changes, rather than reshuffling on every tab return.
        if (inputs != lastTasteInputs) {
            awaitHomeIdle()
            BackgroundWorkCoordinator.work {
                val taste = UserTasteProfile.fromEvents(inputs.events, inputs.tracks, inputs.favorites)
                currentCoroutineContext().ensureActive()
                retainedTaste = taste.copy(languageAffinities = inputs.preferences.languages.associate {
                    HomeRecommendationPolicy.normalize(it) to 0.35f
                } + taste.languageAffinities)
                lastTasteInputs = inputs
            }
        }
        TasteSnapshot(inputs, retainedTaste)
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.WhileSubscribed(5000L), TasteSnapshot())
    private val tasteProfile = tasteSnapshot.map { it.taste }
        .stateIn(dataScope, SharingStarted.WhileSubscribed(5000L), UserTasteProfile.COLD_START_PROFILE)
    val recentlyPlayed: StateFlow<List<MusicTrack>> = combine(actualPlays, historyRows, cachedHistory) { events, rows, cached ->
        if (!eventsLoaded.isCompleted || rows.ids != events.map { it.trackId }.distinct() || events.none(UserTasteProfile::played)) cached.orEmpty() else {
            val byId = rows.tracks.associateBy { it.id }
            val seen = HomeRecommendationPolicy.Recordings()
            // The DAO already orders newest first; stop once the visible history is filled.
            events.asSequence().filter(UserTasteProfile::played)
                .mapNotNull { byId[it.trackId] }.filter { seen.add(it) }.take(30).toList()
        }
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.WhileSubscribed(5000L), cachedHistory.value.orEmpty())
    val heroSeedTrack: StateFlow<MusicTrack?> = combine(recentlyPlayed, favoriteTracks, _catalogTracks, cachedHero) { recent, favorites, catalog, cached ->
        (recent.asSequence() + listOfNotNull(cached).asSequence() + favorites.asSequence() + catalog.asSequence())
            .firstOrNull(com.example.recommendation.OriginalMusicFilter::isHomeRecommendation)
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.WhileSubscribed(5000L), cachedHero.value)

    // Helper to compute authoritative trending title
    fun getAuthoritativeTrendingTitle(countryDisplayName: String): String {
        return if (countryDisplayName.trim().equals("India", ignoreCase = true)) {
            "Trending in India"
        } else {
            "Trending in ${countryDisplayName.trim().ifBlank { "India" }}"
        }
    }

    fun resolveCountryDisplayName(profile: UserProfile): String {
        val countryItem = CountryData.findCountry(profile.countryCode)
            ?: CountryData.findCountry(profile.country)
        return countryItem?.name ?: profile.country.trim().ifBlank { "India" }
    }

    // Card 1: Recently Played (derived strictly from real listening history, never recommendations or filler)
    val pillsCardTracks: StateFlow<List<MusicTrack>> = recentlyPlayed
        .map { recent ->
            recent.distinctBy { it.id }.take(6)
        }
        .flowOn(dataDispatcher)
        .stateIn(dataScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Card 2: YouTube Music Home Screen-Based Card
    private val _ytmCard = MutableStateFlow(
        initialCards?.quick?.limitShelf() ?: SectionState(title = "Quick Picks", subtitle = "Dynamic hits inspired by YouTube Music", isLoading = true)
    )
    val ytmCard: StateFlow<SectionState> = _ytmCard.asStateFlow()

    // Card 3: Era-Specific Hits Card
    private val _eraCard = MutableStateFlow(
        initialCards?.era?.limitShelf() ?: SectionState(title = "Era-Specific Hits", subtitle = "Golden 90s, 2000s classics & retro anthems", isLoading = true)
    )
    val eraCard: StateFlow<SectionState> = _eraCard.asStateFlow()
    val eraSection: StateFlow<SectionState> = _eraCard.asStateFlow() // Backward compatibility alias

    // Card 4: Trending Section (Country & Regional Charts)
    private val _countryLanguageCard = MutableStateFlow(
        initialCards?.chart?.limitShelf() ?: SectionState(
            title = getAuthoritativeTrendingTitle(resolveCountryDisplayName(_userProfile.value)),
            subtitle = "Regional chart discoveries",
            isLoading = true
        )
    )
    val countryLanguageCard: StateFlow<SectionState> = _countryLanguageCard.asStateFlow()

    // Card 5: User Listening History Based Card
    private val _listeningHistoryCard = MutableStateFlow(
        initialCards?.history ?: SectionState(title = "Jump Back In", subtitle = "Based on your recent listening history & frequency", isLoading = false)
    )
    val listeningHistoryCard: StateFlow<SectionState> = _listeningHistoryCard.asStateFlow()

    // Card 6: Fully Personalized Recommendation Card
    private val _personalizedCard = MutableStateFlow(
        initialCards?.personalized?.limitShelf() ?: SectionState(title = "Recommended for You", subtitle = "Curated picks tailored to your sonic taste", isLoading = false)
    )
    val personalizedCard: StateFlow<SectionState> = _personalizedCard.asStateFlow()

    // One publication for the coordinated recommendation cards; history/artist updates stay independent.
    private val _recommendationCards = MutableStateFlow(RecommendationCards(
        _ytmCard.value, _eraCard.value, _countryLanguageCard.value, _personalizedCard.value))
    val recommendationCards: StateFlow<RecommendationCards> = _recommendationCards.asStateFlow()

    private val exposurePreferences by lazy {
        application.getSharedPreferences("home_shelf_exposure", Context.MODE_PRIVATE)
    }

    private data class Candidates(
        val quick: List<MusicTrack> = emptyList(),
        val era: List<MusicTrack> = emptyList(),
        val chart: List<MusicTrack> = emptyList(),
        val generation: Long = 0L
    )
    private val candidates = MutableStateFlow(initialCards?.let {
        Candidates(it.quickCandidates, it.eraCandidates, it.chartCandidates, it.generation)
    } ?: Candidates())
    private var savedCandidates: Candidates? = initialCards?.let { candidates.value }
    private val _mostListenedArtists = MutableStateFlow(initialCards?.artists.orEmpty())
    val mostListenedArtists: StateFlow<List<MostListenedArtist>> = _mostListenedArtists.asStateFlow()
    private val listeningCardRequests = MutableStateFlow(0L)
    private val pendingCardPlays = linkedMapOf<String, MusicTrack>()
    private var lastCardPlaySession = ""
    private var listeningCardsPublished = false
    private val _homeShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val homeShelves: StateFlow<List<HomeShelf>> = _homeShelves.asStateFlow()
    // Compatibility accessors for the old staged-loader name; no second pipeline.
    val primaryCatalog: StateFlow<List<MusicTrack>> get() = catalogTracks
    val heroTrack: StateFlow<MusicTrack?> get() = heroSeedTrack
    val secondaryShelves: StateFlow<List<HomeShelf>> get() = homeShelves
    fun refreshAll() = refreshHome(isExplicitPull = true)

    private data class ArtistHistory(val plays: List<Pair<String, String>>, val trackIds: List<String>)
    private suspend fun refreshListeningCards() {
        // Read only local history; no recommendation fetches or idle gate for these two cards.
        val pending = pendingCardPlays.toMap()
        val events = withContext(Dispatchers.IO) { listeningEventDao.getActualPlays() }
        val played = withContext(Dispatchers.Default) { events.filter(UserTasteProfile::played) }
        val ids = withContext(Dispatchers.Default) { played.map { it.trackId }.distinct() }
        val entities = withContext(Dispatchers.IO) {
            ids.chunked(900).flatMap { database.musicDao().getTracksByIds(it) }
        }
        val (recent, artists, committed) = withContext(Dispatchers.Default) {
            val recorded = played.mapTo(mutableSetOf()) { it.sessionId to it.trackId }
            val uncommitted = pending.filter { (session, track) -> (session to track.id) !in recorded }
            val liveTracks = uncommitted.values.toList().asReversed()
            val metadata = entities.map { it.toMusicTrack() }
            val tracks = (liveTracks + metadata).distinctBy { it.id }
            val byId = tracks.associateBy { it.id }
            val seen = HomeRecommendationPolicy.Recordings()
            val recentTracks = (liveTracks.asSequence() +
                played.asSequence().mapNotNull { byId[it.trackId] }).filter(seen::add).take(30).toList()
            val plays = uncommitted.entries.toList().asReversed().map { (session, track) -> session to track.id } +
                played.map { it.sessionId to it.trackId }
            Triple(recentTracks, rankListenedArtists(plays, tracks), recorded)
        }
        currentCoroutineContext().ensureActive()
        listeningCardsPublished = true
        val changed = _listeningHistoryCard.value.tracks != recent || _mostListenedArtists.value != artists
        _listeningHistoryCard.update { it.copy(tracks = recent, isLoaded = recent.isNotEmpty(), isLoading = false) }
        _mostListenedArtists.value = artists
        pendingCardPlays.entries.removeAll { (it.key to it.value.id) in committed }
        if (changed) shelfCache.peek(profileKey())?.let { saved ->
            shelfCache.save(saved.copy(history = _listeningHistoryCard.value, artists = artists.take(15)))
        }
    }

    private suspend fun rankListenedArtists(plays: List<Pair<String, String>>, tracks: List<MusicTrack>) =
        withContext(Dispatchers.Default) {
            val byId = tracks.associateBy { it.id }
            val counts = linkedMapOf<String, Int>()
            val names = mutableMapOf<String, String>()
            val artistTracks = mutableMapOf<String, MutableList<MusicTrack>>()
            val sessions = mutableSetOf<Pair<String, String>>()
            for (play in plays) {
                currentCoroutineContext().ensureActive()
                if (!sessions.add(play)) continue
                val track = byId[play.second] ?: continue
                for (artist in HomeRecommendationPolicy.artists(track)) {
                    counts[artist] = (counts[artist] ?: 0) + 1
                    names.putIfAbsent(artist, (track.artist + "," + track.singers).split(',')
                        .firstOrNull { HomeRecommendationPolicy.normalize(it) == artist }?.trim() ?: artist)
                    val songs = artistTracks.getOrPut(artist) { mutableListOf() }
                    if (songs.size < 3 && songs.none { it.id == track.id }) songs.add(track)
                }
            }
            counts.entries.sortedByDescending { it.value }.map { (artist, count) ->
                MostListenedArtist(names.getValue(artist), count, artistTracks.getValue(artist).toList())
            }
        }
    private val renderMutex = Mutex()
    private var renderedGeneration = -1L
    private var previousExposure: Set<String> = emptySet()
    private data class RenderInputs(val pool: Candidates, val taste: UserTasteProfile,
        val profile: UserProfile, val catalog: List<MusicTrack>, val history: List<MusicTrack>,
        val favorites: List<MusicTrack>, val recent: List<MusicTrack>)
    private var lastRenderInputs: RenderInputs? = null

    private suspend fun renderRecommendations() = renderMutex.withLock {
        restored.await()
        eventsLoaded.await(); favoritesLoaded.await()
        // A refresh owns one complete candidate snapshot. Do not rerank on individual
        // chart/discovery arrivals or on metadata writes made by that same refresh.
        refreshCandidatesPending.first { !it }
        val pool = candidates.value
        val ids = actualPlays.value.map { it.trackId }.distinct()
        val metadata = historyRows.first { it.ids == ids }.tracks
        val taste = tasteSnapshot.first { snapshot ->
            snapshot.inputs?.let { input -> input.events == actualPlays.value && input.tracks == metadata &&
                input.favorites == favoriteTracks.value && input.preferences == _userProfile.value } == true
        }.taste
        cachedHistory.value = null
        val recentById = metadata.associateBy { it.id }
        val recentSeen = HomeRecommendationPolicy.Recordings()
        val recent = if (taste.isColdStart) emptyList() else actualPlays.value.asSequence()
            .filter(UserTasteProfile::played).mapNotNull { recentById[it.trackId] }
            .filter(recentSeen::add).take(30).toList()
        val inputs = RenderInputs(pool, taste, _userProfile.value, _catalogTracks.value,
            metadata, favoriteTracks.value, recent)
        if (inputs == lastRenderInputs) return@withLock
        val metadataSignal = inputs.history.map { listOf(it.id, it.title, it.artist, it.album, it.coverUrl,
            it.genre, it.language, it.year, it.isLiked) }.hashCode()
        val signal = "${actualPlays.value.hashCode()}|$metadataSignal|${inputs.favorites.map { it.id }.sorted()}|${profileKey()}"
        if (pool == savedCandidates && pool.generation == savedGeneration && signal == savedSignal) {
            _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
            lastRenderInputs = inputs
            return@withLock
        }
        if (!activeRefreshIsExplicit && pool.generation != manualRenderGeneration)
            awaitHomeIdle()
        homeScrolling.first { !it }
        if (refreshCandidatesPending.value || candidates.value != pool) return@withLock
        val rendering = BackgroundWorkCoordinator.busy()
        try {
        currentCoroutineContext().ensureActive()
        val catalog = HomeRecommendationPolicy.originals(inputs.catalog + pool.quick + pool.era + pool.chart + inputs.history + inputs.favorites)
        if (pool.generation != renderedGeneration) {
            previousExposure = withContext(Dispatchers.IO) {
                exposurePreferences.getStringSet("recordings_v2", emptySet()).orEmpty().toSet()
            }
            renderedGeneration = pool.generation
        }
        val shelves = personalizedEngine.generateHomeShelves(catalog, inputs.profile,
            favorites = inputs.favorites, taste = taste)
        currentCoroutineContext().ensureActive()
        val seen = HomeRecommendationPolicy.Recordings()
        val chart = HomeRecommendationPolicy.originals(pool.chart).take(HomeRecommendationPolicy.MAX_SHELF_TRACKS)
        chart.forEach { seen.add(it) } // Charts have priority and retain their source ranking.
        fun fresh(tracks: List<MusicTrack>) = tracks.filter {
            (taste.skipCounts[taste.keyFor(it)] ?: 0) < 2 || taste.keyFor(it) in taste.favoriteRecordings
        }.sortedBy { HomeRecommendationPolicy.metadataKey(it) in previousExposure }
        val era = HomeRecommendationPolicy.diversify(fresh(pool.era), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        val plays = actualPlays.value.filter(UserTasteProfile::played).distinctBy { it.sessionId to it.trackId }
        val historyArtists = inputs.history.associate { it.id to HomeRecommendationPolicy.artists(it) }
        val artistCounts = if (taste.isColdStart) emptyMap() else plays.asSequence().flatMap { event ->
            historyArtists[event.trackId].orEmpty().asSequence()
        }.groupingBy { it }.eachCount()
        val catalogByArtist = mutableMapOf<String, MutableList<MusicTrack>>()
        for (track in catalog) HomeRecommendationPolicy.artists(track).forEach { artist ->
            catalogByArtist.getOrPut(artist) { mutableListOf() }.add(track)
        }
        // Retain the existing cross-shelf reservations; artist-card updates are independent.
        val artistReservations = artistCounts.entries.sortedByDescending { it.value }.take(10).mapNotNull { (artist, count) ->
            currentCoroutineContext().ensureActive()
            val matches = catalogByArtist[artist].orEmpty()
            val tracks = HomeRecommendationPolicy.diversify(fresh(matches), 3, seen).ifEmpty {
                // An artist history card can use its actual played song even when it is in a chart.
                inputs.recent.filter { artist in HomeRecommendationPolicy.artists(it) }.take(1)
            }
            if (tracks.isEmpty()) null else MostListenedArtist(
                matches.firstOrNull()?.let { track -> (track.artist + "," + track.singers).split(',')
                    .firstOrNull { HomeRecommendationPolicy.normalize(it) == artist }?.trim() } ?: artist,
                count, tracks)
        }
        val personalizedGroups = if (taste.isColdStart) emptyList() else shelves.filter {
            it.id in setOf("shelf_mood_vibe", "shelf_top_artist", "shelf_quick_picks")
        }.map { it.tracks }
        // Round robin prevents the first shelf from monopolizing the entire card.
        val personalized = HomeRecommendationPolicy.diversify(fresh(HomeRecommendationPolicy.interleave(personalizedGroups)), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        val quickRanked = personalizedEngine.scoreAndRankCandidates(pool.quick, taste,
            limit = pool.quick.size, variationSeed = pool.generation)
        val quick = HomeRecommendationPolicy.diversify(fresh(quickRanked), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        // Cancellation on a tab change must not publish a partially calculated stale shelf set.
        currentCoroutineContext().ensureActive()
        homeScrolling.first { !it }
        if (refreshCandidatesPending.value || candidates.value != pool) return@withLock
        _homeShelves.value = shelves
        // Reranking must retain error/loading updates arriving from the refresh requests.
        _countryLanguageCard.update { it.copy(tracks = chart) }
        _eraCard.update { it.copy(tracks = era, isLoading = false, isLoaded = era.isNotEmpty()) }
        _ytmCard.update { it.copy(tracks = quick, isLoading = false, isLoaded = quick.isNotEmpty()) }
        _personalizedCard.value = _personalizedCard.value.copy(tracks = personalized,
            isLoading = false, isLoaded = personalized.isNotEmpty())
        _recommendationCards.value = RecommendationCards(_ytmCard.value, _eraCard.value,
            _countryLanguageCard.value, _personalizedCard.value)
        lastRenderInputs = inputs
        _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
        savedSignal = signal; savedGeneration = pool.generation; savedCandidates = pool
        // Both card checkpoint publishers share dataDispatcher; no Main hop or file work here.
        shelfCache.save(SavedHomeCards(profileKey(), signal, pool.generation, lastDiscoveryRefresh,
            _ytmCard.value, _eraCard.value, _countryLanguageCard.value, _listeningHistoryCard.value,
            _personalizedCard.value, _mostListenedArtists.value.take(15), pool.quick, pool.era, pool.chart, catalog.take(400),
            chartRefreshedAt = lastRegionalRefresh))
        if (manualRenderGeneration == pool.generation) manualRenderGeneration = -1L
        val shown = chart + era + quick + personalized + artistReservations.flatMap { it.tracks }
        if (shown.isNotEmpty()) {
            val exposure = shown.map(HomeRecommendationPolicy::metadataKey).toSet()
            withContext(Dispatchers.IO) {
                exposurePreferences.edit().putStringSet("recordings_v2", exposure).apply()
            }
        }

        } finally { rendering.close() }
    }

    // Smooth Pull-to-Refresh & Background Sync State
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    val isSilentRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshErrorMessage = MutableStateFlow<String?>(null)
    val refreshErrorMessage: StateFlow<String?> = _refreshErrorMessage.asStateFlow()

    fun clearRefreshError() {
        _refreshErrorMessage.value = null
    }

    private var regionalTrendingJob: Job? = null
    private var lastRegionalRefresh = initialCards?.chartRefreshedAt ?: 0L
    private var lastRegionalAttempt = 0L
    private var lastRegionalAttemptTitle: String? = null

    private suspend fun awaitHomeIdle() {
        while (true) {
            homeVisible.first { it }
            homeScrolling.first { !it }
            val essential = _ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty()
            // Empty Home content is essential loading, not optional revalidation.
            // Startup work and shimmer frames must not postpone its IO/Default work
            // indefinitely. The existing refresh owner still permits only one pass.
            if (essential) return
            BackgroundWorkCoordinator.awaitIdle(quietMillis = if (essential) 1_500L else 2_500L,
                minimumUptimeMillis = if (essential) 1_500L else 6_000L,
                userRequested = { _isRefreshing.value || manualRenderGeneration == candidates.value.generation })
            if (homeVisible.value) return
        }
    }

    fun loadRegionalTrending(country: String, countryCode: String = "IN", forceRefresh: Boolean = false,
        alreadyIdle: Boolean = false) {
      dataScope.launch {
        val item = CountryData.findCountry(countryCode) ?: CountryData.findCountry(country)
        val name = item?.name ?: country.ifBlank { "India" }
        val title = getAuthoritativeTrendingTitle(name)
        val sameCountry = _countryLanguageCard.value.title == title
        if (!forceRefresh && sameCountry && (regionalTrendingJob?.isActive == true ||
                lastRegionalAttemptTitle == title && System.currentTimeMillis() - lastRegionalAttempt in 0 until 60_000L)) return@launch
        regionalTrendingJob?.cancel()
        if (!sameCountry) candidates.value = candidates.value.copy(chart = emptyList())
        _countryLanguageCard.update { it.copy(title = title,
            tracks = if (sameCountry) it.tracks else emptyList(),
            isLoading = true, isError = false, errorMessage = "") }
        regionalTrendingJob = dataScope.launch regional@ {
          if (!forceRefresh && !alreadyIdle) awaitHomeIdle()
          lastRegionalAttempt = System.currentTimeMillis()
          lastRegionalAttemptTitle = title
          BackgroundWorkCoordinator.work {
            try {
                val songs = repository.getRegionalTrending(name, item?.code ?: countryCode,
                    limit = HomeRecommendationPolicy.MAX_SHELF_TRACKS,
                    forceRefresh = forceRefresh || candidates.value.chart.any { it.id.startsWith("chart_in_") })
                if (_countryLanguageCard.value.title != title) return@regional
                if (songs.isNotEmpty()) {
                    lastRegionalRefresh = repository.getRegionalTrendingFetchedAt(name)
                    candidates.update { it.copy(chart = songs) }
                }
                _countryLanguageCard.update { if (it.title != title) it else it.copy(isLoading = false,
                    isLoaded = candidates.value.chart.isNotEmpty(), isError = songs.isEmpty(),
                    errorMessage = if (songs.isEmpty()) "Regional charts are temporarily unavailable." else "") }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (_countryLanguageCard.value.title == title) {
                    _countryLanguageCard.update { if (it.title != title) it else it.copy(isLoading = false, isError = true,
                        errorMessage = "Regional charts are temporarily unavailable.") }
                }
            }
          }
        }
      }
    }

    fun updateUserProfile(profile: UserProfile) {
        dataScope.launch {
            withContext(Dispatchers.IO) { UserProfilePreferences.saveUserProfile(getApplication(), profile) }
            applyUserProfile(profile)
        }
    }

    private fun applyUserProfile(profile: UserProfile) {
        if (_userProfile.value == profile) return
        val musicPreferencesChanged = profileKey(profile) != profileKey()
        _userProfile.value = profile
        // Onboarding/name updates must not cancel the first request and then have
        // its replacement rejected by the unchanged music-preference retry key.
        if (musicPreferencesChanged) {
            homeRefreshJob?.cancel()
            homeRefreshJob = null
            refreshHome(isExplicitPull = false)
        }
    }

    private var homeRefreshJob: Job? = null
    private var refreshWaitingForIdle = false
    private var pendingExplicitRefresh = false
    private var activeRefreshIsExplicit = false
    private var refreshGeneration = 0L
    private var lastDiscoveryRefresh = initialCards?.refreshedAt ?: 0L
    private var lastDiscoveryAttempt = 0L
    private var lastAttemptProfile: String? = null
    private var manualRenderGeneration = -1L
    private var pendingSilentRefresh = false
    private val refreshCandidatesPending = MutableStateFlow(false)
    private var startupRefreshScheduled = false
    private var lastDiscoverySignal: Pair<Set<String>, Set<String>>? = null

    fun setHomeVisible(visible: Boolean) {
      dataScope.launch {
        if (homeVisible.value == visible) return@launch
        homeVisible.value = visible
        if (!visible) {
            setHomeScrolling(false)
            // Keep bounded requests that have already started. Cancel only optional
            // work still waiting for idle; tab returns reuse the same request/results.
            if (homeRefreshJob?.isActive == true && refreshWaitingForIdle) {
                pendingSilentRefresh = true
                if (activeRefreshIsExplicit) pendingExplicitRefresh = true
                homeRefreshJob?.cancel()
                homeRefreshJob = null
                ++refreshGeneration
                _isRefreshing.value = false
            }
        }
      }
    }

    init {
        dataScope.launch {
            var wasPlaying = false
            playbackManager.listeningState.collect { state ->
                val track = state.track
                val newSession = state.playSessionId != lastCardPlaySession
                if (state.isPlaying && track != null && state.playSessionId.isNotBlank() &&
                    (newSession || !wasPlaying)) {
                    if (newSession) {
                        lastCardPlaySession = state.playSessionId
                        pendingCardPlays[state.playSessionId] = track
                        if (pendingCardPlays.size > 5000) pendingCardPlays.remove(pendingCardPlays.keys.first())
                    }
                    listeningCardRequests.update { it + 1 }
                }
                wasPlaying = state.isPlaying
            }
        }
        dataScope.launch {
            combine(listeningCardRequests, homeVisible) { request, visible -> request to visible }
                .collectLatest { (_, visible) ->
                    if (!visible) return@collectLatest
                    try { refreshListeningCards() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { android.util.Log.w("HomeViewModel", "Could not update listening cards", e) }
                }
        }
        dataScope.launch {
            combine(_ytmCard, _eraCard, _countryLanguageCard, _personalizedCard) { quick, era, chart, personalized ->
                RecommendationCards(quick, era, chart, personalized)
            }.debounce(32L).collectLatest { cards ->
                homeScrolling.first { !it }
                refreshCandidatesPending.first { !it }
                // A single renderer publishes changed tracks; this path publishes loading/errors or cache restoration.
                if (cards.quick.tracks == _ytmCard.value.tracks && cards.era.tracks == _eraCard.value.tracks &&
                    cards.chart.tracks == _countryLanguageCard.value.tracks &&
                    cards.personalized.tracks == _personalizedCard.value.tracks)
                    _recommendationCards.value = cards
            }
        }
        dataScope.launch {
            combine(_ytmCard, _eraCard, _countryLanguageCard) { quick, era, chart ->
                listOf(quick, era, chart).filter { it.isError && !it.isLoading }.map { it.title }
            }.distinctUntilChanged().collect { failed ->
                _refreshErrorMessage.value = failed.takeIf { it.isNotEmpty() }?.let {
                    "${it.joinToString(", ")} couldn't load. Tap Retry to try again."
                }
            }
        }
        dataScope.launch {
          try {
            val cached = initialCards ?: shelfCache.read(profileKey())
            if (cached != null) {
                savedSignal = cached.signal; savedGeneration = cached.generation
                _ytmCard.value = cached.quick.limitShelf(); _eraCard.value = cached.era.limitShelf()
                _countryLanguageCard.value = cached.chart.limitShelf()
                _personalizedCard.value = cached.personalized.limitShelf()
                // A late cache restore must not overwrite a confirmed playback-start update.
                if (!listeningCardsPublished) {
                    _listeningHistoryCard.value = cached.history
                    _mostListenedArtists.value = cached.artists
                }
                _catalogTracks.value = cached.catalog
                cachedHero.value = cached.history.tracks.firstOrNull() ?: cached.catalog.firstOrNull()
                cachedHistory.value = cached.history.tracks
                candidates.value = Candidates(cached.quickCandidates, cached.eraCandidates,
                    cached.chartCandidates, cached.generation)
                savedCandidates = candidates.value
                lastDiscoveryRefresh = cached.refreshedAt
                lastRegionalRefresh = cached.chartRefreshedAt
                playbackManager.setCandidatePool(cached.catalog)
            }
            val initial = if (cached == null) repository.getInitialCatalog(_userProfile.value) else emptyList()
            if (_catalogTracks.value.isEmpty()) {
                _catalogTracks.value = initial
                candidates.value = candidates.value.copy(quick = initial,
                    era = initial.filter { (it.year.toIntOrNull() ?: 0) in 1990..2009 })
                playbackManager.setCandidatePool(initial)
            }
          } finally {
            _currentStage.value = LoadingStage.PRIORITY_2_ESSENTIAL_CONTENT
            restored.complete(Unit)
          }
        }
        dataScope.launch {
            val profiles = withContext(Dispatchers.IO) { UserProfilePreferences.getUserProfileFlow(getApplication()) }
            profiles.collect { profile ->
                applyUserProfile(profile)
            }
        }
        dataScope.launch {
            homeVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
                restored.await()
                coroutineScope {
                    // Persisted history/metadata refinements update only the two listening cards.
                    launch {
                        eventsLoaded.await()
                        val artistHistory = actualPlays.map { events -> ArtistHistory(
                            events.asSequence().filter(UserTasteProfile::played)
                                .map { it.sessionId to it.trackId }.distinct().toList(),
                            events.map { it.trackId }.distinct())
                        }.distinctUntilChanged()
                        combine(artistHistory, historyRows) { history, rows -> history to rows }
                            .mapLatest { (history, rows) ->
                                if (rows.ids != history.trackIds) null
                                else history to rows
                            }.filterNotNull().distinctUntilChanged().collect {
                                listeningCardRequests.update { it + 1 }
                            }
                    }
                    launch {
                        combine(candidates, tasteProfile, favoriteTracks, _userProfile, recentlyPlayed) { pool, taste, _, _, _ -> pool to taste }
                            .combine(refreshCandidatesPending) { input, pending -> if (pending) null else input }
                            .distinctUntilChanged().debounce(250).collectLatest { input ->
                                if (input != null) renderRecommendations()
                            }
                    }
                    // Re-read real listening/likes on return, but do not fetch again for an unchanged signal.
                    launch {
                        // Ignore the temporary cold/empty states while Room restores history
                        // and favorites. Only a completed taste snapshot establishes a signal.
                        tasteSnapshot.map { snapshot -> snapshot.inputs?.let { inputs ->
                            snapshot.taste.topRecentTrackIds.toSet() to inputs.favorites.map { it.id }.toSet()
                        } }.filterNotNull().flowOn(dataDispatcher).distinctUntilChanged().debounce(2_000).collectLatest { signal ->
                            if (lastDiscoverySignal == null) {
                                // The initial request already reads listening history and likes.
                                lastDiscoverySignal = signal
                                return@collectLatest
                            }
                            if (signal != lastDiscoverySignal && (signal.first.isNotEmpty() || signal.second.isNotEmpty())) {
                                delay((60_000 - (System.currentTimeMillis() - lastDiscoveryRefresh)).coerceAtLeast(0))
                                lastDiscoverySignal = signal
                                refreshHome(false)
                            }
                        }
                    }
                    val firstOpening = !startupRefreshScheduled
                    startupRefreshScheduled = true
                    if (pendingSilentRefresh || firstOpening && (lastDiscoveryRefresh == 0L || System.currentTimeMillis() - lastDiscoveryRefresh >= 10 * 60_000L)) {
                        refreshHome(false)
                    } else if (firstOpening && (candidates.value.chart.isEmpty() ||
                        candidates.value.chart.any { it.id.startsWith("chart_in_") } ||
                        System.currentTimeMillis() - lastRegionalRefresh >= 30 * 60_000L)) {
                        val profile = _userProfile.value
                        loadRegionalTrending(resolveCountryDisplayName(profile), profile.countryCode)
                    }
                    awaitCancellation()
                }
            }
        }
    }

    fun refreshHome(isExplicitPull: Boolean = true) {
        // Request/job ownership and list publication share the same Default dispatcher.
        dataScope.launch {
            if (!homeVisible.value) {
                if (isExplicitPull) pendingExplicitRefresh = true else pendingSilentRefresh = true
                return@launch
            }
            val emptyHome = _ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty()
            if (!isExplicitPull && !pendingExplicitRefresh && !emptyHome && lastAttemptProfile == profileKey() &&
                System.currentTimeMillis() - lastDiscoveryAttempt in 0 until 60_000L) return@launch
            if (homeRefreshJob?.isActive == true) {
                if (isExplicitPull && refreshWaitingForIdle) {
                    // A user refresh can take over a deferred job before it has made any requests.
                    ++refreshGeneration
                    homeRefreshJob?.cancel()
                    homeRefreshJob = null
                    refreshWaitingForIdle = false
                } else {
                    if (isExplicitPull) {
                        _isRefreshing.value = true
                        _refreshErrorMessage.value = null
                        // Coalesce pulls into one forced pass after a silent request, never parallel requests.
                        if (!activeRefreshIsExplicit) pendingExplicitRefresh = true
                    }
                    return@launch
                }
            }
            val generation = ++refreshGeneration
            pendingSilentRefresh = false
            val initialExplicit = isExplicitPull || pendingExplicitRefresh || _isRefreshing.value
            pendingExplicitRefresh = false
            homeRefreshJob = dataScope.launch {
                var explicit = initialExplicit
                try {
                    restored.await()
                    if (!initialExplicit && !pendingExplicitRefresh) {
                        refreshWaitingForIdle = true
                        try { awaitHomeIdle() }
                        finally { if (generation == refreshGeneration) refreshWaitingForIdle = false }
                    }
                    while (true) {
                        lastDiscoveryAttempt = System.currentTimeMillis()
                        lastAttemptProfile = profileKey()
                        activeRefreshIsExplicit = explicit
                        refreshCandidatesPending.value = true
                        _isRefreshing.value = explicit
                        _refreshErrorMessage.value = null
                        _ytmCard.update { it.copy(isLoading = it.tracks.isEmpty(), isError = false, errorMessage = "") }
                        _eraCard.update { it.copy(isLoading = it.tracks.isEmpty(), isError = false, errorMessage = "") }
                        try {
                            refreshSuggestions(explicit)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            _ytmCard.update { it.copy(isLoading = false, isError = true,
                                errorMessage = "Quick Picks are temporarily unavailable.") }
                            _eraCard.update { it.copy(isLoading = false, isError = true,
                                errorMessage = "Era hits are temporarily unavailable.") }
                        }
                        if (!pendingExplicitRefresh) break
                        pendingExplicitRefresh = false
                        explicit = true
                    }
                } finally {
                    // A canceled request must not hide the spinner or clear ownership of its replacement.
                    if (generation == refreshGeneration) {
                        _isRefreshing.value = false
                        _ytmCard.update { it.copy(isLoading = false) }
                        _eraCard.update { it.copy(isLoading = false) }
                        activeRefreshIsExplicit = false
                        refreshCandidatesPending.value = false
                        refreshWaitingForIdle = false
                        homeRefreshJob = null
                    }
                }
            }
        }
    }

    private suspend fun refreshSuggestions(force: Boolean) = withContext(dataDispatcher) {
        val profile = _userProfile.value
        val country = resolveCountryDisplayName(profile)
        val title = getAuthoritativeTrendingTitle(country)
        val sameCountry = _countryLanguageCard.value.title == title
        val legacyChart = candidates.value.chart.any { it.id.startsWith("chart_in_") }
        val fetchChart = force || candidates.value.chart.isEmpty() || legacyChart ||
            _countryLanguageCard.value.title != getAuthoritativeTrendingTitle(country) ||
            System.currentTimeMillis() - lastRegionalRefresh >= 30 * 60_000L
        // A full refresh owns the chart too. Cancel an older standalone request before
        // entering the busy section so a deferred chart cannot wait on our own idle gate.
        if (fetchChart) {
            regionalTrendingJob?.cancelAndJoin()
            regionalTrendingJob = null
            _countryLanguageCard.update { it.copy(title = title,
                tracks = if (it.title == title) it.tracks else emptyList(),
                isLoading = true, isError = false, errorMessage = "") }
        }
      BackgroundWorkCoordinator.work {
        // Each repository method owns its IO and CPU dispatcher; keep only coordination here.
        val (quick, era, chart) = coroutineScope {
            val quickJob = async {
                try { repository.getYtmQuickPicks(limit = 100, forceRefresh = force) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            }
            val eraJob = async {
                try { repository.getEraHits(limit = 60, forceRefresh = force) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            }
            val chartJob = async {
                if (!fetchChart) return@async null
                lastRegionalAttempt = System.currentTimeMillis()
                lastRegionalAttemptTitle = title
                try { repository.getRegionalTrending(country, profile.countryCode,
                    limit = HomeRecommendationPolicy.MAX_SHELF_TRACKS, forceRefresh = force || legacyChart) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            }
            Triple(quickJob.await(), eraJob.await(), chartJob.await())
        }
        homeScrolling.first { !it }
        if (profileKey() != profileKey(profile)) return@work
        val catalog = HomeRecommendationPolicy.originals(quick + era + _catalogTracks.value).take(300)
        currentCoroutineContext().ensureActive()
        if (profileKey() != profileKey(profile)) return@work
        if (catalog.isNotEmpty()) {
            _catalogTracks.value = catalog
            playbackManager.setCandidatePool(catalog)
        }
        if (!chart.isNullOrEmpty()) lastRegionalRefresh = repository.getRegionalTrendingFetchedAt(country)
        if (quick.isNotEmpty() || era.isNotEmpty() || !chart.isNullOrEmpty() || !sameCountry) {
            if (quick.isNotEmpty() || era.isNotEmpty()) lastDiscoveryRefresh = System.currentTimeMillis()
            if (force) manualRenderGeneration = candidates.value.generation + 1L
            candidates.update { old -> old.copy(quick = quick.ifEmpty { old.quick },
                era = era.ifEmpty { old.era }, chart = chart?.takeIf { it.isNotEmpty() }
                    ?: old.chart.takeIf { sameCountry }.orEmpty(),
                generation = old.generation + 1) }
        }
        _ytmCard.update { it.copy(isLoading = false, isError = quick.isEmpty(),
            errorMessage = if (quick.isEmpty()) "Quick Picks are temporarily unavailable." else "") }
        _eraCard.update { it.copy(isLoading = false, isError = era.isEmpty(),
            errorMessage = if (era.isEmpty()) "Era hits are temporarily unavailable." else "") }
        if (fetchChart) {
            _countryLanguageCard.update { it.copy(isLoading = false,
                isLoaded = candidates.value.chart.isNotEmpty(), isError = chart.isNullOrEmpty(),
                errorMessage = if (chart.isNullOrEmpty()) "Regional charts are temporarily unavailable." else "") }
        }
      }
    }

    fun loadEraSectionIfNeeded() {
        if (!_eraCard.value.isLoaded && !_eraCard.value.isLoading) refreshHome(false)
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack>) {
        playbackManager.playTrack(track, queue)
    }

    fun toggleFavorite(track: MusicTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleFavorite(track)
        }
    }

    fun computeGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..22 -> "Good evening"
            else -> "Late Night Sessions"
        }
    }

    override fun onCleared() {
        setHomeScrolling(false)
        shelfCache.close()
        super.onCleared()
    }

    companion object {
        fun provideFactory(
            repository: MusicRepository,
            playbackManager: PlaybackManager,
            application: Application
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(repository, playbackManager, application) as T
            }
        }
    }
}
