package com.example

import com.example.data.model.MusicTrack
import com.example.recommendation.HomeUserMode
import com.example.ui.viewmodel.Shelf
import com.example.ui.viewmodel.RecommendationCards
import com.example.ui.viewmodel.SectionState
import com.example.ui.viewmodel.eligibleHomeShelves
import com.example.recommendation.DiscoveryRequestCoordinator
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class NewUserHomePolicyTest {
    private val empty = RecommendationCards(SectionState(), SectionState(), SectionState(), SectionState())
    private val recording = MusicTrack("online_cached", "Title", "Artist", "Album", 180_000,
        "https://image", "https://aac.saavncdn.com/cached.mp4", source = "JIOSAAVN")
    private val allCached = empty.copy(era = SectionState(tracks = listOf(recording)),
        personalized = SectionState(tracks = listOf(recording)),
        inspiredTrack = SectionState(tracks = listOf(recording)))

    @Test fun smallStartupBatchesStayUsableAndOnlyTopUpAfterIdleAdmission() {
        val partial = com.example.data.cache.RecommendationShelfLoad("profile", 100L, partial = true)
        assertFalse(partial.needsRefresh("profile", 101L, 10_000L, false))
        assertTrue(partial.needsRefresh("profile", 101L, 10_000L, true))
        assertFalse(partial.copy(partial = false).needsRefresh("profile", 101L, 10_000L, true))
        assertTrue(partial.copy(partial = false).needsRefresh("profile", 11_000L, 10_000L, false))
        assertTrue(partial.needsRefresh("other-profile", 101L, 10_000L, false))
    }

    @Test fun discoveryKeysDeduplicateEquivalentWhitespaceAndCase() {
        assertEquals(DiscoveryRequestCoordinator.normalize("Punjabi new releases Canada"),
            DiscoveryRequestCoordinator.normalize("  PUNJABI   new\treleases Canada  "))
    }

    @Test fun onlyAnAuthoritativeEmptyHistoryConfirmsANewUser() {
        assertEquals(HomeUserMode.NEW_USER, HomeUserMode.confirmed(0, false))
        assertEquals(HomeUserMode.EXISTING_USER, HomeUserMode.confirmed(4000, false))
        assertEquals(HomeUserMode.EXISTING_USER, HomeUserMode.confirmed(0, true))
        assertEquals(HomeUserMode.EXISTING_USER, HomeUserMode.confirmed(1, true))
        assertNotEquals(HomeUserMode.NEW_USER, HomeUserMode.LOADING)
        assertNotEquals(HomeUserMode.NEW_USER, HomeUserMode.UNAVAILABLE)
    }

    @Test fun newUsersOnlyLoadQuickAndCountryEvenWithStalePersonalizedCacheOrFavorites() {
        assertEquals(setOf(Shelf.QUICK, Shelf.CHART),
            eligibleHomeShelves(HomeUserMode.NEW_USER, false, true, allCached))
    }

    @Test fun loadingAndHistoryErrorsNeverHideVerifiedExistingCards() {
        listOf(HomeUserMode.LOADING, HomeUserMode.UNAVAILABLE, HomeUserMode.EXISTING_USER).forEach { mode ->
            assertEquals(Shelf.entries.toSet(), eligibleHomeShelves(mode, true, false, allCached))
        }
    }

    @Test fun playbackProgressivelyUnlocksOnlyApplicableShelvesWithoutChangingDiscoveryIdentity() {
        val fresh = eligibleHomeShelves(HomeUserMode.NEW_USER, true, false, empty)
        val firstPlay = eligibleHomeShelves(HomeUserMode.EXISTING_USER, true, false, empty)
        val withTasteAndSeed = eligibleHomeShelves(HomeUserMode.EXISTING_USER, false, true, empty)
        assertTrue(firstPlay.containsAll(fresh))
        assertTrue(Shelf.ERA in firstPlay)
        assertFalse(Shelf.PERSONALIZED in firstPlay)
        assertEquals(Shelf.entries.toSet(), withTasteAndSeed)
    }
}
