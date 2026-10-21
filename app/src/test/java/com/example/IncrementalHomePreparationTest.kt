package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import com.example.recommendation.UserTasteProfile
import com.example.ui.viewmodel.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IncrementalHomePreparationTest {
    private fun track(index: Int) = MusicTrack("online_$index", "Recording $index", "Artist ${index % 4}",
        "Album ${index % 5}", 180_000, "https://covers/$index.jpg", "https://aac.saavncdn.com/$index.mp4",
        source = "JIOSAAVN", language = "English", year = "2000", recommendationOnly = true)
    private val taste = UserTasteProfile.COLD_START_PROFILE.copy(isColdStart = false)
    private fun pools() = Shelf.entries.associateWith { shelf -> (0..17).map { track(it + shelf.ordinal * 3) } }

    /** The prior full preparation remains the oracle for cross-shelf diversity. */
    private fun full(pools: Map<Shelf, List<MusicTrack>>, taste: UserTasteProfile,
        recent: List<MusicTrack>, exposure: Set<String>): Map<Shelf, List<MusicTrack>> {
        val seen = HomeRecommendationPolicy.Recordings()
        val excluded = HomeRecommendationPolicy.Recordings().apply { recent.forEach { add(it) } }
        fun notRecent(t: MusicTrack) = !excluded.contains(t) && taste.keyFor(t) !in taste.recentRecordings
        return listOf(Shelf.CHART, Shelf.ERA, Shelf.PERSONALIZED, Shelf.QUICK, Shelf.SIMILAR, Shelf.INSPIRED)
            .associateWith { shelf ->
                if (shelf == Shelf.CHART) HomeRecommendationPolicy.originals(pools.getValue(shelf)).filter(::notRecent)
                    .take(HomeRecommendationPolicy.MAX_SHELF_TRACKS).also { it.forEach { t -> seen.add(t) } }
                else HomeRecommendationPolicy.diversify(pools.getValue(shelf).filter { t ->
                    notRecent(t) && ((taste.skipCounts[taste.keyFor(t)] ?: 0) < 2 || taste.keyFor(t) in taste.favoriteRecordings)
                }.sortedBy { HomeRecommendationPolicy.metadataKey(it) in exposure }, HomeRecommendationPolicy.MAX_SHELF_TRACKS, seen)
            }
    }

    @Test fun incrementalBatchesMatchFullPolicyIncludingAliasesSkipsFavoritesAndExposure() = runTest {
        val preparation = IncrementalShelfPreparation()
        val data = pools().toMutableMap()
        val favorite = taste.keyFor(track(20))
        val profile = taste.copy(skipCounts = mapOf(favorite to 3, taste.keyFor(track(14)) to 3),
            favoriteRecordings = setOf(favorite), recentRecordings = setOf(taste.keyFor(track(11))))
        val exposure = setOf(HomeRecommendationPolicy.metadataKey(track(22)))
        val recent = listOf(track(7))
        for (shelf in Shelf.entries) {
            data[shelf] = data.getValue(shelf) + track(70 + shelf.ordinal)
            assertEquals(full(data, profile, recent, exposure), preparation.prepare(data, Shelf.entries.toSet(),
                profile, recent, exposure, relevantSeed = true, checkpoint = {}))
        }
    }

    @Test fun laterShelfBatchesReuseEveryUnchangedEarlierSelection() = runTest {
        val preparation = IncrementalShelfPreparation()
        val data = pools()
        val first = preparation.prepare(data, Shelf.entries.toSet(), taste, emptyList(), emptySet(), true) {}
        val changed = data + (Shelf.INSPIRED to listOf(track(100)))
        val next = preparation.prepare(changed, Shelf.entries.toSet(), taste, emptyList(), emptySet(), true) {}
        for (shelf in Shelf.entries - Shelf.INSPIRED) assertSame(first[shelf], next[shelf])
        assertEquals(full(changed, taste, emptyList(), emptySet()), next)
    }

    @Test fun metadataAndEarlierArtistConstraintsCannotLeaveStaleMemoizedResults() = runTest {
        val preparation = IncrementalShelfPreparation()
        val data = pools()
        preparation.prepare(data, Shelf.entries.toSet(), taste, emptyList(), emptySet(), true) {}
        val changed = data + (Shelf.CHART to listOf(track(99))) +
            (Shelf.QUICK to data.getValue(Shelf.QUICK).map { it.copy(coverUrl = "https://new/${it.id}") })
        val result = preparation.prepare(changed, Shelf.entries.toSet(), taste, emptyList(), emptySet(), true) {}
        assertEquals(full(changed, taste, emptyList(), emptySet()), result)
        assertTrue(result.getValue(Shelf.QUICK).all { it.coverUrl.startsWith("https://new/") })
    }

    @Test fun cancelledPreparationDoesNotPublishAnIncompleteMemo() = runTest {
        val preparation = IncrementalShelfPreparation()
        var calls = 0
        try {
            preparation.prepare(pools(), Shelf.entries.toSet(), taste, emptyList(), emptySet(), true) {
                if (++calls == 3) throw CancellationException("New preferences")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(full(pools(), taste, emptyList(), emptySet()), preparation.prepare(pools(),
            Shelf.entries.toSet(), taste, emptyList(), emptySet(), true) {})
    }

    @Test fun presentationFilterKeepsUnchangedSectionReferencesAndAcceptsMetadataUpdates() {
        val cards = RecommendationCards(SectionState(tracks = listOf(track(1))), SectionState(),
            SectionState(tracks = listOf(track(2))), SectionState())
        val filter = RecentRecommendationFilter()
        assertSame(cards, filter.prepare(cards, emptyList(), taste))
        val changed = cards.copy(quick = cards.quick.copy(tracks = listOf(track(3))))
        val next = filter.prepare(changed, emptyList(), taste)
        assertSame(cards.chart, next.chart)
        assertEquals(listOf(track(3)), next.quick.tracks)
        assertTrue(filter.prepare(next, listOf(track(3)), taste).quick.tracks.isEmpty())
    }
}
