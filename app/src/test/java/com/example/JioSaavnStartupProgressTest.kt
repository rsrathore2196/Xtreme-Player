package com.example

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.example.service.JioSaavnPlaybackFallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class JioSaavnStartupProgressTest {
    private class FrozenPlayer(ready: Boolean = true, playing: Boolean = true) : SimpleBasePlayer(Looper.getMainLooper()) {
        private val item = MediaItem.Builder().setMediaId("online_frozen")
            .setUri("https://aac.saavncdn.com/song_320.mp4").build()
        private val state = State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("song").setMediaItem(item).setDurationUs(180_000_000L).build()))
            .setCurrentMediaItemIndex(0).setContentPositionMs(PositionSupplier { 0L })
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (ready) Player.STATE_READY else Player.STATE_BUFFERING).build()
        override fun getState(): State = state
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    @Test fun mediaControllerEstimatesThreeSecondsEvenWhenTheServicePositionHasNotMoved() = runBlocking {
        val native = FrozenPlayer()
        val session = MediaSession.Builder(ApplicationProvider.getApplicationContext<Application>(), native).build()
        val future = MediaController.Builder(ApplicationProvider.getApplicationContext<Application>(), session.token).buildAsync()
        var controller: MediaController? = null
        try {
            withTimeout(3_000) {
                while (!future.isDone) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            }
            controller = future.get()
            assertTrue(native.isPlaying)
            assertEquals(0L, native.currentPosition)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500))
            assertTrue("Controller extrapolates before its periodic service update: ${controller.currentPosition}",
                controller.currentPosition >= 2_000L)
            assertEquals("The actual output position is still frozen", 0L, native.currentPosition)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertTrue("The next periodic sample resets speculative progress: ${controller.currentPosition}",
                controller.currentPosition < 1_000L)
        } finally { controller?.release(); session.release(); native.release() }
    }

    @Test fun readyButSilentStartupCannotCancelTheServiceWatchdog() {
        val native = FrozenPlayer()
        val dispatcher = StandardTestDispatcher()
        var recoveries = 0
        val watchdog = JioSaavnPlaybackFallback(native, CoroutineScope(dispatcher),
            requestRecovery = { _, _ -> recoveries++ }, status = {})
        try {
            watchdog.update()
            dispatcher.scheduler.advanceTimeBy(6_001L)
            dispatcher.scheduler.runCurrent()
            assertEquals("READY/isPlaying alone does not prove audio output", 1, recoveries)
        } finally { watchdog.cancel(); native.release() }
    }

    @Test fun foregroundProgressUsesTheNativeOutputInsteadOfTheControllerEstimate() = runBlocking {
        Dispatchers.setMain(StandardTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val native = FrozenPlayer()
        val session = MediaSession.Builder(app, native).build()
        val future = MediaController.Builder(app, session.token).buildAsync()
        val manager = com.example.playback.PlaybackManager(app)
        val sample = com.example.service.MusicService::class.java.getDeclaredField("nativePlaybackProgress").apply { isAccessible = true }
        var controller: MediaController? = null
        try {
            withTimeout(3_000) { while (!future.isDone) { shadowOf(Looper.getMainLooper()).idle(); delay(10) } }
            controller = future.get()
            com.example.playback.PlaybackManager::class.java.getDeclaredMethod("setController", MediaController::class.java)
                .apply { isAccessible = true }.invoke(manager, controller)
            com.example.playback.PlaybackManager::class.java.getDeclaredField("uiForeground")
                .apply { isAccessible = true }.setBoolean(manager, true)
            val update = com.example.playback.PlaybackManager::class.java.getDeclaredMethod("updateProgressValues").apply { isAccessible = true }
            val item = native.currentMediaItem!!
            sample.set(null, { com.example.playback.PlaybackPositionClock.ServiceSample(item.mediaId,
                item.localConfiguration!!.uri.toString(), 0L, false) })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500))
            assertTrue(controller.currentPosition >= 2_000L)
            update.invoke(manager)
            assertEquals(0L, manager.playbackPosition.value)
            assertEquals(0L, manager.currentPlaybackPositionMs)
            sample.set(null, { com.example.playback.PlaybackPositionClock.ServiceSample("old-track", "old-url", 99_000L, true) })
            update.invoke(manager)
            assertTrue("A stale service result must be ignored", manager.playbackPosition.value in 2_000L..2_999L)
        } finally {
            sample.set(null, null)
            manager.release(); controller?.release(); session.release(); native.release(); Dispatchers.resetMain()
        }
    }

    @Test fun realAudioConfirmationCancelsStartupRecoveryAndPausedPlaybackDoesNotRetry() {
        for (playing in listOf(false, true)) {
            val native = FrozenPlayer(playing = playing)
            val dispatcher = StandardTestDispatcher()
            var recoveries = 0
            val watchdog = JioSaavnPlaybackFallback(native, CoroutineScope(dispatcher),
                requestRecovery = { _, _ -> recoveries++ }, status = {})
            try {
                watchdog.update()
                if (playing) watchdog.onAudioPositionAdvancing()
                dispatcher.scheduler.advanceTimeBy(20_000L)
                dispatcher.scheduler.runCurrent()
                assertEquals(0, recoveries)
                assertEquals(playing, watchdog.audioStarted)
            } finally { watchdog.cancel(); native.release() }
        }
    }

    @Test fun stalledOutputIsRepairedBeforeTryingAnotherSource() {
        val native = FrozenPlayer()
        val dispatcher = StandardTestDispatcher()
        var outputRepairs = 0
        var sourceRepairs = 0
        val watchdog = JioSaavnPlaybackFallback(native, CoroutineScope(dispatcher),
            requestRecovery = { _, _ -> sourceRepairs++ }, status = {},
            requestOutputRecovery = { id, uri ->
                assertEquals(native.currentMediaItem!!.mediaId, id)
                assertEquals(native.currentMediaItem!!.localConfiguration!!.uri.toString(), uri)
                ++outputRepairs == 1 // The service owns the existing bounded output guard.
            })
        try {
            watchdog.update()
            dispatcher.scheduler.advanceTimeBy(6_001L); dispatcher.scheduler.runCurrent()
            assertEquals(1, outputRepairs)
            assertEquals(0, sourceRepairs)
            watchdog.update()
            dispatcher.scheduler.advanceTimeBy(6_001L); dispatcher.scheduler.runCurrent()
            assertEquals(1, sourceRepairs)
            watchdog.update()
            dispatcher.scheduler.advanceTimeBy(20_000L); dispatcher.scheduler.runCurrent()
            assertEquals("The same transport cannot retry indefinitely", 1, sourceRepairs)
            assertEquals(1, native.mediaItemCount)
            assertEquals(0L, native.currentPosition)
        } finally { watchdog.cancel(); native.release() }
    }
}
