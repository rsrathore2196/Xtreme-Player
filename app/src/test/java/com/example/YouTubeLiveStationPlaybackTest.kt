package com.example

import android.app.Application
import com.example.data.remote.*
import com.example.playback.AudioQuality
import com.example.playback.PlaybackDiagnostics
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.*
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.metrolist.innertubex.extraction.AudioQuality as NativeQuality

/** Native live-player extraction and the production HLS parser; only HTTP bytes are fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class YouTubeLiveStationPlaybackTest {
    private val video = "abcdefghijk"
    private val master = "https://manifest.googlevideo.com/api/manifest/hls_variant/station.m3u8"
    private val low = "https://rr.googlevideo.com/manifest/audio-low.m3u8"
    private val medium = "https://rr.googlevideo.com/manifest/audio-medium.m3u8"
    private val videoPlaylist = "https://rr.googlevideo.com/manifest/video.m3u8"
    private fun variant(url: String, rate: Int, video: Boolean = false) =
        "#EXT-X-STREAM-INF:BANDWIDTH=$rate,CODECS=\"${if (video) "avc1.42001e,mp4a.40.2" else "mp4a.40.2"}\"${if (video) ",RESOLUTION=1280x720" else ""}\n$url\n"
    private fun masterBody() = "#EXTM3U\n" + variant(low, 48_000) + variant(medium, 128_000) + variant(videoPlaylist, 2_000_000, true)
    private val liveMedia = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:54321\n#EXTINF:4.0,\nsegment.ts\n"
    private fun extracted() = ExtractedStream(videoId = video, audioUrl = master, videoUrl = master,
        headers = emptyMap(), loudnessDb = null, expiresAt = null, contentLengthBytes = null,
        itag = 96, mimeType = "application/x-mpegURL", codecs = null, bitrate = null, sampleRate = null,
        clientName = "WEB_REMIX", profileId = "WEB_REMIX", requireBoundedRange = false, rangeChunkSizeBytes = 512_000)
    private fun extractor(value: ExtractedStream = extracted()) = object : StreamExtractor {
        override suspend fun prewarm() = Unit
        override suspend fun extract(videoId: String, hints: ContentHints, excludedClients: Set<String>,
            audioQuality: NativeQuality, clientPlaybackNonce: String) = value
    }
    private fun fetch(requests: MutableList<String>, body: String = masterBody()): suspend (String, Map<String, String>, Boolean) -> YouTubeAudioStreamService.MediaProbe? =
        { address, _, manifest ->
            assertTrue("Station verification reads playlists, not video segments", manifest)
            requests.add(address.substringBefore('?'))
            val content = when (address.substringBefore('?')) {
                master -> body
                low, medium -> liveMedia
                else -> error("Unexpected/video media request: $address")
            }
            YouTubeAudioStreamService.MediaProbe(address, content.toByteArray(), "application/vnd.apple.mpegurl")
        }

    @Test fun realNativeLiveResponsePlaysOnlyItsAudioRenditionAtEveryQuality() = runBlocking {
        for ((quality, cap) in listOf(AudioQuality.MEDIUM_96 to 96_000, AudioQuality.HIGH_160 to 160_000,
                AudioQuality.ULTRA_HD_320 to 320_000, AudioQuality.HI_RES_LOSSLESS to 320_000)) {
            val response = JSONObject().put("playabilityStatus", JSONObject().put("status", "OK"))
                .put("videoDetails", JSONObject().put("videoId", video).put("lengthSeconds", "0").put("isLiveContent", true))
                .put("streamingData", JSONObject().put("hlsManifestUrl", master))
            val network = OkHttpClient.Builder().addInterceptor(YouTubeAudioFormatPolicy(cap)).addInterceptor { chain ->
                check(chain.request().url.encodedPath == "/youtubei/v1/player")
                Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                    .body(response.toString().toResponseBody()).build()
            }.build()
            val client = HttpClient(OkHttp) {
                engine { preconfigured = network }
                install(ContentNegotiation) { json(YouTubeAudioStreamService.playerJson) }
            }
            val inner = InnerTube(client).apply { visitorData = "fixture-visitor" }
            val provider = YouTubePoTokenProvider({ object : YouTubeTokenBrowser {
                override var alive = true
                override val expiresAtMs = Long.MAX_VALUE
                override suspend fun mint(identifier: String) = "fixture-$identifier"
                override suspend fun close() { alive = false }
            } }, { true })
            val parser = object : YtConfigParser {
                override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                    PlayerConfig("https://www.youtube.com/s/player/fixture/base.js", 12_345, "fixture-visitor", "fixture")
            }
            val cipher = object : ExtractionCipherService {
                override suspend fun initialize() = Unit
                override suspend fun preloadPlayerCode(playerUrl: String) = Unit
                override suspend fun prewarmEjs() = Unit
                override suspend fun processFormats(playerUrl: String, formats: List<Format>) = formats
            }
            val native = InnerTubeExtractor(parser, cipher, inner, tokenProvider = provider, logger = YouTubeAudioStreamService.nativeLogger)
            val selected = object : StreamExtractor {
                override suspend fun prewarm() = Unit
                override suspend fun extract(videoId: String, hints: ContentHints, excludedClients: Set<String>,
                    audioQuality: NativeQuality, clientPlaybackNonce: String): ExtractedStream? =
                    native.extract(videoId, hints.copy(playbackClientOverrideId = "WEB_REMIX"), excludedClients, audioQuality, clientPlaybackNonce)
                        ?.also {
                            assertEquals("The native live extractor shares one manifest across both fields", it.audioUrl, it.videoUrl)
                            assertTrue(it.mediaMetadata?.isLive == true)
                        }
            }
            val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
            try {
                val stream = YouTubeAudioStreamService.extractPlayable(video, quality, cap, null, false, emptySet(), selected,
                    probe = { url, headers, hls, ceiling, codec ->
                        assertTrue(hls)
                        YouTubeAudioStreamService.probeAudio(url, headers, hls, ceiling, codec, fetch(requests))
                    })
                assertEquals(video, stream.videoId)
                assertTrue(stream.isHls)
                assertEquals(if (cap == 96_000) low else medium, stream.url)
                assertEquals(if (cap == 96_000) 48 else 128, stream.bitrateKbps)
                assertEquals("AAC", stream.codec)
                assertEquals(listOf(master, stream.url), requests)
            } finally { provider.close(); inner.close(); client.close() }
        }
    }

    @Test fun aVideoOnlyStationMasterCannotReachPlayback() = runTest {
        val requests = mutableListOf<String>()
        try {
            YouTubeAudioStreamService.extractPlayable(video, AudioQuality.ULTRA_HD_320, 320_000, null, false,
                emptySet(), extractor(), probe = { url, headers, hls, cap, codec ->
                    YouTubeAudioStreamService.probeAudio(url, headers, hls, cap, codec,
                        fetch(requests, "#EXTM3U\n" + variant(videoPlaylist, 2_000_000, true)))
                })
            fail("A live master without audio-only renditions is not playable audio")
        } catch (_: PlaybackDiagnostics.Failure) { }
        assertEquals(listOf(master), requests)
    }

    @Test fun liveStationsCannotBypassTheSelectedBitrateCeiling() = runTest {
        val requests = mutableListOf<String>()
        try {
            YouTubeAudioStreamService.extractPlayable(video, AudioQuality.MEDIUM_96, 96_000, null, false,
                emptySet(), extractor(), probe = { url, headers, hls, cap, codec ->
                    YouTubeAudioStreamService.probeAudio(url, headers, hls, cap, codec,
                        fetch(requests, "#EXTM3U\n" + variant(medium, 128_000)))
                })
            fail("An over-cap live audio rendition must remain rejected")
        } catch (_: PlaybackDiagnostics.Failure) { }
        assertEquals(listOf(master), requests)
    }

    @Test fun separateVideoStreamsAndVideoDimensionsRemainRejectedBeforeProbing() = runTest {
        for (result in listOf(extracted().copy(videoUrl = videoPlaylist), extracted().copy(videoWidth = 1280),
                extracted().copy(videoHeight = 720), extracted().copy(audioUrl = "https://rr.googlevideo.com/videoplayback", mimeType = "audio/mp4"))) {
            var probes = 0
            try {
                YouTubeAudioStreamService.extractPlayable(video, AudioQuality.ULTRA_HD_320, 320_000, null, false,
                    emptySet(), extractor(result), probe = { _, _, _, _, _ -> probes++; null })
                fail("A separate video stream must not be accepted as station audio")
            } catch (error: PlaybackDiagnostics.Failure) {
                assertEquals(PlaybackDiagnostics.Reason.UNSUPPORTED_TRANSPORT, error.reason)
            }
            assertEquals(0, probes)
        }
    }

    @Test fun changingTheSelectedStationCancelsManifestPreparation() = runTest {
        val entered = CompletableDeferred<Unit>()
        var probes = 0
        val request = launch {
            YouTubeAudioStreamService.extractPlayable(video, AudioQuality.ULTRA_HD_320, 320_000, null, false,
                emptySet(), extractor(), probe = { url, headers, hls, cap, codec ->
                    YouTubeAudioStreamService.probeAudio(url, headers, hls, cap, codec) { _, _, _ ->
                        probes++; entered.complete(Unit); awaitCancellation()
                    }
                })
        }
        entered.await()
        request.cancelAndJoin()
        assertEquals(1, probes)
    }
}
