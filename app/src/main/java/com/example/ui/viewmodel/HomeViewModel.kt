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
import com.example.recommendation.UserTasteProfileStore
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.conflate
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
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.example.ui.ai.HomeShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        if (homeScrolling.value == active) return
        homeScrolling.value = active
        BackgroundWorkCoordinator.scrolling(this, active)
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
                // Unrelated Room invalidations do not change listening evidence.
                if (!reloadHistory && changed.isEmpty()) return@collect
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
        val tracks: List<MusicTrack> = emptyList(), val ready: Boolean = false, val complete: Boolean = false)
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
                PersonalizationHistory(recent + tail, rows.tracks + trackTail, true, older.ready).also { retained = it }
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
            BackgroundWorkCoordinator.yieldToInteraction(requireForeground = true)
            val before = cursor
            val batch = BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                withContext(Dispatchers.IO) {
                    val page = listeningEventDao.getActualPlaysBefore(before.timestamp, before.id,
                        minOf(200, 5000 - events.size))
                    val missing = page.map { it.trackId }.distinct().filterNot(metadata::containsKey)
                    val tracks = if (missing.isEmpty()) emptyList() else database.musicDao().getTracksByIds(missing)
                    page to withContext(workerDispatcher) { tracks.map { it.toMusicTrack() } }
                }
            }
            if (batch.first.isEmpty()) break
            batch.first.forEach { events[it.id] = it }
            batch.second.forEach { metadata[it.id] = it }
            cursor = batch.first.last()
        }
        currentCoroutineContext().ensureActive()
        // One publication, not one taste/ranking/render pass per history page.
        BackgroundWorkCoordinator.yieldToInteraction(requireForeground = true)
        historicalRows.value = PersonalizationHistory(events.values.toList(), metadata.values.toList(), true, true)
    }

    private data class TasteInputs(val events: List<ListeningEventEntity>, val tracks: List<MusicTrack>,
        val favorites: List<MusicTrack>, val preferences: UserProfile, val complete: Boolean)
    private data class TasteSnapshot(val inputs: TasteInputs? = null,
        val taste: UserTasteProfile = UserTasteProfile.COLD_START_PROFILE)
    private var lastTasteInputs: TasteInputs? = null
    private val tasteCacheLoaded = CompletableDeferred<Unit>()
    private var cachedTasteSnapshot = UserTasteProfileStore.peekSnapshot(profileKey())
    private var retainedTaste = cachedTasteSnapshot?.profile ?: UserTasteProfile.COLD_START_PROFILE
    private val tasteSnapshot = combine(personalizationHistory, favoriteTracks, _userProfile) { history, favorites, preferences ->
        if (history.ready) TasteInputs(history.events, history.tracks, favorites, preferences, history.complete) else null
    }.filterNotNull().distinctUntilChanged().debounce(300L).mapLatest { inputs ->
        restored.await()
        eventsLoaded.await(); favoritesLoaded.await(); tasteCacheLoaded.await()
        // Restarting a screen subscription is not a new listening signal. Keep the same decay
        // snapshot until real data changes, rather than reshuffling on every tab return.
        if (inputs != lastTasteInputs) {
            val key = profileKey(inputs.preferences)
            val cached = cachedTasteSnapshot?.takeIf { it.contextKey == key }
            // Reuse complete long-term taste while the independent older-history
            // reader fills in. Never replace it with a 300-row partial rebuild.
            if (!inputs.complete && cached?.completeHistory == true) {
                retainedTaste = cached.profile
                lastTasteInputs = inputs
            } else {
                awaitHomePresentation()
                BackgroundWorkCoordinator.startup(if (!deferredHomeReady || lastTasteInputs == null)
                    BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI else BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                    val signature = withContext(workerDispatcher) {
                        UserTasteProfileStore.fingerprint(inputs.events, inputs.tracks, inputs.favorites, key) {
                            yieldHomePreparation()
                        }
                    }
                    val taste = cached?.takeIf { it.matches(key, signature) }?.profile ?: withContext(workerDispatcher) {
                        UserTasteProfile.fromEventsCooperatively(inputs.events, inputs.tracks, inputs.favorites) {
                            yieldHomePreparation()
                        }.let { profile -> profile.copy(languageAffinities = inputs.preferences.languages.associate {
                            HomeRecommendationPolicy.normalize(it) to 0.35f
                        } + profile.languageAffinities) }
                    }
                    currentCoroutineContext().ensureActive()
                    retainedTaste = taste
                    lastTasteInputs = inputs
                    if (inputs.complete) {
                        val snapshot = UserTasteProfileStore.Snapshot(key, signature, true, taste)
                        cachedTasteSnapshot = snapshot
                        UserTasteProfileStore.save(application, snapshot)
                    }
                }
            }
        }
        TasteSnapshot(inputs, retainedTaste)
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.Lazily, TasteSnapshot(taste = retainedTaste))
    private val tasteProfile = tasteSnapshot.map { it.taste }
        .stateIn(dataScope, SharingStarted.Lazily, retainedTaste)
    val recentlyPlayed: StateFlow<List<MusicTrack>> = combine(actualPlays, historyRows, cachedHistory) { events, rows, cached ->
        if (!eventsLoaded.isCompleted || rows.ids != events.map { it.trackId }.distinct()) cached.orEmpty() else {
            val byId = rows.tracks.associateBy { it.id }
            val seen = HomeRecommendationPolicy.Recordings()
            events.asSequence().filter(UserTasteProfile::played).mapNotNull { byId[it.trackId] }
                .filter(seen::add).take(30).toList()
        }
    }.distinctUntilChanged().flowOn(dataDispatcher)
        .stateIn(dataScope, SharingStarted.Lazily, cachedHistory.value.orEmpty())
    val heroSeedTrack: StateFlow<MusicTrack?> = combine(recentlyPlayed, favoriteTracks, _catalogTracks, cachedHero) { recent, favorites, catalog, cached ->
        (recent.asSequence() + listOfNotNull(cached).asSequence() + favorites.asSequence() + catalog.asSequence())
            .firstOrNull(com.example.recommendation.OriginalMusicFilter::isHomeRecommendation)
    }.flowOn(dataDispatcher).stateIn(dataScope, SharingStarted.Lazily, cachedHero.value)

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

    // Computation changes a single prepared generation. UI consumers only observe
    // the presentation flows below, never individual intermediate computations.
    private val preparedCards = MutableStateFlow(RecommendationCards(
        initialCards?.quick?.limitShelf() ?: SectionState(title = "Quick Picks", subtitle = "Discoveries for your languages and listening taste", isLoading = true),
        initialCards?.era?.limitShelf() ?: SectionState(title = "Era-Specific Hits", subtitle = "Golden 90s, 2000s classics & retro anthems", isLoading = true),
        initialCards?.chart?.limitShelf() ?: SectionState(title = getAuthoritativeTrendingTitle(resolveCountryDisplayName(_userProfile.value)),
            subtitle = "Regional chart discoveries", isLoading = true),
        initialCards?.personalized?.limitShelf() ?: SectionState(title = "Recommended for You", subtitle = "Curated picks tailored to your sonic taste"),
        initialCards?.similarArtists?.limitShelf() ?: SectionState(title = "More from Similar Artists"),
        initialCards?.inspiredTrack?.limitShelf() ?: SectionState(title = "Inspired by this Track")
    ))
    private val _listeningHistoryCard = MutableStateFlow(initialCards?.history
        ?: SectionState(title = "Jump Back In", subtitle = "Based on your recent listening history & frequency"))
    val listeningHistoryCard: StateFlow<SectionState> = _listeningHistoryCard.asStateFlow()

    private fun updateSection(shelf: Shelf, transform: (SectionState) -> SectionState) {
        preparedCards.update { cards -> cards.withSection(shelf, transform(cards.section(shelf))) }
    }

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
            val emptyVisible = recommendationViewport.value.filterTo(mutableSetOf()) {
                preparedCards.value.section(it).tracks.isEmpty() && needsShelfLoad(it)
            }
            return emptyVisible + if (preparedCards.value.quick.tracks.isEmpty() && preparedCards.value.chart.tracks.isEmpty())
                setOf(Shelf.QUICK) else emptySet()
        }
        val visible = recommendationViewport.value
        val next = Shelf.entries.drop((visible.maxOfOrNull { it.ordinal } ?: -1) + 1)
            .firstOrNull { needsShelfLoad(it) }
        val bootstrap = if (preparedCards.value.quick.tracks.isEmpty() && preparedCards.value.chart.tracks.isEmpty())
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
    private val _recommendationCards = MutableStateFlow(preparedCards.value)
    val recommendationCards: StateFlow<RecommendationCards> = _recommendationCards.asStateFlow()
    fun sectionState(shelf: Shelf): StateFlow<SectionState> = sectionStates.getValue(shelf)
    private val sectionStates = Shelf.entries.associateWith { shelf ->
        recommendationCards.map { it.section(shelf) }.distinctUntilChanged()
            .stateIn(dataScope, SharingStarted.Eagerly, _recommendationCards.value.section(shelf))
    }
    val ytmCard get() = sectionState(Shelf.QUICK)
    val eraCard get() = sectionState(Shelf.ERA)
    val eraSection get() = eraCard
    val countryLanguageCard get() = sectionState(Shelf.CHART)
    val personalizedCard get() = sectionState(Shelf.PERSONALIZED)

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
        val ready: Set<Shelf> = emptySet(), val contextKey: String = "", val revision: Long = 0L
    )
    private fun restoredCandidates(cards: SavedHomeCards): Candidates {
        // Preview headers contain display tracks but intentionally omit pools.
        // Use those verified tracks until full enrichment, never mark empty pools ready.
        val quick = cards.quickCandidates.ifEmpty { cards.quick.tracks }
        val era = cards.eraCandidates.ifEmpty { cards.era.tracks }
        val chart = cards.chartCandidates.ifEmpty { cards.chart.tracks }
        val personal = cards.personalizedCandidates.ifEmpty { cards.personalized.tracks }
        val similar = cards.similarArtistCandidates.ifEmpty { cards.similarArtists.tracks }
        val inspired = cards.inspiredTrackCandidates.ifEmpty { cards.inspiredTrack.tracks }
        val pools = mapOf(Shelf.QUICK to quick, Shelf.ERA to era, Shelf.CHART to chart,
            Shelf.PERSONALIZED to personal, Shelf.SIMILAR to similar, Shelf.INSPIRED to inspired)
        return Candidates(quick, era, chart, cards.generation, personal, similar, inspired,
            cards.recommendationSource, cards.seedKey, cards.seedArtist, cards.seedTitle,
            pools.filterValues { it.isNotEmpty() }.keys, cards.profileKey)
    }
    private val candidates = MutableStateFlow(initialCards?.let {
        restoredCandidates(it)
    } ?: Candidates())
    private var savedCandidates: Candidates? = initialCards?.let { candidates.value }
    private val _mostListenedArtists = MutableStateFlow(initialCards?.artists.orEmpty().take(20))
    val mostListenedArtists: StateFlow<List<MostListenedArtist>> = _mostListenedArtists.asStateFlow()
    val hasListenedArtists = mostListenedArtists.map { it.isNotEmpty() }.distinctUntilChanged()
        .stateIn(dataScope, SharingStarted.Eagerly, _mostListenedArtists.value.isNotEmpty())
    val hasRecentlyPlayed = listeningHistoryCard.map { it.tracks.isNotEmpty() }.distinctUntilChanged()
        .stateIn(dataScope, SharingStarted.Eagerly, _listeningHistoryCard.value.tracks.isNotEmpty())
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
    private val appliedCachedArtistSessions = mutableSetOf<String>()
    private suspend fun refreshListeningCards() {
        // Reuse the live Room snapshots; song-start requests add their pending play
        // before Room commits it, without re-querying all history and metadata.
        restored.await()
        eventsLoaded.await()
        val (events, rows) = combine(actualPlays, historyRows) { events, rows -> events to rows }
            .first { (events, rows) -> rows.ids == events.map { it.trackId }.distinct() }
        val longTerm = personalizationHistory.first { it.ready }
        val pending = pendingCardPlays.toMap()
        val keepCachedArtists = !longTerm.complete && _mostListenedArtists.value.isNotEmpty()
        val cachedArtists = _mostListenedArtists.value
        val (recent, artists, committed) = withContext(workerDispatcher) {
            val played = longTerm.events.filter(UserTasteProfile::played)
            val recorded = played.mapTo(mutableSetOf()) { it.sessionId to it.trackId }
            val uncommitted = pending.filter { (session, track) -> (session to track.id) !in recorded }
            val liveTracks = uncommitted.values.toList().asReversed()
            val metadata = rows.tracks
            val tracks = (liveTracks + metadata + longTerm.tracks).distinctBy { it.id }
            val byId = tracks.associateBy { it.id }
            val seen = HomeRecommendationPolicy.Recordings()
            val recentTracks = (liveTracks.asSequence() + events.asSequence().filter(UserTasteProfile::played)
                .mapNotNull { byId[it.trackId] }).filter(seen::add).take(30).toList()
            val plays = uncommitted.entries.toList().asReversed().map { (session, track) -> session to track.id } +
                played.map { it.sessionId to it.trackId }
            val artists = if (keepCachedArtists) addPendingArtistPlays(cachedArtists,
                pending.filterKeys { it !in appliedCachedArtistSessions }.values)
            else rankListenedArtists(plays, tracks, immediate = pending.isNotEmpty())
            Triple(recentTracks, artists, recorded)
        }
        currentCoroutineContext().ensureActive()
        // Essential data is prepared even when a tab transition is underway.
        // Its optional visual publication must not hold startup readiness back.
        listeningCardsReady.complete(Unit)
        if (pending.isEmpty()) {
            homeScrolling.first { !it }
            BackgroundWorkCoordinator.awaitNavigationIdle()
        }
        listeningCardsPublished = true
        val changed = _listeningHistoryCard.value.tracks != recent || _mostListenedArtists.value != artists
        if (_listeningHistoryCard.value.tracks != recent || _listeningHistoryCard.value.isLoading)
            _listeningHistoryCard.update { it.copy(tracks = recent, isLoaded = recent.isNotEmpty(), isLoading = false) }
        if (_mostListenedArtists.value != artists) _mostListenedArtists.value = artists
        if (keepCachedArtists) appliedCachedArtistSessions.addAll(pending.keys)
        else appliedCachedArtistSessions.clear()
        pendingCardPlays.entries.removeAll { (it.key to it.value.id) in committed }
        if (changed) savePreparedCards()
    }

    private data class ArtistCredits(val artist: String, val singers: String, val keys: List<String>, val names: Map<String, String>)
    private var rankedArtistCredits: Map<String, ArtistCredits> = emptyMap()
    private var countedArtistPlays: Map<Pair<String, String>, List<String>> = emptyMap()
    private var artistPlayCounts: Map<String, Int> = emptyMap()
    private var lastArtistHistory: Pair<List<Pair<String, String>>, List<MusicTrack>>? = null
    private var lastArtistRanking = emptyList<MostListenedArtist>()
    private suspend fun rankListenedArtists(plays: List<Pair<String, String>>, tracks: List<MusicTrack>, immediate: Boolean = false) =
        withContext(workerDispatcher) {
            val inputs = plays to tracks
            if (lastArtistHistory == inputs) return@withContext lastArtistRanking
            suspend fun checkpoint(index: Int) {
                if (!immediate && index % 128 == 0) yieldHomePreparation()
            }
            val byId = tracks.associateBy { it.id }
            val credits = mutableMapOf<String, ArtistCredits>()
            byId.entries.forEachIndexed { index, (id, track) ->
                checkpoint(index)
                credits[id] = rankedArtistCredits[id]?.takeIf { it.artist == track.artist && it.singers == track.singers } ?: run {
                    val keys = HomeRecommendationPolicy.artists(track)
                    val display = keys.associateWith { key -> (track.artist + "," + track.singers).split(',')
                        .firstOrNull { HomeRecommendationPolicy.normalize(it) == key }?.trim() ?: key }
                    ArtistCredits(track.artist, track.singers, keys, display)
                }
            }
            val current = linkedMapOf<Pair<String, String>, List<String>>()
            plays.forEachIndexed { index, play ->
                checkpoint(index)
                credits[play.second]?.let { current.putIfAbsent(play, it.keys) }
            }
            val counts = artistPlayCounts.toMutableMap()
            // Only new/removed sessions or changed artist credits adjust totals.
            countedArtistPlays.entries.forEachIndexed { index, (play, keys) ->
                checkpoint(index)
                if (current[play] != keys) keys.forEach { counts[it] = (counts[it] ?: 0) - 1 }
            }
            current.entries.forEachIndexed { index, (play, keys) ->
                checkpoint(index)
                if (countedArtistPlays[play] != keys) keys.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            }
            val names = linkedMapOf<String, String>()
            val artistTracks = mutableMapOf<String, MutableList<MusicTrack>>()
            var processed = 0
            current.forEach { (play, keys) ->
                checkpoint(processed++)
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
            lastArtistHistory = inputs; lastArtistRanking = result
            result
        }
    private var renderedGeneration = -1L
    private var previousExposure: Set<String> = emptySet()
    private data class RenderInputs(val pool: Candidates, val taste: UserTasteProfile,
        val profile: UserProfile, val catalog: List<MusicTrack>, val history: List<MusicTrack>,
        val favorites: List<MusicTrack>, val recent: List<MusicTrack>)
    private data class RenderTrigger(val pool: List<Any>, val taste: UserTasteProfile,
        val favorites: List<MusicTrack>, val profile: UserProfile, val recent: List<MusicTrack>)
    private data class DisplayInputs(val cards: RecommendationCards, val recent: List<MusicTrack>,
        val taste: UserTasteProfile, val seed: MusicTrack?, val listening: SectionState)
    private data class ReadyHomeInputs(val events: List<ListeningEventEntity>, val rows: HistoryRows,
        val favorites: List<MusicTrack>, val profile: UserProfile, val taste: TasteSnapshot)
    private var lastRenderInputs: RenderInputs? = null

    private suspend fun renderRecommendations() = withContext(workerDispatcher) {
        restored.await()
        val pool = candidates.value
        if (pool.source != UnifiedRecommendationEngine.SOURCE || pool.contextKey != profileKey()) return@withContext
        yieldHomePreparation()
        val history = personalizationHistory.value
        val snapshot = ReadyHomeInputs(history.events, HistoryRows(history.events.map { it.trackId }.distinct(), history.tracks),
            favoriteTracks.value, _userProfile.value, tasteSnapshot.value)
        val rankingReady = snapshot.taste.inputs?.let { input -> input.events == snapshot.events &&
            input.tracks == snapshot.rows.tracks && input.favorites == snapshot.favorites &&
            input.preferences == snapshot.profile } == true
        val taste = snapshot.taste.taste
        val recentSeen = HomeRecommendationPolicy.Recordings()
        val recent = (recentlyPlayed.value + _listeningHistoryCard.value.tracks +
            listOfNotNull(activeRecommendationTrack())).filter(recentSeen::add)
        val inputs = RenderInputs(pool, taste, snapshot.profile, _catalogTracks.value,
            snapshot.rows.tracks, snapshot.favorites, recent)
        if (inputs == lastRenderInputs) return@withContext
        val metadataSignal = inputs.history.map { listOf(it.id, it.title, it.artist, it.album, it.coverUrl,
            it.genre, it.language, it.year, it.isLiked) }.hashCode()
        val signal = "${snapshot.events.hashCode()}|$metadataSignal|${inputs.favorites.map { it.id }.sorted()}|${profileKey(inputs.profile)}"
        if (pool == savedCandidates && pool.generation == savedGeneration && signal == savedSignal) {
            lastRenderInputs = inputs
            return@withContext
        }
        val exposure = if (pool.generation == renderedGeneration) previousExposure else withContext(Dispatchers.IO) {
            exposurePreferences.getStringSet("recordings_v2", emptySet()).orEmpty().toSet()
        }
        // Safe boundaries are outside locks and busy leases. Keep completed work
        // when a gesture starts, instead of cancelling/restarting a whole ranking.
        yieldHomePreparation()
        // This list is only persisted as a 400-track checkpoint. Do not normalize
        // thousands of older tracks after that prefix is already complete.
        val catalog = HomeRecommendationPolicy.originalsCooperatively(
            sequenceOf(inputs.catalog, pool.quick, pool.era, pool.chart, pool.personalized,
                pool.similar, pool.inspired, inputs.history, inputs.favorites).flatMap { it.asSequence() },
            limit = 400, checkpoint = { yieldHomePreparation() })
        val seen = HomeRecommendationPolicy.Recordings()
        val excludedRecent = HomeRecommendationPolicy.Recordings().apply { inputs.recent.forEach { add(it) } }
        fun notRecent(track: MusicTrack) = !excludedRecent.contains(track) && taste.keyFor(track) !in taste.recentRecordings
        fun fresh(tracks: List<MusicTrack>) = tracks.filter {
            notRecent(it) && ((taste.skipCounts[taste.keyFor(it)] ?: 0) < 2 || taste.keyFor(it) in taste.favoriteRecordings)
        }.sortedBy { HomeRecommendationPolicy.metadataKey(it) in exposure }
        val chart = BackgroundWorkCoordinator.work {
            HomeRecommendationPolicy.originals(pool.chart).filter(::notRecent).take(HomeRecommendationPolicy.MAX_SHELF_TRACKS)
        }
        chart.forEach { seen.add(it) }
        yieldHomePreparation()
        val era = BackgroundWorkCoordinator.work {
            HomeRecommendationPolicy.diversify(fresh(pool.era), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        }
        yieldHomePreparation()
        val personalized = if (taste.isColdStart) emptyList() else BackgroundWorkCoordinator.work {
            HomeRecommendationPolicy.diversify(fresh(pool.personalized), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        }
        yieldHomePreparation()
        val quick = BackgroundWorkCoordinator.work {
            HomeRecommendationPolicy.diversify(fresh(pool.quick), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        }
        val relevantSeed = pool.seedKey.isNotBlank() && pool.seedKey == UnifiedRecommendationEngine.seedKey(activeRecommendationTrack())
        yieldHomePreparation()
        val similar = if (relevantSeed) BackgroundWorkCoordinator.work {
            HomeRecommendationPolicy.diversify(fresh(pool.similar), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        } else emptyList()
        yieldHomePreparation()
        val inspired = if (relevantSeed) BackgroundWorkCoordinator.work {
            HomeRecommendationPolicy.diversify(fresh(pool.inspired), HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
        } else emptyList()
        val shown = chart + era + quick + personalized + similar + inspired + _mostListenedArtists.value.flatMap { it.tracks }
        val nextExposure = shown.map(HomeRecommendationPolicy::metadataKey).toSet()
        val country = resolveCountryDisplayName(inputs.profile)
        val chartTitle = getAuthoritativeTrendingTitle(country)
        withContext(ownerDispatcher) publish@ {
            awaitHomePresentation()
            currentCoroutineContext().ensureActive()
            // A cache read, another refresh, or new preferences cannot overwrite
            // a newer prepared generation after any suspension above.
            if (candidates.value.generation != pool.generation || candidates.value.revision != pool.revision ||
                profileKey(inputs.profile) != profileKey() ||
                favoriteTracks.value != inputs.favorites || tasteProfile.value != inputs.taste) return@publish
            fun ready(old: SectionState, tracks: List<MusicTrack>, subtitle: String = old.subtitle): SectionState =
                if (tracks.isEmpty() && old.tracks.isNotEmpty()) old
                else old.copy(tracks = tracks, subtitle = subtitle, isLoading = false,
                    isLoaded = tracks.isNotEmpty(), isError = false, errorMessage = "")
            preparedCards.update { old -> old.copy(
                quick = if (Shelf.QUICK in pool.ready) ready(old.quick, quick, "Discoveries for your languages and listening taste") else old.quick,
                era = if (Shelf.ERA in pool.ready) ready(old.era, era, "Verified 1990s & 2000s recordings") else old.era,
                chart = if (Shelf.CHART in pool.ready) ready(old.chart, chart, "Popular in $country • your selected languages").copy(title = chartTitle) else old.chart,
                personalized = if (rankingReady && Shelf.PERSONALIZED in pool.ready) ready(old.personalized, personalized, "Inspired by your listening") else old.personalized,
                similarArtists = if (relevantSeed && Shelf.SIMILAR in pool.ready)
                    ready(old.similarArtists, similar, "Related to ${pool.seedArtist}") else old.similarArtists,
                inspiredTrack = if (relevantSeed && Shelf.INSPIRED in pool.ready)
                    ready(old.inspiredTrack, inspired, "Inspired by ${pool.seedTitle}") else old.inspiredTrack
            ) }
            _homeShelves.value = listOf(HomeShelf("personalized", "Recommended for You", "Based on your listening", personalized))
                .filter { it.tracks.isNotEmpty() && !taste.isColdStart }
            lastRenderInputs = inputs
            renderedGeneration = pool.generation
            previousExposure = exposure
            _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
            savedSignal = signal; savedGeneration = pool.generation; savedCandidates = pool
            checkpointCatalog = catalog.take(400)
            // Presentation never waits for full-cache decoding. Only persistence
            // waits, so a preview cannot overwrite still-unrestored candidate pools.
            savePreparedCards()
            if (manualRenderGeneration == pool.generation) manualRenderGeneration = -1L
            if (nextExposure.isNotEmpty()) withContext(Dispatchers.IO) {
                exposurePreferences.edit().putStringSet("recordings_v2", nextExposure).apply()
            }
        }
    }

    private suspend fun yieldHomePreparation() {
        if (!activeRefreshIsExplicit) {
            homeScrolling.first { !it }
            BackgroundWorkCoordinator.yieldToInteraction(requireForeground = true,
                awaitFrameQuiet = deferredHomeReady || startupRecommendationsComplete.isCompleted)
        }
        kotlinx.coroutines.yield()
    }

    private var checkpointCatalog = emptyList<MusicTrack>()
    private fun savePreparedCards() {
        if (!additionalCacheRestored.isCompleted) return
        val pool = candidates.value
        if (pool.contextKey != profileKey() || pool.source != UnifiedRecommendationEngine.SOURCE) return
        val cards = preparedCards.value
        shelfCache.save(SavedHomeCards(profileKey(), savedSignal.orEmpty(), pool.generation, lastDiscoveryRefresh,
            cards.quick, cards.era, cards.chart, _listeningHistoryCard.value, cards.personalized,
            _mostListenedArtists.value.take(20), pool.quick, pool.era, pool.chart,
            checkpointCatalog.ifEmpty { _catalogTracks.value }, chartRefreshedAt = lastRegionalRefresh,
            similarArtists = cards.similarArtists, inspiredTrack = cards.inspiredTrack,
            personalizedCandidates = pool.personalized, similarArtistCandidates = pool.similar,
            inspiredTrackCandidates = pool.inspired, recommendationSource = pool.source,
            seedKey = pool.seedKey, seedArtist = pool.seedArtist, seedTitle = pool.seedTitle,
            shelfLoads = shelfLoads.toMap(), readyShelves = pool.ready.mapTo(mutableSetOf()) { it.name }))
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
            val essential = !deferredHomeReady && preparedCards.value.quick.tracks.isEmpty() && preparedCards.value.chart.tracks.isEmpty()
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

    private var cachePreviewCards: RecommendationCards? = initialCards?.let(::cachedSections)
    private var cachePreviewPool: Candidates? = initialCards?.let(::restoredCandidates)
    private fun cachedSections(cached: SavedHomeCards) = RecommendationCards(cached.quick.limitShelf(),
        cached.era.limitShelf(), cached.chart.limitShelf(), cached.personalized.limitShelf(),
        cached.similarArtists.limitShelf(), cached.inspiredTrack.limitShelf())

    private suspend fun restoreCachedCards(cached: SavedHomeCards, additional: Boolean) {
        if (cached.profileKey != profileKey()) return
        val sections = withContext(workerDispatcher) { cachedSections(cached) }
        val restoredPool = withContext(workerDispatcher) { restoredCandidates(cached) }
        val current = candidates.value
        if (!additional) {
            savedSignal = cached.signal; savedGeneration = cached.generation
            preparedCards.update { cards -> cards.enrichFromCache(sections, cards) }
            cachePreviewCards = preparedCards.value
            cachePreviewPool = restoredPool
            candidates.value = restoredPool
            savedCandidates = restoredPool
            lastDiscoveryRefresh = cached.refreshedAt.takeIf { cached.recommendationSource == UnifiedRecommendationEngine.SOURCE } ?: 0L
            lastRegionalRefresh = cached.chartRefreshedAt
            shelfLoads.clear(); shelfLoads.putAll(cached.shelfLoads)
        } else {
            val preview = cachePreviewCards ?: preparedCards.value
            preparedCards.update { cards -> cards.enrichFromCache(sections, preview) }
            // Enrichment supplies untouched pools without making their arrival a
            // new recommendation generation or rebuilding already prepared cards.
            val baseline = cachePreviewPool
            if (current.source.isBlank()) {
                candidates.value = restoredPool
                savedCandidates = restoredPool
                savedSignal = cached.signal; savedGeneration = cached.generation
                lastDiscoveryRefresh = cached.refreshedAt
                lastRegionalRefresh = cached.chartRefreshedAt
            } else if (current.contextKey == cached.profileKey && baseline != null) {
                fun pool(live: List<MusicTrack>, initial: List<MusicTrack>, full: List<MusicTrack>) =
                    if (live == initial) full.ifEmpty { live } else live
                val sameSeed = current.seedKey == restoredPool.seedKey
                candidates.value = current.copy(
                    quick = pool(current.quick, baseline.quick, restoredPool.quick),
                    era = pool(current.era, baseline.era, restoredPool.era),
                    chart = pool(current.chart, baseline.chart, restoredPool.chart),
                    personalized = pool(current.personalized, baseline.personalized, restoredPool.personalized),
                    similar = if (sameSeed) pool(current.similar, baseline.similar, restoredPool.similar) else current.similar,
                    inspired = if (sameSeed) pool(current.inspired, baseline.inspired, restoredPool.inspired) else current.inspired,
                    ready = current.ready + restoredPool.ready.filter { shelf ->
                        preparedCards.value.section(shelf) == sections.section(shelf) &&
                            (shelf !in setOf(Shelf.SIMILAR, Shelf.INSPIRED) || sameSeed)
                    })
                if (savedCandidates == baseline) savedCandidates = candidates.value
            }
            cached.shelfLoads.forEach { (key, load) ->
                if ((shelfLoads[key]?.fetchedAt ?: 0L) < load.fetchedAt) shelfLoads[key] = load
            }
        }
        // Room/playback always wins over a later cache read, including an empty
        // authoritative library after the user clears history.
        if (!listeningCardsPublished) {
            if (_listeningHistoryCard.value != cached.history) _listeningHistoryCard.value = cached.history
            val artists = cached.artists.take(20)
            if (_mostListenedArtists.value != artists) _mostListenedArtists.value = artists
            cachedHero.value = cached.history.tracks.firstOrNull() ?: cached.catalog.firstOrNull()
            cachedHistory.value = cached.history.tracks
        }
        val catalogChanged = !additional || _catalogTracks.value.isEmpty() || _catalogTracks.value == restoredCatalogTracks
        if (catalogChanged && _catalogTracks.value != cached.catalog) {
            _catalogTracks.value = cached.catalog
            playbackManager.setCandidatePool(cached.catalog)
        }
        if (!additional) restoredCatalogTracks = cached.catalog
        if (preparedCards.value.quick.tracks.isEmpty() && preparedCards.value.chart.tracks.isEmpty()) {
            shelfLoads.remove(Shelf.QUICK.name); shelfAttempts.remove(Shelf.QUICK)
        }
        // Preview is already usable. A later full read only enriches unchanged
        // sections; the same presentation collector handles both without resets.
    }

    init {
        ownerScope.launch {
            restored.await()
            try {
                val key = profileKey()
                val cached = BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) {
                    UserTasteProfileStore.loadAsync(getApplication(), key)
                }
                if (key == profileKey() && lastTasteInputs == null && cached != null) {
                    cachedTasteSnapshot = cached
                    retainedTaste = cached.profile
                }
            } finally { tasteCacheLoaded.complete(Unit) }
        }
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
            awaitHomeIdle(allowWhenEmpty = false, quietMillis = 15_000L)
            deferredHomeReady = true
            _currentStage.value = LoadingStage.PRIORITY_3_BACKGROUND_ENRICHED
            if (startupBootstrapUsed) {
                // Top up from cached discovery/mappings; do not repeat already verified lookups.
                shelfLoads.remove(Shelf.QUICK.name)
                shelfAttempts.remove(Shelf.QUICK)
            }
            refreshHome(false)
            // Optional catalog enrichment is independent of first-shelf readiness.
            ownerScope.launch {
                if (_catalogTracks.value.isEmpty()) {
                    val initial = BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED) {
                        repository.getInitialCatalog(_userProfile.value)
                    }
                    if (_catalogTracks.value.isEmpty()) _catalogTracks.value = initial
                    playbackManager.setCandidatePool(_catalogTracks.value)
                }
            }
        }
        dataScope.launch {
            essentialsLoaded.await()
            // Long-term enrichment is independent of first-shelf preparation.
            // A cache hit or completion/failure of the minimal bootstrap releases
            // it; playback, navigation and recent-history publication never wait.
            startupRecommendationsComplete.await()
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
            combine(preparedCards, recentlyPlayed, tasteProfile, recommendationSeed,
                _listeningHistoryCard) { cards, recent, taste, seed, listening ->
                DisplayInputs(cards, recent, taste, seed, listening)
            }.distinctUntilChanged().mapLatest { inputs ->
                // Do not prepare a new visible list while a retained Home is hidden,
                // scrolling or sliding. mapLatest keeps only the newest inputs.
                awaitHomePresentation()
                val snapshot = withContext(workerDispatcher) {
                    hideRecentRecommendations(inputs.cards,
                        inputs.recent + inputs.listening.tracks + listOfNotNull(inputs.seed), inputs.taste)
                }
                awaitHomePresentation()
                currentCoroutineContext().ensureActive()
                inputs to snapshot
            }.collect { (inputs, snapshot) ->
                if (inputs.cards != preparedCards.value || inputs.recent != recentlyPlayed.value ||
                    inputs.taste != tasteProfile.value || inputs.seed != recommendationSeed.value ||
                    inputs.listening != _listeningHistoryCard.value) return@collect
                if (_recommendationCards.value != snapshot) _recommendationCards.value = snapshot
                if (snapshot.quick.tracks.isNotEmpty() || snapshot.chart.tracks.isNotEmpty())
                    startupRecommendationsComplete.complete(Unit)
            }
        }
        ownerScope.launch {
            preparedCards.map { cards -> listOf(cards.quick, cards.era, cards.chart)
                .filter { it.isError && !it.isLoading }.map { it.title } }
                .distinctUntilChanged().collect { failed ->
                    _refreshErrorMessage.value = failed.takeIf { it.isNotEmpty() }?.let {
                        "${it.joinToString(", ")} couldn't load recommendations. Tap Retry to try again."
                    }
                }
        }
        ownerScope.launch {
          try {
            val cached = BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI) {
                initialCards ?: HomeCardCache.preloadAsync(getApplication(), profileKey(), previewOnly = true).await()
            }
            if (cached != null) restoreCachedCards(cached, additional = false)
            if (preparedCards.value.quick.tracks.isNotEmpty() || preparedCards.value.chart.tracks.isNotEmpty())
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
                    yieldHomePreparation()
                    restoreCachedCards(cached, additional = true)
                }
            } finally {
                // Partial first-read snapshots must never overwrite full disk pools.
                additionalCacheRestored.complete(Unit)
                savePreparedCards()
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
                withContext(workerDispatcher) {
                    ArtistHistory(history.events.asSequence().filter(UserTasteProfile::played)
                        .map { it.sessionId to it.trackId }.distinct().toList(), history.tracks.map { it.id }) to history.tracks
                }
            }.distinctUntilChanged().collect { listeningCardRequests.update { it + 1 } }
        }
        ownerScope.launch {
            combine(candidates, tasteProfile, favoriteTracks, _userProfile, recentlyPlayed) { pool, taste, favorites, profile, recent ->
                RenderTrigger(listOf(pool.generation, pool.revision, pool.source, pool.contextKey), taste, favorites, profile, recent)
            }.distinctUntilChanged().debounce(300L).conflate().collect {
                    // Retain a bounded preparation in progress instead of repeatedly
                    // restarting it for every verified song. Publication still rejects
                    // stale generations/revisions, then consumes only the latest input.
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
                    awaitHomeIdle(allowWhenEmpty = false, quietMillis = 15_000L)
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
                    personalized = usable(pool.personalized), similar = usable(pool.similar), inspired = usable(pool.inspired), revision = pool.revision + 1) }
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
                if (demand.first.isEmpty()) return@collectLatest
                awaitHomePresentation()
                if (activeRecommendationSeed != demand.second && activeRecommendationShelves.isNotEmpty() &&
                    activeRecommendationShelves.all { it == Shelf.SIMILAR || it == Shelf.INSPIRED }) {
                    // An obsolete contextual-only pass must not delay the newly selected song.
                    homeRefreshJob?.cancelAndJoin()
                }
                val emptyDemand = shelfDemand().filterTo(mutableSetOf()) {
                    preparedCards.value.section(it).tracks.isEmpty() && needsShelfLoad(it)
                }
                val currentPriority = activeRefreshPriority
                val waiting = currentPriority?.waitingShelf?.value
                val active = currentPriority?.activeShelf?.value
                if (!activeRefreshIsExplicit && emptyDemand.isNotEmpty() &&
                    (waiting != null && waiting !in emptyDemand || active != null &&
                        active !in currentPriority.essentialShelves && active !in emptyDemand)) {
                    // New visible empty content outranks a stale/offscreen card.
                    // Retain its already-verified mappings and prepared snapshots.
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
                preparedCards.value.quick.tracks.isEmpty() && preparedCards.value.chart.tracks.isEmpty() ||
                System.currentTimeMillis() - lastDiscoveryRefresh >= 30 * 60_000L) {
                if (!pendingExplicitRefresh && (preparedCards.value.quick.tracks.isNotEmpty() || preparedCards.value.chart.tracks.isNotEmpty()))
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
            if (!homeVisible.value) {
                if (isExplicitPull) pendingExplicitRefresh = true else pendingSilentRefresh = true
                return@launch
            }
            // Empty shelves are not necessarily in the viewport yet (hero/onboarding).
            // Bootstrap only essential discovery; all lower shelves retain on-demand loading.
            val bootstrap = if (preparedCards.value.quick.tracks.isEmpty() && preparedCards.value.chart.tracks.isEmpty())
                setOf(Shelf.QUICK) else emptySet()
            val demand = shelfDemand(allowDeferred = isExplicitPull || pendingExplicitRefresh || activeRefreshIsExplicit) + bootstrap
            val requested = (requestedShelves ?: demand).intersect(demand)
                .filterTo(mutableSetOf()) { isExplicitPull || pendingExplicitRefresh || needsShelfLoad(it) }
                // A failed/negative-cached bootstrap must not discard another
                // visible shelf's demand and leave a first installation empty.
                .initialCardDemand(bootstrap, automaticStartup = !deferredHomeReady && !isExplicitPull && !pendingExplicitRefresh)
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
            val startupBatch = !deferredHomeReady && !initialExplicit && requested.any(bootstrap::contains)
            val emptyVisible = (recommendationViewport.value + bootstrap).filterTo(mutableSetOf()) {
                it in requested && preparedCards.value.section(it).tracks.isEmpty()
            }
            val priority = DiscoveryRequestCoordinator.HomeRefresh(initialExplicit, initialShelf = startupBatch,
                essentialShelves = emptyVisible)
            activeRefreshPriority = priority
            activeRefreshIsExplicit = initialExplicit
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
                            try {
                                if (emptyVisible.isNotEmpty()) awaitHomePresentation() else awaitHomeIdle()
                            }
                            finally { if (generation == refreshGeneration) refreshWaitingForIdle = false }
                        }
                        lastDiscoveryAttempt = System.currentTimeMillis()
                        lastAttemptProfile = profileKey()
                        _isRefreshing.value = activeRefreshIsExplicit
                        _refreshErrorMessage.value = null
                        preparedCards.update { cards -> requested.fold(cards) { next, shelf ->
                            val section = next.section(shelf)
                            next.withSection(shelf, section.copy(isLoading = section.tracks.isEmpty(), isError = false, errorMessage = ""))
                        } }
                        try {
                            // Each shelf owns its own admission/deadline. A silent
                            // pass waiting for optional idle must not expire before
                            // it starts; an explicit pull keeps a bounded budget.
                            val completed = priority.withBudget(40_000L) {
                                refreshSuggestions(activeRefreshIsExplicit, requested, startupBatch && !activeRefreshIsExplicit)
                                true
                            }
                            if (completed == null && generation == refreshGeneration)
                                _refreshErrorMessage.value = "Refresh took too long. Your existing cards have been kept. Please retry."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            _refreshErrorMessage.value = e.message ?: "Recommendations are temporarily unavailable."
                            if (Shelf.CHART in requested) updateSection(Shelf.CHART) { it.copy(isLoading = false, isError = true, errorMessage = e.message.orEmpty()) }
                            if (Shelf.QUICK in requested) updateSection(Shelf.QUICK) { it.copy(isLoading = false, isError = true,
                                errorMessage = "Quick Picks are temporarily unavailable.") }
                            if (Shelf.ERA in requested) updateSection(Shelf.ERA) { it.copy(isLoading = false, isError = true,
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
                        preparedCards.update { cards -> requested.fold(cards) { next, shelf ->
                            val section = next.section(shelf)
                            // Verification can finish before the renderer publishes.
                            // Keep its skeleton instead of briefly hiding the ready shelf.
                            next.withSection(shelf, section.finishPreparation(shelf in candidates.value.ready))
                        } }
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
        val requestGeneration = refreshGeneration
        val seed = activeRecommendationTrack()
        BackgroundWorkCoordinator.awaitNavigationIdle(requireForeground = true)
        val bootstrap = if (preparedCards.value.quick.tracks.isEmpty() && preparedCards.value.chart.tracks.isEmpty())
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
                if (profileKey() != profileKey(profile) || requestGeneration != refreshGeneration) return@collect
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
                        generation = nextGeneration, revision = old.revision + 1, source = UnifiedRecommendationEngine.SOURCE,
                        seedKey = if (updatesSeed) batch.seedKey else old.seedKey,
                        seedArtist = if (updatesSeed) batch.seedArtist else old.seedArtist,
                        seedTitle = if (updatesSeed) batch.seedTitle else old.seedTitle,
                        ready = old.ready.takeIf { trusted }.orEmpty() + successful, contextKey = profileKey(profile))
                }
            }
            if (requestGeneration == refreshGeneration && profileKey() == profileKey(profile)) {
                preparedCards.update { cards -> enabled.fold(cards) { next, shelf ->
                    val state = next.section(shelf)
                    // Successful data is internal until rendered. It is not a
                    // failure merely because its visible snapshot is still empty.
                    next.withSection(shelf, state.finishPreparation(shelf in candidates.value.ready))
                } }
            }
        } finally {
            // At most one library transaction for this pass; no per-song Room
            // invalidation storm. Snapshot/mapping caches have their own batch writers.
            if (fresh.isNotEmpty() && requestGeneration == refreshGeneration && profileKey() == profileKey(profile)) {
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
        if (!preparedCards.value.era.isLoaded && !preparedCards.value.era.isLoading) refreshHome(false)
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
