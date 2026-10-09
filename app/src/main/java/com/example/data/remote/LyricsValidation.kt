package com.example.data.remote

import android.util.Log
import com.example.data.model.SongIdentity

object LyricsValidation {
    private const val TAG = "LyricsValidation"

    fun calculateSimilarity(s1: String, s2: String): Double {
        val norm1 = normalizeForSimilarity(s1)
        val norm2 = normalizeForSimilarity(s2)
        if (norm1 == norm2) return 1.0
        if (norm1.isEmpty() || norm2.isEmpty()) return 0.0
        val maxLen = maxOf(norm1.length, norm2.length)
        val distance = levenshteinDistance(norm1, norm2)
        return (1.0 - (distance.toDouble() / maxLen)).coerceIn(0.0, 1.0)
    }

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
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[len2]
    }

    fun normalizeForSimilarity(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
        return decomposed.lowercase()
            .replace(Regex("[\"“”'’\\[\\]()]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Evaluates sequence-preserving title match.
     * Prevents reversed or shuffled token equivalence (e.g. "Do Gallan" vs "Gallan Do").
     */
    fun evaluateTitleMatch(
        candRawTitle: String,
        candArtist: String,
        cleanTitle: String,
        coreTitle: String,
        allArtists: List<String>
    ): Double {
        val normClean = normalizeForSimilarity(cleanTitle)
        val normCore = normalizeForSimilarity(coreTitle)
        val normRaw = normalizeForSimilarity(candRawTitle)

        val (cleanCand, coreCand) = LyricsProvider.sanitizeTitle(candRawTitle)
        val normCand = normalizeForSimilarity(cleanCand)
        val normCoreCand = normalizeForSimilarity(coreCand)

        // Exact match
        if (normCand == normClean || normCand == normCore || normCoreCand == normClean || normCoreCand == normCore) {
            return 1.0
        }

        // Sequential phrase containment
        if (normCore.length >= 4) {
            if (normCand.startsWith("$normCore ") || normRaw.startsWith("$normCore ")) {
                return 0.95
            }
            if (normCand.endsWith(" $normCore") || normRaw.endsWith(" $normCore")) {
                return 0.92
            }
        }
        if (normClean.length >= 4) {
            if (normCand.startsWith("$normClean ") || normRaw.startsWith("$normClean ")) {
                return 0.95
            }
        }

        val simClean = calculateSimilarity(cleanCand, cleanTitle)
        val simCore = calculateSimilarity(coreCand, coreTitle)
        val simCleanCore = calculateSimilarity(cleanCand, coreTitle)
        val simRaw = calculateSimilarity(candRawTitle, cleanTitle)
        val bestLevenshtein = maxOf(simClean, simCore, simCleanCore, simRaw)

        // Word order / inversion protection (e.g. "Do Gallan" vs "Gallan Do")
        val targetTokens = normClean.split(" ").filter { it.length >= 2 }
        val candTokens = normCand.split(" ").filter { it.length >= 2 }
        if (targetTokens.size >= 2 && candTokens.size >= 2) {
            val targetFirst = targetTokens.first()
            val targetLast = targetTokens.last()
            val candFirst = candTokens.first()
            val candLast = candTokens.last()
            if (targetFirst == candLast && targetLast == candFirst && targetFirst != targetLast) {
                return minOf(bestLevenshtein, 0.25)
            }
        }

        return bestLevenshtein
    }

    /**
     * Strict multi-artist validator.
     * Rejects candidate if candidate artist does not match any recognized artist.
     */
    fun validateArtistMatch(candidateArtist: String, primaryArtist: String, allArtists: List<String>): Boolean {
        val normCandidate = normalizeForSimilarity(candidateArtist)
        if (normCandidate.isBlank()) return false

        val normPrimary = normalizeForSimilarity(primaryArtist)
        if (normPrimary.length >= 3) {
            if (normCandidate == normPrimary) return true
            if (normCandidate.startsWith("$normPrimary ") || normCandidate.endsWith(" $normPrimary") || normCandidate.contains(" $normPrimary ")) return true
            if (normPrimary.startsWith("$normCandidate ") || normPrimary.endsWith(" $normCandidate") || normPrimary.contains(" $normCandidate ")) return true
            if (calculateSimilarity(normCandidate, normPrimary) >= 0.75) return true
        }

        for (artist in allArtists) {
            val normArtist = normalizeForSimilarity(artist)
            if (normArtist.length >= 3) {
                if (normCandidate == normArtist) return true
                if (normCandidate.startsWith("$normArtist ") || normCandidate.endsWith(" $normArtist") || normCandidate.contains(" $normArtist ")) return true
                if (normArtist.startsWith("$normCandidate ") || normArtist.endsWith(" $normCandidate") || normArtist.contains(" $normCandidate ")) return true
                if (calculateSimilarity(normCandidate, normArtist) >= 0.75) return true
            }
        }

        val candidateTokens = normCandidate.split(" ").filter { it.length >= 3 && !it.matches(Regex("^(the|and|feat|featuring|official|records|music|prod)$")) }
        for (artist in allArtists) {
            val artistTokens = normalizeForSimilarity(artist).split(" ").filter { it.length >= 3 && !it.matches(Regex("^(the|and|feat|featuring|official|records|music|prod)$")) }
            if (candidateTokens.isNotEmpty() && artistTokens.isNotEmpty()) {
                val matchCount = candidateTokens.count { artistTokens.contains(it) }
                if (matchCount >= 2 || (matchCount >= 1 && (candidateTokens.size <= 2 || artistTokens.size <= 2))) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Comprehensive candidate gate:
     * Never match songs using title alone.
     * Rejects mismatched artists, permuted titles, and extreme duration discrepancies.
     */
    fun validateCandidate(
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int,
        identity: SongIdentity,
        cleanTitle: String,
        coreTitle: String,
        primaryArtist: String,
        allArtists: List<String>
    ): Boolean {
        // 1. Title Sequence Matching
        val titleSim = evaluateTitleMatch(candidateTitle, candidateArtist, cleanTitle, coreTitle, allArtists)
        if (titleSim < 0.65) {
            Log.d(TAG, "Candidate '$candidateTitle' rejected: title similarity ${(titleSim * 100).toInt()}% < 65%")
            return false
        }

        // 2. Artist Validation: NEVER match on title alone when artist is provided!
        if (candidateArtist.isNotBlank()) {
            val artistMatches = validateArtistMatch(candidateArtist, primaryArtist, allArtists)
            if (!artistMatches) {
                Log.d(TAG, "Candidate '$candidateTitle' by '$candidateArtist' rejected: artist mismatch against $allArtists")
                return false
            }
        } else {
            if (titleSim < 0.90) {
                Log.d(TAG, "Candidate '$candidateTitle' with missing artist rejected: title similarity < 90%")
                return false
            }
        }

        // 3. Duration Sanity Check
        val targetDurSec = ((identity.durationMs ?: 0L) / 1000L).toInt()
        if (targetDurSec > 30 && candidateDurationSec > 30) {
            val diff = kotlin.math.abs(targetDurSec - candidateDurationSec)
            if (diff > 35 && (diff.toDouble() / targetDurSec) > 0.20) {
                Log.d(TAG, "Candidate '$candidateTitle' rejected: duration diff ${diff}s too large")
                return false
            }
        }

        return true
    }
}
