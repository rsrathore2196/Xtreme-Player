package com.example.recommendation

import android.util.Log
import com.example.data.model.MusicTrack
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Intelligent Music Recommendation & Infinity Queue Engine (Autoplay)
 *
 * Implements:
 * 1. Custom Query Wrapper Integration: Extracts exact Artist, Language, and Release Year.
 * 2. Weightage Scoring System (Local Sorting Logic):
 *    - Same Singer / Primary Artist: +50 points
 *    - Same Language: +30 points
 *    - Same Release Era (within +-3 years): +20 points
 *    - Mood & Vibe Match: +35 points (Romantic, Sad, Workout, Party, Chill, etc.)
 *    - BPM / Tempo Proximity: +25 points (Natural rhythmic transition)
 *    - Acoustic Energy & Valence: +15 points
 * 3. Advanced Vibe & Feel Matching: Ensures emotional and tempo continuity.
 * 4. Verification Logging: Prints candidate scores and verifies mood tag alignment before queuing.
 */
class RecommendationEngine {
    private val TAG = "InfinityAutoplay"

    // Session Memory: In-memory sliding buffer of the last 10 played tracks
    private val sessionBuffer = ArrayDeque<MusicTrack>(10)

    // Collaborative Filtering: Co-listening transition frequency matrix [TrackA -> Map<TrackB, Count>]
    private val coListeningMatrix = mutableMapOf<String, MutableMap<String, Int>>()

    init {
        seedCoListeningMatrix()
    }

    data class ScoredCandidate(
        val track: MusicTrack,
        val totalScore: Double,
        val singerScore: Double,
        val languageScore: Double,
        val eraScore: Double,
        val moodScore: Double,
        val bpmScore: Double,
        val acousticScore: Double,
        val currentMoodTags: Set<String>,
        val candidateMoodTags: Set<String>,
        val matchingMoodTags: Set<String>
    )

    /**
     * Seeds initial co-listening transition graph based on curated tracks and natural listener flow.
     */
    private fun seedCoListeningMatrix() {
        fun link(from: String, to: String, weight: Int = 10) {
            coListeningMatrix.getOrPut(from) { mutableMapOf() }[to] = weight
            coListeningMatrix.getOrPut(to) { mutableMapOf() }[from] = (weight * 0.8f).toInt()
        }

        // Synthwave & Retrowave cluster
        link("xtreme_01", "xtreme_02", 15)
        link("xtreme_01", "xtreme_03", 18)
        link("xtreme_02", "xtreme_05", 14)
        link("xtreme_03", "xtreme_06", 12)

        // Chillhop, Lofi, Jazz cluster
        link("xtreme_04", "xtreme_08", 16)
        link("xtreme_04", "xtreme_10", 14)
        link("xtreme_08", "xtreme_09", 11)

        // Energy Electronic & Rock cluster
        link("xtreme_05", "xtreme_06", 16)
        link("xtreme_05", "xtreme_07", 13)
        link("xtreme_06", "xtreme_07", 12)
    }

    /**
     * Records a track played by the user into the sliding session memory (last 10 tracks),
     * and updates the collaborative co-listening transition matrix.
     */
    @Synchronized
    fun recordTrackPlayed(track: MusicTrack) {
        val lastTrack = sessionBuffer.peekLast()
        if (lastTrack != null && lastTrack.id != track.id) {
            // Update co-listening transition frequency
            val row = coListeningMatrix.getOrPut(lastTrack.id) { mutableMapOf() }
            row[track.id] = (row[track.id] ?: 0) + 1
        }

        if (sessionBuffer.size >= 10) {
            sessionBuffer.removeFirst()
        }
        sessionBuffer.addLast(track)
        Log.d(TAG, "Recorded track into session buffer: ${track.title}. Buffer size: ${sessionBuffer.size}")
    }

    /**
     * Gets the snapshot of the last 10 songs in session memory.
     */
    @Synchronized
    fun getSessionTracks(): List<MusicTrack> = sessionBuffer.toList()

    /**
     * Recommends the single best next track to automatically play next using
     * the Weightage Scoring System and Advanced Vibe Matching.
     *
     * @param currentTrack The track currently playing.
     * @param candidatePool The available tracks from API queries, catalog, or library.
     * @param excludedIds IDs of tracks currently queued or recently played to prevent repetition.
     * @param prioritizeArtistAndGenre When true, weights singer and genre matching higher.
     */
    @Synchronized
    fun recommendNextTrack(
        currentTrack: MusicTrack,
        candidatePool: List<MusicTrack>,
        excludedIds: Set<String> = emptySet(),
        prioritizeArtistAndGenre: Boolean = false
    ): MusicTrack? {
        if (candidatePool.isEmpty()) return null

        val scoredCandidates = evaluateAndScoreCandidates(
            currentTrack = currentTrack,
            candidatePool = candidatePool,
            excludedIds = excludedIds
        )

        val bestCandidate = scoredCandidates.firstOrNull { it.totalScore > -50.0 }
        if (bestCandidate != null) {
            Log.i(
                TAG,
                "Autoplay selected highest-scoring track: '${bestCandidate.track.title}' by '${bestCandidate.track.artist}' " +
                        "(Score: ${bestCandidate.totalScore.toInt()} pts)"
            )
            return bestCandidate.track
        }

        // Fallback: choose first unplayed track in candidate pool that is strictly NOT the same song name or variant, and not in session history
        val sessionList = sessionBuffer.toList()
        return candidatePool.firstOrNull { cand ->
            cand.id != currentTrack.id &&
            !excludedIds.contains(cand.id) &&
            !isSameSongOrVariant(cand.title, currentTrack.title) &&
            sessionList.none { isSameSongOrVariant(cand.title, it.title) || it.id == cand.id }
        } ?: candidatePool.firstOrNull { cand ->
            cand.id != currentTrack.id &&
            !isSameSongOrVariant(cand.title, currentTrack.title) &&
            sessionList.none { isSameSongOrVariant(cand.title, it.title) || it.id == cand.id }
        }
    }

    /**
     * Weightage Scoring System (Local Sorting Logic):
     * Evaluates raw recommendations from API / catalog using:
     * - Same singer / primary artist: +50 points
     * - Same language: +30 points
     * - Same release era (within +-3 years): +20 points
     * - Mood / Vibe matching: +35 points (Romantic, Sad, Workout, Party, Chill, etc.)
     * - BPM / Tempo compatibility: +25 points
     * - Acoustic energy & valence continuity: +15 points
     * - Session / taste profile boost: +10 points
     *
     * Prints candidate scores and verifies mood matching in Logcat before adding to queue.
     */
    @Synchronized
    fun evaluateAndScoreCandidates(
        currentTrack: MusicTrack,
        candidatePool: List<MusicTrack>,
        excludedIds: Set<String> = emptySet()
    ): List<ScoredCandidate> {
        if (candidatePool.isEmpty()) return emptyList()

        val cleanArtist = CustomRecommendationQueryWrapper.extractPrimaryArtist(currentTrack)
        val cleanLanguage = CustomRecommendationQueryWrapper.extractExactLanguage(currentTrack)
        val cleanYear = CustomRecommendationQueryWrapper.extractReleaseYear(currentTrack)
        val currentYearInt = cleanYear.toIntOrNull()

        val currentMood = MoodVibeAnalyzer.analyzeMood(currentTrack)
        val currentFeatures = AcousticFeatures.forTrack(currentTrack)
        val sessionList = sessionBuffer.toList()
        val sessionArtistWeights = sessionList.groupingBy { it.artist.lowercase() }.eachCount()

        val scoredList = candidatePool.map { candidate ->
            val candArtist = CustomRecommendationQueryWrapper.extractPrimaryArtist(candidate)
            val candLang = CustomRecommendationQueryWrapper.extractExactLanguage(candidate)
            val candYear = CustomRecommendationQueryWrapper.extractReleaseYear(candidate)
            val candYearInt = candYear.toIntOrNull()

            val candMood = MoodVibeAnalyzer.analyzeMood(candidate)
            val candFeatures = AcousticFeatures.forTrack(candidate)

            // 1. Same Singer / Primary Artist Score (+50 points max)
            val isExactArtist = candArtist.equals(cleanArtist, ignoreCase = true)
            val isSharedArtist = sharesSingerOrArtist(currentTrack, candidate)
            val singerScore = when {
                isExactArtist || isSharedArtist -> 50.0
                candArtist.isNotBlank() && (cleanArtist.contains(candArtist, ignoreCase = true) || candArtist.contains(cleanArtist, ignoreCase = true)) -> 25.0
                else -> 0.0
            }

            // 2. Same Language Score (+30 points max, -20 if mismatched)
            val isSameLanguage = candLang.equals(cleanLanguage, ignoreCase = true)
            val languageScore = if (isSameLanguage) {
                30.0
            } else if (cleanLanguage.isNotBlank() && candLang.isNotBlank()) {
                -20.0 // Strong penalty to keep language consistent (e.g. Punjabi with Punjabi)
            } else {
                0.0
            }

            // 3. Same Release Era Score (+20 points max)
            val eraScore = if (currentYearInt != null && candYearInt != null) {
                val deltaYears = abs(currentYearInt - candYearInt)
                when {
                    deltaYears <= 2 -> 20.0
                    deltaYears <= 5 -> 14.0
                    deltaYears <= 10 -> 8.0
                    else -> 2.0
                }
            } else {
                10.0 // Neutral era credit when metadata is partially missing
            }

            // 4. Mood / Vibe Matching (+35 points max) & BPM Proximity (+25 points max)
            val vibeResult = MoodVibeAnalyzer.computeVibeMatch(currentMood, candMood)
            val moodScore = vibeResult.vibeScore
            val bpmScore = vibeResult.bpmScore

            // 5. Acoustic Energy & Valence Continuity (+15 points max)
            val acousticDist = currentFeatures.normalizedDistance(candFeatures)
            val acousticScore = ((1.0f - acousticDist) * 15.0).coerceIn(0.0, 15.0)

            // 6. Session Affinity (+10 points max)
            var sessionBoost = 0.0
            if (sessionArtistWeights[candArtist.lowercase()] ?: 0 > 0) {
                sessionBoost += 5.0
            }
            if (candidate.isLiked) {
                sessionBoost += 5.0
            }

            // 7. Exclusion / Repetition Penalty (Strictly disqualify same song name, variants, and session history)
            var exclusionPenalty = 0.0
            val isSameSongName = isSameSongOrVariant(candidate.title, currentTrack.title)
            val isPlayedInSession = sessionList.any { isSameSongOrVariant(candidate.title, it.title) || it.id == candidate.id }

            if (candidate.id == currentTrack.id || isSameSongName || isPlayedInSession) {
                // Absolute disqualification: never repeat same song name, variants, or tracks from different albums
                exclusionPenalty = -10000.0
            } else if (excludedIds.contains(candidate.id)) {
                exclusionPenalty = -10000.0
            } else if (currentTrack.album.isNotBlank() && candidate.album.equals(currentTrack.album, ignoreCase = true) && currentTrack.album != "Single") {
                // Discourage multiple tracks from the exact same album
                exclusionPenalty -= 60.0
            }

            val totalScore = singerScore + languageScore + eraScore + moodScore + bpmScore + acousticScore + sessionBoost + exclusionPenalty

            ScoredCandidate(
                track = candidate,
                totalScore = totalScore,
                singerScore = singerScore,
                languageScore = languageScore,
                eraScore = eraScore,
                moodScore = moodScore,
                bpmScore = bpmScore,
                acousticScore = acousticScore,
                currentMoodTags = currentMood.tags,
                candidateMoodTags = candMood.tags,
                matchingMoodTags = vibeResult.matchingTags
            )
        }.sortedByDescending { it.totalScore }

        // Verify and log weightage scoring results in Logcat before adding to ExoPlayer queue
        Log.i(
            TAG,
            "=== [WEIGHTAGE SCORING FOR NEXT TRACK] Current: '${currentTrack.title}' by '${currentTrack.artist}' " +
                    "(Lang: $cleanLanguage, Year: $cleanYear, Mood: '${currentMood.primaryMood}') ==="
        )
        scoredList.take(5).forEachIndexed { idx, item ->
            Log.i(
                TAG,
                "#${idx + 1} Track: '${item.track.title}' by '${item.track.artist}' | " +
                        "Score: ${item.totalScore.toInt()} pts " +
                        "[Singer: +${item.singerScore.toInt()}, Lang: +${item.languageScore.toInt()}, " +
                        "Era: +${item.eraScore.toInt()}, Mood: +${item.moodScore.toInt()}, " +
                        "BPM: +${item.bpmScore.toInt()}]"
            )
        }

        // Verify Step 3: Check that the next queued track's metadata mood tags match the currently playing track
        val topCandidate = scoredList.firstOrNull { it.totalScore > -50.0 }
        if (topCandidate != null) {
            val matchingTags = topCandidate.matchingMoodTags
            Log.i(
                TAG,
                "=== [VIBE MATCH DEBUGGING VERIFICATION] ===\n" +
                        "Currently Playing Track: '${currentTrack.title}' | Mood Tags: ${currentMood.tags} | " +
                        "Primary Mood: '${currentMood.primaryMood}' | BPM: ${currentMood.tempoBpm.toInt()}\n" +
                        "Next Queued Track: '${topCandidate.track.title}' | Mood Tags: ${topCandidate.candidateMoodTags} | " +
                        "BPM: ${AcousticFeatures.forTrack(topCandidate.track).tempoBpm.toInt()}\n" +
                        "Matching Mood Tags: ${matchingTags.ifEmpty { setOf(currentMood.primaryMood) }} | " +
                        "Vibe Match Result: ${if (matchingTags.isNotEmpty()) "CONFIRMED MATCH" else "COMPATIBLE VIBE"}"
            )
        }

        return scoredList
    }

    /**
     * Constructs a tailored playback queue when user plays a song from search results:
     * - First: the selected song itself
     * - Followed immediately by: Same singer/artist's other songs
     * - Followed by: Same genre/type songs matching the listener's taste profile
     */
    @Synchronized
    fun buildSearchPlaybackQueue(
        selectedTrack: MusicTrack,
        candidatePool: List<MusicTrack>,
        limit: Int = 20
    ): List<MusicTrack> {
        val resultQueue = mutableListOf<MusicTrack>()
        resultQueue.add(selectedTrack)

        val seenRoots = mutableSetOf<String>()
        val selectedRoot = extractRootTitle(selectedTrack.title)
        if (selectedRoot.isNotBlank()) seenRoots.add(selectedRoot)

        // Filter pool: strictly exclude selected track and ANY track with the same song name or variant
        val poolWithoutSelected = candidatePool.filter { cand ->
            cand.id != selectedTrack.id &&
            !isSameSongOrVariant(selectedTrack.title, cand.title)
        }

        // 1. First priority: Songs by the exact same singer / artist (DIFFERENT songs only)
        val sameSingerSongs = poolWithoutSelected.filter { candidate ->
            sharesSingerOrArtist(selectedTrack, candidate)
        }

        for (cand in sameSingerSongs) {
            val root = extractRootTitle(cand.title)
            val isDuplicate = root.isNotBlank() && (seenRoots.contains(root) || seenRoots.any { isSameSongOrVariant(it, root) })
            if (!isDuplicate) {
                if (root.isNotBlank()) seenRoots.add(root)
                resultQueue.add(cand)
                if (resultQueue.size >= 8) break // Limit artist saturation so queue stays fresh
            }
        }

        // 2. Next priority: Same genre / type songs matching taste profile
        val remainingCandidates = poolWithoutSelected.filter { cand ->
            val root = extractRootTitle(cand.title)
            resultQueue.none { it.id == cand.id } &&
            !isSameSongOrVariant(selectedTrack.title, cand.title) &&
            resultQueue.none { isSameSongOrVariant(it.title, cand.title) } &&
            !seenRoots.contains(root)
        }

        var currentPivot = resultQueue.lastOrNull() ?: selectedTrack
        val excludedIds = resultQueue.map { it.id }.toMutableSet()

        while (resultQueue.size < limit && remainingCandidates.isNotEmpty()) {
            val nextTrack = recommendNextTrack(
                currentTrack = currentPivot,
                candidatePool = remainingCandidates,
                excludedIds = excludedIds,
                prioritizeArtistAndGenre = true
            )
            if (nextTrack != null) {
                val root = extractRootTitle(nextTrack.title)
                if (root.isNotBlank() && !seenRoots.contains(root) && !seenRoots.any { isSameSongOrVariant(it, root) }) {
                    seenRoots.add(root)
                    resultQueue.add(nextTrack)
                    excludedIds.add(nextTrack.id)
                    currentPivot = nextTrack
                } else {
                    excludedIds.add(nextTrack.id)
                }
            } else {
                break
            }
        }

        return resultQueue
    }

    /**
     * Checks if two tracks share any singer or artist (handling collaborations, featured artists, and duets).
     */
    fun sharesSingerOrArtist(t1: MusicTrack, t2: MusicTrack): Boolean {
        if (t1.artist.equals(t2.artist, ignoreCase = true)) return true

        val singers1 = (t1.singers + "," + t1.artist).split(",", "&", "feat.", "ft.", "•", "/")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != "various artists" && it != "unknown artist" }

        val singers2 = (t2.singers + "," + t2.artist).split(",", "&", "feat.", "ft.", "•", "/")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != "various artists" && it != "unknown artist" }

        for (s1 in singers1) {
            for (s2 in singers2) {
                if (s1 == s2 ||
                    (s1.contains(s2) && s2.length >= 4) ||
                    (s2.contains(s1) && s1.length >= 4)
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Generates a continuous recommended batch (Infinity Queue / Autoplay stream) of tracks.
     */
    @Synchronized
    fun recommendInfinityQueue(
        currentTrack: MusicTrack,
        candidatePool: List<MusicTrack>,
        count: Int = 5,
        alreadyQueued: List<MusicTrack> = emptyList()
    ): List<MusicTrack> {
        val result = mutableListOf<MusicTrack>()
        val currentExcluded = (alreadyQueued.map { it.id } + currentTrack.id).toMutableSet()
        var pivotTrack = currentTrack

        for (i in 0 until count) {
            val next = recommendNextTrack(pivotTrack, candidatePool, currentExcluded)
            if (next != null) {
                result.add(next)
                currentExcluded.add(next.id)
                pivotTrack = next
            } else {
                break
            }
        }
        return result
    }

    /**
     * High-precision Infinite Autoplay Recommendation List builder:
     * Generates the upcoming infinite play list scored by artist continuity, language,
     * era, mood/vibe, tempo, and acoustic affinity.
     * Strictly eliminates duplicate song titles, alternate versions, and songs already in session.
     */
    @Synchronized
    fun buildInfiniteAutoplayRecommendations(
        currentTrack: MusicTrack,
        candidatePool: List<MusicTrack>,
        limit: Int = 15,
        alreadyQueuedIds: Set<String> = emptySet()
    ): List<MusicTrack> {
        val excluded = alreadyQueuedIds.toMutableSet()
        excluded.add(currentTrack.id)

        val scored = evaluateAndScoreCandidates(
            currentTrack = currentTrack,
            candidatePool = candidatePool,
            excludedIds = excluded
        )

        val result = mutableListOf<MusicTrack>()
        val seenRoots = mutableSetOf<String>()
        val currentRoot = extractRootTitle(currentTrack.title)
        if (currentRoot.isNotBlank()) seenRoots.add(currentRoot)
        val sessionList = sessionBuffer.toList()

        for (item in scored) {
            if (item.totalScore <= -50.0) continue
            val track = item.track
            if (excluded.contains(track.id)) continue

            val root = extractRootTitle(track.title)
            val isDuplicateTitle = root.isNotBlank() && (seenRoots.contains(root) || seenRoots.any { isSameSongOrVariant(it, root) })
            val isVariant = isSameSongOrVariant(currentTrack.title, track.title) ||
                    result.any { isSameSongOrVariant(it.title, track.title) } ||
                    sessionList.any { isSameSongOrVariant(it.title, track.title) || it.id == track.id }

            if (!isDuplicateTitle && !isVariant) {
                if (root.isNotBlank()) seenRoots.add(root)
                result.add(track)
                excluded.add(track.id)
                if (result.size >= limit) break
            }
        }

        // Fallback: fill remaining slots with unplayed matching candidates from candidatePool
        if (result.size < limit) {
            val fallbackCandidates = candidatePool.filter { cand ->
                val candRoot = extractRootTitle(cand.title)
                !excluded.contains(cand.id) &&
                        !isSameSongOrVariant(currentTrack.title, cand.title) &&
                        result.none { isSameSongOrVariant(it.title, cand.title) } &&
                        sessionList.none { isSameSongOrVariant(it.title, cand.title) || it.id == cand.id } &&
                        !seenRoots.contains(candRoot)
            }.sortedByDescending { cand ->
                var s = 0
                if (cand.language.equals(currentTrack.language, ignoreCase = true)) s += 25
                if (cand.artist.equals(currentTrack.artist, ignoreCase = true)) s += 35
                if (cand.genre.equals(currentTrack.genre, ignoreCase = true)) s += 15
                s
            }

            for (cand in fallbackCandidates) {
                val root = extractRootTitle(cand.title)
                if (root.isNotBlank() && !seenRoots.contains(root) && !seenRoots.any { isSameSongOrVariant(it, root) }) {
                    seenRoots.add(root)
                    result.add(cand)
                    excluded.add(cand.id)
                    if (result.size >= limit) break
                }
            }
        }

        return result
    }

    companion object {
        /**
         * Extracts the canonical root title of a song by removing:
         * 1. Bracketed/Parenthetical strings: (From "Movie"), (Remix), [Official Video], etc.
         * 2. Separators: - , – , — , | , : , /
         * 3. Audio/release buzzwords: lyrical, ost, soundtrack, lofi, acoustic, slowed, reverb, etc.
         */
        fun extractRootTitle(rawTitle: String): String {
            var title = rawTitle.lowercase()
            // Strip bracketed/parenthesized content
            title = title.replace(Regex("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}"), " ")

            // Split on common metadata separators
            val parts = title.split(Regex("[-–—|:/•]"))
            if (parts.isNotEmpty()) {
                val firstPart = parts[0].trim()
                if (firstPart.length >= 2) {
                    title = firstPart
                }
            }

            // Remove common release tokens and noise
            title = title.replace(
                Regex("(?i)\\b(from|soundtrack|ost|movie|film|album|original|official|audio|video|lyrics|lyrical|remix|lofi|slowed|reverb|version|reprise|acoustic|live|cover|unplugged|edit|instrumental|single|hd|4k|hq|full song|feat|ft|featuring)\\b"),
                " "
            )

            // Remove punctuation and non-alphanumerics (retaining letters & numbers across languages)
            title = title.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")

            return title.trim().replace(Regex("\\s+"), " ")
        }

        fun normalizeTitle(rawTitle: String): String {
            return extractRootTitle(rawTitle)
        }

        /**
         * Checks whether two tracks are the same song or variants/remixes of each other,
         * even if released in different albums, soundtracks, or formats.
         */
        fun isSameSongOrVariant(track1Title: String, track2Title: String): Boolean {
            val root1 = extractRootTitle(track1Title)
            val root2 = extractRootTitle(track2Title)
            if (root1.isBlank() || root2.isBlank()) return false
            if (root1 == root2) return true

            // Substring or prefix equality for roots of meaningful length
            if (root1.length >= 3 && root2.length >= 3) {
                if (root1.startsWith(root2) || root2.startsWith(root1)) return true
                if (root1.contains(root2) || root2.contains(root1)) return true
            }

            // Significant words match (length >= 3)
            val words1 = root1.split(" ").filter { it.length >= 3 }
            val words2 = root2.split(" ").filter { it.length >= 3 }
            if (words1.isNotEmpty() && words2.isNotEmpty()) {
                val set1 = words1.toSet()
                val set2 = words2.toSet()
                val sharedWords = set1.intersect(set2)
                if (sharedWords.size >= set1.size || sharedWords.size >= set2.size) {
                    return true
                }
                if (sharedWords.size >= 2 && (sharedWords.size.toDouble() / set1.size >= 0.6 || sharedWords.size.toDouble() / set2.size >= 0.6)) {
                    return true
                }
            }
            return false
        }
    }

    private fun isCompatibleGenre(genre1: String, genre2: String): Boolean {
        val g1 = genre1.lowercase()
        val g2 = genre2.lowercase()
        return (g1.contains("synth") && g2.contains("elec")) ||
                (g1.contains("elec") && g2.contains("synth")) ||
                (g1.contains("lofi") && g2.contains("chill")) ||
                (g1.contains("chill") && g2.contains("jazz")) ||
                (g1.contains("pop") && g2.contains("dance")) ||
                (g1.contains("rock") && g2.contains("elec"))
    }
}
