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
        // transition before checking explicit source transitions.
        awaitCondition {
            val monitor = field("networkQualityMonitor").get(manager) as? NetworkQualityMonitor
            monitor?.sourcePolicy?.value?.let { it.ready && it.ceiling == maximum.effectiveCeiling } == true &&
                manager.uiState.value.selectedQuality == AudioQuality.HI_RES_LOSSLESS
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

    @Test fun youtubeOnlyFailureCannotCascadeThroughTheRestOfTheQueue() = runBlocking {
        val sources = com.example.data.local.PlaybackSourcesPreferences
        val app = ApplicationProvider.getApplicationContext<Application>()
        sources.save(app, PlaybackSource.newInstallDefaults)
        awaitCondition {
            (field("networkQualityMonitor").get(manager) as? NetworkQualityMonitor)?.sourcePolicy?.value?.playbackSources
                ?.map { it.id } == listOf(PlaybackSource.youtube.id)
        }
        val next = track.copy(id = "yt_lmnopqrstuv", title = "Next Song", audioUrl = "")
        manager.continueAfterUnavailableSelection(track, listOf(track, next), false, "Temporary extraction failure")
        assertEquals(track.id, manager.uiState.value.currentTrack?.id)
        assertEquals("Temporary extraction failure", manager.uiState.value.errorMessage)
        assertNull(field("playResolutionJob").get(manager))
    }
    private fun policy(value: ActiveQualityState) {
        PlaybackManager::class.java.getDeclaredMethod("updateLosslessPolicy", AudioQualityCeiling::class.java)
            .apply { isAccessible = true }.invoke(manager, value.effectiveCeiling)
    }

    @Test fun equalizerControlsKeepTheExplicitSourceTransitionAndPreservePlayback() {
        val job = Job()
        set("youtubeQualityJob", job)
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
        assertSame(job, field("youtubeQualityJob").get(manager))
        assertEquals(before, manager.uiState.value)
        assertEquals(eq, AudioEffectsManager.effectsState.value)
        assertEquals(initialRequestEpoch, field("losslessRequestEpoch").getLong(manager))
    }

    @Test fun repeatedQualityNotificationsDoNotStartArchiveDiscovery() {
        repeat(3) { policy(maximum) }
        assertNull(field("youtubeQualityJob").get(manager))
        assertEquals(initialRequestEpoch, field("losslessRequestEpoch").getLong(manager))
        assertEquals(track, manager.uiState.value.currentTrack)
    }

    @Test fun equalizerEditsDoNotCancelAnExplicitSourceTransition() = runBlocking {
        val job = Job()
        set("youtubeQualityJob", job)
        AudioEffectsManager.setBandLevel(0, 500)
        dispatcher.scheduler.runCurrent()
        assertTrue("EQ edits must not cancel an explicit provider transition", job.isActive)
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
            .declaredConstructors.first { it.parameterCount == 14 }.apply { isAccessible = true }
        val fallback = constructor.newInstance(0L, track, upgraded, 45_000L, 0L, true, true, Bundle(), true, false, null, true, null, null)
        set("losslessFallback", fallback)
        val before = manager.uiState.value
        AudioEffectsManager.setEnabled(false)
        policy(maximum)
        assertSame(fallback, field("losslessFallback").get(manager))
        assertEquals(before, manager.uiState.value)

        // Equalizer edits preserve both the current recording and any explicit
        // transition owned by the source-policy coordinator.
        val pendingBetterCopy = Job()
        set("losslessRequestEpoch", 1L)
        set("youtubeQualityJob", pendingBetterCopy)
        policy(maximum)
        assertTrue(pendingBetterCopy.isActive)
        assertSame(fallback, field("losslessFallback").get(manager))
        assertEquals(before, manager.uiState.value)
    }

    @Test fun explicitPolicyChangesCancelObsoleteSourceRequestsWithoutChangingDisplayedMetadata() {
        for (ceiling in AudioQualityCeiling.entries) {
            val job = Job()
            set("youtubeQualityJob", job)
            PlaybackManager::class.java.getDeclaredMethod("reconcileSourcePolicy", PlaybackSourcePolicy::class.java,
                Boolean::class.javaPrimitiveType, String::class.java, MusicTrack::class.java,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(manager, PlaybackSourcePolicy(ceiling), true, null, null, false)
            assertTrue(job.isCancelled)
            assertEquals(track, manager.uiState.value.currentTrack)
            assertEquals(45_000L, manager.uiState.value.currentPositionMs)
        }
    }

    @Test fun newSelectionInvalidatesTheOldSourceTransitionEpoch() {
        PlaybackManager::class.java.getDeclaredMethod("cancelLosslessUpgrade").apply { isAccessible = true }.invoke(manager)
        assertEquals(initialRequestEpoch + 1L, field("losslessRequestEpoch").getLong(manager))
        assertNull(field("losslessFallback").get(manager))
    }

    @Test fun repeatedLossyPolicyNotificationsDoNotInvalidateAnEnhancerTransition() {
        val constructor = PlaybackManager::class.java.declaredClasses.first { it.simpleName == "LosslessFallback" }
            .declaredConstructors.first { it.parameterCount == 14 }.apply { isAccessible = true }
        val opus = track.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000&codec=opus",
            source = "YouTube Music", bitrateKbps = 146, qualityBadge = "Opus • 146 kbps")
        val fallback = constructor.newInstance(0L, track, opus, 45_000L, initialRequestEpoch,
            false, false, null, false, true, "HIGH:true:true", true, null, null)
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
                requestRecovery = { _, _ -> recoveryCalls++; error("A fresh source should have time to buffer") },
                status = {})
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

    @Test fun archiveCatalogIdentityCannotMarkYoutubePlaybackAsLossless() {
        val item = androidx.media3.common.MediaItem.Builder().setMediaId("ia_album:song.flac")
            .setUri("xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000")
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("Song").setArtist("Artist")
                .setExtras(Bundle().apply { putString("media_source", "YOUTUBE"); putBoolean("isLossless", false) }).build()).build()
        val actual = PlaybackManager::class.java.getDeclaredMethod("controllerTrack", androidx.media3.common.MediaItem::class.java)
            .apply { isAccessible = true }.invoke(manager, item) as MusicTrack
        assertFalse(actual.isLossless)
        assertEquals(item.mediaId, actual.id)
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
