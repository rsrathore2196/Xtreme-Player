package com.example.data.repository

import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.AudioRoutingService
import com.example.data.remote.YouTubeMusicApiService
import com.example.playback.AudioQuality
import com.example.data.remote.catalogGet
import com.example.data.remote.catalogRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Typed browsing through the app's existing catalog provider and stream URL decoder. */
class CatalogRepository {
    // Construct TLS/network resources on the first IO request, not a UI-owned browser constructor.
    private val client by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build() }
    private val cache = object : android.util.LruCache<String, Pair<Long, String>>(80) {}
    private val locks = Array(8) { Mutex() }
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
                return@withLock withContext(Dispatchers.Default) { JSONObject(cached.second) }
            try {
                val body = if (artistBrowse) client.catalogRequest(Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "application/json").build()) else client.catalogGet(url)
                val json = withContext(Dispatchers.Default) { JSONObject(body) }
                if (json.opt("error")?.let { it != JSONObject.NULL && it != false && it != "" && it != 0 } == true) throw IOException("Catalog unavailable")
                synchronized(cache) { cache.put(url, System.currentTimeMillis() to body) }
                json
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { cached?.let { withContext(Dispatchers.Default) { JSONObject(it.second) } } ?: throw e }
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
        val id = text(o, "id"); val title = text(o, "title"); val artists = credits(o, album = true)
        val year = text(o, "year").ifBlank { o.optJSONObject("more_info")?.let { text(it, "year") }.orEmpty() }
        if (text(o, "type") != "album" || id.isBlank() || title.isBlank() || artists.isEmpty() || image(o).isBlank() || year.toIntOrNull() == null || unofficial.containsMatchIn(title)) return null
        return CatalogAlbum(id, title, artists, image(o), year)
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
        quality: AudioQuality = AudioQuality.ULTRA_HD_320, previousPage: CatalogPage? = null
    ): CatalogPage = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext CatalogPage()
        if (type == "All") return@withContext supervisorScope {
            val tasks = listOf("Tracks", "Albums", "Artists").map { kind -> async {
                try { search(query, kind, 1, quality) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            } }
            val results = tasks.awaitAll()
            if (results.all { it == null }) throw IOException("Search unavailable")
            CatalogPage(tracks = results[0]?.tracks.orEmpty(), albums = results[1]?.albums.orEmpty(),
                artists = results[2]?.artists.orEmpty(), warning =
                    results.mapNotNull { it?.warning }.firstOrNull()
                        ?: if (results.any { it == null }) "Some results couldn't load. Tap to retry." else null)
        }
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
    suspend fun resolveArtist(selected: CatalogArtist): CatalogArtist = withContext(Dispatchers.Default) {
        if (selected.id.isNotBlank()) return@withContext selected
        // Failure to find a JioSaavn ID does not make the artist unavailable elsewhere.
        withTimeoutOrNull(6_000L) { resolveJioArtist(selected) } ?: selected
    }
    private suspend fun resolveJioArtist(selected: CatalogArtist): CatalogArtist? = withContext(Dispatchers.Default) {
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
    suspend fun artistSongs(selected: CatalogArtist, page: Int,
        continuation: ArtistCatalogContinuation? = null, existing: List<MusicTrack> = emptyList()
    ): CatalogPage = withContext(Dispatchers.Default) {
        if (selected.name.isBlank()) return@withContext CatalogPage()
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
        val enriched = try { withTimeoutOrNull(4_000L) { ArtistPhotoRepository.enrichIdentity(selected) } ?: selected }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { selected }
        val resolved = try { withTimeoutOrNull(6_000L) { resolveJioArtist(enriched) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        val recovered = resolved ?: enriched
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
    suspend fun albumSongs(selected: CatalogAlbum): CatalogPage = withContext(Dispatchers.Default) {
        val root = request("content.getAlbumDetails", "albumid" to selected.id)
        val verified = album(root) ?: throw IOException("Album metadata unavailable")
        if (verified.id != selected.id || !CatalogNames.same(verified.title, selected.title) || verified.artists.none { a -> selected.artists.any { it.id == a.id } }) throw IOException("Album identity mismatch")
        val raw = root.optJSONArray("list").objects()
        // Album details return the release's ordered track list (no discography or title search).
        val members = raw.filter { item -> item.optJSONObject("more_info")?.let { text(it, "album_id") } == selected.id }
        val ordered = if (members.all { (it.optJSONObject("more_info")?.optInt("track_number", 0) ?: 0) > 0 })
            members.sortedWith(compareBy<JSONObject> { it.optJSONObject("more_info")?.optInt("disc_number", 1) ?: 1 }.thenBy { it.optJSONObject("more_info")?.optInt("track_number") ?: 0 }) else members
        CatalogPage(tracks = ordered.mapNotNull(::track).distinctBy { it.id }, warning =
            if (root.optJSONObject("more_info")?.optInt("song_count", raw.size)?.let { it > raw.size } == true) "The catalog returned only part of this release." else null)
    }
    suspend fun playlistSongs(selected: CatalogPlaylist, page: Int): CatalogPage = withContext(Dispatchers.Default) {
        val root = request("playlist.getDetails", "listid" to selected.id, "p" to page.toString(), "n" to "10")
        if (text(root, "id").ifBlank { text(root, "listid") } != selected.id) throw IOException("Playlist identity mismatch")
        val raw = (root.optJSONArray("list") ?: root.optJSONArray("songs")).objects()
        val count = root.optJSONObject("more_info")?.optInt("song_count", -1) ?: root.optInt("list_count", -1)
        CatalogPage(tracks = uniqueTracks(raw.mapNotNull(::track)), nextPage = if (raw.isNotEmpty() && (if (count >= 0) page * 10 < count else raw.size >= 10)) page + 1 else null)
    }
}
