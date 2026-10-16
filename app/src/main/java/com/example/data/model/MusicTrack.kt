package com.example.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
    val audioUrl: String,
    val bitrateKbps: Int = 320,
    val qualityBadge: String = "HD • 320 kbps",
    val genre: String = "Unknown",
    val isLiked: Boolean = false,
    val isCached: Boolean = false,
    val singers: String = "",
    val writer: String = "",
    val language: String = "Unknown",
    val year: String = "",
    val source: String = "JIOSAAVN",
    val isrc: String = "",
    val isLossless: Boolean = false,
    val motionArtworkUrl: String? = null,
    /** Request policy for recommendation queues, not persisted over library metadata. */
    val recommendationOnly: Boolean = false
) {
    val streamUrl: String get() = audioUrl
    val coverArtUrl: String get() = coverUrl

    fun formatDuration(): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Clean parsed song title stripped of parenthetical/bracketed metadata
     * (e.g. (From "..."), [Bonus Track], (Feat. ...), [Official Video], etc.)
     */
    @delegate:Transient
    val cleanTitle: String by lazy(LazyThreadSafetyMode.PUBLICATION) { computeCleanTitle(title) }

    companion object {
        private val bracketedMetadata = Regex("\\[.*?\\]")
        private val quotedSoundtrack = Regex("(?i)\\(\\s*from\\s+[\"'].*?[\"']\\s*\\)")
        private val soundtrack = Regex("(?i)\\(\\s*from\\s+.*?\\)")
        private val parenNoise = Regex(
            "(?i)\\((?:from\\b|feat\\b|ft\\b|featuring\\b|with\\b|official|lyric|music\\s*video|video|audio|remix|remaster|deluxe|bonus|live|acoustic|radio|club|slowed|reverb|ost|soundtrack|theme|trending|version).*?\\)"
        )
        private val trailingNoise = Regex("(?i)(official|video|audio|lyrics|t-series|remaster|hd|4k|cover|slowed|reverb|full\\s*song|from|feat|ft|version)")
        private val quotes = Regex("[\"“”'’]")
        private val whitespace = Regex("\\s+")
        private val delimiters = listOf(" | ", " • ", " — ", " – ", " - ")

        private fun computeCleanTitle(title: String): String {
            var t = title.trim()
            if (t.isBlank()) return title

            // 1. Remove bracketed metadata: [Official Video], [HD], [Bonus Track], etc.
            t = t.replace(bracketedMetadata, " ")

            // 2. Remove (From "...") / (From '...') soundtrack tags specifically
            t = t.replace(quotedSoundtrack, " ")
            t = t.replace(soundtrack, " ")

            // 3. Remove common parenthetical noise (feat, official, audio, video, remaster, etc.)
            t = t.replace(parenNoise, " ")

            // 4. Remove common trailing noise after separators: " | ", " • ", " — ", " – ", " - "
            for (delim in delimiters) {
                if (t.contains(delim)) {
                    val parts = t.split(delim)
                    val firstPart = parts[0].trim()
                    val secondPart = parts.getOrNull(1)?.trim() ?: ""
                    if (trailingNoise.containsMatchIn(secondPart)) {
                        t = firstPart
                    }
                }
            }

            // 5. Clean quotes and collapse whitespace
            t = t.replace(quotes, "")
                .replace(whitespace, " ")
                .trim()

            // 6. Secondary fallback check for dangling parentheses
            if (t.contains(" (")) {
                val candidate = t.substringBefore(" (").trim()
                if (candidate.isNotBlank() && candidate.length >= 2) {
                    t = candidate
                }
            }

            return if (t.isNotBlank()) t else title
        }
    }
}
