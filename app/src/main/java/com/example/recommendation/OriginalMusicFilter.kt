package com.example.recommendation

import com.example.data.model.MusicTrack
import java.util.Locale

/**
 * Shared Original-Music Verification and Filtering System for Xtreme Player.
 *
 * Ensures Home recommendations display exclusively genuine original recordings,
 * official studio singles, and verified albums while excluding:
 * - Unofficial remixes, bootlegs, and DJ edits
 * - Mashups and mega mixes
 * - Slowed + reverb and sped-up / nightcore modifications
 * - Fan covers, tributes, and amateur remakes
 * - Karaoke, backing tracks, and instrumentals
 * - Podcasts, audiobooks, and interviews
 * - Compilations, audio jukeboxes, and custom playlists
 * - SoundHelix and placeholder demo tracks
 *
 * Note: A track is NOT considered original merely because its title contains "Official".
 */
object OriginalMusicFilter {

    /** Home-only policy leaves explicit search and playback filtering behavior intact. */
    fun isHomeRecommendation(track: MusicTrack): Boolean {
        if (track.id.startsWith("xtreme_", true) || track.id.startsWith("demo", true) ||
            track.audioUrl.contains("soundhelix", true) || track.title.contains("soundhelix", true)) return false
        if (!HomeRecommendationPolicy.known(track.artist) || track.title.isBlank()) return false
        if (track.album.equals("YouTube Playlist", true) || track.source.contains("podcast", true)) return false
        val text = "${track.title} ${track.album}"
        if (BANNED_TITLE_PATTERNS.any { it.containsMatchIn(text) }) return false
        if (Regex("(?i)\\b(?:unofficial|bootleg|fan[ -]made|ai[ -](?:cover|generated)|reverb|demo\\s+track)\\b").containsMatchIn(text)) return false
        // A legitimate artist may have DJ in their name; it is not evidence of a fake upload.
        return !Regex("(?i)\\b(?:karaoke|tribute band|cover artist|remix channel)\\b").containsMatchIn(track.artist)
    }

    private val BANNED_TITLE_PATTERNS = listOf(
        // Remixes & DJ Edits
        Regex("(?i)\\b(?:remix|re-mix|rmx)\\b"),
        Regex("(?i)\\b(?:mashup|mash-up|mash\\s+up)\\b"),
        Regex("(?i)\\b(?:dj\\s+(?:edit|mix|remix|version|club|blast|drop))\\b"),
        Regex("(?i)\\b(?:club\\s+mix|extended\\s+mix|dance\\s+mix|trap\\s+mix|edm\\s+mix)\\b"),
        Regex("(?i)\\b(?:bass\\s+boost(?:ed)?|8d\\s+audio|3d\\s+audio|dhol\\s+mix|bhangra\\s+mix)\\b"),
        Regex("(?i)\\b(?:tiktok\\s+version|reels\\s+version|viral\\s+version|trend\\s+version)\\b"),

        // Slowed & Sped Up
        Regex("(?i)\\b(?:slowed|slowed\\s*[+&]\\s*reverb|slowed\\s+and\\s+reverb|reverb\\s+version)\\b"),
        Regex("(?i)\\b(?:chopped\\s+and\\s+screwed|slow\\s+version)\\b"),
        Regex("(?i)\\b(?:sped\\s+up|speed\\s+up|spedup|nightcore|daycore|fast\\s+version)\\b"),

        // Covers, Tributes & Fan Remakes
        Regex("(?i)\\b(?:cover|fan\\s+cover|acoustic\\s+cover|female\\s+cover|male\\s+cover)\\b"),
        Regex("(?i)\\b(?:female\\s+version|male\\s+version|cover\\s+version|unplugged\\s+cover)\\b"),
        Regex("(?i)\\b(?:tribute\\s+to|in\\s+the\\s+style\\s+of)\\b"),

        // Karaoke & Instrumental
        Regex("(?i)\\b(?:karaoke|backing\\s+track|minus\\s+one|ringtone|instrumental\\s+version)\\b"),

        // Podcasts & Non-Music Content
        Regex("(?i)\\b(?:podcast|episode|audiobook|interview|commentary|reaction|review)\\b"),
        Regex("(?i)\\b(?:status\\s+video|whatsapp\\s+status|shorts\\s+audio)\\b"),

        // Full Jukeboxes, Mega Mixes & Compilations
        Regex("(?i)\\b(?:jukebox|audio\\s+jukebox|all\\s+songs|full\\s+album|mega\\s*mix|megamix)\\b"),
        Regex("(?i)\\b(?:top\\s+\\d+\\s+songs|hits\\s+collection|non\\s*stop)\\b")
    )

    private val BANNED_ARTIST_PATTERNS = listOf(
        Regex("(?i)\\b(?:various\\s+artists|unknown\\s+artist|online\\s+artist|youtube\\s+artist)\\b"),
        Regex("(?i)\\b(?:dj\\s+[a-z0-9]+|remix\\s+[a-z0-9]+|[a-z0-9]+\\s+edits|[a-z0-9]+\\s+lofi)\\b"),
        Regex("(?i)\\b(?:podcast|karaoke|tribute\\s+band|cover\\s+artist)\\b")
    )

    /**
     * Determines whether a [MusicTrack] is a verified, genuine original recording.
     */
    fun isOriginalMusic(track: MusicTrack): Boolean {
        // 1. Remove SoundHelix and demo tracks
        if (track.id.startsWith("xtreme_") ||
            track.audioUrl.contains("soundhelix.com", ignoreCase = true) ||
            track.title.contains("SoundHelix", ignoreCase = true)
        ) {
            return false
        }

        // 2. Reject non-song durations (under 45 seconds or over 15 minutes)
        if (track.durationMs in 1..44_999L || track.durationMs > 900_000L) {
            return false
        }

        val title = track.title.trim()
        val artist = track.artist.trim()
        val album = track.album.trim()

        if (title.isBlank()) return false

        // 3. Reject titles matching banned derivative patterns
        for (pattern in BANNED_TITLE_PATTERNS) {
            if (pattern.containsMatchIn(title)) {
                return false
            }
        }

        // 4. Reject album names that indicate compilations, remixes, or custom playlists
        if (album.isNotBlank()) {
            if (album.equals("YouTube Playlist", ignoreCase = true) ||
                album.contains("Jukebox", ignoreCase = true) ||
                album.contains("Remix", ignoreCase = true) ||
                album.contains("Slowed", ignoreCase = true) ||
                album.contains("Karaoke", ignoreCase = true)
            ) {
                return false
            }
        }

        // 5. Reject artists that indicate fan edits, bootleg mixers, or generic placeholders
        for (pattern in BANNED_ARTIST_PATTERNS) {
            if (pattern.containsMatchIn(artist)) {
                return false
            }
        }

        // 6. Content-type check: Reject if source is marked as podcast, playlist, or non-song
        val source = track.source.trim()
        if (source.equals("Podcast", ignoreCase = true) ||
            source.equals("Playlist", ignoreCase = true)
        ) {
            return false
        }

        return true
    }

    /**
     * Determines whether a user's search query explicitly requests an alternative version
     * (e.g. remix, acoustic, cover, sped up, live, etc.).
     */
    fun isExplicitAlternativeQuery(query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return false
        val explicitAlternativeKeywords = listOf(
            "remix", "re-mix", "rmx", "mashup", "mash-up", "mash up",
            "slowed", "reverb", "sped up", "speed up", "spedup", "nightcore",
            "cover", "acoustic", "live", "unplugged", "instrumental", "karaoke",
            "dj edit", "club mix", "extended mix", "tribute", "lofi", "lo-fi"
        )
        return explicitAlternativeKeywords.any { q.contains(it) }
    }

    /**
     * Computes an originality ranking score for a track given an optional user search query.
     * Higher score = higher priority in search results and playlist matches.
     * Genuine original recordings receive high priority (100+), while unofficial uploads,
     * fan covers, slowed/reverb, and bootlegs receive severe penalties unless explicitly searched.
     */
    fun calculateOriginalityScore(track: MusicTrack, userQuery: String = ""): Double {
        val isExplicitAlt = isExplicitAlternativeQuery(userQuery)
        val isVerifiedOriginal = isOriginalMusic(track)

        var score = 50.0

        if (isVerifiedOriginal) {
            score += 60.0 // Base bonus for verified original recording
        } else if (!isExplicitAlt) {
            score -= 80.0 // Heavy penalty if not verified original and user did NOT ask for alt version
        }

        val title = track.title.trim()
        val artist = track.artist.trim()
        val album = track.album.trim()

        // 1. ISRC presence indicates official industry recording registration
        if (track.isrc.isNotBlank()) {
            score += 25.0
        }

        // 2. Official release / album verification
        if (album.isNotBlank() &&
            !album.equals("Single", ignoreCase = true) &&
            !album.equals("YouTube Music", ignoreCase = true) &&
            !album.equals("Online Stream", ignoreCase = true) &&
            !album.contains("Playlist", ignoreCase = true)
        ) {
            score += 15.0
        }

        // 3. Known verified source preference
        if (track.source.equals("JIOSAAVN", ignoreCase = true)) {
            score += 10.0
        }

        // 4. Playable stream bonus
        if (track.audioUrl.isNotBlank()) {
            score += 10.0
        }

        // 5. Penalties for banned title patterns when not explicitly requested
        if (!isExplicitAlt) {
            for (pattern in BANNED_TITLE_PATTERNS) {
                if (pattern.containsMatchIn(title)) {
                    score -= 50.0
                    break
                }
            }
            for (pattern in BANNED_ARTIST_PATTERNS) {
                if (pattern.containsMatchIn(artist)) {
                    score -= 50.0
                    break
                }
            }
        }

        // 6. SoundHelix / demo track absolute disqualification
        if (track.id.startsWith("xtreme_") ||
            track.audioUrl.contains("soundhelix.com", ignoreCase = true) ||
            track.title.contains("SoundHelix", ignoreCase = true)
        ) {
            score = -1000.0
        }

        return score
    }

    /**
     * Filters a list of tracks, keeping only verified original recordings and official releases.
     */
    fun filterOriginals(tracks: List<MusicTrack>): List<MusicTrack> {
        return tracks.filter { isOriginalMusic(it) }
    }
}
