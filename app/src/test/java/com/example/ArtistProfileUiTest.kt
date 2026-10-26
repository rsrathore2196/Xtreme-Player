package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.repository.*
import com.example.playback.PlayerUiState
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.CatalogDetailScreen
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.CatalogDetailState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
class ArtistProfileUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun realArtistDetailsAndReleaseNavigationAreDisplayedWithoutChangingOtherPages() {
        val artist = CatalogArtist("UCmain", "Main Artist")
        val release = CatalogAlbum("MPRErelease", "Release", listOf(artist), "", "2026", CatalogProvider.YOUTUBE_MUSIC)
        val profile = CatalogArtistProfile(artist, "Official biography", "1.2M subscribers", "3.4M monthly listeners",
            releases = listOf(ArtistReleaseSection("Albums", listOf(release))))
        var opened: CatalogAlbum? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false) {
                CatalogDetailScreen(CatalogDetailState(CatalogDestination.Artist(artist), artistProfile = profile),
                    PlayerUiState(), {}, {}, { _, _ -> }, {}, { _, _ -> }, onOpenAlbum = { opened = it })
            }
        } }
        compose.onNodeWithTag("artist_profile_name").assertTextEquals("Main Artist").assertIsDisplayed()
        compose.onNodeWithText("1.2M subscribers").assertIsDisplayed()
        compose.onNodeWithText("3.4M monthly listeners").assertIsDisplayed()
        compose.onNodeWithText("Official biography").assertIsDisplayed()
        compose.onNodeWithText("Read more").performClick()
        compose.onNodeWithText("Show less").assertIsDisplayed()
        compose.onNodeWithText("Release").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(release, opened) }
    }
}
