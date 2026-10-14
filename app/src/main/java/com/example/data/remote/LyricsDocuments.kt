package com.example.data.remote

import android.text.Html
import com.example.data.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer
import java.util.Locale

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
                    val metadata = value.optJSONObject("metadata") ?: value.optJSONObject("meta") ?: value
                    val foundTitle = metadata.optString("trackName").ifBlank { (metadata.opt("title") as? String).orEmpty() }
                    val foundArtist = metadata.optString("artistName").ifBlank { (metadata.opt("artist") as? String).orEmpty() }
                    if ((foundTitle.isNotBlank() || foundArtist.isNotBlank()) && !identityMatches(identity,
                        foundTitle.ifBlank { identity.title }, foundArtist.ifBlank { identity.artist.orEmpty() })) return null
                    val suppliedDuration = metadata.optLong("durationMs", metadata.optLong("duration_ms", 0L))
                    if (suppliedDuration > 0 && (identity.durationMs ?: 0) > 0 && kotlin.math.abs(suppliedDuration - identity.durationMs!!) > 5_000) return null
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
        if (clean.isEmpty()) return null
        // A document extending beyond the recording is the wrong edit or uses the wrong units.
        val duration = identity.durationMs ?: 0
        if (synced && (clean.any { it.timestampMs < 0 || it.durationMs < 0 || it.endTimeMs < it.timestampMs } ||
            clean.zipWithNext().any { (a, b) -> b.timestampMs < a.timestampMs })) return null
        if (synced && duration > 0 && clean.any { maxOf(it.timestampMs, it.words.maxOfOrNull(LyricWord::endTimeMs) ?: 0) > duration + 5_000 }) return null
        return SyllableSynthesizer.enrichLyrics(TrackLyrics(identity.trackId.orEmpty(), identity.title,
            identity.artist.orEmpty(), synced, clean, provider = provider,
            hasWordTiming = clean.any { line -> line.words.any { it.isReliableTiming && it.durationMs > 0 } },
            hasTranslation = clean.any { !it.translation.isNullOrBlank() },
            hasRomanization = clean.any { !it.romanization.isNullOrBlank() }))
    }

    /** Stable ties retain source preference; availability/timing always outranks provider order. */
    private val providerOrder = listOf("lrc.red", "BiniLyrics", "BetterLyrics", "BetterLyrics Portato", "PaxSenix",
        "PaxSenix: Spotify", "PaxSenix: Musixmatch", "LyricsPlus", "Unison", "YouTube captions", "KuGou", "LRCLIB", "Musixmatch", "YouTube Music", "Genius")
    val comparator: Comparator<TrackLyrics> = compareBy<TrackLyrics> { quality(it) }
        .thenBy { (assessment(it).coverage * 20).toInt() }
        .thenBy { assessment(it).textLength / 25 }
        .thenBy { -providerOrder.indexOf(it.provider).let { index -> if (index < 0) providerOrder.size else index } }

    data class TimingAssessment(val tier: Int, val coverage: Double, val syllableCoverage: Double,
        val textLength: Int, val complete: Boolean)

    private fun text(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter {
            val type = Character.getType(it)
            it.isLetterOrDigit() || type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
        }
    private val versions = Regex("(?i)\\b(live|remix|acoustic|unplugged|instrumental|karaoke|cover|slowed|sped|reverb|nightcore|radio edit|remaster)\\b")
    fun identityMatches(identity: SongIdentity, title: String, artist: String): Boolean {
        if (versions.findAll(title).map { it.value.lowercase(Locale.ROOT) }.toSet() !=
            versions.findAll(identity.title).map { it.value.lowercase(Locale.ROOT) }.toSet()) return false
        val (clean, core) = LyricsProvider.sanitizeTitle(identity.title)
        val (primary, artists) = LyricsProvider.sanitizeArtist(identity.artist.orEmpty())
        return LyricsValidation.evaluateTitleMatch(title, artist, clean, core, artists) >= 0.85 &&
            (primary.isBlank() || LyricsValidation.validateArtistMatch(artist, primary, artists))
    }

    fun verified(identity: SongIdentity, lyrics: TrackLyrics): Boolean {
        if (lyrics.lines.isEmpty() || !identityMatches(identity, lyrics.title, lyrics.artist)) return false
        if (!lyrics.isSynced) return true
        val maximum = (identity.durationMs ?: 0L).takeIf { it > 0 }?.plus(5_000) ?: Long.MAX_VALUE
        if (lyrics.lines.any { line -> line.timestampMs < 0 || line.durationMs < 0 ||
            line.endTimeMs < line.timestampMs || line.timestampMs > maximum ||
            line.isReliableDuration && (line.durationMs <= 0 || line.endTimeMs > maximum) }) return false
        if (lyrics.lines.zipWithNext().any { (a, b) -> b.timestampMs < a.timestampMs }) return false
        for (line in lyrics.lines) {
            val words = line.words.filter { it.isReliableTiming }
            if (words.any { it.timestampMs < line.timestampMs || it.durationMs <= 0 ||
                it.endTimeMs <= it.timestampMs || it.endTimeMs > maximum ||
                line.isReliableDuration && it.endTimeMs > line.endTimeMs + 50 } ||
                words.zipWithNext().any { (a, b) -> b.timestampMs < a.timestampMs }) return false
        }
        // An all-zero timestamp response is plain text, not genuine line synchronization.
        return lyrics.lines.any { it.timestampMs > 0 || it.isReliableDuration || it.words.any { word -> word.isReliableTiming } }
    }

    fun assessment(lyrics: TrackLyrics): TimingAssessment {
        val total = lyrics.lines.sumOf { text(it.text).length }.coerceAtLeast(1)
        if (lyrics.lines.isEmpty()) return TimingAssessment(-1, 0.0, 0.0, 0, false)
        if (!lyrics.isSynced) return TimingAssessment(0, 0.0, 0.0, total, true)
        var wordText = 0
        var syllableText = 0
        var splits = 0
        for (line in lyrics.lines) {
            val lineText = text(line.text)
            var cursor = 0
            var previous = line.timestampMs
            for (word in line.words) {
                val value = text(word.text)
                val at = if (value.isNotEmpty()) lineText.indexOf(value, cursor) else -1
                if (at < 0) continue
                cursor = at + value.length
                val valid = word.isReliableTiming && word.durationMs > 0 && word.timestampMs >= previous &&
                    word.timestampMs >= line.timestampMs && word.endTimeMs > word.timestampMs &&
                    (!line.isReliableDuration || word.endTimeMs <= line.endTimeMs + 50)
                previous = maxOf(previous, word.timestampMs)
                if (!valid) continue
                wordText += value.length
                val syllables = word.syllables
                val authentic = syllables.isNotEmpty() && text(syllables.joinToString("") { it.text }) == value &&
                    syllables.all { it.isReliableTiming && it.durationMs > 0 && it.timestampMs >= word.timestampMs &&
                        it.endTimeMs > it.timestampMs && it.endTimeMs <= word.endTimeMs + 50 } &&
                    syllables.zipWithNext().none { (a, b) -> b.timestampMs < a.timestampMs || b.timestampMs + 50 < a.endTimeMs }
                val single = !SyllableSynthesizer.isNonEnglish(word.text, lyrics.language, lyrics.artist) &&
                    SyllableSynthesizer.syllabifyWord(word.text, lyrics.language, lyrics.artist).size == 1
                if (authentic && syllables.size > 1) { splits++; syllableText += value.length }
                else if (single) syllableText += value.length
            }
        }
        val words = (wordText.toDouble() / total).coerceIn(0.0, 1.0)
        val syllables = (syllableText.toDouble() / total).coerceIn(0.0, 1.0)
        val substantial = lyrics.lines.size >= 3 && total >= 50 &&
            lyrics.lines.last().timestampMs - lyrics.lines.first().timestampMs >= 10_000
        val tier = when { substantial && words >= 0.9 && syllables >= 0.85 && splits >= 2 -> 3
            words >= 0.8 -> 2
            else -> 1 }
        return TimingAssessment(tier, words, syllables, total, substantial && (tier == 3 || tier == 2 && words >= 0.9))
    }

    fun quality(lyrics: TrackLyrics): Int {
        return assessment(lyrics).tier
    }

    fun meaningfulUpgrade(candidate: TrackLyrics, current: TrackLyrics?): Boolean {
        if (current == null) return candidate.lines.isNotEmpty()
        val next = assessment(candidate); val old = assessment(current)
        return next.tier > old.tier || next.tier == old.tier &&
            (next.coverage >= old.coverage + 0.05 || next.textLength >= old.textLength * 1.15 && next.coverage >= old.coverage)
    }

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
