package com.example.data.remote

import com.example.performance.AppDispatchers

import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogNames
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import com.example.playback.LosslessDiagnostics.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service for querying the Internet Archive API specifically for verified Lossless audio.
 * Supported uncompressed and lossless compressed formats:
 * - FLAC (.flac)
 * - WAV (.wav)
 * - AIFF (.aiff / .aif)
 * - ALAC / Apple Lossless (.m4a with ALAC codec)
 * - APE / Monkey's Audio (.ape)
 * - WavPack (.wv)
 * - DSD / Direct Stream Digital (.dsf / .dff)
 *
 * Enforces file-specific lossless verification with cancellable, bounded requests.
 * Rejects items that only contain lossy formats (MP3/OGG/VBR/Opus/AAC).
 */
object InternetArchiveApiService {
    private const val TAG = "InternetArchiveApi"
    private const val TIMEOUT_MS = 10_000L
    private const val PAGE_TIMEOUT_MS = 24_000L
    internal const val BACKGROUND_TIMEOUT_MS = 45_000L
    private const val PAGE_TTL_MS = 10 * 60_000L
    private const val ITEM_TTL_MS = 30 * 60_000L
    private val metadataRequests = Semaphore(2)
    private val headerRequests = Semaphore(2)
    private val pageLocks = Array(16) { Mutex() }
    private val itemLocks = Array(16) { Mutex() }
    private val reuseLicense = Regex("/(?:licenses/(?:by|by-sa|by-nd|by-nc|by-nc-sa|by-nc-nd)/[1-4]\\.0(?:/.*)?|publicdomain/(?:zero|mark)/1\\.0(?:/.*)?)")
    private val genericTitle = Regex("(?i)^(?:track|audio|cd|disc|unknown)[ ._-]*[0-9]*$")
    private val pageCache = object : LruCache<String, Pair<Long, LosslessSearchPage>>(640) {
        override fun sizeOf(key: String, value: Pair<Long, LosslessSearchPage>): Int =
            value.second.tracks.size.coerceAtLeast(1)
    }
    private val itemCache = object : LruCache<String, Pair<Long, List<MusicTrack>>>(1280) {
        override fun sizeOf(key: String, value: Pair<Long, List<MusicTrack>>): Int =
            value.second.size.coerceAtLeast(1)
    }

    /** [page] refers to Archive item pages; a collection may contain several recordings. */
    data class LosslessSearchPage(
        val tracks: List<MusicTrack> = emptyList(),
        val nextPage: Int? = null,
        val isSuccessful: Boolean = true,
        val failure: Reason? = null,
        val exhaustive: Boolean = true
    )

    private class RecordingSearch(val track: MusicTrack, val excludedUrls: Set<String>, val allowed: () -> Boolean) {
        val headers = AtomicInteger(8)
        val items = linkedSetOf<String>()
        @Volatile var truncated = false
        @Volatile var incomplete = false
        @Volatile var failure: Reason? = null
    }

    /** Collections are discovery hints only. Every returned file still proves its own identity. */
    internal suspend fun findLosslessRecording(track: MusicTrack, excludedUrls: Set<String> = emptySet(),
        allowed: () -> Boolean = { true }): LosslessSearchPage =
        withContext(Dispatchers.IO) {
            val search = RecordingSearch(track, excludedUrls, allowed)
            val plans = withContext(AppDispatchers.CpuBounded) { recordingQueries(track) }
            var successful = true
            var exhausted = true
            var failure: Reason? = null
            var sawCandidates = false
            val found = withTimeoutOrNull(BACKGROUND_TIMEOUT_MS) {
                val pages = plans.associateWith { 1 as Int? }.toMutableMap()
                // Inspect every relevant strategy before spending the budget on deeper pages.
                for (round in 0..1) {
                    for (batch in plans.chunked(2)) {
                        currentCoroutineContext().ensureActive()
                        if (!allowed()) return@withTimeoutOrNull LosslessSearchPage(isSuccessful = false,
                            failure = Reason.NETWORK_POLICY_BLOCKED, exhaustive = false)
                        if (search.items.size >= 20) { search.truncated = true; return@withTimeoutOrNull null }
                        val fetched = coroutineScope { batch.mapNotNull { expression ->
                            pages[expression]?.let { page -> async {
                                expression to searchPage(expression, expression, page, 4, true, search)
                            } }
                        }.awaitAll() }
                        for ((expression, result) in fetched) {
                        sawCandidates = sawCandidates || result.tracks.isNotEmpty()
                        successful = successful && result.isSuccessful
                        failure = result.failure ?: failure
                        val matches = withContext(AppDispatchers.CpuBounded) {
                            result.tracks.filter { it.audioUrl !in excludedUrls }.mapNotNull { candidate ->
                                RecordingIdentity.losslessConfidence(track, candidate)?.let { candidate to it }
                            }.sortedByDescending { it.second }.map { it.first }
                        }
                        if (matches.isNotEmpty()) return@withTimeoutOrNull result.copy(tracks = matches, failure = null)
                        pages[expression] = result.nextPage
                        }
                    }
                }
                exhausted = pages.values.all { it == null }
                LosslessSearchPage(isSuccessful = successful, failure = failure ?: if (sawCandidates)
                    Reason.RECORDING_MISMATCH else Reason.NO_RESULTS,
                    exhaustive = exhausted && !search.truncated)
            }
            found ?: LosslessSearchPage(isSuccessful = false,
                failure = if (search.truncated) Reason.METADATA_INCOMPLETE else Reason.NETWORK_TIMEOUT, exhaustive = false)
        }

    internal fun originalFileUrl(track: MusicTrack): String? {
        if (!track.id.startsWith("ia_") || ':' !in track.id) return null
        val identifier = track.id.removePrefix("ia_").substringBefore(':')
        val file = track.id.substringAfter(':')
        if (identifier.isBlank() || file.isBlank() || file.split('/').any { it == ".." }) return null
        return okhttp3.HttpUrl.Builder().scheme("https").host("archive.org")
            .addPathSegment("download").addPathSegment(identifier).apply {
                file.split('/').forEach { addPathSegment(it) }
            }.build().toString()
    }

    /** Revalidate an exact file, including its current explicit license; never search a substitute. */
    internal suspend fun verifyDirectRecording(track: MusicTrack, allowed: () -> Boolean): MusicTrack? =
        withContext(Dispatchers.IO) {
            if (!allowed()) return@withContext null
            val url = track.audioUrl.toHttpUrlOrNull() ?: return@withContext null
            if (url.scheme != "https" || url.host != "archive.org" || url.pathSegments.firstOrNull() != "download")
                return@withContext null
            val identifier = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return@withContext null
            val doc = withContext(AppDispatchers.CpuBounded) { JSONObject().put("identifier", identifier) }
            cachedLosslessItem(doc, RecordingSearch(track, emptySet(), allowed))?.firstOrNull {
                it.audioUrl == track.audioUrl && RecordingIdentity.losslessConfidence(track, it) != null
            }
        }

    internal fun searchExpression(expression: String, targeted: Boolean): String {
        val collections = "($expression) AND (mediatype:audio OR mediatype:etree OR collection:etree)"
        // Album-level format indexes may be incomplete. Targeted artist/album/ISRC
        // searches inspect bounded individual files instead of excluding the item.
        return if (targeted) collections else collections + " AND " +
            "(format:(\"FLAC\" OR \"24bit Flac\" OR \"Flac\" OR \"WAV\" OR " +
            "\"Waveform Audio\" OR \"AIFF\" OR \"ALAC\" OR \"Apple Lossless Audio\" OR \"Apple Lossless\" OR \"Monkey's Audio\" OR " +
            "\"APE\" OR \"WavPack\" OR \"DSD\"))"
    }

    internal fun recordingQueries(track: MusicTrack): List<String> {
        fun quote(value: String) = "\"${normalized(value)}\""
        val title = StreamResolver.extractCleanTitle(track.title, track.artist)
        val primary = RecordingIdentity.artistNames(track).firstOrNull().orEmpty()
        val album = CatalogNames.clean(track.album).takeUnless {
            normalized(it) in setOf("", "unknown", "single", "online stream", "youtube music")
        }.orEmpty()
        return buildList {
            RecordingIdentity.isrc(track.isrc)?.let { add("\"$it\"") }
            if (title.isNotBlank() && primary.isNotBlank()) add("(${quote(title)} AND ${quote(primary)})")
            if (album.isNotBlank() && primary.isNotBlank()) add("(title:${quote(album)} AND creator:${quote(primary)})")
            if (album.isNotBlank()) add("title:${quote(album)}")
            if (primary.isNotBlank()) add("creator:${quote(primary)}")
            if (title.isNotBlank()) add("title:${quote(title)}")
        }.distinct().take(6)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 3; maxRequestsPerHost = 2 })
            .build()
    }

    /**
     * Checks if a given file name and format string represents a supported lossless audio format.
     */
    fun isLosslessAudioFile(fileName: String, format: String): Boolean {
        val lowerName = fileName.lowercase().trim()
        val lowerFormat = format.lowercase().trim()

        // Explicitly reject known lossy extensions
        if (lowerName.endsWith(".mp3") ||
            lowerName.endsWith(".ogg") ||
            lowerName.endsWith(".opus") ||
            lowerName.endsWith(".aac") ||
            lowerName.endsWith(".wma") ||
            lowerFormat.contains("mp3") ||
            lowerFormat.contains("vorbis") ||
            lowerFormat.contains("vbr") ||
            lowerFormat.contains("opus") ||
            lowerFormat.contains("ogg")
        ) {
            return false
        }

        // 1. FLAC (.flac)
        if (lowerName.endsWith(".flac") || lowerFormat == "flac" || lowerFormat.contains("flac")) {
            return true
        }

        // 2. WAV (.wav)
        if (lowerName.endsWith(".wav") || lowerFormat == "wav" || lowerFormat.contains("waveform audio")) {
            return true
        }

        // 3. AIFF (.aiff / .aif)
        if (lowerName.endsWith(".aiff") || lowerName.endsWith(".aif") || lowerFormat == "aiff" || lowerFormat == "aif") {
            return true
        }

        // 4. ALAC / Apple Lossless (.m4a with ALAC codec)
        if (lowerName.endsWith(".m4a") && (lowerFormat.contains("alac") || lowerFormat.contains("apple lossless") || lowerName.contains("alac"))) {
            return true
        }
        if (lowerFormat.contains("alac") || lowerFormat.contains("apple lossless")) {
            return true
        }

        // 5. APE / Monkey's Audio (.ape)
        if (lowerName.endsWith(".ape") || lowerFormat.contains("monkey's audio") || lowerFormat == "ape") {
            return true
        }

        // 6. WavPack (.wv)
        if (lowerName.endsWith(".wv") || lowerFormat.contains("wavpack") || lowerFormat == "wv") {
            return true
        }

        // 7. DSD / Direct Stream Digital (.dsf / .dff)
        if (lowerName.endsWith(".dsf") || lowerName.endsWith(".dff") || lowerFormat.contains("dsd") || lowerFormat == "dsf" || lowerFormat == "dff") {
            return true
        }

        return false
    }

    /**
     * Search a real Archive item page, retaining every matching individual audio file.
     * Do not infer hi-res from bitrate or losslessness alone. Successful pages and parsed
     * items are cached separately so paging does not decode/fetch the same collection again.
     */
    suspend fun searchLosslessPage(
        query: String,
        page: Int = 1,
        pageSize: Int = 10,
        playableOnly: Boolean = true
    ): LosslessSearchPage {
        val terms = normalized(query).split(' ').filter(String::isNotBlank).take(12)
        if (terms.isEmpty()) return LosslessSearchPage()
        return searchPage(query, terms.joinToString(" AND ") { "\"$it\"" }, page, pageSize, playableOnly, null)
    }

    private suspend fun searchPage(query: String, expression: String, page: Int, pageSize: Int,
        playableOnly: Boolean, recording: RecordingSearch?): LosslessSearchPage = withContext(AppDispatchers.CpuBounded) {
        val terms = normalized(query).split(' ').filter(String::isNotBlank).take(12)
        val requestedPage = page.coerceAtLeast(1)
        val rows = pageSize.coerceIn(1, 20)
        val key = "$expression|$requestedPage|$rows|$playableOnly|${recording?.track?.let(RecordingIdentity::key).orEmpty()}|${recording?.excludedUrls?.hashCode() ?: 0}"
        pageLocks[(key.hashCode() and Int.MAX_VALUE) % pageLocks.size].withLock {
            val cached = pageCache.get(key)
            if (cached != null && SystemClock.elapsedRealtime() - cached.first <
                if (cached.second.tracks.isEmpty()) 60_000L else PAGE_TTL_MS) {
                return@withLock cached.second
            }
            val result = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                try {
                    // Quote normalized terms: user input must not become Lucene operators.
                    val formattedQuery = searchExpression(expression, targeted = recording != null)
                    val searchUrl = okhttp3.HttpUrl.Builder().scheme("https").host("archive.org")
                        .addPathSegment("advancedsearch.php").addQueryParameter("q", formattedQuery)
                        .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title")
                        .addQueryParameter("fl[]", "creator").addQueryParameter("fl[]", "album")
                        .addQueryParameter("fl[]", "year").addQueryParameter("rows", rows.toString())
                        .addQueryParameter("page", requestedPage.toString())
                        .addQueryParameter("output", "json").build()
                    val request = Request.Builder().url(searchUrl)
                        .header("User-Agent", "XtremePlayer/2.5.0 (Android; Lossless Audio Search)")
                        .header("Accept", "application/json").build()
                    if (recording != null && !withContext(Dispatchers.IO) { recording.allowed() })
                        return@withTimeoutOrNull LosslessSearchPage(isSuccessful = false,
                            failure = Reason.NETWORK_POLICY_BLOCKED, exhaustive = false)
                    val response = JSONObject(requestMetadata(request)).optJSONObject("response")
                        ?: throw java.io.IOException("Invalid Archive search response")
                    val docs = response.optJSONArray("docs")
                        ?: throw java.io.IOException("Missing Archive search results")
                    val parsed = collectRecordingFiles(recording?.track,
                        (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }, recording?.excludedUrls.orEmpty()) {
                        cachedLosslessItem(it, recording)
                    }
                    val tracks = parsed.filterNotNull().flatten().distinctBy { it.id }
                        .filter { (recording != null || relevant(terms, it)) && (!playableOnly || supportedSearchAudio(it)) }
                        .sortedWith(compareByDescending<MusicTrack> { relevance(terms, it) }
                            .thenByDescending { it.qualityBadge.startsWith("Hi-Res") }
                            .thenByDescending { it.bitrateKbps })
                    val total = response.optLong("numFound", -1L)
                    val hasMore = docs.length() > 0 && if (total >= 0L) {
                        requestedPage.toLong() * rows < total
                    } else docs.length() >= rows
                    val value = LosslessSearchPage(tracks,
                        nextPage = if (hasMore) requestedPage + 1 else null,
                        isSuccessful = parsed.none { it == null } && recording?.incomplete != true,
                        failure = recording?.failure ?: if (parsed.any { it == null } || recording?.incomplete == true)
                            Reason.METADATA_INCOMPLETE else null,
                        exhaustive = !hasMore && recording?.truncated != true)
                    // Partial failures must be retried instead of being cached as exhausted.
                    if (value.isSuccessful && recording?.truncated != true)
                        pageCache.put(key, SystemClock.elapsedRealtime() to value)
                    value
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Internet Archive search failed: ${e.message}")
                    val reason = if (e is java.net.SocketTimeoutException) Reason.NETWORK_TIMEOUT else Reason.METADATA_INCOMPLETE
                    cached?.second?.copy(isSuccessful = false, failure = reason) ?:
                        LosslessSearchPage(isSuccessful = false, failure = reason, exhaustive = false)
                }
            }
            result ?: cached?.second?.copy(isSuccessful = false, failure = Reason.NETWORK_TIMEOUT) ?:
                LosslessSearchPage(isSuccessful = false, failure = Reason.NETWORK_TIMEOUT, exhaustive = false)
        }
    }

    /** Do not make a verified recording wait for unrelated slow collection requests. */
    internal suspend fun collectRecordingFiles(requested: MusicTrack?, documents: List<JSONObject>,
        excludedUrls: Set<String> = emptySet(), load: suspend (JSONObject) -> List<MusicTrack>?
    ): List<List<MusicTrack>?> = coroutineScope {
        if (requested == null) return@coroutineScope documents.map { async { load(it) } }.awaitAll()
        val results = Channel<List<MusicTrack>?>(documents.size.coerceAtLeast(1))
        val jobs = documents.map { document -> launch { results.send(load(document)) } }
        val completed = mutableListOf<List<MusicTrack>?>()
        try {
            for (ignored in documents.indices) {
                val tracks = results.receive()
                completed.add(tracks)
                if (tracks.orEmpty().any { it.audioUrl !in excludedUrls &&
                    RecordingIdentity.losslessConfidence(requested, it) != null }) break
            }
        } finally {
            jobs.forEach { it.cancel() }
            results.cancel()
        }
        completed
    }

    /** Playback resolver compatibility: keep its bounded first-page result contract. */
    suspend fun searchLosslessTracks(query: String, maxResults: Int = 10): List<MusicTrack> {
        if (maxResults <= 0 || query.isBlank()) return emptyList()
        return withTimeoutOrNull(TIMEOUT_MS) {
            searchLosslessPage(query, pageSize = maxResults.coerceAtMost(20), playableOnly = false)
                .tracks.take(maxResults)
        } ?: emptyList()
    }

    // The existing Media3 stack has no AIFF/APE/WavPack/DSD extractor or native extension.
    // Keep those legacy resolver formats untouched while exposing playable search containers.
    private fun supportedSearchAudio(track: MusicTrack): Boolean {
        val file = java.net.URI(track.audioUrl).path.substringAfterLast('/').lowercase(Locale.ROOT)
        return file.endsWith(".flac") || file.endsWith(".wav") || file.endsWith(".m4a")
    }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    private fun relevant(terms: List<String>, track: MusicTrack): Boolean {
        val metadata = normalized("${track.title} ${track.artist} ${track.album}")
        return terms.all { it in metadata }
    }

    private fun relevance(terms: List<String>, track: MusicTrack): Int {
        val phrase = terms.joinToString(" ")
        val title = normalized(track.title)
        val artist = normalized(track.artist)
        return when {
            title == phrase -> 5
            artist == phrase -> 4
            phrase in title -> 3
            phrase in artist -> 2
            else -> 1
        }
    }

    private suspend fun cachedLosslessItem(doc: JSONObject, recording: RecordingSearch? = null): List<MusicTrack>? {
        val identifier = text(doc, "identifier")
        if (identifier.isBlank()) return emptyList()
        if (recording != null) synchronized(recording.items) {
            if (identifier in recording.items) return emptyList()
            if (recording.items.size >= 20) { recording.truncated = true; return null }
            recording.items.add(identifier)
        }
        return itemLocks[(identifier.hashCode() and Int.MAX_VALUE) % itemLocks.size].withLock {
            val cached = itemCache.get(identifier)
            if (cached != null && SystemClock.elapsedRealtime() - cached.first < ITEM_TTL_MS &&
                (recording == null || cached.second.any { it.audioUrl !in recording.excludedUrls &&
                    RecordingIdentity.losslessConfidence(recording.track, it) != null })) {
                return@withLock cached.second
            }
            metadataRequests.withPermit {
                if (recording != null && !withContext(Dispatchers.IO) { recording.allowed() }) return@withPermit null
                verifyAndParseLosslessItem(doc, recording)?.also { tracks ->
                    if (tracks.isNotEmpty()) itemCache.put(identifier,
                        SystemClock.elapsedRealtime() to (tracks + cached?.second.orEmpty()).distinctBy { it.id })
                }
            }
        }
    }

    /**
     * Backward-compatible alias for [searchLosslessTracks].
     */
    suspend fun searchFlacTracks(query: String, maxResults: Int = 10): List<MusicTrack> =
        searchLosslessTracks(query, maxResults)

    /**
     * Retrieves metadata for an item and verifies that a true streamable lossless file exists.
     * Rejects items that only have lossy files.
     */
    private fun text(objectValue: JSONObject?, key: String): String = when (val value = objectValue?.opt(key)) {
        is String -> value.takeUnless { it == "null" }.orEmpty().trim()
        is org.json.JSONArray -> if (value.length() == 1) value.optString(0).trim() else ""
        is Number -> value.toString()
        else -> ""
    }

    /** Public accessibility alone is not a playback license. Require an explicit reuse grant. */
    private fun authorized(metadata: JSONObject?): Boolean {
        if (metadata == null || metadata.optBoolean("access-restricted-item", false) ||
            metadata.optBoolean("is_dark", false)) return false
        val rights = text(metadata, "rights").lowercase(Locale.ROOT)
        if (rights.contains("all rights reserved")) return false
        val licenses = when (val value = metadata.opt("licenseurl")) {
            is org.json.JSONArray -> (0 until value.length()).map { value.optString(it) }
            is String -> listOf(value)
            else -> emptyList()
        }
        return licenses.any { license ->
            val url = license.toHttpUrlOrNull()
            url != null && url.host in setOf("creativecommons.org", "www.creativecommons.org") &&
                reuseLicense.matches(url.encodedPath)
        } || rights.trim() == "public domain"
    }

    private fun durationMillis(value: String): Long {
        val seconds = if (value.contains(':')) value.split(':').fold(0.0) { total, part ->
            val amount = part.toDoubleOrNull() ?: return 0L
            total * 60 + amount
        } else value.toDoubleOrNull() ?: return 0L
        return if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else 0L
    }

    /** Bind each recording to its own file tags and stable item-plus-file identity. */
    private suspend fun verifyAndParseLosslessItem(doc: JSONObject, recording: RecordingSearch?): List<MusicTrack>? {
        val identifier = text(doc, "identifier")
        if (identifier.isBlank()) return emptyList()
        try {
            val metadataUrl = okhttp3.HttpUrl.Builder().scheme("https").host("archive.org")
                .addPathSegment("metadata").addPathSegment(identifier).build()
            val request = Request.Builder().url(metadataUrl)
                .header("User-Agent", "XtremePlayer/2.5.0 (Android)").build()
            val root = JSONObject(requestMetadata(request))
            if (!authorized(root.optJSONObject("metadata"))) {
                recording?.failure = Reason.UNAUTHORIZED
                return emptyList()
            }
            val searchHeaders = AtomicInteger(2)
            return parseLosslessItem(doc, root, recording?.track) { url ->
                if (recording == null) {
                    if (searchHeaders.getAndDecrement() > 0) readFlacHeader(url) else null
                } else if (recording.headers.getAndDecrement() <= 0) {
                    recording.truncated = true
                    null
                } else if (!withContext(Dispatchers.IO) { recording.allowed() }) null
                else readFlacHeader(url).also { if (it == null) recording.incomplete = true }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            recording?.failure = if (e is java.net.SocketTimeoutException) Reason.NETWORK_TIMEOUT else Reason.METADATA_INCOMPLETE
            Log.w(TAG, "Failed to verify metadata for $identifier: ${e.message}")
            return null
        }
    }

    @Volatile private var retryNotBeforeMs = 0L

    private suspend fun requestMetadata(request: Request): String {
        if (SystemClock.elapsedRealtime() < retryNotBeforeMs) throw CatalogHttpException(429,
            (retryNotBeforeMs - SystemClock.elapsedRealtime()) / 1000L)
        repeat(2) { attempt ->
            try { return httpClient.catalogRequest(request, maxResponseBytes = 4L * 1024 * 1024) }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) {
                val retryable = e !is CatalogHttpException || e.status == 408 || e.status == 429 || e.status >= 500
                if (e is CatalogHttpException && e.status in setOf(429, 503) && (e.retryAfter ?: 0L) > 3L) {
                    retryNotBeforeMs = SystemClock.elapsedRealtime() + e.retryAfter!!.coerceAtMost(3600L) * 1000L
                    throw e
                }
                if (!retryable || attempt == 1) throw e
                // Respect bounded server backoff; don't immediately hammer a restricted item.
                delay(((e as? CatalogHttpException)?.retryAfter?.times(1000L) ?: 500L).coerceIn(500L, 3_000L))
            }
        }
        throw IOException("Incomplete Archive response")
    }

    private suspend fun readFlacHeader(url: String): ArchiveAudioMetadata.Tags? = withContext(Dispatchers.IO) {
        headerRequests.withPermit {
            if (SystemClock.elapsedRealtime() < retryNotBeforeMs) return@withPermit null
            try {
                val bytes = suspendCancellableCoroutine<ByteArray> { continuation ->
                    val call = httpClient.newCall(Request.Builder().url(url)
                        .header("Range", "bytes=0-262143").header("Accept-Encoding", "identity")
                        .header("User-Agent", "XtremePlayer/2.5.0 (Android; Audio Metadata)").build())
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: IOException) {
                            if (!continuation.isCancelled) continuation.resumeWithException(e)
                        }
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            response.use {
                                try {
                                    if (!response.isSuccessful) throw IOException("Archive header HTTP ${response.code}")
                                    val body = response.body ?: throw IOException("Empty Archive header")
                                    val buffer = okio.Buffer()
                                    val source = body.source()
                                    // A server may ignore Range. Stop reading at the cap and close it.
                                    while (buffer.size < 262_144L && source.read(buffer,
                                        minOf(8192L, 262_144L - buffer.size)) != -1L) Unit
                                    if (!continuation.isCancelled) continuation.resume(buffer.readByteArray())
                                } catch (e: Exception) {
                                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                                }
                            }
                        }
                    })
                }
                withContext(AppDispatchers.CpuBounded) { ArchiveAudioMetadata.flac(bytes) }
            } catch (e: CancellationException) { throw e }
            catch (_: IOException) { null }
        }
    }

    /** Interpret actual per-file tags, without borrowing a collection's title/duration. */
    internal suspend fun parseLosslessItem(doc: JSONObject, root: JSONObject, requested: MusicTrack? = null,
        readHeader: suspend (String) -> ArchiveAudioMetadata.Tags? = ::readFlacHeader
    ): List<MusicTrack> = withContext(AppDispatchers.CpuBounded) {
            val identifier = text(doc, "identifier")
            if (identifier.isBlank()) return@withContext emptyList()
            if (root.optBoolean("is_dark", false)) return@withContext emptyList()
            val metadata = root.optJSONObject("metadata")
            if (!authorized(metadata)) return@withContext emptyList()
            val files = root.optJSONArray("files") ?: throw java.io.IOException("Missing Archive file metadata")
            val lossless = (0 until files.length()).mapNotNull { files.optJSONObject(it) }.filter {
                isLosslessAudioFile(text(it, "name"), text(it, "format")) &&
                    !it.optBoolean("private", false) && text(it, "size").toLongOrNull()?.let { size -> size > 0 } != false
            }
            val collections = when (val value = metadata?.opt("collection")) {
                is org.json.JSONArray -> (0 until value.length()).map { value.optString(it) }
                is String -> listOf(value)
                else -> emptyList()
            }
            val isConcert = collections.any { it.equals("etree", true) }
            val ordered = if (requested == null) lossless else lossless.sortedByDescending { file ->
                val hint = normalized(text(file, "title") + " " + text(file, "name"))
                if (RecordingIdentity.title(requested) in hint) 1 else 0
            }
            return@withContext ordered.mapNotNull { file ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val name = text(file, "name")
                if (name.isBlank() || name.split('/').any { it == ".." }) return@mapNotNull null
                // Names such as track01.flac cannot establish recording identity. Only a
                // single-file item's real title can substitute for missing embedded tags.
                val taggedTitle = text(file, "title").ifBlank { text(file, "track_title") }.ifBlank { text(file, "song_title") }
                var fileTitle = taggedTitle.ifBlank {
                    if (lossless.size == 1 && !isConcert) text(metadata, "title") else ""
                }
                var artist = text(file, "artist").ifBlank { text(file, "creator") }
                    .ifBlank { text(file, "album_artist") }.ifBlank { text(file, "albumartist") }
                    .ifBlank { text(metadata, "artist") }
                    .ifBlank { text(metadata, "creator") }.ifBlank { text(doc, "creator") }.ifBlank { "Unknown Artist" }
                var album = text(file, "album").ifBlank { text(metadata, "album") }
                    .ifBlank { text(doc, "album") }
                    .ifBlank { if (lossless.size > 1) text(metadata, "title") else "Unknown" }
                var duration = sequenceOf("length", "duration", "runtime")
                    .map { durationMillis(text(file, it)) }.firstOrNull { it > 0L }
                    ?: text(file, "duration_ms").toLongOrNull()?.takeIf { it > 0L }
                    ?: if (lossless.size == 1 && !isConcert) sequenceOf("length", "duration", "runtime")
                        .map { durationMillis(text(metadata, it)) }.firstOrNull { it > 0L } ?: 0L else 0L
                var code = text(file, "isrc")
                val streamUrl = okhttp3.HttpUrl.Builder().scheme("https").host("archive.org")
                    .addPathSegment("download").addPathSegment(identifier).apply {
                        name.split('/').forEach { addPathSegment(it) }
                    }.build().toString()
                val titleKey = CatalogNames.normalize(StreamResolver.extractCleanTitle(fileTitle, artist))
                val needsIdentity = fileTitle.isBlank() || genericTitle.matches(fileTitle.trim()) || duration <= 0L ||
                    artist.equals("Unknown Artist", true) || artist.equals("Various Artists", true) ||
                    requested != null && CatalogNames.normalize(artist) != CatalogNames.normalize(requested.artist)
                val plausible = requested != null && (taggedTitle.isBlank() || genericTitle.matches(fileTitle.trim()) ||
                    CatalogNames.spellingEquivalent(RecordingIdentity.title(requested), titleKey) ||
                    RecordingIdentity.title(requested) in normalized(name))
                val header = if (needsIdentity && (requested == null || plausible) && name.endsWith(".flac", true))
                    readHeader(streamUrl) else null
                if (header != null) {
                    fileTitle = header.title.ifBlank { fileTitle }
                    artist = header.artist.ifBlank { artist }
                    album = header.album.ifBlank { album }
                    code = header.isrc.ifBlank { code }
                    if (header.durationMs > 0L) duration = header.durationMs
                }
                if (fileTitle.isBlank() || genericTitle.matches(fileTitle.trim())) return@mapNotNull null
                val verifiedCode = requested != null && RecordingIdentity.isrc(code)?.let {
                    it == RecordingIdentity.isrc(requested.isrc)
                } == true
                if ((duration <= 0L || artist.equals("Unknown Artist", true) || artist.equals("Various Artists", true)) &&
                    !verifiedCode) return@mapNotNull null
                val reportedBitrate = text(file, "bitrate").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.toInt()
                val bitrate = reportedBitrate ?: if (duration > 0L) {
                    ((text(file, "size").toLongOrNull() ?: 0L) * 8L / duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else 0
                val version = text(file, "version")
                if (version.isNotBlank() && !fileTitle.contains(version, true)) fileTitle += " ($version)"
                val title = if (isConcert && !Regex("(?i)\\blive\\b").containsMatchIn(fileTitle)) "$fileTitle (Live)" else fileTitle
                MusicTrack(id = "ia_$identifier:$name", title = title, artist = artist, album = album,
                    durationMs = duration, coverUrl = "https://archive.org/services/img/$identifier",
                    audioUrl = streamUrl, bitrateKbps = bitrate, qualityBadge = if (header != null &&
                        header.bitDepth > 16 && header.sampleRate >= 44_100) "Hi-Res • Lossless" else losslessQuality(file),
                    genre = text(file, "genre").ifBlank { "Unknown" },
                    year = text(file, "year").ifBlank { text(metadata, "year") }.ifBlank { text(doc, "year") },
                    isrc = code, source = "INTERNET_ARCHIVE", isLossless = true)
            }
    }

    private fun losslessQuality(file: JSONObject): String {
        val bits = listOf("bits", "bitdepth", "bit_depth", "bits_per_sample")
            .firstNotNullOfOrNull { text(file, it).toIntOrNull()?.takeIf { value -> value in 8..64 } }
            ?: Regex("(?i)(16|24|32)[ -]?bit").find(text(file, "format"))?.groupValues?.get(1)?.toIntOrNull()
        val rate = listOf("samplerate", "sample_rate", "sampling_rate")
            .firstNotNullOfOrNull { key ->
                val value = text(file, key).lowercase(Locale.ROOT)
                val number = Regex("[0-9]+(?:[.][0-9]+)?").find(value)?.value?.toDoubleOrNull()
                number?.let { if (value.contains("khz")) it * 1000 else it }
                    ?.takeIf { it.isFinite() && it in 8000.0..768000.0 }
            }
        return if (bits != null && rate != null && (bits > 16 || rate > 48000)) "Hi-Res • Lossless" else "Lossless"
    }
}
