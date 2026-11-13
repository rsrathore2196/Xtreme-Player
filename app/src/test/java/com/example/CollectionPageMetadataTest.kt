package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogAlbum
import com.example.data.repository.CatalogArtist
import com.example.ui.util.CollectionPageMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectionPageMetadataTest {
    @Test fun stripsHtmlSpacingAndOnlyExplicitPromotionalSuffixes() {
        assertEquals("Muse & Friends", CollectionPageMetadata.title(
            " <b>Muse &amp; Friends</b>\u00a0\n (Deluxe Edition) [Official Audio] | YouTube Music ", "Album"))
        assertEquals("Night Drive", CollectionPageMetadata.title("Night Drive (Official Playlist)", "Playlist"))
    }

    @Test fun filtersDeluxeAndRemasterLabelsWhilePreservingTheName() {
        for (title in listOf("Blue (Deluxe Edition)", "Blue [Super Deluxe]", "Blue (2024 Remaster)",
            "Blue (Remastered 2024)", "Blue - Remastered", "Blue Deluxe Edition")) {
            assertEquals("Blue", CollectionPageMetadata.title(title, "Untitled"))
        }
    }

    @Test fun preservesSoundtrackNamesLiveTitlesAndMeaningfulPunctuation() {
        for (title in listOf("Live", "Song (From \"Film\")", "Blue (Live)",
            "Full Album", "Songs for You", "Rock - The Album", "प्यार (Original Motion Picture Soundtrack)", "A • B", "Unknown")) {
            assertEquals(title, CollectionPageMetadata.title(title, "Untitled"))
        }
    }

    @Test fun blankAndPlaceholderTitlesUseTheCollectionFallback() {
        for (raw in listOf("", "  ", "<b></b>", "undefined", "null")) {
            assertEquals("Untitled playlist", CollectionPageMetadata.title(raw, "Untitled playlist"))
        }
    }

    @Test fun albumMetadataSeparatesAndDeduplicatesRealArtistsAndReleaseYear() {
        val album = CatalogAlbum("album", "Blue", listOf(CatalogArtist("a", "Muse &amp; Friends - TOPIC"),
            CatalogArtist("a2", "Muse & Friends"), CatalogArtist("none", "Unknown artist")), "", "Album • 2024-11-13")
        assertEquals("Muse & Friends • 2024", CollectionPageMetadata.albumSubtitle(album))
        assertEquals("", CollectionPageMetadata.albumSubtitle(album.copy(artists = emptyList(), year = "N/A")))
        assertEquals("Blue", album.title)
        assertEquals("Album • 2024-11-13", album.year)
    }

    @Test fun redundantDescriptionsAreHiddenAndReadableDescriptionsArePreserved() {
        assertEquals("", CollectionPageMetadata.description("Blue", "Blue"))
        assertEquals("", CollectionPageMetadata.description("null", "Blue"))
        assertEquals("A mix for late nights & long drives.", CollectionPageMetadata.description(
            "<p>A mix for late nights &amp; long drives.</p>", "Blue"))
    }

    @Test fun countsDurationsAndPartialPagesAreAccurateWithoutInventingUnknownDuration() {
        fun track(id: String, duration: Long) = MusicTrack(id, id, "Artist", "Album", duration, "", "")
        assertEquals("1 track • 1 min", CollectionPageMetadata.trackSummary(listOf(track("a", 90_000))))
        assertEquals("2 tracks loaded • 1 hr 5 min", CollectionPageMetadata.trackSummary(
            listOf(track("a", 3_900_000), track("b", -1)), complete = false))
        assertEquals("0 tracks", CollectionPageMetadata.trackSummary(emptyList()))
    }
}
