package com.example

import android.app.Application
import android.net.Uri
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioFormatPolicy
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.repository.StreamResolver
import com.example.playback.*
import com.metrolist.innertubex.extraction.AudioQuality as NativeQuality
import com.metrolist.innertubex.extraction.selectBestAudioFormat
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubeStreamingPolicyTest {
    private fun audio(itag: Int, rate: Int, opus: Boolean = false, track: String? = null, default: Boolean = false) =
        JSONObject().put("itag", itag).put("bitrate", rate)
            .put("url", "https://rr.googlevideo.com/videoplayback?itag=$itag")
            .put("mimeType", if (opus) "audio/webm; codecs=\"opus\"" else "audio/mp4; codecs=\"mp4a.40.2\"")
            .apply { if (track != null) put("audioTrack", JSONObject().put("id", track).put("audioIsDefault", default)) }

    private fun filter(cap: Int, vararg audio: JSONObject): List<Format> {
        val body = JSONObject().put("streamingData", JSONObject().put("adaptiveFormats", JSONArray(audio.toList())))
        // Execute the production response interceptor and native format selector;
        // the terminal interceptor supplies a player response without external HTTP.
        val client = OkHttpClient.Builder().addInterceptor(YouTubeAudioFormatPolicy(cap)).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody()).build()
        }.build()
        return client.newCall(Request.Builder().url("https://music.youtube.com/youtubei/v1/player").build()).execute().use {
            val entries = JSONObject(it.body.string()).getJSONObject("streamingData").getJSONArray("adaptiveFormats")
            val json = Json { ignoreUnknownKeys = true }
            (0 until entries.length()).map { index -> json.decodeFromString<Format>(entries.getJSONObject(index).toString()) }
        }
    }

    @Test fun nativePlayerRequestsRetainDefaultContextFieldsAndOmitNulls() {
        val context = com.metrolist.innertubex.models.Context(
            client = com.metrolist.innertubex.models.Context.Client("ANDROID", "test", gl = "US", hl = "en", visitorData = null))
        val body = JSONObject(YouTubeAudioStreamService.playerJson.encodeToString(context))
        assertTrue(body.getJSONObject("request").getBoolean("useSsl"))
        assertFalse(body.getJSONObject("user").getBoolean("lockedSafetyMode"))
        assertEquals("ANDROID", body.getJSONObject("client").getString("clientName"))
        assertFalse(body.getJSONObject("client").has("visitorData"))
        assertFalse(body.has("thirdParty"))
    }

    @Test fun dataSaverSelectsAnActualLowerBitrateRepresentationOnEveryNativeAttempt() {
        val formats = filter(96_000, audio(139, 48_000), audio(140, 128_000), audio(250, 80_000, opus = true), audio(251, 146_000, opus = true))
        assertEquals(setOf(139, 250), formats.map { it.itag }.toSet())
        repeat(3) { attempt ->
            val quality = YouTubeAudioFormatPolicy.quality(96_000, attempt)
            assertEquals(NativeQuality.LOW, quality)
            val selected = requireNotNull(selectBestAudioFormat(formats, quality))
            assertEquals(139, selected.itag)
            assertEquals(48_000, selected.bitrate)
        }
        val high = filter(320_000, audio(139, 48_000), audio(251, 146_000, opus = true))
        assertEquals(251, selectBestAudioFormat(high, YouTubeAudioFormatPolicy.quality(320_000, 0))?.itag)
    }

    @Test fun originalVocalsAreNeverReplacedByAFittingDub() {
        assertTrue(filter(96_000, audio(140, 128_000, track = "original", default = true),
            audio(139, 48_000, track = "dub")).isEmpty())
        val allowed = filter(96_000, audio(139, 48_000, track = "original", default = true),
            audio(250, 80_000, opus = true, track = "dub"))
        assertEquals(listOf(139), allowed.map { it.itag })
        val missingId = audio(140, 128_000).put("audioTrack", JSONObject().put("displayName", "Original").put("audioIsDefault", true))
        assertTrue(filter(96_000, missingId, audio(139, 48_000, track = "dub")).isEmpty())
    }

    @Test fun noOverCapOrUnknownRateFallbackCanBypassDataSaver() {
        assertTrue(filter(96_000, audio(140, 128_000), audio(251, 146_000, opus = true), audio(139, 0)).isEmpty())
        assertFalse(YouTubeAudioFormatPolicy.acceptsBitrate(null, 96_000))
        assertFalse(YouTubeAudioFormatPolicy.acceptsBitrate(97_000, 96_000))
        assertTrue(YouTubeAudioFormatPolicy.acceptsBitrate(48_000, 96_000))
        assertTrue(YouTubeAudioFormatPolicy.acceptsBitrate(null, 320_000))
    }

    @Test fun sabrAndDirectReferenceCapsUseTheSameUnitsAndRejectMalformedValues() {
        val direct = Uri.parse("xtreme-youtube://audio/abcdefghijk/stream.audio?cap=96000")
        val sabr = Uri.parse("xtreme-sabr://audio/abcdefghijk/stream.audio?cap=96")
        assertEquals(96_000, YouTubeAudioStreamService.bitrateCapFromUri(direct))
        assertEquals(96_000, YouTubeAudioStreamService.bitrateCapFromUri(sabr))
        for (value in listOf("0", "-96", "2147483647", "9223372036854775807", "unknown")) {
            assertNull(YouTubeAudioStreamService.bitrateCapFromUri(Uri.parse(sabr.toString().replace("cap=96", "cap=$value"))))
        }
        val track = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180_000, "cover", "", bitrateKbps = 0)
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER, sources = PlaybackSource.newInstallDefaults)
        assertTrue(policy.acceptsRemoteTrack(track.copy(audioUrl = sabr.toString())))
        assertFalse(policy.acceptsRemoteTrack(track.copy(audioUrl = sabr.toString().replace("cap=96", "cap=320"))))
    }

    @Test fun singleNativeExtractionCanFinishBeyondTheOldOuterTimeoutWithoutSkippingTheSelectedSong() = runTest {
        val original = MusicTrack("yt_abcdefghijk", "Selected Song", "Artist", "Album", 180_000, "cover", "", source = "YouTube Music")
        var extractions = 0
        val resolver = StreamResolver(ioDispatcher = StandardTestDispatcher(testScheduler),
            searchYouTube = { error("Already have the exact video ID") },
            searchStandard = { _, _ -> error("Optional sources are off") },
            searchLossless = { error("Optional sources are off") },
            resolveYouTube = { requested, _, _, cap ->
                extractions++
                delay(30_000) // Virtual time: a cold native fallback must have its full budget.
                requested.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=$cap&itag=139",
                    bitrateKbps = 48, qualityBadge = "AAC • 48 kbps")
            })
        val result = resolver.resolve(original, AudioQuality.MEDIUM_96,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER, sources = PlaybackSource.newInstallDefaults))
        assertEquals(1, extractions)
        assertEquals(original.id, result.id)
        assertEquals("YouTube Music", result.source)
        assertEquals(48, result.bitrateKbps)
        assertTrue(result.audioUrl.contains("cap=96000"))
    }
}
