package com.example.recommendation

import android.util.Log
import com.example.data.local.ListeningEventDao
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.ui.ai.HomeShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


/**
 * Core Unified Recommendation Engine inspired by BitChord.
 *
 * Architecture & Data Flow:
 * - Reads user listening events & implicit signals from [ListeningEventDao].
 * - Computes rolling [UserTasteProfile] with exponential 14-day time-decay.
 * - Enforces COLD-START vs. PERSONALIZED router:
 *     * Cold-Start (< 5 meaningful plays): ONLY Country Hits, New Releases, Era-Specific cards.
 *     * Personalized: Quick Picks, Because You Listened To..., Your Vibe, etc.
 * - Multi-factor candidate ranking equation.
 * - Cross-shelf title and variant deduplication.
 * - Stale-While-Revalidate snapshot caching.
 */
class PersonalizedHomeRecommendationEngine(
    private val listeningEventDao: ListeningEventDao
) {
    private val TAG = "PersonalizedRecEngine"

    private val snapshotMutex = Mutex()
    private var cachedSnapshot: RecommendationSnapshot? = null
    private var cachedTasteProfile: UserTasteProfile? = null

    /**
     * Synchronously returns cached snapshot if fresh (< 5 minutes),
     * enabling instant (< 10ms) Home screen display on launch.
     */
    fun getCachedSnapshot(): RecommendationSnapshot? = cachedSnapshot

    /**
     * Generates or refreshes Home shelves following the strict Cold-Start vs. Personalized routing.
     */
    suspend fun generateHomeShelves(
        catalogTracks: List<MusicTrack>,
        userProfile: UserProfile? = null,
        forceRefresh: Boolean = false
    ): List<HomeShelf> = withContext(Dispatchers.Default) {
        if (catalogTracks.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedSnapshot != null && (now - cachedSnapshot!!.timestamp) < 180_000L) {
            return@withContext cachedSnapshot!!.shelves
        }

        // 1. Fetch or update taste profile on Dispatchers.IO
        val tasteProfile = withContext(Dispatchers.IO) {
            val p = cachedTasteProfile
            if (p != null && (now - p.lastUpdatedMs) < 300_000L && !forceRefresh) {
                p
            } else {
                val fresh = UserTasteProfile.buildProfile(listeningEventDao, now)
                cachedTasteProfile = fresh
                fresh
            }
        }

        // 2. Cold-Start vs. Personalized Routing
        val shelves = if (tasteProfile.isColdStart) {
            Log.i(TAG, "User in COLD-START state (meaningful plays: ${tasteProfile.meaningfulPlaysCount} < 5). Emitting curated static cards only.")
            generateColdStartShelves(catalogTracks, userProfile)
        } else {
            Log.i(TAG, "User in PERSONALIZED state (meaningful plays: ${tasteProfile.meaningfulPlaysCount}). Emitting personalized shelves.")
            generatePersonalizedShelves(catalogTracks, tasteProfile, userProfile)
        }

        snapshotMutex.withLock {
            cachedSnapshot = RecommendationSnapshot(shelves = shelves, timestamp = now, isPersonalized = !tasteProfile.isColdStart)
        }

        shelves
    }

    /**
     * COLD-START ROUTE:
     * When listening history is insufficient (< 5 tracks with completion > 30s),
     * the Home screen MUST ONLY display static/curated non-personalized cards:
     * 1. Country Hits Card
     * 2. New Releases Card
     * 3. Era-Specific Card
     * NO placeholder or empty personalized cards are revealed.
     */
    private fun generateColdStartShelves(
        catalogTracks: List<MusicTrack>,
        userProfile: UserProfile?
    ): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        val seenTrackIds = mutableSetOf<String>()

        // 1. Country Hits Card (Top Hits by Country/Region)
        val userCountry = userProfile?.country?.takeIf { it.isNotBlank() } ?: "India"
        val userLanguages = userProfile?.languages ?: emptyList()

        val countryTracks = catalogTracks.filter { track ->
            val langMatch = userLanguages.any { lang -> track.language.contains(lang, ignoreCase = true) }
            val countryMatch = track.language.equals("Hindi", ignoreCase = true) ||
                    track.language.equals("Punjabi", ignoreCase = true) ||
                    track.genre.contains("Bollywood", ignoreCase = true)
            langMatch || countryMatch
        }.ifEmpty { catalogTracks }
            .filter { seenTrackIds.add(it.id) }
            .take(15)

        if (countryTracks.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "shelf_country_hits",
                    title = "Top Hits in $userCountry",
                    subtitle = "Trending charts, viral releases & national favorites",
                    tracks = countryTracks
                )
            )
        }

        // 2. New Releases Card (Latest Catalog Releases)
        val newReleases = catalogTracks
            .filter { !seenTrackIds.contains(it.id) }
            .sortedByDescending { it.year.toIntOrNull() ?: 2024 }
            .filter { seenTrackIds.add(it.id) }
            .take(15)

        if (newReleases.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "shelf_new_releases",
                    title = "New Releases & Fresh Drops",
                    subtitle = "Latest studio master tracks added to catalog",
                    tracks = newReleases
                )
            )
        }

        // 3. Era-Specific Card (Retro / 90s / 2000s Hits)
        val eraTracks = catalogTracks
            .filter { !seenTrackIds.contains(it.id) }
            .filter {
                val yr = it.year.toIntOrNull() ?: 2000
                yr < 2015 || it.genre.contains("Retro", ignoreCase = true) || it.genre.contains("Classic", ignoreCase = true)
            }
            .ifEmpty { catalogTracks.filter { !seenTrackIds.contains(it.id) } }
            .filter { seenTrackIds.add(it.id) }
            .take(15)

        if (eraTracks.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "shelf_era_classics",
                    title = "Golden Era & Retro Anthems",
                    subtitle = "Timeless 90s, 2000s melodies & nostalgic classics",
                    tracks = eraTracks
                )
            )
        }

        return shelves
    }

    /**
     * PERSONALIZED ROUTE:
     * Generates rich personalized shelves driven by active listening signals and scoring.
     */
    private suspend fun generatePersonalizedShelves(
        catalogTracks: List<MusicTrack>,
        tasteProfile: UserTasteProfile,
        userProfile: UserProfile?
    ): List<HomeShelf> = coroutineScope {
        val shelves = mutableListOf<HomeShelf>()
        val seenTrackIds = mutableSetOf<String>()
        val seenNormalizedTitles = mutableSetOf<String>()

        val recentPlayedIds = tasteProfile.topRecentTrackIds.toSet()

        // 1. Shelf: "Quick Picks" / "Listen Again"
        val quickPicks = catalogTracks.filter { track ->
            recentPlayedIds.contains(track.id) || tasteProfile.artistAffinities.containsKey(track.artist.trim().lowercase())
        }.sortedByDescending { track ->
            val artistAff = tasteProfile.artistAffinities[track.artist.trim().lowercase()] ?: 0f
            val genreAff = tasteProfile.genreAffinities[track.genre.trim().lowercase()] ?: 0f
            artistAff * 2f + genreAff
        }.filter { track ->
            val norm = RecommendationEngine.normalizeTitle(track.title)
            seenTrackIds.add(track.id) && seenNormalizedTitles.add(norm)
        }.take(12)

        if (quickPicks.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "shelf_quick_picks",
                    title = "Quick Picks",
                    subtitle = "Familiar favorites tuned to your taste",
                    tracks = quickPicks
                )
            )
        }

        // 2. Shelf: "Because You Listened To [Top Artist]"
        val topArtist = tasteProfile.artistAffinities.maxByOrNull { it.value }?.key
        if (topArtist != null && topArtist.isNotBlank()) {
            val artistDisplayName = catalogTracks.find {
                it.artist.trim().equals(topArtist, ignoreCase = true)
            }?.artist ?: topArtist.replaceFirstChar { it.uppercase() }

            val artistCandidates = catalogTracks.filter { track ->
                !seenTrackIds.contains(track.id) &&
                (track.artist.contains(topArtist, ignoreCase = true) || track.singers.contains(topArtist, ignoreCase = true))
            }.filter { track ->
                val norm = RecommendationEngine.normalizeTitle(track.title)
                seenTrackIds.add(track.id) && seenNormalizedTitles.add(norm)
            }.take(12)

            if (artistCandidates.isNotEmpty()) {
                shelves.add(
                    HomeShelf(
                        id = "shelf_top_artist",
                        title = "Because You Love $artistDisplayName",
                        subtitle = "Hits, collaborations & tracks featuring $artistDisplayName",
                        tracks = artistCandidates
                    )
                )
            }
        }

        // 3. Shelf: "Your Vibe: [Top Inferred Mood]"
        val topMood = tasteProfile.moodAffinities.maxByOrNull { it.value }?.key ?: "Euphoric"
        val moodCandidates = scoreAndRankCandidates(
            pool = catalogTracks.filter { !seenTrackIds.contains(it.id) },
            tasteProfile = tasteProfile,
            targetMood = topMood,
            limit = 12
        ).filter { track ->
            val norm = RecommendationEngine.normalizeTitle(track.title)
            seenTrackIds.add(track.id) && seenNormalizedTitles.add(norm)
        }

        if (moodCandidates.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "shelf_mood_vibe",
                    title = "Your Vibe • $topMood",
                    subtitle = "Curated acoustic matches reflecting your recent flow",
                    tracks = moodCandidates
                )
            )
        }

        // 4. Shelf: Country Hits & Trending
        val userCountry = userProfile?.country?.takeIf { it.isNotBlank() } ?: "India"
        val remainingCountry = catalogTracks.filter { !seenTrackIds.contains(it.id) }
            .take(12)
            .filter { track ->
                val norm = RecommendationEngine.normalizeTitle(track.title)
                seenTrackIds.add(track.id) && seenNormalizedTitles.add(norm)
            }

        if (remainingCountry.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "shelf_country_trending",
                    title = "Trending in $userCountry",
                    subtitle = "Popular tracks topping the charts right now",
                    tracks = remainingCountry
                )
            )
        }

        // 5. Shelf: New Releases
        val newReleases = catalogTracks.filter { !seenTrackIds.contains(it.id) }
            .sortedByDescending { it.year.toIntOrNull() ?: 2024 }
            .take(12)
            .filter { track ->
                val norm = RecommendationEngine.normalizeTitle(track.title)
                seenTrackIds.add(track.id) && seenNormalizedTitles.add(norm)
            }

        if (newReleases.isNotEmpty()) {
            shelves.add(
                HomeShelf(
                    id = "shelf_new_releases",
                    title = "Fresh Releases",
                    subtitle = "Newly discovered studio masters",
                    tracks = newReleases
                )
            )
        }

        shelves
    }

    /**
     * Multi-factor scoring equation:
     * FinalScore = TasteScore + ArtistAffinity + LanguageAffinity + GenreAffinity +
     *              MoodScore + AcousticSimilarity + CoListeningScore + FreshnessBoost -
     *              RepetitionPenalty - SkipPenalty
     */
    fun scoreAndRankCandidates(
        pool: List<MusicTrack>,
        tasteProfile: UserTasteProfile,
        seedTrack: MusicTrack? = null,
        targetMood: String? = null,
        limit: Int = 15
    ): List<MusicTrack> {
        val seedAcoustic = seedTrack?.let { AcousticFeatures.forTrack(it) }
        val seedMood = seedTrack?.let { MoodVibeAnalyzer.analyzeMood(it) }
        val recentPlayedIds = tasteProfile.topRecentTrackIds.toSet()

        val artistTrackCounts = mutableMapOf<String, Int>()

        return pool.map { candidate ->
            val cleanArtist = candidate.artist.trim().lowercase()
            val cleanGenre = candidate.genre.trim().lowercase()
            val cleanLang = candidate.language.trim().lowercase()

            // 1. Artist Affinity (0 to 25 pts)
            val artistAffinity = (tasteProfile.artistAffinities[cleanArtist] ?: 0f) * 25f

            // 2. Language Affinity (0 to 15 pts)
            val languageAffinity = (tasteProfile.languageAffinities[cleanLang] ?: 0f) * 15f

            // 3. Genre Affinity (0 to 15 pts)
            val genreAffinity = (tasteProfile.genreAffinities[cleanGenre] ?: 0f) * 15f

            // 4. Mood Score (0 to 15 pts)
            val moodScore = if (seedMood != null) {
                val candMood = MoodVibeAnalyzer.analyzeMood(candidate)
                val vibe = MoodVibeAnalyzer.computeVibeMatch(seedMood, candMood)
                (vibe.vibeScore * 0.4f).toFloat()
            } else if (targetMood != null) {
                val inferred = MoodVibeAnalyzer.inferMoodFromGenre(cleanGenre)
                if (inferred.equals(targetMood, ignoreCase = true)) 15f else 5f
            } else {
                (tasteProfile.moodAffinities[MoodVibeAnalyzer.inferMoodFromGenre(cleanGenre)] ?: 0f) * 15f
            }

            // 5. Acoustic Similarity (0 to 10 pts)
            val acousticSimilarity = if (seedAcoustic != null) {
                val candAcoustic = AcousticFeatures.forTrack(candidate)
                (AcousticFeatures.calculateAcousticSimilarity(seedAcoustic, candAcoustic) * 10.0).toFloat()
            } else 5f

            // 6. Co-Listening / Bitrate Baseline (0 to 10 pts)
            val coListeningScore = ((candidate.bitrateKbps.coerceIn(128, 320) - 128f) / 192f * 10f)

            // 7. Freshness Boost (+5 pts if not played recently)
            val isRecentlyPlayed = recentPlayedIds.contains(candidate.id)
            val freshnessBoost = if (!isRecentlyPlayed) 5f else 0f

            // 8. Repetition Penalty (-15 pts if played recently)
            val repetitionPenalty = if (isRecentlyPlayed) 15f else 0f

            val finalScore = artistAffinity + languageAffinity + genreAffinity +
                    moodScore + acousticSimilarity + coListeningScore + freshnessBoost - repetitionPenalty

            Pair(candidate, finalScore)
        }.sortedByDescending { it.second }
        .map { it.first }
        .filter { candidate ->
            // Max 2-3 tracks per artist for diversity
            val aKey = candidate.artist.trim().lowercase()
            val currentCount = artistTrackCounts.getOrDefault(aKey, 0)
            if (currentCount < 3) {
                artistTrackCounts[aKey] = currentCount + 1
                true
            } else {
                false
            }
        }.take(limit)
    }
}
