package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.model.*
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
class NativeHomeRowsUiTest {
    @get:Rule val compose = createComposeRule()
    private val fullTitle = "Artist 1 - Native song 1 (Live Remix) (Official Music Video)"
    private val tracks = (1..20).map { MusicTrack("yt_native$it", if (it == 2) "Native song 2 (Acoustic)" else "Native song $it", "Artist $it", "Album", 120_000, "", "",
        originalProviderTitle = if (it == 1) fullTitle else "") }
    private fun content(kind: NativeShelfKind, trending: Boolean = false, click: (MusicTrack, List<MusicTrack>) -> Unit = { _, _ -> }) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false, LocalArtworkRequestsActive provides false) {
                SectionCardLayout(SectionState(title = "Native shelf", tracks = tracks, isLoaded = true), tracks[1].id,
                    DarkAppColors.cardBackground, DarkAppColors.cardBorder, DarkAppColors.textPrimary, DarkAppColors.textMuted,
                    onTrackClick = click, tracksPrepared = true, nativeKind = kind, resolveSongCovers = trending,
                    shelfItems = tracks.map { ServerHomeItem.Song(it, "MUSIC_VIDEO_TYPE_OMV") })
            }
        } }
        compose.onNodeWithText(fullTitle).assertIsDisplayed()
        compose.onNodeWithText(tracks[1].title).assertIsDisplayed()
    }
    @Test fun quickPicksHasTwoColumnsAndThreeRowsPerLazyPageWithOriginalQueueOrder() {
        var selected: MusicTrack? = null; var queue: List<MusicTrack> = emptyList()
        content(NativeShelfKind.QUICK_PICKS) { track, list -> selected = track; queue = list }
        val bounds = (1..6).map { compose.onNodeWithTag("habit_pill_yt_native$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        for (row in 0..2) {
            assertEquals(bounds[row * 2].top, bounds[row * 2 + 1].top, .5f)
            assertTrue(bounds[row * 2].right < bounds[row * 2 + 1].right)
        }
        assertTrue(bounds[0].top < bounds[2].top); assertTrue(bounds[2].top < bounds[4].top)
        compose.onNodeWithTag("habit_pill_yt_native3").performClick()
        compose.runOnIdle { assertEquals(tracks[2], selected); assertEquals(tracks, queue) }
        compose.onNodeWithTag("native_quick_picks_grid").performScrollToIndex(1)
        compose.onNodeWithTag("habit_pill_yt_native7").assertIsDisplayed()
        compose.onNodeWithTag("habit_pill_yt_native8").assertIsDisplayed()
    }
    @Test fun verifiedVideoArtworkIsLandscapeAndKeepsPlaybackQueue() {
        var queue: List<MusicTrack> = emptyList()
        content(NativeShelfKind.MUSIC_VIDEOS) { _, list -> queue = list }
        compose.onNodeWithTag("native_video_art_yt_native1", useUnmergedTree = true)
            .assertWidthIsEqualTo(224.dp).assertHeightIsEqualTo(126.dp)
        compose.onNodeWithText(fullTitle).performClick()
        compose.runOnIdle { assertEquals(tracks, queue) }
    }
    @Test fun quickPicksEndsAfterThreePagesOfSixWithoutChangingThePlaybackQueue() {
        var selected: MusicTrack? = null; var queue: List<MusicTrack> = emptyList()
        content(NativeShelfKind.QUICK_PICKS) { track, list -> selected = track; queue = list }
        compose.onNodeWithTag("native_quick_picks_grid").performScrollToIndex(2)
        compose.onNodeWithTag("native_quick_picks_grid").performTouchInput { swipeLeft(durationMillis = 350) }
        compose.onNodeWithTag("habit_pill_yt_native18").assertIsDisplayed().performClick()
        compose.onNodeWithTag("habit_pill_yt_native19").assertDoesNotExist()
        compose.onNodeWithTag("habit_pill_yt_native20").assertDoesNotExist()
        compose.runOnIdle { assertEquals(tracks[17], selected); assertEquals(tracks, queue) }
    }
    @Test fun standardMusicShelvesKeepTheirExistingSquareArtwork() {
        content(NativeShelfKind.STANDARD)
        compose.onNodeWithTag("native_music_video_row").assertDoesNotExist()
        compose.onNodeWithTag("native_quick_picks_grid").assertDoesNotExist()
        compose.onNodeWithContentDescription(fullTitle, useUnmergedTree = true)
            .assertWidthIsEqualTo(163.2.dp).assertHeightIsEqualTo(163.2.dp)
    }
    @Test fun trendingUsesLandscapeArtworkAndKeepsItsPlaybackQueue() {
        var queue: List<MusicTrack> = emptyList()
        content(NativeShelfKind.STANDARD, trending = true) { _, list -> queue = list }
        compose.onNodeWithContentDescription(fullTitle, useUnmergedTree = true)
            .assertWidthIsEqualTo(224.dp).assertHeightIsEqualTo(126.dp)
        compose.onNodeWithText(fullTitle).performClick()
        compose.runOnIdle { assertEquals(tracks, queue) }
    }
}
