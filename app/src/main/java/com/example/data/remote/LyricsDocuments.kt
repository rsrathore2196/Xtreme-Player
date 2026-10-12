package com.example.data.remote

import android.text.Html
import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Provider wire formats retain their original clocks; no estimated syllable subdivision. */
internal object LyricsDocuments {
    data class Run(val text: String, val start: Long, val end: Long, val reliable: Boolean = true)

    fun parse(identity: SongIdentity, raw: String, provider: String, depth: Int = 0): TrackLyrics? {
        if (depth > 8 || raw.length > 2_000_000) return null
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty() || text.lowercase() in setOf("null", "false", "undefined")) return null
        // LRC is not a JSON array (org.json accepts its unquoted timestamp leniently).
        if (lineStamp.containsMatchIn(text) || Regex("(?m)^\\[\\d+,\\d+]").containsMatchIn(text)) {
            timedText(identity, text, provider)?.let { return it }
        }
        Regex("LyricContent\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)?.let {
            return timedText(identity, Html.fromHtml(it.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString(), provider)
        }
        if (text.startsWith("&lt;")) return parse(identity, Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString(), provider, depth + 1)
        if (text.startsWith('<') && Regex("<(?:(?:\\w+):)?tt(?:\\s|>)").containsMatchIn(text)) {
            val parsed = BetterLyricsParser.parseTtml(identity, text) ?: return null
            return build(identity, provider, parsed.lines)?.copy(language = parsed.language, songWriters = parsed.songWriters)
        }
        if (text.startsWith("{") || text.startsWith('"') ||
            Regex("^\\[\\s*[\\[{\"]").containsMatchIn(text) || text == "[]") {
            val value = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            when (value) {
                is JSONObject -> {
                    if (value.optBoolean("isError") || value.opt("ok") == false || value.opt("success") == false ||
                        (value.has("error") && !value.isNull("error") && value.opt("error") !in listOf(false, ""))) return null
                    val choices = listOf("ttml", "ttmlContent", "syncedLyrics", "richsync_body", "subtitle_body",
                        "lyrics", "lrc", "content", "lines", "lyric", "data", "result", "response", "attributes", "plainLyrics", "text")
                        .mapNotNull { key -> value.opt(key)?.takeUnless { it == JSONObject.NULL }?.let {
                            parse(identity, it.toString(), provider, depth + 1)
                        } }
                    val best = choices.maxWithOrNull(comparator)
                    return if (value.optString("type").lowercase() in setOf("plain", "unsynced", "none")) {
                        best?.copy(isSynced = false, hasWordTiming = false, hasSyllableTiming = false,
                            lines = best.lines.map { it.copy(timestampMs = 0, durationMs = 0, words = emptyList()) })
                    } else best
                }
                is JSONArray -> return jsonLines(identity, value, provider)
                is String -> return parse(identity, value, provider, depth + 1)
            }
        }
        if (text.startsWith('<') && !text.startsWith("<00:")) return null
        val timed = timedText(identity, text, provider)
        if (timed != null) return timed
        if (text.startsWith('{') || text.startsWith('[') && !text.contains('\n') ||
            Regex("(?i)^(?:error|not found|unauthorized|forbidden|lyrics? (?:not found|unavailable)|404|502)\\b").containsMatchIn(text)) return null
        val lines = text.lines().map(String::trim).filter { it.isNotBlank() && !it.matches(Regex("\\[[^]]*]")) }
        return build(identity, provider, lines.map { LyricLine(0, it) }, synced = false)
    }

    fun build(identity: SongIdentity, provider: String, lines: List<LyricLine>, synced: Boolean = true): TrackLyrics? {
        val clean = lines.filter { it.text.isNotBlank() }.distinctBy { it.timestampMs to it.text }
            .let { if (synced) it.sortedBy(LyricLine::timestampMs) else it }
        if (clean.isEmpty()) return null
        // A document extending beyond the recording is the wrong edit or uses the wrong units.
        val duration = identity.durationMs ?: 0
        if (synced && duration > 0 && clean.any { maxOf(it.timestampMs, it.words.maxOfOrNull(LyricWord::endTimeMs) ?: 0) > duration + 5_000 }) return null
        return SyllableSynthesizer.enrichLyrics(TrackLyrics(identity.trackId.orEmpty(), identity.title,
            identity.artist.orEmpty(), synced, clean, provider = provider,
            hasWordTiming = clean.any { line -> line.words.any { it.isReliableTiming && it.durationMs > 0 } },
            hasTranslation = clean.any { !it.translation.isNullOrBlank() },
            hasRomanization = clean.any { !it.romanization.isNullOrBlank() }))
    }

    /** Stable ties retain source preference; availability/timing always outranks provider order. */
    val comparator: Comparator<TrackLyrics> = compareBy<TrackLyrics> { quality(it) }
        .thenBy { coverage(it) }.thenBy { it.lines.count { line -> line.words.isNotEmpty() } }

    fun quality(lyrics: TrackLyrics): Int {
        if (lyrics.lines.isEmpty()) return -1
        val words = lyrics.lines.flatMap { it.words }.filter { it.isReliableTiming && it.durationMs > 0 }
        if (words.any { word -> word.syllables.size > 1 && word.syllables.all { it.isReliableTiming && it.durationMs > 0 } }) return 3
        if (words.isNotEmpty()) return 2
        return if (lyrics.isSynced) 1 else 0
    }
    private fun coverage(lyrics: TrackLyrics): Double = lyrics.lines.sumOf { line ->
        line.words.filter { it.isReliableTiming && it.durationMs > 0 }.sumOf { it.text.length }
    }.toDouble() / lyrics.lines.sumOf { it.text.length }.coerceAtLeast(1)

    fun words(runs: List<Run>, explicitSpacing: Boolean): List<LyricWord> {
        if (!explicitSpacing) return runs.filter { it.text.isNotBlank() }.map {
            LyricWord(it.start, (it.end - it.start).coerceAtLeast(0), it.text.trim(),
                isReliableTiming = it.reliable && it.end > it.start && !Regex("\\s").containsMatchIn(it.text.trim()))
        }
        val result = mutableListOf<LyricWord>()
        val parts = mutableListOf<LyricSyllable>()
        fun flush() {
            if (parts.isEmpty()) return
            val start = parts.minOf { it.timestampMs }; val end = parts.maxOf { it.endTimeMs }
            result.add(LyricWord(start, (end - start).coerceAtLeast(0), parts.joinToString("") { it.text },
                parts.toList(), isReliableTiming = parts.all { it.isReliableTiming } && end > start))
            parts.clear()
        }
        runs.forEach { run ->
            val tokens = Regex("\\s+|\\S+").findAll(run.text).toList()
            val reliable = run.reliable && run.end > run.start && tokens.count { !it.value.isBlank() } == 1
            tokens.forEach { token ->
                if (token.value.isBlank()) flush() else parts.add(LyricSyllable(run.start,
                    (run.end - run.start).coerceAtLeast(0), token.value, reliable))
            }
        }
        flush()
        return result
    }

    private fun jsonLines(identity: SongIdentity, array: JSONArray, provider: String): TrackLyrics? {
        val rows = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        // Candidate arrays are not lyric arrays. Never concatenate different recordings.
        if (rows.none { it.has("time") || it.has("timestamp") || it.has("startTime") || it.has("startTimeMs") || it.has("ts") }) return null
        var anyTimed = false
        val lines = rows.mapNotNull { row ->
            fun ms(obj: JSONObject, key: String, seconds: Boolean = false): Long? {
                if (!obj.has(key) || obj.isNull(key)) return null
                val value = obj.opt(key)
                if (value is JSONObject) return value.optDouble("total", Double.NaN).takeIf { it.isFinite() }?.let { (it * 1000).toLong() }
                val number = value.toString().toDoubleOrNull() ?: return null
                return if (number.isFinite() && number >= 0) (number * if (seconds) 1000 else 1).toLong() else null
            }
            val seconds = row.has("startTime") || row.has("ts")
            val start = ms(row, "time") ?: ms(row, "timestamp") ?: ms(row, "startTimeMs") ?: ms(row, "startTime", true) ?: ms(row, "ts", true) ?: 0
            val end = ms(row, "endTimeMs") ?: ms(row, "endtime") ?: ms(row, "endTime", seconds) ?: ms(row, "te", true)
                ?: ms(row, "duration")?.let { start + it }
            anyTimed = anyTimed || row.has("time") || row.has("timestamp") || row.has("startTime") || row.has("startTimeMs") || row.has("ts")
            val parts = row.optJSONArray("syllabus") ?: row.optJSONArray("words") ?: row.optJSONArray("text") ?: row.optJSONArray("l")
            val runs = if (parts == null) emptyList() else (0 until parts.length()).mapNotNull { index ->
                val part = parts.optJSONObject(index) ?: return@mapNotNull null
                val value = part.optString("text", part.optString("c", ""))
                if (value.isEmpty()) return@mapNotNull null
                val begin = ms(part, "time") ?: ms(part, "timestamp") ?: ms(part, "startTimeMs") ?: ms(part, "startTime", seconds)
                    ?: ms(part, "o", true)?.let { start + it }
                val finish = ms(part, "endTimeMs") ?: ms(part, "endtime") ?: ms(part, "endTime", seconds)
                    ?: ms(part, "duration")?.let { (begin ?: start) + it }
                    ?: if (row.has("l")) {
                        parts.optJSONObject(index + 1)?.let { ms(it, "o", true) }?.let { start + it } ?: end
                    } else null
                val literal = if (part.has("part") && !part.optBoolean("part")) "$value " else value
                Run(literal, begin ?: start, finish ?: begin ?: start, begin != null && finish != null)
            }
            val originalText = row.opt("text") as? String
            // Some LyricsPlus mirrors omit spaces on syllable fragments. Use the actual line
            // to recover boundaries, retaining every fragment's provided timestamp.
            var cursor = 0
            val spacedRuns = if (!originalText.isNullOrBlank() && runs.none { it.text.any(Char::isWhitespace) }) runs.map { run ->
                val match = originalText.indexOf(run.text, cursor)
                if (match < 0) run else {
                    cursor = match + run.text.length
                    if (originalText.getOrNull(cursor)?.isWhitespace() == true) run.copy(text = run.text + " ") else run
                }
            } else runs
            val explicit = row.has("syllabus") || row.has("l") || row.opt("text") is JSONArray || runs.any { it.text.any(Char::isWhitespace) }
            val words = words(spacedRuns, explicit || spacedRuns.any { it.text.any(Char::isWhitespace) })
            val text = (row.opt("text") as? String) ?: (row.opt("words") as? String) ?: row.optString("x", "")
                .ifBlank { if (explicit) runs.joinToString("") { it.text } else runs.joinToString(" ") { it.text } }
            if (text.isBlank()) return@mapNotNull null
            val finish = end ?: words.filter { it.isReliableTiming }.maxOfOrNull { it.endTimeMs } ?: start
            LyricLine(start, text.replace(Regex("\\s+"), " ").trim(), (finish - start).coerceAtLeast(0), words,
                translation = row.optString("translation").ifBlank { null }, romanization = row.optString("romanization").ifBlank { null },
                isReliableDuration = finish > start)
        }
        return build(identity, provider, lines, anyTimed)
    }

    private val lineStamp = Regex("\\[(\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?)]")
    private val wordStamp = Regex("<(\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?)>")
    private fun clock(value: String): Long = BetterLyricsParser.parseTtmlTimestamp(value.replace(Regex("^(\\d+:\\d{2}):(\\d+)$"), "$1.$2"))

    fun timedText(identity: SongIdentity, text: String, provider: String): TrackLyrics? {
        val offset = Regex("(?i)\\[offset:([+-]?\\d+)]").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val lines = mutableListOf<LyricLine>()
        text.lines().forEach { source ->
            val row = source.trim()
            val karaoke = Regex("^\\[(\\d+),(\\d+)](.*)$").matchEntire(row)
            if (karaoke != null) {
                val start = karaoke.groupValues[1].toLong(); val duration = karaoke.groupValues[2].toLong(); val body = karaoke.groupValues[3]
                val prefix = body.startsWith('(')
                val regex = if (prefix) Regex("\\((\\d+),(\\d+)(?:,\\d+)?\\)([^()]*)") else Regex("([^()]*)\\((\\d+),(\\d+)(?:,\\d+)?\\)")
                val runs = regex.findAll(body).map { m ->
                    val begin = m.groupValues[if (prefix) 1 else 2].toLong() + offset
                    Run(m.groupValues[if (prefix) 3 else 1], begin, begin + m.groupValues[if (prefix) 2 else 3].toLong())
                }.toList()
                val words = words(runs, runs.any { it.text.any(Char::isWhitespace) })
                if (words.isNotEmpty()) lines.add(LyricLine((start + offset).coerceAtLeast(0), runs.joinToString("") { it.text }.trim(), duration, words, isReliableDuration = true))
            } else {
                val stamps = lineStamp.findAll(row).takeWhile { it.range.first == 0 || row.substring(0, it.range.first).replace(lineStamp, "").isEmpty() }.toList()
                if (stamps.isEmpty()) return@forEach
                val content = row.substring(stamps.last().range.last + 1)
                val wordTimes = wordStamp.findAll(content).toList()
                val clean = content.replace(wordStamp, "").trim()
                if (clean.isEmpty()) return@forEach
                val runs = wordTimes.mapIndexedNotNull { index, match ->
                    val next = wordTimes.getOrNull(index + 1)
                    val chunk = content.substring(match.range.last + 1, next?.range?.first ?: content.length)
                    if (chunk.isEmpty()) null else Run(chunk, clock(match.groupValues[1]) + offset,
                        next?.let { clock(it.groupValues[1]) + offset } ?: clock(match.groupValues[1]) + offset,
                        reliable = next != null)
                }
                stamps.forEach { stamp ->
                    val start = (clock(stamp.groupValues[1]) + offset).coerceAtLeast(0)
                    // Repeated line stamps don't establish shifted word stamps: time those rows as lines only.
                    val words = if (stamps.size == 1) words(runs, runs.any { it.text.any(Char::isWhitespace) }) else emptyList()
                    val end = words.maxOfOrNull { it.endTimeMs } ?: start
                    lines.add(LyricLine(start, clean, (end - start).coerceAtLeast(0), words, isReliableDuration = end > start))
                }
            }
        }
        return build(identity, provider, lines)
    }
}
