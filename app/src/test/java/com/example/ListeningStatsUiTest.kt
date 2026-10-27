package com.example

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningStatsUiTest {
    @get:Rule val compose = createComposeRule()
    private val song = MusicTrack("one", "Favorite Song", "Main Artist", "Album", 120_000, "", "local-source", isLiked = true)
    private fun content(light: Boolean = false, block: @Composable () -> Unit) = compose.setContent {
        MaterialTheme { CompositionLocalProvider(LocalAppColors provides (if (light) LightAppColors else DarkAppColors).copy(dynamicGlassEnabled = false),
            LocalDynamicGlassEnabled provides false, LocalArtworkRequestsActive provides false,
            LocalVisualEffectsActive provides false) { Box(Modifier.width(400.dp).fillMaxHeight()) { block() } } }
    }
    @Test fun likedPlayConsumesItsClickAndTheWholeRowOpensTheExistingList() {
        var plays = 0
        var queue = emptyList<MusicTrack>()
        var selected: MusicTrack? = null
        val favorites = listOf(song, song.copy(id = "two", title = "Second Song"), song.copy(id = "three", title = "Third Song"))
        content {
            LibraryScreen(favorites, emptyList(), onTrackClick = { track, tracks -> plays++; queue = tracks; selected = track },
                onToggleFavorite = {}, onOpenPlaylist = {}, onCreatePlaylistClick = {})
        }
        compose.onNodeWithText("Listening Stats").assertExists()
        compose.onNodeWithTag("play_liked_songs").performClick()
        compose.runOnIdle { assertEquals(1, plays); assertEquals(favorites, queue) }
        compose.onNodeWithTag("library_screen").assertExists()
        compose.onNodeWithTag("open_liked_songs").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Favorite Song").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library_screen").assertDoesNotExist()
        compose.onNodeWithText("Favorite Song").assertExists()
        compose.runOnIdle { assertEquals("Opening liked songs must not start playback", 1, plays) }
        compose.onNodeWithTag("liked_songs_play").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, plays); assertEquals(favorites.map { it.id }, queue.map { it.id }) }
        compose.onNodeWithTag("liked_songs_shuffle").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { plays == 3 }
        compose.runOnIdle {
            assertEquals(favorites.map { it.id }.toSet(), queue.map { it.id }.toSet())
            assertEquals(3, queue.size); assertEquals(queue.first().id, selected?.id)
        }
    }
    @Test fun statsNavigationKeepsTheSelectedPeriodWhenReopened() {
        content {
            LibraryScreen(emptyList(), emptyList(), onTrackClick = { _, _ -> },
                onToggleFavorite = {}, onOpenPlaylist = {}, onCreatePlaylistClick = {})
        }
        compose.onNodeWithText("Listening Stats").performClick()
        compose.onNodeWithTag("listening_stats_page").assertExists()
        compose.onNodeWithTag("library_screen").assertExists()
        compose.onNodeWithText("Your Library").assertIsDisplayed()
        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("Listening Stats").assertIsSelected()
        compose.onNodeWithTag("create_playlist_button").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithText("Yearly").performClick()
        compose.onNodeWithText("Yearly").assertIsSelected()
        compose.onNodeWithText("Playlists").performClick()
        compose.onNodeWithTag("library_screen").assertExists()
        compose.onNodeWithTag("create_playlist_button").assertIsDisplayed()
        compose.onNodeWithText("Listening Stats").performClick()
        compose.onNodeWithText("Yearly").assertIsSelected()
    }
    @Test fun fourSquareCardsAndOnlyTheTwoRequestedSectionsWorkInLightTheme() {
        var period by mutableStateOf(ListeningPeriod.WEEKLY)
        val empty = ListeningSummary(0, 0, emptyList(), emptyList())
        content(light = true) {
            ListeningStatsContent(empty, period, { period = it }, {}, { _, _ -> }, {})
        }
        val tags = listOf("stats_card_Minutes Played", "stats_card_Top Song", "stats_card_Top Artist", "stats_card_Songs Played")
        val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].width, bounds[0].height, 1f)
        assertTrue(bounds.all { kotlin.math.abs(it.width - bounds[0].width) < 1 && kotlin.math.abs(it.height - bounds[0].height) < 1 })
        assertTrue(bounds[0].left < bounds[1].left && bounds[0].top == bounds[1].top)
        assertTrue(bounds[2].left < bounds[3].left && bounds[2].top == bounds[3].top)
        tags.forEachIndexed { index, tag ->
            val logo = compose.onNodeWithTag("stats_logo_${tag.removePrefix("stats_card_")}")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(logo.right <= bounds[index].right && logo.left > bounds[index].center.x)
            assertTrue(logo.top >= bounds[index].top && logo.bottom < bounds[index].center.y)
        }
        compose.onNodeWithText("Yearly").performClick()
        compose.runOnIdle { assertEquals(ListeningPeriod.YEARLY, period) }
        compose.onNodeWithText("No listening activity in this period yet.").assertExists()
        compose.onNodeWithTag("listening_stats_page").performScrollToNode(hasText("Top Artists"))
        compose.onNodeWithText("Top Artists").assertExists()
    }
    @Test fun topSongAndArtistRowsUseExistingPlaybackAndCatalogNavigation() {
        val songRow = ListeningTotal("o", "2026-10", "song", "song-key", "one", "Favorite Song", "Main Artist", "UC_exact", "", 120_000, "", 90_000, 2)
        val artistRow = songRow.copy(kind = "artist", identity = "UC_exact")
        val summary = ListeningSummary(90_000, 2, listOf(songRow), listOf(artistRow))
        var playing: MusicTrack? = null
        var artist: CatalogArtist? = null
        content {
            ListeningStatsContent(summary, ListeningPeriod.MONTHLY, {}, {}, { track, _ -> playing = track }, { artist = it })
        }
        compose.onNodeWithTag("listening_stats_page").performScrollToNode(hasText("2 plays"))
        compose.onNodeWithText("2 plays").performClick()
        compose.runOnIdle { assertEquals("one", playing?.id) }
        compose.onNodeWithTag("listening_stats_page").performScrollToNode(hasText("1.5 minutes played"))
        compose.onNodeWithText("1.5 minutes played").performClick()
        compose.runOnIdle { assertEquals("UC_exact", artist?.id); assertEquals("Main Artist", artist?.name) }
    }
}
