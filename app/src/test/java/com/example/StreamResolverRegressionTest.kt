package com.example

import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.data.repository.TrackUnavailableException
import com.example.playback.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamResolverRegressionTest {
    private val requested = MusicTrack("yt_song", "Song", "Artist", "Album", 180000, "cover", "")
    private val quality = AudioQuality.ULTRA_HD_320

    @Test fun unavailableSongNeverReceivesDemoAudioOrCacheEntry() = runBlocking {
        val resolver = StreamResolver(searchStandard = { _, _ -> emptyList() }, searchLossless = { emptyList() })
        for (q in listOf(quality, AudioQuality.HI_RES_LOSSLESS)) {
            try {
                resolver.resolve(requested, q)
                fail("Expected an unavailable-track error")
            } catch (_: TrackUnavailableException) { }
        }
        assertEquals(0, resolver.cacheSize)
    }

    @Test fun wrongRecordingIsRejectedAndMatchingCandidateKeepsRequestedIdentity() = runBlocking {
        val wrongArtist = requested.copy(id = "wrong", artist = "Someone Else", audioUrl = "wrong")
        val remix = requested.copy(id = "remix", title = "Song Remix", audioUrl = "remix")
        val match = requested.copy(id = "provider", audioUrl = "correct", isLiked = false)
        val resolver = StreamResolver(searchStandard = { _, _ -> listOf(wrongArtist, remix, match) })
        val result = resolver.resolve(requested.copy(isLiked = true), quality)
        assertEquals("correct", result.audioUrl)
        assertEquals(requested.id, result.id)
        assertTrue(result.isLiked)
    }

    @Test fun forcedRecoveryBypassesCacheAndExistingUrl() = runBlocking {
        var url = "expired"
        var calls = 0
        val refreshRequests = mutableListOf<Boolean>()
        val resolver = StreamResolver(searchStandard = { _, refresh ->
            calls++
            refreshRequests.add(refresh)
            listOf(requested.copy(audioUrl = url))
        })
        val first = resolver.resolve(requested, quality)
        url = "fresh"
        val recovered = resolver.resolve(first, quality, forceRefresh = true, failedUrl = "expired")
        assertEquals("fresh", recovered.audioUrl)
        assertEquals(2, calls)
        assertEquals(listOf(false, true), refreshRequests)
        assertEquals("fresh", resolver.resolve(requested, quality).audioUrl)
    }

    @Test fun failedUrlCannotBeReturnedAgain() = runBlocking {
        val resolver = StreamResolver(searchStandard = { _, _ -> listOf(requested.copy(audioUrl = "expired")) })
        try {
            resolver.resolve(requested.copy(audioUrl = "expired"), quality, true, "expired")
            fail("Expected rejection of the failed URL")
        } catch (_: TrackUnavailableException) { }
        assertEquals(0, resolver.cacheSize)
    }

    @Test fun cancellationDoesNotInvokeFallbackOrPopulateCache() = runBlocking {
        var standardCalls = 0
        val resolver = StreamResolver(searchStandard = { _, _ -> standardCalls++; emptyList() },
            searchLossless = { throw CancellationException("cancelled") })
        try {
            resolver.resolve(requested, AudioQuality.HI_RES_LOSSLESS)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, standardCalls)
        assertEquals(0, resolver.cacheSize)
    }
}
