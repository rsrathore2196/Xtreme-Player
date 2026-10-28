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
import com.example.data.local.AppThemeMode
import com.example.data.local.PlaylistWithTracks
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.playback.AudioEffectsState
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

    @Test fun portraitKeepsFullWidthHeroAndShowsOnlyFourRecents() {
        showHome()
        assertEquals(px(360f), rect("infinite_radio_hero_card").width, 1f)
        assertEquals(px(240f), rect("infinite_radio_hero_card").height, 1f)
        compose.onNodeWithTag("landscape_home_overview").assertDoesNotExist()
        compose.onNodeWithTag("landscape_recent_grid").assertDoesNotExist()
        compose.onNodeWithTag("home_screen").performScrollToNode(hasTestTag("habit_pill_recent-4"))
        compose.onNodeWithTag("habit_pill_recent-4").assertIsDisplayed()
        compose.onNodeWithTag("habit_pill_recent-5").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapePutsFourActualRecentsBesideOneThirdHeroWithoutOverlap() {
        showHome()
        val hero = rect("infinite_radio_hero_card")
        val grid = rect("landscape_recent_grid")
        assertEquals(px(240f), hero.height, 1f)
        assertEquals(px((widthDp - 56f) / 3f), hero.width, 1f)
        assertTrue(hero.right + px(15f) <= grid.left)
        assertEquals(hero.top, grid.top, 1f)
        for (index in 0..3) {
            val cell = rect("habit_pill_recent-${index + 1}")
            assertTrue(cell.left >= grid.left && cell.right <= grid.right + 1f)
            assertTrue(cell.top >= grid.top && cell.bottom <= grid.bottom + 1f)
            if (index % 3 > 0) assertTrue(rect("habit_pill_recent-$index").right < cell.left)
        }
        compose.onAllNodesWithText("Recently Played").assertCountEquals(1)
        compose.onNodeWithTag("habit_pill_recent-5").assertDoesNotExist()
        compose.onNodeWithTag("habit_pill_recent-4").performClick()
        assertEquals(tracks[3], played)
        assertEquals(tracks.take(4), queue)
        capture("home-landscape")
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun tabletHomeUsesTheWholeLandscapeWindow() = checkFullWidthHome()

    @Test @Config(qualifiers = "w1920dp-h1080dp-land")
    fun desktopHomeUsesTheWholeLandscapeWindow() {
        checkFullWidthHome()
        capture("home-desktop-full-width")
    }

    private fun checkFullWidthHome() {
        showHome()
        val screen = rect("home_screen")
        val hero = rect("infinite_radio_hero_card")
        val recents = rect("landscape_recent_grid")
        assertEquals(0f, screen.left, 1f)
        assertEquals(px(widthDp.toFloat()), screen.width, 1f)
        assertEquals(px(20f), hero.left, 1f)
        assertEquals(px((widthDp - 56f) / 3f), hero.width, 1f)
        assertEquals(px(240f), hero.height, 1f)
        assertTrue(hero.right < recents.left)
        assertEquals(px(widthDp - 20f), recents.right, 1f)
        compose.onNodeWithTag("habit_pill_recent-4").assertIsDisplayed()
        compose.onNodeWithTag("habit_pill_recent-5").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w600dp-h360dp-land")
    fun narrowLandscapeUsesFourReadableRecentsBesideHero() {
        showHome()
        compose.onNodeWithTag("habit_pill_recent-4").assertIsDisplayed()
        compose.onNodeWithTag("habit_pill_recent-5").assertDoesNotExist()
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
    fun tabletLandscapeUsesFullWidthWithCompactPlaylistTiles() = checkLandscapeLibrary(expectedColumns = 5)

    @Test @Config(qualifiers = "w1920dp-h1080dp-land")
    fun desktopLandscapeUsesFullWidthWithCompactPlaylistTiles() {
        checkLandscapeLibrary(expectedColumns = 7)
        capture("library-desktop-full-width")
    }

    @Test @Config(qualifiers = "w480dp-h320dp-land")
    fun narrowLandscapeReducesPlaylistColumnsWithoutClipping() = checkLandscapeLibrary(expectedColumns = 3)

    private fun checkLandscapeLibrary(expectedColumns: Int = 4) {
        showLibrary()
        assertEquals(px(widthDp.toFloat()), rect("library_screen").width, 1f)
        val tile = rect("playlist_card_1")
        assertEquals(px((widthDp - 40f - 14f * (expectedColumns - 1)) / expectedColumns), tile.width, 1f)
        assertTrue("Wide windows must not stretch covers into oversized tiles", tile.width <= px(260f))
        if (expectedColumns == 4) {
            val ratio = tile.width / px((widthDp - 54f) / 2f)
            assertTrue("Cover area must be close to one-quarter, not one-sixteenth", ratio * ratio in .20f.. .26f)
        }
        for (i in 2..expectedColumns) {
            val next = rect("playlist_card_$i")
            assertEquals(tile.top, next.top, 1f)
            assertTrue(rect("playlist_card_${i - 1}").right < next.left)
        }
        assertEquals(px(20f), tile.left, 1f)
        assertEquals(px(widthDp - 20f), rect("playlist_card_$expectedColumns").right, 1f)
        compose.onNodeWithTag("playlist_card_$expectedColumns").performClick()
        assertEquals(expectedColumns.toLong(), opened)
    }

    @Test @Config(qualifiers = "w1920dp-h1080dp-land")
    fun libraryFollowsActualParentWidthWhenLandscapeWindowResizes() {
        var windowWidth by mutableStateOf(1400.dp)
        content { Box(Modifier.width(windowWidth).fillMaxHeight()) {
            LibraryScreen(emptyList(), playlists, onTrackClick = { _, _ -> }, onToggleFavorite = {},
                onOpenPlaylist = { opened = it }, onCreatePlaylistClick = {})
        } }
        for ((width, columns) in listOf(1400 to 6, 900 to 4, 600 to 4)) {
            compose.runOnIdle { windowWidth = width.dp }
            compose.onNodeWithTag("library_screen").performScrollToNode(hasTestTag("playlist_card_1"))
            assertEquals(px(width.toFloat()), rect("library_screen").width, 1f)
            assertEquals(px(20f), rect("playlist_card_1").left, 1f)
            assertEquals(px(width - 20f), rect("playlist_card_$columns").right, 1f)
            assertEquals(rect("playlist_card_1").top, rect("playlist_card_$columns").top, 1f)
        }
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port")
    fun largePortraitStillKeepsOriginalTwoColumnLayout() {
        showLibrary()
        assertEquals(px(373f), rect("playlist_card_1").width, 1f)
        assertEquals(rect("playlist_card_1").top, rect("playlist_card_2").top, 1f)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun tabletLandscapeSearchUsesFullWidthWithoutChangingSearchBehavior() = checkFullWidthSearch()

    @Test @Config(qualifiers = "w1920dp-h1080dp-land")
    fun desktopLandscapeSearchUsesFullWidthWithoutChangingSearchBehavior() {
        checkFullWidthSearch()
        capture("search-desktop-full-width")
    }

    private fun checkFullWidthSearch() {
        content { SearchScreen(SearchUiState(exploreIndexReady = true), onQueryChange = {}, onSelectGenre = {},
            onTrackClick = { _, _ -> }, onToggleFavorite = {}) }
        val screen = rect("search_screen")
        assertEquals(px(widthDp.toFloat()), screen.width, 1f)
        assertEquals(0f, screen.left, 1f)
        compose.onNodeWithTag("search_screen").performScrollToNode(hasTestTag("genre_chip_pop"))
        compose.onNodeWithTag("genre_chip_pop").assertIsDisplayed().assertHasClickAction()
    }

    @Test @Config(qualifiers = "w1920dp-h1080dp-land")
    fun landscapeGenreResultsAlsoUseFullWidth() {
        content { SearchScreen(SearchUiState(selectedGenre = "Pop"), onQueryChange = {}, onSelectGenre = {},
            onTrackClick = { _, _ -> }, onToggleFavorite = {}) }
        assertEquals(px(widthDp.toFloat()), rect("genre_results_page").width, 1f)
        assertEquals(0f, rect("genre_results_page").left, 1f)
    }

    @Test fun portraitSearchKeepsItsOriginalWidth() = checkFullWidthSearch()

    @Test @Config(qualifiers = "w800dp-h1280dp-port")
    fun largePortraitHomeKeepsTheOriginalHeroAndRecentsArrangement() {
        showHome()
        assertEquals(px(760f), rect("infinite_radio_hero_card").width, 1f)
        compose.onNodeWithTag("landscape_recent_grid").assertDoesNotExist()
        compose.onNodeWithTag("home_screen").performScrollToNode(hasTestTag("habit_pill_recent-4"))
        compose.onNodeWithTag("habit_pill_recent-5").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun tabletLandscapeSettingsUseTheWholeWindow() = checkFullWidthSettings()

    @Test @Config(qualifiers = "w1920dp-h1080dp-land")
    fun desktopLandscapeSettingsUseTheWholeWindow() {
        checkFullWidthSettings()
        capture("settings-desktop-full-width")
    }

    @Test fun portraitSettingsKeepTheirOriginalWidthAndCategories() = checkFullWidthSettings()

    private fun checkFullWidthSettings() {
        content { SettingsScreen(effectsState = AudioEffectsState(), isDarkMode = true,
            themeMode = AppThemeMode.DARK, userProfile = null, onSelectThemeMode = {},
            onToggleDarkMode = {}, onCrystalClarityToggle = {}, onToggleEqualizer = {},
            onOpenEqualizer = {}, onSelectPreset = {}) }
        assertEquals(px(widthDp.toFloat()), rect("settings_screen").width, 1f)
        assertEquals(0f, rect("settings_screen").left, 1f)
        val category = compose.onNodeWithTag("settings_cat_music").performScrollTo().assertIsDisplayed()
        category.assertHasClickAction()
        assertEquals(px(widthDp - 16f), rect("settings_cat_music").right, 1f)
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
