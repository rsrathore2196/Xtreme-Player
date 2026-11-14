package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.*
import com.example.playback.PlayerUiState
import com.example.ui.components.*
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsExpansionMotionTest {
    @get:Rule val compose = createComposeRule()
    private val lyrics = TrackLyrics("motion-lyrics", "Title", "Artist", true,
        (0..20).map { LyricLine(it * 1000L, "Line $it", 1000) }, provider = "LRCLIB")

    @Test fun maximizingAndMinimizingHideAndRestoreTheTimelineWithTheControls() {
        val song = MusicTrack(lyrics.trackId, "Title", "Artist", "Album", 180_000, "", "")
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false,
                dynamicAlbumGlow = false), LocalDynamicGlassEnabled provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = song, currentPositionMs = 6000),
                    onMinimize = {}, onPlayPause = {}, onSeekTo = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleShuffle = {}, onToggleRepeat = {}, onToggleFavorite = {}, onOpenQueue = {},
                    onAddToPlaylist = {}, lyrics = lyrics, isScreenVisible = false)
            }
        } }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("player_lyrics_button").performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("player_timeline_section", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(440)
        compose.onNodeWithContentDescription("Minimize lyrics").assertIsDisplayed()
        compose.onNodeWithTag("player_timeline_section", useUnmergedTree = true).assertIsNotDisplayed()
        compose.onNodeWithTag("expanded_player_shuffle").assertIsNotDisplayed()
        compose.onNodeWithTag("lyrics_provider_pill").assertIsDisplayed()
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(440)
        compose.onNodeWithTag("player_timeline_section", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("expanded_player_shuffle").assertIsDisplayed()
        // Reverse an in-flight expansion; it must settle into the requested state.
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(90)
        compose.onNodeWithTag("player_lyrics_expand_button").performClick()
        compose.mainClock.advanceTimeBy(440)
        compose.onNodeWithTag("player_timeline_section", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Maximize lyrics").assertIsDisplayed()
    }

    @Test fun landscapeArtworkFillsItsPaneOnlyWhenFullscreenIsEnabled() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        val original = com.example.data.local.OtherSettingsPreferences.playerExperienceSettings(context).fullScreenAlbumArt
        try {
            com.example.data.local.OtherSettingsPreferences.setFullScreenAlbumArt(context, true)
            val song = MusicTrack("landscape-cover", "Title", "Artist", "Album", 180_000, "", "")
            compose.mainClock.autoAdvance = false
            compose.setContent { MaterialTheme {
                CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false,
                    dynamicAlbumGlow = false), LocalDynamicGlassEnabled provides false) {
                    ExpandedPlayerScreen(PlayerUiState(currentTrack = song), onMinimize = {}, onPlayPause = {},
                        onSeekTo = {}, onSkipNext = {}, onSkipPrevious = {}, onToggleShuffle = {}, onToggleRepeat = {},
                        onToggleFavorite = {}, onOpenQueue = {}, onAddToPlaylist = {}, isScreenVisible = false)
                }
            } }
            compose.mainClock.advanceTimeBy(600)
            fun artwork() = compose.onNodeWithTag("flip_album_art_card").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val pane = compose.onNodeWithTag("landscape_artwork_pane", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(pane.width, artwork().width, 1f)
            assertEquals(pane.height, artwork().height, 1f)
            compose.onNodeWithTag("expanded_player_play_pause").assertIsDisplayed()
            compose.onNodeWithTag("player_queue_button").assertIsDisplayed()
            compose.onNodeWithTag("flip_album_art_card").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithText("TRACK CREDITS & DETAILS").assertIsDisplayed()
            compose.runOnIdle { com.example.data.local.OtherSettingsPreferences.setFullScreenAlbumArt(context, false) }
            // Preferences arrive from IO independently of the manually advanced animation clock.
            compose.waitUntil(15_000) {
                compose.mainClock.advanceTimeByFrame()
                artwork().height < pane.height
            }
            compose.mainClock.advanceTimeBy(400)
            val standard = artwork()
            assertEquals(standard.width, standard.height, 1f)
            assertTrue(standard.height < pane.height)
            compose.runOnIdle { com.example.data.local.OtherSettingsPreferences.setFullScreenAlbumArt(context, true) }
            compose.waitUntil(15_000) {
                compose.mainClock.advanceTimeByFrame()
                kotlin.math.abs(artwork().height - pane.height) < 1f
            }
            compose.mainClock.advanceTimeBy(400)
            assertEquals(pane.width, artwork().width, 1f)
            assertEquals(pane.height, artwork().height, 1f)
        } finally { com.example.data.local.OtherSettingsPreferences.setFullScreenAlbumArt(context, original) }
    }

    @Test fun heightChangesKeepTheActiveLineCenteredWithoutAFollowUpScrollAnimation() {
        var height by mutableStateOf(240.dp)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false) {
                Box(Modifier.width(320.dp).height(height)) {
                    SyncedLyricsView(lyrics, currentPositionMs = 6000, isPlaying = false, onSeekTo = {})
                }
            }
        } }
        compose.mainClock.advanceTimeBy(600)
        for (nextHeight in listOf(240.dp, 400.dp, 520.dp, 280.dp, 240.dp)) {
            compose.runOnIdle { height = nextHeight }
            compose.mainClock.advanceTimeByFrame()
            val viewport = compose.onNodeWithTag("synced_lyrics_view").fetchSemanticsNode().boundsInRoot
            val line = compose.onNodeWithText("Line 6").fetchSemanticsNode().boundsInRoot
            assertEquals("The active line stays centered as the viewport changes", viewport.center.y, line.center.y, 2f)
        }
    }
}
