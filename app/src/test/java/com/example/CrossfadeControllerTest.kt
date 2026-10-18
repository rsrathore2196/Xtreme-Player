package com.example

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.example.playback.CrossfadeController
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CrossfadeControllerTest {
    private class Audio(var items: List<MediaItem>) {
        val listeners = mutableListOf<Player.Listener>()
        var index = 0
        var position = 53_000L
        var duration = 60_000L
        var bufferedAhead = 5_000L
        var state = Player.STATE_READY
        var wanted = true
        var volume = 0.8f
        var repeat = Player.REPEAT_MODE_OFF
        var live = false
        var error: PlaybackException? = null
        var releaseCount = 0
        var seekCount = 0
        var queueRebuilds = 0
        var bufferOnSeek = false
        val playing get() = wanted && state == Player.STATE_READY && error == null
        fun event() {
            listeners.toList().forEach { it.onEvents(player, Player.Events(FlagSet.Builder().build())) }
        }
        fun advance(ms: Long) {
            if (playing) position = (position + ms).coerceAtMost(duration)
            if (position == duration) state = Player.STATE_ENDED
        }
        fun pause() { wanted = false; event() }
        fun play() { wanted = true; event() }
        fun seek(index: Int, position: Long) {
            this.index = index; this.position = position; seekCount++
            if (bufferOnSeek) state = Player.STATE_BUFFERING
            event()
        }
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex" -> index
                "getNextMediaItemIndex" -> if (index + 1 < items.size) index + 1 else C.INDEX_UNSET
                "getMediaItemAt" -> items[args!![0] as Int]
                "getMediaItemCount" -> items.size
                "getCurrentPosition" -> position
                "getDuration" -> duration
                "getTotalBufferedDuration" -> duration - position + bufferedAhead
                "getPlaybackState" -> state
                "getPlayWhenReady" -> wanted
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "getPlayerError" -> error
                "isPlaying" -> playing
                "isCurrentMediaItemLive" -> live
                "isCurrentMediaItemDynamic" -> false
                "getRepeatMode" -> repeat
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; listeners.toList().forEach { it.onVolumeChanged(volume) }; event(); null }
                "setRepeatMode" -> { repeat = args!![0] as Int; null }
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "setMediaItem" -> { items = listOf(args!![0] as MediaItem); index = 0; position = args[1] as Long; null }
                "setMediaItems" -> { queueRebuilds++; null }
                "prepare" -> { state = Player.STATE_READY; null }
                "play" -> { play(); null }
                "pause" -> { pause(); null }
                "seekTo" -> { if (args!!.size == 2) seek(args[0] as Int, args[1] as Long) else position = args[0] as Long; null }
                "release" -> { releaseCount++; wanted = false; null }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Audio($index@$position)"
                else -> null
            }
        } as Player
    }
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://audio.example/$id.m4a").build()
    private inner class Harness(private val scope: TestScope, var mixAllowed: Boolean = true) : AutoCloseable {
        val primary = Audio(listOf(item("first"), item("second"), item("third")))
        val tails = mutableListOf<Audio>()
        val controller = CrossfadeController(primary.player, scope,
            createTail = { Audio(emptyList()).also { tails.add(it) }.player },
            canMix = { mixAllowed }, now = { scope.testScheduler.currentTime })
        fun advance(ms: Long) {
            repeat((ms / 20).toInt()) {
                primary.advance(20)
                tails.filter { it.releaseCount == 0 }.forEach { it.advance(20) }
                scope.advanceTimeBy(20); scope.runCurrent()
            }
        }
        fun start() { controller.setEnabled(true); scope.runCurrent() }
        override fun close() { controller.close(); scope.runCurrent() }
    }

    @Test fun disabledByDefaultCreatesNoTailAndDoesNotChangePlayback() = runTest {
        Harness(this).use { h ->
            h.advance(5_000)
            assertFalse(h.controller.enabled)
            assertTrue(h.tails.isEmpty())
            assertEquals(0, h.primary.seekCount)
            assertEquals(0.8f, h.primary.volume, 0f)
        }
    }
    @Test fun actualOverlapKeepsTheOriginalSessionQueueAndContinuesTheNextSong() = runTest {
        Harness(this).use { h ->
            h.start()
            assertEquals(1, h.tails.size)
            assertEquals(h.primary.items[0], h.tails.single().items.single())
            assertEquals(53_000L, h.tails.single().position)
            h.advance(5_500)
            assertEquals(1, h.primary.index)
            assertEquals(1, h.primary.seekCount)
            assertEquals(0, h.primary.queueRebuilds)
            assertTrue(h.primary.position in 1_000..2_000)
            assertTrue(h.primary.volume > 0.1f && h.primary.volume < 0.8f)
            assertTrue(h.tails.single().volume > 0.1f)
            h.advance(2_000)
            assertEquals(1, h.tails.single().releaseCount)
            assertEquals(0.8f, h.primary.volume, 0.001f)
            assertTrue("The next recording must never restart", h.primary.position > 3_000)
        }
    }
    @Test fun pauseAndDisableDuringOverlapRestoreGainAndReleaseTheTail() = runTest {
        Harness(this).use { h ->
            h.start(); h.advance(5_000)
            h.primary.pause(); runCurrent()
            val position = h.primary.position
            assertEquals(1, h.tails.single().releaseCount)
            assertEquals(0.8f, h.primary.volume, 0f)
            h.advance(1_000)
            assertEquals(position, h.primary.position)
            h.primary.play(); h.controller.setEnabled(false); runCurrent()
            assertEquals(1, h.tails.size)
            assertEquals(0.8f, h.primary.volume, 0f)
        }
    }
    @Test fun skippingDuringWarmupNeverRedirectsTheNewSelection() = runTest {
        Harness(this).use { h ->
            h.start(); h.advance(200)
            h.primary.seek(2, 8_000)
            runCurrent(); h.advance(2_000)
            assertEquals(2, h.primary.index)
            assertEquals(10_000L, h.primary.position)
            assertEquals(1, h.tails.single().releaseCount)
            assertEquals(0.8f, h.primary.volume, 0f)
        }
    }
    @Test fun insufficientIncomingBufferAndDirectOutputKeepNormalPlayback() = runTest {
        Harness(this).use { h ->
            h.primary.bufferedAhead = 0
            h.start(); h.advance(5_500)
            assertEquals(0, h.primary.seekCount)
            assertEquals(0.8f, h.primary.volume, 0f)
        }
        Harness(this, mixAllowed = false).use { h ->
            h.start(); h.advance(5_500)
            assertTrue(h.tails.isEmpty())
            assertEquals(0, h.primary.seekCount)
        }
    }
    @Test fun anIncomingStallCannotLeaveTheSessionMutedAfterTheTailEnds() = runTest {
        Harness(this).use { h ->
            h.primary.bufferOnSeek = true
            h.start(); h.advance(7_500)
            assertEquals(1, h.primary.index)
            assertEquals(1, h.tails.single().releaseCount)
            assertEquals(0.8f, h.primary.volume, 0f)
            h.primary.state = Player.STATE_READY; h.primary.event(); h.advance(500)
            assertTrue(h.primary.playing)
            assertEquals(500L, h.primary.position)
        }
    }
    @Test fun userGainAndQueueEditsDuringHandoffWinOverTheOldTransition() = runTest {
        Harness(this).use { h ->
            h.start(); h.advance(4_020)
            // The 60ms outgoing handoff has begun. Removing/replacing its target
            // must never seek to a different recording at the old index.
            h.primary.items = listOf(h.primary.items[0], h.primary.items[2])
            h.advance(100)
            assertEquals(0, h.primary.seekCount)
            assertEquals(0.8f, h.primary.volume, 0f)
            h.controller.setEnabled(false)
        }
        Harness(this).use { h ->
            h.start(); h.advance(5_000)
            h.primary.player.volume = 0.35f
            runCurrent()
            assertEquals(0.35f, h.primary.volume, 0f)
            assertEquals(1, h.tails.single().releaseCount)
        }
    }
    @Test fun aRouteChangeReleasesAudioAndPreventsRewarmingTheOldRoute() = runTest {
        Harness(this).use { h ->
            h.start(); h.advance(200)
            h.controller.outputRouteChanging()
            h.primary.event(); h.advance(5_000)
            assertEquals(1, h.tails.size)
            assertEquals(1, h.tails.single().releaseCount)
            assertEquals(0, h.primary.seekCount)
            assertEquals(0.8f, h.primary.volume, 0f)
        }
    }
    @Test fun bufferingDuringTheOutgoingHandoffRestoresGainWithoutAdvancingTheQueue() = runTest {
        for (primaryBuffers in listOf(true, false)) Harness(this).use { h ->
            h.start(); h.advance(4_020)
            if (primaryBuffers) {
                h.primary.state = Player.STATE_BUFFERING
                h.primary.event()
            } else h.tails.single().state = Player.STATE_BUFFERING
            h.advance(200)
            assertEquals(0, h.primary.seekCount)
            assertEquals(0.8f, h.primary.volume, 0f)
            assertEquals(1, h.tails.single().releaseCount)
            h.primary.state = Player.STATE_READY; h.primary.event(); h.advance(500)
            assertEquals(1, h.tails.size)
            assertTrue(h.primary.playing)
        }
    }
    @Test fun fadeEnvelopeHasBoundedConstantPowerAndCorrectEndpoints() {
        for (step in 0..100) {
            val (old, next) = CrossfadeController.gains(step / 100f)
            assertTrue(old in 0f..1f && next in 0f..1f)
            assertEquals(1f, old * old + next * next, 0.0001f)
        }
        assertEquals(1f, CrossfadeController.gains(-1f).first, 0f)
        assertEquals(1f, CrossfadeController.gains(2f).second, 0f)
    }
}
