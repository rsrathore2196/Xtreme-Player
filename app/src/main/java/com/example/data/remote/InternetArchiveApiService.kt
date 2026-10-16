package com.example.data.remote

import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.example.data.model.MusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private const val TIMEOUT_MS = 3500L
    private const val PAGE_TIMEOUT_MS = 8_000L
    private const val PAGE_TTL_MS = 10 * 60_000L
    private const val ITEM_TTL_MS = 30 * 60_000L
    private val metadataRequests = Semaphore(4)
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
        val isSuccessful: Boolean = true
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
    ): LosslessSearchPage = withContext(Dispatchers.IO) {
        val terms = normalized(query).split(' ').filter(String::isNotBlank).take(12)
        if (terms.isEmpty()) return@withContext LosslessSearchPage()
        val requestedPage = page.coerceAtLeast(1)
        val rows = pageSize.coerceIn(1, 20)
        val key = "${terms.joinToString(" ")}|$requestedPage|$rows|$playableOnly"
        pageLocks[(key.hashCode() and Int.MAX_VALUE) % pageLocks.size].withLock {
            val cached = pageCache.get(key)
            if (cached != null && SystemClock.elapsedRealtime() - cached.first < PAGE_TTL_MS) {
                return@withLock cached.second
            }
            val result = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                try {
                    // Quote normalized terms: user input must not become Lucene operators.
                    val expression = terms.joinToString(" AND ") { "\"$it\"" }
                    val formattedQuery = "($expression) AND mediatype:(audio) AND " +
                        "(format:(\"FLAC\" OR \"24bit Flac\" OR \"Flac\" OR \"WAV\" OR " +
                        "\"Waveform Audio\" OR \"AIFF\" OR \"ALAC\" OR \"Monkey's Audio\" OR " +
                        "\"APE\" OR \"WavPack\" OR \"DSD\"))"
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
                    val response = JSONObject(httpClient.catalogRequest(request)).optJSONObject("response")
                        ?: throw java.io.IOException("Invalid Archive search response")
                    val docs = response.optJSONArray("docs")
                        ?: throw java.io.IOException("Missing Archive search results")
                    val parsed = coroutineScope {
                        (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.map { doc ->
                            async { cachedLosslessItem(doc) }
                        }.awaitAll()
                    }
                    val tracks = parsed.filterNotNull().flatten().distinctBy { it.id }
                        .filter { relevant(terms, it) && (!playableOnly || supportedSearchAudio(it)) }
                        .sortedWith(compareByDescending<MusicTrack> { relevance(terms, it) }
                            .thenByDescending { it.qualityBadge.startsWith("Hi-Res") }
                            .thenByDescending { it.bitrateKbps })
                    val total = response.optLong("numFound", -1L)
                    val hasMore = docs.length() > 0 && if (total >= 0L) {
                        requestedPage.toLong() * rows < total
                    } else docs.length() >= rows
                    val value = LosslessSearchPage(tracks,
                        nextPage = if (hasMore) requestedPage + 1 else null,
                        isSuccessful = parsed.none { it == null })
                    // Partial failures must be retried instead of being cached as exhausted.
                    if (parsed.none { it == null }) pageCache.put(key, SystemClock.elapsedRealtime() to value)
                    value
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Internet Archive search failed: ${e.message}")
                    cached?.second?.copy(isSuccessful = false) ?: LosslessSearchPage(isSuccessful = false)
                }
            }
            result ?: cached?.second?.copy(isSuccessful = false) ?: LosslessSearchPage(isSuccessful = false)
        }
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

    private suspend fun cachedLosslessItem(doc: JSONObject): List<MusicTrack>? {
        val identifier = text(doc, "identifier")
        if (identifier.isBlank()) return emptyList()
        return itemLocks[(identifier.hashCode() and Int.MAX_VALUE) % itemLocks.size].withLock {
            val cached = itemCache.get(identifier)
            if (cached != null && SystemClock.elapsedRealtime() - cached.first < ITEM_TTL_MS) {
                return@withLock cached.second
            }
            metadataRequests.withPermit {
                verifyAndParseLosslessItem(doc)?.also { tracks ->
                    itemCache.put(identifier, SystemClock.elapsedRealtime() to tracks)
                } ?: cached?.second
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
    private suspend fun verifyAndParseLosslessItem(doc: JSONObject): List<MusicTrack>? {
        val identifier = text(doc, "identifier")
        if (identifier.isBlank()) return emptyList()
        try {
            val metadataUrl = okhttp3.HttpUrl.Builder().scheme("https").host("archive.org")
                .addPathSegment("metadata").addPathSegment(identifier).build()
            val request = Request.Builder().url(metadataUrl)
                .header("User-Agent", "XtremePlayer/2.5.0 (Android)").build()
            val root = JSONObject(httpClient.catalogRequest(request))
            if (root.optBoolean("is_dark", false)) return emptyList()
            val metadata = root.optJSONObject("metadata")
            if (!authorized(metadata)) return emptyList()
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
            return lossless.mapNotNull { file ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val name = text(file, "name")
                if (name.isBlank() || name.split('/').any { it == ".." }) return@mapNotNull null
                // Names such as track01.flac cannot establish recording identity. Only a
                // single-file item's real title can substitute for missing embedded tags.
                val fileTitle = text(file, "title").ifBlank {
                    if (lossless.size == 1 && !isConcert) text(metadata, "title") else ""
                }
                if (fileTitle.isBlank() || genericTitle.matches(fileTitle.trim())) return@mapNotNull null
                val artist = text(file, "artist").ifBlank { text(file, "creator") }
                    .ifBlank { text(metadata, "creator") }.ifBlank { text(doc, "creator") }.ifBlank { "Unknown Artist" }
                val album = text(file, "album").ifBlank { text(metadata, "album") }
                    .ifBlank { text(doc, "album") }
                    .ifBlank { if (lossless.size > 1) text(metadata, "title") else "Unknown" }
                val duration = durationMillis(text(file, "length"))
                if (duration <= 0L || artist.equals("Unknown Artist", true) || artist.equals("Various Artists", true))
                    return@mapNotNull null
                val reportedBitrate = text(file, "bitrate").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.toInt()
                val bitrate = reportedBitrate ?: if (duration > 0L) {
                    ((text(file, "size").toLongOrNull() ?: 0L) * 8L / duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else 0
                val streamUrl = okhttp3.HttpUrl.Builder().scheme("https").host("archive.org")
                    .addPathSegment("download").addPathSegment(identifier).apply {
                        name.split('/').forEach { addPathSegment(it) }
                    }.build().toString()
                val title = if (isConcert && !Regex("(?i)\\blive\\b").containsMatchIn(fileTitle)) "$fileTitle (Live)" else fileTitle
                MusicTrack(id = "ia_$identifier:$name", title = title, artist = artist, album = album,
                    durationMs = duration, coverUrl = "https://archive.org/services/img/$identifier",
                    audioUrl = streamUrl, bitrateKbps = bitrate, qualityBadge = losslessQuality(file),
                    genre = text(file, "genre").ifBlank { "Unknown" },
                    year = text(file, "year").ifBlank { text(metadata, "year") }.ifBlank { text(doc, "year") },
                    isrc = text(file, "isrc"), source = "INTERNET_ARCHIVE", isLossless = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Failed to verify metadata for $identifier: ${e.message}")
            return null
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
