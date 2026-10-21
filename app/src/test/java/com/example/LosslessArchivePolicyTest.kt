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
            .declaredConstructors.first { it.parameterCount == 12 }.apply { isAccessible = true }
        val fallback = constructor.newInstance(0L, track, upgraded, 45_000L, 0L, true, true, Bundle(), true, false, null, true)
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

    @Test fun repeatedLossyPolicyNotificationsDoNotInvalidateAnEnhancerTransition() {
        set("losslessPolicyAllowed", false)
        val constructor = PlaybackManager::class.java.declaredClasses.first { it.simpleName == "LosslessFallback" }
            .declaredConstructors.first { it.parameterCount == 12 }.apply { isAccessible = true }
        val opus = track.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000&codec=opus",
            source = "YouTube Music", bitrateKbps = 146, qualityBadge = "Opus • 146 kbps")
        val fallback = constructor.newInstance(0L, track, opus, 45_000L, initialRequestEpoch,
            false, false, null, false, true, "HIGH:true:true", true)
        set("losslessFallback", fallback)
        repeat(3) { policy(ActiveQualityState(AudioQualityCeiling.HIGH, NetworkConnectionType.CELLULAR)) }
        assertEquals(initialRequestEpoch, field("losslessRequestEpoch").getLong(manager))
        assertSame(fallback, field("losslessFallback").get(manager))
        assertEquals(track, manager.uiState.value.currentTrack)
    }

    @Test fun sourceOnlyReplacementClearsTheExpiredInitialWatchdogWithoutChangingTheRecording() {
        val extras = Bundle().apply { putLong("jio_start_deadline", 1L); putBoolean("jio_youtube_fallback", true)
            putString("source_policy_key", "HIGH:false:true"); putLong("duration", 180_000L) }
        val original = androidx.media3.common.MediaItem.Builder().setMediaId(track.id).setUri(track.audioUrl)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setExtras(extras).build()).build()
        val replacement = PlaybackManager::class.java.getDeclaredMethod("forSourceTransition", androidx.media3.common.MediaItem::class.java)
            .apply { isAccessible = true }.invoke(manager, original) as androidx.media3.common.MediaItem
        assertEquals(0L, replacement.mediaMetadata.extras!!.getLong("jio_start_deadline"))
        assertFalse(replacement.mediaMetadata.extras!!.containsKey("jio_youtube_fallback"))
        assertEquals(original.mediaId, replacement.mediaId)
        assertEquals(original.localConfiguration!!.uri, replacement.localConfiguration!!.uri)
        assertEquals("HIGH:false:true", replacement.mediaMetadata.extras!!.getString("source_policy_key"))
        assertEquals(180_000L, replacement.mediaMetadata.extras!!.getLong("duration"))
        assertEquals("The original item must stay immutable", 1L, extras.getLong("jio_start_deadline"))

        // Exercise the service watchdog too: returning to AAC long after the
        // first selection must be allowed to buffer instead of immediately
        // entering recovery/stopping the now-disallowed Opus fallback.
        // This watchdog owns its own timer. Do not advance application-owned
        // startup admission loops, which use Android's monotonic clock.
        kotlinx.coroutines.test.runTest(StandardTestDispatcher(kotlinx.coroutines.test.TestCoroutineScheduler())) {
            var playing = false
            var recoveryCalls = 0
            val player = java.lang.reflect.Proxy.newProxyInstance(androidx.media3.common.Player::class.java.classLoader,
                arrayOf(androidx.media3.common.Player::class.java)) { _, method, _ ->
                when (method.name) {
                    "getCurrentMediaItem" -> replacement
                    "getCurrentMediaItemIndex" -> 0
                    "getPlayerError" -> null
                    "getPlayWhenReady" -> true
                    "isPlaying" -> playing
                    "getPlaybackState" -> androidx.media3.common.Player.STATE_BUFFERING
                    else -> error("Unexpected player command: ${method.name}")
                }
            } as androidx.media3.common.Player
            val watchdog = com.example.service.JioSaavnPlaybackFallback(player, this,
                resolve = { _, _ -> recoveryCalls++; error("A fresh source should have time to buffer") },
                status = {}, changed = {})
            watchdog.update()
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(5_999)
            testScheduler.runCurrent()
            assertEquals(0, recoveryCalls)
            playing = true
            watchdog.update()
            testScheduler.advanceUntilIdle()
            assertEquals(0, recoveryCalls)
            watchdog.cancel()
        }
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
