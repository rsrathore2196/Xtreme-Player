package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.ServerHomeItem
import com.example.data.repository.*
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.SectionCardLayout
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.SectionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerHomeCollectionCardsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun collectionArtworkAndTitlesOpenTheirNativeDetailDestinationRatherThanPlayingAnOverlay() {
        val album = CatalogAlbum("MPRE-release", "Native album", listOf(CatalogArtist("UC-singer", "Singer")), "", "",
            CatalogProvider.YOUTUBE_MUSIC, "release-params", "IN")
        val playlist = CatalogPlaylist("VL-native", "Native playlist", "", CatalogProvider.YOUTUBE_MUSIC, "playlist-params", "IN")
        val opened = mutableListOf<CatalogDestination>(); var plays = 0
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false, LocalArtworkRequestsActive provides false) {
                SectionCardLayout(SectionState(title = "Native collection shelf", isLoaded = true), null,
                    DarkAppColors.cardBackground, DarkAppColors.cardBorder, DarkAppColors.textPrimary, DarkAppColors.textMuted,
                    onTrackClick = { _, _ -> plays++ }, tracksPrepared = true,
                    shelfItems = listOf(ServerHomeItem.Album(album, "Album • Singer"), ServerHomeItem.Playlist(playlist, "YouTube Music")),
                    onOpenCatalog = { opened += it })
            } }
        }
        compose.onNodeWithText("Native collection shelf").assertIsDisplayed()
        compose.onNodeWithContentDescription("Native album").performClick()
        compose.onNodeWithText("Native playlist").performClick()
        compose.runOnIdle {
            assertEquals(listOf(CatalogDestination.Album(album), CatalogDestination.Playlist(playlist)), opened)
            assertEquals(0, plays)
        }
    }
}
