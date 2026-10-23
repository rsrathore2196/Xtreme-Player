package com.example

import android.app.Application
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.AdtsExtractor
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.AudioQuality
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourcePolicy
import com.example.service.YouTubeMediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(com.metrolist.innertubex.sabr.ExperimentalSabrApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubeAudioLoadingTest {
    @Suppress("UNCHECKED_CAST")
    private val cache get() = YouTubeAudioStreamService::class.java.getDeclaredField("cache")
        .apply { isAccessible = true }.get(null) as LruCache<String, YouTubeAudioStreamService.AudioStream>
    @After fun cleanup() {
        cache.evictAll()
        (YouTubeAudioStreamService::class.java.getDeclaredField("rejected").apply { isAccessible = true }.get(null) as LruCache<*, *>).evictAll()
    }

    @Suppress("UNCHECKED_CAST")
    private fun rejectedClients() = YouTubeAudioStreamService::class.java.getDeclaredMethod("rejectedClients", String::class.java)
        .apply { isAccessible = true }.invoke(YouTubeAudioStreamService, "abcdefghijk") as Set<String>

    @Test fun qualityRenewalDoesNotBlacklistTheWorkingClientOrClearARealCdnRefusal() = runBlocking {
        val url = "https://rr.googlevideo.com/videoplayback?itag=251"
        val original = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180_000, "cover",
            "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000&itag=251", bitrateKbps = 146)
        val stream = YouTubeAudioStreamService.AudioStream(url, 146, false, "old", System.currentTimeMillis() + 60_000,
            emptyMap(), "Opus", 251, null, "abcdefghijk", false, 512_000, "native", selectionCapBps = 320_000)
        cache.put("abcdefghijk:320000:false", stream)
        val resolver = StreamResolver(resolveYouTube = { requested, _, _, cap -> requested.copy(
            audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=$cap&itag=139", bitrateKbps = 48, qualityBadge = "AAC • 48 kbps") })
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER, sources = PlaybackSource.newInstallDefaults)
        val low = resolver.resolve(original, AudioQuality.MEDIUM_96, forceRefresh = true, failedUrl = original.audioUrl, sourcePolicy = policy)
        assertEquals(48, low.bitrateKbps)
        assertTrue("Renewing for a smaller profile must not exclude its otherwise working client", rejectedClients().isEmpty())
        YouTubeAudioStreamService.reject("abcdefghijk", url, "native")
        resolver.resolve(low, AudioQuality.MEDIUM_96, forceRefresh = true, failedUrl = low.audioUrl, sourcePolicy = policy)
        assertEquals(setOf("native"), rejectedClients())
    }

    @Test fun resolvedYoutubeAudioReachesTheMedia3ExtractorWithOptionalSourcesUnused() = runBlocking {
        // A short stereo AAC silence recording, generated locally. This verifies
        // real Media3 sample loading; it does not assert live provider availability.
        val segment = Base64.getDecoder().decode("//FQgAPf/N4CAExhdmM2MS4xOS4xMDEAQiAIwRg4//FQgAG//CEQBGCMHP/xUIABv/whEARgjBz/8VCAAb/8IRAEYIwc//FQgAG//CEQBGCMHP/xUIABv/whEARgjBz/8VCAAb/8IRAEYIwc//FQgAG//CEQBGCMHP/xUIABv/whEARgjBz/8VCAAb/8IRAEYIwc")
        val bytes = ByteArray(segment.size * 8) { segment[it % segment.size] }
        val url = "https://rr.googlevideo.com/videoplayback?itag=139"
        val stream = YouTubeAudioStreamService.AudioStream(url, 48, false, "youtube:abcdefghijk:139:${bytes.size}",
            System.currentTimeMillis() + 60_000, emptyMap(), "AAC", 139, bytes.size.toLong(), "abcdefghijk", false,
            512_000, "native", selectionCapBps = 96_000)
        cache.put("abcdefghijk:96000:false", stream)
        val source = YouTubeMediaDataSource(RuntimeEnvironment.getApplication(), DataSource.Factory { ByteArrayDataSource(bytes) })
        var format: Format? = null
        var samples = 0
        val track = object : TrackOutput by DummyTrackOutput() {
            override fun format(value: Format) { format = value }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { samples++ }
        }
        val extractor = AdtsExtractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) track else DummyTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        try {
            withContext(Dispatchers.IO) {
                val length = source.open(DataSpec(Uri.parse(url)))
                val input = DefaultExtractorInput(source, 0, length)
                assertTrue(extractor.sniff(input))
                input.resetPeekPosition()
                val seek = PositionHolder()
                while (extractor.read(input, seek) != Extractor.RESULT_END_OF_INPUT) { }
            }
            assertEquals(MimeTypes.AUDIO_AAC, format?.sampleMimeType)
            assertEquals(44_100, format?.sampleRate)
            assertEquals(2, format?.channelCount)
            assertTrue("Media3 must receive actual audio samples", samples > 0)
        } finally { source.close(); extractor.release() }
    }
}
