package com.example.data.repository

import com.example.performance.AppDispatchers
import android.content.Context
import com.example.data.cache.RecommendationCache
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeExploreParser
import com.example.recommendation.DiscoveryRequestCoordinator

import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.AudioRoutingService
import com.example.data.remote.YouTubeMusicApiService
import com.example.playback.AudioQuality
import com.example.data.remote.catalogGet
import com.example.data.remote.catalogRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Publish completed categories in their existing display order. The callback
 * consumes prepared data; one slow/failing category cannot withhold the others. */
internal suspend fun loadSearchCategories(load: suspend (String) -> CatalogPage,
    onPartial: (suspend (CatalogPage) -> Unit)?): CatalogPage = supervisorScope {
    val kinds = listOf("Tracks", "Albums", "Artists")
    val updates = Channel<Pair<Int, CatalogPage?>>(kinds.size)
    val rows = arrayOfNulls<CatalogPage>(kinds.size)
    var failed = false
    fun snapshot() = CatalogPage(tracks = rows[0]?.tracks.orEmpty(), albums = rows[1]?.albums.orEmpty(),
        artists = rows[2]?.artists.orEmpty(), warning = rows.mapNotNull { it?.warning }.firstOrNull()
            ?: "Some results couldn't load. Tap to retry.".takeIf { failed })
    val workers = kinds.mapIndexed { index, kind -> launch {
        val result = try { load(kind) } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null // A category's own timeout must settle, not strand its siblings.
        }
            catch (_: Exception) { null }
        updates.send(index to result)
    } }
    try {
        repeat(kinds.size) {
            val (index, page) = updates.receive()
            rows[index] = page
            if (page == null) failed = true
            else onPartial?.invoke(snapshot())
        }
        if (rows.all { it == null }) throw IOException("Search unavailable")
        snapshot()
    } finally { workers.forEach { it.cancel() }; updates.cancel() }
}

/** Typed browsing through the app's existing catalog provider and stream URL decoder. */
class CatalogRepository(context: Context? = null,
    private val browseLoader: suspend (String, String?, String?, String, String) -> String =
        { id, params, continuation, country, language ->
            YouTubeAudioStreamService.discoveryResponse("browse", id, country, params, continuation, language)
        }) {
    private val application = context?.applicationContext
    private val metadataCache by lazy { application?.let(RecommendationCache::get) }
    // Construct TLS/network resources on the first IO request, not a UI-owned browser constructor.
    private val client by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build() }
    private val cache = object : android.util.LruCache<String, Pair<Long, String>>(80) {}
    private val locks = Array(8) { Mutex() }
    companion object { private val browseLocks = Array(8) { Mutex() } }
    private fun locale(country: String, language: String): Pair<String, String> =
        (country.uppercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US") to
            (language.replace('_', '-').takeIf { it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) } ?: "en")
    private fun browseCacheKey(kind: String, country: String, language: String, id: String,
        params: String?, continuation: String?): String {
        val descriptor = JSONArray(listOf(kind, country, language, id, params.orEmpty(), continuation.orEmpty())).toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(descriptor.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        return "explore-catalog-v1:$digest"
    }
    private suspend fun <T> cachedBrowse(key: String, ttl: Long, forceRefresh: Boolean,
        decode: (String) -> T, encode: (T) -> String, checkpoint: Boolean = false,
        cacheable: (T) -> Boolean = { true }, load: suspend () -> T): T = withContext(AppDispatchers.Io) {
        browseLocks[(key.hashCode() and Int.MAX_VALUE) % browseLocks.size].withLock {
            var cached = synchronized(cache) { cache.get(key) }
            if (cached == null) metadataCache?.get(key)?.let { entry ->
                cached = entry.fetchedAt to entry.body
                synchronized(cache) { cache.put(key, cached!!) }
            }
            val now = System.currentTimeMillis()
            suspend fun decoded(): T? = cached?.takeIf { now >= it.first && now - it.first < TimeUnit.DAYS.toMillis(7) }?.let {
                try { withContext(AppDispatchers.CpuBounded) { decode(it.second) } }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            }
            if (!forceRefresh && cached?.let { now >= it.first && now - it.first < ttl } == true)
                decoded()?.let { return@withLock it }
            try {
                val result = load()
                currentCoroutineContext().ensureActive()
                val body = withContext(AppDispatchers.CpuBounded) { encode(result) }
                // Public catalogue metadata only, bounded independently of audio buffers.
                if (body.length <= 512 * 1024 && cacheable(result)) {
                    synchronized(cache) { cache.put(key, System.currentTimeMillis() to body) }
                    metadataCache?.putBody(key, body, ttl, checkpoint)
                }
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { decoded() ?: throw failure }
        }
    }
    suspend fun exploreCategories(countryCode: String = "US", languageCode: String = "en",
        forceRefresh: Boolean = false): List<ExploreCategory> {
        val (country, language) = locale(countryCode, languageCode)
        val key = browseCacheKey("categories", country, language, "FEmusic_moods_and_genres", null, null)
        return cachedBrowse(key, TimeUnit.DAYS.toMillis(1), forceRefresh, YouTubeExploreParser::decodeCategories,
            YouTubeExploreParser::encodeCategories, checkpoint = true) {
            val body = DiscoveryRequestCoordinator.network(essential = true) {
                browseLoader("FEmusic_moods_and_genres", null, null, country, language)
            }
            withContext(AppDispatchers.CpuBounded) {
                val job = currentCoroutineContext()
                YouTubeExploreParser.categories(JSONObject(body)) { job.ensureActive() }
            }
        }
    }
    suspend fun exploreCategory(category: ExploreCategory, countryCode: String = "US", languageCode: String = "en",
        forceRefresh: Boolean = false): CatalogPage {
        if (category.provider != CatalogProvider.YOUTUBE_MUSIC || category.browseId.isBlank())
            throw IOException("Music category is unavailable")
        return youtubeBrowse(category.browseId, category.params, countryCode, languageCode, null, false, forceRefresh)
    }
    private suspend fun youtubeBrowse(id: String, params: String?, countryCode: String, languageCode: String,
        continuation: CatalogBrowseContinuation?, collectionOnly: Boolean, forceRefresh: Boolean = false): CatalogPage {
        val (country, language) = locale(countryCode, languageCode)
        if (continuation != null && (continuation.browseId != id || continuation.params != params ||
                continuation.countryCode != country || continuation.languageCode != language))
            throw IOException("Music continuation does not belong to this collection")
        val key = browseCacheKey(if (collectionOnly) "collection" else "category", country, language, id, params, continuation?.token)
        val page = cachedBrowse(key, TimeUnit.MINUTES.toMillis(30), forceRefresh,
            { YouTubeExploreParser.decodePage(it, country, language) }, YouTubeExploreParser::encodePage,
            cacheable = { it.tracks.isNotEmpty() || it.albums.isNotEmpty() || it.playlists.isNotEmpty() || it.browseContinuation != null }) {
            val body = DiscoveryRequestCoordinator.network(essential = true) {
                browseLoader(id, params, continuation?.token, country, language)
            }
            withContext(AppDispatchers.CpuBounded) {
                val job = currentCoroutineContext()
                YouTubeExploreParser.page(JSONObject(body), country, language, collectionOnly) { job.ensureActive() }
            }
        }
        val seen = continuation?.seenTokens.orEmpty() + listOfNotNull(continuation?.token)
        val next = page.browseContinuation?.token?.takeUnless { it in seen }
        val marker = (continuation?.page ?: 1) + 1
        return page.copy(nextPage = marker.takeIf { next != null }, browseContinuation = next?.let {
            CatalogBrowseContinuation(id, params, it, country, language, seen, marker)
        })
    }
    private suspend fun request(call: String, vararg params: Pair<String, String>, artistBrowse: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val url = "https://www.jiosaavn.com/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("__call", call).addQueryParameter("_format", "json").addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4").addQueryParameter("ctx", "web6dot0")
            .apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
                if (artistBrowse) { addQueryParameter("cc", "in"); addQueryParameter("includeMetaTags", "1") }
            }.build().toString()
        locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val cached = synchronized(cache) { cache.get(url) }
            if (cached != null && System.currentTimeMillis() - cached.first < 300_000)
                return@withLock withContext(AppDispatchers.CpuBounded) { JSONObject(cached.second) }
            try {
                val body = if (artistBrowse) client.catalogRequest(Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "application/json").build()) else client.catalogGet(url)
                val json = withContext(AppDispatchers.CpuBounded) { JSONObject(body) }
                if (json.opt("error")?.let { it != JSONObject.NULL && it != false && it != "" && it != 0 } == true) throw IOException("Catalog unavailable")
                synchronized(cache) { cache.put(url, System.currentTimeMillis() to body) }
                json
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { cached?.let { withContext(AppDispatchers.CpuBounded) { JSONObject(it.second) } } ?: throw e }
        }
    }
    private fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
    private fun text(o: JSONObject, key: String) = o.optString(key).takeUnless { it == "null" }.orEmpty().let(CatalogNames::clean)
    private fun image(o: JSONObject) = text(o, "image").replace("150x150", "500x500").replace("50x50", "500x500").replaceFirst("http://", "https://")
    private fun artist(o: JSONObject): CatalogArtist? {
        val id = text(o, "id"); val name = text(o, "name").ifBlank { text(o, "title") }
        if (id.isBlank() || name.isBlank() || text(o, "type").let { it.isNotBlank() && it != "artist" }) return null
        if (CatalogNames.normalize(name) in setOf("unknown", "unknown artist", "various artists")) return null
        return CatalogArtist(id, name, text(o, "musicbrainz_id"), text(o, "aka").split(';', '|').filter(String::isNotBlank), image(o))
    }
    private fun credits(o: JSONObject, album: Boolean = false): List<CatalogArtist> {
        val map = o.optJSONObject("more_info")?.optJSONObject("artistMap") ?: return emptyList()
        val direct = (map.optJSONArray("primary_artists").objects() + map.optJSONArray("featured_artists").objects())
        val performers = if (album) direct else direct + map.optJSONArray("artists").objects().filter {
            text(it, "role").lowercase() in setOf("singer", "primary_artists", "featured_artists", "vocalist")
        }
        return performers.mapNotNull(::artist).distinctBy { it.id }
    }
    private val unofficial = Regex("(?i)\\b(?:unofficial|bootleg|karaoke|tribute to|fan cover|cover version|slowed|reverb|jukebox|user playlist)\\b")
    private fun album(o: JSONObject): CatalogAlbum? {
        val id = text(o, "id"); val title = text(o, "title"); val directArtists = credits(o, album = true)
        val resolvedArtists = if (directArtists.isNotEmpty()) directArtists else {
            val fallbackName = o.optJSONObject("more_info")?.optString("primary_artists")?.takeIf { it.isNotBlank() }
                ?: o.optJSONObject("more_info")?.optString("music")?.takeIf { it.isNotBlank() }
                ?: text(o, "subtitle").takeIf { it.isNotBlank() }
                ?: "Various Artists"
            listOf(CatalogArtist(id = "", name = fallbackName))
        }
        val rawYear = text(o, "year").ifBlank { o.optJSONObject("more_info")?.let { text(it, "year") }.orEmpty() }
        val year = if (rawYear.toIntOrNull() != null) rawYear else "2024"
        val img = image(o).ifBlank { o.optJSONObject("more_info")?.optString("image")?.replace("150x150", "500x500")?.replaceFirst("http://", "https://").orEmpty() }
        val type = text(o, "type")
        if ((type.isNotBlank() && type != "album") || id.isBlank() || title.isBlank() || img.isBlank() || unofficial.containsMatchIn(title)) return null
        return CatalogAlbum(id, title, resolvedArtists, img, year)
    }
    private fun playlist(o: JSONObject): CatalogPlaylist? {
        if (text(o, "type") != "playlist" || text(o, "id").isBlank() || text(o, "title").isBlank()) return null
        return CatalogPlaylist(text(o, "id"), text(o, "title"), image(o))
    }
    private fun track(o: JSONObject): MusicTrack? {
        val parsed = OnlineMusicApiService.parseSong(o, allowMissingStream = true) ?: return null
        val artists = credits(o)
        // Some genuine search rows have primary_artists/singers without artistMap.
        // Use the shared parser's explicit performer credits instead of dropping them.
        if (artists.isEmpty()) return parsed.takeIf {
            CatalogNames.normalize(it.artist) !in setOf("", "unknown", "unknown artist", "various artists")
        }
        return parsed.copy(
            artist = artists.joinToString(", ") { it.name }, singers = artists.joinToString(", ") { it.name })
    }
    private val recordingIsrc = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
    private fun recordingCode(track: MusicTrack) = track.isrc.uppercase().replace("-", "")
        .takeIf { it.matches(recordingIsrc) }
    private fun recordingName(track: MusicTrack) = CatalogNames.normalize(track.title) + "|" +
        track.artist.split(',').map(CatalogNames::normalize).sorted().joinToString(",")

    fun uniqueTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val ids = mutableSetOf<String>()
        val recordings = mutableSetOf<String>()
        val metadata = mutableSetOf<String>()
        val names = tracks.associateWith(::recordingName)
        val codes = tracks.associateWith(::recordingCode)
        return tracks.filter { track ->
            val isrc = codes[track]
            // Keep version text and non-Latin vowel marks; neither is disposable punctuation.
            val key = names.getValue(track)
            val fresh = track.id !in ids && key !in metadata && (isrc == null || isrc !in recordings)
            ids.add(track.id); metadata.add(key); isrc?.let(recordings::add)
            fresh
        }
    }
    suspend fun search(query: String, type: String, page: Int = 1,
        quality: AudioQuality = AudioQuality.ULTRA_HD_320, previousPage: CatalogPage? = null,
        onPartial: (suspend (CatalogPage) -> Unit)? = null
    ): CatalogPage = withContext(AppDispatchers.CpuBounded) {
        if (query.isBlank()) return@withContext CatalogPage()
        if (type == "All") return@withContext loadSearchCategories(
            load = { kind -> search(query, kind, 1, quality) }, onPartial = onPartial)
        if (type == "Tracks") return@withContext trackSearch(query.trim(), page, previousPage)
        val call = when (type) { "Albums" -> "search.getAlbumResults"; "Artists" -> "search.getArtistResults"; "Playlists" -> "search.getPlaylistResults"; else -> return@withContext CatalogPage() }
        val root = request(call, "q" to query, "p" to page.toString(), "n" to "10")
        val raw = root.optJSONArray("results").objects()
        if (!root.has("results")) throw IOException("Invalid catalog response")
        val next = if (raw.isNotEmpty() && (if (root.has("total")) page * 10 < root.optInt("total") else raw.size >= 10)) page + 1 else null
        when (type) {
            "Albums" -> CatalogPage(albums = raw.mapNotNull(::album).distinctBy { it.id }.filter { CatalogNames.score(query, it.title, it.artists.map { a -> a.name }) > 0 }
                .sortedByDescending { CatalogNames.score(query, it.title, it.artists.map { a -> a.name }) }, nextPage = next)
            "Artists" -> CatalogPage(artists = raw.mapNotNull(::artist).distinctBy { it.id }.filter { CatalogNames.score(query, it.name, it.aliases) > 0 }
                .sortedByDescending { CatalogNames.score(query, it.name, it.aliases) }, nextPage = next)
            else -> CatalogPage(playlists = raw.mapNotNull(::playlist).distinctBy { it.id }.filter { CatalogNames.score(query, it.title) > 0 }
                .sortedByDescending { CatalogNames.score(query, it.title) }, nextPage = next)
        }
    }

    private suspend fun trackSearch(query: String, page: Int, previousPage: CatalogPage?): CatalogPage {
        val cursor = previousPage?.trackSearchContinuation?.takeIf { it.query == query }
            ?: TrackSearchContinuation(query, standardPage = page)
        val fetch = cursor.pendingTracks.size < 10
        val result = if (fetch) AudioRoutingService.searchPage(query, cursor.standardPage,
            cursor.youtubeContinuation, cursor.youtubeHasMore) else null
        if (result?.standardFailed == true && result.youtubeFailed && cursor.pendingTracks.isEmpty())
            throw IOException("Search unavailable")
        val ranked = AudioRoutingService.rankTracks(query, cursor.pendingTracks + result?.tracks.orEmpty())
        val previousTracks = previousPage?.tracks.orEmpty()
        val previousIds = previousTracks.mapTo(mutableSetOf()) { it.id }
        val tracks = AudioRoutingService.mergeTracks(previousTracks + ranked).filter { it.id !in previousIds }
        val pending = tracks.drop(10)
        // A failed provider retains its cursor, rather than silently marking its catalog exhausted.
        val standardNext = if (!fetch || result?.standardFailed == true) cursor.standardPage else result?.standardNext
        val youtubeNext = if (!fetch || result?.youtubeFailed == true) cursor.youtubeContinuation else result?.youtubeNext
        val youtubeHasMore = if (!fetch || result?.youtubeFailed == true) cursor.youtubeHasMore else youtubeNext != null
        val hasMore = pending.isNotEmpty() || standardNext != null || youtubeHasMore
        return CatalogPage(tracks = tracks.take(10), nextPage = if (hasMore) page + 1 else null,
            warning = if (result?.standardFailed == true || result?.youtubeFailed == true)
                "Some catalog results couldn't load. Tap to retry." else previousPage?.warning?.takeIf { !fetch },
            trackSearchContinuation = TrackSearchContinuation(query, standardNext, pending, youtubeNext, youtubeHasMore))
    }
    private fun sameArtistName(requested: String, credited: String): Boolean {
        val first = CatalogNames.normalize(requested)
        val second = CatalogNames.normalize(credited)
        // Catalog/YouTube credits can spell the same name as EdSheeran or Ed Sheeran.
        // Only punctuation/spacing differences qualify; partial names never identify artists.
        return first.isNotBlank() && first.replace(" ", "") == second.replace(" ", "")
    }
    private fun artistQueries(name: String): List<String> {
        val clean = CatalogNames.clean(name).trim()
        return listOf(clean, clean.replace(Regex("(?<=\\p{Ll})(?=\\p{Lu})"), " "))
            .filter(String::isNotBlank).distinct()
    }
    suspend fun resolveArtist(selected: CatalogArtist): CatalogArtist = withContext(AppDispatchers.CpuBounded) {
        if (selected.id.isNotBlank()) return@withContext selected
        // Failure to find a JioSaavn ID does not make the artist unavailable elsewhere.
        withTimeoutOrNull(6_000L) { resolveJioArtist(selected) } ?: selected
    }
    private suspend fun resolveJioArtist(selected: CatalogArtist): CatalogArtist? = withContext(AppDispatchers.CpuBounded) {
        val queries = (artistQueries(selected.name) + selected.aliases.flatMap(::artistQueries)).distinct().take(3)
        val names = listOf(selected.name) + selected.aliases
        for (query in queries) {
            val root = try { request("search.getArtistResults", "q" to query, "p" to "1", "n" to "10", artistBrowse = true) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val matches = root?.optJSONArray("results").objects().mapNotNull(::artist).filter { candidate ->
                (listOf(candidate.name) + candidate.aliases).any { credit -> names.any { sameArtistName(it, credit) } } &&
                    (selected.musicBrainzId.isBlank() || candidate.musicBrainzId.isBlank() ||
                        selected.musicBrainzId.equals(candidate.musicBrainzId, ignoreCase = true))
            }.distinctBy { it.id }
            matches.singleOrNull()?.let { return@withContext it.copy(
                musicBrainzId = it.musicBrainzId.ifBlank { selected.musicBrainzId },
                aliases = (it.aliases + selected.aliases + selected.name).filter { name -> name != it.name }.distinct()) }
        }
        // Artist search sometimes omits an otherwise available performer. Resolve only a
        // unique, exact performer credit from real songs, not their title or popularity.
        for (query in queries) {
            val root = try { request("search.getResults", "q" to query, "p" to "1", "n" to "10", artistBrowse = true) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val matches = root?.optJSONArray("results").objects().flatMap { credits(it) }
                .filter { candidate -> (listOf(candidate.name) + candidate.aliases)
                    .any { credit -> names.any { sameArtistName(it, credit) } } &&
                        (selected.musicBrainzId.isBlank() || candidate.musicBrainzId.isBlank() ||
                            selected.musicBrainzId.equals(candidate.musicBrainzId, true)) }
                .distinctBy { it.id }
            matches.singleOrNull()?.let { return@withContext it.copy(
                musicBrainzId = it.musicBrainzId.ifBlank { selected.musicBrainzId },
                aliases = (it.aliases + selected.aliases + selected.name).filter { name -> name != it.name }.distinct()) }
        }
        null
    }
    private val artistPageSizes = object : android.util.LruCache<String, Int>(40) {}
    private val artistCoverVersion = Regex("(?i)\\bcover\\b")
    private fun verifiedArtistTracks(entries: List<JSONObject>, selected: CatalogArtist): List<MusicTrack> {
        val names = listOf(selected.name) + selected.aliases
        val performerCredits = entries.associateWith { credits(it) }
        val ids = if (selected.id.isNotBlank()) setOf(selected.id) else {
            // Name-only Home routes can still find songs when the artist-search endpoint
            // fails. Resolve a unique credited performer, never a title/keyword match.
            performerCredits.values.flatten().filter { candidate ->
                (listOf(candidate.name) + candidate.aliases).any { name -> names.any { sameArtistName(it, name) } } &&
                    (selected.musicBrainzId.isBlank() || candidate.musicBrainzId.isBlank() ||
                        selected.musicBrainzId.equals(candidate.musicBrainzId, true))
            }.map { it.id }.distinct().singleOrNull()?.let(::setOf).orEmpty()
        }
        return uniqueTracks(entries.filter { item -> performerCredits.getValue(item).any { it.id in ids } }.mapNotNull(::track))
            .filterNot { unofficial.containsMatchIn(it.title) || artistCoverVersion.containsMatchIn(it.title) }
    }
    private suspend fun artistSearchPage(selected: CatalogArtist, page: Int): CatalogPage {
        val root = withTimeoutOrNull(8_000L) {
            request("search.getResults", "q" to selected.name, "p" to page.toString(), "n" to "10", artistBrowse = true)
        } ?: throw IOException("Artist song search timed out")
        val entries = root.optJSONArray("results") ?: throw IOException("Missing artist song search results")
        val raw = entries.objects()
        val total = root.optInt("total", -1)
        val more = raw.isNotEmpty() && (if (total >= 0) page.toLong() * 10 < total else raw.size >= 10)
        // Negative cursors distinguish this one-based search from the zero-based artist
        // endpoint, so a retry/fallback cannot silently skip or repeat provider pages.
        return CatalogPage(tracks = verifiedArtistTracks(raw, selected), nextPage = if (more) -(page + 1) else null)
    }
    private suspend fun artistSearchPages(selected: CatalogArtist, start: Int, existing: List<MusicTrack>): CatalogPage {
        val collected = mutableListOf<MusicTrack>()
        var number = start
        var next: Int? = null
        repeat(3) {
            val result = try { artistSearchPage(selected, number) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val fresh = uniqueTracks(existing + collected).drop(existing.size)
                if (fresh.isEmpty()) throw e
                return CatalogPage(tracks = fresh, nextPage = -number,
                    warning = "Some songs couldn't load. Tap See More to retry.")
            }
            collected += result.tracks
            next = result.nextPage
            val fresh = uniqueTracks(existing + collected).drop(existing.size)
            if (fresh.size >= 10 || next == null) return result.copy(tracks = fresh)
            number = -requireNotNull(next)
        }
        return CatalogPage(tracks = uniqueTracks(existing + collected).drop(existing.size), nextPage = next)
    }
    private suspend fun artistProviderPage(selected: CatalogArtist, page: Int, details: Boolean = false): CatalogPage {
        val response = if (details) request("artist.getArtistPageDetails", "artistId" to selected.id,
            "page" to "0", "n_song" to "10", "n_album" to "0", "category" to "popularity", "sort_order" to "desc", artistBrowse = true)
        else request("artist.getArtistMoreSong", "artistId" to selected.id, "page" to page.toString(),
            "category" to "popularity", "sort_order" to "desc", artistBrowse = true)
        val root = response.optJSONObject("data") ?: response
        val responseArtist = text(root, "artistId").ifBlank { text(root, "id") }
        // Some API responses do not echo artistId; every song must still credit it.
        if (responseArtist.isNotBlank() && responseArtist != selected.id) throw IOException("Artist identity mismatch")
        val responseName = text(root, "name")
        if (responseName.isNotBlank() && (listOf(selected.name) + selected.aliases).none { sameArtistName(it, responseName) })
            throw IOException("Artist name mismatch")
        val songs = root.optJSONObject("topSongs")
        val entries = songs?.optJSONArray("songs") ?: root.optJSONArray("topSongs")
            ?: root.optJSONArray("songs") ?: root.optJSONArray("results") ?: root.optJSONArray("list")
            ?: throw IOException("Missing artist songs")
        val raw = entries.objects()
        val tracks = verifiedArtistTracks(raw, selected)
        if (page == 0 && tracks.isEmpty()) throw IOException("No verified songs in artist response")
        val total = songs?.optInt("total", -1)?.takeIf { it >= 0 } ?: root.optInt("total", -1)
        val size = synchronized(artistPageSizes) {
            val width = maxOf(artistPageSizes.get(selected.id) ?: 10, raw.size)
            artistPageSizes.put(selected.id, width)
            width
        }
        val start = songs?.optLong("start", -1)?.takeIf { it >= 0 } ?: root.optLong("start", -1)
        val consumed = (start.takeIf { it >= 0 } ?: page.toLong() * size) + raw.size
        val more = raw.isNotEmpty() && (if (total >= 0) consumed < total else raw.size >= size)
        // The overview's ten-song window is independent of the MoreSong endpoint's
        // page size. Continue through song search rather than skipping a provider page.
        return CatalogPage(tracks = tracks, nextPage = if (more) { if (details) -1 else page + 1 } else null)
    }
    private suspend fun youtubeArtistPage(selected: CatalogArtist, query: String, token: String?, page: Int,
        existing: List<MusicTrack>): CatalogPage {
        val names = (listOf(selected.name) + selected.aliases).filter(String::isNotBlank)
        val collected = mutableListOf<MusicTrack>()
        var continuation = token
        val visited = mutableSetOf<String>().apply { token?.let(::add) }
        // Skip a small number of empty/overlapping provider pages, rather than making
        // See More appear stuck. Further pages remain truly incremental.
        repeat(3) {
            val result = try { withTimeoutOrNull(12_000L) { YouTubeMusicApiService.searchSongsPage(query, continuation) }
                ?: throw IOException("Artist song fallback timed out") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val fresh = uniqueTracks(existing + collected).drop(existing.size)
                if (fresh.isEmpty()) throw e
                return CatalogPage(tracks = fresh, nextPage = page.coerceAtLeast(0) + 1,
                    artistContinuation = continuation?.let { ArtistCatalogContinuation(query, it) },
                    warning = "Some songs couldn't load. Tap See More to retry.")
            }
            collected += result.tracks.filter { track ->
                result.artistCredits[track.id].orEmpty().any { credit -> names.any { sameArtistName(it, credit) } } &&
                    !unofficial.containsMatchIn(track.title) && !artistCoverVersion.containsMatchIn(track.title)
            }
            val fresh = uniqueTracks(existing + collected).drop(existing.size)
            continuation = result.continuation?.takeIf(visited::add)
            if (fresh.size >= 10 || continuation == null || existing.isEmpty() && fresh.isEmpty()) return CatalogPage(tracks = fresh,
                nextPage = if (continuation != null) page.coerceAtLeast(0) + 1 else null,
                artistContinuation = continuation?.let { ArtistCatalogContinuation(query, it) })
        }
        return CatalogPage(tracks = uniqueTracks(existing + collected).drop(existing.size),
            nextPage = page.coerceAtLeast(0) + 1,
            artistContinuation = continuation?.let { ArtistCatalogContinuation(query, it) })
    }
    suspend fun artistProfile(artist: CatalogArtist): CatalogArtistProfile? = YouTubeMusicApiService.artistProfile(artist, application)

    suspend fun artistSongs(selected: CatalogArtist, page: Int,
        continuation: ArtistCatalogContinuation? = null, existing: List<MusicTrack> = emptyList(),
        browseContinuation: CatalogBrowseContinuation? = null
    ): CatalogPage = withContext(AppDispatchers.CpuBounded) {
        if (selected.name.isBlank()) return@withContext CatalogPage()
        if (selected.id.startsWith("UC") && continuation == null) {
            val profile = try { artistProfile(selected) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (profile != null) {
                val top = profile.moreSongsBrowseId?.let { id ->
                    try { youtubeBrowse(id, null, "US", "en", browseContinuation, true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                if (top != null && top.tracks.isNotEmpty()) return@withContext top.copy(
                    resolvedArtist = profile.artist, artistProfile = profile)
                if (existing.isEmpty() && profile.songs.isNotEmpty()) return@withContext CatalogPage(
                    tracks = profile.songs, resolvedArtist = profile.artist, artistProfile = profile)
            }
        }
        var failure: Exception? = null
        var available: CatalogPage? = null
        val attemptedQueries = mutableSetOf<String>()
        suspend fun attempt(load: suspend () -> CatalogPage): CatalogPage? = try {
            load().also { if (available == null || it.tracks.isNotEmpty() || it.nextPage != null ||
                available?.nextPage == null) available = it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e; null }
        if (continuation != null) {
            attempt { youtubeArtistPage(selected, continuation.query, continuation.token, page, existing) }
                ?.let { return@withContext it.copy(resolvedArtist = selected) }
        }
        suspend fun catalogSongs(artist: CatalogArtist): CatalogPage? {
            if (artist.id.isNotBlank() && page >= 0) {
                val primary = attempt {
                    withTimeoutOrNull(8_000L) { artistProviderPage(artist, if (continuation != null) 0 else page) }
                        ?: throw IOException("Artist provider timed out")
                }
                if (primary != null && uniqueTracks(existing + primary.tracks).size > existing.size)
                    return primary.copy(resolvedArtist = artist)
                if (primary == null && existing.isEmpty()) {
                    val overview = attempt {
                        withTimeoutOrNull(8_000L) { artistProviderPage(artist, 0, details = true) }
                            ?: throw IOException("Artist overview timed out")
                    }
                    if (overview != null && overview.tracks.isNotEmpty()) return overview.copy(resolvedArtist = artist)
                }
            }
            // Song credits can identify a performer even if artist-ID lookup failed.
            val fallback = attempt { artistSearchPages(artist, if (page < 0 && continuation == null) -page else 1, existing) }
            if (fallback != null && fallback.tracks.isNotEmpty()) return fallback.copy(resolvedArtist = artist)
            return null
        }
        suspend fun youtubeSongs(artist: CatalogArtist): CatalogPage? {
            val queries = (artistQueries(artist.name) + artist.aliases.flatMap(::artistQueries)).distinct().take(3)
            for (query in queries) {
                if (!attemptedQueries.add(query)) continue
                val fallback = attempt { youtubeArtistPage(artist, query, null, page, existing) }
                if (fallback != null && fallback.tracks.isNotEmpty()) return fallback.copy(resolvedArtist = artist)
            }
            return null
        }
        catalogSongs(selected)?.let { return@withContext it }
        youtubeSongs(selected)?.let { return@withContext it }
        // Recover stage-name/spelling variants from verified metadata, never by
        // accepting a similarly named performer or relaxing track-credit checks.
        val resolved = try { withTimeoutOrNull(6_000L) { resolveJioArtist(selected) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        val recovered = resolved ?: selected
        if (recovered != selected) {
            catalogSongs(recovered)?.let { return@withContext it }
            // An earlier empty search page can now pass credit verification using
            // a learned alias; the response cache prevents another network request.
            attemptedQueries.clear()
            youtubeSongs(recovered)?.let { return@withContext it }
        }
        // Genuine empty responses use the existing empty state. Only total provider
        // failure is retryable; one failing API must not hide another API's real songs.
        available ?: throw IOException("Artist catalogues are temporarily unavailable", failure)
    }
    suspend fun getGenreAlbums(genre: String, country: String = "", languages: List<String> = emptyList(), limit: Int = 12): List<CatalogAlbum> = withContext(AppDispatchers.CpuBounded) {
        val albums = mutableListOf<CatalogAlbum>()
        val queries = mutableListOf<String>()

        val primaryLang = languages.firstOrNull { it.isNotBlank() } ?: ""
        if (primaryLang.isNotBlank() && !genre.equals("All", true)) {
            queries.add("$primaryLang $genre")
        }
        if (country.isNotBlank() && !genre.equals("All", true)) {
            queries.add("$country $genre")
        }
        if (genre.equals("All", true)) {
            if (primaryLang.isNotBlank()) queries.add("$primaryLang Top Hits")
            queries.add("Top Hits")
        } else {
            queries.add("$genre")
            queries.add("$genre Hits")
        }

        for (q in queries.distinct().take(3)) {
            try {
                val root = request("search.getAlbumResults", "q" to q, "p" to "1", "n" to "10")
                val raw = root.optJSONArray("results").objects()
                val parsed = raw.mapNotNull(::album)
                albums.addAll(parsed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            if (albums.size >= limit) break
        }

        albums.distinctBy { it.id.ifBlank { it.title.lowercase() } }.take(limit)
    }

    suspend fun albumSongs(selected: CatalogAlbum, continuation: CatalogBrowseContinuation? = null): CatalogPage = withContext(AppDispatchers.CpuBounded) {
        if (selected.provider == CatalogProvider.YOUTUBE_MUSIC) {
            val page = youtubeBrowse(selected.id, selected.browseParams, selected.countryCode,
                selected.languageCode, continuation, collectionOnly = true)
            // Album rows often omit thumbnails. Keep this exact release's catalog
            // cover instead of the parser's low-resolution video fallback.
            return@withContext if (selected.image.isBlank()) page else page.copy(tracks = page.tracks.map {
                it.copy(coverUrl = selected.image)
            })
        }
        val root = try {
            request("content.getAlbumDetails", "albumid" to selected.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        val raw = root?.optJSONArray("list")?.objects().orEmpty()
        val members = raw.filter { item -> item.optJSONObject("more_info")?.let { text(it, "album_id") } == selected.id }.ifEmpty { raw }
        val ordered = if (members.all { (it.optJSONObject("more_info")?.optInt("track_number", 0) ?: 0) > 0 })
            members.sortedWith(compareBy<JSONObject> { it.optJSONObject("more_info")?.optInt("disc_number", 1) ?: 1 }.thenBy { it.optJSONObject("more_info")?.optInt("track_number") ?: 0 }) else members
        val parsedTracks = ordered.mapNotNull(::track).distinctBy { it.id }

        if (parsedTracks.isNotEmpty()) {
            return@withContext CatalogPage(tracks = parsedTracks, warning =
                if (root?.optJSONObject("more_info")?.optInt("song_count", raw.size)?.let { it > raw.size } == true) "The catalog returned only part of this release." else null)
        }

        // Fallback: fetch songs belonging to this album via YouTube search
        val artistName = selected.artists.firstOrNull()?.name ?: ""
        val searchQ = if (artistName.isNotBlank() && artistName != "Various Artists") {
            "${selected.title} $artistName"
        } else {
            selected.title
        }
        val fallbackTracks = try {
            YouTubeMusicApiService.searchSongs(searchQ, limit = 20)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        CatalogPage(tracks = fallbackTracks)
    }
    suspend fun playlistSongs(selected: CatalogPlaylist, page: Int, continuation: CatalogBrowseContinuation? = null): CatalogPage = withContext(AppDispatchers.CpuBounded) {
        if (selected.provider == CatalogProvider.YOUTUBE_MUSIC) return@withContext youtubeBrowse(selected.id,
            selected.browseParams, selected.countryCode, selected.languageCode, continuation, collectionOnly = true)
        val root = request("playlist.getDetails", "listid" to selected.id, "p" to page.toString(), "n" to "10")
        if (text(root, "id").ifBlank { text(root, "listid") } != selected.id) throw IOException("Playlist identity mismatch")
        val raw = (root.optJSONArray("list") ?: root.optJSONArray("songs")).objects()
        val count = root.optJSONObject("more_info")?.optInt("song_count", -1) ?: root.optInt("list_count", -1)
        CatalogPage(tracks = uniqueTracks(raw.mapNotNull(::track)), nextPage = if (raw.isNotEmpty() && (if (count >= 0) page * 10 < count else raw.size >= 10)) page + 1 else null)
    }
}
