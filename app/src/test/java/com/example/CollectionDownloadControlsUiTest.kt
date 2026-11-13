package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.downloads.*
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionDownloadControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: OfflineDownloads
    private val tracks = (1..2).map { MusicTrack("button-$it", "Song $it", "Artist", "Album", 120_000, "", "https://example.test/$it") }

    @Before fun prepare() = runBlocking { store = OfflineDownloads(context); store.load() }

    @Test fun percentageRingUpdatesFromLiveDownloadsAndPreservesPausedProgress() {
        runBlocking {
            store.put(DownloadRecord(tracks[0], DownloadStatus.COMPLETE))
            store.put(DownloadRecord(tracks[1], DownloadStatus.DOWNLOADING, bytes = 500, total = 1000))
        }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
            CollectionDownloadButton(rememberCollectionDownloadAction(tracks, "Mix", "playlist:mix", store = store))
        } } }
        val ring = compose.onNodeWithTag("collection_download_progress", useUnmergedTree = true)
        fun assertProgress(fraction: Float, description: String) {
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() &&
                    kotlin.math.abs(ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current - fraction) < .001f
            }
            assertEquals(fraction, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        }
        assertProgress(.75f, "Downloading · 75% · 1 of 2 tracks saved")
        compose.onNodeWithTag("download_collection").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Downloading · 75% · 1 of 2 tracks saved").assertExists()
        runBlocking { store.update(tracks[1].id, persist = false) { it.copy(bytes = 800) } }
        compose.waitForIdle()
        assertProgress(.9f, "Downloading · 90% · 1 of 2 tracks saved")
        runBlocking { store.update(tracks[1].id) { it.copy(status = DownloadStatus.PAUSED) } }
        compose.waitForIdle()
        assertProgress(.9f, "Resume download · 90% · 1 of 2 tracks saved")
        compose.onNodeWithTag("download_collection").assertIsEnabled()
        compose.onNodeWithContentDescription("Resume download · 90% · 1 of 2 tracks saved").assertExists()
        runBlocking { store.update(tracks[1].id) { it.copy(status = DownloadStatus.COMPLETE, bytes = 1000) } }
        compose.waitForIdle()
        assertProgress(1f, "Downloaded · 2 tracks saved")
        compose.onNodeWithTag("download_collection").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Downloaded · 2 tracks saved").assertExists()
    }

    @Test fun preparingTheWholeCollectionSurvivesTheHeaderLeavingComposition() {
        runBlocking { tracks.forEach { store.put(DownloadRecord(it, DownloadStatus.COMPLETE)) } }
        val gate = CompletableDeferred<List<MusicTrack>>()
        var headerVisible by mutableStateOf(true)
        var action: CollectionDownloadAction? = null
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
            val state = rememberCollectionDownloadAction(tracks.take(1), "Mix", "playlist:mix", collectionComplete = false,
                loadTracks = { gate.await() }, store = store)
            SideEffect { action = state }
            if (headerVisible) CollectionDownloadButton(state)
        } } }
        compose.onNodeWithTag("download_collection").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(action!!.loading); headerVisible = false }
        gate.complete(tracks)
        compose.waitUntil(5_000) { store.collections.value["playlist:mix"] == tracks.map { it.id } }
        compose.waitForIdle()
        compose.waitUntil(30_000) { action?.progress?.complete == true }
        compose.runOnIdle { assertTrue(action!!.progress.complete); headerVisible = true }
        compose.onNodeWithTag("download_collection").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Downloaded · 2 tracks saved").assertExists()
        val restored = OfflineDownloads(context)
        runBlocking { restored.load() }
        assertEquals(tracks.map { it.id }, restored.collections.value["playlist:mix"])
    }
}
