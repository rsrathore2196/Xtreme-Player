package com.example

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.example.playback.AudioSignalPath
import com.example.service.MusicService
import java.lang.reflect.Proxy
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveSignalActivationTest {
    @After fun clear() { AudioSignalPath.reset() }
    @Test fun sourceAndBadgeRemainAacUntilTheMatchingArchiveDecoderIsReady() {
        val service = Robolectric.buildService(MusicService::class.java).get()
        val uri = Uri.parse("https://archive.org/download/album/song.flac")
        val item = MediaItem.Builder().setMediaId("online_song").setUri(uri).setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { putBoolean("lossless_upgrade_pending", true) }).build()).build()
        var state = Player.STATE_BUFFERING
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) { "getCurrentMediaItem" -> item; "getPlaybackState" -> state; else -> null }
        } as ExoPlayer
        fun set(name: String, value: Any?) = MusicService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        fun publish() = MusicService::class.java.getDeclaredMethod("publishVerifiedSignal").apply { isAccessible = true }.invoke(service)
        AudioSignalPath.source(item.mediaId, "JioSaavn")
        AudioSignalPath.format(item.mediaId, Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setAverageBitrate(320000).setSampleRate(44100).build(), 320)
        set("exoPlayer", player); set("verifiedSignalUri", uri); set("verifiedSignalId", item.mediaId)
        set("verifiedSignalFormat", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setPcmEncoding(C.ENCODING_PCM_24BIT).setSampleRate(96000).build())
        MusicService::class.java.getDeclaredMethod("publishSignalSource", MediaItem::class.java)
            .apply { isAccessible = true }.invoke(service, item)
        publish()
        assertEquals("JioSaavn", AudioSignalPath.state.value.source)
        assertEquals("AAC • 320 kbps", AudioSignalPath.state.value.quality.label)
        state = Player.STATE_READY
        publish()
        assertEquals("Internet Archive", AudioSignalPath.state.value.source)
        assertEquals("FLAC • 24-bit/96.0 kHz", AudioSignalPath.state.value.quality.label)
        set("verifiedSignalUri", Uri.parse("https://archive.org/download/other/song.flac"))
        AudioSignalPath.source(item.mediaId, "JioSaavn")
        publish()
        assertEquals("JioSaavn", AudioSignalPath.state.value.source)
    }
}
