package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.viewmodel.SearchUiState
import com.example.ui.viewmodel.CatalogSearchState
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
class SearchGenreNavigationTest {
    @get:Rule val compose = createComposeRule()

    private fun content(block: @Composable () -> Unit) = compose.setContent {
        MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
            LocalDynamicGlassEnabled provides false, content = block) }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port")
    fun searchControlsAlignAndKeepTheirActions() = checkSearchControls()
    @Test @Config(qualifiers = "w320dp-h740dp-port")
    fun narrowSearchControlsFitWithoutOverlapping() = checkSearchControls()
    @Test @Config(qualifiers = "w891dp-h411dp-land")
    fun landscapeSearchControlsMatchTheSearchField() = checkSearchControls()

    private fun checkSearchControls() {
        var colors by mutableStateOf(DarkAppColors.copy(dynamicGlassEnabled = false))
        var catalog by mutableStateOf(CatalogSearchState())
        var searched: String? = null
        var remembered: String? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalDynamicGlassEnabled provides false) {
                SearchScreen(SearchUiState(query = "track title"), onQueryChange = { searched = it },
                    onSelectGenre = {}, onTrackClick = { _, _ -> }, onToggleFavorite = {},
                    catalogState = catalog, onSelectType = { catalog = catalog.copy(type = it) },
                    onRememberSearch = { remembered = it })
            }
        } }
        for (theme in listOf(DarkAppColors, LightAppColors)) {
            compose.runOnIdle { colors = theme.copy(dynamicGlassEnabled = false) }
            val field = compose.onNodeWithTag("search_query_input").fetchSemanticsNode().boundsInRoot
            val filters = listOf("all", "tracks", "artists", "albums").map { tag ->
                compose.onNodeWithTag("filter_pill_$tag").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
            assertEquals(field.left, filters.first().left, 1f)
            assertEquals(field.right, filters.last().right, 1f)
            assertTrue(filters.all { kotlin.math.abs(it.width - filters.first().width) <= 1f && it.top >= field.bottom })
            filters.zipWithNext().forEach { (left, right) -> assertTrue(left.right < right.left) }
            val action = compose.onNodeWithTag("finish_search_button").assertIsDisplayed()
                .assertWidthIsAtLeast(60.dp).assertHeightIsAtLeast(40.dp)
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width > bounds.height && bounds.left >= field.left && bounds.right <= field.right)
            compose.onNodeWithContentDescription("Search").assertIsDisplayed()
            action.performClick()
            compose.runOnIdle { assertEquals("track title", searched); assertEquals("track title", remembered) }
            listOf("Tracks", "Artists", "Albums", "All").forEach { type ->
                compose.onNodeWithTag("filter_pill_${type.lowercase()}").performClick()
                compose.runOnIdle { assertEquals(type, catalog.type) }
            }
        }
    }

    @Test fun twentyRecentPillsUseTwoScrollableRowsAndKeepSearchAndRemovalWorking() {
        var searched: String? = null
        var removed: String? = null
        content {
            SearchScreen(SearchUiState(recentSearches = (1..25).map { "Query $it" }),
                onQueryChange = { searched = it }, onSelectGenre = {}, onTrackClick = { _, _ -> },
                onToggleFavorite = {}, onRemoveRecentSearch = { removed = it })
        }
        compose.onNodeWithTag("search_screen").performScrollToNode(hasTestTag("recent_search_row_0"))
        for (index in 1..20) {
            compose.onNode(hasTestTag("recent_search_chip_Query $index") and
                hasAnyAncestor(hasTestTag("recent_search_row_${if (index <= 10) 0 else 1}"))).assertExists()
        }
        compose.onNodeWithTag("recent_search_row_2").assertDoesNotExist()
        compose.onNodeWithTag("recent_search_chip_Query 21").assertDoesNotExist()
        val first = compose.onNodeWithTag("recent_search_row_0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("recent_search_row_1").fetchSemanticsNode().boundsInRoot
        assertTrue(second.top >= first.bottom)
        compose.onNodeWithTag("recent_search_chip_Query 10").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Query 10", searched) }
        compose.onNodeWithTag("recent_search_chip_Query 20").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("remove_recent_search_Query 20").performClick()
        compose.runOnIdle { assertEquals("Query 20", removed); assertEquals("Query 10", searched) }
    }

    @Test fun genreOpensItsOwnPageWithPlayableSongsAlbumsAndBackNavigation() {
        val track = MusicTrack("yt_abcdefghijk", "Pop Song", "Artist", "Pop Album", 180000, "", "")
        val album = CatalogAlbum("pop_album", "Pop Album", listOf(CatalogArtist("artist", "Artist")), "", "2026")
        val results = SearchResultCategory(songs = listOf(track), catalogAlbums = listOf(album))
        var state by mutableStateOf(SearchUiState())
        var played: MusicTrack? = null
        var queue: List<MusicTrack> = emptyList()
        var opened: CatalogDestination? = null
        content {
            SearchScreen(state, onQueryChange = {},
                onSelectGenre = { state = state.copy(selectedGenre = it, result = if (it == "All") SearchResultCategory() else results) },
                onTrackClick = { song, songs -> played = song; queue = songs }, onToggleFavorite = {},
                onOpenCatalog = { opened = it })
        }
        compose.onNodeWithTag("search_screen").performScrollToNode(hasTestTag("genre_chip_pop"))
        compose.onNodeWithTag("genre_chip_pop").performClick()
        compose.onNodeWithTag("genre_results_page").assertExists()
        compose.onNodeWithTag("search_screen").assertDoesNotExist()
        compose.onNodeWithText("Explore Genres").assertDoesNotExist()
        compose.onNodeWithText("Pop Song").performClick()
        compose.runOnIdle { assertEquals(track, played); assertEquals(listOf(track), queue) }
        compose.onNodeWithTag("genre_results_page").performScrollToNode(hasText("Pop Album"))
        compose.onNodeWithText("Pop Album").performClick()
        compose.runOnIdle { assertEquals(CatalogDestination.Album(album), opened) }
        compose.onNodeWithTag("genre_results_page").performScrollToNode(hasTestTag("back_to_all_genres_button"))
        compose.onNodeWithTag("back_to_all_genres_button").performClick()
        compose.onNodeWithTag("search_screen").assertExists()
        compose.onNodeWithTag("genre_results_page").assertDoesNotExist()
        compose.onNodeWithTag("genre_chip_pop").assertIsDisplayed()
    }
}
