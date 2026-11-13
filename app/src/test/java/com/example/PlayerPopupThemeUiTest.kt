package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LyricsOffsetManager
import com.example.data.model.MusicTrack
import com.example.playback.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.util.LocalArtworkRequestsActive
import com.example.ui.util.playerAppearanceColors
import com.example.ui.viewmodel.PlaylistImportUiState
import com.github.takahirom.roborazzi.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.example.downloads.DownloadRecord
import com.example.downloads.DownloadStatus
import com.example.downloads.OfflineDownloads
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

@OptIn(ExperimentalRoborazziApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerPopupThemeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val track = MusicTrack("popup-track", "Test song", "Artist", "Album", 180_000, "", "")
    private var captureSuffix = ""

    @Before fun prepare() {
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        context.getSharedPreferences("lyrics_offset_preferences", 0).edit().clear().commit()
        LyricsOffsetManager.resetOffset(context, track.id, applyToAll = true)
    }

    @After fun cleanup() {
        AudioSignalPath.reset()
        LyricsOffsetManager.resetOffset(context, track.id, applyToAll = true)
        val instance = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (instance.get(null) as? NetworkQualityMonitor)?.let {
            (NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(it) as CoroutineScope).cancel()
        }
        instance.set(null, null)
    }

    private fun settle() {
        repeat(3) {
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    private fun capture(tag: String): java.awt.image.BufferedImage {
        val file = File("build/reports/player-popup-review/$tag$captureSuffix.png").apply { parentFile?.mkdirs() }
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            // Draw the node's actual window. The generic multi-window capture can
            // select the Activity behind a Popup or use incorrect bottom-sheet offsets.
            val view = (node.root as ViewRootForTest).view
            val rootView = view.rootView
            val full = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            rootView.draw(Canvas(full))
            val bounds = node.boundsInRoot
            val location = IntArray(2).also(view::getLocationInWindow)
            val origin = IntArray(2).also(rootView::getLocationInWindow)
            bitmap = Bitmap.createBitmap(full,
                bounds.left.roundToInt() + location[0] - origin[0],
                bounds.top.roundToInt() + location[1] - origin[1],
                bounds.width.roundToInt(), bounds.height.roundToInt())
        }
        bitmap.captureRoboImage(file.absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return ImageIO.read(file)
    }

    private fun assertBackground(tag: String, background: Color) {
        val image = capture(tag)
        val matching = (0 until image.height).sumOf { y ->
            (0 until image.width).count { x -> image.getRGB(x, y) == background.toArgb() }
        }
        val opaque = (0 until image.height).sumOf { y ->
            (0 until image.width).count { x -> image.getRGB(x, y).ushr(24) == 255 }
        }
        // A sheet's semantics bounds can include its transparent offscreen area during native capture.
        assertTrue("$tag must have an untinted $background surface", matching > opaque * .25)
        val visibleRows = (0 until image.height).filter { y -> image.getRGB(image.width / 2, y).ushr(24) == 255 }
        assertTrue("$tag must render an opaque surface", visibleRows.isNotEmpty())
        val middle = (visibleRows.first() + visibleRows.last()) / 2
        // The straight side of the rounded surface must have no contrasting outline.
        assertEquals("$tag outer border", background.toArgb(), image.getRGB(1, middle))
    }

    @Test fun playerPopupsFollowLightModeEvenInsideTheNeutralDarkPlayer() = checkPopups(LightAppColors)
    @Test fun playerPopupsUsePureBlackAndReadableControlsInDarkMode() = checkPopups(DarkAppColors)

    private fun checkPopups(source: AppThemeColors) {
        captureSuffix = if (source.isDark) "-dark" else "-light"
        var popup by mutableIntStateOf(0)
        var size by mutableIntStateOf(32)
        var enabled by mutableStateOf(true)
        var selectedProvider by mutableStateOf("Auto")
        var dismissed = 0
        // Extreme saved accents previously caused white-on-white switches and selected text.
        val original = source.copy(primaryAccent = if (source.isDark) Color.White else Color.Yellow)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides playerAppearanceColors(original),
                LocalPlayerPopupColors provides original, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF708090))) {
                    when (popup) {
                        0 -> SoundOutputDeviceDialog(emptyList(), onSelectDevice = {}, onDismiss = { dismissed++ })
                        1 -> QueueBottomSheet(listOf(track), track, {}, { _, _ -> }, { dismissed++ })
                        2 -> SleepTimerDialog { dismissed++ }
                        3 -> EqualizerDialog(AudioEffectsState(isEnabled = enabled,
                            availablePresets = listOf("Flat", "Rock & Metal")), { enabled = it }, {}, { _, _ -> },
                            {}, {}, {}, { dismissed++ })
                        4 -> LyricsOffsetBottomSheet(track, { dismissed++ })
                        5 -> AddToPlaylistDialog(track, emptyList(), onPlaylistSelected = {},
                            onCreateNewPlaylist = {}, onDismiss = { dismissed++ })
                        6 -> SignalPathPopupCard(SignalPathState(), onClose = { dismissed++ })
                        7 -> LyricsTextSizePopup(size, { size = it }, { dismissed++ })
                        8 -> LyricsProviderSelectionPopup(selectedProvider, { selectedProvider = it },
                            { dismissed++ }, allowImport = true)
                    }
                }
            }
        } }
        val tags = listOf("sound_output_devices_dialog", "queue_bottom_sheet", "sleep_timer_popup",
            "equalizer_dialog", "lyrics_offset_sheet", "add_to_playlist_popup", "signal_path_popup",
            "lyrics_text_size_popup", "lyrics_provider_popup")
        val background = if (source.isDark) Color.Black else Color.White
        tags.forEachIndexed { index, tag ->
            compose.runOnIdle { popup = index }
            settle()
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertBackground(tag, background)
            when (index) {
                3 -> {
                    compose.onNodeWithTag("equalizer_master_switch").assertIsOn().performClick()
                    settle()
                    compose.onNodeWithTag("equalizer_master_switch").assertIsOff()
                }
                4 -> {
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithTag("lyrics_offset_value_text")
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertEquals(playerPopupColors(original).textPrimary, layouts.single().layoutInput.style.color)
                    compose.onNodeWithTag("lyrics_offset_apply_all").assertIsOn().performClick()
                    settle()
                    compose.onNodeWithTag("lyrics_offset_apply_all").assertIsOff()
                    assertFalse(LyricsOffsetManager.usesGlobalOffset(context, track.id))
                }
                8 -> {
                    compose.onNodeWithText("Import Lyrics").assertExists()
                    compose.onNodeWithText("Auto", useUnmergedTree = true).performClick()
                    assertEquals("Auto", selectedProvider)
                    assertEquals(1, dismissed)
                }
            }
        }
    }

    @Test fun actualPlayerMenuAndItsChildDialogUseTheSelectedAppMode() {
        var colors by mutableStateOf(LightAppColors.copy(dynamicGlassEnabled = false))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = track), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {}, isScreenVisible = false)
            }
        } }
        settle()
        compose.onNodeWithTag("player_options_button").performClick()
        settle()
        assertBackground("player_options_popup", Color.White)
        assertDownloadRow("Download")
        compose.onNodeWithText("Sleep Mode").performClick()
        settle()
        assertBackground("sleep_timer_popup", Color.White)
        compose.onNodeWithText("Close").performClick()
        settle()
        compose.runOnIdle { colors = DarkAppColors.copy(dynamicGlassEnabled = false) }
        settle()
        compose.onNodeWithTag("player_options_button").performClick()
        settle()
        assertBackground("player_options_popup", Color.Black)
        assertDownloadRow("Download")
        val downloads = OfflineDownloads.get(context)
        for ((status, title) in listOf(DownloadStatus.COMPLETE to "Remove download",
            DownloadStatus.PAUSED to "Resume download", DownloadStatus.DOWNLOADING to "Pause download",
            DownloadStatus.QUEUED to "Pause download", DownloadStatus.FAILED to "Resume download")) {
            runBlocking { downloads.put(DownloadRecord(track, status)) }
            settle()
            assertDownloadRow(title)
            val progress = compose.onNodeWithTag("player_download_progress", useUnmergedTree = true)
            if (status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING) {
                assertEquals(ProgressBarRangeInfo.Indeterminate,
                    progress.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
            } else progress.assertDoesNotExist()
        }
        runBlocking { downloads.put(DownloadRecord(track, DownloadStatus.PAUSED)) }
    }

    @Test fun downloadIconProgressFollowsLiveBytesPauseResumeAndCompletion() {
        val progressTrack = track.copy(id = "menu-progress")
        val downloads = OfflineDownloads.get(context)
        runBlocking {
            downloads.load()
            downloads.put(DownloadRecord(progressTrack, DownloadStatus.DOWNLOADING, bytes = 0, total = 1000))
        }
        var colors by mutableStateOf(DarkAppColors.copy(dynamicGlassEnabled = false))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors, LocalArtworkRequestsActive provides false,
                LocalVisualEffectsActive provides false) {
                ExpandedPlayerScreen(PlayerUiState(currentTrack = progressTrack), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAddToPlaylist = {}, isScreenVisible = false)
            }
        } }
        settle()
        compose.onNodeWithTag("player_options_button").performClick()
        settle()
        val ring = compose.onNodeWithTag("player_download_progress", useUnmergedTree = true)
        fun assertProgress(fraction: Float) {
            assertEquals(fraction, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            val iconBounds = compose.onNodeWithTag("player_download_icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("The progress line surrounds the existing icon", iconBounds, ring.fetchSemanticsNode().boundsInRoot)
        }
        assertProgress(0f)
        for (bytes in listOf(250L, 730L)) {
            runBlocking { downloads.update(progressTrack.id, persist = false) { it.copy(bytes = bytes) } }
            settle()
            assertProgress(bytes / 1000f)
        }
        captureSuffix = "-progress-dark"
        assertBackground("player_options_popup", Color.Black)
        runBlocking { downloads.update(progressTrack.id) { it.copy(status = DownloadStatus.PAUSED) } }
        settle()
        assertDownloadRow("Resume download")
        assertProgress(.73f)
        compose.runOnIdle { colors = LightAppColors.copy(dynamicGlassEnabled = false) }
        settle()
        assertProgress(.73f)
        captureSuffix = "-progress-light"
        assertBackground("player_options_popup", Color.White)
        runBlocking { downloads.update(progressTrack.id) { it.copy(status = DownloadStatus.DOWNLOADING, bytes = 850) } }
        settle()
        assertDownloadRow("Pause download")
        assertProgress(.85f)
        runBlocking { downloads.update(progressTrack.id) { it.copy(total = -1) } }
        settle()
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        runBlocking { downloads.update(progressTrack.id) { it.copy(status = DownloadStatus.COMPLETE, bytes = 1000, total = 1000) } }
        settle()
        assertDownloadRow("Remove download")
        ring.assertDoesNotExist()
    }

    private fun assertDownloadRow(title: String) {
        val text = compose.onNodeWithText(title, useUnmergedTree = true)
        val share = compose.onNodeWithText("Share Track", useUnmergedTree = true)
        val bounds = text.fetchSemanticsNode().boundsInRoot
        val shareBounds = share.fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("player_download_icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Download text uses the same inset as the other options", shareBounds.left, bounds.left, .5f)
        assertEquals(bounds.center.y, icon.center.y, .5f)
        assertEquals(34f * context.resources.displayMetrics.density, icon.width, .5f)
        fun style(node: SemanticsNodeInteraction): androidx.compose.ui.text.TextStyle {
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style
        }
        assertEquals(style(share).fontWeight, style(text).fontWeight)
        assertEquals(style(share).fontSize, style(text).fontSize)
        assertEquals(style(share).color, style(text).color)
    }

    @Test @Config(sdk = [34])
    fun libraryImportSelectionHasReadableInkAndStillChangesPlatform() {
        var colors by mutableStateOf(LightAppColors.copy(dynamicGlassEnabled = false))
        var platform by mutableStateOf("Link")
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAppColors provides colors) {
                ImportSetupContent(PlaylistImportUiState(selectedPlatform = platform), false, {},
                    onInputChanged = {}, onPlatformChanged = { platform = it }, onTokenChanged = {},
                    onLoadSample = {}, onPasteFromClipboard = {}, onStartImport = {})
            }
        } }
        settle()
        fun assertLabel() {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(platform, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val ink = layouts.single().layoutInput.style.color
            val surface = colors.primaryAccent.copy(alpha = if (colors.isDark) .35f else .12f)
            val actual = androidx.compose.ui.graphics.lerp(colors.cardBackground, surface.copy(alpha = 1f), surface.alpha)
            val contrast = (maxOf(ink.luminance(), actual.luminance()) + .05f) /
                (minOf(ink.luminance(), actual.luminance()) + .05f)
            assertTrue("Selected import pill text must contrast with its translucent fill", contrast >= 4.5f)
        }
        assertLabel()
        compose.onNodeWithTag("platform_chip_Spotify").performClick()
        settle()
        assertEquals("Spotify", platform)
        assertLabel()
        compose.runOnIdle { colors = DarkAppColors.copy(dynamicGlassEnabled = false) }
        settle()
        assertLabel()
    }
}
