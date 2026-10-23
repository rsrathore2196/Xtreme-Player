package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService.LosslessSearchPage
import com.example.data.repository.StreamResolver
import com.example.data.repository.TrackUnavailableException
import com.example.playback.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class PlaybackIntegrationRegressionTest {
    private val selected = MusicTrack("yt_abcdefghijk", "Selected Song", "Artist", "Album", 180_000, "art", "")
    private val youtube = selected.copy(source = "YouTube Music", bitrateKbps = 146,
        audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000&itag=251")
    private val flac = selected.copy(id = "ia_album:track.flac", source = "INTERNET_ARCHIVE", isLossless = true,
        audioUrl = "https://archive.org/download/album/track.flac")
    private val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX,
        sources = listOf(PlaybackSource.archive, PlaybackSource.youtube))

    @Before fun clearBefore() { StreamResolver.clearVerifiedLosslessCache() }
    @After fun clearAfter() { StreamResolver.clearVerifiedLosslessCache() }

    @Test fun archiveTimeoutKeepsItsReasonWhileAnEnabledYoutubeFallbackPlays() = runBlocking {
        val resolver = StreamResolver(findLossless = { _, _, _ ->
            LosslessSearchPage(isSuccessful = false, failure = LosslessDiagnostics.Reason.NETWORK_TIMEOUT, exhaustive = false)
        }, searchStandard = { _, _ -> error("JioSaavn is disabled") },
            resolveYouTube = { request, _, _, _ -> youtube.copy(id = request.id) })
        val result = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(selected.id, result.id)
        assertEquals(youtube.audioUrl, result.audioUrl)
        assertFalse(result.isLossless)
        val attempt = PlaybackDiagnostics.recent(selected.id, policy).last { it.source == PlaybackSourceKind.INTERNET_ARCHIVE }
        assertEquals(PlaybackDiagnostics.Reason.TIMEOUT, attempt.reason)
    }

    @Test fun incompleteArchiveResponseCannotHideALaterVerifiedFileBehindANegativeCache() = runBlocking {
        var lookups = 0
        val resolver = StreamResolver(findLossless = { _, _, _ ->
            if (++lookups == 1) LosslessSearchPage(isSuccessful = false,
                failure = LosslessDiagnostics.Reason.METADATA_INCOMPLETE, exhaustive = false)
            else LosslessSearchPage(listOf(flac))
        }, searchStandard = { _, _ -> error("JioSaavn is disabled") }, supportsLossless = { true },
            resolveYouTube = { request, _, _, _ -> youtube.copy(id = request.id) })
        val first = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(youtube.audioUrl, first.audioUrl)
        val retried = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(2, lookups)
        assertEquals(flac.audioUrl, retried.audioUrl)
        assertEquals(selected.id, retried.id)
        assertTrue(retried.isLossless)
    }

    @Test fun incompleteMetadataIsATemporaryFailureWhenAllOtherProvidersAreDisabled() = runBlocking {
        val onlyArchive = policy.copy(sources = listOf(PlaybackSource.archive))
        val resolver = StreamResolver(findLossless = { _, _, _ -> LosslessSearchPage(isSuccessful = false,
            failure = LosslessDiagnostics.Reason.METADATA_INCOMPLETE, exhaustive = false) },
            searchStandard = { _, _ -> error("Disabled JioSaavn") },
            resolveYouTube = { _, _, _, _ -> null })
        try {
            resolver.resolve(selected, onlyArchive.ceiling.audioQuality, sourcePolicy = onlyArchive)
            fail("Incomplete metadata cannot be reported as playable")
        } catch (expected: TrackUnavailableException) {
            assertTrue(expected.temporary)
            assertTrue(expected.message.orEmpty().contains("incomplete recording metadata"))
        }
    }

    @Test fun aVerifiedFileFromAPartiallySuccessfulDiscoveryStillPlays() = runBlocking {
        val resolver = StreamResolver(findLossless = { _, _, _ -> LosslessSearchPage(listOf(flac),
            isSuccessful = false, failure = LosslessDiagnostics.Reason.NETWORK_TIMEOUT, exhaustive = false) },
            supportsLossless = { true }, resolveYouTube = { _, _, _, _ -> error("A verified FLAC is available") })
        val result = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(flac.audioUrl, result.audioUrl)
        assertEquals(selected.id, result.id)
    }
}
