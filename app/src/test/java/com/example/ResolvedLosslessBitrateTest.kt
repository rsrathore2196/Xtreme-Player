package com.example

import android.app.Application
import android.os.Bundle
import androidx.media3.common.*
import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.StreamingAddons
import com.example.playback.*
import com.example.service.MusicService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ResolvedLosslessBitrateTest {
    private fun quality(uri: String, format: Format): StreamQualityBadge {
        val service = Robolectric.buildService(MusicService::class.java).get()
        val item = MediaItem.Builder().setMediaId("yt_abcdefghijk").setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                putInt("bitrate", 320); putString("media_source", "JIOSAAVN")
            }).build()).build()
        return MusicService::class.java.getDeclaredMethod("audioInputQuality", MediaItem::class.java, Format::class.java)
            .apply { isAccessible = true }.invoke(service, item, format) as StreamQualityBadge
    }
    private fun flac() = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).setSampleRate(96000)
        .setPcmEncoding(C.ENCODING_PCM_24BIT).setChannelCount(2).build()

    @Test fun customBitrateUsesOnlyTheResolvedUrlAndMatchingActualCodec() {
        val source = PlaybackSource("bitrate-test", PlaybackSourceKind.ADDON, "Custom", baseUrl = "https://custom.example")
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(source) + PlaybackSource.builtIns)
        val track = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180000, "", "")
        val selected = StreamingAddons.parseStream("""{"url":"https://custom.example/exact.flac","codec":"flac","bitrate":1500000}""",
            source.baseUrl, track, track, policy, source.id)!!
        assertEquals(1500, quality(selected.audioUrl, flac()).bitrateKbps)
        assertEquals(0, quality(selected.audioUrl, Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()).bitrateKbps)
        assertEquals(0, quality("https://custom.example/other.flac", flac()).bitrateKbps)
        assertEquals(1400, quality(selected.audioUrl, flac().buildUpon().setAverageBitrate(1400000).build()).bitrateKbps)
        val adaptive = StreamingAddons.parseStream("""{"url":"https://custom.example/adaptive.mpd","format":"dash","codec":"flac","bitrate":1500000}""",
            source.baseUrl, track, track, policy, source.id)!!
        assertEquals(0, quality(adaptive.audioUrl, flac()).bitrateKbps)
        assertEquals(1400, quality(adaptive.audioUrl, flac().buildUpon().setAverageBitrate(1400000).build()).bitrateKbps)
    }

    @Test fun archiveFileMetadataSuppliesAverageRateWithoutReusingPriorProviderExtras() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Song","creator":"Artist","rights":"public domain"},
            "files":[{"name":"song.flac","format":"Flac","title":"Song","artist":"Artist","length":"180","size":"33750000"}]}""")
        val track = InternetArchiveApiService.parseLosslessItem(JSONObject("""{"identifier":"bitrate-test"}"""), root).single()
        assertEquals(1500, track.bitrateKbps)
        assertEquals(1500, quality(track.audioUrl, flac()).bitrateKbps)
        assertEquals(0, quality(track.audioUrl, Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()).bitrateKbps)
        assertEquals(0, quality("https://archive.org/download/unknown/song.flac", flac()).bitrateKbps)
    }
}
