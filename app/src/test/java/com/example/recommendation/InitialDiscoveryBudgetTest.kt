package com.example.recommendation

import android.app.Application
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.performance.BackgroundWorkCoordinator
import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InitialDiscoveryBudgetTest {
    private data class Lookup(val operation: String, val query: String, val country: String, val essential: Boolean)
    private fun track(number: Int, language: String = "English") = MusicTrack(
        "yt_video${number.toString().padStart(6, '0')}", "Song $number", "Artist $number", "Album $number",
        180_000L, "", "", language = language, source = "YOUTUBE_MUSIC")
    private fun engine(calls: MutableList<Lookup>, response: (Lookup) -> List<MusicTrack>) =
        UnifiedRecommendationEngine(lookup = { operation, query, country, _, essential, _ ->
            val call = Lookup(operation, query, country, essential)
            calls.add(call)
            response(call)
        }, nowMillis = { 0L })

    private suspend fun load(engine: UnifiedRecommendationEngine, shelf: Shelf = Shelf.QUICK,
        profile: UserProfile = UserProfile(languages = listOf("English", "Hindi", "Punjabi")),
        current: MusicTrack? = null, taste: UserTasteProfile = UserTasteProfile.COLD_START_PROFILE,
        initial: Boolean = true, force: Boolean = false): List<UnifiedRecommendationEngine.Batch> =
        withContext(DiscoveryRequestCoordinator.HomeRefresh(requested = true,
            essentialShelves = setOf(shelf), initialPresentation = initial)) {
            // Exercise the real flow and provider operation planning. This fixture
            // owns no Activity/frame pump; its explicit critical admission avoids
            // unrelated navigation/15-second idle gates without bypassing ranking.
            BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL,
                requireForeground = false) {
                withTimeout(5_000L) {
                    engine.refresh(profile, profile.country, taste, current, emptyList(), emptyList(), emptyList(),
                        force, shelves = setOf(shelf)).toList()
                }
            }
        }

    @Test fun threeUsableInitialTracksAvoidOtherLanguageAndPublicFeedRequests() = runBlocking {
        val calls = CopyOnWriteArrayList<Lookup>()
        val engine = engine(calls) { (1..3).map { track(it) } }
        val batches = load(engine)
        assertEquals(listOf("search"), calls.map { it.operation })
        assertTrue(calls.single().query.startsWith("english"))
        assertEquals("IN", calls.single().country)
        assertTrue(calls.single().essential)
        assertEquals((1..3).map { track(it).id }.toSet(), batches.last().quick.map { it.id }.toSet())
        assertTrue(batches.flatMap { it.quick }.all(UnifiedRecommendationEngine::verified))
    }

    @Test fun rawDuplicatesRejectedTracksAndRecentRecordingsDoNotSatisfyTheInitialBudget() = runBlocking {
        val calls = CopyOnWriteArrayList<Lookup>()
        val seed = track(20)
        val recent = track(21)
        val taste = UserTasteProfile.COLD_START_PROFILE.copy(recentRecordings = setOf(HomeRecommendationPolicy.metadataKey(recent)))
        val engine = engine(calls) { lookup ->
            if (lookup.query.startsWith("english")) listOf(
                track(1), track(1), track(2).copy(artist = track(1).artist),
                track(3).copy(artist = track(1).artist), seed, recent,
                track(4, "French"), track(5).copy(source = "PODCAST"),
                track(6).copy(id = "online_invalid"), track(7).copy(artist = "Unknown Artist"),
                track(1).copy(id = "yt_video000011"), track(1).copy(id = "yt_video000012"))
            else (30..32).map { track(it, "Punjabi") }
        }
        val result = load(engine, profile = UserProfile(languages = listOf("English", "Punjabi")),
            current = seed, taste = taste).last().quick
        assertEquals(2, calls.size)
        assertTrue(calls[0].query.startsWith("english"))
        assertTrue(calls[1].query.startsWith("punjabi"))
        assertEquals(3, result.size)
        assertTrue(result.all(UnifiedRecommendationEngine::verified))
        assertFalse(result.any { it.id == seed.id || it.id == recent.id || it.language == "French" })
        assertEquals(3, result.distinctBy(HomeRecommendationPolicy::metadataKey).size)
        assertTrue(result.groupingBy { it.artist }.eachCount().values.all { it <= 2 })
    }

    @Test fun enoughRelatedTracksSkipContextualMetadataSearches() = runBlocking {
        val calls = CopyOnWriteArrayList<Lookup>()
        val engine = engine(calls) { (1..3).map { track(it) } }
        val result = load(engine, shelf = Shelf.INSPIRED, current = track(99)).last().inspiredTrack
        assertEquals(listOf("related"), calls.map { it.operation })
        assertEquals(3, result.size)
    }

    @Test fun ineligibleRelatedRowsStillAllowAContextualFallback() = runBlocking {
        val calls = CopyOnWriteArrayList<Lookup>()
        val engine = engine(calls) { lookup ->
            if (lookup.operation == "related") List(12) { track(1, "French") }
            else (30..32).map { track(it) }
        }
        val result = load(engine, shelf = Shelf.INSPIRED, current = track(99)).last().inspiredTrack
        assertEquals(listOf("related", "search"), calls.map { it.operation })
        assertTrue(calls.last().query.startsWith("english"))
        assertEquals(3, result.size)
        assertTrue(result.all { it.language == "English" })
    }

    @Test fun anUnusableChartSearchCanReachTheAuthorizedPublicDiscoveryFallback() = runBlocking {
        val calls = CopyOnWriteArrayList<Lookup>()
        val engine = engine(calls) { lookup ->
            if (lookup.operation == "browse") (1..3).map { track(it) }
            else listOf(track(15, "French"))
        }
        // Public results with explicit selected-language metadata remain eligible;
        // no fallback is allowed to override a selected language.
        val result = load(engine, shelf = Shelf.CHART,
            profile = UserProfile(languages = listOf("English"))).last().chart.orEmpty()
        assertEquals(listOf("search", "charts", "browse"), calls.map { it.operation })
        assertEquals(3, result.size)
        assertTrue(result.all { it.language == "English" })
    }

    @Test fun fullEnrichmentStillRequestsAllLanguagesAndTheNormalShelfSize() = runBlocking {
        val calls = CopyOnWriteArrayList<Lookup>()
        val engine = engine(calls) { lookup ->
            val (base, language) = when {
                lookup.query.startsWith("english") -> 0 to "English"
                lookup.query.startsWith("hindi") -> 10 to "Hindi"
                else -> 20 to "Punjabi"
            }
            (1..6).map { track(base + it, language) }
        }
        val result = load(engine, initial = false).last().quick
        assertEquals(3, calls.size)
        assertEquals(listOf("english", "hindi", "punjabi"), calls.map { it.query.substringBefore(' ') })
        assertTrue(calls.all { it.essential })
        assertEquals(12, result.size)
        assertEquals(setOf("English", "Hindi", "Punjabi"), result.map { it.language }.toSet())
    }

    @Test fun explicitInitialRotationKeepsItsExistingLanguageStartingOrder() = runBlocking {
        val calls = CopyOnWriteArrayList<Lookup>()
        val engine = engine(calls) { lookup ->
            val language = if (lookup.query.startsWith("hindi")) "Hindi" else "Punjabi"
            (1..3).map { track(it, language) }
        }
        load(engine, force = true)
        load(engine, force = true)
        assertEquals(listOf("hindi", "punjabi"), calls.map { it.query.substringBefore(' ') })
    }
}
