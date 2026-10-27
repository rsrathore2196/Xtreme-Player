package com.example

import android.app.Application
import com.example.data.remote.*
import com.example.playback.AudioQuality
import com.example.playback.PlaybackDiagnostics
import com.example.service.SabrSessionRegistry
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.extraction.*
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import com.metrolist.innertubex.sabr.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant
import com.metrolist.innertubex.extraction.AudioQuality as NativeQuality

@OptIn(ExperimentalSabrApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class YouTubeNativeExtractionTest {
    private val video = "abcdefghijk"
    private val visitor = "fixture-visitor"
    private val url = "https://rr.googlevideo.com/videoplayback?itag=139"
    private val probe: suspend (String, Map<String, String>, Boolean, Int, String) -> YouTubeAudioStreamService.AudioProbe? =
        { address, _, _, _, codec -> YouTubeAudioStreamService.AudioProbe(address, codec = codec) }

    @Test fun realNativePlayerResponseAndTokenBindingProducePlayableCappedAudioAtEveryQuality() = runBlocking {
        for ((cap, quality) in listOf(96_000 to AudioQuality.MEDIUM_96, 160_000 to AudioQuality.HIGH_160,
            320_000 to AudioQuality.ULTRA_HD_320, 320_000 to AudioQuality.HI_RES_LOSSLESS)) {
            val minted = mutableListOf<String>()
            val provider = YouTubePoTokenProvider({ object : YouTubeTokenBrowser {
                override var alive = true
                override val expiresAtMs = Long.MAX_VALUE
                override suspend fun mint(identifier: String): String {
                    minted += identifier
                    return "fixture-$identifier"
                }
                override suspend fun close() { alive = false }
            } }, { true })
            var playerRequests = 0
            val response = JSONObject().put("playabilityStatus", JSONObject().put("status", "OK"))
                .put("videoDetails", JSONObject().put("videoId", video).put("lengthSeconds", "180"))
                .put("streamingData", JSONObject().put("expiresInSeconds", 3600).put("adaptiveFormats", JSONArray().apply {
                    for ((itag, bitrate, codec) in listOf(Triple(139, 48_000, "mp4a.40.2"), Triple(140, 128_000, "mp4a.40.2"), Triple(251, 146_000, "opus")))
                        put(JSONObject().put("itag", itag).put("bitrate", bitrate).put("contentLength", "1234567")
                            .put("url", "$url&representation=$itag")
                            .put("mimeType", if (codec == "opus") "audio/webm; codecs=\"opus\"" else "audio/mp4; codecs=\"$codec\"")
                            .put("audioSampleRate", "44100").put("audioChannels", 2).put("approxDurationMs", "180000"))
                }))
            val network = OkHttpClient.Builder().addInterceptor(YouTubeAudioFormatPolicy(cap)).addInterceptor { chain ->
                check(chain.request().url.encodedPath == "/youtubei/v1/player") { "Unexpected fixture request" }
                playerRequests++
                Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                    .body(response.toString().toResponseBody()).build()
            }.build()
            val client = HttpClient(OkHttp) {
                engine { preconfigured = network }
                install(ContentNegotiation) { json(YouTubeAudioStreamService.playerJson) }
            }
            val inner = InnerTube(client).apply { visitorData = visitor }
            val parser = object : YtConfigParser {
                override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                    PlayerConfig("https://www.youtube.com/s/player/fixture/base.js", 12_345, visitor, "fixture")
            }
            val cipher = object : ExtractionCipherService {
                override suspend fun initialize() = Unit
                override suspend fun preloadPlayerCode(playerUrl: String) = Unit
                override suspend fun prewarmEjs() = Unit
                override suspend fun processFormats(playerUrl: String, formats: List<Format>) = formats
            }
            val native = InnerTubeExtractor(parser, cipher, inner, tokenProvider = provider,
                logger = YouTubeAudioStreamService.nativeLogger)
            val selected = object : StreamExtractor {
                override suspend fun prewarm() = Unit
                override suspend fun extract(videoId: String, hints: ContentHints, excludedClients: Set<String>,
                    audioQuality: NativeQuality, clientPlaybackNonce: String): ExtractedStream? =
                    native.extract(videoId, hints.copy(playbackClientOverrideId = "WEB_REMIX"), excludedClients,
                        audioQuality, clientPlaybackNonce)
            }
            try {
                // No stream cache is seeded: typed player HTTP serialization, response
                // parsing, native selection, tokens and production validation all run.
                val stream = YouTubeAudioStreamService.extractPlayable(video, quality, cap, null, false, emptySet(), selected, probe = probe)
                assertEquals(video, stream.videoId)
                assertTrue(stream.bitrateKbps in 1..cap / 1000)
                assertEquals(if (cap == 96_000) 48 else 146, stream.bitrateKbps)
                assertEquals(if (cap == 96_000) "AAC" else "Opus", stream.codec)
                assertEquals(cap, stream.selectionCapBps)
                assertFalse(stream.isHls)
                assertFalse(stream.isSabr)
                assertTrue(playerRequests > 0)
                assertTrue(minted.contains(visitor))
                assertTrue(minted.contains(video))
                assertTrue("GVS must carry the VIDEO-bound token", stream.url.contains("fixture-$video"))
            } finally { provider.close(); inner.close(); client.close() }
        }
    }

    private fun extracted() = ExtractedStream(video, url, emptyMap(), null, Instant.fromEpochMilliseconds(System.currentTimeMillis() + 120_000),
        1_234_567, 139, "audio/mp4", "mp4a.40.2", 48_000, 44100, "WEB_REMIX", "WEB_REMIX", true, 512_000)
    private fun extractor(block: suspend (ContentHints, Set<String>) -> ExtractedStream?) = object : StreamExtractor {
        override suspend fun prewarm() = Unit
        override suspend fun extract(videoId: String, hints: ContentHints, excludedClients: Set<String>, audioQuality: NativeQuality,
            clientPlaybackNonce: String) = block(hints, excludedClients)
    }

    @Test fun coldExtractionIsNotCancelledByTheOldFourteenSecondTransportBudget() = runTest {
        val native = extractor { hints, _ ->
            assertTrue(hints.allowHls && hints.allowSabr && hints.allowBoundedRange)
            delay(30_000)
            extracted()
        }
        val stream = withTimeout(YouTubeAudioStreamService.RESOLUTION_TIMEOUT_MS) {
            YouTubeAudioStreamService.extractPlayable(video, AudioQuality.MEDIUM_96, 96_000, null, false, emptySet(), native, probe = probe)
        }
        assertEquals(48, stream.bitrateKbps)
    }

    @Test fun hlsOnlyDoesNotAcceptDirectOrSabrAndNativeFailuresRetainTheirCause() = runTest {
        var calls = 0
        val native = extractor { hints, excluded ->
            calls++
            assertFalse(hints.allowSabr)
            assertTrue(hints.allowHls)
            if (calls > 1) assertTrue("WEB_REMIX" in excluded)
            extracted()
        }
        try {
            YouTubeAudioStreamService.extractPlayable(video, AudioQuality.MEDIUM_96, 96_000, true, false, emptySet(), native, probe = probe)
            fail("A direct stream cannot satisfy an HLS-only loader")
        } catch (error: PlaybackDiagnostics.Failure) {
            assertEquals(PlaybackDiagnostics.Reason.UNSUPPORTED_TRANSPORT, error.reason)
            assertNotNull(error.cause)
        }
        assertEquals("The extractor must not probe the identical rejected representation again", 2, calls)
    }

    @Test fun hlsAndSabrUseTheirExistingAudioOnlyMedia3Handoff() = runTest {
        val hls = extracted().copy(audioUrl = "https://rr.googlevideo.com/manifest/audio.m3u8", mimeType = "application/x-mpegURL")
        val playlist = YouTubeAudioStreamService.extractPlayable(video, AudioQuality.MEDIUM_96, 96_000, true, false,
            emptySet(), extractor { _, _ -> hls }, probe = { address, _, isHls, _, _ ->
                assertTrue(isHls)
                YouTubeAudioStreamService.AudioProbe(address, 48_000, "AAC")
            })
        assertTrue(playlist.isHls)
        val bootstrap = SabrBootstrap(video, "https://rr.googlevideo.com/videoplayback", byteArrayOf(), 1, "fixture",
            SabrFormatId(139), SabrFormatId(0), 0, durationMs = 180_000, contentLengthBytes = 1_234_567, mimeType = "audio/mp4")
        try {
            val sabr = YouTubeAudioStreamService.extractPlayable(video, AudioQuality.MEDIUM_96, 96_000, null, false,
                emptySet(), extractor { _, _ -> extracted().copy(sabrBootstrap = bootstrap) }, probe = { _, _, _, _, _ -> error("SABR has its own loader") })
            assertTrue(sabr.isSabr)
            assertTrue(sabr.url.startsWith("xtreme-sabr://audio/$video/"))
            // Mirror the AUTO resolver's cache key before Media3 pins SABR.
            // The loader must adopt this bootstrap without invoking a second extractor.
            @Suppress("UNCHECKED_CAST")
            val cache = YouTubeAudioStreamService::class.java.getDeclaredField("cache").apply { isAccessible = true }
                .get(null) as android.util.LruCache<String, YouTubeAudioStreamService.AudioStream>
            cache.evictAll()
            try {
                cache.put("$video:96000:null", sabr)
                val loaded = withContext(Dispatchers.IO) { withTimeout(1_000) {
                    YouTubeAudioStreamService.resolveSabr(video, AudioQuality.MEDIUM_96, maxBitrateBps = 96_000)
                } }
                assertSame(sabr, loaded)
                assertSame(sabr, cache.get("$video:96000:sabr"))
                cache.put("$video:96000:sabr", sabr.copy(expiresAt = 0L))
                assertSame(sabr, YouTubeAudioStreamService.resolveSabr(video, AudioQuality.MEDIUM_96, maxBitrateBps = 96_000))
            } finally { cache.evictAll() }
        } finally { SabrSessionRegistry.releaseAll() }
    }

    @Test fun obsoleteRequestsAreCancelledWithoutClientRejectionOrRetry() = runTest {
        var calls = 0
        val lookup = async { YouTubeAudioStreamService.extractPlayable(video, AudioQuality.MEDIUM_96, 96_000, null, false,
            emptySet(), extractor { _, _ -> calls++; awaitCancellation() }, probe = probe) }
        testScheduler.runCurrent()
        lookup.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test fun nativeDiagnosticsDoNotRetainTokensUrlsOrRawReasons() {
        YouTubeAudioStreamService.nativeLogger.log(InnerTubeLogEvent(InnerTubeLogLevel.ERROR, "tag",
            "player request failed", video, mapOf("client" to "WEB_REMIX", "httpStatus" to "403",
                "reason" to "https://secret.example/?token=private", "cookie" to "private")))
        val event = PlaybackDiagnostics.recentYouTube(video).last()
        assertEquals("PLAYER_REQUEST_FAILED", event.code)
        assertEquals(403, event.httpStatus)
        assertFalse(event.toString().contains("secret"))
        assertFalse(event.toString().contains("private"))
    }
}
