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
    val genre: String = "Electronic",
    val isLiked: Boolean = false,
    val isCached: Boolean = false,
    val singers: String = "",
    val writer: String = "",
    val language: String = "Hindi",
    val year: String = "",
    val source: String = "JIOSAAVN",
    val isrc: String = "",
    val isLossless: Boolean = false,
    val motionArtworkUrl: String? = null
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
    val cleanTitle: String
        get() {
            var t = title.trim()
            if (t.isBlank()) return title

            // 1. Remove bracketed metadata: [Official Video], [HD], [Bonus Track], etc.
            t = t.replace(Regex("\\[.*?\\]"), " ")

            // 2. Remove (From "...") / (From '...') soundtrack tags specifically
            t = t.replace(Regex("(?i)\\(\\s*from\\s+[\"'].*?[\"']\\s*\\)"), " ")
            t = t.replace(Regex("(?i)\\(\\s*from\\s+.*?\\)"), " ")

            // 3. Remove common parenthetical noise (feat, official, audio, video, remaster, etc.)
            val parenNoise = Regex(
                "(?i)\\((?:from\\b|feat\\b|ft\\b|featuring\\b|with\\b|official|lyric|music\\s*video|video|audio|remix|remaster|deluxe|bonus|live|acoustic|radio|club|slowed|reverb|ost|soundtrack|theme|trending|version).*?\\)"
            )
            t = t.replace(parenNoise, " ")

            // 4. Remove common trailing noise after separators: " | ", " • ", " — ", " – ", " - "
            for (delim in listOf(" | ", " • ", " — ", " – ", " - ")) {
                if (t.contains(delim)) {
                    val parts = t.split(delim)
                    val firstPart = parts[0].trim()
                    val secondPart = parts.getOrNull(1)?.trim() ?: ""
                    if (secondPart.contains(Regex("(?i)(official|video|audio|lyrics|t-series|remaster|hd|4k|cover|slowed|reverb|full\\s*song|from|feat|ft|version)"))) {
                        t = firstPart
                    }
                }
            }

            // 5. Clean quotes and collapse whitespace
            t = t.replace(Regex("[\"“”'’]"), "")
                .replace(Regex("\\s+"), " ")
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
