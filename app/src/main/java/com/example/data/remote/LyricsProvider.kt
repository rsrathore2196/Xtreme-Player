package com.example.data.remote

import android.util.Base64
import android.util.Log
import com.example.XtremeMusicApp
import com.example.data.model.LyricLine
import com.example.data.model.LyricSyllable
import com.example.data.model.LyricWord
import com.example.data.model.MusicTrack
import com.example.data.model.SongIdentity
import com.example.data.model.TrackLyrics
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object LyricsProvider {
    private const val TAG = "LyricsProvider"
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val LRC_REGEX = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2}(?:\\.\\d{1,3})?)\\](.*)")

    // In-memory cache for parsed lyrics (0ms instant access)
    private val lyricsCache = ConcurrentHashMap<String, TrackLyrics>()

    private fun getDiskCacheDir(): File? {
        return try {
            val app = XtremeMusicApp.instance
            val dir = File(app.cacheDir, "lyrics_cache")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir
        } catch (_: Exception) {
            null
        }
    }

    private fun safeHash(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        }
    }

    private fun normalizeForLookup(text: String): String {
        return normalizeForSimilarity(text).replace(" ", "_")
    }

    private fun loadFromDisk(file: File): TrackLyrics? {
        if (!file.exists() || !file.canRead() || file.length() <= 0) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val trackId = json.optString("trackId", "")
            val title = json.optString("title", "")
            val artist = json.optString("artist", "")
            val isSynced = json.optBoolean("isSynced", false)
            val provider = json.optString("provider", "")
            val language = json.optString("language", "").ifBlank { null }
            val hasWordTiming = json.optBoolean("hasWordTiming", false)
            val hasTranslation = json.optBoolean("hasTranslation", false)
            val hasRomanization = json.optBoolean("hasRomanization", false)
            val linesArray = json.optJSONArray("lines") ?: JSONArray()
            val lines = mutableListOf<LyricLine>()
            for (i in 0 until linesArray.length()) {
                val item = linesArray.getJSONObject(i)
                val wordsArray = item.optJSONArray("w")
                val words = mutableListOf<LyricWord>()
                if (wordsArray != null) {
                    for (j in 0 until wordsArray.length()) {
                        val wObj = wordsArray.getJSONObject(j)
                        val sylArray = wObj.optJSONArray("s")
                        val syllables = mutableListOf<LyricSyllable>()
                        if (sylArray != null) {
                            for (k in 0 until sylArray.length()) {
                                val sObj = sylArray.getJSONObject(k)
                                syllables.add(
                                    LyricSyllable(
                                        timestampMs = sObj.optLong("t", 0L),
                                        durationMs = sObj.optLong("d", 0L),
                                        text = sObj.optString("x", "")
                                    )
                                )
                            }
                        }
                        words.add(
                            LyricWord(
                                timestampMs = wObj.optLong("t", 0L),
                                durationMs = wObj.optLong("d", 0L),
                                text = wObj.optString("x", ""),
                                syllables = syllables
                            )
                        )
                    }
                }
                lines.add(
                    LyricLine(
                        timestampMs = item.optLong("t", 0L),
                        text = item.optString("x", ""),
                        durationMs = item.optLong("d", 3000L),
                        words = words,
                        translation = item.optString("tr", "").ifBlank { null },
                        romanization = item.optString("ro", "").ifBlank { null }
                    )
                )
            }
            if (lines.isNotEmpty()) {
                TrackLyrics(
                    trackId = trackId,
                    title = title,
                    artist = artist,
                    isSynced = isSynced,
                    lines = lines,
                    provider = provider,
                    language = language,
                    hasWordTiming = hasWordTiming,
                    hasTranslation = hasTranslation,
                    hasRomanization = hasRomanization
                )
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "Error reading cached lyrics from disk: ${e.message}")
            null
        }
    }

    private fun getCachedLyrics(identity: SongIdentity, cleanTitle: String, primaryArtist: String): TrackLyrics? {
        val trackId = identity.trackId ?: ""
        if (trackId.isNotBlank()) {
            lyricsCache[trackId]?.let {
                if (it.lines.isNotEmpty()) return it
            }
        }

        val dir = getDiskCacheDir() ?: return null

        if (trackId.isNotBlank()) {
            val trackFile = File(dir, "trk_${safeHash(trackId)}.json")
            loadFromDisk(trackFile)?.let {
                lyricsCache[trackId] = it
                Log.i(TAG, "Hit disk lyrics cache for '${identity.title}' (track ID)")
                return it
            }
        }

        val isrcKey = identity.isrc?.trim()?.uppercase() ?: ""
        if (isrcKey.isNotBlank()) {
            val isrcFile = File(dir, "isrc_${safeHash(isrcKey)}.json")
            loadFromDisk(isrcFile)?.let {
                val mapped = if (trackId.isNotBlank()) it.copy(trackId = trackId) else it
                if (trackId.isNotBlank()) lyricsCache[trackId] = mapped
                Log.i(TAG, "Hit disk lyrics cache for '${identity.title}' (ISRC $isrcKey)")
                return mapped
            }
        }

        val metaKey = "${normalizeForLookup(cleanTitle)}_${normalizeForLookup(primaryArtist)}"
        if (metaKey.length > 2) {
            val metaFile = File(dir, "meta_${safeHash(metaKey)}.json")
            loadFromDisk(metaFile)?.let {
                val mapped = if (trackId.isNotBlank()) it.copy(trackId = trackId) else it
                if (trackId.isNotBlank()) lyricsCache[trackId] = mapped
                Log.i(TAG, "Hit disk lyrics cache for '${identity.title}' (metadata)")
                return mapped
            }
        }

        return null
    }

    private fun persistLyrics(
        identity: SongIdentity,
        cleanTitle: String,
        primaryArtist: String,
        lyrics: TrackLyrics,
        isrc: String = ""
    ) {
        val trackId = identity.trackId ?: lyrics.trackId
        if (trackId.isNotBlank()) {
            lyricsCache[trackId] = lyrics
        }
        if (lyrics.lines.isEmpty()) return

        val dir = getDiskCacheDir() ?: return
        try {
            val json = JSONObject()
            json.put("trackId", trackId)
            json.put("title", lyrics.title)
            json.put("artist", lyrics.artist)
            json.put("isSynced", lyrics.isSynced)
            json.put("provider", lyrics.provider)
            json.put("language", lyrics.language ?: "")
            json.put("hasWordTiming", lyrics.hasWordTiming)
            json.put("hasTranslation", lyrics.hasTranslation)
            json.put("hasRomanization", lyrics.hasRomanization)
            json.put("cachedAt", System.currentTimeMillis())

            val linesArray = JSONArray()
            for (line in lyrics.lines) {
                val lineObj = JSONObject()
                lineObj.put("t", line.timestampMs)
                lineObj.put("x", line.text)
                lineObj.put("d", line.durationMs)
                if (!line.translation.isNullOrBlank()) lineObj.put("tr", line.translation)
                if (!line.romanization.isNullOrBlank()) lineObj.put("ro", line.romanization)

                if (line.words.isNotEmpty()) {
                    val wArray = JSONArray()
                    for (w in line.words) {
                        val wObj = JSONObject()
                        wObj.put("t", w.timestampMs)
                        wObj.put("d", w.durationMs)
                        wObj.put("x", w.text)
                        if (w.syllables.isNotEmpty()) {
                            val sArray = JSONArray()
                            for (s in w.syllables) {
                                val sObj = JSONObject()
                                sObj.put("t", s.timestampMs)
                                sObj.put("d", s.durationMs)
                                sObj.put("x", s.text)
                                sArray.put(sObj)
                            }
                            wObj.put("s", sArray)
                        }
                        wArray.put(wObj)
                    }
                    lineObj.put("w", wArray)
                }
                linesArray.put(lineObj)
            }
            json.put("lines", linesArray)

            val content = json.toString()

            if (trackId.isNotBlank()) {
                val trackFile = File(dir, "trk_${safeHash(trackId)}.json")
                trackFile.writeText(content, Charsets.UTF_8)
            }

            val effectiveIsrc = isrc.ifBlank { identity.isrc ?: "" }.trim().uppercase()
            if (effectiveIsrc.isNotBlank()) {
                val isrcFile = File(dir, "isrc_${safeHash(effectiveIsrc)}.json")
                isrcFile.writeText(content, Charsets.UTF_8)
            }

            val metaKey = "${normalizeForLookup(cleanTitle)}_${normalizeForLookup(primaryArtist)}"
            if (metaKey.length > 2) {
                val metaFile = File(dir, "meta_${safeHash(metaKey)}.json")
                metaFile.writeText(content, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error saving lyrics to disk cache: ${e.message}")
        }
    }

    @Synchronized
    fun clearCache() {
        lyricsCache.clear()
        try {
            val dir = getDiskCacheDir()
            dir?.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
        Log.i(TAG, "Lyrics cache cleared successfully")
    }

    @Synchronized
    fun clearCacheForTrack(trackId: String) {
        lyricsCache.remove(trackId)
        try {
            val dir = getDiskCacheDir()
            val file = File(dir, "trk_${safeHash(trackId)}.json")
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
        Log.d(TAG, "Cleared lyrics cache for track: $trackId")
    }

    @Synchronized
    fun getCacheSize(): Long {
        var size = 0L
        try {
            val dir = getDiskCacheDir()
            dir?.listFiles()?.forEach {
                size += it.length()
            }
        } catch (_: Exception) {}
        return size.coerceAtLeast(lyricsCache.size * 512L)
    }

    @Synchronized
    fun getCacheCount(): Int {
        var count = lyricsCache.size
        try {
            val dir = getDiskCacheDir()
            val fileCount = dir?.list()?.count { it.startsWith("trk_") } ?: 0
            count = maxOf(count, fileCount)
        } catch (_: Exception) {}
        return count
    }

    private fun JSONObject.getCleanString(key: String): String {
        if (isNull(key)) return ""
        val str = optString(key, "").trim()
        return if (str.equals("null", ignoreCase = true)) "" else str
    }

    data class LrcCandidate(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSec: Int,
        val syncedLyrics: String,
        val plainLyrics: String,
        val score: Int = 0
    )

    suspend fun getLyricsForTrack(track: MusicTrack): TrackLyrics = withContext(Dispatchers.IO) {
        getLyricsForIdentity(SongIdentity.from(track), track)
    }

    suspend fun getLyricsForIdentity(identity: SongIdentity, rawTrack: MusicTrack? = null): TrackLyrics = withContext(Dispatchers.IO) {
        val (cleanTitle, coreTitle) = sanitizeTitle(identity.title)
        val (primaryArtist, allArtists) = sanitizeArtist(identity.artist ?: "", rawTrack?.singers ?: "", rawTrack?.writer ?: "")
        val targetDurationSec = ((identity.durationMs ?: rawTrack?.durationMs ?: 0L) / 1000L).toInt()
        val trackId = identity.trackId ?: ""

        // 1. Check local persistent cache (in-memory + disk, 0ms fast)
        getCachedLyrics(identity, cleanTitle, primaryArtist)?.let {
            if (it.lines.isNotEmpty()) return@withContext it
        }

        Log.d(TAG, "Fetching lyrics for '${identity.title}' (clean='$cleanTitle', core='$coreTitle', artists=$allArtists, dur=${targetDurationSec}s)")

        // =========================================================================
        // STRICT 3-TIER MULTI-PROVIDER HIERARCHY WITH WRONG-SONG PROTECTION:
        // 1 — Better Lyrics (PRIMARY rich-lyrics source: TTML, word/syllable/char timing)
        // 2 — BiniLyrics (SECONDARY fallback: Apple Music / Musixmatch rich word sync)
        // 3 — LRCLIB (THIRD fallback: verified line/synced lyrics)
        // =========================================================================

        // 1 — Better Lyrics (PRIMARY)
        try {
            val betterLyrics = fetchBetterLyrics(
                identity = identity,
                cleanTitle = cleanTitle,
                coreTitle = coreTitle,
                primaryArtist = primaryArtist,
                allArtists = allArtists,
                targetDurationSec = targetDurationSec
            )
            if (betterLyrics != null && betterLyrics.lines.isNotEmpty()) {
                Log.i(TAG, "1st Preference (BetterLyrics) verified and matched for '${identity.title}'")
                persistLyrics(identity, cleanTitle, primaryArtist, betterLyrics, identity.isrc ?: "")
                return@withContext betterLyrics
            }
        } catch (e: Exception) {
            Log.w(TAG, "1st preference BetterLyrics attempt failed: ${e.message}, falling back to BiniLyrics")
        }

        // 2 — BiniLyrics (SECONDARY FALLBACK)
        try {
            val biniLyrics = fetchBiniLyrics(
                identity = identity,
                cleanTitle = cleanTitle,
                coreTitle = coreTitle,
                primaryArtist = primaryArtist,
                allArtists = allArtists,
                targetDurationSec = targetDurationSec
            )
            if (biniLyrics != null && biniLyrics.lines.isNotEmpty()) {
                Log.i(TAG, "2nd Preference (BiniLyrics) verified and matched for '${identity.title}'")
                persistLyrics(identity, cleanTitle, primaryArtist, biniLyrics, identity.isrc ?: "")
                return@withContext biniLyrics
            }
        } catch (e: Exception) {
            Log.w(TAG, "2nd preference BiniLyrics attempt failed: ${e.message}, falling back to LRCLIB")
        }

        // 3 — LRCLIB (THIRD FALLBACK)
        try {
            val lrclibLyrics = fetchLrcLibFast(
                identity = identity,
                cleanTitle = cleanTitle,
                coreTitle = coreTitle,
                primaryArtist = primaryArtist,
                allArtists = allArtists,
                targetDurationSec = targetDurationSec,
                rawTrack = rawTrack
            )
            if (lrclibLyrics != null && lrclibLyrics.lines.isNotEmpty()) {
                Log.i(TAG, "3rd Preference (LRCLIB) verified and matched for '${identity.title}'")
                persistLyrics(identity, cleanTitle, primaryArtist, lrclibLyrics, identity.isrc ?: "")
                return@withContext lrclibLyrics
            }
        } catch (e: Exception) {
            Log.w(TAG, "3rd preference LRCLIB fast lyrics fetch failed: ${e.message}")
        }

        // 3.b ISRC Query fallback on LRCLIB
        var effectiveIsrc = identity.isrc?.trim() ?: ""
        if (effectiveIsrc.isBlank() && trackId.startsWith("online_")) {
            val rawId = trackId.removePrefix("online_")
            effectiveIsrc = OnlineMusicApiService.fetchSongIsrc(rawId)?.trim() ?: ""
        }
        if (effectiveIsrc.isNotBlank()) {
            try {
                val isrcLyrics = fetchLrcLibByIsrc(identity, effectiveIsrc, cleanTitle, coreTitle, primaryArtist, allArtists, targetDurationSec)
                if (isrcLyrics != null && isrcLyrics.lines.isNotEmpty()) {
                    Log.i(TAG, "3rd Preference LRCLIB (ISRC $effectiveIsrc) matched for '${identity.title}'")
                    persistLyrics(identity, cleanTitle, primaryArtist, isrcLyrics, effectiveIsrc)
                    return@withContext isrcLyrics
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB ISRC query failed: ${e.message}")
            }
        }

        // 4. Final Protection: If no sufficiently confident result exists -> Lyrics unavailable
        // (Correctness is more important than availability. Never show wrong-song lyrics!)
        val notAvailable = TrackLyrics(
            trackId = trackId,
            title = identity.title,
            artist = identity.artist ?: "",
            isSynced = false,
            lines = emptyList()
        )
        if (trackId.isNotBlank()) {
            lyricsCache[trackId] = notAvailable
        }
        return@withContext notAvailable
    }

    /**
     * Sanitizes raw track titles: removes bracketed text, parenthetical tags,
     * video/audio markers, featured artist tags, movie soundtrack tags, and quality indicators.
     */
    fun sanitizeTitle(rawTitle: String): Pair<String, String> {
        var title = rawTitle.trim()

        // 1. Remove bracketed noise: [Official Video], [HD], [4K], [Audio], [1080p], etc.
        title = title.replace(Regex("\\[.*?\\]"), " ")

        // 2. Remove common parenthetical noise (Official Video, From Movie, Remastered, Feat, etc.)
        val parenNoise = Regex(
            "(?i)\\((?:from\\b|feat\\b|ft\\b|official|lyric|music\\s*video|video|audio|remix|remaster|deluxe|bonus|live|acoustic|radio|club|slowed|reverb|ost|soundtrack|theme).*?\\)"
        )
        title = title.replace(parenNoise, " ")

        // 3. Remove any remaining (From "...") or (From '...') tags
        title = title.replace(Regex("(?i)\\(\\s*from\\s+[\"'].*?[\"']\\s*\\)"), " ")
        title = title.replace(Regex("(?i)\\(\\s*from\\s+.*?\\)"), " ")

        // 4. Remove common trailing noise after separators: " | ", " • ", " — ", " – ", " - "
        val splitDelimiters = listOf(" | ", " • ", " — ", " – ", " - ")
        for (delim in splitDelimiters) {
            if (title.contains(delim)) {
                val parts = title.split(delim)
                val firstPart = parts[0].trim()
                val secondPart = parts.getOrNull(1)?.trim() ?: ""
                if (secondPart.contains(Regex("(?i)(official|video|audio|lyrics|t-series|remaster|hd|4k|cover|slowed|reverb|full\\s*song)"))) {
                    title = firstPart
                }
            }
        }

        // 5. Clean quotes and collapse whitespace
        title = title.replace(Regex("[\"“”'’]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Extract core title (before any dash or remaining parentheses)
        var coreTitle = title
        if (coreTitle.contains(" - ")) {
            coreTitle = coreTitle.substringBefore(" - ").trim()
        }
        if (coreTitle.contains("(")) {
            coreTitle = coreTitle.substringBefore("(").trim()
        }
        coreTitle = coreTitle.replace(Regex("\\s+"), " ").trim()

        if (title.isBlank()) title = rawTitle.trim()
        if (coreTitle.isBlank()) coreTitle = title

        return Pair(title, coreTitle)
    }

    /**
     * Extracts clean primary artist and list of all individual artists.
     */
    /**
     * Extracts clean primary artist and list of all individual artists and singers.
     */
    fun sanitizeArtist(rawArtist: String, rawSingers: String = "", rawWriter: String = ""): Pair<String, List<String>> {
        val candidates = mutableListOf<String>()
        val sources = listOf(rawArtist, rawSingers, rawWriter).filter { it.isNotBlank() }
        for (src in sources) {
            val parts = src.split(
                Regex("(?i)\\s*(?:,|&|feat\\.?|ft\\.?|featuring|with|•|/|;|\\+|\\bx\\b)\\s*")
            ).map {
                it.replace(Regex("[\"“”'’\\[\\]()]"), "").trim()
            }.filter { it.isNotBlank() }
            for (p in parts) {
                if (!candidates.contains(p)) candidates.add(p)
            }
        }

        // If rawArtist itself wasn't empty, try to pick the first token as primary
        val primary = candidates.firstOrNull() ?: rawArtist.trim()
        return Pair(primary, candidates)
    }

    fun parseLrc(trackId: String, title: String, artist: String, lrcText: String, provider: String = "LRCLIB"): TrackLyrics {
        val lines = mutableListOf<LyricLine>()
        val rawLines = lrcText.lines()

        for (line in rawLines) {
            val matcher = LRC_REGEX.matcher(line.trim())
            if (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val secStr = matcher.group(2) ?: "0"
                val secParts = secStr.split(".")
                val sec = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) {
                    val frac = secParts[1].padEnd(3, '0').take(3)
                    frac.toLongOrNull() ?: 0L
                } else 0L

                val totalMs = (min * 60 * 1000L) + (sec * 1000L) + ms
                val text = matcher.group(3)?.trim() ?: ""
                if (text.isNotBlank()) {
                    lines.add(LyricLine(timestampMs = totalMs, text = text))
                }
            }
        }

        lines.sortBy { it.timestampMs }

        // Approximate line vocal duration for standard .lrc lines (where only line start exists)
        // Never stretch line vocals to the next line's start; leave a silent gap before next line.
        val timedLines = lines.mapIndexed { index, item ->
            val wordCount = item.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size.coerceAtLeast(1)
            val duration = if (index < lines.lastIndex) {
                val gapToNext = lines[index + 1].timestampMs - item.timestampMs
                val estimatedSinging = (wordCount * 380L).coerceAtLeast(1000L)
                if (gapToNext > estimatedSinging + 600L) {
                    estimatedSinging
                } else {
                    (gapToNext - 400L).coerceIn(600L, gapToNext)
                }
            } else {
                (wordCount * 450L).coerceIn(2000L, 5000L)
            }
            item.copy(
                durationMs = duration,
                words = emptyList(), // Never silently fabricate word timestamps for pure LRC
                isReliableDuration = false // Explicitly approximate fallback
            )
        }

        return TrackLyrics(
            trackId = trackId,
            title = title,
            artist = artist,
            isSynced = timedLines.isNotEmpty(),
            lines = timedLines,
            provider = provider,
            hasWordTiming = false,
            hasSyllableTiming = false
        )
    }

    fun parseTtmlTimestamp(raw: String): Long = BetterLyricsParser.parseTtmlTimestamp(raw)

    fun parseTtml(trackId: String, title: String, artist: String, ttml: String): TrackLyrics? {
        val identity = SongIdentity(trackId = trackId, title = title, artist = artist)
        return BetterLyricsParser.parseTtml(identity, ttml)
    }

    /**
     * Primary Provider: BetterLyrics API (Rich TTML with line, word, syllable, char timing)
     */
    private suspend fun fetchBetterLyrics(
        identity: SongIdentity,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        targetDurationSec: Int
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        val baseUrls = listOf(
            "https://lyrics-api.boidu.dev/getLyrics",
            "https://api.betterlyrics.org/getLyrics"
        )
        val queries = mutableListOf<Pair<String, String>>()
        queries.add(Pair(cleanTitle, primaryArtist))
        if (coreTitle != cleanTitle && coreTitle.length >= 2) {
            queries.add(Pair(coreTitle, primaryArtist))
        }

        for (baseUrl in baseUrls) {
            for ((title, artist) in queries) {
                try {
                    val encodedTitle = URLEncoder.encode(title, "UTF-8")
                    val encodedArtist = URLEncoder.encode(artist, "UTF-8")
                    val url = "$baseUrl?s=$encodedTitle&a=$encodedArtist"

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile)")
                        .header("Accept", "application/json")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string() ?: return@use null
                        val json = JSONObject(body)
                        val ttml = json.optString("ttml", "")
                        if (ttml.isNotBlank()) {
                            val parsed = BetterLyricsParser.parseTtml(identity, ttml)
                            if (parsed != null && parsed.lines.isNotEmpty()) {
                                Log.i(TAG, "Fetched ${parsed.lines.size} lines from BetterLyrics (TTML) for '${identity.title}'")
                                return@withContext parsed
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "BetterLyrics attempt failed ($baseUrl): ${e.message}")
                }
            }
        }
        null
    }

    /**
     * Secondary Provider (Fallback 1): BiniLyrics API (LyricsPlus / Apple Music & Musixmatch rich word sync)
     */
    private suspend fun fetchBiniLyrics(
        identity: SongIdentity,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        targetDurationSec: Int
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        val baseUrls = listOf(
            "https://lyricsplus.binimum.org/v2/lyrics/get",
            "https://lyricsplus.atomix.one/v2/lyrics/get",
            "https://lyricsplus-seven.vercel.app/v2/lyrics/get",
            "https://lyricsplus.prjktla.workers.dev/v2/lyrics/get"
        )
        val queries = mutableListOf<Pair<String, String>>()
        queries.add(Pair(cleanTitle, primaryArtist))
        if (coreTitle != cleanTitle && coreTitle.length >= 2) {
            queries.add(Pair(coreTitle, primaryArtist))
        }

        for (baseUrl in baseUrls) {
            for ((title, artist) in queries) {
                try {
                    val encodedTitle = URLEncoder.encode(title, "UTF-8")
                    val encodedArtist = URLEncoder.encode(artist, "UTF-8")
                    val durParam = if ((identity.durationMs ?: 0L) > 0) "&duration=${identity.durationMs}" else ""
                    val url = "$baseUrl?title=$encodedTitle&artist=$encodedArtist$durParam&source=apple,lyricsplus,musixmatch,spotify,musixmatch-word"

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile)")
                        .header("Accept", "application/json")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string() ?: return@use null
                        val json = JSONObject(body)

                        // 1. Candidate validation from metadata
                        val metadata = json.optJSONObject("metadata")
                        if (metadata != null) {
                            val candTitle = metadata.optString("title", "").trim()
                            val candArtist = metadata.optString("artist", "").trim()
                            val totalDurStr = metadata.optString("totalDuration", "").trim()
                            val candDurSec = if (totalDurStr.isNotBlank()) (BetterLyricsParser.parseTtmlTimestamp(totalDurStr) / 1000L).toInt() else 0

                            if (candTitle.isNotBlank()) {
                                val valid = LyricsValidation.validateCandidate(
                                    candidateTitle = candTitle,
                                    candidateArtist = candArtist,
                                    candidateDurationSec = candDurSec,
                                    identity = identity,
                                    cleanTitle = cleanTitle,
                                    coreTitle = coreTitle,
                                    primaryArtist = primaryArtist,
                                    allArtists = allArtists
                                )
                                if (!valid) {
                                    Log.d(TAG, "BiniLyrics candidate '$candTitle' rejected for '${identity.title}'")
                                    return@use null
                                }
                            }
                        }

                        val lyricsArray = json.optJSONArray("lyrics") ?: return@use null
                        if (lyricsArray.length() == 0) return@use null

                        val lines = mutableListOf<LyricLine>()
                        var hasWordTiming = false
                        var hasTranslation = false
                        var hasRomanization = false

                        for (i in 0 until lyricsArray.length()) {
                            val lineObj = lyricsArray.getJSONObject(i)
                            val timeMs = lineObj.optLong("time", 0L)
                            val durMs = lineObj.optLong("duration", 0L)
                            val text = lineObj.optString("text", "").trim()
                            if (text.isBlank()) continue

                            val translation = lineObj.optString("translation", "").ifBlank { null }
                            val romanization = lineObj.optString("romanization", "").ifBlank { null }
                            if (translation != null) hasTranslation = true
                            if (romanization != null) hasRomanization = true

                            val words = mutableListOf<LyricWord>()
                            val syllabusArray = lineObj.optJSONArray("syllabus")
                            if (syllabusArray != null && syllabusArray.length() > 0) {
                                val currentSyllables = mutableListOf<LyricSyllable>()
                                val wordText = StringBuilder()
                                var wordStartMs = 0L
                                var wordEndMs = 0L

                                for (j in 0 until syllabusArray.length()) {
                                    val sObj = syllabusArray.getJSONObject(j)
                                    val sTime = sObj.optLong("time", 0L)
                                    val sDur = sObj.optLong("duration", 0L)
                                    val rawText = sObj.optString("text", "")
                                    val endsWithSpace = rawText.endsWith(" ")
                                    val cleanS = rawText.trim()
                                    if (cleanS.isEmpty()) continue

                                    if (currentSyllables.isEmpty()) {
                                        wordStartMs = sTime
                                    }
                                    wordEndMs = sTime + sDur
                                    wordText.append(cleanS)
                                    currentSyllables.add(LyricSyllable(sTime, sDur, cleanS))

                                    if (endsWithSpace) {
                                        words.add(
                                            LyricWord(
                                                timestampMs = wordStartMs,
                                                durationMs = (wordEndMs - wordStartMs).coerceAtLeast(50L),
                                                text = wordText.toString(),
                                                syllables = currentSyllables.toList()
                                            )
                                        )
                                        currentSyllables.clear()
                                        wordText.clear()
                                    }
                                }

                                if (currentSyllables.isNotEmpty()) {
                                    words.add(
                                        LyricWord(
                                            timestampMs = wordStartMs,
                                            durationMs = (wordEndMs - wordStartMs).coerceAtLeast(50L),
                                            text = wordText.toString(),
                                            syllables = currentSyllables.toList()
                                        )
                                    )
                                }
                            }

                            if (words.isNotEmpty()) {
                                hasWordTiming = true
                            }

                            lines.add(
                                LyricLine(
                                    timestampMs = timeMs,
                                    text = text,
                                    durationMs = if (durMs > 0) durMs else 3000L,
                                    words = words,
                                    translation = translation,
                                    romanization = romanization
                                )
                            )
                        }

                        if (lines.isNotEmpty()) {
                            Log.i(TAG, "Fetched ${lines.size} lines from BiniLyrics for '${identity.title}'")
                            val rawLyrics = TrackLyrics(
                                trackId = identity.trackId ?: "",
                                title = identity.title,
                                artist = identity.artist ?: "",
                                isSynced = true,
                                lines = lines,
                                provider = "BiniLyrics",
                                language = metadata?.optString("language", "")?.ifBlank { null },
                                hasWordTiming = hasWordTiming,
                                hasTranslation = hasTranslation,
                                hasRomanization = hasRomanization
                            )
                            return@withContext SyllableSynthesizer.enrichLyrics(rawLyrics)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "BiniLyrics attempt failed ($baseUrl): ${e.message}")
                }
            }
        }
        null
    }

    /**
     * High-speed parallel LRCLIB engine:
     * Dispatches concurrent asynchronous network requests across LRCLIB endpoints:
     * 1. Full-text search (q=...) - Exact mechanism used by lrclib.net web interface
     * 2. Direct exact GET (/api/get?track_name=...&artist_name=...)
     * 3. Dedicated field search (/api/search?track_name=...&artist_name=...)
     * 4. Core title search (when title contains subtitles or featured artist noise)
     * 5. ISRC query (if available on track)
     * Collects and evaluates all candidates concurrently, prioritizing verified synchronized lyrics.
     */
    private suspend fun fetchLrcLibFast(
        identity: SongIdentity,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        targetDurationSec: Int,
        rawTrack: MusicTrack? = null
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        val artistCandidates = mutableListOf<String>()
        if (primaryArtist.isNotBlank()) artistCandidates.add(primaryArtist)
        for (a in allArtists) {
            if (a.isNotBlank() && !artistCandidates.contains(a)) artistCandidates.add(a)
        }
        val rawSingers = rawTrack?.singers ?: ""
        if (rawSingers.isNotBlank()) {
            val singerTokens = rawSingers.split(Regex("(?i)\\s*(?:,|&|feat\\.?|ft\\.?|/|;)\\s*")).map { it.trim() }
            for (s in singerTokens) {
                if (s.isNotBlank() && !artistCandidates.contains(s)) artistCandidates.add(0, s)
            }
        }

        val deferredList = mutableListOf<Deferred<List<LrcCandidate>>>()

        // 1. Full-text search (q=...) - Exact mechanism used by lrclib.net web interface
        if (cleanTitle.isNotBlank()) {
            val q = if (primaryArtist.isNotBlank()) "$cleanTitle $primaryArtist" else cleanTitle
            deferredList.add(async {
                executeLrcLibSearch("q=${urlEncode(q)}")
            })
        }

        // 2. Direct GET by title and primary artist
        if (cleanTitle.isNotBlank() && primaryArtist.isNotBlank()) {
            deferredList.add(async {
                val direct = executeLrcLibGet(cleanTitle, primaryArtist, cleanTitle, coreTitle, allArtists)
                if (direct != null && (direct.first.isNotBlank() || direct.second.isNotBlank())) {
                    listOf(
                        LrcCandidate(
                            id = 1L,
                            trackName = cleanTitle,
                            artistName = primaryArtist,
                            albumName = "",
                            durationSec = targetDurationSec,
                            syncedLyrics = direct.first,
                            plainLyrics = direct.second
                        )
                    )
                } else emptyList()
            })
        }

        // 3. Dedicated field search (/api/search?track_name=...&artist_name=...)
        if (cleanTitle.isNotBlank() && primaryArtist.isNotBlank()) {
            deferredList.add(async {
                executeLrcLibSearch("track_name=${urlEncode(cleanTitle)}&artist_name=${urlEncode(primaryArtist)}")
            })
        }

        // 4. Core title search if different (e.g. "Lover (Moonchild Era)" -> "Lover")
        if (coreTitle != cleanTitle && coreTitle.isNotBlank()) {
            val qCore = if (primaryArtist.isNotBlank()) "$coreTitle $primaryArtist" else coreTitle
            deferredList.add(async {
                executeLrcLibSearch("q=${urlEncode(qCore)}")
            })
        }

        // 5. ISRC query if available
        val effectiveIsrc = identity.isrc?.trim() ?: ""
        if (effectiveIsrc.isNotBlank()) {
            deferredList.add(async {
                val isrcCand = executeLrcLibGetByIsrc(effectiveIsrc, cleanTitle, coreTitle, primaryArtist, allArtists)
                if (isrcCand != null) listOf(isrcCand) else emptyList()
            })
        }

        val allFoundCandidates = mutableListOf<LrcCandidate>()
        for (deferred in deferredList) {
            try {
                allFoundCandidates.addAll(deferred.await())
            } catch (e: Exception) {
                Log.d(TAG, "Parallel LRCLIB query failed: ${e.message}")
            }
        }

        // De-duplicate candidates by unique id / title+artist
        val distinctCandidates = allFoundCandidates.distinctBy {
            if (it.id > 1L) it.id.toString() else "${it.trackName.lowercase()}_${it.artistName.lowercase()}"
        }

        var best = findBestCandidate(
            candidates = distinctCandidates,
            cleanTitle = cleanTitle,
            coreTitle = coreTitle,
            primaryArtist = primaryArtist,
            allArtists = allArtists,
            targetDurationSec = targetDurationSec
        )

        val trackId = identity.trackId ?: ""

        // If candidate with synced lyrics is found, return immediately!
        if (best != null && best.syncedLyrics.isNotBlank()) {
            Log.i(TAG, "LRCLIB fast parallel match (synced) for '${identity.title}' (cand='${best.trackName}' by '${best.artistName}')")
            return@withContext parseLrc(trackId, identity.title, identity.artist ?: "", best.syncedLyrics)
        }

        // Fast Secondary Fallback: If no candidate found, search title-only and secondary artists
        if (best == null) {
            val secondaryJobs = mutableListOf<Deferred<List<LrcCandidate>>>()
            if (cleanTitle.isNotBlank()) {
                secondaryJobs.add(async { executeLrcLibSearch("q=${urlEncode(cleanTitle)}") })
            }
            if (coreTitle != cleanTitle && coreTitle.isNotBlank()) {
                secondaryJobs.add(async { executeLrcLibSearch("q=${urlEncode(coreTitle)}") })
            }
            for (artist in artistCandidates.drop(1).take(2)) {
                secondaryJobs.add(async { executeLrcLibSearch("q=${urlEncode("$cleanTitle $artist")}") })
            }

            for (sec in secondaryJobs) {
                try {
                    allFoundCandidates.addAll(sec.await())
                } catch (e: Exception) {
                    // ignore
                }
            }

            val secondDistinct = allFoundCandidates.distinctBy {
                if (it.id > 1L) it.id.toString() else "${it.trackName.lowercase()}_${it.artistName.lowercase()}"
            }
            best = findBestCandidate(
                candidates = secondDistinct,
                cleanTitle = cleanTitle,
                coreTitle = coreTitle,
                primaryArtist = primaryArtist,
                allArtists = allArtists,
                targetDurationSec = targetDurationSec
            )
        }

        if (best != null) {
            if (best.syncedLyrics.isNotBlank()) {
                Log.i(TAG, "LRCLIB matched synced lyrics for '${identity.title}' (cand='${best.trackName}', score=${best.score})")
                return@withContext parseLrc(trackId, identity.title, identity.artist ?: "", best.syncedLyrics)
            } else if (best.plainLyrics.isNotBlank()) {
                Log.i(TAG, "LRCLIB matched plain lyrics for '${identity.title}' (cand='${best.trackName}', score=${best.score})")
                return@withContext formatPlainLyrics(identity, best.plainLyrics)
            }
        }

        null
    }

    private fun executeLrcLibGetByIsrc(
        isrc: String,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>
    ): LrcCandidate? {
        val cleanIsrc = isrc.trim()
        if (cleanIsrc.isBlank()) return null
        return try {
            val directUrl = "https://lrclib.net/api/get?isrc=${urlEncode(cleanIsrc)}"
            val req = Request.Builder()
                .url(directUrl)
                .addHeader("User-Agent", "XtremeMusicApp/2.5 (Android; contact: app@xtreme.audio)")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            if (body.startsWith("{")) {
                val json = JSONObject(body)
                val candTrack = json.getCleanString("trackName").ifBlank { json.getCleanString("name") }
                val candArtist = json.getCleanString("artistName")
                val duration = json.optInt("duration", 0)
                val synced = json.getCleanString("syncedLyrics")
                val plain = json.getCleanString("plainLyrics")
                if (candTrack.isNotBlank() && (synced.isNotBlank() || plain.isNotBlank())) {
                    LrcCandidate(
                        id = json.optLong("id", 1L),
                        trackName = candTrack,
                        artistName = candArtist,
                        albumName = json.getCleanString("albumName"),
                        durationSec = duration,
                        syncedLyrics = synced,
                        plainLyrics = plain
                    )
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Direct query on LRCLIB using track ISRC (International Standard Recording Code).
     */
    private suspend fun fetchLrcLibByIsrc(
        identity: SongIdentity,
        isrc: String,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        targetDurationSec: Int
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        val cleanIsrc = isrc.trim()
        if (cleanIsrc.isBlank()) return@withContext null
        val trackId = identity.trackId ?: ""

        // 1. Direct GET query by ISRC
        try {
            val cand = executeLrcLibGetByIsrc(cleanIsrc, cleanTitle, coreTitle, primaryArtist, allArtists)
            if (cand != null) {
                val titleSim = evaluateTitleMatch(cand.trackName, cand.artistName, cleanTitle, coreTitle, allArtists)
                val artistMatch = cand.artistName.isBlank() || validateArtistMatch(cand.artistName, primaryArtist, allArtists)
                val durationValid = targetDurationSec <= 0 || cand.durationSec <= 0 || kotlin.math.abs(targetDurationSec - cand.durationSec) <= 45

                if ((titleSim >= 0.60 || artistMatch) && durationValid) {
                    if (cand.syncedLyrics.isNotBlank()) {
                        Log.i(TAG, "Matched synchronized lyrics on LRCLIB via direct ISRC '$cleanIsrc' for '${identity.title}'")
                        return@withContext parseLrc(trackId, identity.title, identity.artist ?: "", cand.syncedLyrics)
                    } else if (cand.plainLyrics.isNotBlank()) {
                        Log.i(TAG, "Matched plain lyrics on LRCLIB via direct ISRC '$cleanIsrc' for '${identity.title}'")
                        return@withContext formatPlainLyrics(identity, cand.plainLyrics)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct LRCLIB get by ISRC failed: ${e.message}")
        }

        // 2. Search query fallback by ISRC
        try {
            val candidates = executeLrcLibSearch("q=${urlEncode(cleanIsrc)}")
            for (cand in candidates) {
                if (targetDurationSec > 0 && cand.durationSec > 0) {
                    val diff = kotlin.math.abs(targetDurationSec - cand.durationSec)
                    if (diff > 45) continue
                }
                val titleSim = evaluateTitleMatch(cand.trackName, cand.artistName, cleanTitle, coreTitle, allArtists)
                val artistMatch = cand.artistName.isBlank() || validateArtistMatch(cand.artistName, primaryArtist, allArtists)
                if (titleSim >= 0.60 || artistMatch) {
                    if (cand.syncedLyrics.isNotBlank()) {
                        Log.i(TAG, "Matched synchronized lyrics on LRCLIB via search ISRC '$cleanIsrc' for '${identity.title}'")
                        return@withContext parseLrc(trackId, identity.title, identity.artist ?: "", cand.syncedLyrics)
                    } else if (cand.plainLyrics.isNotBlank()) {
                        Log.i(TAG, "Matched plain lyrics on LRCLIB via search ISRC '$cleanIsrc' for '${identity.title}'")
                        return@withContext formatPlainLyrics(identity, cand.plainLyrics)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "LRCLIB search by ISRC failed: ${e.message}")
        }
        null
    }

    private fun executeLrcLibGet(
        trackName: String,
        artistName: String,
        cleanTitle: String,
        coreTitle: String,
        allArtists: List<String>
    ): Pair<String, String>? {
        return try {
            val urlBuilder = StringBuilder("https://lrclib.net/api/get?")
                .append("track_name=").append(urlEncode(trackName))
                .append("&artist_name=").append(urlEncode(artistName))

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("User-Agent", "XtremeMusicApp/2.5 (Android; contact: app@xtreme.audio)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            if (body.startsWith("{")) {
                val json = JSONObject(body)
                val returnedTrackName = json.getCleanString("trackName").ifBlank { json.getCleanString("name") }
                val returnedArtistName = json.getCleanString("artistName")

                // Robust title matching
                if (returnedTrackName.isNotBlank()) {
                    val maxSim = evaluateTitleMatch(returnedTrackName, returnedArtistName, cleanTitle, coreTitle, allArtists)
                    if (maxSim < 0.60) {
                        Log.d(TAG, "executeLrcLibGet returned '$returnedTrackName' but similarity ${(maxSim * 100).toInt()}% is below threshold")
                        return null
                    }
                }

                // Multi-Artist Validation
                if (returnedArtistName.isNotBlank()) {
                    if (!validateArtistMatch(returnedArtistName, artistName, allArtists) &&
                        !returnedTrackName.contains(artistName, ignoreCase = true)
                    ) {
                        Log.d(TAG, "executeLrcLibGet rejected: returned artist '$returnedArtistName' doesn't match track artists ($allArtists)")
                        return null
                    }
                }

                val synced = json.getCleanString("syncedLyrics")
                val plain = json.getCleanString("plainLyrics")
                if (synced.isNotBlank() || plain.isNotBlank()) {
                    Pair(synced, plain)
                } else null
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "executeLrcLibGet failed: ${e.message}")
            null
        }
    }

    private fun executeLrcLibSearch(queryParams: String): List<LrcCandidate> {
        return try {
            val url = "https://lrclib.net/api/search?$queryParams"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "XtremeMusicApp/2.5 (Android; contact: app@xtreme.audio)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            if (body.startsWith("[")) {
                val array = JSONArray(body)
                val list = mutableListOf<LrcCandidate>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optLong("id", 0L)
                    val trackName = item.getCleanString("trackName").ifBlank { item.getCleanString("name") }
                    val artistName = item.getCleanString("artistName")
                    val albumName = item.getCleanString("albumName")
                    val duration = item.optDouble("duration", 0.0).toInt()
                    val synced = item.getCleanString("syncedLyrics")
                    val plain = item.getCleanString("plainLyrics")

                    if (trackName.isNotBlank() && (synced.isNotBlank() || plain.isNotBlank())) {
                        list.add(
                            LrcCandidate(
                                id = id,
                                trackName = trackName,
                                artistName = artistName,
                                albumName = albumName,
                                durationSec = duration,
                                syncedLyrics = synced,
                                plainLyrics = plain
                            )
                        )
                    }
                }
                list
            } else emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "executeLrcLibSearch failed: ${e.message}")
            emptyList()
        }
    }

    private fun findBestCandidate(
        candidates: List<LrcCandidate>,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        targetDurationSec: Int
    ): LrcCandidate? {
        if (candidates.isEmpty()) return null

        val scored = candidates.mapNotNull { cand ->
            val score = scoreCandidateWithMatching(
                candidate = cand,
                cleanTitle = cleanTitle,
                coreTitle = coreTitle,
                primaryArtist = primaryArtist,
                allArtists = allArtists,
                targetDurationSec = targetDurationSec
            ) ?: return@mapNotNull null

            cand.copy(score = score)
        }

        // Prioritize candidates with synced lyrics first, then by highest score
        return scored.sortedWith(
            compareByDescending<LrcCandidate> { it.syncedLyrics.isNotBlank() }
                .thenByDescending { it.score }
        ).firstOrNull()
    }

    private fun scoreCandidateWithMatching(
        candidate: LrcCandidate,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        targetDurationSec: Int
    ): Int? {
        if (candidate.syncedLyrics.isBlank() && candidate.plainLyrics.isBlank()) return null

        // 1. Title Similarity & Candidate Title Extraction
        val titleMatch = evaluateTitleMatch(
            candRawTitle = candidate.trackName,
            candArtist = candidate.artistName,
            cleanTitle = cleanTitle,
            coreTitle = coreTitle,
            allArtists = allArtists
        )
        if (titleMatch < 0.55) {
            Log.d(TAG, "LRCLIB candidate '${candidate.trackName}' discarded: title match ${(titleMatch * 100).toInt()}% < 55%")
            return null
        }

        // 2. Artist Validation
        val artistScore = evaluateArtistScore(
            candidateArtist = candidate.artistName,
            candidateTitle = candidate.trackName,
            primaryArtist = primaryArtist,
            allArtists = allArtists,
            titleMatch = titleMatch
        )
        if (artistScore == null) {
            Log.d(TAG, "LRCLIB candidate '${candidate.trackName}' by '${candidate.artistName}' discarded: artist mismatch")
            return null
        }

        // 3. Duration Sanity Check
        var durationScore = 8
        if (targetDurationSec > 30 && candidate.durationSec > 30) {
            val diff = kotlin.math.abs(targetDurationSec - candidate.durationSec)
            if (diff > 55 && (diff.toDouble() / targetDurationSec) > 0.35) {
                Log.d(TAG, "LRCLIB candidate '${candidate.trackName}' discarded: duration diff ${diff}s too large")
                return null
            }
            durationScore = when {
                diff <= 3 -> 15
                diff <= 8 -> 12
                diff <= 15 -> 9
                diff <= 30 -> 6
                else -> 2
            }
        }

        // 4. Bonus for Synchronized Lyrics
        val syncBonus = if (candidate.syncedLyrics.isNotBlank()) 30 else 0
        val titlePoints = (titleMatch * 60).toInt()

        return titlePoints + artistScore + durationScore + syncBonus
    }

    fun evaluateTitleMatch(
        candRawTitle: String,
        candArtist: String,
        cleanTitle: String,
        coreTitle: String,
        allArtists: List<String>
    ): Double {
        // Strip common tags
        var stripped = candRawTitle
            .replace(Regex("(?i)\\[.*?\\]"), " ")
            .replace(Regex("(?i)\\((?:from\\b|feat\\.?|ft\\.?|official|lyric|music\\s*video|video|audio|remix|remaster|deluxe|bonus|live|acoustic|radio|club|slowed|reverb|ost|soundtrack|theme|original).*?\\)"), " ")
            .replace(Regex("(?i)\\(\\s*from\\s+[\"'].*?[\"']\\s*\\)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Extract true song title if candidate title is "Artist - Title" or "Title - Artist" or "Artist: Title"
        val separators = listOf(" - ", " : ", " | ", " — ", " – ")
        for (sep in separators) {
            if (stripped.contains(sep)) {
                val parts = stripped.split(sep).map { it.trim() }.filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    val p0 = parts[0]
                    val p1 = parts[1]
                    if (validateArtistMatch(p0, candArtist, allArtists)) {
                        stripped = p1
                        break
                    }
                    if (validateArtistMatch(p1, candArtist, allArtists)) {
                        stripped = p0
                        break
                    }
                    if (calculateSimilarity(p0, cleanTitle) >= 0.70 || calculateSimilarity(p0, coreTitle) >= 0.70) {
                        stripped = p0
                        break
                    }
                    if (calculateSimilarity(p1, cleanTitle) >= 0.70 || calculateSimilarity(p1, coreTitle) >= 0.70) {
                        stripped = p1
                        break
                    }
                }
            }
        }

        val (cleanCand, coreCand) = sanitizeTitle(stripped)

        val simRaw = calculateSimilarity(candRawTitle, cleanTitle)
        val simStripped = calculateSimilarity(stripped, cleanTitle)
        val simClean = calculateSimilarity(cleanCand, cleanTitle)
        val simCore = calculateSimilarity(coreCand, coreTitle)
        val simCleanCore = calculateSimilarity(cleanCand, coreTitle)

        var bestSim = maxOf(simRaw, simStripped, simClean, simCore, simCleanCore)

        val normCore = normalizeForSimilarity(coreTitle)
        val normClean = normalizeForSimilarity(cleanTitle)
        val normCand = normalizeForSimilarity(cleanCand)
        val normStripped = normalizeForSimilarity(stripped)
        val normRaw = normalizeForSimilarity(candRawTitle)

        // Exact match
        if (normCand == normClean || normCand == normCore || normStripped == normClean || normStripped == normCore) {
            return 1.0
        }

        // Phrase containment
        if (normCore.length >= 3) {
            if (normCand.contains(normCore) || normStripped.contains(normCore) || normRaw.contains(normCore)) {
                bestSim = maxOf(bestSim, 0.92)
            }
            if (normCand.length >= 3 && normCore.contains(normCand)) {
                bestSim = maxOf(bestSim, 0.90)
            }
        }

        // Token overlap
        val coreTokens = normCore.split(" ").filter { it.length >= 2 }
        if (coreTokens.isNotEmpty()) {
            val candTokens = normRaw.split(" ").toSet()
            val matchCount = coreTokens.count { candTokens.contains(it) }
            val ratio = matchCount.toDouble() / coreTokens.size
            if (ratio >= 0.75) {
                bestSim = maxOf(bestSim, 0.88)
            } else if (ratio >= 0.50) {
                bestSim = maxOf(bestSim, 0.75)
            }
        }

        return bestSim
    }

    private fun evaluateArtistScore(
        candidateArtist: String,
        candidateTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        titleMatch: Double
    ): Int? {
        val normCandArtist = normalizeForSimilarity(candidateArtist)
        val normCandTitle = normalizeForSimilarity(candidateTitle)
        val normPrimary = normalizeForSimilarity(primaryArtist)

        // 1. Direct candidate artist match against primary artist
        if (normPrimary.length >= 3 && (normCandArtist.contains(normPrimary) || normPrimary.contains(normCandArtist))) {
            return 25
        }

        // 2. Candidate artist matches any in allArtists
        for (artist in allArtists) {
            val normA = normalizeForSimilarity(artist)
            if (normA.length >= 3 && (normCandArtist.contains(normA) || normA.contains(normCandArtist))) {
                return 22
            }
        }

        // 3. Candidate TITLE itself contains the artist name
        if (normPrimary.length >= 3 && normCandTitle.contains(normPrimary)) {
            return 25
        }
        for (artist in allArtists) {
            val normA = normalizeForSimilarity(artist)
            if (normA.length >= 3 && normCandTitle.contains(normA)) {
                return 22
            }
        }

        // 4. Token overlap between artists
        val candidateTokens = normCandArtist.split(" ").filter { it.length >= 3 }
        for (artist in allArtists) {
            val artistTokens = normalizeForSimilarity(artist).split(" ").filter { it.length >= 3 }
            if (candidateTokens.any { artistTokens.contains(it) }) {
                return 20
            }
        }

        // 5. If title match is very high (>= 0.85) and artist is blank
        if (candidateArtist.isBlank() && titleMatch >= 0.85) {
            return 15
        }

        // 6. Similarity between artists >= 0.65
        if (calculateSimilarity(candidateArtist, primaryArtist) >= 0.65) {
            return 18
        }
        for (artist in allArtists) {
            if (calculateSimilarity(candidateArtist, artist) >= 0.65) {
                return 18
            }
        }

        // If title match is near-perfect (>= 0.95), allow weak artist with lower score
        if (titleMatch >= 0.95) {
            return 10
        }

        return null
    }

    /**
     * Computes the Levenshtein distance between two strings.
     */
    fun levenshteinDistance(s1: CharSequence, s2: CharSequence): Int {
        val len1 = s1.length
        val len2 = s2.length
        var prev = IntArray(len2 + 1) { it }
        var curr = IntArray(len2 + 1)

        for (i in 1..len1) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..len2) {
                val c2 = s2[j - 1]
                val cost = if (c1 == c2) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,       // insertion
                    prev[j] + 1,           // deletion
                    prev[j - 1] + cost     // substitution
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[len2]
    }

    /**
     * Calculates string similarity score between 0.0 and 1.0 using Levenshtein distance.
     */
    fun calculateSimilarity(s1: String, s2: String): Double {
        val norm1 = normalizeForSimilarity(s1)
        val norm2 = normalizeForSimilarity(s2)
        if (norm1 == norm2) return 1.0
        if (norm1.isEmpty() || norm2.isEmpty()) return 0.0
        val maxLen = maxOf(norm1.length, norm2.length)
        val distance = levenshteinDistance(norm1, norm2)
        return (1.0 - (distance.toDouble() / maxLen)).coerceIn(0.0, 1.0)
    }

    fun validateTitleSimilarity(candidateTitle: String, cleanTitle: String, coreTitle: String): Double {
        return evaluateTitleMatch(candidateTitle, "", cleanTitle, coreTitle, emptyList())
    }

    fun validateArtistMatch(candidateArtist: String, primaryArtist: String, allArtists: List<String>): Boolean {
        val normCandidate = normalizeForSimilarity(candidateArtist)
        if (normCandidate.isBlank()) return false

        val normPrimary = normalizeForSimilarity(primaryArtist)
        if (normPrimary.length >= 3 && (normCandidate.contains(normPrimary) || normPrimary.contains(normCandidate))) {
            return true
        }

        for (artist in allArtists) {
            val normArtist = normalizeForSimilarity(artist)
            if (normArtist.length >= 3 && (normCandidate.contains(normArtist) || normArtist.contains(normCandidate))) {
                return true
            }
        }

        val candidateTokens = normCandidate.split(" ").filter { it.length >= 3 && !it.matches(Regex("^(the|and|feat|featuring|official|records|music|prod)$")) }
        for (artist in allArtists) {
            val artistTokens = normalizeForSimilarity(artist).split(" ").filter { it.length >= 3 && !it.matches(Regex("^(the|and|feat|featuring|official|records|music|prod)$")) }
            for (cTok in candidateTokens) {
                if (artistTokens.contains(cTok)) {
                    return true
                }
            }
        }

        if (calculateSimilarity(candidateArtist, primaryArtist) >= 0.70) {
            return true
        }
        for (artist in allArtists) {
            if (calculateSimilarity(candidateArtist, artist) >= 0.70) {
                return true
            }
        }

        return false
    }

    private fun normalizeForSimilarity(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
        return decomposed.lowercase()
            .replace(Regex("[\"“”'’\\[\\]()]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Direct HD Stream Lyrics API:
     * Queries direct stream endpoints (generic and legal policy safe),
     * ensuring high-fidelity matches for Indian and global streaming catalog tracks.
     */
    private suspend fun fetchDirectHdLyrics(
        track: MusicTrack,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        // 1. Direct call for tracks with online streaming catalog ID (online_...)
        if (track.id.startsWith("online_")) {
            val rawId = track.id.removePrefix("online_")
            val directLyrics = fetchDirectHdLyricsById(track, rawId)
            if (directLyrics != null && directLyrics.lines.isNotEmpty()) {
                return@withContext directLyrics
            }
        }

        // 2. Direct catalog search for streaming tracks
        val queries = listOf(
            "$cleanTitle $primaryArtist".trim(),
            cleanTitle.trim()
        ).distinct().filter { it.isNotBlank() }

        for (q in queries) {
            try {
                val searchUrl = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=5&p=1&_marker=0&ctx=web6dot0&q=${urlEncode(q)}"
                val searchReq = Request.Builder()
                    .url(searchUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val searchResp = httpClient.newCall(searchReq).execute()
                if (!searchResp.isSuccessful) continue
                val searchBody = searchResp.body?.string() ?: continue
                if (!searchBody.startsWith("{")) continue

                val json = JSONObject(searchBody)
                val results = json.optJSONArray("results") ?: continue

                for (i in 0 until results.length()) {
                    val songObj = results.getJSONObject(i)
                    val candTitle = cleanHtml(songObj.optString("title", ""))
                    val candSingers = cleanHtml(songObj.optString("singers", ""))
                    val candPrimaryArtists = cleanHtml(songObj.optString("primary_artists", ""))
                    val candSongId = songObj.optString("id", "")
                    if (candSongId.isBlank()) continue

                    val maxSim = evaluateTitleMatch(candTitle, candPrimaryArtists, cleanTitle, coreTitle, allArtists)
                    if (maxSim < 0.65) {
                        Log.d(TAG, "Direct HD stream candidate '$candTitle' rejected: similarity ${(maxSim * 100).toInt()}% < 65%")
                        continue
                    }

                    val candCombinedArtists = listOf(candPrimaryArtists, candSingers).filter { it.isNotBlank() }.joinToString(", ")
                    if (evaluateArtistScore(candCombinedArtists, candTitle, primaryArtist, allArtists, maxSim) == null) {
                        Log.d(TAG, "Direct HD stream candidate '$candTitle' by '$candCombinedArtists' rejected: artist mismatch")
                        continue
                    }

                    val songLyrics = fetchDirectHdLyricsById(track, candSongId)
                    if (songLyrics != null && songLyrics.lines.isNotEmpty()) {
                        Log.i(TAG, "Matched direct stream lyrics for '${track.title}' via '$candTitle'")
                        return@withContext songLyrics
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Direct HD catalog search for query '$q' failed: ${e.message}")
            }
        }
        null
    }

    /**
     * Direct API lookup for a specific song ID
     */
    private suspend fun fetchDirectHdLyricsById(track: MusicTrack, songId: String): TrackLyrics? = withContext(Dispatchers.IO) {
        try {
            // 1. Direct lyrics call
            val directUrl = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&lyrics_id=$songId&_format=json&ctx=web6dot0"
            val directReq = Request.Builder()
                .url(directUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val directResp = httpClient.newCall(directReq).execute()
            if (directResp.isSuccessful) {
                val body = directResp.body?.string() ?: ""
                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    val rawLyrics = json.optString("lyrics", "")
                    if (rawLyrics.isNotBlank()) {
                        val cleaned = cleanHtmlLyrics(rawLyrics)
                        if (cleaned.isNotBlank()) {
                            Log.i(TAG, "Direct HD lyrics succeeded for id $songId")
                            return@withContext formatPlainLyrics(track, cleaned)
                        }
                    }
                }
            }

            // 2. Call song details to resolve lyrics_id
            val detailsUrl = "https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0%3F_marker%3D0&_format=json&pids=$songId"
            val detReq = Request.Builder()
                .url(detailsUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val detResp = httpClient.newCall(detReq).execute()
            if (detResp.isSuccessful) {
                val detBody = detResp.body?.string() ?: ""
                if (detBody.startsWith("{")) {
                    val detJson = JSONObject(detBody)
                    val songObj = detJson.optJSONObject(songId)
                        ?: detJson.optJSONArray("songs")?.optJSONObject(0)
                    if (songObj != null && (songObj.optString("has_lyrics", "false").equals("true", ignoreCase = true) || songObj.optString("has_lyrics", "0") == "1")) {
                        val lyricsId = songObj.optString("lyrics_id", songId).ifBlank { songId }
                        val callUrl = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&lyrics_id=$lyricsId&_format=json&ctx=web6dot0"
                        val lrcReq = Request.Builder().url(callUrl).addHeader("User-Agent", "Mozilla/5.0").build()
                        val lrcResp = httpClient.newCall(lrcReq).execute()
                        if (lrcResp.isSuccessful) {
                            val lrcBody = lrcResp.body?.string() ?: ""
                            if (lrcBody.startsWith("{")) {
                                val lrcJson = JSONObject(lrcBody)
                                val lyricsText = lrcJson.optString("lyrics", "")
                                if (lyricsText.isNotBlank()) {
                                    val cleaned = cleanHtmlLyrics(lyricsText)
                                    if (cleaned.isNotBlank()) {
                                        Log.i(TAG, "Direct HD lyrics resolved via details id '$lyricsId' for '${track.title}'")
                                        return@withContext formatPlainLyrics(track, cleaned)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "fetchDirectHdLyricsById failed for id $songId: ${e.message}")
            null
        }
    }

    /**
     * Resolves song ISRC from catalog for direct LRCLIB querying.
     */
    private suspend fun fetchIsrcFromCatalog(
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>
    ): String? = withContext(Dispatchers.IO) {
        try {
            val query = "$cleanTitle $primaryArtist".trim()
            val searchUrl = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&n=3&p=1&_marker=0&ctx=web6dot0&q=${urlEncode(query)}"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val searchResp = httpClient.newCall(searchReq).execute()
            if (!searchResp.isSuccessful) return@withContext null
            val body = searchResp.body?.string() ?: return@withContext null
            if (!body.startsWith("{")) return@withContext null

            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return@withContext null
            for (i in 0 until results.length()) {
                val song = results.getJSONObject(i)
                val candTitle = cleanHtml(song.optString("title", ""))
                val candSingers = cleanHtml(song.optString("singers", ""))
                val candPrimary = cleanHtml(song.optString("primary_artists", ""))
                val songId = song.optString("id", "")

                val sim = evaluateTitleMatch(candTitle, candPrimary, cleanTitle, coreTitle, allArtists)
                if (sim < 0.65) continue

                val candCombined = "$candPrimary, $candSingers"
                if (!validateArtistMatch(candCombined, primaryArtist, allArtists)) continue

                val isrc = OnlineMusicApiService.fetchSongIsrc(songId)
                if (!isrc.isNullOrBlank()) {
                    return@withContext isrc.trim()
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "fetchIsrcFromCatalog failed: ${e.message}")
            null
        }
    }

    private fun cleanHtml(raw: String): String {
        return raw.replace(Regex("(?i)<br\\s*/?>"), " ")
            .replace(Regex("<.*?>"), " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanHtmlLyrics(raw: String): String {
        return raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?p>"), "\n")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Fallback #1: NetEase Cloud Music API (Levenshtein >= 80% & artist match)
     */
    private suspend fun fetchNetEaseLyrics(
        track: MusicTrack,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        targetDurationSec: Int
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        val queries = listOf(
            "$cleanTitle $primaryArtist".trim(),
            cleanTitle.trim(),
            coreTitle.trim()
        ).distinct().filter { it.isNotBlank() }

        for (q in queries) {
            try {
                val searchUrl = "https://music.163.com/api/cloudsearch/pc?s=${urlEncode(q)}&type=1&offset=0&limit=5"
                val request = Request.Builder()
                    .url(searchUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("Referer", "https://music.163.com/")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) continue
                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: continue

                for (i in 0 until songs.length()) {
                    val song = songs.getJSONObject(i)
                    val songTitle = song.optString("name", "")
                    val songId = song.optLong("id", 0L)
                    val durationMs = song.optLong("dt", 0L)
                    val candDurSec = (durationMs / 1000L).toInt()

                    // Strict Levenshtein similarity check: must be >= 0.80 (80% Rule)
                    val maxSim = validateTitleSimilarity(songTitle, cleanTitle, coreTitle)
                    if (maxSim < 0.80) continue

                    // Multi-Artist Validation for NetEase
                    val artistsArray = song.optJSONArray("ar") ?: song.optJSONArray("artists")
                    var candArtistMatch = false
                    if (artistsArray != null) {
                        for (aIdx in 0 until artistsArray.length()) {
                            val aObj = artistsArray.getJSONObject(aIdx)
                            val aName = aObj.optString("name", "")
                            if (validateArtistMatch(aName, primaryArtist, allArtists)) {
                                candArtistMatch = true
                                break
                            }
                        }
                    } else {
                        candArtistMatch = true
                    }
                    if (!candArtistMatch) continue

                    // Duration check
                    if (targetDurationSec > 0 && candDurSec > 0) {
                        val diff = kotlin.math.abs(targetDurationSec - candDurSec)
                        if (diff > 15) continue
                    }

                    // Fetch lyric content
                    val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
                    val lyricReq = Request.Builder()
                        .url(lyricUrl)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .addHeader("Referer", "https://music.163.com/")
                        .build()

                    val lyricResp = httpClient.newCall(lyricReq).execute()
                    if (!lyricResp.isSuccessful) continue
                    val lyricBody = lyricResp.body?.string() ?: continue
                    val lyricJson = JSONObject(lyricBody)
                    val lrcStr = lyricJson.optJSONObject("lrc")?.optString("lyric", "") ?: ""

                    if (lrcStr.isNotBlank() && (lrcStr.contains("[00:") || lrcStr.contains("[01:"))) {
                        Log.i(TAG, "NetEase lyrics found for '${track.title}' (similarity ${(maxSim * 100).toInt()}%)")
                        return@withContext parseLrc(track.id, track.title, track.artist, lrcStr)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchNetEaseLyrics query '$q' failed: ${e.message}")
            }
        }
        null
    }

    /**
     * Fallback #2: Kugou Lyrics API (Levenshtein >= 80% & artist match)
     */
    private suspend fun fetchKugouLyrics(
        track: MusicTrack,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>,
        targetDurationSec: Int
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        val queries = listOf(
            "$cleanTitle $primaryArtist".trim(),
            cleanTitle.trim()
        ).distinct().filter { it.isNotBlank() }

        for (q in queries) {
            try {
                val searchUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=${urlEncode(q)}&duration=$targetDurationSec&hash="
                val request = Request.Builder()
                    .url(searchUrl)
                    .addHeader("User-Agent", "KugouMusic/10.0 (Android)")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) continue
                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates") ?: continue

                for (i in 0 until candidates.length()) {
                    val cand = candidates.getJSONObject(i)
                    val candSong = cand.optString("song", "")
                    val candSinger = cand.optString("singer", "")
                    val candId = cand.optString("id", "")
                    val accessKey = cand.optString("accesskey", "")

                    if (candId.isBlank() || accessKey.isBlank()) continue

                    // Strict Levenshtein similarity check: must be >= 0.80 (80% Rule)
                    val maxSim = validateTitleSimilarity(candSong, cleanTitle, coreTitle)
                    if (maxSim < 0.80) continue

                    // Multi-Artist Validation for Kugou
                    if (candSinger.isNotBlank()) {
                        if (!validateArtistMatch(candSinger, primaryArtist, allArtists)) continue
                    }

                    // Download lyrics in LRC format
                    val downloadUrl = "http://krcs.kugou.com/download?ver=1&client=mobi&id=$candId&accesskey=$accessKey&fmt=lrc&charset=utf8"
                    val dlReq = Request.Builder()
                        .url(downloadUrl)
                        .addHeader("User-Agent", "KugouMusic/10.0 (Android)")
                        .build()

                    val dlResp = httpClient.newCall(dlReq).execute()
                    if (!dlResp.isSuccessful) continue
                    val dlBody = dlResp.body?.string() ?: continue
                    val dlJson = JSONObject(dlBody)
                    val base64Content = dlJson.optString("content", "")

                    if (base64Content.isNotBlank()) {
                        val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
                        val lrcString = String(decodedBytes, Charsets.UTF_8)
                        if (lrcString.contains("[00:") || lrcString.contains("[01:")) {
                            Log.i(TAG, "Kugou lyrics found for '${track.title}' (similarity ${(maxSim * 100).toInt()}%)")
                            return@withContext parseLrc(track.id, track.title, track.artist, lrcString)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchKugouLyrics query '$q' failed: ${e.message}")
            }
        }
        null
    }

    private fun urlEncode(s: String): String {
        return try {
            URLEncoder.encode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }
    }

    private fun formatPlainLyrics(identity: SongIdentity, plainText: String): TrackLyrics {
        val rawLineList = plainText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("[") }

        val lines = rawLineList.map { text ->
            LyricLine(timestampMs = 0L, text = text, durationMs = 0L)
        }

        return TrackLyrics(
            trackId = identity.trackId ?: "",
            title = identity.title,
            artist = identity.artist ?: "",
            isSynced = false,
            lines = lines
        )
    }

    private fun formatPlainLyrics(track: MusicTrack, plainText: String): TrackLyrics {
        return formatPlainLyrics(SongIdentity.from(track), plainText)
    }

    // Curated catalog removed - strictly using 1. BetterLyrics -> 2. BiniLyrics -> 3. LRCLIB
    /*
    private val CURATED_LRC_MAP = mapOf(
        // --- PUNJABI HITS ---
        "brown munde" to """
            [00:08.20]Desi da drum, utte pair dhareya
            [00:13.50]Ambran te naam sadda likheya peya
            [00:18.20]Brown Munde, Brown Munde
            [00:22.90]Yeah, we the same guys from the block
            [00:27.40]Gaddi kali, tinted sheeshe, rim ghumde
            [00:32.00]Pind de munde jaake Toronto ghumde
            [00:36.50]Brown Munde, Brown Munde
            [00:41.20]Kehndi hundi si, chann tak raah bana de
            [00:46.00]Taare ne pasand mainu, hethaan saare laade
            [00:51.30]Brown Munde shinin' bright like diamonds in the sky
            [00:56.00]Worldwide vibe, never say goodbye
            [01:05.00]♪ Heavy Punjabi Bass Drop ♪
            [01:20.00]Desi touch, global rhythm, that's how we roll
            [01:28.00]Brown Munde, Brown Munde
        """.trimIndent(),

        "softly" to """
            [00:06.00]Ikky on the beat boi!
            [00:10.50]Karan Aujla!
            [00:14.20]Ni tu softly softly turdi aen
            [00:18.80]Dil mera vi hoke bharda aen
            [00:23.40]Tere utte maran da shauk nahio
            [00:27.80]Par dil mera tere te marda aen
            [00:32.50]Diamond di mundi wangu chamakdi aen
            [00:37.00]Gaddi vich gaane laake nachdi aen
            [00:41.80]Softly, baby talk to me softly
            [00:46.50]Je tu mainu jaan'di aen, das de clearly
            [00:55.00]♪ Karan Aujla Signature Flow ♪
            [01:10.00]Likh ke naam mera dil utte rakh le
            [01:18.00]Pyar di nishani saambh ke tu rakh le
        """.trimIndent(),

        "295" to """
            [00:07.00]Sidhu Moose Wala!
            [00:15.50]Dass kihton kihton darrdi ae duniya
            [00:22.00]Sach bolan ton darrdi ae duniya
            [00:28.40]Nitt nawa controversy khadi karde
            [00:35.00]Chardi jawani utte aivein sad'de
            [00:41.50]295 da parcha hunda
            [00:47.80]Sach da raah te jo vi turda
            [00:54.20]Moosewala naam rehna dilan te sada
            [01:00.50]Sach kade vi na kisse ton chupda
            [01:12.00]♪ Brass & Dhol Drop ♪
            [01:25.00]Rab di raza vich rehna sikh leya
            [01:34.00]Kalam ne sach hi likh leya
        """.trimIndent(),

        "lover" to """
            [00:05.00]Diljit Dosanjh!
            [00:11.20]Tera ni lover, koi hor na bana le
            [00:17.00]Akhiyan ch paake kajla, dil nu chura le
            [00:23.50]Baby tera lover, lover, lover
            [00:29.00]Mainu takk le tu zara ik vaar
            [00:35.00]Kivein dasan tainu kina karda pyar
            [00:41.20]Tere piche ghumdi aa poori car
            [00:47.00]Tera ni lover, koi hor na bana le
            [00:53.00]♪ Upbeat Dance Groove ♪
            [01:10.00]Sun le ni heeriye, meri gall sun le
            [01:18.00]Pyar de supne tu mere naal bun le
        """.trimIndent(),

        "excuses" to """
            [00:08.00]Kehndi hundi si chann tak raah bana de
            [00:16.00]Taare ne pasand mainu hethan saare laade
            [00:23.50]Ohna taareyan de vich jadon mainu vekhengi
            [00:31.00]Yaad taan auni meri, zaroor auni ae
            [00:39.00]Dil tuttna taan laazmi si
            [00:46.50]Par aidaan chhad ke jaan di umeed nahi si
            [00:54.00]♪ Synth Acoustic Beat ♪
            [01:12.00]Galti meri si ki tainu sab kujh mann leya
            [01:20.00]Apne to wadh ke tere te bharosa kar leya
        """.trimIndent(),

        "tauba tauba" to """
            [00:04.00]Karan Aujla, Vicky Kaushal!
            [00:10.00]Tauba tauba tera husn soniye
            [00:15.50]Katal kare tera nakhra soniye
            [00:21.00]Jadon matak ke pair dhar di aen
            [00:26.50]Saare mundeyan da chain khondi aen
            [00:32.00]Tauba tauba, tauba tauba!
            [00:38.00]Aidaan na kardi na saanu maar suttdi
            [00:45.00]♪ High Energy Punjabi Dhol Beats ♪
            [01:02.00]Look tera killa karda ni kamaal
            [01:10.00]Dil mera puchhda tera hi haal
        """.trimIndent(),

        // --- HINDI / BOLLYWOOD HITS ---
        "tum hi ho" to """
            [00:08.00]Hum tere bin ab reh nahi sakte
            [00:16.50]Tere bina kya wajood mera
            [00:24.00]Tujhse juda agar ho jayenge
            [00:31.50]Toh khud se hi ho jayenge juda
            [00:39.00]Kyunki tum hi ho, ab tum hi ho
            [00:47.00]Zindagi ab tum hi ho
            [00:55.00]Chain bhi, mera dard bhi
            [01:03.00]Meri aashiqui ab tum hi ho
            [01:15.00]♪ Soulful Piano Interlude ♪
            [01:30.00]Tera mera rishta hai kaisa
            [01:37.50]Ek pal door gawaara nahi
            [01:45.00]Tere liye har roz hai jeete
            [01:52.50]Tujhko diya mera waqt sabhi
        """.trimIndent(),

        "kesariya" to """
            [00:07.00]Mujhko itna bataye koi
            [00:13.50]Kaise tujhse dil na lagaye koi
            [00:20.00]Rabba ne tujhko banane mein
            [00:26.50]Kardi hai husn ki khaali tijoriyan
            [00:33.00]Kajal ki siyahi se likhi
            [00:39.50]Hai tune jaane kitno ki love storiyan
            [00:46.00]Kesariya tera ishq hai piya
            [00:53.00]Rang jaaun jo main haath lagaun
            [01:00.00]Din beete saara teri fikr mein
            [01:07.00]Rain saari teri khair manaun
            [01:18.00]♪ Acoustic Guitar Melodic Strum ♪
            [01:32.00]Patjhad ke mausam mein bhi
            [01:39.00]Rangi bahaar lagti hai tu
        """.trimIndent(),

        "channa mereya" to """
            [00:09.00]Achha chalta hoon, duaon mein yaad rakhna
            [00:17.50]Mere zikr ka zubaan pe swaad rakhna
            [00:26.00]Dil ke sandookon mein mere achhe kaam rakhna
            [00:34.50]Chitthi taaron mein bhi mera tu salaam rakhna
            [00:43.00]Andhera tera maine le liya
            [00:51.50]Mera ujla sitaara tere naam kiya
            [01:00.00]Channa mereya mereya, channa mereya mereya
            [01:08.50]Channa mereya mereya beliya, o piya!
            [01:22.00]♪ Grand Orchestral Sitar Crescendo ♪
            [01:38.00]Mehfil mein teri hum na rahein jo
            [01:46.50]Gham toh nahi hai, kisse humaare
        """.trimIndent(),

        "apna bana le" to """
            [00:06.00]Tu mera koi na hoke bhi kuch laage
            [00:14.00]Tu mera koi na hoke bhi kuch laage
            [00:22.00]Kiya re jo bhi toone kaise kiya re
            [00:30.00]Jiya ko mere baandh aise liya re
            [00:38.00]Apna bana le piya, apna bana le piya
            [00:46.50]Dil ke nagar mein shehar tu basa le piya
            [00:55.00]Apna bana le piya, apna bana le piya
            [01:08.00]♪ Flute & Sarangi Harmonies ♪
            [01:24.00]Chhookar gaya jo tu aise hawa sa
            [01:32.00]Hota nahi hai kabhi dard zawaa sa
        """.trimIndent(),

        "raataan lambiyan" to """
            [00:07.00]Teri meri gallan ho gayi mashhur
            [00:14.00]Kar na kabhi tu mujhe nazron se door
            [00:21.00]Kithe chaliye tu kithe chaliye
            [00:28.00]Jaanda ae dil yeh toh jaandi ae tu
            [00:35.00]Tere bina kivein jeena meri jaan
            [00:42.00]Kaatun kaise raataan o saawre
            [00:49.00]Jiya nahi jaata sun baawre
            [00:56.00]Raataan lambiyan lambiyan re
            [01:03.00]Katte tere sang yaara ve
        """.trimIndent(),

        // --- ENGLISH / GLOBAL HITS ---
        "blinding lights" to """
            [00:05.00]Yeah! The Weeknd!
            [00:12.50]I've been tryna call
            [00:17.00]I've been on my own for long enough
            [00:22.00]Maybe you can show me how to love, maybe
            [00:27.50]I'm going through withdrawals
            [00:32.00]You don't even have to do too much
            [00:37.00]You can turn me on with just a touch, baby
            [00:42.50]I look around and Sin City's cold and empty
            [00:47.80]No one's around to judge me
            [00:52.50]I can't see clearly when you're gone
            [00:58.00]I said, ooh, I'm blinded by the lights
            [01:04.50]No, I can't sleep until I feel your touch
            [01:10.00]I said, ooh, I'm drowning in the night
            [01:16.50]Oh, when I'm like this, you're the one I trust
            [01:25.00]♪ 80s Synthesizer Solo Riff ♪
            [01:40.00]I'm just walking by to let you know
            [01:46.00]I could never say it on the phone
        """.trimIndent(),

        "starboy" to """
            [00:06.00]I'm tryna put you in the worst mood, ah
            [00:11.00]P1 cleaner than your church shoes, ah
            [00:16.00]Milli point two just to hurt you, ah
            [00:21.00]All red Lamb' just to tease you, ah
            [00:26.00]None of these toys on lease too, ah
            [00:31.00]Made your whole year in a week too, yah
            [00:36.00]Look what you've done
            [00:40.00]I'm a motherfuckin' starboy
            [00:45.50]Look what you've done
            [00:50.00]I'm a motherfuckin' starboy
            [00:58.00]♪ Daft Punk Vocoder Electro Drop ♪
            [01:15.00]Every day a star is born
            [01:22.00]Climb into the sky before the dawn
        """.trimIndent(),

        "shape of you" to """
            [00:04.00]The club isn't the best place to find a lover
            [00:08.50]So the bar is where I go
            [00:13.00]Me and my friends at the table doing shots
            [00:17.00]Drinking fast and then we talk slow
            [00:21.50]Come over and start up a conversation with just me
            [00:26.00]And trust me I'll give it a chance now
            [00:30.00]Take my hand, stop, put Van the Man on the jukebox
            [00:35.00]And then we start to dance
            [00:39.00]Girl, you know I want your love
            [00:43.00]Your love was handmade for somebody like me
            [00:47.50]I'm in love with the shape of you
            [00:52.00]We push and pull like a magnet do
            [00:56.50]Although my heart is falling too
            [01:01.00]I'm in love with your body
        """.trimIndent(),

        "levitating" to """
            [00:04.00]If you wanna run away with me, I know a galaxy
            [00:09.00]And I can take you for a ride
            [00:13.50]I had a premonition that we fell into a rhythm
            [00:18.00]Where the music don't stop for life
            [00:22.50]Glitter in the sky, glitter in my eyes
            [00:27.00]Shining just the way I like
            [00:31.50]If you're feeling like you need a little bit of company
            [00:36.00]You met me at the perfect time
            [00:40.50]You want me, I want you, baby
            [00:45.00]My sugarboo, I'm levitating
            [00:49.50]The Milky Way, we're renegading
            [00:54.00]Yeah, yeah, yeah, yeah, yeah!
        """.trimIndent(),

        // --- CATALOGUE TRACKS 01 TO 10 ---
        "xtreme_01" to """
            [00:04.00]♪ Synthwave Arpeggio Intro ♪
            [00:15.20]Grid lines glowing in the midnight air
            [00:23.50]Cybernetic shadows moving everywhere
            [00:31.80]Signals running through the fiber line
            [00:39.40]Lost inside this digital design
            [00:48.00]Neon horizon calling out my name
            [00:56.20]Living in the pulse of an electric game
            [01:04.80]♪ Heavy Bass Drop & Solo ♪
            [01:21.00]Speeding through the highway at 2088
            [01:29.40]Leave the past behind before it is too late
            [01:37.80]Analog dreams will never fade away
            [01:46.00]Until we reach the dawn of a synthetic day
            [01:58.00]♪ Cybernetic Outro Fade ♪
        """.trimIndent(),

        "xtreme_02" to """
            [00:05.00]♪ Retro Drum Loop Intro ♪
            [00:14.00]Midnight drive down the empty coast
            [00:22.40]Chasing the memories I miss the most
            [00:31.00]Dashboard lights illuminating the dark
            [00:39.50]Looking for an old forgotten spark
            [00:48.00]Turn the stereo up, feel the engine hum
            [00:56.50]Waiting for the morning light to come
            [01:08.00]♪ Atmospheric Synth Breakdown ♪
            [01:24.00]Reflections in the rearview mirror shine
            [01:32.50]Crossing over every boundary line
            [01:41.00]Midnight drive beneath the open sky
            [01:50.00]Never needing to ask the reason why
            [02:00.00]♪ Melodic Sunset Outro ♪
        """.trimIndent(),

        "xtreme_03" to """
            [00:06.00]♪ Hyperdrive Engine Charge ♪
            [00:16.50]Outrun protocol engaged right now
            [00:24.80]We make it through the storm somehow
            [00:33.00]Echoes of the matrix in the night
            [00:41.20]Blinding speed of pure ultraviolet light
            [00:52.00]Faster than the sound, higher than the stars
            [01:02.00]Leaving behind all our ancient scars
            [01:14.00]♪ 320kbps High Energy Beat Drop ♪
            [01:35.00]Break the barrier, touch the hyperdrive
            [01:44.00]This is what it means to feel alive
            [01:55.00]Infinite echoes across space and time
            [02:05.00]Every single beat in rhythm and rhyme
            [02:18.00]♪ Cosmic Outro Echoes ♪
        """.trimIndent(),

        "xtreme_04" to """
            [00:03.00]♪ Vinyl Crackle & Lofi Rhodes Intro ♪
            [00:13.00]Steam from the cup, notebook on the side
            [00:22.00]Nowhere else I would rather hide
            [00:31.00]Rain tapping softly against the glass
            [00:40.00]Watching all the quiet hours pass
            [00:52.00]Starlight whispers through the cold night air
            [01:03.00]Not a single worry, not a single care
            [01:16.00]♪ Gentle Mellow Trumpet Solo ♪
            [01:32.00]Coffee sessions with the cosmic chill
            [01:42.00]Time stands completely peaceful and still
            [01:54.00]Just a relaxed beat and a calming thought
            [02:05.00]The simple peace that cannot be bought
            [02:15.00]♪ Warm Tape Loop Fadeout ♪
        """.trimIndent(),

        "xtreme_05" to """
            [00:05.00]♪ Sub-Bass Overdrive Intro ♪
            [00:14.20]Electric pulse vibrating the floor
            [00:22.80]Louder and heavier than ever before
            [00:31.50]Synth bass rattling inside your chest
            [00:40.00]We bring the rhythm that beats the rest
            [00:51.00]Voltage rising to the maximum peak
            [00:60.00]This is the sonic power that we seek
            [01:12.00]♪ Massive Electronic Drop ♪
            [01:30.00]Feel the frequency take complete control
            [01:39.00]Pure audio energy in your soul
            [01:50.00]Overdrive circuit burning so bright
            [02:00.00]Electrifying the city throughout the night
            [02:15.00]♪ Bass Resonance Decay ♪
        """.trimIndent(),

        "xtreme_06" to """
            [00:05.00]♪ Distorted Guitar Riff Intro ♪
            [00:16.00]Walking down this Neon Boulevard
            [00:24.00]Shadows long and the night hits hard
            [00:32.50]Guitars screaming through the midnight rain
            [00:40.80]Nothing left to lose and no more pain
            [00:50.00]We ignite the fire in the city streets
            [00:58.50]Tuning into pure rebellious beats
            [01:08.00]♪ High Gain Guitar Solo Riff ♪
            [01:25.00]Neon Boulevard leads us straight ahead
            [01:34.00]Remembering every single word you said
            [01:45.00]Rock and roll echoing through the air
            [01:55.00]Hands raised high everywhere
        """.trimIndent(),

        "xtreme_07" to """
            [00:04.00]♪ Bright Euphoric Pop Intro ♪
            [00:14.50]Solar flare bursting through the sky
            [00:22.00]We can spread our wings and fly so high
            [00:30.50]Colors shining golden in the sun
            [00:38.00]Now our adventure has just begun
            [00:47.00]Dance under the warm celestial rays
            [00:55.50]Lost inside these sunlit summer days
            [01:05.00]♪ Upbeat Synthpop Dance Groove ♪
            [01:22.00]Solar flare burning in our eyes
            [01:31.00]No more sorrow and no more goodbyes
            [01:40.00]Feel the rhythm and feel the heat
            [01:50.00]Stepping light on every dancing street
        """.trimIndent(),

        "xtreme_08" to """
            [00:06.00]♪ Smoky Saxophone & Walking Bass Intro ♪
            [00:18.00]Velvet cushions and a dimmed blue light
            [00:28.00]Notes cascading through the quiet night
            [00:38.50]Trumpet whispering tales of yesterday
            [00:48.00]Washing all the busy thoughts away
            [00:58.00]Finger snaps and a gentle piano chord
            [01:08.00]Music is the richest sweet reward
            [01:20.00]♪ Expressive Saxophone Improvisation ♪
            [01:40.00]Deep velvet groove keeping us in line
            [01:50.00]Savoring the melody like vintage wine
            [02:00.00]Midnight Blue club open till dawn
            [02:10.00]Let the smooth jazz carry on
        """.trimIndent(),

        "xtreme_09" to """
            [00:05.00]♪ Boom-Bap Hip Hop Beat Intro ♪
            [00:15.00]District 9 rising from the underground
            [00:23.50]Concrete jungle vibrating with the sound
            [00:32.00]Rhythm in my step, rhyme inside my head
            [00:40.50]Living out the dreams instead of what they said
            [00:50.00]Streetlights shining on the asphalt floor
            [00:59.00]Every single bar giving something more
            [01:10.00]♪ Turntable Scratch & Beat Breakdown ♪
            [01:26.00]Underground odyssey, stayin' true and real
            [01:36.00]Expressing every single word that we feel
            [01:46.00]Subwoofers rolling through the block
            [01:55.00]Around the clock, we never stop
        """.trimIndent(),

        "xtreme_10" to """
            [00:07.00]♪ Ethereal Space Drone & Chimes ♪
            [00:22.00]Drifting far beyond the event horizon
            [00:36.00]Silence and tranquility slowly risin'
            [00:50.00]Starlight pulsing across the deep expanse
            [01:05.00]Watching cosmic galaxies slowly dance
            [01:20.00]Quantum echoes reverberate through space
            [01:38.00]Finding peace in this boundless place
            [02:00.00]♪ Deep Celestial Resonance Decay ♪
            [02:20.00]Timeless stillness calming the mind
            [02:40.00]Leaving all the gravity behind
        """.trimIndent()
    )
    */
}
