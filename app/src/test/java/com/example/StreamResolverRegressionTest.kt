package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.data.repository.TrackUnavailableException
import com.example.playback.AudioQuality
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackSourcePolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StreamResolverRegressionTest {
    // Verified matches are process-wide in the current resolver. Each test owns
    // its provider responses and must not inherit another fixture's recording.
    @org.junit.Before fun clearSharedMatches() { StreamResolver.clearVerifiedLosslessCache() }
    @org.junit.After fun releaseSharedMatches() { StreamResolver.clearVerifiedLosslessCache() }

    private val requested = MusicTrack("import_song", "Song", "Artist", "Album", 180000, "cover", "")
    private val youtube = requested.copy(id = "yt_abcdefghijk", source = "YouTube Music")
    private val quality = AudioQuality.ULTRA_HD_320
    private val expired = "https://aac.saavncdn.com/expired.m4a"
    private val fresh = "https://aac.saavncdn.com/fresh.m4a"

    // Every provider boundary is controlled; these checks never need live APIs.
    private fun resolver(search: suspend (String, Boolean) -> List<MusicTrack>) = StreamResolver(
        searchStandard = search, searchLossless = { emptyList() }, fetchById = { _, _ -> null },
        resolveYouTube = { _, _, _, _ -> null }, searchYouTube = { emptyList() })

    @Test fun oneSelectionNeverRepeatsTheSameFailedProviderDetailLookup() = runBlocking {
        val calls = AtomicInteger()
        val selected = requested.copy(id = "online_selected")
        val resolver = StreamResolver(searchStandard = { _, _ -> listOf(selected.copy(audioUrl = "")) },
            fetchById = { _, _ -> calls.incrementAndGet(); null }, resolveYouTube = { _, _, _, _ -> null },
            searchYouTube = { emptyList() }, searchLossless = { emptyList() })
        try { resolver.resolve(selected, quality, preferredSourceId = "jiosaavn"); fail("Unavailable recording must fail safely") }
        catch (_: TrackUnavailableException) { }
        assertEquals(1, calls.get())
        try { resolver.resolve(selected, quality, preferredSourceId = "jiosaavn") }
        catch (_: TrackUnavailableException) { }
        assertEquals("A later selection can retry temporary failures", 2, calls.get())
    }
    @Test fun unavailableSongNeverReceivesDemoAudioOrCacheEntry() = runBlocking {
        val resolver = resolver { _, _ -> emptyList() }
        for (q in listOf(quality, AudioQuality.HI_RES_LOSSLESS)) {
            try {
                resolver.resolve(requested, q)
                fail("Expected an unavailable-track error")
            } catch (_: TrackUnavailableException) { }
        }
        assertEquals(0, resolver.cacheSize)
    }

    @Test fun wrongRecordingIsRejectedAndMatchingCandidateKeepsRequestedIdentity() = runBlocking {
        val wrongArtist = requested.copy(id = "online_wrong", artist = "Someone Else", audioUrl = "https://aac.saavncdn.com/wrong.m4a")
        val remix = requested.copy(id = "online_remix", title = "Song Remix", audioUrl = "https://aac.saavncdn.com/remix.m4a")
        val match = requested.copy(id = "online_provider", audioUrl = fresh, isLiked = false)
        val resolver = resolver { _, _ -> listOf(wrongArtist, remix, match) }
        val result = resolver.resolve(youtube.copy(isLiked = true), quality)
        assertEquals(fresh, result.audioUrl)
        assertEquals(youtube.id, result.id)
        assertEquals(youtube.title, result.title)
        assertEquals(youtube.artist, result.artist)
        assertTrue(result.isLiked)
        assertEquals("JIOSAAVN", result.source)
    }

    @Test fun forcedRecoveryBypassesCacheAndExistingUrl() = runBlocking {
        var url = expired
        val refreshRequests = mutableListOf<Boolean>()
        val resolver = resolver { _, refresh ->
            refreshRequests.add(refresh)
            listOf(requested.copy(id = "online_provider", audioUrl = url))
        }
        val first = resolver.resolve(requested, quality)
        assertEquals(expired, first.audioUrl)
        url = fresh
        val recovered = resolver.resolve(first, quality, forceRefresh = true, failedUrl = expired)
        assertEquals(fresh, recovered.audioUrl)
        assertEquals(requested.id, recovered.id)
        assertEquals(fresh, resolver.resolve(requested, quality).audioUrl)
        assertEquals(listOf(false, true), refreshRequests)
        assertEquals(1, resolver.cacheSize)
    }

    @Test fun failedUrlCannotBeReturnedAgain() = runBlocking {
        val resolver = resolver { _, _ -> listOf(requested.copy(audioUrl = expired)) }
        try {
            resolver.resolve(requested.copy(audioUrl = expired), quality, true, expired)
            fail("Expected rejection of the failed URL")
        } catch (_: TrackUnavailableException) { }
        assertEquals(0, resolver.cacheSize)
    }

    @Test fun cancellationStopsConcurrentResolutionWithoutPopulatingCache() = runBlocking {
        val standardCalls = AtomicInteger()
        val archiveCalls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val resolver = StreamResolver(
            searchStandard = { _, _ -> standardCalls.incrementAndGet(); emptyList() },
            searchLossless = { archiveCalls.incrementAndGet(); emptyList() },
            fetchById = { _, _ -> null },
            resolveYouTube = { _, _, _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }, searchYouTube = { fail("Cancelled selection must not search another video"); emptyList() })
        val selection = async { resolver.resolve(youtube, AudioQuality.HI_RES_LOSSLESS) }
        try {
            withTimeout(5_000) { started.await() }
            selection.cancelAndJoin()
            assertTrue(selection.isCancelled)
            assertTrue(stopped.isCompleted)
            val completedJioCalls = standardCalls.get()
            val completedArchiveCalls = archiveCalls.get()
            kotlinx.coroutines.yield()
            assertEquals(completedJioCalls, standardCalls.get())
            assertEquals(completedArchiveCalls, archiveCalls.get())
            assertEquals(0, resolver.cacheSize)
        } finally { selection.cancelAndJoin() }
    }

    @Test fun providerCancellationIsPropagatedWithoutFallback() = runBlocking {
        val resolver = StreamResolver(
            searchStandard = { _, _ -> emptyList() },
            resolveYouTube = { _, _, _, _ -> throw CancellationException("selected track changed") },
            searchYouTube = { fail("Cancellation must not trigger YouTube search"); emptyList() })
        try {
            resolver.resolve(youtube, quality)
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("selected track changed", expected.message)
        }
        assertEquals(0, resolver.cacheSize)
    }

    @Test fun exactYouTubeSelectionPlaysWhenJioIsUnavailable() = runBlocking {
        val resolver = StreamResolver(
            searchStandard = { _, _ -> emptyList() },
            resolveYouTube = { track, selectedQuality, _, _ ->
                assertEquals(youtube.id, track.id)
                assertEquals(quality, selectedQuality)
                track.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk?itag=251", bitrateKbps = 160)
            }, searchYouTube = { fail("Exact video is playable"); emptyList() })
        val result = resolver.resolve(youtube, quality)
        assertEquals(youtube.id, result.id)
        assertEquals("xtreme-youtube://audio/abcdefghijk?itag=251", result.audioUrl)
        assertEquals(160, result.bitrateKbps)
        assertFalse(result.isLossless)
    }

    @Test fun failedAddonGroupCannotSpendTheColdYouTubeExtractionWindow() = runTest {
        val sources = (1..3).map { PlaybackSource("addon_$it", PlaybackSourceKind.ADDON, "Server $it", baseUrl = "https://server$it.example") } +
            PlaybackSource.newInstallDefaults
        var extractions = 0
        val resolver = StreamResolver(ioDispatcher = StandardTestDispatcher(testScheduler),
            resolveAddon = { _, _, _, _ -> delay(60_000); null },
            resolveYouTube = { requested, _, _, _ ->
                extractions++
                delay(30_000)
                requested.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000&itag=251", bitrateKbps = 146)
            }, searchStandard = { _, _ -> error("Disabled provider") }, searchLossless = { error("Disabled provider") })
        val result = resolver.resolve(youtube, AudioQuality.HI_RES_LOSSLESS,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = sources))
        assertEquals(youtube.id, result.id)
        assertEquals(1, extractions)
        assertEquals(30_250, testScheduler.currentTime)
    }

    @Test fun exhaustedProvidersTerminateWithinTheOverallBudgetWithoutCachingAFailure() = runTest {
        val resolver = StreamResolver(ioDispatcher = StandardTestDispatcher(testScheduler),
            resolveAddon = { _, _, _, _ -> awaitCancellation() },
            resolveYouTube = { _, _, _, _ -> awaitCancellation() },
            searchStandard = { _, _ -> awaitCancellation() }, searchLossless = { awaitCancellation() })
        val sources = listOf(PlaybackSource("addon", PlaybackSourceKind.ADDON, "Server", baseUrl = "https://server.example")) + PlaybackSource.builtIns
        try {
            resolver.resolve(youtube, AudioQuality.HI_RES_LOSSLESS,
                sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = sources))
            fail("Expected a bounded unavailable result")
        } catch (_: TrackUnavailableException) { }
        assertTrue(testScheduler.currentTime <= StreamResolver.PLAYBACK_RESOLUTION_TIMEOUT_MS)
        assertEquals(0, resolver.cacheSize)
    }
}
