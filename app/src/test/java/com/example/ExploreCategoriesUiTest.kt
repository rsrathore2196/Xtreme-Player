package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.repository.*
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.viewmodel.SearchUiState
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
class ExploreCategoriesUiTest {
    @get:Rule val compose = createComposeRule()
    private fun content(block: @Composable () -> Unit) = compose.setContent {
        MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
            LocalDynamicGlassEnabled provides false, content = block) }
    }
    private val mood = ExploreCategory("Feel Good", "FEmusic_moods_and_genres_category", "mood-endpoint", ExploreCategoryKind.MOOD)
    private val genre = ExploreCategory("Feel Good", "FEmusic_moods_and_genres_category", "genre-endpoint", ExploreCategoryKind.GENRE)

    @Test fun retainedHiddenSearchAndTypingNeverRequestTheExploreIndex() {
        var active by mutableStateOf(false)
        var state by mutableStateOf(SearchUiState())
        val visibility = mutableListOf<Boolean>()
        content {
            SearchScreen(state, isScreenActive = active, onQueryChange = {}, onSelectGenre = {},
                onTrackClick = { _, _ -> }, onToggleFavorite = {}, onExploreVisibilityChanged = { visibility.add(it) })
        }
        compose.runOnIdle { assertEquals(listOf(false), visibility); active = true }
        compose.runOnIdle { assertEquals(listOf(false, true), visibility); state = state.copy(query = "Song") }
        compose.runOnIdle { assertEquals(false, visibility.last()); state = state.copy(query = "") }
        compose.runOnIdle { assertEquals(true, visibility.last()); state = state.copy(selectedGenre = mood.title, selectedExploreCategory = mood) }
        compose.runOnIdle { assertEquals(false, visibility.last()); active = false }
        compose.runOnIdle { assertEquals(false, visibility.last()) }
    }

    @Test fun serverMoodsAndGenresKeepDistinctOpaqueEndpointsEvenWhenTitlesMatch() {
        var selected: ExploreCategory? = null
        var refresh: Boolean? = null
        content {
            SearchScreen(SearchUiState(exploreCategories = listOf(mood, genre)), onQueryChange = {}, onSelectGenre = {},
                onTrackClick = { _, _ -> fail("Category browsing must not start playback") }, onToggleFavorite = {},
                onSelectExploreCategory = { category, force -> selected = category; refresh = force })
        }
        compose.onNodeWithTag("search_screen").performScrollToNode(hasTestTag("explore_chip_${genre.key}"))
        compose.onNodeWithTag("explore_chip_${mood.key}").assertExists()
        compose.onNodeWithTag("explore_chip_${genre.key}").performClick()
        compose.runOnIdle { assertEquals(genre, selected); assertEquals(false, refresh) }
        compose.onNodeWithTag("genre_chip_pop").assertDoesNotExist()
    }

    @Test fun offlineIndexFallbackKeepsExistingGenrePillsAndSelection() {
        var selected = ""
        content {
            SearchScreen(SearchUiState(), onQueryChange = {}, onSelectGenre = { selected = it },
                onTrackClick = { _, _ -> }, onToggleFavorite = {})
        }
        compose.onNodeWithTag("search_screen").performScrollToNode(hasTestTag("genre_chip_pop"))
        compose.onNodeWithTag("genre_chip_pop").performClick()
        assertEquals("Pop", selected)
    }

    @Test fun realCollectionsOpenTheExactProviderEndpointWithoutFabricatedSongs() {
        val playlist = CatalogPlaylist("VLplaylist", "Feel Good Playlist", "", provider = CatalogProvider.YOUTUBE_MUSIC,
            browseParams = "playlist-params", countryCode = "IN", languageCode = "hi")
        val album = CatalogAlbum("MPREalbum", "Feel Good Album", emptyList(), "", "2026",
            provider = CatalogProvider.YOUTUBE_MUSIC, browseParams = "album-params", countryCode = "IN", languageCode = "hi")
        val state = SearchUiState(selectedGenre = mood.title, selectedExploreCategory = mood,
            result = SearchResultCategory(catalogPlaylists = listOf(playlist), catalogAlbums = listOf(album)))
        var opened: CatalogDestination? = null
        content {
            SearchScreen(state, onQueryChange = {}, onSelectGenre = {}, onTrackClick = { _, _ -> fail("No playback during browse") },
                onToggleFavorite = {}, onOpenCatalog = { opened = it })
        }
        compose.onNodeWithText("No results found").assertDoesNotExist()
        compose.onNodeWithText("Songs").assertDoesNotExist()
        compose.onNodeWithTag("genre_results_page").performScrollToNode(hasText(playlist.title))
        compose.onNodeWithText(playlist.title).performClick()
        assertEquals(CatalogDestination.Playlist(playlist), opened)
        compose.onNodeWithTag("genre_results_page").performScrollToNode(hasText(album.title))
        compose.onNodeWithText(album.title).performClick()
        assertEquals(CatalogDestination.Album(album), opened)
    }

    @Test fun categoryRefreshRetainsItsEndpointAndExplicitRefreshIntent() {
        var request: Pair<ExploreCategory, Boolean>? = null
        content {
            SearchScreen(SearchUiState(selectedGenre = mood.title, selectedExploreCategory = mood),
                onQueryChange = {}, onSelectGenre = {}, onTrackClick = { _, _ -> }, onToggleFavorite = {},
                onSelectExploreCategory = { category, force -> request = category to force })
        }
        compose.onNodeWithTag("reshuffle_genre_button").performClick()
        assertEquals(mood to true, request)
    }

    @Test fun excludedGenresAreRemovedAndDanceRenamedAndSearchPillsExcludePlaylists() {
        val carnatic = ExploreCategory("Carnatic Classical", "cat_carnatic", null, ExploreCategoryKind.GENRE)
        val country = ExploreCategory("Country & Americana", "cat_country", null, ExploreCategoryKind.GENRE)
        val hindustani = ExploreCategory("Hindustani Classical", "cat_hindustani", null, ExploreCategoryKind.GENRE)
        val indie = ExploreCategory("Indie & Alternative", "cat_indie", null, ExploreCategoryKind.GENRE)
        val reggae = ExploreCategory("Reggae & Caribbean", "cat_reggae", null, ExploreCategoryKind.GENRE)
        val danceElectronic = ExploreCategory("Dance & Electronic", "cat_dance", null, ExploreCategoryKind.GENRE)
        val pop = ExploreCategory("Pop Hits", "cat_pop", null, ExploreCategoryKind.GENRE)

        val categories = listOf(carnatic, country, hindustani, indie, reggae, danceElectronic, pop)

        content {
            SearchScreen(SearchUiState(exploreCategories = categories), onQueryChange = {}, onSelectGenre = {},
                onTrackClick = { _, _ -> }, onToggleFavorite = {})
        }

        // Verify Search Type Filter Pills: All, Tracks, Artists, Albums exist; Playlists does not
        compose.onNodeWithTag("filter_pill_all").assertExists()
        compose.onNodeWithTag("filter_pill_tracks").assertExists()
        compose.onNodeWithTag("filter_pill_artists").assertExists()
        compose.onNodeWithTag("filter_pill_albums").assertExists()
        compose.onNodeWithTag("filter_pill_playlists").assertDoesNotExist()

        // Excluded 5 genres must not exist in Explore Genres
        compose.onNodeWithText("Carnatic Classical").assertDoesNotExist()
        compose.onNodeWithText("Country & Americana").assertDoesNotExist()
        compose.onNodeWithText("Hindustani Classical").assertDoesNotExist()
        compose.onNodeWithText("Indie & Alternative").assertDoesNotExist()
        compose.onNodeWithText("Reggae & Caribbean").assertDoesNotExist()

        // Dance & Electronic must be renamed to Dance
        compose.onNodeWithText("Dance & Electronic").assertDoesNotExist()
        compose.onNodeWithTag("search_screen").performScrollToNode(hasText("Dance"))
        compose.onNodeWithText("Dance").assertExists()
        compose.onNodeWithText("Pop Hits").assertExists()
    }
}
