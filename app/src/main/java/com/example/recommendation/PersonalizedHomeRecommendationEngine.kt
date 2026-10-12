package com.example.recommendation

import com.example.data.local.ListeningEventDao
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.ui.ai.HomeShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.random.Random

/** Metadata and actual-listening ranking shared by Home's existing recommendation cards. */
class PersonalizedHomeRecommendationEngine(private val listeningEventDao: ListeningEventDao) {
    private val snapshotMutex = Mutex()
    private var cachedSnapshot: RecommendationSnapshot? = null
    private var cachedKey: Int? = null
    fun getCachedSnapshot(): RecommendationSnapshot? = cachedSnapshot

    suspend fun generateHomeShelves(
        catalogTracks: List<MusicTrack>,
        userProfile: UserProfile? = null,
        forceRefresh: Boolean = false,
        favorites: List<MusicTrack> = emptyList(),
        taste: UserTasteProfile? = null
    ): List<HomeShelf> = withContext(Dispatchers.Default) {
        val profile = taste ?: withContext(Dispatchers.IO) {
            UserTasteProfile.buildProfile(listeningEventDao, tracks = catalogTracks, favorites = favorites)
        }
        // Event/profile/catalog changes invalidate immediately; time alone never masks new likes.
        val key = listOf(catalogTracks, userProfile, favorites,
            profile.copy(lastUpdatedMs = 0)).hashCode()
        snapshotMutex.withLock {
            val now = System.currentTimeMillis()
            cachedSnapshot?.takeIf { !forceRefresh && cachedKey == key && now - it.timestamp < 180_000 }
                ?.let { return@withLock it.shelves }
            val pool = HomeRecommendationPolicy.originals(catalogTracks)
            val shelves = mutableListOf<HomeShelf>()
            fun shelf(id: String, title: String, subtitle: String, candidates: List<MusicTrack>) {
                if (candidates.size >= 3) shelves.add(HomeShelf(id, title, subtitle, candidates))
            }
            if (!profile.isColdStart) {
                val familiar = pool.filter { track ->
                    HomeRecommendationPolicy.artists(track).any { (profile.artistAffinities[it] ?: 0f) > 0 } ||
                        profile.keyFor(track) in profile.favoriteRecordings
                }
                shelf("shelf_quick_picks", "Made for You", "Inspired by music you spend time with",
                    scoreAndRankCandidates(familiar, profile, limit = 24))
                val topArtist = profile.artistAffinities.filterValues { it > 0 }.maxByOrNull { it.value }?.key
                if (topArtist != null) {
                    val related = pool.filter { track -> HomeRecommendationPolicy.artists(track).any { it == topArtist } }
                    shelf("shelf_top_artist", "Because You Listened to ${topArtist.replaceFirstChar { it.uppercase() }}",
                        "Original songs and collaborations", scoreAndRankCandidates(related, profile, limit = 24))
                }
                // Broader discovery needs richer history and an actual language/genre connection.
                if (profile.meaningfulPlaysCount >= 4) {
                    val discoveries = pool.filter { track ->
                        val keyName = profile.keyFor(track)
                        keyName !in profile.recentRecordings && (
                            (profile.genreAffinities[HomeRecommendationPolicy.normalize(track.genre)] ?: 0f) > 0 ||
                            (profile.languageAffinities[HomeRecommendationPolicy.normalize(track.language)] ?: 0f) > 0 ||
                            HomeRecommendationPolicy.artists(track).any { (profile.artistAffinities[it] ?: 0f) > 0 })
                    }
                    shelf("shelf_mood_vibe", "Discover Your Next Favorite", "Fresh songs connected to your listening",
                        scoreAndRankCandidates(discoveries, profile, limit = 24))
                }
            }
            // Charts come from the chart repository only. Unknown years never become fake releases/eras.
            val year = Calendar.getInstance().get(Calendar.YEAR)
            shelf("shelf_new_releases", "New Releases", "Recent original releases",
                HomeRecommendationPolicy.diversify(pool.filter { (it.year.toIntOrNull() ?: 0) in (year - 1)..year }.shuffled(), 15))
            shelf("shelf_era_classics", "Era-Specific Hits", "Original 90s and 2000s recordings",
                HomeRecommendationPolicy.diversify(pool.filter { (it.year.toIntOrNull() ?: 0) in 1990..2009 }.shuffled(), 15))
            cachedKey = key
            cachedSnapshot = RecommendationSnapshot(shelves, now, !profile.isColdStart)
            shelves
        }
    }

    fun scoreAndRankCandidates(pool: List<MusicTrack>, tasteProfile: UserTasteProfile,
        seedTrack: MusicTrack? = null, targetMood: String? = null, limit: Int = 15): List<MusicTrack> {
        val ranked = HomeRecommendationPolicy.originals(pool).map { candidate ->
            val artists = HomeRecommendationPolicy.artists(candidate)
            val genre = HomeRecommendationPolicy.normalize(candidate.genre)
            val language = HomeRecommendationPolicy.normalize(candidate.language)
            val key = tasteProfile.keyFor(candidate)
            val artistScore = artists.maxOfOrNull { tasteProfile.artistAffinities[it] ?: 0f } ?: 0f
            val genreScore = tasteProfile.genreAffinities[genre] ?: 0f
            val languageScore = tasteProfile.languageAffinities[language] ?: 0f
            val recent = key in tasteProfile.recentRecordings || candidate.id in tasteProfile.topRecentTrackIds
            val plays = tasteProfile.playCounts[key] ?: tasteProfile.playCounts[candidate.id] ?: 0
            val skips = tasteProfile.skipCounts[key] ?: tasteProfile.skipCounts[candidate.id] ?: 0
            val seedMatch = seedTrack?.let {
                (if (HomeRecommendationPolicy.known(it.genre) && genre == HomeRecommendationPolicy.normalize(it.genre)) 5f else 0f) +
                (if (HomeRecommendationPolicy.known(it.language) && language == HomeRecommendationPolicy.normalize(it.language)) 4f else 0f)
            } ?: 0f
            // No synthetic acoustics or bitrate-based popularity. Only explicit metadata can match a mood.
            val moodMatch = if (targetMood != null && HomeRecommendationPolicy.known(candidate.genre) &&
                genre.contains(HomeRecommendationPolicy.normalize(targetMood))) 3f else 0f
            val genreMood = HomeRecommendationPolicy.moodForGenre(candidate.genre)
            val moodAffinity = genreMood?.let { tasteProfile.moodAffinities[it] } ?: 0f
            val score = moodAffinity * 3 + artistScore * 22 + genreScore * 14 + languageScore * 12 + seedMatch + moodMatch +
                (if (key in tasteProfile.favoriteRecordings) 5 else 0) +
                (if (!recent) 10 else -18) - minOf(plays, 12) * 3 - minOf(skips, 8) * 12 + Random.nextFloat() * 8
            candidate to score
        }.sortedByDescending { it.second }.map { it.first }
        // Reserve regular discovery opportunities, then diversify both artists and albums.
        val discoveries = ranked.filter { track ->
            HomeRecommendationPolicy.artists(track).none { (tasteProfile.artistAffinities[it] ?: 0f) > 0 } &&
                ((tasteProfile.genreAffinities[HomeRecommendationPolicy.normalize(track.genre)] ?: 0f) > 0 ||
                (tasteProfile.languageAffinities[HomeRecommendationPolicy.normalize(track.language)] ?: 0f) > 0) &&
                (tasteProfile.skipCounts[tasteProfile.keyFor(track)] ?: 0) == 0
        }.take((limit / 4).coerceAtLeast(1))
        val balanced = ranked.toMutableList()
        discoveries.forEachIndexed { index, track -> balanced.remove(track); balanced.add(minOf(index * 4 + 3, balanced.size), track) }
        return HomeRecommendationPolicy.diversify(balanced, limit)
    }
}
