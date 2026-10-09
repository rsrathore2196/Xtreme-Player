package com.example.data.remote

import android.util.Log
import com.example.data.model.LyricLine
import com.example.data.model.LyricSyllable
import com.example.data.model.LyricWord
import com.example.data.model.SongIdentity
import com.example.data.model.TrackLyrics
import java.util.regex.Pattern

object BetterLyricsParser {
    private const val TAG = "BetterLyricsParser"

    fun parseTtmlTimestamp(raw: String): Long {
        val s = raw.trim().removeSuffix("s")
        return try {
            if (s.contains(':')) {
                val parts = s.split(':')
                if (parts.size == 3) {
                    val hours = parts[0].toDoubleOrNull() ?: 0.0
                    val minutes = parts[1].toDoubleOrNull() ?: 0.0
                    val seconds = parts[2].toDoubleOrNull() ?: 0.0
                    ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
                } else if (parts.size == 2) {
                    val minutes = parts[0].toDoubleOrNull() ?: 0.0
                    val seconds = parts[1].toDoubleOrNull() ?: 0.0
                    ((minutes * 60 + seconds) * 1000).toLong()
                } else 0L
            } else {
                val seconds = s.toDoubleOrNull() ?: 0.0
                (seconds * 1000).toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun parseTtml(identity: SongIdentity, ttml: String): TrackLyrics? {
        val lines = mutableListOf<LyricLine>()
        val pRegex = Pattern.compile("<p\\s+[^>]*begin=\"([^\"]+)\"[^>]*(?:end=\"([^\"]+)\")?[^>]*>(.*?)</p>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val pMatcher = pRegex.matcher(ttml)

        val spanRegex = Pattern.compile("<span\\s+[^>]*begin=\"([^\"]+)\"[^>]*(?:end=\"([^\"]+)\")?[^>]*>(.*?)</span>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)

        var bodyDurSec = 0
        val durMatcher = Pattern.compile("<body\\s+[^>]*dur=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(ttml)
        if (durMatcher.find()) {
            val durStr = durMatcher.group(1) ?: ""
            val durMs = parseTtmlTimestamp(durStr)
            bodyDurSec = (durMs / 1000L).toInt()
        }

        val songWriters = mutableListOf<String>()
        val swMatcher = Pattern.compile("<songwriter>(.*?)</songwriter>", Pattern.CASE_INSENSITIVE).matcher(ttml)
        while (swMatcher.find()) {
            val sw = swMatcher.group(1)?.trim() ?: ""
            if (sw.isNotBlank() && !songWriters.contains(sw)) {
                songWriters.add(sw)
            }
        }

        var language: String? = null
        val langMatcher = Pattern.compile("xml:lang=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(ttml)
        if (langMatcher.find()) {
            language = langMatcher.group(1)?.trim()
        }

        var hasWordTiming = false
        var hasRomanization = false
        var hasTranslation = false

        while (pMatcher.find()) {
            val beginStr = pMatcher.group(1) ?: continue
            val endStr = pMatcher.group(2)
            val pContent = pMatcher.group(3) ?: ""

            val beginMs = parseTtmlTimestamp(beginStr)
            val endMs = if (!endStr.isNullOrBlank()) parseTtmlTimestamp(endStr) else beginMs + 3000L
            val durMs = (endMs - beginMs).coerceAtLeast(500L)

            val words = mutableListOf<LyricWord>()
            val currentWordSyllables = mutableListOf<LyricSyllable>()
            val currentWordText = StringBuilder()
            var currentWordStartMs = 0L
            var currentWordEndMs = 0L
            var lastSpanEndPos = 0

            val spanMatcher = spanRegex.matcher(pContent)
            while (spanMatcher.find()) {
                val spanStartInP = spanMatcher.start()
                val gapText = pContent.substring(lastSpanEndPos, spanStartInP)
                val isNewWord = gapText.contains(" ") || gapText.contains("\n") || gapText.contains("\t")

                val sBeginStr = spanMatcher.group(1) ?: continue
                val sEndStr = spanMatcher.group(2)
                val sText = spanMatcher.group(3)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""

                val sBeginMs = parseTtmlTimestamp(sBeginStr)
                val sEndMs = if (!sEndStr.isNullOrBlank()) parseTtmlTimestamp(sEndStr) else sBeginMs + 250L
                val sDur = (sEndMs - sBeginMs).coerceAtLeast(40L)

                if (sText.isNotBlank()) {
                    if (isNewWord && currentWordSyllables.isNotEmpty()) {
                        words.add(
                            LyricWord(
                                timestampMs = currentWordStartMs,
                                durationMs = (currentWordEndMs - currentWordStartMs).coerceAtLeast(50L),
                                text = currentWordText.toString(),
                                syllables = currentWordSyllables.toList()
                            )
                        )
                        currentWordSyllables.clear()
                        currentWordText.clear()
                    }
                    if (currentWordSyllables.isEmpty()) {
                        currentWordStartMs = sBeginMs
                    }
                    currentWordEndMs = sEndMs
                    currentWordText.append(sText)
                    currentWordSyllables.add(LyricSyllable(sBeginMs, sDur, sText))
                }
                lastSpanEndPos = spanMatcher.end()
            }

            if (currentWordSyllables.isNotEmpty()) {
                words.add(
                    LyricWord(
                        timestampMs = currentWordStartMs,
                        durationMs = (currentWordEndMs - currentWordStartMs).coerceAtLeast(50L),
                        text = currentWordText.toString(),
                        syllables = currentWordSyllables.toList()
                    )
                )
            }

            if (words.isNotEmpty()) {
                hasWordTiming = true
            }

            // Extract romanization from ruby tag if present
            var lineRomanization: String? = null
            val rubyMatcher = Pattern.compile("<ruby>.*?<rt>(.*?)</rt>.*?</ruby>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(pContent)
            if (rubyMatcher.find()) {
                lineRomanization = rubyMatcher.group(1)?.replace(Regex("<[^>]+>"), "")?.trim()
                if (!lineRomanization.isNullOrBlank()) hasRomanization = true
            }

            val cleanText = pContent.replace(Regex("<rt>.*?</rt>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleanText.isNotBlank()) {
                val effectiveDur = if (words.isNotEmpty()) {
                    (words.last().endTimeMs - beginMs).coerceAtLeast(100L)
                } else durMs

                lines.add(
                    LyricLine(
                        timestampMs = beginMs,
                        text = cleanText,
                        durationMs = effectiveDur,
                        words = words,
                        romanization = lineRomanization,
                        isReliableDuration = !endStr.isNullOrBlank() || words.isNotEmpty()
                    )
                )
            }
        }

        if (lines.isEmpty()) return null

        val targetDurSec = ((identity.durationMs ?: 0L) / 1000L).toInt()
        val effectiveDurSec = if (bodyDurSec > 0) bodyDurSec else ((lines.last().timestampMs + lines.last().durationMs) / 1000L).toInt()
        if (targetDurSec > 30 && effectiveDurSec > 30) {
            val diff = kotlin.math.abs(targetDurSec - effectiveDurSec)
            if (diff > 45 && (diff.toDouble() / targetDurSec) > 0.25) {
                Log.d(TAG, "BetterLyrics duration check rejected: ${diff}s diff")
                return null
            }
        }

        val rawLyrics = TrackLyrics(
            trackId = identity.trackId ?: "",
            title = identity.title,
            artist = identity.artist ?: "",
            isSynced = true,
            lines = lines,
            provider = "BetterLyrics",
            language = language,
            hasWordTiming = hasWordTiming,
            hasTranslation = hasTranslation,
            hasRomanization = hasRomanization,
            songWriters = songWriters
        )
        return SyllableSynthesizer.enrichLyrics(rawLyrics)
    }
}
