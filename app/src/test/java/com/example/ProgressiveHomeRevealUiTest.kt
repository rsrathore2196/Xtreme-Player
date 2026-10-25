package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.data.model.MusicTrack
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.HomeScreenContent
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.SectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressiveHomeRevealUiTest {
    @get:Rule val compose = createComposeRule()
    private fun shelf(name: String) = SectionState(name,
        tracks = listOf(MusicTrack(name, "$name song", "Artist", "Album", 180000, "", "")), isLoaded = true)
    private fun content(restoration: StateRestorationTester? = null) {
        val block: @androidx.compose.runtime.Composable () -> Unit = {
            MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false) {
                HomeScreenContent(ytmCard = shelf("Quick Picks"), countryLanguageCard = shelf("Country Chart"),
                    eraCard = shelf("Era Hits"), personalizedCard = shelf("Personalized"),
                    similarArtistsCard = shelf("Similar"), inspiredTrackCard = shelf("Inspired"),
                    onTrackClick = { _, _ -> }, onToggleFavorite = {})
            } }
        }
        if (restoration == null) compose.setContent(block) else restoration.setContent(block)
    }

    @Test @Config(qualifiers = "w1920dp-h1080dp-land")
    fun tallLandscapeDoesNotRevealCachedLowerCardsMerelyBecauseTheyWouldFit() {
        content()
        compose.onNodeWithText("Quick Picks").assertIsDisplayed()
        compose.onNodeWithText("Country Chart").assertIsDisplayed()
        compose.onNodeWithText("Era Hits").assertDoesNotExist()
        compose.onNodeWithText("Personalized").assertDoesNotExist()
        // A real attempted scroll must work even if the initial two shelves fit.
        compose.onNodeWithTag("home_screen").performTouchInput { swipeUp(durationMillis = 350) }
        compose.onNodeWithText("Era Hits").assertExists()
        compose.onNodeWithText("Personalized").assertDoesNotExist()
    }

    @Test fun programmaticScrollAndRecompositionDoNotRevealButUserGestureDoes() {
        content()
        compose.onNodeWithTag("home_screen").performScrollToNode(hasText("Country Chart"))
        compose.onNodeWithText("Era Hits").assertDoesNotExist()
        compose.onNodeWithTag("home_screen").performTouchInput { swipeUp(durationMillis = 350) }
        compose.onNodeWithText("Era Hits").assertExists()
        compose.onNodeWithText("Personalized").assertDoesNotExist()
    }

    @Test fun recreatedHomeRetainsRevealedSectionsInsteadOfRevealingTheEntireCache() {
        val restoration = StateRestorationTester(compose)
        content(restoration)
        compose.onNodeWithTag("home_screen").performScrollToNode(hasText("Country Chart"))
        compose.onNodeWithTag("home_screen").performTouchInput { swipeUp(durationMillis = 350) }
        compose.onNodeWithText("Era Hits").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("home_screen").performScrollToNode(hasText("Era Hits"))
        compose.onNodeWithText("Era Hits").assertExists()
        compose.onNodeWithText("Personalized").assertDoesNotExist()
    }
}
