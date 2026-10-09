package com.example.data.remote

import android.util.Log
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
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
                val formattedQuery = "$trimmed AND mediatype:(audio) AND (format:(\"FLAC\" OR \"24bit Flac\" OR \"WAV\" OR \"AIFF\" OR \"ALAC\" OR \"Monkey's Audio\" OR \"WavPack\" OR \"DSD\") OR format:(\"Flac\" OR \"Waveform Audio\" OR \"APE\"))"
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

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Search failed with HTTP code: ${response.code}")
                    return@withTimeoutOrNull emptyList()
                }

                val responseBody = response.body?.string() ?: return@withTimeoutOrNull emptyList()
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
                    deferredList.awaitAll().filterNotNull()
                }
            } catch (e: Exception) {
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
    private fun verifyAndParseLosslessItem(doc: JSONObject): MusicTrack? {
        val identifier = doc.optString("identifier", "").trim()
        if (identifier.isBlank()) return null

        val title = doc.optString("title", "").ifBlank { identifier }
        val creator = doc.optString("creator", "").ifBlank { "Internet Archive" }
        val album = doc.optString("album", "").ifBlank { "Live & Archival Hi-Res" }
        val year = doc.optString("year", "")

        try {
            val metadataUrl = "https://archive.org/metadata/$identifier"
            val request = Request.Builder()
                .url(metadataUrl)
                .addHeader("User-Agent", "XtremePlayer/2.5.0 (Android; Hi-Res Audio Streamer)")
                .addHeader("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val responseBody = response.body?.string() ?: return null
            val metadataJson = JSONObject(responseBody)
            val filesArray = metadataJson.optJSONArray("files") ?: return null

            var verifiedFileName: String? = null
            var verifiedDurationSec: Long = 0L
            var verifiedBitrateKbps: Int = 1411 // Standard CD baseline (16-bit 44.1kHz)

            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(i)
                val fileName = fileObj.optString("name", "")
                val format = fileObj.optString("format", "")

                if (isLosslessAudioFile(fileName, format)) {
                    verifiedFileName = fileName
                    val lengthStr = fileObj.optString("length", "")
                    val parsedDuration = lengthStr.toDoubleOrNull()?.toLong() ?: 0L
                    if (parsedDuration > 0) {
                        verifiedDurationSec = parsedDuration
                    }
                    if (format.contains("24bit", ignoreCase = true) || fileName.contains("24bit", ignoreCase = true)) {
                        verifiedBitrateKbps = 4608 // 24-bit studio master
                    } else if (fileName.endsWith(".dsf", ignoreCase = true) || fileName.endsWith(".dff", ignoreCase = true) || format.contains("dsd", ignoreCase = true)) {
                        verifiedBitrateKbps = 5644 // DSD 2.8MHz
                    } else if (fileName.endsWith(".wav", ignoreCase = true) || format.contains("wav", ignoreCase = true)) {
                        verifiedBitrateKbps = 2116 // Uncompressed WAV
                    }
                    break
                }
            }

            // If no streamable lossless file is found, reject this result completely
            if (verifiedFileName == null) {
                return null
            }

            // Construct direct stream URL: https://archive.org/download/<identifier>/<file_name>
            val encodedFileName = URLEncoder.encode(verifiedFileName, "UTF-8").replace("+", "%20")
            val streamUrl = "https://archive.org/download/$identifier/$encodedFileName"

            // Thumbnail / Cover Art resolution
            val coverUrl = "https://archive.org/services/img/$identifier"
            val durationMs = if (verifiedDurationSec > 0) verifiedDurationSec * 1000L else 240000L

            return MusicTrack(
                id = "ia_$identifier",
                title = title,
                artist = creator,
                album = album,
                durationMs = durationMs,
                coverUrl = coverUrl,
                audioUrl = streamUrl,
                bitrateKbps = verifiedBitrateKbps,
                qualityBadge = "Hi-Res Lossless",
                genre = "Hi-Res Audio",
                year = year,
                source = "INTERNET_ARCHIVE",
                isLossless = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to verify metadata for $identifier: ${e.message}")
            return null
        }
    }
}
