package com.example

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.*
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.SearchUiState
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
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
class LandscapeScreensUiTest {
    @get:Rule val compose = createComposeRule()
    private val tracks = (1..12).map { MusicTrack("recent-$it", "Song $it", "Artist $it", "Album", 180000, "", "") }
    private val playlists = (1L..12L).map { PlaylistEntity(it, "Playlist $it") }
    private var widthDp = 0
    private var played: MusicTrack? = null
    private var queue = emptyList<MusicTrack>()
    private var opened: Long? = null

    private fun content(fontScale: Float = 1f, block: @Composable () -> Unit) = compose.setContent {
        widthDp = LocalConfiguration.current.screenWidthDp
        MaterialTheme { CompositionLocalProvider(
            LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false, dynamicAlbumGlow = false),
            LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
            LocalDensity provides Density(LocalDensity.current.density, fontScale),
            LocalArtworkRequestsActive provides false, content = block) }
    }
    private fun rect(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun px(dp: Float) = with(compose.density) { dp.dp.toPx() }
    private fun showHome(recent: List<MusicTrack> = tracks) {
        content { HomeScreenContent(pillsCardTracks = recent,
            onTrackClick = { track, all -> played = track; queue = all }, onToggleFavorite = {}) }
        compose.onNodeWithTag("home_screen").performScrollToNode(hasTestTag("infinite_radio_hero_card"))
    }

    @Test fun portraitKeepsFullWidthHeroAndOriginalSixRecents() {
        showHome()
        assertEquals(px(360f), rect("infinite_radio_hero_card").width, 1f)
        assertEquals(px(240f), rect("infinite_radio_hero_card").height, 1f)
        compose.onNodeWithTag("landscape_home_overview").assertDoesNotExist()
        compose.onNodeWithTag("landscape_recent_grid").assertDoesNotExist()
        compose.onNodeWithTag("home_screen").performScrollToNode(hasTestTag("habit_pill_recent-6"))
        compose.onNodeWithTag("habit_pill_recent-6").assertIsDisplayed()
        compose.onNodeWithTag("habit_pill_recent-7").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapePutsNineActualRecentsBesideOneThirdHeroWithoutOverlap() {
        showHome()
        val hero = rect("infinite_radio_hero_card")
        val grid = rect("landscape_recent_grid")
        assertEquals(px(240f), hero.height, 1f)
        assertEquals(px((widthDp - 56f) / 3f), hero.width, 1f)
        assertTrue(hero.right + px(15f) <= grid.left)
        assertEquals(hero.top, grid.top, 1f)
        for (row in 0..2) for (column in 0..2) {
            val cell = rect("habit_pill_recent-${row * 3 + column + 1}")
            assertTrue(cell.left >= grid.left && cell.right <= grid.right + 1f)
            assertTrue(cell.top >= grid.top && cell.bottom <= grid.bottom + 1f)
            if (column > 0) assertTrue(rect("habit_pill_recent-${row * 3 + column}").right < cell.left)
        }
        compose.onAllNodesWithText("Recently Played").assertCountEquals(1)
        compose.onNodeWithTag("habit_pill_recent-10").assertDoesNotExist()
        compose.onNodeWithTag("habit_pill_recent-9").performClick()
        assertEquals(tracks[8], played)
        assertEquals(tracks.take(9), queue)
        capture("home-landscape")
    }

    @Test @Config(qualifiers = "w600dp-h360dp-land")
    fun narrowLandscapeUsesSixReadableRecentsBesideHero() {
        showHome()
        compose.onNodeWithTag("habit_pill_recent-6").assertIsDisplayed()
        compose.onNodeWithTag("habit_pill_recent-7").assertDoesNotExist()
        assertTrue(rect("habit_pill_recent-2").width >= px(120f))
        assertTrue(rect("habit_pill_recent-2").left > rect("habit_pill_recent-1").right)
        assertTrue(rect("habit_pill_recent-3").top > rect("habit_pill_recent-1").bottom)
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun noListeningHistoryNeverCreatesFakeRecents() {
        showHome(emptyList())
        compose.onNodeWithText("Recently Played").assertDoesNotExist()
        compose.onNodeWithTag("landscape_recent_grid").assertDoesNotExist()
        assertEquals(px(widthDp / 2f), rect("infinite_radio_hero_card").center.x, 1f)
        compose.onNodeWithTag("hero_play_infinite_radio_button").assertIsDisplayed().assertHasClickAction()
    }

    @Test @Config(qualifiers = "w600dp-h360dp-land")
    fun landscapeHeroAndRecentTextRemainContainedAtLargerFontScale() {
        content(fontScale = 1.3f) { HomeScreenContent(pillsCardTracks = tracks,
            onTrackClick = { _, _ -> }, onToggleFavorite = {}) }
        compose.onNodeWithTag("home_screen").performScrollToNode(hasTestTag("infinite_radio_hero_card"))
        val hero = compose.onNodeWithTag("infinite_radio_hero_card").getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText("Infinite Radio").getUnclippedBoundsInRoot()
        val play = compose.onNodeWithTag("hero_play_infinite_radio_button").getUnclippedBoundsInRoot()
        assertTrue(heading.top >= hero.top && heading.bottom < play.top)
        assertTrue(play.bottom <= hero.bottom && play.right <= hero.right)
        capture("home-landscape-large-font")
    }

    private fun showLibrary() {
        content { LibraryScreen(emptyList(), playlists, onTrackClick = { _, _ -> }, onToggleFavorite = {},
            onOpenPlaylist = { opened = it }, onCreatePlaylistClick = {}) }
        compose.onNodeWithTag("library_screen").performScrollToNode(hasTestTag("playlist_card_1"))
    }

    @Test fun portraitLibraryKeepsTwoColumnsAndOriginalCoverSize() {
        showLibrary()
        assertEquals(px(173f), rect("playlist_card_1").width, 1f)
        assertEquals(rect("playlist_card_1").top, rect("playlist_card_2").top, 1f)
        compose.onNodeWithTag("library_screen").performScrollToNode(hasTestTag("playlist_card_3"))
        assertTrue(rect("playlist_card_3").top > rect("playlist_card_1").top)
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapePlaylistCoversUseFourColumnsAndAboutQuarterArea() {
        checkLandscapeLibrary()
        capture("library-landscape")
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun tabletLandscapeCentersAndBoundsThePlaylistGrid() = checkLandscapeLibrary()

    private fun checkLandscapeLibrary() {
        showLibrary()
        val contentWidth = widthDp.coerceAtMost(1120).toFloat()
        val oldCover = (contentWidth - 54f) / 2f
        val tile = rect("playlist_card_1")
        val ratio = tile.width / px(oldCover)
        assertTrue("Cover area must be close to one-quarter, not one-sixteenth", ratio * ratio in .20f.. .26f)
        for (i in 2..4) {
            val next = rect("playlist_card_$i")
            assertEquals(tile.top, next.top, 1f)
            assertTrue(rect("playlist_card_${i - 1}").right < next.left)
        }
        assertEquals(px(widthDp / 2f), (tile.left + rect("playlist_card_4").right) / 2f, 1f)
        compose.onNodeWithTag("playlist_card_4").performClick()
        assertEquals(4L, opened)
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port")
    fun largePortraitStillKeepsOriginalTwoColumnLayout() {
        showLibrary()
        assertEquals(px(373f), rect("playlist_card_1").width, 1f)
        assertEquals(rect("playlist_card_1").top, rect("playlist_card_2").top, 1f)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun landscapeSearchIsCenteredWithoutChangingSearchBehavior() {
        content { SearchScreen(SearchUiState(), onQueryChange = {}, onSelectGenre = {},
            onTrackClick = { _, _ -> }, onToggleFavorite = {}) }
        val screen = rect("search_screen")
        assertEquals(px(1120f), screen.width, 1f)
        assertEquals(px(640f), screen.center.x, 1f)
        compose.onNodeWithTag("search_screen").performScrollToNode(hasTestTag("genre_chip_pop"))
        compose.onNodeWithTag("genre_chip_pop").assertIsDisplayed().assertHasClickAction()
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapePlaylistHeaderLeavesRoomForTracksAndWorkingActions() {
        content { PlaylistDetailScreen(PlaylistWithTracks(playlists.first(), tracks.map { TrackEntity.fromMusicTrack(it) }),
            onBackClick = {}, onPlayTrack = { track, all -> played = track; queue = all },
            onToggleFavorite = {}, onDeletePlaylist = {}, onRemoveTrack = {}) }
        val artwork = rect("landscape_playlist_artwork")
        assertEquals(px(112f), artwork.width, 1f)
        assertEquals(artwork.width, artwork.height, 1f)
        compose.onNodeWithText("PLAY ALL").assertIsDisplayed().performClick()
        assertEquals(tracks.first().id, played?.id)
        assertEquals(12, queue.size)
        compose.onNodeWithText("SHUFFLE").assertIsDisplayed().performClick()
        assertEquals(tracks.map { it.id }.toSet(), queue.map { it.id }.toSet())
        capture("playlist-landscape")
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        val file = java.io.File("build/reports/landscape-review/$name.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }
}
