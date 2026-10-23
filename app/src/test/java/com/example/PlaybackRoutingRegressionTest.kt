package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.repository.StreamResolver
import com.example.data.repository.TrackUnavailableException
import com.example.playback.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PlaybackRoutingRegressionTest {
    private val videoId = "abcdefghijk"
    private val reference = "xtreme-youtube://audio/$videoId/stream.audio?cap=320000&itag=251&length=123456"

    @Test fun everySupportedCatalogReferenceRetainsTheExactSelectedVideo() {
        val references = listOf(
            "https://music.youtube.com/watch?v=$videoId",
            "https://www.youtube.com/watch?v=$videoId&list=playlist",
            "https://youtu.be/$videoId?t=34",
            "https://youtube.com/shorts/$videoId",
            "https://youtube.com/embed/$videoId",
            "https://youtube.com/live/$videoId",
            reference,
            "xtreme-sabr://audio/$videoId/stream.audio?cap=96"
        )
        references.forEach { assertEquals(it, videoId, YouTubeAudioStreamService.videoIdFromReference("legacy", it)) }
        assertEquals(videoId, YouTubeAudioStreamService.videoIdFromReference("yt_$videoId", ""))
        assertNull(YouTubeAudioStreamService.videoIdFromReference("legacy", "https://youtube.com.other.example/watch?v=$videoId"))
        assertNull(YouTubeAudioStreamService.videoIdFromReference("yt_bad", "https://youtube.com/watch?v=bad"))
    }

    @Test fun catalogIdentityDoesNotMisidentifyAnotherProvidersActiveTransport() {
        assertTrue(YouTubeAudioStreamService.isYouTubeAudio("legacy", "https://music.youtube.com/watch?v=$videoId"))
        assertFalse(YouTubeAudioStreamService.isYouTubeAudio("yt_$videoId", "https://aac.saavncdn.com/song_320.mp4"))
        assertFalse(YouTubeAudioStreamService.isYouTubeAudio("yt_$videoId", "https://archive.org/download/album/song.flac"))
    }

    @Test fun qualityCeilingChangesAvoidRestartingOnlyAnIdenticalVerifiedRepresentation() {
        assertTrue(YouTubeAudioStreamService.sameRepresentation(reference, reference.replace("320000", "160000")))
        for (different in listOf(
            reference.replace(videoId, "lmnopqrstuv"), reference.replace("itag=251", "itag=250"),
            reference.replace("length=123456", "length=123457"), reference.replace("stream.audio", "stream.m3u8"),
            "$reference&session=new", reference.replace("&length=123456", ""), reference.replace("itag=251", "itag=0")
        )) assertFalse(different, YouTubeAudioStreamService.sameRepresentation(reference, different))
        assertFalse(YouTubeAudioStreamService.sameRepresentation("xtreme-youtube://audio/$videoId", "xtreme-youtube://audio/$videoId"))
    }

    @Test fun extractionFailureRetainsItsReasonAndCannotInvokeADisabledFallback() = runBlocking {
        val song = MusicTrack("yt_$videoId", "Selected Recording", "Artist", "Album", 180_000, "cover", "")
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = PlaybackSource.newInstallDefaults)
        var extractions = 0
        val resolver = StreamResolver(
            searchYouTube = { error("The selected recording already has a video ID") },
            searchStandard = { _, _ -> error("JioSaavn is disabled") },
            searchLossless = { error("Archive is disabled") },
            resolveYouTube = { _, _, _, _ ->
                extractions++
                throw PlaybackDiagnostics.Failure(PlaybackDiagnostics.Reason.UNSUPPORTED_TRANSPORT,
                    PlaybackDiagnostics.Stage.EXTRACTION, IOException("https://private.example/audio?token=secret-signed-value"))
            }
        )
        try {
            resolver.resolve(song, policy.ceiling.audioQuality, sourcePolicy = policy)
            fail("Unsupported extraction must not report a playable stream")
        } catch (expected: TrackUnavailableException) {
            assertTrue(expected.message.orEmpty().contains("unsupported stream transport"))
            assertFalse(expected.message.orEmpty().contains("secret-signed-value"))
        }
        assertEquals(1, extractions)
        val attempts = PlaybackDiagnostics.recent(song.id, policy)
        assertEquals(1, attempts.size)
        assertEquals(listOf(PlaybackSourceKind.YOUTUBE), attempts.single().enabledSources)
        assertEquals(PlaybackDiagnostics.Reason.UNSUPPORTED_TRANSPORT, attempts.single().reason)
        assertEquals(PlaybackDiagnostics.Stage.EXTRACTION, attempts.single().stage)
        assertTrue(ShadowLog.getLogsForTag("PlaybackRouting").none { it.msg.contains("secret-signed-value") })
    }
}
