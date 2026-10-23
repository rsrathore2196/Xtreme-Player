package com.example

import com.example.data.model.MusicTrack
import com.example.recommendation.UnifiedRecommendationEngine.Shelf
import com.example.ui.viewmodel.RecommendationCards
import com.example.ui.viewmodel.SectionState
import com.example.ui.viewmodel.enrichFromCache
import com.example.ui.viewmodel.finishPreparation
import com.example.ui.viewmodel.withSection
import com.example.ui.viewmodel.initialCardDemand
import com.example.ui.viewmodel.withSeedHeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HomePresentationRegressionTest {
    private fun section(id: String, cover: String = "cover") = SectionState(title = id,
        tracks = listOf(MusicTrack(id, id, "Artist", "Album", 180_000, cover, "https://audio")), isLoaded = true)
    private fun cards(quick: SectionState = section("quick"), era: SectionState = SectionState()) =
        RecommendationCards(quick, era, SectionState(), SectionState())

    @Test fun relatedHeadingsIncludeTheirActualSeedAndRemoveTheDuplicateSubtitle() {
        val similar = section("More From Similar Artists").copy(subtitle = "Related to old artist")
        val inspired = section("Inspired by this Track").copy(subtitle = "Inspired by old track")
        val artist = similar.withSeedHeading(Shelf.SIMILAR, "Karan Aujla")
        val track = inspired.withSeedHeading(Shelf.INSPIRED, "On Top")
        assertEquals("More From 'Karan Aujla'", artist.title)
        assertEquals("Because You Listened 'On Top'", track.title)
        assertEquals("", artist.subtitle)
        assertEquals("", track.subtitle)
        assertSame(similar.tracks, artist.tracks)
        assertSame(inspired.tracks, track.tracks)
        assertSame(artist, artist.withSeedHeading(Shelf.SIMILAR, "Karan Aujla"))
    }

    @Test fun oldCachedSubtitlesSupplyMissingSeedMetadataWithoutGuessing() {
        assertEquals("More From 'Artist'", section("old").copy(subtitle = "Related to Artist")
            .withSeedHeading(Shelf.SIMILAR, "").title)
        assertEquals("Because You Listened 'Song'", section("old").copy(subtitle = "Inspired by Song")
            .withSeedHeading(Shelf.INSPIRED, "").title)
        val unknown = section("More from Similar Artists")
        assertSame(unknown, unknown.withSeedHeading(Shelf.SIMILAR, ""))
        assertSame(unknown, unknown.withSeedHeading(Shelf.QUICK, "Artist"))
    }

    @Test fun lateFullCacheFillsAnUntouchedShelfWithoutReplacingNewerLiveTracks() {
        val preview = cards()
        val live = preview.copy(quick = section("new-selection"))
        val full = preview.copy(era = section("era"))
        val enriched = live.enrichFromCache(full, preview)
        assertSame(live.quick, enriched.quick)
        assertSame(full.era, enriched.era)
    }

    @Test fun identicalFullCacheRetainsTheEntireSnapshotAndArtworkIdentity() {
        val preview = cards()
        val decodedAgain = preview.copy(quick = preview.quick.copy(tracks = preview.quick.tracks.toList()))
        assertSame(preview, preview.enrichFromCache(decodedAgain, preview))
    }

    @Test fun anEmptyCacheSectionCannotClearAPopulatedShelf() {
        val preview = cards(era = section("era"))
        assertSame(preview, preview.enrichFromCache(cards(quick = SectionState()), preview))
    }

    @Test fun verifiedCoverMetadataEnrichesAnUntouchedTrackWithoutChangingItsId() {
        val preview = cards()
        val full = cards(quick = section("quick", "verified-high-resolution-cover"))
        val result = preview.enrichFromCache(full, preview)
        assertEquals(preview.quick.tracks.single().id, result.quick.tracks.single().id)
        assertEquals("verified-high-resolution-cover", result.quick.tracks.single().coverUrl)
    }

    @Test fun oneSectionUpdateRetainsAllOtherSectionReferences() {
        val original = cards(era = section("era"))
        val next = original.withSection(Shelf.QUICK, section("next"))
        assertSame(original.era, next.era)
        assertSame(original.chart, next.chart)
        assertSame(original.personalized, next.personalized)
        assertSame(original, original.withSection(Shelf.QUICK, original.quick.copy()))
    }

    @Test fun firstInstallVerificationKeepsTheSkeletonUntilItsFirstSnapshotIsPresented() {
        val loading = SectionState(title = "Quick Picks", isLoading = true)
        assertSame(loading, loading.finishPreparation(hasVerifiedCandidate = true))
        val staleError = loading.copy(isError = true, errorMessage = "old failure")
        assertEquals(loading, staleError.finishPreparation(hasVerifiedCandidate = true))
    }

    @Test fun aFailedRefreshRetainsUsableCachedTracks() {
        val cached = section("cached")
        assertSame(cached, cached.finishPreparation(hasVerifiedCandidate = false))
    }

    @Test fun firstInstallStartsWithOnlyTheEligibleBootstrapShelf() {
        assertEquals(setOf(Shelf.QUICK), setOf(Shelf.QUICK, Shelf.CHART, Shelf.ERA)
            .initialCardDemand(setOf(Shelf.QUICK), automaticStartup = true))
    }

    @Test fun aFailedOrNegativeCachedBootstrapCannotBlockOtherVisibleEmptyCards() {
        val eligible = setOf(Shelf.CHART, Shelf.ERA)
        assertSame(eligible, eligible.initialCardDemand(setOf(Shelf.QUICK), automaticStartup = true))
    }

    @Test fun explicitRefreshAndLaterViewportDemandKeepTheirRequestedSections() {
        val requested = setOf(Shelf.QUICK, Shelf.CHART, Shelf.ERA)
        assertSame(requested, requested.initialCardDemand(setOf(Shelf.QUICK), automaticStartup = false))
    }
}
