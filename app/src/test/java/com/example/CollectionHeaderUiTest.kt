package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.repository.*
import com.example.downloads.OfflineDownloads
import com.example.playback.PlayerUiState
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.viewmodel.CatalogDetailState
import com.github.takahirom.roborazzi.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionHeaderUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: OfflineDownloads
    private val tracks = (1..2).map { MusicTrack("header-$it", "Song $it", "Artist", "Album", 120_000, "", "https://example.test/$it") }
    private val playlist = PlaylistEntity(10, "Night Drive (Deluxe) [Official Playlist]", "<b>Late nights &amp; long drives</b>")

    @Before fun prepare() {
        OfflineDownloads::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        store = OfflineDownloads.get(context)
        runBlocking { store.load() }
    }

    private fun showPlaylist(icon: Boolean = false) {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false) {
                PlaylistDetailScreen(PlaylistWithTracks(playlist, tracks.map(TrackEntity::fromMusicTrack)),
                    onBackClick = {}, onPlayTrack = { _, _ -> }, onToggleFavorite = {}, onDeletePlaylist = {}, onRemoveTrack = {},
                    downloadStore = store, artworkIcon = if (icon) Icons.Default.Download else null)
            }
        } }
    }

    @Test fun playlistUsesACleanTitleAndOneCompactDownloadControlBesideIt() {
        showPlaylist()
        compose.onNodeWithText("Night Drive").assertExists()
        compose.onNodeWithText("Late nights & long drives").assertExists()
        compose.onNodeWithText("2 tracks • 4 min").assertExists()
        assertTitleAction("playlist_profile_name", "playlist_profile_header")
        assertArtAtTop("playlist_profile_artwork", "playlist_profile_header")
        capture("playlist_detail_screen", "playlist")
        assertEquals("Saved title stays intact", "Night Drive (Deluxe) [Official Playlist]", playlist.title)
    }

    @Test fun albumFiltersEditionLabelsAndSeparatesCleanArtistYearAndTrackMetadata() {
        val album = CatalogAlbum("album", "Blue &amp; Gold (Remastered) [Official Audio]",
            listOf(CatalogArtist("a", "Artist - Topic"), CatalogArtist("b", "Artist")), "", "2024-11-13")
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides LightAppColors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false) {
                CatalogDetailScreen(CatalogDetailState(CatalogDestination.Album(album), tracks = tracks), PlayerUiState(),
                    onBack = {}, onMore = {}, onPlay = { _, _ -> }, onFavorite = {}, onScroll = { _, _ -> })
            }
        } }
        compose.onNodeWithText("Blue & Gold").assertExists()
        compose.onNodeWithText("Artist • 2024").assertExists()
        compose.onNodeWithText("2 tracks • 4 min").assertExists()
        assertTitleAction("album_profile_name", "album_profile_header")
        assertArtAtTop("album_profile_artwork", "album_profile_header")
        capture("catalog_detail_list", "album-light")
    }

    @Test @Config(qualifiers = "w740dp-h400dp-land")
    fun landscapePlaylistKeepsTheDownloadBesideTheTitleAndPlaybackActionsWorking() {
        showPlaylist()
        assertTitleAction("playlist_profile_name", "playlist_detail_screen")
        compose.onNodeWithText("PLAY ALL").assertIsDisplayed().performClick()
        compose.onNodeWithText("SHUFFLE").assertIsDisplayed().performClick()
        capture("playlist_detail_screen", "playlist-landscape")
    }

    @Test fun normalPlaylistArtAlsoStartsAtTheTopAndKeepsItsExistingEdgeBlends() {
        showPlaylist(icon = true)
        assertArtAtTop("playlist_profile_artwork", "playlist_profile_header")
        val file = capture("playlist_profile_artwork", "normal-playlist-blend")
        val image = ImageIO.read(file)
        assertEquals(DarkAppColors.scaffoldBackground.toArgb(), image.getRGB(image.width / 2, 1))
        assertTrue(java.awt.Color(image.getRGB(image.width / 2, image.height / 3)).blue > 100)
        assertEquals(DarkAppColors.scaffoldBackground.toArgb(), image.getRGB(image.width / 2, image.height - 1))
    }

    private fun assertTitleAction(titleTag: String, screenTag: String) {
        compose.onNodeWithTag("download_collection").assertIsDisplayed()
        compose.onAllNodesWithTag("download_collection").assertCountEquals(1)
        compose.onNodeWithText("Download").assertDoesNotExist()
        val action = compose.onNodeWithTag("download_collection").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag(titleTag).fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag(screenTag).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        assertTrue("Download sits beside the name", title.right < action.left)
        assertEquals(title.center.y, action.center.y, .5f)
        assertEquals(48f * density, action.width, .5f)
        assertEquals(action.width, action.height, .5f)
        assertEquals("Download aligns with the header's right edge", screen.right - 24f * density, action.right, 1f)
    }

    private fun assertArtAtTop(artTag: String, headerTag: String) {
        val art = compose.onNodeWithTag(artTag).fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag(headerTag).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, header.top, .5f)
        assertEquals(header.top, art.top, .5f)
    }

    private fun capture(tag: String, name: String): File {
        val file = File("build/reports/collection-header-review/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return file
    }
}
