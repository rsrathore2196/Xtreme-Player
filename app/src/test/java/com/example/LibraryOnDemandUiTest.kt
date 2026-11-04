package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.MusicTrack
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.LibraryScreen
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryOnDemandUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun libraryPinUsesOnlyTheCountUntilLikedSongsIsOpened() {
        val favorites = MutableStateFlow(listOf(MusicTrack("liked", "Saved song", "Singer", "Album", 120_000, "", "")))
        var playRequests = 0
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(
                LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false
            ) {
                LibraryScreen(favoriteTracks = emptyList(), playlists = emptyList(), favoriteTracksState = favorites,
                    favoriteCount = 1, onPlayLikedSongs = { playRequests++ }, onTrackClick = { _, _ -> },
                    onToggleFavorite = {}, onOpenPlaylist = {}, onCreatePlaylistClick = {})
            } }
        }
        compose.onNodeWithText("1 tracks liked").assertIsDisplayed()
        assertEquals(0, favorites.subscriptionCount.value)
        compose.onNodeWithTag("play_liked_songs").performClick()
        compose.runOnIdle { assertEquals(1, playRequests); assertEquals(0, favorites.subscriptionCount.value) }
        compose.onNodeWithTag("open_liked_songs").performClick()
        compose.waitUntil(5_000) { favorites.subscriptionCount.value == 1 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved song").fetchSemanticsNodes().isNotEmpty() }
    }
}
