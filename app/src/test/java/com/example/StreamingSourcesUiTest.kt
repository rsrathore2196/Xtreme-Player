package com.example

import android.app.Application
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.OtherSettingsPreferences
import com.example.playback.*
import com.example.ui.screens.settings.SourcesScreen
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingSourcesUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun startFreshMonitor() { stopMonitor() }
    @After fun stopMonitor() {
        AudioSignalPath.reset()
        val field = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? NetworkQualityMonitor)?.let { monitor ->
            (NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(monitor) as CoroutineScope).cancel()
        }
        field.set(null, null)
    }

    @Test fun restrictedSourcesAreOffAndDisabledWhileSavedPreferencesRemainIntact() = runBlocking {
        val sources = PlaybackSource.builtIns
        OtherSettingsPreferences.savePlaybackSources(app, sources)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(app) != sources) delay(10) }
        withContext(Dispatchers.IO) { AudioQualityPreferences.setCellularQualityCeiling(app, AudioQualityCeiling.NORMAL); AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.NORMAL) }
        val monitor = NetworkQualityMonitor.getInstance(app)
        compose.setContent { MaterialTheme { SourcesScreen(onNavigateBack = {}) } }
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.ceiling == AudioQualityCeiling.NORMAL }
        compose.onNodeWithTag("switch_source_youtube").assertDoesNotExist()
        compose.onNodeWithTag("badge_always_on_youtube").assertExists()
        compose.onNodeWithTag("source_up_youtube").assertDoesNotExist()
        compose.onNodeWithTag("source_down_youtube").assertDoesNotExist()
        compose.onNodeWithTag("switch_source_jiosaavn").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("switch_source_internet_archive").assertIsOff().assertIsNotEnabled()
        assertTrue(OtherSettingsPreferences.playbackIntegrations(app).sources.all { it.enabled })
        withContext(Dispatchers.IO) { AudioQualityPreferences.setCellularQualityCeiling(app, AudioQualityCeiling.HIGH); AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.HIGH) }
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.ceiling == AudioQualityCeiling.HIGH }
        compose.onNodeWithTag("switch_source_jiosaavn").assertIsOn().assertIsEnabled()
        compose.onNodeWithTag("switch_source_internet_archive").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("add_addon_row").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Add Streaming Addon").assertExists()
        compose.onNodeWithTag("input_addon_url").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        Unit
    }

    @Test fun onlyCustomRowsMoveAndJioStillHasAnIndependentSwitch() = runBlocking {
        val first = PlaybackSource(PlaybackSource.addonId("https://first.example"), PlaybackSourceKind.ADDON, "First", baseUrl = "https://first.example")
        val second = PlaybackSource(PlaybackSource.addonId("https://second.example"), PlaybackSourceKind.ADDON, "Second", baseUrl = "https://second.example")
        val initial = listOf(first, second) + PlaybackSource.builtIns
        OtherSettingsPreferences.savePlaybackSources(app, initial)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(app) != initial) delay(10) }
        withContext(Dispatchers.IO) { AudioQualityPreferences.setCellularQualityCeiling(app, AudioQualityCeiling.MAX); AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.MAX) }
        val monitor = NetworkQualityMonitor.getInstance(app)
        compose.setContent { MaterialTheme { SourcesScreen(onNavigateBack = {}) } }
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.ceiling == AudioQualityCeiling.MAX }
        compose.onNodeWithTag("source_up_jiosaavn").assertDoesNotExist()
        compose.onNodeWithTag("source_up_internet_archive").assertDoesNotExist()
        compose.onNodeWithTag("source_up_${second.id}").assertDoesNotExist()
        compose.onNodeWithTag("source_down_${first.id}").assertDoesNotExist()
        compose.onNodeWithTag("source_drag_${first.id}").performScrollTo().performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 180f), 300L)
        }
        assertEquals("A drag must not write preferences before drop", initial, OtherSettingsPreferences.loadPlaybackSources(app))
        compose.onNodeWithTag("source_drag_${first.id}").performTouchInput { cancel() }
        compose.waitUntil(5000) { compose.onNodeWithTag("source_row_${first.id}").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("source_row_${second.id}").fetchSemanticsNode().boundsInRoot.top }
        assertEquals("Cancellation must retain the saved source order", initial, OtherSettingsPreferences.loadPlaybackSources(app))
        compose.onNodeWithTag("source_drag_${first.id}").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 180f), 300L)
        }
        compose.onNodeWithTag("source_drag_${first.id}").performTouchInput { up() }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle();
            OtherSettingsPreferences.playbackIntegrations(app).sources.firstOrNull()?.id == second.id }
        assertEquals(second.id, OtherSettingsPreferences.playbackIntegrations(app).sources.first().id)
        compose.waitUntil(5000) { compose.onNodeWithTag("source_row_${first.id}").fetchSemanticsNode().boundsInRoot.top >
            compose.onNodeWithTag("source_row_${second.id}").fetchSemanticsNode().boundsInRoot.top }
        compose.onNodeWithContentDescription("Reorder First").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -140f), 300L)
            up()
        }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle();
            OtherSettingsPreferences.playbackIntegrations(app).sources.firstOrNull()?.id == first.id }
        compose.onNodeWithTag("switch_source_jiosaavn").performScrollTo().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle();
            OtherSettingsPreferences.playbackIntegrations(app).sources.firstOrNull { it.id == "jiosaavn" }?.enabled == false }
        compose.onNodeWithTag("switch_source_youtube").assertDoesNotExist()
        assertEquals(5, OtherSettingsPreferences.playbackIntegrations(app).sources.size)
    }
    @Test fun alreadyPreferredVerifiedSourceShowsOnAndOffRequestsYoutubeWithoutEnablingDsp() = runBlocking {
        val ordered = PlaybackSource.builtIns
        OtherSettingsPreferences.savePlaybackSources(app, ordered)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(app) != ordered) delay(10) }
        withContext(Dispatchers.IO) {
            AudioQualityPreferences.setCellularQualityCeiling(app, AudioQualityCeiling.HIGH)
            AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.HIGH)
            AudioQualityPreferences.setAudioEnhancerEnabled(app, false)
        }
        val monitor = NetworkQualityMonitor.getInstance(app)
        val eqBefore = AudioEffectsManager.effectsState.value
        AudioSignalPath.update { SignalPathState(trackId = "yt_abcdefghijk",
            source = "JioSaavn", sourceId = PlaybackSource.jio.id,
            quality = StreamQualityBadge("AAC", 320, 44100, verified = true)) }
        compose.setContent { MaterialTheme {
            com.example.ui.components.SourceUpgradeMenuItem("yt_abcdefghijk", onDismiss = {})
        } }
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle();
            monitor.sourcePolicy.value.ceiling == AudioQualityCeiling.HIGH &&
                monitor.sourcePolicy.value.playbackSources.map { it.id } == listOf("youtube", "jiosaavn") }
        compose.onNodeWithText("Revert to Original").assertIsEnabled().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.returnToYouTube }
        compose.onNodeWithText("Revert to Original").assertExists()
        AudioSignalPath.update { it.copy(sourceId = PlaybackSource.youtube.id, source = "YouTube Music",
            quality = StreamQualityBadge("OPUS", 146, 48000, verified = true)) }
        compose.onNodeWithText("Upgrade Audio").assertExists()
        assertFalse(monitor.sourcePolicy.value.enhancer)
        assertEquals(PlaybackSource.youtube.id, monitor.sourcePolicy.value.resolutionSources.first().id)
        assertEquals(eqBefore, AudioEffectsManager.effectsState.value)
    }

    @Test fun menuStatusWaitsForActualSourceChangeAndFailedUpgradeCanBeRetriedWithoutDspChanges() = runBlocking {
        OtherSettingsPreferences.savePlaybackSources(app, PlaybackSource.builtIns)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(app) != PlaybackSource.builtIns) delay(10) }
        withContext(Dispatchers.IO) {
            AudioQualityPreferences.setCellularQualityCeiling(app, AudioQualityCeiling.MAX)
            AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.MAX)
            AudioQualityPreferences.setAudioEnhancerEnabled(app, false)
        }
        val monitor = NetworkQualityMonitor.getInstance(app)
        AudioSignalPath.update { SignalPathState(trackId = "playing", sourceId = PlaybackSource.youtube.id,
            source = "YouTube Music", streamUri = "xtreme-youtube://audio/abcdefghijk?cap=320000",
            quality = StreamQualityBadge("OPUS", 160, 48000, verified = true)) }
        var dismissed = 0
        val eqBefore = AudioEffectsManager.effectsState.value
        compose.setContent { MaterialTheme {
            com.example.ui.components.SourceUpgradeMenuItem("playing", onDismiss = { dismissed++ })
        } }
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.ceiling == AudioQualityCeiling.MAX }
        compose.onNodeWithText("Upgrade Audio").assertIsEnabled().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.enhancer }
        compose.onNodeWithText("Upgrade Audio").assertExists()
        AudioSignalPath.sourceRequest("playing", true)
        compose.onNodeWithText("Upgrade Audio").assertIsNotEnabled()
        AudioSignalPath.sourceRequest("playing", false)
        val revision = monitor.sourcePolicy.value.requestRevision
        compose.onNodeWithText("Upgrade Audio").performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.requestRevision > revision }
        compose.onNodeWithText("Upgrade Audio").assertExists()
        AudioSignalPath.update { it.copy(sourceId = PlaybackSource.jio.id, source = "JioSaavn",
            enhancerOriginalSourceId = PlaybackSource.youtube.id, enhancerOriginalUri = it.streamUri,
            streamUri = "https://aac.saavncdn.com/song_320.mp4",
            quality = StreamQualityBadge("AAC", 320, 44100, verified = true)) }
        compose.onNodeWithText("Revert to Original").performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !monitor.sourcePolicy.value.enhancer }
        compose.onNodeWithText("Revert to Original").assertExists()
        // A failed return leaves the verified upgraded stream and its return bookmark intact.
        AudioSignalPath.sourceRequest("playing", true)
        compose.onNodeWithText("Revert to Original").assertIsNotEnabled()
        AudioSignalPath.sourceRequest("playing", false)
        compose.onNodeWithText("Revert to Original").assertExists()
        AudioSignalPath.update { it.copy(sourceId = PlaybackSource.youtube.id, source = "YouTube Music",
            streamUri = it.enhancerOriginalUri, enhancerOriginalSourceId = "", enhancerOriginalUri = "",
            quality = StreamQualityBadge("OPUS", 160, 48000, verified = true)) }
        compose.onNodeWithText("Upgrade Audio").assertExists()
        assertEquals(3, dismissed)
        assertFalse(monitor.sourcePolicy.value.returnToYouTube)
        assertEquals(eqBefore, AudioEffectsManager.effectsState.value)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        Unit
    }

}
