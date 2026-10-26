package com.example

import com.example.data.model.MusicTrack
import com.example.recommendation.UnifiedRecommendationEngine
import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import com.example.ui.viewmodel.HomeViewModel.Candidates
import com.example.ui.viewmodel.availableShelfTracks
import org.junit.Assert.*
import org.junit.Test

class HomeBatchPublicationTest {
    private val tracks = listOf(MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180000, "", ""))
    private val batch = UnifiedRecommendationEngine.Batch(quick = tracks, requested = setOf(Shelf.QUICK))
    private val current = Candidates(quick = tracks, generation = 4, revision = 9,
        source = UnifiedRecommendationEngine.SOURCE, contextKey = "profile", ready = setOf(Shelf.QUICK))
    @Test fun unchangedBatchesDoNotAdvanceRevisionOrReplaceShelfLists() {
        val result = current.withResults(mapOf(Shelf.QUICK to tracks.toList()), batch, "profile", true, 5, false)
        assertSame(current, result)
        assertSame(tracks, result.quick)
    }
    @Test fun metadataAndReadinessChangesStillPublishAndRetainOtherShelves() {
        val changed = tracks.map { it.copy(coverUrl = "https://art.example/better.jpg") }
        val result = current.withResults(mapOf(Shelf.ERA to changed), batch, "profile", true, 5, false)
        assertEquals(10, result.revision)
        assertEquals(5, result.generation)
        assertSame(tracks, result.quick)
        assertTrue(Shelf.ERA in result.ready)
        assertEquals(changed, result.era)
    }
    @Test fun explicitRefreshAdvancesItsGenerationOnlyOnceForIdenticalContent() {
        val result = current.withResults(mapOf(Shelf.QUICK to tracks), batch, "profile", true, 5, true)
        assertEquals(5, result.generation)
        assertSame(result, result.withResults(mapOf(Shelf.QUICK to tracks), batch, "profile", true, 5, true))
    }
    @Test fun profileAndSeedChangesDoNotRetainStaleContextualShelves() {
        val old = current.copy(inspired = tracks, seedKey = "old")
        val fresh = batch.copy(inspiredTrack = tracks, requested = setOf(Shelf.INSPIRED), seedKey = "new")
        val result = old.withResults(mapOf(Shelf.INSPIRED to tracks), fresh, "new-profile", true, 5, false)
        assertTrue(result.quick.isEmpty())
        assertEquals(tracks, result.inspired)
        assertEquals("new", result.seedKey)
        assertEquals(setOf(Shelf.INSPIRED), result.ready)
    }
    @Test fun onlyRequestedEligibleShelvesAreFilteredAndUnchangedListsAreReused() {
        val result = batch.copy(era = tracks).availableShelfTracks(emptyMap(), 100, true)
        assertEquals(setOf(Shelf.QUICK), result.keys)
        assertSame(tracks, result[Shelf.QUICK])
        assertTrue(batch.availableShelfTracks(mapOf(tracks.single().id to 101L), 100, true).isEmpty())
        assertTrue(batch.copy(inspiredTrack = tracks, requested = setOf(Shelf.INSPIRED))
            .availableShelfTracks(emptyMap(), 100, false).isEmpty())
    }
}
