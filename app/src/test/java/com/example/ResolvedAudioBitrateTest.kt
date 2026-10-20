package com.example

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.example.data.remote.YouTubeAudioStreamService
import com.example.playback.StreamQualityBadge
import com.example.service.MusicService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(com.metrolist.innertubex.sabr.ExperimentalSabrApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ResolvedAudioBitrateTest {
    private val video = "abcdefghijk"
    @Suppress("UNCHECKED_CAST")
    private val cache get() = YouTubeAudioStreamService::class.java.getDeclaredField("cache")
        .apply { isAccessible = true }.get(null) as LruCache<String, YouTubeAudioStreamService.AudioStream>
    @Before fun setup() { cache.evictAll() }
    @After fun cleanup() { cache.evictAll() }

    private fun stream(rate: Int = 146, codec: String = "Opus", itag: Int = 251, length: Long = 1_234_567) =
        YouTubeAudioStreamService.AudioStream("https://rr.googlevideo.com/videoplayback?itag=$itag&length=$length",
            rate, false, "selected-$itag-$length", System.currentTimeMillis() + 60_000, emptyMap(), codec,
            itag, length, video, false, 512_000, "ANDROID", selectionCapBps = 320_000, opusOnly = codec == "Opus")
    private fun select(stream: YouTubeAudioStreamService.AudioStream): Uri {
        cache.put(stream.cacheKey, stream)
        return Uri.parse(YouTubeAudioStreamService.playbackUri("yt_$video", stream.url))
    }
    private fun quality(uri: Uri, format: Format, oldRate: Int = 320): StreamQualityBadge {
        val item = MediaItem.Builder().setMediaId("online_original").setUri(uri).setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { putInt("bitrate", oldRate) }).build()).build()
        return MusicService::class.java.getDeclaredMethod("audioInputQuality", MediaItem::class.java, Format::class.java)
            .apply { isAccessible = true }.invoke(Robolectric.buildService(MusicService::class.java).get(), item, format) as StreamQualityBadge
    }
    private val opus get() = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).setSampleRate(48000).build()

    @Test fun opusWithoutContainerBitrateUsesItsExactSelectedRepresentation() {
        val selected = select(stream())
        val result = quality(selected, opus)
        assertEquals("OPUS • 146 kbps", result.label)
        assertEquals(48000, result.sampleRateHz)
        assertTrue(result.verified)
        assertFalse(result.lossless)
        assertEquals(146, quality(selected, opus, oldRate = 900).bitrateKbps)
        assertEquals(128, quality(selected, opus.buildUpon().setAverageBitrate(128000).build()).bitrateKbps)
    }

    @Test fun anotherVideoCodecCapItagOrLengthCannotSupplyABitrate() {
        val selected = select(stream())
        for (wrong in listOf(
            selected.buildUpon().path("/lmnopqrstuv/stream.audio").build(),
            selected.buildUpon().clearQuery().appendQueryParameter("cap", "96000").appendQueryParameter("itag", "251").build(),
            selected.buildUpon().clearQuery().appendQueryParameter("cap", "320000").appendQueryParameter("itag", "250").build(),
            selected.buildUpon().clearQuery().appendQueryParameter("cap", "320000").appendQueryParameter("itag", "251")
                .appendQueryParameter("length", "9999").build())) {
            assertEquals(0, quality(wrong, opus).bitrateKbps)
        }
        assertEquals(0, quality(selected, Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()).bitrateKbps)
    }

    @Test fun ambiguousReferencesAndUnresolvedStreamsStayUnknownInsteadOfUsingStaleExtras() {
        val selected = select(stream())
        select(stream(rate = 126, length = 9_876_543))
        assertEquals(146, quality(selected, opus).bitrateKbps)
        val ambiguous = selected.buildUpon().clearQuery().appendQueryParameter("cap", "320000")
            .appendQueryParameter("itag", "251").build()
        assertEquals(0, quality(ambiguous, opus).bitrateKbps)
        cache.evictAll()
        assertEquals(0, quality(selected, opus).bitrateKbps)
        assertEquals("OPUS • 48.0 kHz", quality(selected, opus).label)
    }

    @Test fun aacReturnUsesTheJioUrlRateOnlyAfterItsCodecIsConfirmed() {
        val uri = Uri.parse("https://aac.saavncdn.com/song_320.mp4")
        val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(44100).build()
        assertEquals("AAC • 320 kbps", quality(uri, aac, oldRate = 146).label)
        assertEquals(0, quality(uri, opus).bitrateKbps)
        assertEquals(0, quality(Uri.parse("https://other.example/song_320.mp4"), aac).bitrateKbps)
        assertEquals(0, quality(Uri.parse("https://aac.saavncdn.com/song.mp4"), aac).bitrateKbps)
    }

    @Test fun sabrUsesOnlyTheExactRepresentationAndHlsDoesNotGuessTheActiveRendition() {
        val uri = "xtreme-sabr://audio/$video/${"a".repeat(64)}/stream.audio?cap=320"
        val bootstrap = com.metrolist.innertubex.sabr.SabrBootstrap(videoId = video,
            serverAbrStreamingUrl = "https://rr.googlevideo.com/videoplayback", videoPlaybackUstreamerConfig = byteArrayOf(),
            clientName = 3, clientVersion = "test", audioFormat = com.metrolist.innertubex.sabr.SabrFormatId(251),
            discardVideoFormat = com.metrolist.innertubex.sabr.SabrFormatId(0), discardVideoHeight = 0, durationMs = 180_000,
            contentLengthBytes = 1_234_567, mimeType = "audio/webm; codecs=opus")
        cache.put("sabr", stream().copy(url = uri, cacheKey = "youtube-sabr:$video:${"a".repeat(64)}", sabrBootstrap = bootstrap))
        val pinned = Uri.parse(YouTubeAudioStreamService.playbackUri("yt_$video", uri, opusOnly = true))
        assertEquals(146, quality(pinned, opus).bitrateKbps)
        assertEquals(0, quality(Uri.parse(pinned.toString().replace("cap=320", "cap=96")), opus).bitrateKbps)
        assertEquals(0, quality(Uri.parse(uri.replace("a".repeat(64), "b".repeat(64))), opus).bitrateKbps)
        val hls = stream().copy(url = "https://rr.googlevideo.com/manifest/stream.m3u8", isHls = true)
        cache.put("hls", hls)
        assertEquals(0, quality(Uri.parse(hls.url), opus).bitrateKbps)
    }
}
