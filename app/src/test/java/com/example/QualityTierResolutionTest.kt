package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.AudioQuality
import com.example.playback.AudioQualityPreference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class QualityTierResolutionTest {
    private val song = MusicTrack("yt_abcdefghijk", "Song (Official Audio)", "Artist", "Album", 180000, "cover", "", source = "YouTube Music")
    private val jio = song.copy(id = "online_match", title = "Song", source = "JIOSAAVN", audioUrl = "https://aac.saavncdn.com/song_320.mp4")
    private val archive = song.copy(id = "ia_album:song.flac", title = "Song", audioUrl = "https://archive.org/download/album/song.flac",
        isLossless = true, source = "INTERNET_ARCHIVE", qualityBadge = "Lossless")

    @Test fun maximumUsesVerifiedArchiveBeforeOtherProvidersAndPreservesIdentity() = runBlocking {
        val calls = mutableListOf<String>()
        val resolver = StreamResolver(searchLossless = { query -> calls += "archive"; assertEquals("Song artist", query); listOf(archive) },
            searchStandard = { _, _ -> calls += "jio"; listOf(jio) }, resolveYouTube = { _, _, _ -> calls += "youtube"; null }, supportsLossless = { true })
        val result = resolver.resolve(song, AudioQualityPreference.MAX_LOSSLESS)
        assertEquals(listOf("archive"), calls); assertEquals(song.id, result.id)
        assertEquals(song.coverUrl, result.coverUrl); assertEquals(archive.audioUrl, result.audioUrl); assertTrue(result.isLossless)
    }
    @Test fun lossyTiersSkipArchiveAndUseCorrectJioRate() = runBlocking {
        for ((quality, kbps) in listOf(AudioQuality.ULTRA_HD_320 to 320, AudioQuality.HIGH_160 to 160, AudioQuality.MEDIUM_96 to 96)) {
            val resolver = StreamResolver(searchLossless = { fail("Lossy tiers must never call Archive"); emptyList() },
                searchStandard = { _, _ -> listOf(jio) }, resolveYouTube = { _, _, _ -> fail("Jio is playable"); null })
            val result = resolver.resolve(song, quality)
            assertTrue(result.audioUrl.endsWith("_${kbps}.mp4")); assertEquals(kbps, result.bitrateKbps); assertFalse(result.isLossless)
        }
    }
    @Test fun archiveBudgetCancelsPendingWorkThenPlaysJio() = runBlocking {
        var cancelled = false
        val resolver = StreamResolver(searchLossless = { try { awaitCancellation() } finally { cancelled = true } },
            searchStandard = { _, _ -> listOf(jio) }, resolveYouTube = { _, _, _ -> null })
        val result = withTimeout(4000) { resolver.resolve(song, AudioQuality.HI_RES_LOSSLESS) }
        assertTrue(cancelled); assertEquals(jio.audioUrl, result.audioUrl)
        assertFalse("A timeout must allow the delayed broader lossless lookup", resolver.hasRecentLosslessAttempt(song))
    }
    @Test fun failedInitialArchiveRequestDoesNotPoisonTheNextVerifiedResolution() = runBlocking {
        var lookups = 0
        val resolver = StreamResolver(searchLossless = {
            if (++lookups == 1) throw java.io.IOException("Temporary network failure")
            listOf(archive)
        }, searchStandard = { _, _ -> listOf(jio) }, resolveYouTube = { _, _, _ -> null }, supportsLossless = { true })
        val first = resolver.resolve(song, AudioQuality.HI_RES_LOSSLESS)
        assertEquals(jio.audioUrl, first.audioUrl)
        assertFalse(resolver.hasRecentLosslessAttempt(song))
        val refreshed = resolver.resolve(song, AudioQuality.HI_RES_LOSSLESS, failedUrl = first.audioUrl)
        assertEquals(archive.audioUrl, refreshed.audioUrl)
        assertTrue(resolver.hasRecentLosslessAttempt(song))
        assertEquals(2, lookups)
    }
    @Test fun emptyInitialPageStillPermitsABroaderBackgroundCollectionLookup() = runBlocking {
        val resolver = StreamResolver(searchLossless = { emptyList() }, searchStandard = { _, _ -> listOf(jio) },
            resolveYouTube = { _, _, _ -> null })
        assertEquals(jio.audioUrl, resolver.resolve(song, AudioQuality.HI_RES_LOSSLESS).audioUrl)
        assertFalse(resolver.hasRecentLosslessAttempt(song))
    }
    @Test fun unavailableJioUsesExactSelectedYoutubeNotAnUnrelatedVideo() = runBlocking {
        val resolver = StreamResolver(searchLossless = { emptyList() }, searchStandard = { _, _ -> emptyList() },
            resolveYouTube = { track, _, _ -> assertEquals(song.id, track.id)
                track.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk?itag=251", bitrateKbps = 160, qualityBadge = "Opus • 160 kbps") },
            searchYouTube = { fail("The original selected video is playable"); emptyList() })
        val result = resolver.resolve(song, AudioQuality.ULTRA_HD_320)
        assertEquals(song.id, result.id); assertEquals("YouTube Music", result.source); assertFalse(result.isLossless)
    }
    @Test fun archiveIdentityRejectsDifferentArtistsVersionsAndDurations() {
        val requested = song.copy(title = "Song")
        assertTrue(StreamResolver.matchesInitialLossless(requested, archive.copy(durationMs = 185000)))
        assertFalse(StreamResolver.matchesInitialLossless(requested, archive.copy(durationMs = 185001)))
        assertFalse(StreamResolver.matchesInitialLossless(requested, archive.copy(artist = "Wrong Artist")))
        assertFalse(StreamResolver.matchesInitialLossless(requested, archive.copy(title = "Song (Live)")))
        assertFalse(StreamResolver.matchesInitialLossless(requested, archive.copy(title = "Song Remix")))
        assertFalse(StreamResolver.matchesInitialLossless(requested, archive.copy(durationMs = 0)))
        assertFalse(StreamResolver.matchesInitialLossless(requested, archive.copy(audioUrl = "https://archive.org/download/album/song.mp3")))
    }
    @Test fun maximumPreferenceCannotBypassAnActiveLossyNetworkCeiling() = runBlocking {
        val resolver = StreamResolver(searchLossless = { fail("The active cellular/Wi-Fi profile is capped"); emptyList() },
            searchStandard = { _, _ -> listOf(jio) }, resolveYouTube = { _, _, _ -> null })
        val result = resolver.resolve(song, AudioQuality.HI_RES_LOSSLESS, maxBitrateBps = 160000)
        assertEquals(160, result.bitrateKbps); assertFalse(result.isLossless)
    }
    @Test fun changedRecordingMetadataCannotReuseAnotherRecordingsCachedUrl() = runBlocking {
        var candidate = jio
        val resolver = StreamResolver(searchLossless = { emptyList() }, searchStandard = { _, _ -> listOf(candidate) },
            resolveYouTube = { _, _, _ -> null })
        assertEquals(jio.audioUrl, resolver.resolve(song, AudioQuality.ULTRA_HD_320).audioUrl)
        candidate = jio.copy(title = "Another Song", audioUrl = "https://aac.saavncdn.com/another_320.mp4")
        assertEquals(candidate.audioUrl, resolver.resolve(song.copy(title = "Another Song"), AudioQuality.ULTRA_HD_320).audioUrl)
    }
    @Test fun unsupportedLosslessCodecFallsBackBeforePlaybackPreparation() = runBlocking {
        val resolver = StreamResolver(searchLossless = { listOf(archive) }, supportsLossless = { false },
            searchStandard = { _, _ -> listOf(jio) }, resolveYouTube = { _, _, _ -> null })
        assertEquals(jio.audioUrl, resolver.resolve(song, AudioQuality.HI_RES_LOSSLESS).audioUrl)
    }
    @Test fun localFilesRemainAvailableOfflineWithoutProviderCalls() = runBlocking {
        val local = song.copy(audioUrl = "file:///storage/music/song.flac", isLossless = true)
        val resolver = StreamResolver(searchLossless = { fail("Local files must work offline"); emptyList() },
            searchStandard = { _, _ -> fail("Local files must work offline"); emptyList() })
        assertEquals(local, resolver.resolve(local, AudioQuality.HI_RES_LOSSLESS))
    }
}
