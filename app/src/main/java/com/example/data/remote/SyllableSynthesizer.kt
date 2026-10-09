package com.example.data.remote

import com.example.data.model.LyricLine
import com.example.data.model.LyricSyllable
import com.example.data.model.LyricWord
import com.example.data.model.TrackLyrics

/**
 * Language-Aware Lyric Flow & Syllable Engine:
 *
 * 1. English Lyrics:
 *    - Uses syllable-level progression ONLY when reliable syllable timings are available.
 *    - If only word timings exist, uses smooth word-level highlighting without arbitrary letter splitting.
 *    - Never treats individual letters as syllables or splits words arbitrarily.
 *
 * 2. Hindi, Punjabi, and all other non-English lyrics:
 *    - Uses words as the timing units, never individual characters or syllables.
 *    - Smoothly reveals the highlight across each whole word during its sung interval.
 *    - Preserves script shaping, combining marks, vowel signs, conjuncts, and grapheme clusters.
 *    - Never splits code points or alters rendered text.
 *    - Romanized Hindi and Punjabi retain word-level behavior.
 *
 * 3. Vocal Timings & Silent Gaps:
 *    - Each unit has explicit start and end timestamps.
 *    - Highlighting finishes at vocal end and holds completed during silent pauses until the next unit begins.
 */
object SyllableSynthesizer {

    private val ENGLISH_VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y', 'A', 'E', 'I', 'O', 'U', 'Y')

    // Common romanized Hindi and Punjabi words/suffixes to detect transliteration accurately
    private val ROMANIZED_INDIC_PATTERNS = setOf(
        "tere", "teri", "tera", "meray", "mere", "mera", "meri", "kudi", "kudiye", "mundey", "munde",
        "nach", "nachdi", "pyar", "ishq", "dil", "dila", "diyan", "vich", "haan", "yaar", "yaara",
        "saada", "jatt", "jatti", "pind", "akhiyan", "akhiyaan", "rabba", "soniye", "sohneya", "channa",
        "gaddi", "raatan", "soch", "zindagi", "duniya", "menu", "tainu", "chal", "chalo", "haye", "oye",
        "ve", "ni", "si", "te", "di", "da", "de", "do", "billo", "jind", "jaan", "saah", "kade", "jadon",
        "kadon", "ohna", "apna", "apni", "pyaar", "hum", "tum", "aaj", "kal", "saath", "raat", "tera",
        "mujhko", "tujhko", "mera", "meri", "mere", "zara", "paas", "door", "dekh", "dekho", "suno", "sun",
        "kya", "kyun", "kaise", "kahan", "nahi", "nahin", "hain", "hai", "tha", "thi", "the", "leke"
    )

    /**
     * Determines whether the given text or context is Indic / Non-English.
     */
    fun isNonEnglish(
        word: String,
        trackLanguage: String? = null,
        trackArtist: String? = null
    ): Boolean {
        val clean = word.trim().lowercase()
        if (clean.isEmpty()) return false

        // 1. Explicit native Indic scripts & Arabic/Gurmukhi Unicode ranges
        val hasIndicScript = clean.any { c ->
            val code = c.code
            // Devanagari (0x0900..0x097F), Bengali (0x0980..0x09FF), Gurmukhi (0x0A00..0x0A7F),
            // Gujarati (0x0A80..0x0AFF), Oriya (0x0B00..0x0B7F), Tamil (0x0B80..0x0BFF),
            // Telugu (0x0C00..0x0C7F), Kannada (0x0C80..0x0CFF), Malayalam (0x0D00..0x0D7F),
            // Arabic/Urdu (0x0600..0x06FF), CJK (0x4E00..0x9FFF, 0x3040..0x30FF, 0xAC00..0xD7AF)
            (code in 0x0900..0x0D7F) || (code in 0x0600..0x06FF) ||
                    (code in 0x4E00..0x9FFF) || (code in 0x3040..0x30FF) || (code in 0xAC00..0xD7AF)
        }
        if (hasIndicScript) return true

        // 2. Track language metadata
        val lang = trackLanguage?.lowercase().orEmpty()
        if (lang.contains("hindi") || lang.contains("punjabi") || lang.contains("urdu") ||
            lang.contains("tamil") || lang.contains("telugu") || lang.contains("spanish") ||
            lang.contains("korean") || lang.contains("japanese") || lang.contains("arabic")) {
            return true
        }

        // 3. Romanized Indic vocabulary check
        val strippedWord = clean.replace(Regex("[^a-z]"), "")
        if (ROMANIZED_INDIC_PATTERNS.contains(strippedWord)) {
            return true
        }

        // 4. Artist name heuristics for prominent Punjabi/Hindi artists
        val art = trackArtist?.lowercase().orEmpty()
        if (art.contains("diljit") || art.contains("sidhu") || art.contains("arijit") ||
            art.contains("karan aujla") || art.contains("ap dhillon") || art.contains("shubh") ||
            art.contains("badshah") || art.contains("honey singh") || art.contains("amrit maan") ||
            art.contains("gurdas") || art.contains("b praak") || art.contains("jasleen")) {
            return true
        }

        return false
    }

    /**
     * Syllabifies an English word into natural phonetic syllables.
     * For non-English words, always returns the whole word intact.
     */
    fun syllabifyWord(
        word: String,
        trackLanguage: String? = null,
        trackArtist: String? = null
    ): List<String> {
        val clean = word.trim()
        if (clean.length <= 3) return listOf(clean)

        // Non-English: NEVER split into sub-syllables. Whole word is the timing unit.
        if (isNonEnglish(clean, trackLanguage, trackArtist)) {
            return listOf(clean)
        }

        // English natural phonetic syllable parsing (V-CV or VC-CV boundaries)
        val syllables = mutableListOf<String>()
        var currentSyllable = StringBuilder()
        var hasVowel = false

        for (i in clean.indices) {
            val c = clean[i]
            val isVowel = c in ENGLISH_VOWELS
            val nextChar = clean.getOrNull(i + 1)
            val nextIsVowel = nextChar != null && nextChar in ENGLISH_VOWELS

            currentSyllable.append(c)
            if (isVowel) {
                hasVowel = true
            }

            // Split point heuristic (V-CV or VC-CV boundary)
            if (hasVowel && i < clean.length - 2) {
                val charAfterNext = clean.getOrNull(i + 2)
                val charAfterNextIsVowel = charAfterNext != null && charAfterNext in ENGLISH_VOWELS

                if (!isVowel && nextChar != null && charAfterNextIsVowel) {
                    syllables.add(currentSyllable.toString())
                    currentSyllable = StringBuilder()
                    hasVowel = false
                } else if (isVowel && !nextIsVowel && charAfterNextIsVowel) {
                    syllables.add(currentSyllable.toString())
                    currentSyllable = StringBuilder()
                    hasVowel = false
                }
            }
        }

        if (currentSyllable.isNotEmpty()) {
            if (syllables.isNotEmpty() && !hasVowel && currentSyllable.length <= 2) {
                val lastIndex = syllables.lastIndex
                val last = syllables.removeAt(lastIndex)
                syllables.add(last + currentSyllable.toString())
            } else {
                syllables.add(currentSyllable.toString())
            }
        }

        return if (syllables.isNotEmpty()) syllables else listOf(clean)
    }

    /**
     * Synthesizes LyricSyllable elements for an English word.
     * Non-English words return a single syllable containing the entire word.
     */
    fun synthesizeSyllablesForWord(
        wordText: String,
        wordStartMs: Long,
        wordDurationMs: Long,
        trackLanguage: String? = null,
        trackArtist: String? = null
    ): List<LyricSyllable> {
        val clean = wordText.trim()
        if (clean.isBlank()) return emptyList()

        val dur = wordDurationMs.coerceAtLeast(80L)

        // Non-English: Whole word is the timing unit
        if (isNonEnglish(clean, trackLanguage, trackArtist)) {
            return listOf(
                LyricSyllable(
                    timestampMs = wordStartMs,
                    durationMs = dur,
                    text = clean,
                    isReliableTiming = true
                )
            )
        }

        val parts = syllabifyWord(clean, trackLanguage, trackArtist)
        if (parts.size <= 1) {
            return listOf(
                LyricSyllable(
                    timestampMs = wordStartMs,
                    durationMs = dur,
                    text = clean,
                    isReliableTiming = true
                )
            )
        }

        val totalLength = parts.sumOf { it.length.coerceAtLeast(1) }
        val syllables = mutableListOf<LyricSyllable>()
        var currentStart = wordStartMs

        for (i in parts.indices) {
            val part = parts[i]
            val isLast = (i == parts.lastIndex)
            val partDur = if (isLast) {
                (wordStartMs + dur - currentStart).coerceAtLeast(40L)
            } else {
                ((part.length.toDouble() / totalLength) * dur).toLong().coerceAtLeast(40L)
            }
            syllables.add(
                LyricSyllable(
                    timestampMs = currentStart,
                    durationMs = partDur,
                    text = part,
                    isReliableTiming = false // Approximate subdivision from word timing
                )
            )
            currentStart += partDur
        }
        return syllables
    }

    /**
     * Enriches TrackLyrics:
     * - Preserves genuine word and syllable timings from TTML/BiniLyrics.
     * - Marks hasSyllableTiming = true only when real multi-syllable timings exist.
     * - Preserves non-English words as whole intact units.
     */
    fun enrichLyrics(lyrics: TrackLyrics): TrackLyrics {
        if (!lyrics.isSynced || lyrics.lines.isEmpty()) return lyrics

        var hasMultiSyllableTiming = false

        val enrichedLines = lyrics.lines.map { line ->
            if (line.words.isNotEmpty()) {
                val enrichedWords = line.words.map { word ->
                    val isNonEng = isNonEnglish(word.text, lyrics.language, lyrics.artist)
                    if (isNonEng) {
                        // Ensure non-English word has single whole-word syllable
                        word.copy(
                            language = "non-english",
                            syllables = listOf(
                                LyricSyllable(
                                    timestampMs = word.timestampMs,
                                    durationMs = word.durationMs,
                                    text = word.text,
                                    isReliableTiming = word.isReliableTiming
                                )
                            )
                        )
                    } else if (word.syllables.isNotEmpty()) {
                        if (word.syllables.size > 1) {
                            hasMultiSyllableTiming = true
                        }
                        word.copy(language = "english")
                    } else {
                        // English word without pre-parsed syllables: synthesize approximate syllables
                        val synth = synthesizeSyllablesForWord(
                            word.text,
                            word.timestampMs,
                            word.durationMs,
                            lyrics.language,
                            lyrics.artist
                        )
                        word.copy(
                            language = "english",
                            syllables = synth
                        )
                    }
                }
                line.copy(words = enrichedWords)
            } else {
                // Line sync only (e.g. standard LRC).
                // DO NOT fabricate fake word timings here per Requirement 4.
                line
            }
        }

        return lyrics.copy(
            lines = enrichedLines,
            hasSyllableTiming = hasMultiSyllableTiming
        )
    }
}
