package com.example.recommendation

import android.app.Application
import com.example.data.cache.RecommendationShelfLoad
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.performance.BackgroundWorkCoordinator
import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeDiscoveryFreshnessTest {
    private val day = 86_400_000L
    private val profile = UserProfile(country = "India", countryCode = "IN", languages = listOf("Hindi"))
    private fun song(n: Int, language: String = "Hindi", year: String = "2001") = MusicTrack(
        "yt_video${n.toString().padStart(6, '0')}", "Song $n", "Artist $n", "Album $n", 180_000,
        "", "", language = language, year = year, source = "YOUTUBE_MUSIC")
    private suspend fun load(engine: UnifiedRecommendationEngine, shelf: Shelf, force: Boolean = false,
        selected: UserProfile = profile, taste: UserTasteProfile = UserTasteProfile.COLD_START_PROFILE): List<MusicTrack> =
        withContext(DiscoveryRequestCoordinator.HomeRefresh(requested = true, essentialShelves = setOf(shelf))) {
            BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL, requireForeground = false) {
                val batch = engine.refresh(selected, selected.country, taste, null, emptyList(), emptyList(), emptyList(),
                    force, shelves = setOf(shelf)).toList().last()
                if (shelf == Shelf.ERA) batch.era else batch.chart.orEmpty()
            }
        }

    @Test fun automaticRefreshChangesDailyButIsStableAcrossReopeningOnTheSameDay() = runBlocking {
        var now = 100L * day
        val queries = mutableListOf<String>()
        fun engine() = UnifiedRecommendationEngine(lookup = { operation, query, country, _, _, _ ->
            assertEquals("IN", country)
            if (operation == "search") queries += query
            (1..48).map { song(it) }
        }, nowMillis = { now })
        for (shelf in listOf(Shelf.CHART, Shelf.ERA)) {
            val first = load(engine(), shelf)
            val query = queries.last()
            now += 60_000
            assertEquals(first, load(engine(), shelf))
            assertEquals(query, queries.last())
            now += day
            val next = load(engine(), shelf)
            assertNotEquals(query, queries.last())
            assertNotEquals("Refreshing should explore other qualified recordings, not only reshuffle the same twelve", first.toSet(), next.toSet())
            assertEquals(12, next.size)
            assertEquals(12, next.distinctBy(HomeRecommendationPolicy::metadataKey).size)
            assertTrue(next.all { it.language == "Hindi" && UnifiedRecommendationEngine.verified(it) })
        }
    }

    @Test fun manualRefreshChangesDiscoveryWithoutWaitingForTheNextDay() = runBlocking {
        val queries = mutableListOf<String>()
        val engine = UnifiedRecommendationEngine(lookup = { op, query, _, _, _, _ ->
            if (op == "search") queries += query
            (1..48).map { song(it) }
        }, nowMillis = { 100 * day })
        val first = load(engine, Shelf.ERA)
        val second = load(engine, Shelf.ERA, force = true)
        assertNotEquals(queries.first(), queries.last())
        assertNotEquals(first, second)
        assertEquals(second, load(engine, Shelf.ERA))
    }

    @Test fun eraRotationStillRejectsWrongLanguageWrongEraAndRecentRecordings() = runBlocking {
        val recent = song(1)
        val taste = UserTasteProfile.COLD_START_PROFILE.copy(recentRecordings = setOf(HomeRecommendationPolicy.metadataKey(recent)))
        val engine = UnifiedRecommendationEngine(lookup = { _, _, _, _, _, _ ->
            listOf(recent, song(90, "French"), song(91, year = "2025")) + (2..48).map { song(it) }
        }, nowMillis = { 101 * day })
        val result = load(engine, Shelf.ERA, taste = taste)
        assertEquals(12, result.size)
        assertFalse(result.any { it.id in setOf(recent.id, song(90).id, song(91).id) })
    }

    @Test fun languageRotationCoversAllFivePreferencesWithinTheExistingRequestLimit() = runBlocking {
        val languages = listOf("Hindi", "Punjabi", "English", "Tamil", "Telugu")
        val queried = mutableListOf<String>()
        var now = 100 * day
        val engine = UnifiedRecommendationEngine(lookup = { op, query, country, _, _, _ ->
            assertEquals("IN", country)
            if (op != "search") emptyList() else {
                val language = query.substringBefore(' ')
                queried += language
                (1..8).map { song(it + languages.indexOfFirst { name -> name.equals(language, true) } * 10, language) }
            }
        }, nowMillis = { now })
        load(engine, Shelf.ERA, selected = profile.copy(languages = languages))
        assertEquals(4, queried.size)
        now += day
        load(engine, Shelf.ERA, selected = profile.copy(languages = languages))
        assertEquals(8, queried.size)
        assertEquals(languages.map { it.lowercase() }.toSet(), queried.toSet())
    }

    @Test fun changingPreferencesUsesTheSelectedCountryAndLanguage() = runBlocking {
        val engine = UnifiedRecommendationEngine(lookup = { operation, query, country, force, _, _ ->
            assertEquals("GB", country)
            assertFalse("Automatic freshness must retain normal provider cache reuse", force)
            if (operation == "search") {
                assertTrue(query.startsWith("english "))
                assertTrue(query.endsWith("United Kingdom"))
            }
            (1..32).map { song(it, "English") } + (40..48).map { song(it, "Hindi") }
        }, nowMillis = { 101 * day })
        val result = load(engine, Shelf.CHART, selected = profile.copy(country = "United Kingdom",
            countryCode = "GB", languages = listOf("English")))
        assertEquals(12, result.size)
        assertTrue(result.all { it.language == "English" })
    }

    @Test fun resumeRevalidationRespectsCacheLifetimeAndPartialEnrichment() {
        val ttl = UnifiedRecommendationEngine.CHART_TTL
        val saved = RecommendationShelfLoad("IN|Hindi", 100 * day)
        assertFalse(saved.needsRefresh("IN|Hindi", 100 * day + ttl - 1, ttl, true))
        assertTrue(saved.needsRefresh("IN|Hindi", 100 * day + ttl, ttl, true))
        assertTrue(saved.needsRefresh("GB|English", 100 * day, ttl, true))
        assertFalse(saved.copy(partial = true).needsRefresh("IN|Hindi", 100 * day, ttl, false))
        assertTrue(saved.copy(partial = true).needsRefresh("IN|Hindi", 100 * day, ttl, true))
    }
}
