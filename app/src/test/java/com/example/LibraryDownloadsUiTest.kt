package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PlaylistEntity
import com.example.data.model.MusicTrack
import com.example.downloads.*
import com.example.ui.components.LocalDynamicGlassEnabled
import com.example.ui.components.LocalVisualEffectsActive
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.settings.DownloadsPage
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.github.takahirom.roborazzi.*
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalRoborazziApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1000dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryDownloadsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: OfflineDownloads
    private val ids = mutableListOf<String>()
    private var play: Pair<MusicTrack, List<MusicTrack>>? = null
    private val liked = MusicTrack("liked", "Favorite song", "Artist", "Album", 120_000, "", "https://example.test/liked")
    private val playlists = listOf(PlaylistEntity(1, "Road trip"), PlaylistEntity(2, "Evening mix"))

    @Before fun prepare() { store = OfflineDownloads(context); runBlocking { store.load() } }
    @After fun release() { ids.forEach(store::releaseCache) }

    private fun save(id: String, time: Long, status: DownloadStatus = DownloadStatus.COMPLETE, audio: Boolean = true) = runBlocking(Dispatchers.IO) {
        val track = MusicTrack(id, "Saved $id", "Artist", "Album", 120_000, "", "https://example.test/$id")
        val plan = OfflinePlan(track.audioUrl, "audio/mpeg", "test", true)
        if (audio && status == DownloadStatus.COMPLETE) {
            OfflineTransfer.save(store, id, plan, DataSource.Factory { ByteArrayDataSource(ByteArray(1024) { 1 }) })
        }
        store.put(DownloadRecord(track, status, bytes = 1024, total = 1024, plan = plan, addedAt = time))
        ids += id
        track
    }

    private fun showLibrary(colors: AppThemeColors = DarkAppColors, onImport: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalAppColors provides colors.copy(dynamicGlassEnabled = false),
                LocalDynamicGlassEnabled provides false, LocalVisualEffectsActive provides false,
                LocalArtworkRequestsActive provides false) {
                LibraryScreen(listOf(liked), playlists, onTrackClick = { track, queue -> play = track to queue },
                    onPlayDownloadedTrack = { track, queue -> play = track to queue }, onToggleFavorite = {},
                    onOpenPlaylist = {}, onCreatePlaylistClick = {}, onImportPlaylistClick = onImport, downloadStore = store)
            } }
        }
        compose.waitForIdle()
    }

    @Test fun pinnedCardsMatchPlaylistSquaresWithImportAboveThemInDarkMode() = checkLayout(DarkAppColors, "dark")
    @Test fun pinnedCardsMatchPlaylistSquaresWithImportAboveThemInLightMode() = checkLayout(LightAppColors, "light")
    @Test @Config(qualifiers = "w740dp-h600dp-land")
    fun landscapePinnedCardsRetainTheCustomPlaylistTileSize() = checkLayout(DarkAppColors, "landscape")

    private fun checkLayout(colors: AppThemeColors, name: String) {
        var imported = 0
        showLibrary(colors) { imported++ }
        val likedCard = compose.onNodeWithTag("open_liked_songs").fetchSemanticsNode().boundsInRoot
        val downloadsCard = compose.onNodeWithTag("open_downloads").fetchSemanticsNode().boundsInRoot
        val importedCard = compose.onNodeWithTag("import_playlist_banner_card").fetchSemanticsNode().boundsInRoot
        assertTrue(importedCard.bottom < likedCard.top)
        assertTrue(likedCard.right < downloadsCard.left)
        assertEquals(likedCard.top, downloadsCard.top, .5f)
        assertEquals(likedCard.width, downloadsCard.width, .5f)
        assertEquals(likedCard.height, downloadsCard.height, .5f)
        for (tag in listOf("open_liked_songs_artwork", "open_downloads_artwork")) {
            val square = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(square.width, square.height, .5f)
        }
        compose.onNodeWithTag("library_screen").performScrollToNode(hasTestTag("playlist_card_1"))
        val playlistCard = compose.onNodeWithTag("playlist_card_1").fetchSemanticsNode().boundsInRoot
        assertEquals("Use exactly the custom-playlist width", likedCard.width, playlistCard.width, .5f)
        assertEquals("Use exactly the custom-playlist height", likedCard.height, playlistCard.height, .5f)
        compose.onNodeWithTag("library_screen").performScrollToNode(hasTestTag("import_playlist_banner_card"))
        compose.onNodeWithTag("import_playlist_banner_card").performClick()
        assertEquals(1, imported)
        val file = File("build/reports/library-downloads/$name.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag("library_screen").captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    @Test fun downloadsOpensTheCollectionAndPlaysOnlySavedOfflineTracksInPlayAndShuffleOrder() {
        save("older", 1); save("newer", 2); save("paused", 3, DownloadStatus.PAUSED); save("failed", 4, DownloadStatus.FAILED)
        showLibrary()
        compose.onNodeWithTag("open_downloads").performClick()
        awaitDownloads()
        compose.onNodeWithTag("download_collection").assertDoesNotExist()
        val file = File("build/reports/library-downloads/collection.png").apply { parentFile?.mkdirs() }
        compose.onNodeWithTag("library_downloads_page").captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText("PLAY ALL").performScrollTo().performClick()
        compose.waitUntil(5_000) { play != null }
        assertEquals(listOf("newer", "older"), play!!.second.map { it.id })
        assertEquals("newer", play!!.first.id)
        assertTrue(play!!.second.all { Uri.parse(it.audioUrl).scheme == "xtreme-offline" && it.isCached })
        compose.runOnIdle { play = null }
        compose.onNodeWithText("SHUFFLE").performScrollTo().performClick()
        compose.waitUntil(5_000) { play != null }
        assertEquals(setOf("newer", "older"), play!!.second.map { it.id }.toSet())
        assertEquals(play!!.second.first().id, play!!.first.id)
        compose.onNodeWithText("Saved paused").assertDoesNotExist()
        compose.onNodeWithText("Saved failed").assertDoesNotExist()
        compose.onNodeWithTag("playlist_back_button").performScrollTo().performClick()
        compose.onNodeWithTag("library_screen").assertExists()
    }

    @Test fun missingSavedFilesCannotStartAnOnlineStreamAndTheCollectionUpdatesToEmpty() {
        save("missing", 1, audio = false)
        showLibrary()
        compose.onNodeWithTag("open_downloads").performClick()
        awaitDownloads()
        compose.onNodeWithText("PLAY ALL").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved audio is missing. Download this track again.").fetchSemanticsNodes().isNotEmpty() }
        assertNull(play)
        compose.onNodeWithText("No downloads yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("PLAY ALL").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("SHUFFLE").assertIsNotEnabled()
    }

    @Test fun settingsKeepsPreferencesAndStorageControlsWithoutTheSavedTrackBrowser() {
        save("only-in-library", 1)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
            DownloadsPage(store = store)
        } } }
        compose.onNodeWithTag("downloads_wifi_only").assertExists()
        compose.onNodeWithText("Download quality").assertExists()
        compose.onNodeWithText("Saved only-in-library").assertDoesNotExist()
        compose.onNodeWithText("Search downloads").assertDoesNotExist()
        compose.onNodeWithText("Play downloaded").assertDoesNotExist()
        compose.onNodeWithTag("clear_downloads").assertExists()
        compose.onNodeWithText("Find and play your saved tracks in Library → Downloads.").assertExists()
    }

    private fun awaitDownloads() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist_detail_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library_downloads_page").assertExists()
    }
}
