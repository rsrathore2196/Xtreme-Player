package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.CollectionPlaybackTarget
import com.example.data.model.ServerHomeItem
import com.example.data.repository.*
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.*
import com.github.takahirom.roborazzi.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionCardPlaybackUiTest {
    @get:Rule val compose = createComposeRule()
    private val album = CatalogAlbum("MPRE-album", "Native album", listOf(CatalogArtist("UC-artist", "Artist")), "", "2026",
        CatalogProvider.YOUTUBE_MUSIC, "release-params", "IN")
    private val playlist = CatalogPlaylist("VL-native", "Native playlist", "", CatalogProvider.YOUTUBE_MUSIC, "playlist-params", "IN")
    private val albumTarget = CollectionPlaybackTarget.Catalog(CatalogDestination.Album(album))
    private val playlistTarget = CollectionPlaybackTarget.Catalog(CatalogDestination.Playlist(playlist))

    @Test fun homeCardsPlayTheirExactCollectionsAndKeepCompactCirclesInBothThemes() {
        var colors by mutableStateOf(DarkAppColors)
        var pending by mutableStateOf<String?>(null)
        val played = mutableListOf<CollectionPlaybackTarget>()
        var opened = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false,
                LocalCollectionPlayback provides CollectionPlaybackActions(pending, { played += it })) {
                Box(Modifier.fillMaxSize().background(colors.scaffoldBackground)) {
                SectionCardLayout(SectionState(title = "Collections", isLoaded = true), null,
                    colors.cardBackground, colors.cardBorder, colors.textPrimary, colors.textMuted,
                    onTrackClick = { _, _ -> }, tracksPrepared = true,
                    shelfItems = listOf(ServerHomeItem.Album(album, "Album"), ServerHomeItem.Playlist(playlist, "Playlist")),
                    onOpenCatalog = { opened++ })
                }
            }
        } }
        for ((name, theme) in listOf("dark" to DarkAppColors, "light" to LightAppColors)) {
            compose.runOnIdle { colors = theme }
            for (target in listOf(albumTarget, playlistTarget)) {
                val tag = "play_collection_${target.key}"
                val circle = compose.onNodeWithTag("${tag}_circle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val title = if (target == albumTarget) album.title else playlist.title
                val art = compose.onNodeWithContentDescription(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals(32f, circle.width, .5f)
                assertEquals(circle.width, circle.height, .5f)
                assertTrue(circle.left > art.center.x && circle.top > art.center.y && circle.right <= art.right && circle.bottom <= art.bottom)
                compose.onNodeWithTag(tag).assertIsEnabled().performClick()
            }
            capture("home-$name")
        }
        assertEquals(listOf(albumTarget, playlistTarget, albumTarget, playlistTarget), played)
        assertEquals(0, opened)
        compose.runOnIdle { pending = albumTarget.key }
        compose.onNodeWithTag("play_collection_${albumTarget.key}").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Preparing Native album").assertExists()
        compose.runOnIdle { pending = null }
        compose.onNodeWithTag("play_collection_${albumTarget.key}").assertIsEnabled()
    }

    @Test fun searchEntityRowsPlayFromTheCoverAndLeaveArtistRowsAndNavigationIntact() {
        val played = mutableListOf<CollectionPlaybackTarget>()
        var opened = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors, LocalArtworkRequestsActive provides false,
                LocalCollectionPlayback provides CollectionPlaybackActions(play = { played += it })) {
                Column {
                    CatalogEntityRow(album.title, "Album", "", playTarget = albumTarget) { opened++ }
                    CatalogEntityRow(playlist.title, "Playlist", "", playTarget = playlistTarget) { opened++ }
                    CatalogEntityRow("Artist", "Artist details", "", artist = CatalogArtist("artist", "Artist")) { opened++ }
                }
            }
        } }
        for (target in listOf(albumTarget, playlistTarget)) {
            compose.onNodeWithTag("play_collection_${target.key}").performClick()
            val circle = compose.onNodeWithTag("play_collection_${target.key}_circle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(20f, circle.width, .5f)
            assertEquals(circle.width, circle.height, .5f)
        }
        assertEquals(listOf(albumTarget, playlistTarget), played)
        assertEquals(0, opened)
        compose.onNodeWithText("Artist", substring = false).performClick()
        assertEquals(1, opened)
    }

    @Test fun genreAlbumAndPlaylistCardsPlayWithoutOpeningTheirDetails() {
        val played = mutableListOf<CollectionPlaybackTarget>()
        var opened = 0
        val state = SearchUiState(selectedGenre = "Pop", exploreIndexReady = true,
            result = SearchResultCategory(catalogAlbums = listOf(album), catalogPlaylists = listOf(playlist)))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors, LocalDynamicGlassEnabled provides false,
                LocalArtworkRequestsActive provides false,
                LocalCollectionPlayback provides CollectionPlaybackActions(play = { played += it })) {
                SearchScreen(state, onQueryChange = {}, onSelectGenre = {}, onTrackClick = { _, _ -> }, onToggleFavorite = {},
                    onOpenCatalog = { opened++ })
            }
        } }
        for ((title, target) in listOf(playlist.title to playlistTarget, album.title to albumTarget)) {
            compose.onNodeWithTag("genre_results_page").performScrollToNode(hasText(title))
            compose.onNodeWithTag("play_collection_${target.key}").performClick()
        }
        assertEquals(listOf(playlistTarget, albumTarget), played)
        assertEquals(0, opened)
        compose.onNodeWithTag("genre_results_page").assertExists()
    }

    @Test fun artistReleaseArtworkHasADirectPlayControlThatDoesNotOpenTheAlbum() {
        val played = mutableListOf<CollectionPlaybackTarget>()
        var opened = 0
        val artist = CatalogArtist("UC-artist", "Artist")
        val profile = CatalogArtistProfile(artist, releases = listOf(ArtistReleaseSection("Albums", listOf(album))))
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors, LocalDynamicGlassEnabled provides false,
                LocalArtworkRequestsActive provides false,
                LocalCollectionPlayback provides CollectionPlaybackActions(play = { played += it })) {
                CatalogDetailScreen(CatalogDetailState(CatalogDestination.Artist(artist), artistProfile = profile), PlayerUiState(),
                    onBack = {}, onMore = {}, onPlay = { _, _ -> }, onFavorite = {}, onScroll = { _, _ -> },
                    onOpenAlbum = { opened++ })
            }
        } }
        compose.onNodeWithTag("catalog_detail_list").performScrollToNode(hasTestTag("artist_release_${album.id}"))
        compose.onNodeWithTag("play_collection_${albumTarget.key}").performClick()
        assertEquals(listOf(albumTarget), played)
        assertEquals(0, opened)
        compose.onNodeWithTag("artist_release_${album.id}").performClick()
        assertEquals(1, opened)
    }

    private fun capture(name: String) {
        val file = File("build/reports/collection-card-play/$name.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
