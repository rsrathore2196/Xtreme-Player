package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
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
import com.example.recommendation.UnifiedRecommendationEngine
import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import com.example.data.cache.RecommendationShelfLoad
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.DiscoveryRequestCoordinator
import com.example.recommendation.UserTasteProfile
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import com.example.data.cache.HomeCardCache
import com.example.data.cache.SavedHomeCards
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.example.ui.ai.HomeShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
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
    val chart: SectionState, val personalized: SectionState,
    val similarArtists: SectionState = SectionState(), val inspiredTrack: SectionState = SectionState())

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

    // IO collectors do not consume the two CPU-ranking workers. Only small mutable
    // request/bookkeeping sections share an owner; queries and scoring suspend off it.
    private val workerDispatcher = repository.homeDispatcher
    private val dataDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val ownerDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ownerScope = viewModelScope + ownerDispatcher
    private val dataScope = viewModelScope + dataDispatcher

    // 1. Instant User Profile (< 1ms)
    private val _userProfile = MutableStateFlow(UserProfilePreferences.peekUserProfile()
        ?: (application as? com.example.XtremeMusicApp)?.startupSettings?.profile ?: UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()
    private val shelfCache = HomeCardCache(application, workerDispatcher)
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
    private val additionalCacheRestored = CompletableDeferred<Unit>()
    private val essentialsLoaded = CompletableDeferred<Unit>()
    private val listeningCardsReady = CompletableDeferred<Unit>()
    private val startupRecommendationsComplete = CompletableDeferred<Unit>()
    @Volatile private var deferredHomeReady = false
    private val eventsLoaded = CompletableDeferred<Unit>()
    private val favoritesLoaded = CompletableDeferred<Unit>()
    private var savedSignal: String? = initialCards?.signal
    private var savedGeneration = initialCards?.generation ?: -1L
    private var restoredCatalogTracks = initialCards?.catalog
    private val lastEventStamps = mutableMapOf<Long, com.example.data.local.ActualPlayStamp>()
    private val retainedEvents = mutableMapOf<Long, ListeningEventEntity>()
    private fun profileKey(profile: UserProfile = _userProfile.value) = "${profile.countryCode}|${profile.languages.joinToString(",")}"
    private val cachedHero = MutableStateFlow(initialCards?.history?.tracks?.firstOrNull() ?: initialCards?.catalog?.firstOrNull())
    private val cachedHistory = MutableStateFlow(initialCards?.history?.tracks)

    private val database by lazy { MusicDatabase.getDatabase(application) }
    private val listeningEventDao by lazy { database.listeningEventDao() }
    val favoriteTracks = kotlinx.coroutines.flow.flow {
        restored.await()
        BackgroundWorkCoordinator.awaitFirstFrame()
        repository.getFavoriteTracks().collect { emit(it) }
    }.onEach { favoritesLoaded.complete(Unit) }
        .flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.Eagerly, emptyList())
    private val historicalReload = MutableStateFlow(0L)
    @Volatile private var historicalLoadFailed = false
    private val historyReadyForRanking = MutableStateFlow(false)
    private val actualPlays = flow {
            restored.await()
            favoritesLoaded.await()
            BackgroundWorkCoordinator.awaitFirstFrame()
            var previousCount = -1
            var previousIds: Set<Long>? = null
            combine(listeningEventDao.observeActualPlayStamps(),
                listeningEventDao.observeActualPlayCount().distinctUntilChanged()) { stamps, count -> stamps to count }
                .flowOn(Dispatchers.IO).collect { (stamps, count) ->
                val ids = stamps.mapTo(mutableSetOf()) { it.id }
                val reloadHistory = previousIds != ids || previousCount != count
                val rebuildOlder = previousCount >= 0 && (count < previousCount || count > previousCount + 1 ||
                    count == previousCount && previousIds != ids || historicalLoadFailed && count != previousCount)
                // Only the visible recent window is reactive. Older personalization
                // data is loaded separately, in cancellable keyset batches.
                if (reloadHistory) {
                    val history = withContext(Dispatchers.IO) {
                        BackgroundWorkCoordinator.work { listeningEventDao.getActualPlays(300) }
                    }
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
                emit(withContext(workerDispatcher) { retainedEvents.values.sortedWith(compareByDescending<ListeningEventEntity> { it.timestamp }.thenByDescending { it.id }).take(300) })
                // Visible history is ready independently of long-term preparation.
                historyReadyForRanking.value = true
                if (stamps.isEmpty()) { cachedHero.value = null; cachedHistory.value = null }
                eventsLoaded.complete(Unit)
                if (rebuildOlder) historicalReload.update { it + 1L }
            }
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.Eagerly, emptyList())
    private data class HistoryRows(val ids: List<String> = emptyList(), val tracks: List<MusicTrack> = emptyList())
    private val historyRows = actualPlays.map { events -> events.map { it.trackId }.distinct() }
        .distinctUntilChanged().flatMapLatest { ids ->
        val pages = ids.chunked(900).map { database.musicDao().observeTracksByIds(it)
            .flowOn(Dispatchers.IO).distinctUntilChanged() }
        if (pages.isEmpty()) kotlinx.coroutines.flow.flowOf(HistoryRows(ids)) else combine(pages) { groups ->
            withContext(workerDispatcher) { HistoryRows(ids, groups.flatMap { rows -> rows.map { it.toMusicTrack() } }) }
        }
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.Eagerly, HistoryRows())
    private data class PersonalizationHistory(val events: List<ListeningEventEntity> = emptyList(),
        val tracks: List<MusicTrack> = emptyList(), val ready: Boolean = false)
    private val historicalRows = MutableStateFlow(PersonalizationHistory())
    // Long-term rows never expand the visible Room observer or its metadata query.
    // Reuse old metadata and merge only the changed recent window on milestones.
    private val personalizationHistory = flow {
        var source: PersonalizationHistory? = null
        var recentIds: List<Long> = emptyList()
        var recentTrackIds: List<String> = emptyList()
        var tail = emptyList<ListeningEventEntity>()
        var trackTail = emptyList<MusicTrack>()
        var retained = PersonalizationHistory()
        combine(actualPlays, historyRows, historicalRows, historyReadyForRanking) { recent, rows, older, ready ->
            if (!ready || rows.ids != recent.map { it.trackId }.distinct()) return@combine null
            withContext(workerDispatcher) {
                val ids = recent.map { it.id }
                val tracks = rows.tracks.map { it.id }
                if (older !== source || ids != recentIds) {
                    val liveIds = ids.toHashSet()
                    // Carry plays that age out of the 300-row observer forward,
                    // and rebuild the old tail only when membership really changes.
                    val base = if (older !== source) older.events else retained.events
                    tail = base.filterNot { it.id in liveIds }.take((5000 - recent.size).coerceAtLeast(0))
                }
                if (older !== source || tracks != recentTrackIds || ids != recentIds) {
                    val liveIds = tracks.toHashSet()
                    val activeIds = (recent.asSequence() + tail.asSequence()).map { it.trackId }.toHashSet()
                    val base = if (older !== source) older.tracks else retained.tracks
                    trackTail = base.filter { it.id !in liveIds && it.id in activeIds }
                }
                source = older; recentIds = ids; recentTrackIds = tracks
                PersonalizationHistory(recent + tail, rows.tracks + trackTail, true).also { retained = it }
            }
        }.filterNotNull().collect { emit(it) }
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.Eagerly, PersonalizationHistory())

    private suspend fun loadHistoricalPersonalization() {
        val recent = combine(actualPlays, historyRows) { events, rows -> events to rows }
            .first { (events, rows) -> eventsLoaded.isCompleted && rows.ids == events.map { it.trackId }.distinct() }
            .let { (events, rows) -> PersonalizationHistory(events, rows.tracks, true) }
        val events = recent.events.associateByTo(linkedMapOf()) { it.id }
        val metadata = recent.tracks.associateByTo(linkedMapOf()) { it.id }
        var cursor = recent.events.lastOrNull()
        while (cursor != null && events.size < 5000) {
            val before = cursor
            val batch = BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                val page = listeningEventDao.getActualPlaysBefore(before.timestamp, before.id,
                    minOf(200, 5000 - events.size))
                val missing = page.map { it.trackId }.distinct().filterNot(metadata::containsKey)
                val tracks = if (missing.isEmpty()) emptyList() else database.musicDao().getTracksByIds(missing)
                page to withContext(workerDispatcher) { tracks.map { it.toMusicTrack() } }
            }
            if (batch.first.isEmpty()) break
            batch.first.forEach { events[it.id] = it }
            batch.second.forEach { metadata[it.id] = it }
            cursor = batch.first.last()
        }
        currentCoroutineContext().ensureActive()
        // One publication, not one taste/ranking/render pass per history page.
        historicalRows.value = PersonalizationHistory(events.values.toList(), metadata.values.toList(), true)
    }

    private data class TasteInputs(val events: List<ListeningEventEntity>, val tracks: List<MusicTrack>,
        val favorites: List<MusicTrack>, val preferences: UserProfile)
    private data class TasteSnapshot(val inputs: TasteInputs? = null,
        val taste: UserTasteProfile = UserTasteProfile.COLD_START_PROFILE)
    private var lastTasteInputs: TasteInputs? = null
    private var retainedTaste = UserTasteProfile.COLD_START_PROFILE
    private val tasteSnapshot = combine(personalizationHistory, favoriteTracks, _userProfile) { history, favorites, preferences ->
        if (history.ready) TasteInputs(history.events, history.tracks, favorites, preferences) else null
    }.filterNotNull().distinctUntilChanged().debounce(300L).mapLatest { inputs ->
        restored.await()
        eventsLoaded.await(); favoritesLoaded.await()
        // Restarting a screen subscription is not a new listening signal. Keep the same decay
        // snapshot until real data changes, rather than reshuffling on every tab return.
        if (inputs != lastTasteInputs) {
            awaitHomeIdle()
            BackgroundWorkCoordinator.startup(if (!deferredHomeReady) BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI
                else BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                val taste = withContext(workerDispatcher) {
                    UserTasteProfile.fromEvents(inputs.events, inputs.tracks, inputs.favorites)
                }
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
    val recentlyPlayed: StateFlow<List<MusicTrack>> = combine(personalizationHistory, cachedHistory) { history, cached ->
        if (!eventsLoaded.isCompleted || !history.ready || history.events.none(UserTasteProfile::played)) cached.orEmpty() else {
            val byId = history.tracks.associateBy { it.id }
            val seen = HomeRecommendationPolicy.Recordings()
            // The DAO already orders newest first; stop once the visible history is filled.
            history.events.asSequence().filter(UserTasteProfile::played)
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
        initialCards?.quick?.limitShelf() ?: SectionState(title = "Quick Picks", subtitle = "Discoveries for your languages and listening taste", isLoading = true)
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

    private val _similarArtistsCard = MutableStateFlow(initialCards?.similarArtists?.limitShelf()
        ?: SectionState(title = "More from Similar Artists"))
    private val _inspiredTrackCard = MutableStateFlow(initialCards?.inspiredTrack?.limitShelf()
        ?: SectionState(title = "Inspired by this Track"))
    private val recommendationSeed = MutableStateFlow<MusicTrack?>(playbackManager.uiState.value.currentTrack)
    private val recommendationViewport = MutableStateFlow<Set<Shelf>>(emptySet())
    private val recommendationViewports = mutableMapOf<Any, Set<Shelf>>()
    private val shelfLoads = initialCards?.shelfLoads.orEmpty().toMutableMap()
    private val shelfAttempts = mutableMapOf<Shelf, RecommendationShelfLoad>()
    private val pendingShelfRequests = mutableSetOf<Shelf>()
    private fun activeRecommendationTrack(): MusicTrack? = recommendationSeed.value
        ?: recentlyPlayed.value.firstOrNull() ?: cachedHistory.value?.firstOrNull()

    // Hidden, zero-height shelves cannot request themselves. Prefetch only the
    // next eligible section as it approaches the viewport, never a full fan-out.
    private fun shelfDemand(allowDeferred: Boolean = false): Set<Shelf> {
        if (!deferredHomeReady && !allowDeferred) {
            // Cold Home only bootstraps its first shelf. Cached cards stay visible
            // without scheduling discovery for every empty/lower section.
            return if (_ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty())
                setOf(Shelf.QUICK) else emptySet()
        }
        val visible = recommendationViewport.value
        val next = Shelf.entries.drop((visible.maxOfOrNull { it.ordinal } ?: -1) + 1)
            .firstOrNull { needsShelfLoad(it) }
        val bootstrap = if (_ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty())
            setOf(Shelf.QUICK) else emptySet()
        return visible + listOfNotNull(next) + bootstrap
    }

    fun setRecommendationViewport(owner: Any, keys: Set<String>) {
        synchronized(recommendationViewports) {
            if (keys.isEmpty()) recommendationViewports.remove(owner)
            else recommendationViewports[owner] = Shelf.entries.filterTo(mutableSetOf()) { it.itemKey in keys }
            recommendationViewport.value = recommendationViewports.values.flatten().toSet()
        }
    }

    private fun shelfContext(shelf: Shelf): String {
        val seed = activeRecommendationTrack()
        val extra = when (shelf) {
            Shelf.SIMILAR, Shelf.INSPIRED -> UnifiedRecommendationEngine.seedKey(seed)
            Shelf.PERSONALIZED -> tasteProfile.value.topRecentTrackIds.joinToString(",") + "|" + favoriteTracks.value.map { it.id }.sorted().joinToString(",")
            else -> ""
        }
        return "${UnifiedRecommendationEngine.SOURCE}|${profileKey()}|$extra"
    }

    private fun needsShelfLoad(shelf: Shelf): Boolean {
        if (shelf == Shelf.PERSONALIZED && tasteProfile.value.isColdStart) return false
        if (shelf in setOf(Shelf.SIMILAR, Shelf.INSPIRED) && activeRecommendationTrack() == null) return false
        val context = shelfContext(shelf)
        val now = System.currentTimeMillis()
        if (shelfAttempts[shelf]?.let { it.context == context && now - it.fetchedAt in 0 until 60_000L } == true) return false
        val ttl = if (shelf in setOf(Shelf.QUICK, Shelf.ERA, Shelf.CHART)) UnifiedRecommendationEngine.CHART_TTL else UnifiedRecommendationEngine.CONTEXT_TTL
        return shelfLoads[shelf.name]?.let { it.context != context || now - it.fetchedAt !in 0 until ttl } ?: true
    }
    private fun recommendationSnapshot(): RecommendationCards = hideRecentRecommendations(
        RecommendationCards(_ytmCard.value, _eraCard.value, _countryLanguageCard.value,
            _personalizedCard.value, _similarArtistsCard.value, _inspiredTrackCard.value),
        recentlyPlayed.value + _listeningHistoryCard.value.tracks + listOfNotNull(activeRecommendationTrack()),
        tasteProfile.value)

    private fun hideRecentRecommendations(cards: RecommendationCards, recent: List<MusicTrack>,
        taste: UserTasteProfile): RecommendationCards {
        val excluded = HomeRecommendationPolicy.Recordings().apply { recent.forEach { add(it) } }
        fun SectionState.withoutRecent(): SectionState {
            val visible = tracks.filterNot { excluded.contains(it) || taste.keyFor(it) in taste.recentRecordings }
            return if (visible.size == tracks.size) this else copy(tracks = visible, isLoaded = visible.isNotEmpty())
        }
        return cards.copy(quick = cards.quick.withoutRecent(), era = cards.era.withoutRecent(),
            chart = cards.chart.withoutRecent(), personalized = cards.personalized.withoutRecent(),
            similarArtists = cards.similarArtists.withoutRecent(), inspiredTrack = cards.inspiredTrack.withoutRecent())
    }

    // One publication for the coordinated recommendation cards; history/artist updates stay independent.
    private val _recommendationCards = MutableStateFlow(recommendationSnapshot())
    val recommendationCards: StateFlow<RecommendationCards> = _recommendationCards.asStateFlow()

    private val exposurePreferences by lazy {
        application.getSharedPreferences("home_shelf_exposure", Context.MODE_PRIVATE)
    }

    private data class Candidates(
        val quick: List<MusicTrack> = emptyList(),
        val era: List<MusicTrack> = emptyList(),
        val chart: List<MusicTrack> = emptyList(),
        val generation: Long = 0L,
        val personalized: List<MusicTrack> = emptyList(),
        val similar: List<MusicTrack> = emptyList(),
        val inspired: List<MusicTrack> = emptyList(),
        val source: String = "", val seedKey: String = "", val seedArtist: String = "", val seedTitle: String = "",
        val ready: Set<Shelf> = emptySet(), val contextKey: String = ""
    )
    private fun restoredCandidates(cards: SavedHomeCards) = Candidates(cards.quickCandidates,
        cards.eraCandidates, cards.chartCandidates, cards.generation, cards.personalizedCandidates,
        cards.similarArtistCandidates, cards.inspiredTrackCandidates, cards.recommendationSource,
        cards.seedKey, cards.seedArtist, cards.seedTitle,
        Shelf.entries.filterTo(mutableSetOf()) { it.name in cards.readyShelves }.ifEmpty {
            if (cards.recommendationSource == UnifiedRecommendationEngine.SOURCE) Shelf.entries.toSet() else emptySet() }, cards.profileKey)
    private val candidates = MutableStateFlow(initialCards?.let {
        restoredCandidates(it)
    } ?: Candidates())
    private var savedCandidates: Candidates? = initialCards?.let { candidates.value }
    private val _mostListenedArtists = MutableStateFlow(initialCards?.artists.orEmpty().take(20))
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
        // Reuse the live Room snapshots; song-start requests add their pending play
        // before Room commits it, without re-querying all history and metadata.
        restored.await()
        eventsLoaded.await()
        val (_, rows) = combine(actualPlays, historyRows) { events, rows -> events to rows }
            .first { (events, rows) -> rows.ids == events.map { it.trackId }.distinct() }
        val longTerm = personalizationHistory.first { it.ready }
        val pending = pendingCardPlays.toMap()
        val (recent, artists, committed) = withContext(workerDispatcher) {
            val played = longTerm.events.filter(UserTasteProfile::played)
            val recorded = played.mapTo(mutableSetOf()) { it.sessionId to it.trackId }
            val uncommitted = pending.filter { (session, track) -> (session to track.id) !in recorded }
            val liveTracks = uncommitted.values.toList().asReversed()
            val metadata = rows.tracks
            val tracks = (liveTracks + metadata + longTerm.tracks).distinctBy { it.id }
            val byId = tracks.associateBy { it.id }
            val seen = HomeRecommendationPolicy.Recordings()
            val recentTracks = (liveTracks.asSequence() +
                played.asSequence().mapNotNull { byId[it.trackId] }).filter(seen::add).take(30).toList()
            val plays = uncommitted.entries.toList().asReversed().map { (session, track) -> session to track.id } +
                played.map { it.sessionId to it.trackId }
            Triple(recentTracks, rankListenedArtists(plays, tracks), recorded)
        }
        currentCoroutineContext().ensureActive()
        if (pending.isEmpty() && historicalRows.value.ready) {
            homeScrolling.first { !it }
            BackgroundWorkCoordinator.awaitNavigationIdle()
        }
        listeningCardsPublished = true
        val changed = _listeningHistoryCard.value.tracks != recent || _mostListenedArtists.value != artists
        _listeningHistoryCard.update { it.copy(tracks = recent, isLoaded = recent.isNotEmpty(), isLoading = false) }
        _mostListenedArtists.value = artists
        pendingCardPlays.entries.removeAll { (it.key to it.value.id) in committed }
        listeningCardsReady.complete(Unit)
        if (changed && additionalCacheRestored.isCompleted) shelfCache.peekFull(profileKey())?.let { saved ->
            shelfCache.save(saved.copy(history = _listeningHistoryCard.value, artists = artists.take(20)))
        }
    }

    private data class ArtistCredits(val artist: String, val singers: String, val keys: List<String>, val names: Map<String, String>)
    private var rankedArtistCredits: Map<String, ArtistCredits> = emptyMap()
    private var countedArtistPlays: Map<Pair<String, String>, List<String>> = emptyMap()
    private var artistPlayCounts: Map<String, Int> = emptyMap()
    private suspend fun rankListenedArtists(plays: List<Pair<String, String>>, tracks: List<MusicTrack>) =
        withContext(workerDispatcher) {
            val byId = tracks.associateBy { it.id }
            val credits = byId.mapValues { (id, track) ->
                rankedArtistCredits[id]?.takeIf { it.artist == track.artist && it.singers == track.singers } ?: run {
                    val keys = HomeRecommendationPolicy.artists(track)
                    val display = keys.associateWith { key -> (track.artist + "," + track.singers).split(',')
                        .firstOrNull { HomeRecommendationPolicy.normalize(it) == key }?.trim() ?: key }
                    ArtistCredits(track.artist, track.singers, keys, display)
                }
            }
            val current = linkedMapOf<Pair<String, String>, List<String>>()
            plays.forEach { play -> credits[play.second]?.let { current.putIfAbsent(play, it.keys) } }
            val counts = artistPlayCounts.toMutableMap()
            // Only new/removed sessions or changed artist credits adjust totals.
            countedArtistPlays.forEach { (play, keys) -> if (current[play] != keys)
                keys.forEach { counts[it] = (counts[it] ?: 0) - 1 } }
            current.forEach { (play, keys) -> if (countedArtistPlays[play] != keys)
                keys.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            val names = linkedMapOf<String, String>()
            val artistTracks = mutableMapOf<String, MutableList<MusicTrack>>()
            current.forEach { (play, keys) ->
                currentCoroutineContext().ensureActive()
                val track = byId.getValue(play.second)
                val display = credits.getValue(play.second).names
                keys.forEach { artist ->
                    names.putIfAbsent(artist, display.getValue(artist))
                    val songs = artistTracks.getOrPut(artist) { mutableListOf() }
                    if (songs.size < 3 && songs.none { it.id == track.id }) songs.add(track)
                }
            }
            val result = names.keys.sortedByDescending { counts[it] ?: 0 }.take(20).map { artist ->
                MostListenedArtist(names.getValue(artist), counts.getValue(artist), artistTracks.getValue(artist).toList())
            }
            currentCoroutineContext().ensureActive()
            rankedArtistCredits = credits; countedArtistPlays = current; artistPlayCounts = counts.filterValues { it > 0 }
            result
        }
    private val renderMutex = Mutex()
    private var renderedGeneration = -1L
    private var previousExposure: Set<String> = emptySet()
    private data class RenderInputs(val pool: Candidates, val taste: UserTasteProfile,
        val profile: UserProfile, val catalog: List<MusicTrack>, val history: List<MusicTrack>,
        val favorites: List<MusicTrack>, val recent: List<MusicTrack>)
    private data class RenderTrigger(val pool: Candidates, val taste: UserTasteProfile,
        val favorites: List<MusicTrack>, val profile: UserProfile, val recent: List<MusicTrack>)
    private data class ReadyHomeInputs(val events: List<ListeningEventEntity>, val rows: HistoryRows,
        val favorites: List<MusicTrack>, val profile: UserProfile, val taste: TasteSnapshot)
    private var lastRenderInputs: RenderInputs? = null

    private suspend fun renderRecommendations() = withContext(workerDispatcher) { renderMutex.withLock {
        restored.await()
        additionalCacheRestored.await()
        // General verified cards are independent of slow Room/taste initialization.
        // Batches are verified snapshots. Presentation below defers replacements
        // during scrolling/navigation without holding up background matching.
        val pool = candidates.value
        if (pool.source != UnifiedRecommendationEngine.SOURCE || pool.contextKey != profileKey()) return@withLock
        // Never make verified general shelves wait for the long-term history
        // expansion/idle gate. Personalized cards keep their previous snapshot
        // until a coherent taste generation becomes available.
        val history = personalizationHistory.value
        val snapshot = ReadyHomeInputs(history.events, HistoryRows(history.events.map { it.trackId }.distinct(), history.tracks),
            favoriteTracks.value, _userProfile.value, tasteSnapshot.value)
        val rankingReady = snapshot.rows.ids == snapshot.events.map { it.trackId }.distinct() &&
            snapshot.taste.inputs?.let { input -> input.events == snapshot.events &&
                input.tracks == snapshot.rows.tracks && input.favorites == snapshot.favorites &&
                input.preferences == snapshot.profile } == true
        val metadata = snapshot.rows.tracks
        val taste = snapshot.taste.taste
        if (rankingReady && eventsLoaded.isCompleted) cachedHistory.value = null
        val recentById = metadata.associateBy { it.id }
        val recentSeen = HomeRecommendationPolicy.Recordings()
        val recent = (recentlyPlayed.value + listOfNotNull(activeRecommendationTrack()) +
            snapshot.events.asSequence().filter(UserTasteProfile::played).mapNotNull { recentById[it.trackId] }
                .take(30).toList()).filter(recentSeen::add)
        val inputs = RenderInputs(pool, taste, snapshot.profile, _catalogTracks.value,
            metadata, snapshot.favorites, recent)
        if (inputs == lastRenderInputs) return@withLock
        val metadataSignal = inputs.history.map { listOf(it.id, it.title, it.artist, it.album, it.coverUrl,
            it.genre, it.language, it.year, it.isLiked) }.hashCode()
        val signal = "${snapshot.events.hashCode()}|$metadataSignal|${inputs.favorites.map { it.id }.sorted()}|${profileKey(inputs.profile)}"
        if (pool == savedCandidates && pool.generation == savedGeneration && signal == savedSignal) {
            _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
            lastRenderInputs = inputs
            return@withLock
        }
        if (!activeRefreshIsExplicit && pool.generation != manualRenderGeneration)
            awaitHomeIdle()
        awaitHomePresentation()
        if (candidates.value != pool) return@withLock
        val rendering = BackgroundWorkCoordinator.busy()
        try {
        currentCoroutineContext().ensureActive()
        val catalog = HomeRecommendationPolicy.originals(inputs.catalog + pool.quick + pool.era + pool.chart +
            pool.personalized + pool.similar + pool.inspired + inputs.history + inputs.favorites)
        if (pool.generation != renderedGeneration) {
            previousExposure = withContext(Dispatchers.IO) {
                exposurePreferences.getStringSet("recordings_v2", emptySet()).orEmpty().toSet()
            }
            renderedGeneration = pool.generation
        }
        currentCoroutineContext().ensureActive()
        val seen = HomeRecommendationPolicy.Recordings()
        val excludedRecent = HomeRecommendationPolicy.Recordings().apply { inputs.recent.forEach { add(it) } }
        fun notRecent(track: MusicTrack) = !excludedRecent.contains(track) &&
            taste.keyFor(track) !in taste.recentRecordings
        val chart = HomeRecommendationPolicy.originals(pool.chart).filter(::notRecent)
            .take(HomeRecommendationPolicy.MAX_SHELF_TRACKS)
        chart.forEach { seen.add(it) } // Charts have priority and retain their source ranking.
        fun fresh(tracks: List<MusicTrack>) = tracks.filter {
            notRecent(it) && ((taste.skipCounts[taste.keyFor(it)] ?: 0) < 2 ||
                taste.keyFor(it) in taste.favoriteRecordings)
        }.sortedBy { HomeRecommendationPolicy.metadataKey(it) in previousExposure }
        val era = HomeRecommendationPolicy.diversify(fresh(pool.era), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        // Artist history has its own publisher. It must not reserve recommendation
        // tracks here and silently empty later shelves through the shared deduper.
        val personalized = if (taste.isColdStart) emptyList() else HomeRecommendationPolicy.diversify(
            fresh(pool.personalized), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        val quick = HomeRecommendationPolicy.diversify(fresh(pool.quick), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        val relevantSeed = pool.seedKey.isNotBlank() && pool.seedKey == UnifiedRecommendationEngine.seedKey(activeRecommendationTrack())
        val similar = if (relevantSeed) HomeRecommendationPolicy.diversify(fresh(pool.similar), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen) else emptyList()
        val inspired = if (relevantSeed) HomeRecommendationPolicy.diversify(fresh(pool.inspired), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen) else emptyList()
        // Cancellation on a tab change must not publish a partially calculated stale shelf set.
        currentCoroutineContext().ensureActive()
        // Retaining ready results while hidden must not hold other optional work busy.
        rendering.close()
        withContext(ownerDispatcher) publish@ {
        awaitHomePresentation()
        if (candidates.value != pool || profileKey(inputs.profile) != profileKey()) return@publish
        _homeShelves.value = listOf(HomeShelf("personalized", "Recommended for You", "Based on your listening", personalized))
            .filter { it.tracks.isNotEmpty() && !taste.isColdStart }
        // Reranking must retain error/loading updates arriving from the refresh requests.
        if (Shelf.CHART in pool.ready) _countryLanguageCard.update { it.copy(tracks = chart) }
        if (Shelf.ERA in pool.ready) _eraCard.update { it.copy(tracks = era, isLoading = false, isLoaded = era.isNotEmpty()) }
        if (Shelf.QUICK in pool.ready) _ytmCard.update { it.copy(tracks = quick, isLoading = false, isLoaded = quick.isNotEmpty()) }
        if (rankingReady && Shelf.PERSONALIZED in pool.ready) _personalizedCard.value = _personalizedCard.value.copy(tracks = personalized,
            isLoading = false, isLoaded = personalized.isNotEmpty())
        if (Shelf.SIMILAR in pool.ready) _similarArtistsCard.update { it.copy(tracks = similar, isLoading = false, isLoaded = similar.isNotEmpty(),
            subtitle = "Related to ${pool.seedArtist}") }
        if (Shelf.INSPIRED in pool.ready) _inspiredTrackCard.update { it.copy(tracks = inspired, isLoading = false, isLoaded = inspired.isNotEmpty(),
            subtitle = "Inspired by ${pool.seedTitle}") }
        _recommendationCards.value = recommendationSnapshot()
        lastRenderInputs = inputs
        _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
        savedSignal = signal; savedGeneration = pool.generation; savedCandidates = pool
        // Both checkpoint publishers share the lightweight owner; no Main/file work here.
        shelfCache.save(SavedHomeCards(profileKey(), signal, pool.generation, lastDiscoveryRefresh,
            _ytmCard.value, _eraCard.value, _countryLanguageCard.value, _listeningHistoryCard.value,
            _personalizedCard.value, _mostListenedArtists.value.take(20), pool.quick, pool.era, pool.chart, catalog.take(400),
            chartRefreshedAt = lastRegionalRefresh, similarArtists = _similarArtistsCard.value,
            inspiredTrack = _inspiredTrackCard.value, personalizedCandidates = pool.personalized,
            similarArtistCandidates = pool.similar, inspiredTrackCandidates = pool.inspired,
            recommendationSource = pool.source, seedKey = pool.seedKey, seedArtist = pool.seedArtist, seedTitle = pool.seedTitle,
            shelfLoads = shelfLoads.toMap(), readyShelves = pool.ready.mapTo(mutableSetOf()) { it.name }))
        if (manualRenderGeneration == pool.generation) manualRenderGeneration = -1L
        val shown = chart + era + quick + personalized + similar + inspired + _mostListenedArtists.value.flatMap { it.tracks }
        if (shown.isNotEmpty()) {
            val exposure = shown.map(HomeRecommendationPolicy::metadataKey).toSet()
            withContext(Dispatchers.IO) {
                exposurePreferences.edit().putStringSet("recordings_v2", exposure).apply()
            }
        }

        }

        } finally { rendering.close() }
    } }

    // Smooth Pull-to-Refresh & Background Sync State
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    val isSilentRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshErrorMessage = MutableStateFlow<String?>(null)
    val refreshErrorMessage: StateFlow<String?> = _refreshErrorMessage.asStateFlow()

    fun clearRefreshError() {
        _refreshErrorMessage.value = null
    }

    @Volatile private var lastRegionalRefresh = initialCards?.chartRefreshedAt ?: 0L

    // Navigation admission ignores our rendering lease. Retain completed work
    // across hidden tabs rather than discarding it and reranking on the return.
    private suspend fun awaitHomePresentation() {
        while (true) {
            homeVisible.first { it }
            homeScrolling.first { !it }
            BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
            if (homeVisible.value && !homeScrolling.value) return
        }
    }

    private suspend fun awaitHomeIdle(allowWhenEmpty: Boolean = true, quietMillis: Long = 2_500L) {
        restored.await()
        essentialsLoaded.await()
        while (true) {
            homeVisible.first { it }
            homeScrolling.first { !it }
            BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
            if (!homeVisible.value || homeScrolling.value) continue
            val essential = !deferredHomeReady && _ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty()
            // Empty Home content is essential loading, not optional revalidation.
            // Startup work and shimmer frames must not postpone its IO/Default work
            // indefinitely. The existing refresh owner still permits only one pass.
            if (allowWhenEmpty && essential) return
            BackgroundWorkCoordinator.awaitIdle(quietMillis = quietMillis,
                minimumUptimeMillis = 0L,
                userRequested = { _isRefreshing.value || manualRenderGeneration == candidates.value.generation })
            BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
            if (homeVisible.value && !homeScrolling.value) return
        }
    }

    fun loadRegionalTrending(country: String, countryCode: String = "IN", forceRefresh: Boolean = false,
        alreadyIdle: Boolean = false) {
        // All discovery now has one unified refresh owner and cache publication.
        refreshHome(isExplicitPull = forceRefresh)
    }

    fun updateUserProfile(profile: UserProfile) {
        ownerScope.launch {
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
    private var activeRecommendationShelves: Set<Shelf> = emptySet()
    private var activeRecommendationSeed = ""
    private var refreshWaitingForIdle = false
    private var pendingExplicitRefresh = false
    @Volatile private var activeRefreshIsExplicit = false
    private var activeRefreshPriority: DiscoveryRequestCoordinator.HomeRefresh? = null
    private var refreshGeneration = 0L
    @Volatile private var lastDiscoveryRefresh = initialCards?.refreshedAt ?: 0L
    private var lastDiscoveryAttempt = 0L
    private var lastAttemptProfile: String? = null
    @Volatile private var manualRenderGeneration = -1L
    private var pendingSilentRefresh = false
    private var startupRefreshScheduled = false
    private var startupBootstrapUsed = false
    private var lastDiscoverySignal: Pair<Set<String>, Set<String>>? = null

    fun setHomeVisible(visible: Boolean) {
      ownerScope.launch {
        if (homeVisible.value == visible) return@launch
        homeVisible.value = visible
        if (!visible) {
            setHomeScrolling(false)
        } else if (startupRefreshScheduled && (pendingExplicitRefresh || pendingSilentRefresh)) {
            refreshHome(isExplicitPull = pendingExplicitRefresh)
        }
      }
    }

    private fun restoreCachedCards(cached: SavedHomeCards, additional: Boolean) {
        if (cached.profileKey != profileKey()) return
        savedSignal = cached.signal; savedGeneration = cached.generation
        _ytmCard.value = cached.quick.limitShelf(); _eraCard.value = cached.era.limitShelf()
        _countryLanguageCard.value = cached.chart.limitShelf(); _personalizedCard.value = cached.personalized.limitShelf()
        _similarArtistsCard.value = cached.similarArtists.limitShelf(); _inspiredTrackCard.value = cached.inspiredTrack.limitShelf()
        // Room/live playback wins over a late full-cache restore.
        if (!listeningCardsPublished) {
            _listeningHistoryCard.value = cached.history
            _mostListenedArtists.value = cached.artists.take(20)
            cachedHero.value = cached.history.tracks.firstOrNull() ?: cached.catalog.firstOrNull()
            cachedHistory.value = cached.history.tracks
        }
        if (!additional || _catalogTracks.value.isEmpty() || _catalogTracks.value == restoredCatalogTracks)
            _catalogTracks.value = cached.catalog
        if (!additional) restoredCatalogTracks = cached.catalog
        candidates.value = restoredCandidates(cached)
        savedCandidates = candidates.value
        lastDiscoveryRefresh = cached.refreshedAt.takeIf { cached.recommendationSource == UnifiedRecommendationEngine.SOURCE } ?: 0L
        lastRegionalRefresh = cached.chartRefreshedAt
        shelfLoads.clear(); shelfLoads.putAll(cached.shelfLoads)
        if (_ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty()) {
            shelfLoads.remove(Shelf.QUICK.name); shelfAttempts.remove(Shelf.QUICK)
        }
        playbackManager.setCandidatePool(_catalogTracks.value)
        // Public recommendation consumers receive one coherent, equality-checked value.
        val snapshot = recommendationSnapshot()
        if (_recommendationCards.value != snapshot) _recommendationCards.value = snapshot
    }

    init {
        ownerScope.launch {
            restored.await()
            favoritesLoaded.await()
            eventsLoaded.await()
            listeningCardsReady.await()
            essentialsLoaded.complete(Unit)
            BackgroundWorkCoordinator.homeContentReady()
            // Cached cards or a small completed bootstrap are sufficient for the
            // first Home. Deeper rankings/discovery follow a real idle window.
            startupRecommendationsComplete.await()
            BackgroundWorkCoordinator.optionalRecommendationsReady()
            awaitHomeIdle(allowWhenEmpty = false, quietMillis = 750L)
            deferredHomeReady = true
            _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
            if (_catalogTracks.value.isEmpty()) {
                val initial = BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                    repository.getInitialCatalog(_userProfile.value)
                }
                if (_catalogTracks.value.isEmpty()) _catalogTracks.value = initial
                playbackManager.setCandidatePool(_catalogTracks.value)
            }
            if (startupBootstrapUsed) {
                // Top up from cached discovery/mappings; do not repeat already verified lookups.
                shelfLoads.remove(Shelf.QUICK.name)
                shelfAttempts.remove(Shelf.QUICK)
            }
            refreshHome(false)
        }
        dataScope.launch {
            essentialsLoaded.await()
            historicalReload.collectLatest {
                // Rebuild older pages only after structural import/deletion changes;
                // normal playback milestones merge into the retained long-term window.
                historicalLoadFailed = false
                for (attempt in 0..1) {
                    try {
                        loadHistoricalPersonalization()
                        historicalLoadFailed = false
                        break
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (attempt == 1) historicalLoadFailed = true
                        android.util.Log.w("HomeViewModel", "Historical personalization unavailable", e)
                    }
                }
            }
        }
        dataScope.launch {
            BackgroundWorkCoordinator.uiForeground.collect { visible ->
                if (visible && historicalLoadFailed) historicalReload.update { it + 1L }
            }
        }
        ownerScope.launch {
            var wasPlaying = false
            playbackManager.listeningState.collect { state ->
                val track = state.track
                if (track != null && UnifiedRecommendationEngine.seedKey(track) !=
                    UnifiedRecommendationEngine.seedKey(recommendationSeed.value)) recommendationSeed.value = track
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
        ownerScope.launch {
            listeningCardRequests.collectLatest {
                try { refreshListeningCards() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.w("HomeViewModel", "Could not update listening cards", e)
                    listeningCardsReady.complete(Unit)
                }
            }
        }
        ownerScope.launch {
            val cards = combine(combine(_ytmCard, _eraCard, _countryLanguageCard, _personalizedCard) { quick, era, chart, personalized ->
                RecommendationCards(quick, era, chart, personalized)
            }, _similarArtistsCard, _inspiredTrackCard) { base, similar, inspired ->
                base.copy(similarArtists = similar, inspiredTrack = inspired)
            }
            combine(cards, recentlyPlayed, tasteProfile, recommendationSeed) { snapshot, recent, taste, seed ->
                withContext(workerDispatcher) {
                    hideRecentRecommendations(snapshot, recent + _listeningHistoryCard.value.tracks + listOfNotNull(seed), taste)
                }
            }.debounce(32L).collectLatest { snapshot ->
                homeScrolling.first { !it }
                if (snapshot == recommendationSnapshot()) _recommendationCards.value = snapshot
            }
        }
        ownerScope.launch {
            combine(_ytmCard, _eraCard, _countryLanguageCard) { quick, era, chart ->
                listOf(quick, era, chart).filter { it.isError && !it.isLoading }.map { it.title }
            }.distinctUntilChanged().collect { failed ->
                _refreshErrorMessage.value = failed.takeIf { it.isNotEmpty() }?.let {
                    "${it.joinToString(", ")} couldn't load recommendations. Tap Retry to try again."
                }
            }
        }
        ownerScope.launch {
          try {
            val cached = BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) {
                initialCards ?: shelfCache.readInitial(profileKey())
            }
            if (cached != null) restoreCachedCards(cached, additional = false)
            if (_ytmCard.value.tracks.isNotEmpty() || _countryLanguageCard.value.tracks.isNotEmpty())
                startupRecommendationsComplete.complete(Unit)
            else {
                shelfLoads.remove(Shelf.QUICK.name)
                shelfAttempts.remove(Shelf.QUICK)
            }
          } finally {
            _currentStage.value = LoadingStage.PRIORITY_2_ESSENTIAL_CONTENT
            restored.complete(Unit)
            BackgroundWorkCoordinator.cachedHomeRestored()
          }
        }
        ownerScope.launch {
            restored.await()
            essentialsLoaded.await()
            val key = profileKey()
            try {
                val cached = shelfCache.peekFull(key) ?: BackgroundWorkCoordinator.startup(
                    BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                    urgent = { pendingExplicitRefresh }) { shelfCache.read(key) }
                if (cached != null && key == profileKey()) {
                    awaitHomePresentation()
                    restoreCachedCards(cached, additional = true)
                    if (listeningCardsPublished) shelfCache.save(cached.copy(history = _listeningHistoryCard.value,
                        artists = _mostListenedArtists.value.take(20)))
                }
            } finally {
                // Partial first-read snapshots must never overwrite full disk pools.
                additionalCacheRestored.complete(Unit)
            }
        }
        ownerScope.launch {
            val profiles = withContext(Dispatchers.IO) { UserProfilePreferences.getUserProfileFlow(getApplication()) }
            profiles.collect { profile ->
                applyUserProfile(profile)
            }
        }
        // These collectors belong to the ViewModel. Visibility only gates optional
        // processing, so changing tabs never restarts Room or replays discovery.
        ownerScope.launch {
            restored.await()
            eventsLoaded.await()
            personalizationHistory.filter { it.ready }.map { history ->
                ArtistHistory(history.events.asSequence().filter(UserTasteProfile::played)
                    .map { it.sessionId to it.trackId }.distinct().toList(), history.tracks.map { it.id }) to history.tracks
            }.distinctUntilChanged().collect { listeningCardRequests.update { it + 1 } }
        }
        ownerScope.launch {
            combine(candidates, tasteProfile, favoriteTracks, _userProfile, recentlyPlayed) { pool, taste, favorites, profile, recent ->
                RenderTrigger(pool, taste, favorites, profile, recent)
            }.distinctUntilChanged().debounce(300L).collectLatest {
                    renderRecommendations()
                }
        }
        ownerScope.launch {
            // Only completed Room/taste snapshots establish a listening signal.
            tasteSnapshot.map { snapshot -> snapshot.inputs?.let { inputs ->
                snapshot.taste.topRecentTrackIds.toSet() to inputs.favorites.map { it.id }.toSet()
            } }.filterNotNull().distinctUntilChanged().debounce(2_000L).collectLatest { signal ->
                if (lastDiscoverySignal == null) {
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
        ownerScope.launch {
            repository.recommendations.unavailable.collectLatest { failures ->
                if (failures.isEmpty()) return@collectLatest
                restored.await()
                awaitHomePresentation()
                val failed = failures.filterValues { it > android.os.SystemClock.elapsedRealtime() }.keys
                val old = candidates.value
                val affected = mapOf(Shelf.QUICK to old.quick, Shelf.ERA to old.era, Shelf.CHART to old.chart,
                    Shelf.PERSONALIZED to old.personalized, Shelf.SIMILAR to old.similar, Shelf.INSPIRED to old.inspired)
                    .filterValues { tracks -> tracks.any { it.id in failed } }.keys
                if (affected.isEmpty()) return@collectLatest
                fun usable(tracks: List<MusicTrack>) = tracks.filterNot { it.id in failed }
                candidates.update { pool -> pool.copy(quick = usable(pool.quick), era = usable(pool.era), chart = usable(pool.chart),
                    personalized = usable(pool.personalized), similar = usable(pool.similar), inspired = usable(pool.inspired)) }
                _catalogTracks.update { tracks -> tracks.filterNot { it.recommendationOnly && it.id in failed } }
                playbackManager.setCandidatePool(_catalogTracks.value)
                affected.forEach { shelfLoads.remove(it.name); shelfAttempts.remove(it) }
                // Replace only affected visible cards from their existing discovery pool.
                // History, artist counts and the active player/queue are never edited.
                refreshHome(false, affected)
            }
        }
        ownerScope.launch {
            // Observe actual LazyColumn demand, not precomposition or the creation of Home.
            combine(recommendationViewport, recommendationSeed, tasteProfile, _userProfile, favoriteTracks) { visible, seed, taste, profile, favorites ->
                Triple(visible, UnifiedRecommendationEngine.seedKey(seed ?: recentlyPlayed.value.firstOrNull()),
                    "${profileKey(profile)}|${taste.isColdStart}|${taste.topRecentTrackIds}|${favorites.map { it.id }.sorted()}")
            }.distinctUntilChanged().debounce(200L).collectLatest { demand ->
                restored.await()
                if (demand.second.isNotBlank() && candidates.value.seedKey != demand.second) {
                    _similarArtistsCard.update { it.copy(tracks = emptyList(), isLoaded = false) }
                    _inspiredTrackCard.update { it.copy(tracks = emptyList(), isLoaded = false) }
                }
                if (demand.first.isEmpty()) return@collectLatest
                awaitHomePresentation()
                if (activeRecommendationSeed != demand.second && activeRecommendationShelves.isNotEmpty() &&
                    activeRecommendationShelves.all { it == Shelf.SIMILAR || it == Shelf.INSPIRED }) {
                    // An obsolete contextual-only pass must not delay the newly selected song.
                    homeRefreshJob?.cancelAndJoin()
                }
                homeRefreshJob?.join()
                val needed = if (pendingExplicitRefresh) shelfDemand()
                    else shelfDemand().filterTo(mutableSetOf(), ::needsShelfLoad)
                if (needed.isNotEmpty()) refreshHome(false, needed)
            }
        }
        ownerScope.launch {
            restored.await()
            homeVisible.first { it }
            startupRefreshScheduled = true
            if (pendingExplicitRefresh || pendingSilentRefresh || lastDiscoveryRefresh == 0L ||
                _ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty() ||
                System.currentTimeMillis() - lastDiscoveryRefresh >= 30 * 60_000L) {
                if (!pendingExplicitRefresh && (_ytmCard.value.tracks.isNotEmpty() || _countryLanguageCard.value.tracks.isNotEmpty()))
                    awaitHomeIdle(allowWhenEmpty = false, quietMillis = 750L)
                refreshHome(isExplicitPull = pendingExplicitRefresh)
            } else if (candidates.value.chart.isEmpty() ||
                candidates.value.chart.any { it.id.startsWith("chart_in_") } ||
                System.currentTimeMillis() - lastRegionalRefresh >= 30 * 60_000L) {
                val profile = _userProfile.value
                loadRegionalTrending(resolveCountryDisplayName(profile), profile.countryCode)
            }
        }
    }

    fun refreshHome(isExplicitPull: Boolean = true, requestedShelves: Set<Shelf>? = null) {
        // Request ownership and short publication blocks share the IO owner.
        ownerScope.launch {
            restored.await()
            if (isExplicitPull && !additionalCacheRestored.isCompleted) pendingExplicitRefresh = true
            additionalCacheRestored.await()
            if (!homeVisible.value) {
                if (isExplicitPull) pendingExplicitRefresh = true else pendingSilentRefresh = true
                return@launch
            }
            // Empty shelves are not necessarily in the viewport yet (hero/onboarding).
            // Bootstrap only essential discovery; all lower shelves retain on-demand loading.
            val bootstrap = if (_ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty())
                setOf(Shelf.QUICK) else emptySet()
            val demand = shelfDemand(allowDeferred = isExplicitPull || pendingExplicitRefresh || activeRefreshIsExplicit) + bootstrap
            val requested = (requestedShelves ?: demand).intersect(demand)
                .filterTo(mutableSetOf()) { isExplicitPull || pendingExplicitRefresh || needsShelfLoad(it) }
            if (requested.isEmpty()) {
                if (isExplicitPull) pendingExplicitRefresh = true
                return@launch
            }
            if (homeRefreshJob?.isActive == true) {
                pendingShelfRequests.addAll(requested - activeRecommendationShelves)
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
                        // Promote this pass in place: a pull must not wait for a silent
                        // refresh and then repeat all of its network requests.
                        activeRefreshIsExplicit = true
                        activeRefreshPriority?.promote()
                    }
                    return@launch
                }
            }
            val generation = ++refreshGeneration
            pendingSilentRefresh = false
            val initialExplicit = isExplicitPull || pendingExplicitRefresh || _isRefreshing.value
            pendingExplicitRefresh = false
            val priority = DiscoveryRequestCoordinator.HomeRefresh(initialExplicit)
            activeRefreshPriority = priority
            activeRefreshIsExplicit = initialExplicit
            val startupBatch = !deferredHomeReady && !initialExplicit
            if (startupBatch) startupBootstrapUsed = true
            _isRefreshing.value = initialExplicit
            if (initialExplicit) _refreshErrorMessage.value = null
            homeRefreshJob = ownerScope.launch {
                val indicatorDeadline = launch {
                    priority.requested.first { it }
                    delay(12_000L)
                    if (generation == refreshGeneration) _isRefreshing.value = false
                }
                try {
                    withContext(priority) {
                        restored.await()
                        essentialsLoaded.await()
                        if (!activeRefreshIsExplicit) {
                            refreshWaitingForIdle = true
                            try { awaitHomeIdle() }
                            finally { if (generation == refreshGeneration) refreshWaitingForIdle = false }
                        }
                        lastDiscoveryAttempt = System.currentTimeMillis()
                        lastAttemptProfile = profileKey()
                        _isRefreshing.value = activeRefreshIsExplicit
                        _refreshErrorMessage.value = null
                        if (Shelf.QUICK in requested) _ytmCard.update { it.copy(isLoading = it.tracks.isEmpty(), isError = false, errorMessage = "") }
                        if (Shelf.ERA in requested) _eraCard.update { it.copy(isLoading = it.tracks.isEmpty(), isError = false, errorMessage = "") }
                        try {
                            val completed = BackgroundWorkCoordinator.startup(
                                if (activeRefreshIsExplicit) BackgroundWorkCoordinator.StartupPriority.CRITICAL
                                else if (startupBatch) BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI
                                else BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                                urgent = { activeRefreshIsExplicit }) {
                                withTimeoutOrNull(40_000L) {
                                    refreshSuggestions(activeRefreshIsExplicit, requested, startupBatch && !activeRefreshIsExplicit)
                                    true
                                }
                            }
                            if (completed == null && generation == refreshGeneration)
                                _refreshErrorMessage.value = "Refresh took too long. Your existing cards have been kept. Please retry."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            _refreshErrorMessage.value = e.message ?: "Recommendations are temporarily unavailable."
                            if (Shelf.CHART in requested) _countryLanguageCard.update { it.copy(isLoading = false, isError = true, errorMessage = e.message.orEmpty()) }
                            if (Shelf.QUICK in requested) _ytmCard.update { it.copy(isLoading = false, isError = true,
                                errorMessage = "Quick Picks are temporarily unavailable.") }
                            if (Shelf.ERA in requested) _eraCard.update { it.copy(isLoading = false, isError = true,
                                errorMessage = "Era hits are temporarily unavailable.") }
                        }
                        // Finish the visible refresh only after the coherent shelf set
                        // is ready; do not add the normal idle/debounce delay to a pull.
                        if (activeRefreshIsExplicit) {
                            manualRenderGeneration = candidates.value.generation
                            // A drag, tab change or delayed taste snapshot must not hold
                            // the pull indicator indefinitely; the retained render collector
                            // still publishes the latest complete result when presentation is ready.
                            withTimeoutOrNull(2_000L) { renderRecommendations() }
                        }
                    }
                } finally {
                    indicatorDeadline.cancel()
                    if (startupBatch || initialExplicit) startupRecommendationsComplete.complete(Unit)
                    // A canceled request must not hide the spinner or clear ownership of its replacement.
                    if (generation == refreshGeneration) {
                        _isRefreshing.value = false
                        if (Shelf.QUICK in requested) _ytmCard.update { it.copy(isLoading = false) }
                        if (Shelf.ERA in requested) _eraCard.update { it.copy(isLoading = false) }
                        if (manualRenderGeneration == candidates.value.generation) manualRenderGeneration = -1L
                        activeRefreshIsExplicit = false
                        activeRefreshPriority = null
                        activeRecommendationShelves = emptySet()
                        activeRecommendationSeed = ""
                        refreshWaitingForIdle = false
                        homeRefreshJob = null
                        // A viewport/listening change during a running pass is demand,
                        // not a reason to drop the next cards until manual refresh.
                        val pending = pendingShelfRequests.toSet() + shelfDemand().filter(::needsShelfLoad)
                        pendingShelfRequests.clear()
                        if (pending.isNotEmpty() && homeVisible.value)
                            refreshHome(false, pending)
                    }
                }
            }
        }
    }

    private suspend fun refreshSuggestions(force: Boolean, requested: Set<Shelf>, startupBatch: Boolean = false) = withContext(ownerDispatcher) {
        val profile = _userProfile.value
        val country = resolveCountryDisplayName(profile)
        val title = getAuthoritativeTrendingTitle(country)
        val seed = activeRecommendationTrack()
        BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
        val bootstrap = if (_ytmCard.value.tracks.isEmpty() && _countryLanguageCard.value.tracks.isEmpty())
            setOf(Shelf.QUICK) else emptySet()
        val enabled = requested.intersect(shelfDemand(allowDeferred = force || activeRefreshIsExplicit) + bootstrap)
        if (enabled.isEmpty()) return@withContext
        val contexts = enabled.associateWith(::shelfContext)
        activeRecommendationShelves = enabled
        activeRecommendationSeed = UnifiedRecommendationEngine.seedKey(seed)
        val startedAt = System.currentTimeMillis()
        val nextGeneration = candidates.value.generation + 1
        enabled.forEach { shelfAttempts[it] = RecommendationShelfLoad(contexts.getValue(it), startedAt) }
        val fresh = linkedMapOf<String, MusicTrack>()
        try {
            repository.recommendations.refresh(profile, country, tasteProfile.value, seed,
                personalizationHistory.value.tracks, favoriteTracks.value, _catalogTracks.value, force, shelves = enabled, startupBatch = startupBatch).collect { batch ->
                currentCoroutineContext().ensureActive()
                if (profileKey() != profileKey(profile)) return@collect
                val sameSeed = UnifiedRecommendationEngine.seedKey(activeRecommendationTrack()) == batch.seedKey
                val results = mapOf(Shelf.QUICK to batch.quick, Shelf.ERA to batch.era, Shelf.CHART to batch.chart.orEmpty(),
                    Shelf.PERSONALIZED to batch.personalized, Shelf.SIMILAR to batch.similarArtists, Shelf.INSPIRED to batch.inspiredTrack)
                    .mapValues { (_, tracks) -> tracks.filter { track ->
                        (repository.recommendations.unavailable.value[track.id] ?: 0L) <= android.os.SystemClock.elapsedRealtime() } }
                val successful = batch.requested.filterTo(mutableSetOf()) { results.getValue(it).isNotEmpty() &&
                    (it !in setOf(Shelf.SIMILAR, Shelf.INSPIRED) || sameSeed) }
                if (successful.isEmpty()) return@collect
                successful.forEach { shelf ->
                    shelfLoads[shelf.name] = RecommendationShelfLoad(contexts.getValue(shelf), batch.fetchedAt[shelf] ?: startedAt)
                    results.getValue(shelf).forEach { fresh[it.id] = it }
                }
                if (Shelf.QUICK in successful || Shelf.ERA in successful) lastDiscoveryRefresh = System.currentTimeMillis()
                if (Shelf.CHART in successful) lastRegionalRefresh = batch.chartFetchedAt
                // One generation for the whole pass: appending a small batch never
                // changes the shuffle seed or reranks already published positions.
                if (force || activeRefreshIsExplicit) manualRenderGeneration = nextGeneration
                candidates.update { old ->
                    val trusted = old.source == UnifiedRecommendationEngine.SOURCE && old.contextKey == profileKey(profile)
                    fun retain(shelf: Shelf, previous: List<MusicTrack>) = if (shelf in successful) results.getValue(shelf)
                        else previous.takeIf { trusted }.orEmpty()
                    val updatesSeed = sameSeed && successful.any { it == Shelf.SIMILAR || it == Shelf.INSPIRED }
                    val keepRelated = !updatesSeed || old.seedKey == batch.seedKey
                    old.copy(quick = retain(Shelf.QUICK, old.quick), era = retain(Shelf.ERA, old.era),
                        chart = retain(Shelf.CHART, old.chart), personalized = retain(Shelf.PERSONALIZED, old.personalized),
                        similar = retain(Shelf.SIMILAR, old.similar.takeIf { keepRelated }.orEmpty()),
                        inspired = retain(Shelf.INSPIRED, old.inspired.takeIf { keepRelated }.orEmpty()),
                        generation = nextGeneration, source = UnifiedRecommendationEngine.SOURCE,
                        seedKey = if (updatesSeed) batch.seedKey else old.seedKey,
                        seedArtist = if (updatesSeed) batch.seedArtist else old.seedArtist,
                        seedTitle = if (updatesSeed) batch.seedTitle else old.seedTitle,
                        ready = old.ready.takeIf { trusted }.orEmpty() + successful, contextKey = profileKey(profile))
                }
                if (Shelf.CHART in successful) _countryLanguageCard.update { it.copy(title = title,
                    subtitle = "Popular in $country • your selected languages", isLoading = false, isError = false) }
                if (Shelf.QUICK in successful) _ytmCard.update { it.copy(subtitle = "Discoveries for your languages and listening taste", isLoading = false, isError = false) }
                if (Shelf.ERA in successful) _eraCard.update { it.copy(subtitle = "Verified 1990s & 2000s recordings", isLoading = false, isError = false) }
                if (Shelf.PERSONALIZED in successful) _personalizedCard.update { it.copy(subtitle = "Inspired by your listening", isLoading = false, isError = false) }
            }
            // Empty provider results leave valid cached cards intact. Expose recovery
            // only for a requested section that has no usable snapshot at all.
            fun failed(state: SectionState) = if (state.tracks.isEmpty()) state.copy(isLoading = false,
                isError = true, errorMessage = "Recommendations are temporarily unavailable. Tap Retry to try again.") else state
            if (Shelf.QUICK in enabled) _ytmCard.update(::failed)
            if (Shelf.ERA in enabled) _eraCard.update(::failed)
            if (Shelf.CHART in enabled) _countryLanguageCard.update(::failed)
            if (Shelf.PERSONALIZED in enabled) _personalizedCard.update(::failed)
            if (Shelf.SIMILAR in enabled) _similarArtistsCard.update(::failed)
            if (Shelf.INSPIRED in enabled) _inspiredTrackCard.update(::failed)
        } finally {
            // At most one library transaction for this pass; no per-song Room
            // invalidation storm. Snapshot/mapping caches have their own batch writers.
            if (fresh.isNotEmpty()) {
                val verified = fresh.values.toList()
                dataScope.launch {
                    BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                        requireForeground = false) { repository.cacheRecommendationTracks(verified) }
                }
                _catalogTracks.value = withContext(workerDispatcher) {
                    HomeRecommendationPolicy.originals(verified + _catalogTracks.value).take(300) }
                playbackManager.setCandidatePool(_catalogTracks.value)
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
