package com.example

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.MusicTrack
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.HomeScreenContent
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeRecentOrientationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun landscapeShowsSixRecentsAndPortraitKeepsFourIncludingTheirPlaybackQueues() {
        val tracks = (1..8).map { MusicTrack("recent-$it", "Song $it", "Artist", "Album", 180000, "", "") }
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        var orientation by mutableIntStateOf(Configuration.ORIENTATION_LANDSCAPE)
        var queue = emptyList<MusicTrack>()
        compose.setContent {
            val configuration = Configuration(base).apply { this.orientation = orientation }
            MaterialTheme { CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false, dynamicAlbumGlow = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false) {
                HomeScreenContent(pillsCardTracks = tracks, onTrackClick = { _, all -> queue = all }, onToggleFavorite = {})
            } }
        }
        for ((mode, count) in listOf(Configuration.ORIENTATION_LANDSCAPE to 6, Configuration.ORIENTATION_PORTRAIT to 4)) {
            compose.runOnIdle { orientation = mode }
            compose.onNodeWithTag("home_screen").performScrollToNode(hasTestTag("habit_pill_recent-$count"))
            compose.onNodeWithTag("habit_pill_recent-$count").assertIsDisplayed().performClick()
            compose.onNodeWithTag("habit_pill_recent-${count + 1}").assertDoesNotExist()
            assertEquals(tracks.take(count), queue)
        }
    }
}
