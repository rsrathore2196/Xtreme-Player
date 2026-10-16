package com.example.recommendation

import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.data.remote.LastFmApiService as LastFm
import com.example.data.repository.CatalogNames
import com.example.data.repository.MusicRepository
import com.example.data.repository.StreamResolver
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Last.fm candidate selection with local taste ranking retained by HomeViewModel. */
class LastFmHomeRecommendationEngine(private val repository: MusicRepository) {
    enum class Shelf(val itemKey: String) {
        QUICK("card_2_ytm_dynamic_feed"), ERA("card_3_era_specific_hits"),
        CHART("card_4_country_language_hits"), PERSONALIZED("card_6_personalized_recommendation"),
        SIMILAR("card_7_similar_artists"), INSPIRED("card_8_inspired_by_track")
    }
    data class Batch(val quick: List<MusicTrack>, val era: List<MusicTrack>, val chart: List<MusicTrack>?,
        val personalized: List<MusicTrack>, val similarArtists: List<MusicTrack>, val inspiredTrack: List<MusicTrack>,
        val chartFetchedAt: Long = 0L, val seedKey: String = "", val seedArtist: String = "", val seedTitle: String = "",
        val requested: Set<Shelf> = emptySet(), val fetchedAt: Map<Shelf, Long> = emptyMap())
    private val variations = AtomicLong(0)
    companion object {
        const val SOURCE = "lastfm-v2-preferences"
        fun seedKey(track: MusicTrack?): String = track?.let {
            CatalogNames.normalize(it.artist) + "|" + CatalogNames.normalize(it.title)
        }.orEmpty()
    }
    private val versions = Regex("(?i)\\b(?:live|remix|remaster(?:ed)?|radio edit|acoustic|instrumental|karaoke|cover|slowed|reverb|extended|demo)\\b")
    private val filmCredit = Regex("(?i)\\s*(?:\\(from\\s+[^)]*\\)|[-–—]\\s*from\\s+.+)$")
    private val featuredCredit = Regex("(?i)\\s*[\\[(](?:feat\\.?|ft\\.?|featuring)\\s+[^)\\]]*[)\\]]")
    private fun title(value: String, artist: String) = CatalogNames.normalize(StreamResolver.extractCleanTitle(value, artist)
        .replace(filmCredit, "").replace(featuredCredit, "")).replace(" ", "")
    private fun matches(seed: LastFm.Track, track: MusicTrack): Boolean {
        if (title(seed.title, seed.artist) != title(track.title, track.artist)) return false
        if (versions.findAll(seed.title).map { it.value.lowercase() }.toSet() !=
            versions.findAll(track.title).map { it.value.lowercase() }.toSet()) return false
        val artist = CatalogNames.normalize(seed.artist).replace(" ", "")
        val credits = (HomeRecommendationPolicy.rawArtists(track) + track.artist).map {
            CatalogNames.normalize(StreamResolver.extractCleanArtist(it)).replace(" ", "") }
        if (credits.none { it == artist || CatalogNames.spellingEquivalent(it, artist) }) return false
        return seed.durationMs <= 0 || track.durationMs <= 0 || kotlin.math.abs(seed.durationMs - track.durationMs) <= 10_000L
    }

    suspend fun refresh(profile: UserProfile, country: String, taste: UserTasteProfile,
        current: MusicTrack?, history: List<MusicTrack>, favorites: List<MusicTrack>, known: List<MusicTrack>,
        force: Boolean, shelves: Set<Shelf> = Shelf.entries.toSet()): Batch = withContext(repository.homeDispatcher) {
        if (!LastFm.isConfigured) throw IOException(LastFm.CONFIGURATION_ERROR)
        val failure = AtomicReference<Exception?>(null)
        suspend fun safe(block: suspend () -> List<LastFm.Track>): List<LastFm.Track> = try { block() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { failure.compareAndSet(null, e); emptyList() }
        val artist = current?.let(CustomRecommendationQueryWrapper::extractPrimaryArtist)
            ?.takeIf(HomeRecommendationPolicy::known).orEmpty()
        val rotation = if (force && (Shelf.QUICK in shelves || Shelf.ERA in shelves)) variations.incrementAndGet() else variations.get()
        val page = 1 + (rotation % 3).toInt()
        val languages = profile.languages.map(CatalogNames::normalize)
            .filter(HomeRecommendationPolicy::known).distinct()
        fun language(value: String) = when (val normalized = CatalogNames.normalize(value)) {
            "panjabi" -> "punjabi"
            "eng" -> "english"
            "hin" -> "hindi"
            else -> normalized
        }
        val allowedLanguages = languages.map(::language).toSet()
        val languageSeeds = ConcurrentHashMap.newKeySet<String>()
        val languageResults = ConcurrentHashMap<String, List<LastFm.Track>>()
        val eraSeeds = ConcurrentHashMap.newKeySet<String>()
        val preferredArtists = (history + favorites + listOfNotNull(current)).filter {
            language(it.language) in allowedLanguages
        }.flatMap(HomeRecommendationPolicy::rawArtists).map(CatalogNames::normalize).toSet()
        fun allowed(seed: LastFm.Track, track: MusicTrack): Boolean {
            if (allowedLanguages.isEmpty()) return true
            val actual = language(track.language)
            if (HomeRecommendationPolicy.known(actual)) return actual in allowedLanguages
            // Unknown is not a guessed language. Require contextual tag/artist evidence.
            return seed.key in languageSeeds || CatalogNames.normalize(seed.artist) in preferredArtists ||
                languageResults.values.any { tracks -> tracks.any {
                    CatalogNames.normalize(it.artist) == CatalogNames.normalize(seed.artist)
                } }
        }
        val personalSeeds = (favorites + history).distinctBy(HomeRecommendationPolicy::metadataKey)
            .filter { HomeRecommendationPolicy.known(it.artist) && it.id != current?.id }
            .sortedByDescending { taste.artistAffinities[HomeRecommendationPolicy.normalize(CustomRecommendationQueryWrapper.extractPrimaryArtist(it))] ?: 0f }
            .take(2).ifEmpty { listOfNotNull(current) }
        var chartTime = 0L
        val completed = Array<List<LastFm.Track>>(6) { emptyList() }
        suspend fun group(index: Int, block: suspend () -> List<LastFm.Track>) = block().also { completed[index] = it }
        val explicit = force || currentCoroutineContext()[DiscoveryRequestCoordinator.HomeRefresh]?.requested?.value == true
        val metadataReady = withTimeoutOrNull(if (explicit) 5_000L else 8_000L) { coroutineScope {
            // These cached endpoint pools are shared across Quick Picks, era and
            // contextual validation; never issue one tag request per card or song.
            val languagePool = async {
                languages.map { tag -> async { safe { LastFm.tagTopTracks(tag, page) }.also { tracks ->
                    tracks.forEach { languageSeeds.add(it.key) }
                    languageResults[tag] = tracks
                } } }.awaitAll().let { interleave(it) }
            }
            val chart = async {
                val result = if (Shelf.CHART !in shelves && Shelf.QUICK !in shelves) emptyList() else try {
                    LastFm.countryTopTracks(country).also { chartTime = it.fetchedAt }.tracks
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure.compareAndSet(null, e); emptyList() }
                completed[2] = result
                result
            }
            val quick = async { group(0) {
                if (Shelf.QUICK !in shelves) emptyList() else {
                    val localChart = chart.await()
                    val picks = languagePool.await()
                    // Regional chart order supplies a country signal; language pools
                    // supply language evidence. No unrelated global/pop fallback.
                    (localChart.filter { it.key in languageSeeds } + picks).distinctBy { it.key }
                        .ifEmpty { if (languages.isEmpty()) localChart else emptyList() }
                }
            } }
            val era = async { group(1) { if (Shelf.ERA !in shelves) emptyList() else
                coroutineScope {
                    val decades = async { listOf("90s", "2000s").map { tag ->
                        async { safe { LastFm.tagTopTracks(tag, page) }.also { tracks ->
                            tracks.forEach { eraSeeds.add(it.key) }
                        } }
                    }.awaitAll().let { interleave(it) } }
                    interleave(listOf(decades.await(), languagePool.await())).distinctBy { it.key }
                } } }
            val personalized = async { group(3) { if (Shelf.PERSONALIZED !in shelves || taste.isColdStart) emptyList() else
                personalSeeds.map { seed -> async { safe { LastFm.similarTracks(
                    CustomRecommendationQueryWrapper.extractPrimaryArtist(seed), StreamResolver.extractCleanTitle(seed.title, seed.artist), seed.id)
                    // Reserve the first slice for Inspired by this Track when both cards share a seed.
                    .let { if (seedKey(seed) == seedKey(current)) it.drop((it.size / 2).coerceIn(1, 20)) else it } } } }
                    .awaitAll().let { interleave(it) } } }
            val similar = async { group(4) {
                if (Shelf.SIMILAR !in shelves || artist.isBlank()) emptyList() else {
                    val artists = try { LastFm.similarArtists(artist) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) {
                            failure.compareAndSet(null, e); LastFm.ArtistPage(emptyList(), 0L) }
                    artists.names.take(3).map { name -> async { safe { LastFm.artistTopTracks(name).map {
                        it.copy(expiresAt = minOf(it.expiresAt, artists.expiresAt)) } } } }
                        .awaitAll().let { interleave(it) }
                }
            } }
            val inspired = async { group(5) { if (Shelf.INSPIRED !in shelves || artist.isBlank() || current == null) emptyList() else safe {
                LastFm.similarTracks(artist, StreamResolver.extractCleanTitle(current.title, current.artist), current.id)
                    .let { it.take((it.size / 2).coerceIn(1, 20)) } } } }
            awaitAll(chart, quick, era, personalized, similar, inspired)
            true
        } }
        // Keep completed tag responses when a slower sibling reaches the deadline.
        // One slow language must not discard all the other valid recommendations.
        if (Shelf.QUICK in shelves && completed[0].isEmpty())
            completed[0] = interleave(languages.map { languageResults[it].orEmpty() })
        if (Shelf.ERA in shelves && completed[1].isEmpty())
            completed[1] = interleave(languages.map { languageResults[it].orEmpty() })
        val groups = completed.mapIndexed { index, tracks ->
            tracks.takeIf { Shelf.entries[index] in shelves }.orEmpty()
        }
        val timestamps = Shelf.entries.mapIndexedNotNull { index, shelf ->
            val ttl = if (shelf in setOf(Shelf.QUICK, Shelf.ERA, Shelf.CHART)) LastFm.CHART_TTL else LastFm.CONTEXT_TTL
            groups[index].map { it.expiresAt }.filter { it > 0L }.minOrNull()?.let { shelf to it - ttl }
        }.toMap()
        if (groups.all { it.isEmpty() }) {
            failure.get()?.let { throw it }
            if (metadataReady == null) throw IOException("Last.fm recommendations timed out. Please retry.")
        }
        val requests = interleave(groups.map { it.take(24) }).distinctBy { it.key }.take(48)
        val knownIndex = (known + history + favorites).distinctBy { it.id }.groupBy { title(it.title, it.artist) }
        val resolved = ConcurrentHashMap<String, MusicTrack>()
        // Resolve every local hit before waiting for any network lookup.
        for (seed in requests) {
            knownIndex[title(seed.title, seed.artist)].orEmpty().firstOrNull {
                matches(seed, it) && allowed(seed, it) && OriginalMusicFilter.isHomeRecommendation(it)
            }?.let { resolved[seed.key] = it }
        }
        val missing = requests.filterNot { resolved.containsKey(it.key) }
        val cursor = AtomicInteger(0)
        // Keep completed matches when an API is slow. Both workers and the whole pass are bounded.
        withTimeoutOrNull(if (explicit) 6_000L else 10_000L) { coroutineScope {
            repeat(2) { launch {
                while (true) {
                    val seed = missing.getOrNull(cursor.getAndIncrement()) ?: break
                    val track = try {
                        val candidates = repository.lastFmCatalogCandidates("${seed.title} ${seed.artist}", force, seed.key) {
                            matches(seed, it) && allowed(seed, it) && OriginalMusicFilter.isHomeRecommendation(it)
                        }
                        withContext(repository.homeDispatcher) { candidates.firstOrNull { matches(seed, it) && allowed(seed, it) } }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (track != null && OriginalMusicFilter.isHomeRecommendation(track)) resolved[seed.key] = track
                }
            } }
        } }
        fun mapped(index: Int) = groups[index].mapNotNull { seed -> resolved[seed.key]?.takeIf { allowed(seed, it) } }.distinctBy { it.id }
        val era = groups[1].mapNotNull { seed -> resolved[seed.key]?.takeIf { track ->
            allowed(seed, track) && (track.year.toIntOrNull()?.let { it in 1990..2009 } ?: (seed.key in eraSeeds))
        } }.distinctBy { it.id }
        Batch(mapped(0), era,
            mapped(2).takeIf { Shelf.CHART in shelves }, mapped(3), mapped(4), mapped(5), chartTime,
            seedKey(current), artist, current?.title.orEmpty(), shelves, timestamps)
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> = buildList {
        repeat(lists.maxOfOrNull { it.size } ?: 0) { index -> lists.forEach { it.getOrNull(index)?.let(::add) } }
    }
}
