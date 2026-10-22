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
        compose.onNodeWithTag("switch_source_youtube").assertIsOn().assertIsEnabled()
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

    @Test fun movingAndTogglingASourcePersistsWithoutReplacingTheOtherRows() = runBlocking {
        OtherSettingsPreferences.savePlaybackSources(app, PlaybackSource.builtIns)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(app) != PlaybackSource.builtIns) delay(10) }
        withContext(Dispatchers.IO) { AudioQualityPreferences.setCellularQualityCeiling(app, AudioQualityCeiling.MAX); AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.MAX) }
        val monitor = NetworkQualityMonitor.getInstance(app)
        compose.setContent { MaterialTheme { SourcesScreen(onNavigateBack = {}) } }
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.ceiling == AudioQualityCeiling.MAX }
        compose.onNodeWithTag("source_up_jiosaavn").performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); OtherSettingsPreferences.playbackIntegrations(app).sources.firstOrNull()?.id == "jiosaavn" }
        compose.onNodeWithTag("switch_source_jiosaavn").performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); OtherSettingsPreferences.playbackIntegrations(app).sources.firstOrNull()?.enabled == false }
        compose.onNodeWithTag("switch_source_youtube").assertIsOn()
        compose.onNodeWithTag("switch_source_internet_archive").assertIsOn()
        assertEquals(3, OtherSettingsPreferences.playbackIntegrations(app).sources.size)
    }
    @Test fun alreadyPreferredVerifiedSourceShowsOnAndOffRequestsYoutubeWithoutEnablingDsp() = runBlocking {
        val ordered = listOf(PlaybackSource.jio, PlaybackSource.youtube, PlaybackSource.archive)
        OtherSettingsPreferences.savePlaybackSources(app, ordered)
        withTimeout(3000) { while (OtherSettingsPreferences.loadPlaybackSources(app) != ordered) delay(10) }
        withContext(Dispatchers.IO) {
            AudioQualityPreferences.setCellularQualityCeiling(app, AudioQualityCeiling.HIGH)
            AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.HIGH)
            AudioQualityPreferences.setAudioEnhancerEnabled(app, false)
        }
        val monitor = NetworkQualityMonitor.getInstance(app)
        val eqBefore = AudioEffectsManager.effectsState.value
        compose.setContent { MaterialTheme {
            com.example.ui.components.SignalPathPopupCard(SignalPathState(trackId = "yt_abcdefghijk",
                source = "JioSaavn", sourceId = PlaybackSource.jio.id,
                quality = StreamQualityBadge("AAC", 320, 44100, verified = true)))
        } }
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle();
            monitor.sourcePolicy.value.ceiling == AudioQualityCeiling.HIGH && monitor.sourcePolicy.value.playbackSources.first().id == "jiosaavn" }
        compose.onNodeWithTag("source_audio_enhancer").assertIsOn().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); monitor.sourcePolicy.value.returnToYouTube }
        compose.onNodeWithTag("source_audio_enhancer").assertIsOff()
        assertFalse(monitor.sourcePolicy.value.enhancer)
        assertEquals(PlaybackSource.youtube.id, monitor.sourcePolicy.value.resolutionSources.first().id)
        assertEquals(eqBefore, AudioEffectsManager.effectsState.value)
    }

}
