package com.example.recommendation

import com.example.performance.AppDispatchers

import android.content.Context
import com.example.performance.BackgroundWorkCoordinator
import com.example.data.cache.RecommendationCache
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogNames
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong

/** One metadata-discovery / local-ranking / verified-catalog pipeline for Home and radio. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedRecommendationEngine(context: Context?,
    private val limits: Limits = Limits(), private val weights: Weights = Weights()) {
    enum class Shelf(val itemKey: String) {
        QUICK("card_2_ytm_dynamic_feed"), ERA("card_3_era_specific_hits"),
        CHART("card_4_country_language_hits"), PERSONALIZED("card_6_personalized_recommendation"),
        SIMILAR("card_7_similar_artists"), INSPIRED("card_8_inspired_by_track")
    }
    data class Batch(val quick: List<MusicTrack> = emptyList(), val era: List<MusicTrack> = emptyList(),
        val chart: List<MusicTrack>? = null, val personalized: List<MusicTrack> = emptyList(),
        val similarArtists: List<MusicTrack> = emptyList(), val inspiredTrack: List<MusicTrack> = emptyList(),
        val chartFetchedAt: Long = 0, val seedKey: String = "", val seedArtist: String = "", val seedTitle: String = "",
        val requested: Set<Shelf> = emptySet(), val fetchedAt: Map<Shelf, Long> = emptyMap())
    data class Weights(val language: Float = .50f, val country: Float = .30f,
        val mood: Float = .15f, val discovery: Float = .05f)
    data class Limits(val matchConcurrency: Int = 3, val candidatesPerShelf: Int = 24,
        val shelfSize: Int = 12, val reserve: Int = 4)
    private data class Candidate(val track: MusicTrack, val language: String = "", val regional: Boolean = false,
        val related: Boolean = false, val era: Boolean = false, val ordinal: Int = 0,
        val mood: String = "")
    private val cache = context?.let { RecommendationCache.get(it) }
    private val discovery = YouTubeRecommendationDiscovery(cache)
    private val ranking = BackgroundWorkCoordinator.optionalCpuDispatcher
    private val urgentRanking = AppDispatchers.CpuBounded
    private val discoveryWorkers = Semaphore(2)
    private fun confidence(seed: MusicTrack, candidate: MusicTrack): Int? =
        RecordingIdentity.providerConfidence(seed, candidate, durationToleranceMs = 5_000L)
    private val rotation = AtomicLong()
    private val failures = MutableStateFlow<Map<String, Long>>(emptyMap())
    val unavailable: StateFlow<Map<String, Long>> = failures.asStateFlow()
    internal fun markUnavailable(track: MusicTrack) {
        if (!track.recommendationOnly) return
        val now = android.os.SystemClock.elapsedRealtime()
        failures.update { previous -> (previous.filterValues { it > now } + (track.id to (now + 30_000L)))
            .entries.toList().takeLast(128).associate { it.key to it.value } }
    }
    private fun unavailable(track: MusicTrack) = (failures.value[track.id] ?: 0L) > android.os.SystemClock.elapsedRealtime()

    companion object {
        internal const val INITIAL_SHELF_SIZE = 3
        internal fun requestedShelfSize(startupBatch: Boolean, initialPresentation: Boolean, fullSize: Int): Int =
            if (startupBatch || initialPresentation) INITIAL_SHELF_SIZE else fullSize

        /** Independent initial shelves share network/matching limits, not a single
         * long-lived startup admission lease. Optional shelves remain serialized
         * by the coordinator, including its idle gate and card spacing. */
        internal suspend fun prepareShelves(shelves: List<Shelf>, initialPresentation: Boolean,
            load: suspend (Shelf) -> Unit) = coroutineScope {
            if (!initialPresentation) shelves.forEach { load(it) }
            else {
                val slots = Semaphore(2)
                shelves.map { shelf -> launch { slots.withPermit { load(shelf) } } }.joinAll()
            }
        }

        const val SOURCE = "native-discovery-mood-v3-youtube"
        const val CHART_TTL = 3 * 60 * 60_000L
        const val CONTEXT_TTL = 6 * 60 * 60_000L
        fun seedKey(track: MusicTrack?): String = track?.let { HomeRecommendationPolicy.metadataKey(it) }.orEmpty()
        /** A real YouTube catalog identity is sufficient for a Home card. Audio
         * extraction belongs to playback, never to recommendation presentation. */
        fun verified(track: MusicTrack): Boolean = track.id.startsWith("yt_") &&
            com.example.data.remote.YouTubeAudioStreamService.isVideoId(track.id.removePrefix("yt_")) &&
            track.title.isNotBlank() && track.artist.isNotBlank() && !track.isLossless &&
            OriginalMusicFilter.isHomeRecommendation(track)
        fun language(value: String): String = when (val name = CatalogNames.normalize(value)) {
            "panjabi", "pa" -> "punjabi"; "hin", "hi" -> "hindi"; "eng", "en" -> "english"
            else -> name
        }
    }

    private suspend fun yieldHomeWork() {
        val priority = currentCoroutineContext()[DiscoveryRequestCoordinator.HomeRefresh]
        BackgroundWorkCoordinator.yieldToInteraction(requireForeground = true,
            awaitFrameQuiet = !DiscoveryRequestCoordinator.essentialHomeWork(),
            bypass = { priority?.requested?.value == true })
    }

    /** Each emission changes one shelf, in stable batches. Nothing waits for all cards. */
    fun refresh(profile: UserProfile, country: String, taste: UserTasteProfile, current: MusicTrack?,
        history: List<MusicTrack>, favorites: List<MusicTrack>, known: List<MusicTrack>, force: Boolean,
        shelves: Set<Shelf> = Shelf.entries.toSet(), startupBatch: Boolean = false): Flow<Batch> = channelFlow {
        val variation = if (force) rotation.incrementAndGet() else rotation.get()
        val seed = current ?: history.firstOrNull()
        val artist = seed?.let(CustomRecommendationQueryWrapper::extractPrimaryArtist).orEmpty()
        val recent = HomeRecommendationPolicy.Recordings().apply {
            current?.let { add(it) }
            history.filter { taste.keyFor(it) in taste.recentRecordings || it.id in taste.topRecentTrackIds }
                .forEach { add(it) }
        }
        val ordering = listOf(Shelf.QUICK, Shelf.CHART, Shelf.ERA, Shelf.PERSONALIZED, Shelf.SIMILAR, Shelf.INSPIRED)
        // Only requested/visible shelves are queued. Below-the-fold shelves do no work.
        val requested = ordering.filter { it in shelves }
        val priority = currentCoroutineContext()[DiscoveryRequestCoordinator.HomeRefresh]
        val initialPresentation = priority?.initialPresentation == true
        // The popup path must never accidentally request shelfSize + reserve.
        val initialBatch = startupBatch || initialPresentation
        prepareShelves(requested, initialPresentation) { shelf ->
            // Direct compatibility entry points request a specific visible shelf;
            // Home supplies explicit optional/essential demand in its context.
            val essential = initialBatch || priority == null || shelf in priority.essentialShelves
            if (!initialPresentation) priority?.waitingShelf?.value = shelf
            try {
                suspend fun load() {
                    if (!initialPresentation) {
                        priority?.waitingShelf?.value = null
                        priority?.activeShelf?.value = shelf
                    }
                    withContext(DiscoveryRequestCoordinator.HomeCard(essential)) {
                        // The parent refresh owns one shared cold-start budget.
                        // No independent 15s wait is added for each initial shelf.
                        withTimeout(if (initialPresentation) DiscoveryRequestCoordinator.INITIAL_DISCOVERY_BUDGET_MS else 40_000L) {
                            yieldHomeWork()
                            val candidates = discover(shelf, profile, country, taste, seed, history, favorites, force, variation, initialPresentation,
                                initialBatch = initialBatch)
                                .filterNot { recent.contains(it.track) || taste.keyFor(it.track) in taste.recentRecordings }
                            resolveBatches(candidates, shelf, profile, taste, known + history + favorites, initialPresentation,
                                recent = recent, maxResults = requestedShelfSize(startupBatch, initialPresentation, limits.shelfSize + limits.reserve),
                                concurrency = if (initialBatch) minOf(2, limits.matchConcurrency) else limits.matchConcurrency).collect { tracks ->
                                val now = System.currentTimeMillis()
                                send(Batch(quick = tracks.takeIf { shelf == Shelf.QUICK }.orEmpty(),
                                    era = tracks.takeIf { shelf == Shelf.ERA }.orEmpty(),
                                    chart = tracks.takeIf { shelf == Shelf.CHART },
                                    personalized = tracks.takeIf { shelf == Shelf.PERSONALIZED }.orEmpty(),
                                    similarArtists = tracks.takeIf { shelf == Shelf.SIMILAR }.orEmpty(),
                                    inspiredTrack = tracks.takeIf { shelf == Shelf.INSPIRED }.orEmpty(),
                                    chartFetchedAt = now, seedKey = seedKey(seed), seedArtist = artist,
                                    seedTitle = seed?.title.orEmpty(), requested = setOf(shelf), fetchedAt = mapOf(shelf to now)))
                            }
                        }
                    }
                }
                if (initialPresentation) load()
                else BackgroundWorkCoordinator.startup(if (essential || force)
                    BackgroundWorkCoordinator.StartupPriority.ESSENTIAL_UI else BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                    urgent = { priority?.requested?.value == true }, cardWork = true,
                    label = "Home shelf ${shelf.name}") { load() }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive() // A canceled parent must not start another card.
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep other shelves and the last verified snapshot. */ }
            finally {
                if (!initialPresentation) {
                    priority?.waitingShelf?.value = null
                    priority?.activeShelf?.value = null
                }
            }
        }
    }.flowOn(ranking)

    suspend fun radio(seed: MusicTrack, profile: UserProfile, taste: UserTasteProfile,
        queued: List<MusicTrack>, limit: Int): List<MusicTrack> = withContext(urgentRanking) {
        val excluded = HomeRecommendationPolicy.Recordings().apply { add(seed); queued.forEach { add(it) } }
        val found = mutableListOf<MusicTrack>()
        suspend fun collectMatches(pool: List<Candidate>, shelf: Shelf) {
            resolveBatches(pool, shelf, profile, taste, queued, true).transformWhile { tracks ->
                tracks.filter { !excluded.contains(it) }.forEach { if (excluded.add(it)) found.add(it) }
                emit(Unit)
                found.size < limit
            }.collect()
        }
        // Keep partial successes if the lookup budget expires; a slow later match
        // must not discard songs already verified for an almost-empty queue.
        withTimeoutOrNull(13_000L) {
            val related = discover(Shelf.INSPIRED, profile, profile.country, taste, seed, listOf(seed), emptyList(), false, 0, true)
            collectMatches(related, Shelf.INSPIRED)
            if (found.size < limit) {
                val extra = discover(Shelf.QUICK, profile, profile.country, taste, seed, listOf(seed), emptyList(), false, 0, true)
                collectMatches(extra, Shelf.QUICK)
            }
        }
        found.take(limit.coerceAtMost(limits.shelfSize))
    }

    private fun recentMoods(history: List<MusicTrack>, taste: UserTasteProfile): Map<String, Float> {
        val positions = taste.topRecentTrackIds.withIndex().associate { it.value to it.index }
        val signals = history.filter { it.id in positions }.sortedBy { positions[it.id] }.take(30)
        val moods = mutableMapOf<String, Float>()
        signals.forEachIndexed { index, track ->
            HomeRecommendationPolicy.moodForTrack(track)?.let { mood ->
                val skipped = (taste.skipCounts[taste.keyFor(track)] ?: 0) > 0
                if (!skipped) moods[mood] = (moods[mood] ?: 0f) + 1f / (1f + index * .15f)
            }
        }
        val maximum = moods.values.maxOrNull() ?: return emptyMap()
        return moods.mapValues { it.value / maximum }
    }

    private suspend fun discover(shelf: Shelf, profile: UserProfile, country: String, taste: UserTasteProfile,
        seed: MusicTrack?, history: List<MusicTrack>, favorites: List<MusicTrack>, force: Boolean,
        variation: Long, essential: Boolean, initialBatch: Boolean = false): List<Candidate> {
        val languages = profile.languages.map(::language).filter(HomeRecommendationPolicy::known).distinct()
        val gl = profile.countryCode.trim().uppercase(java.util.Locale.ROOT)
        val ttl = if (shelf == Shelf.ERA) 24 * 60 * 60_000L else if (shelf in setOf(Shelf.QUICK, Shelf.CHART)) CHART_TTL else CONTEXT_TTL
        val found = mutableListOf<Candidate>()
        val moods = recentMoods(history, taste)
        val mood = moods.maxByOrNull { it.value }?.key.orEmpty()
        suspend fun pool(op: String, query: String, lang: String = "", regional: Boolean = false,
            related: Boolean = false, era: Boolean = false, moodHint: String = "") {
            if (!essential) yieldHomeWork()
            val tracks = discoveryWorkers.withPermit { withTimeoutOrNull(8_000L) {
                discovery.tracks(op, query, gl, force, essential, ttl)
            }.orEmpty() }
            tracks.forEachIndexed { index, track -> found.add(Candidate(track, lang, regional, related, era, index, moodHint)) }
        }
        withTimeoutOrNull(12_000L) {
        if (shelf in setOf(Shelf.INSPIRED, Shelf.SIMILAR, Shelf.PERSONALIZED)) {
            val personal = (favorites + history).distinctBy(HomeRecommendationPolicy::metadataKey)
                .maxByOrNull { track -> HomeRecommendationPolicy.artists(track).maxOfOrNull { taste.artistAffinities[it] ?: 0f } ?: 0f }
            val pivot = if (shelf == Shelf.PERSONALIZED) personal ?: seed else seed
            if (pivot != null) withTimeoutOrNull(5_000L) {
                var video = pivot.id.removePrefix("yt_").takeIf { pivot.id.startsWith("yt_") && it.length == 11 }
                val query = "${StreamResolver.extractCleanTitle(pivot.title, pivot.artist)} ${CustomRecommendationQueryWrapper.extractPrimaryArtist(pivot)}"
                if (shelf == Shelf.SIMILAR) {
                    val performer = CustomRecommendationQueryWrapper.extractPrimaryArtist(pivot)
                    if (performer.isNotBlank()) pool("similar", performer, related = true)
                }
                else {
                    if (video == null) {
                        val seeds = discoveryWorkers.withPermit { discovery.tracks("search", query, gl, false, essential, CONTEXT_TTL) }
                        video = seeds.firstOrNull { confidence(pivot, it) != null }?.id?.removePrefix("yt_")
                    }
                    if (video != null) pool("related", video, related = true)
                }
            }
            // Anonymous related/artist browse renderers may be empty. Use a small,
            // section-specific metadata search instead of leaving the card permanently blank.
            if (found.size < limits.shelfSize) {
                val performer = pivot?.let(CustomRecommendationQueryWrapper::extractPrimaryArtist).orEmpty()
                for (lang in languages.ifEmpty { listOf("") }.take(2)) {
                    when (shelf) {
                        Shelf.PERSONALIZED -> pool("search", "$lang $mood songs $country".trim(),
                            lang, regional = true, moodHint = mood)
                        Shelf.SIMILAR -> if (performer.isNotBlank()) pool("search", "$lang artists like $performer songs".trim(),
                            lang, related = true)
                        Shelf.INSPIRED -> if (pivot != null) pool("search",
                            "$lang $mood songs like ${StreamResolver.extractCleanTitle(pivot.title, pivot.artist)} $performer".trim(),
                            lang, related = true, moodHint = mood)
                        else -> Unit
                    }
                    if (found.size >= limits.candidatesPerShelf) break
                }
            }
        } else {
            if (shelf == Shelf.CHART && !initialBatch) pool("charts", "FEmusic_charts", regional = true)
            val selected = languages.ifEmpty { listOf("") }
            // Balanced rounds cover all selected languages; cap work per refresh and
            // rotate the starting language rather than permanently dropping selections.
            val start = (variation % selected.size).toInt()
            for (i in 0 until minOf(selected.size, 4)) {
                val lang = selected[(start + i) % selected.size]
                val query = when (shelf) {
                    Shelf.ERA -> "$lang ${if ((i + variation) % 2L == 0L) "1990s" else "2000s"} original hits"
                    Shelf.CHART -> "$lang popular songs $country"
                    else -> {
                        val genre = taste.genreAffinities.filterValues { it > 0f }.maxByOrNull { it.value }?.key.orEmpty()
                        val topic = if (variation % 2L == 0L) "new releases" else "original hits"
                        "$lang $genre $mood $topic $country"
                    }
                }.trim()
                pool("search", query, lang, regional = true, era = shelf == Shelf.ERA,
                    moodHint = mood.takeIf { shelf == Shelf.QUICK }.orEmpty())
                // First installation needs a usable first shelf, not the whole
                // multilingual pool. The normal follow-up resumes all preferences.
                if (initialBatch && found.size >= limits.shelfSize) break
            }
            // Anonymous editorial discovery is supporting evidence only, never a
            // substitute for selected-language candidates or a claimed official chart.
            if (found.isEmpty() && shelf == Shelf.CHART && initialBatch)
                pool("charts", "FEmusic_charts", regional = true)
            if (found.isEmpty() && shelf != Shelf.ERA)
                pool("browse", "FEmusic_home")
            // Public-feed candidates still pass language filtering and exact
            // recording/JioSaavn verification; regional defaults never override
            // explicit preferences.

        }
        }
        return rank(found, shelf, profile, taste, moods, variation, essential)
    }

    private suspend fun rank(pool: List<Candidate>, shelf: Shelf, profile: UserProfile, taste: UserTasteProfile,
        moods: Map<String, Float>, variation: Long, essential: Boolean): List<Candidate> =
        withContext(if (essential) urgentRanking else ranking) {
        val selected = profile.languages.map(::language).toSet()
        val seen = HomeRecommendationPolicy.Recordings()
        val merged = pool.groupBy { it.track.id }.values.map { aliases ->
            val first = aliases.first()
            first.copy(language = aliases.firstOrNull { it.language.isNotBlank() }?.language.orEmpty(),
                regional = aliases.any { it.regional }, related = aliases.any { it.related },
                mood = aliases.firstOrNull { it.mood.isNotBlank() }?.mood.orEmpty())
        }
        val eligible = merged.filter { candidate ->
            val lang = language(candidate.track.language)
            OriginalMusicFilter.isHomeRecommendation(candidate.track) &&
                taste.keyFor(candidate.track) !in taste.recentRecordings &&
                (!HomeRecommendationPolicy.known(lang) || selected.isEmpty() || lang in selected) &&
                seen.add(candidate.track)
        }
        fun score(c: Candidate): Float {
            val track = c.track
            val lang = language(track.language).takeIf(HomeRecommendationPolicy::known) ?: c.language
            val mood = HomeRecommendationPolicy.moodForTrack(track) ?: c.mood
            val moodAffinity = (moods[mood] ?: 0f).coerceIn(0f, 1f)
            val key = taste.keyFor(track)
            return 100 * (weights.language * (if (lang in selected) 1f else 0f) +
                weights.country * (if (c.regional) 1f else .35f) + weights.mood * moodAffinity +
                weights.discovery / (1 + c.ordinal)) - (taste.skipCounts[key] ?: 0) * 20 -
                minOf(taste.playCounts[key] ?: 0, 10) * 2 - (if (key in taste.recentRecordings) 10 else 0) +
                (if (variation > 0) ((track.id.hashCode().toLong() xor variation) and 7).toFloat() * .3f else 0f)
        }
        val sorted = eligible.sortedByDescending(::score)
        // Interleave language groups before matching so a single language cannot
        // consume all request slots. Track metadata remains the provider's own.
        val groups = sorted.groupBy { language(it.track.language).takeIf(HomeRecommendationPolicy::known) ?: it.language }
        val diverse = groups.values.map { group ->
            interleave(group.groupBy { HomeRecommendationPolicy.artists(it.track).firstOrNull().orEmpty() }.values.toList())
        }
        interleave(diverse).take(limits.candidatesPerShelf)
    }

    private fun resolveBatches(candidates: List<Candidate>, shelf: Shelf, profile: UserProfile,
        taste: UserTasteProfile, known: List<MusicTrack>, essential: Boolean,
        recent: HomeRecommendationPolicy.Recordings? = null,
        maxResults: Int = limits.shelfSize + limits.reserve,
        concurrency: Int = limits.matchConcurrency): Flow<List<MusicTrack>> = flow {
        val selected = profile.languages.map(::language).toSet()
        val output = mutableListOf<MusicTrack>()
        val seen = HomeRecommendationPolicy.Recordings()
        val artists = mutableMapOf<String, Int>()
        val albums = mutableMapOf<String, Int>()
        val languageCounts = mutableMapOf<String, Int>()
        val languageQuota = (limits.shelfSize + selected.size.coerceAtLeast(1) - 1) / selected.size.coerceAtLeast(1)
        var published = 0
        fun add(c: Candidate, track: MusicTrack?) {
            if (output.size >= maxResults) return
            val candidateTrack = track ?: c.track.takeIf { verified(it) }
            if (candidateTrack == null || !verified(candidateTrack) || unavailable(candidateTrack) ||
                seen.contains(candidateTrack) || recent?.contains(candidateTrack) == true ||
                recent != null && (taste.keyFor(candidateTrack) in taste.recentRecordings || recent.contains(c.track))) return
            val lang = language(candidateTrack.language)
            if (selected.isNotEmpty() && (if (HomeRecommendationPolicy.known(lang)) lang !in selected else c.language !in selected && !c.related)) return
            if (shelf == Shelf.ERA && (candidateTrack.year.toIntOrNull()?.let { it !in 1990..2009 }
                    ?: !c.era)) return
            val actualLanguage = lang.takeIf(HomeRecommendationPolicy::known) ?: c.language
            if (selected.size > 1 && actualLanguage in selected && (languageCounts[actualLanguage] ?: 0) >= languageQuota) return
            val key = taste.keyFor(candidateTrack)
            if ((taste.skipCounts[key] ?: 0) >= 2 && key !in taste.favoriteRecordings) return
            val artist = HomeRecommendationPolicy.artists(candidateTrack).firstOrNull().orEmpty()
            val album = "$artist|${CatalogNames.normalize(candidateTrack.album)}"
            if ((artists[artist] ?: 0) >= 2 || HomeRecommendationPolicy.known(candidateTrack.album) && (albums[album] ?: 0) >= 2) return
            seen.add(candidateTrack); artists[artist] = (artists[artist] ?: 0) + 1; albums[album] = (albums[album] ?: 0) + 1
            languageCounts[actualLanguage] = (languageCounts[actualLanguage] ?: 0) + 1
            output.add(candidateTrack.copy(recommendationOnly = true))
        }
        // Discovery already returns stable YouTube IDs and original metadata.
        // Keep the same ranked order, diversity and language rules; no provider
        // availability lookup or artwork work gates a prepared shelf.
        for ((index, candidate) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!essential && index % 4 == 0) yieldHomeWork()
            add(candidate, candidate.track)
            if (output.size >= published + (if (published == 0) 1 else 3)) {
                emit(output.take(limits.shelfSize).toList()); published = output.size
            }
            if (output.size >= maxResults) break
        }
        if (output.isNotEmpty() && output.size != published) emit(output.take(limits.shelfSize).toList())
    }.distinctUntilChanged().flowOn(if (essential) urgentRanking else ranking)

    private fun <T> interleave(groups: List<List<T>>): List<T> = buildList {
        repeat(groups.maxOfOrNull { it.size } ?: 0) { index -> groups.forEach { it.getOrNull(index)?.let(::add) } }
    }

}
