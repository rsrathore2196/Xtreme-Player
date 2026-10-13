package com.example.data.remote

import android.util.Log
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
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
 * Enforces strict Lossless file verification and a tight 3500ms timeout.
 * Rejects items that only contain lossy formats (MP3/OGG/VBR/Opus/AAC).
 */
object InternetArchiveApiService {
    private const val TAG = "InternetArchiveApi"
    private const val TIMEOUT_MS = 3500L

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
     * Searches Internet Archive for Lossless audio tracks matching [query].
     * Strictly verifies that each item contains a direct streamable lossless file
     * (FLAC, WAV, AIFF, ALAC, APE, WavPack, or DSD).
     * Rejects items that only contain lossy formats.
     * Times out after 3500ms.
     */
    suspend fun searchLosslessTracks(query: String, maxResults: Int = 10): List<MusicTrack> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        withTimeoutOrNull(TIMEOUT_MS) {
            try {
                // Step 1: Advanced Search Request targeting audio items with lossless formats
                val formattedQuery = "($trimmed) AND mediatype:(audio) AND (format:(\"FLAC\" OR \"24bit Flac\" OR \"WAV\" OR \"AIFF\" OR \"ALAC\" OR \"Monkey's Audio\" OR \"WavPack\" OR \"DSD\") OR format:(\"Flac\" OR \"Waveform Audio\" OR \"APE\"))"
                val encodedQuery = URLEncoder.encode(formattedQuery, "UTF-8")
                val searchUrl = "https://archive.org/advancedsearch.php" +
                        "?q=$encodedQuery" +
                        "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=album&fl[]=year" +
                        "&rows=$maxResults" +
                        "&output=json"

                val request = Request.Builder()
                    .url(searchUrl)
                    .addHeader("User-Agent", "XtremePlayer/2.5.0 (Android; Hi-Res Audio Streamer)")
                    .addHeader("Accept", "application/json")
                    .build()

                val responseBody = httpClient.catalogRequest(request)
                val json = JSONObject(responseBody)
                val responseObj = json.optJSONObject("response") ?: return@withTimeoutOrNull emptyList()
                val docs = responseObj.optJSONArray("docs") ?: return@withTimeoutOrNull emptyList()

                if (docs.length() == 0) return@withTimeoutOrNull emptyList()

                // Step 2: Strict File Format Verification & Metadata Extraction
                coroutineScope {
                    val deferredList = (0 until docs.length()).map { idx ->
                        val doc = docs.getJSONObject(idx)
                        async {
                            verifyAndParseLosslessItem(doc)
                        }
                    }
                    deferredList.awaitAll().flatten().distinctBy { it.id }
                        .sortedByDescending { track ->
                            trimmed.lowercase().split(Regex("\\s+")).count { word ->
                                word in (track.title + " " + track.artist).lowercase()
                            }
                        }.take(maxResults)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "Internet Archive search encountered error/timeout: ${e.message}")
                emptyList()
            }
        } ?: emptyList()
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

    private fun durationMillis(value: String): Long {
        val seconds = if (value.contains(':')) value.split(':').fold(0.0) { total, part ->
            val amount = part.toDoubleOrNull() ?: return 0L
            total * 60 + amount
        } else value.toDoubleOrNull() ?: return 0L
        return if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong() else 0L
    }

    /** Bind each recording to its own file tags and stable item-plus-file identity. */
    private suspend fun verifyAndParseLosslessItem(doc: JSONObject): List<MusicTrack> {
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
            val files = root.optJSONArray("files") ?: return emptyList()
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
                // A filename is file-specific metadata; never substitute the album/concert title.
                val fileTitle = text(file, "title").ifBlank {
                    name.substringAfterLast('/').substringBeforeLast('.')
                        .replace(Regex("^\\d{1,3}[._ -]+"), "").replace('_', ' ').trim()
                }
                if (fileTitle.isBlank()) return@mapNotNull null
                val artist = text(file, "artist").ifBlank { text(file, "creator") }
                    .ifBlank { text(metadata, "creator") }.ifBlank { text(doc, "creator") }.ifBlank { "Unknown Artist" }
                val album = text(file, "album").ifBlank { text(metadata, "album") }
                    .ifBlank { text(doc, "album") }
                    .ifBlank { if (lossless.size > 1) text(metadata, "title") else "Unknown" }
                val duration = durationMillis(text(file, "length"))
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
                    audioUrl = streamUrl, bitrateKbps = bitrate, qualityBadge = "Lossless",
                    genre = text(file, "genre").ifBlank { "Unknown" },
                    year = text(file, "year").ifBlank { text(metadata, "year") }.ifBlank { text(doc, "year") },
                    isrc = text(file, "isrc"), source = "INTERNET_ARCHIVE", isLossless = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Failed to verify metadata for $identifier: ${e.message}")
            return emptyList()
        }
    }
}
