package com.example

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.playback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LosslessArchivePolicyTest {
    private lateinit var manager: PlaybackManager
    private val dispatcher = StandardTestDispatcher()
    private val track = MusicTrack("online_song", "Song", "Artist", "Album", 180_000,
        "cover", "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN", bitrateKbps = 320)
    private val maximum = ActiveQualityState(AudioQualityCeiling.MAX, NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET)
    private var initialRequestEpoch = 0L

    @Suppress("DEPRECATION")
    @Before fun ready() = runBlocking {
        Dispatchers.setMain(dispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        com.example.data.local.AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.MAX)
        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        connectivity.clearAllNetworks()
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        connectivity.addNetwork(network, info)
        connectivity.setActiveNetworkInfo(info)
        connectivity.setDefaultNetworkActive(true)
        connectivity.setNetworkCapabilities(network, caps)
        AudioEffectsManager.resetAll(false)
        manager = PlaybackManager(app)
        // Let the actual async monitor finish its initial OFFLINE -> Wi-Fi
        // transition before installing a fake Archive job. Otherwise that valid
        // startup policy change can cancel the job, depending on test order.
        awaitCondition {
            val monitor = field("networkQualityMonitor").get(manager) as? NetworkQualityMonitor
            monitor?.activeQualityCeiling?.value == maximum &&
                field("losslessPolicyAllowed").getBoolean(manager)
        }
        initialRequestEpoch = field("losslessRequestEpoch").getLong(manager)
        @Suppress("UNCHECKED_CAST")
        val state = field("_uiState").get(manager) as MutableStateFlow<PlayerUiState>
        state.value = PlayerUiState(currentTrack = track, queue = listOf(track), isPlaying = true,
            selectedQuality = AudioQuality.HI_RES_LOSSLESS, qualityBadge = "AAC • 320 kbps", currentPositionMs = 45_000)
    }

    @After fun clear() {
        manager.release()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        // This fixture can create the process monitor through the policy guard.
        // Do not retain its old Application/preferences in the next Robolectric test.
        val instance = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val monitor = instance.get(null)
        if (monitor != null) {
            val scope = NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(monitor) as CoroutineScope
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            instance.set(null, null)
        }
        AudioEffectsManager.resetAll(false)
    }

    private fun field(name: String) = PlaybackManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(manager, value)
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(3000) {
        while (!condition()) {
            dispatcher.scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            delay(10)
        }
    }
    private fun policy(value: ActiveQualityState) {
        PlaybackManager::class.java.getDeclaredMethod("updateLosslessPolicy", ActiveQualityState::class.java)
            .apply { isAccessible = true }.invoke(manager, value)
    }

    @Test fun equalizerControlsKeepTheAutomaticMaximumRequestAndPreservePlayback() {
        val job = Job()
        set("losslessUpgradeJob", job)
        set("losslessAttemptKey", "0|${track.id}")
        AudioEffectsManager.setEnabled(true)
        AudioEffectsManager.setBandLevel(0, 450)
        // Drain queued collectors while EQ is enabled too: a conflated final
        // disabled state must not conceal a forbidden intermediate restart.
        dispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val before = manager.uiState.value
        AudioEffectsManager.setEnabled(false)
        val eq = AudioEffectsManager.effectsState.value
        policy(maximum)
        dispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(job.isActive)
        assertSame(job, field("losslessUpgradeJob").get(manager))
        assertEquals("0|${track.id}", field("losslessAttemptKey").get(manager))
        assertEquals(before, manager.uiState.value)
        assertEquals(eq, AudioEffectsManager.effectsState.value)
        assertEquals(initialRequestEpoch, field("losslessRequestEpoch").getLong(manager))
    }

    @Test fun automaticQualityUpgradeRemainsIndependentAndRepeatedPolicyEmissionsDoNotRestartIt() {
        val job = Job()
        set("losslessUpgradeJob", job)
        policy(maximum)
        assertTrue(job.isActive)
        policy(maximum)
        policy(maximum)
        assertSame(job, field("losslessUpgradeJob").get(manager))
        assertEquals(initialRequestEpoch, field("losslessRequestEpoch").getLong(manager))
    }

    @Test fun equalizerEditsDoNotCancelAnAutomaticMaximumLookup() = runBlocking {
        val job = Job()
        set("losslessUpgradeJob", job)
        AudioEffectsManager.setBandLevel(0, 500)
        dispatcher.scheduler.runCurrent()
        assertTrue("EQ edits must not cancel lossless discovery", job.isActive)
        dispatcher.scheduler.runCurrent()
        assertTrue(job.isActive)
        assertEquals(AudioQuality.HI_RES_LOSSLESS, manager.uiState.value.selectedQuality)
    }

    @Test fun activeArchiveSourceAndPositionSurviveEqualizerChanges() {
        val upgraded = track.copy(audioUrl = "https://archive.org/download/album/song.flac",
            source = "INTERNET_ARCHIVE", isLossless = true, qualityBadge = "Lossless")
        @Suppress("UNCHECKED_CAST")
        val state = field("_uiState").get(manager) as MutableStateFlow<PlayerUiState>
        state.value = state.value.copy(currentTrack = upgraded, queue = listOf(upgraded), qualityBadge = "Lossless")
        val constructor = PlaybackManager::class.java.declaredClasses.first { it.simpleName == "LosslessFallback" }
            .declaredConstructors.first { it.parameterCount == 8 }.apply { isAccessible = true }
        val fallback = constructor.newInstance(0L, track, upgraded, 45_000L, 0L, true, true, Bundle())
        set("losslessFallback", fallback)
        val before = manager.uiState.value
        AudioEffectsManager.setEnabled(false)
        policy(maximum)
        assertSame(fallback, field("losslessFallback").get(manager))
        assertEquals(before, manager.uiState.value)

        // Maximum remains eligible independently of equalizer controls; the
        // current source and any automatic preparation remain untouched.
        val pendingBetterCopy = Job()
        set("losslessRequestEpoch", 1L)
        set("losslessUpgradeJob", pendingBetterCopy)
        policy(maximum)
        assertTrue(pendingBetterCopy.isActive)
        assertSame(fallback, field("losslessFallback").get(manager))
        assertEquals(before, manager.uiState.value)
    }

    @Test fun everyNonMaximumProfileOrOfflineStateCancelsPreparationWithoutReplacingAudio() {
        for (value in AudioQualityCeiling.entries.filter { it != AudioQualityCeiling.MAX }
            .map { ActiveQualityState(it, NetworkConnectionType.CELLULAR) } +
            ActiveQualityState(AudioQualityCeiling.MAX, NetworkConnectionType.OFFLINE)) {
            val job = Job()
            set("losslessUpgradeJob", job)
            policy(value)
            assertTrue(job.isCancelled)
            assertEquals(track, manager.uiState.value.currentTrack)
            assertEquals(45_000L, manager.uiState.value.currentPositionMs)
            assertEquals("AAC • 320 kbps", manager.uiState.value.qualityBadge)
        }
    }

    @Test fun newSelectionInvalidatesTheOldRequestAndClearsOnlyItsRetryState() {
        val job = Job()
        set("losslessUpgradeJob", job)
        set("losslessRetryAfterMs", 123_000L)
        set("losslessAttemptCount", 3)
        PlaybackManager::class.java.getDeclaredMethod("cancelLosslessUpgrade").apply { isAccessible = true }.invoke(manager)
        assertTrue(job.isCancelled)
        assertEquals(initialRequestEpoch + 1L, field("losslessRequestEpoch").getLong(manager))
        assertEquals(0L, field("losslessRetryAfterMs").getLong(manager))
        assertEquals(0, field("losslessAttemptCount").getInt(manager))
    }

    @Test fun repeatedMaximumPolicyKeepsAnActiveLookupAndItsRetryBudget() {
        val job = Job()
        set("losslessUpgradeJob", job)
        set("losslessAttemptCount", 2)
        policy(maximum)
        assertSame(job, field("losslessUpgradeJob").get(manager))
        assertEquals(2, field("losslessAttemptCount").getInt(manager))
    }

    @Test fun recoveryUsesLatestValidPositionAndNeverAcceptsAnInitializationResetToZero() {
        assertEquals(46_500L, losslessRecoveryPosition(45_000L, 46_500L, false))
        assertEquals(45_000L, losslessRecoveryPosition(45_000L, 0L, false))
        assertEquals(45_000L, losslessRecoveryPosition(45_000L, 0L, true))
        assertEquals(45_000L, losslessRecoveryPosition(45_000L, -1L, true))
        assertEquals(121_000L, losslessRecoveryPosition(45_000L, 121_000L, true))
        assertEquals(0L, losslessRecoveryPosition(0L, 0L, false)) // A real user seek to the beginning.
    }
}
