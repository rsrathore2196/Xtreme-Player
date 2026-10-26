package com.example

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.example.playback.canRetryAudioOutput
import com.example.playback.keepResolvedSource
import com.example.playback.restartAudioOutput
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class AudioOutputRecoveryTest {
    @Test fun transientOutputRestartRetainsTheSourceQueuePositionAndPausedIntent() {
        val queue = listOf(MediaItem.fromUri("https://audio/one"), MediaItem.fromUri("https://audio/two"))
        for (resume in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
                when (method.name) {
                    "getCurrentMediaItemIndex" -> 1
                    "getCurrentPosition" -> 42_000L
                    "getPlayWhenReady" -> resume
                    "stop", "prepare" -> { calls += method.name; null }
                    "seekTo" -> { assertEquals(1, args!![0]); assertEquals(42_000L, args[1]); calls += "seek"; null }
                    "setPlayWhenReady" -> { assertEquals(resume, args!![0]); calls += "resume"; null }
                    else -> error("Output restart must not change the queue/source/settings: ${method.name}")
                }
            } as Player
            restartAudioOutput(player)
            assertEquals(listOf("stop", "seek", "prepare", "resume"), calls)
            assertEquals("https://audio/two", queue[1].localConfiguration!!.uri.toString())
        }
    }

    @Test fun runtimeInvariantsAndDecoderErrorsDoNotRetryAsTransientOutputFailures() {
        assertTrue(PlaybackException("init", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED).canRetryAudioOutput())
        assertTrue(PlaybackException("write", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED).canRetryAudioOutput())
        assertFalse(PlaybackException("runtime", null, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK).canRetryAudioOutput())
        assertFalse(PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED).canRetryAudioOutput())
    }

    @Test fun renewedStableVirtualUriMustBePreparedAfterAnError() {
        val uri = "xtreme-youtube://audio/abcdefghijk/stream.audio?itag=251&length=1000"
        assertTrue(keepResolvedSource(uri, uri, null))
        assertFalse("A renewed signed URL can retain the same virtual URI", keepResolvedSource(uri, uri, uri))
    }
}
