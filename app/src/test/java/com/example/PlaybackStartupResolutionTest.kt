package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.data.repository.resolveProviderCandidates
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackSourcePolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class PlaybackStartupResolutionTest {
    private val native = listOf(PlaybackSource.youtube, PlaybackSource.jio)
    private val song = MusicTrack("yt_abcdefghijk", "Exact Song", "Artist", "Album", 180_000, "cover", "",
        source = "YouTube Music")
    private fun youtube(track: MusicTrack, cap: Int) = track.copy(
        audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=$cap&itag=251", bitrateKbps = 80)

    @Test fun fastPrimaryAvoidsEverySpeculativeFallback() = runTest {
        val calls = mutableListOf<String>()
        val result = resolveProviderCandidates(native) { source ->
            calls += source.id
            if (source.kind == PlaybackSourceKind.YOUTUBE) { delay(100); "youtube" }
            else error("Fast primary must not start fallback network work")
        }
        assertEquals("youtube", result)
        assertEquals(listOf(PlaybackSource.youtube.id), calls)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test fun slowPrimaryAndFallbackOverlapButSourceOrderStillWins() = runTest {
        val started = mutableMapOf<String, Long>()
        val result = resolveProviderCandidates(native) { source ->
            started[source.id] = testScheduler.currentTime
            if (source.kind == PlaybackSourceKind.YOUTUBE) { delay(900); "youtube" }
            else { delay(50); "jio" }
        }
        assertEquals("youtube", result)
        assertEquals(250L, started[PlaybackSource.jio.id])
        assertEquals(900L, testScheduler.currentTime)
    }

    @Test fun primaryFailureReturnsAlreadyPreparedFallbackWithoutAnotherLookup() = runTest {
        var fallbackCalls = 0
        val result = resolveProviderCandidates(native) { source ->
            if (source.kind == PlaybackSourceKind.YOUTUBE) { delay(1_000); null }
            else { fallbackCalls++; delay(400); "jio" }
        }
        assertEquals("jio", result)
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(1, fallbackCalls)
    }

    @Test fun optionalArchiveCanLeaveCriticalPathButJioStillPrecedesYoutube() = runTest {
        var archiveCancelled = false
        val result = resolveProviderCandidates(listOf(PlaybackSource.archive) + native.reversed(),
            defer = { it.kind == PlaybackSourceKind.INTERNET_ARCHIVE }) { source ->
            when (source.kind) {
                PlaybackSourceKind.INTERNET_ARCHIVE -> try { awaitCancellation() } finally { archiveCancelled = true }
                PlaybackSourceKind.JIOSAAVN -> { delay(300); "jio" }
                else -> { delay(10); "youtube" }
            }
        }
        assertEquals("jio", result)
        assertEquals(550L, testScheduler.currentTime)
        assertTrue(archiveCancelled)
    }

    @Test fun completedArchiveWinsASimultaneouslyPreparedFallback() = runTest {
        val result = resolveProviderCandidates(listOf(PlaybackSource.archive, PlaybackSource.jio),
            defer = { it.kind == PlaybackSourceKind.INTERNET_ARCHIVE }) { source ->
            delay(if (source.kind == PlaybackSourceKind.INTERNET_ARCHIVE) 500 else 250)
            source.id
        }
        assertEquals(PlaybackSource.archive.id, result)
        assertEquals(500L, testScheduler.currentTime)
    }

    @Test fun aPriorityGraceNeverAbandonsTheOnlyProviderThatCanActuallyPlay() = runTest {
        val result = resolveProviderCandidates(native, priorityGraceMs = { 600L }) { source ->
            if (source.kind == PlaybackSourceKind.YOUTUBE) { delay(2_000); "youtube" }
            else { delay(50); null }
        }
        assertEquals("youtube", result)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test fun highStartsPreparedFallbackAtGraceDeadlineAndCancelsSlowExtraction() = runTest {
        var cancelled = false
        var fallbackCalls = 0
        // An existing Jio transport still keeps its original YouTube catalog ID.
        val selected = song.copy(audioUrl = "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN")
        val resolver = StreamResolver(ioDispatcher = StandardTestDispatcher(testScheduler),
            resolveYouTube = { _, _, _, _ -> try { awaitCancellation() } finally { cancelled = true } },
            searchStandard = { _, _ -> fallbackCalls++; error("Already have the exact transport") },
            searchYouTube = { error("The catalog video ID is already known") })
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = PlaybackSource.builtIns)
        val resolved = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(StreamResolver.PROVIDER_PRIORITY_GRACE_MS, testScheduler.currentTime)
        assertTrue(cancelled)
        assertEquals(0, fallbackCalls)
        assertEquals(selected.id, resolved.id)
        assertEquals("JIOSAAVN", resolved.source)
        assertFalse(resolved.isLossless)
    }

    @Test fun onlyYoutubeKeepsItsColdExtractionWindowAtAllFourQualityTiers() = runTest {
        for (ceiling in AudioQualityCeiling.entries) {
            val start = testScheduler.currentTime
            var calls = 0
            val resolver = StreamResolver(ioDispatcher = StandardTestDispatcher(testScheduler),
                resolveYouTube = { requested, _, _, cap -> calls++; delay(30_000); youtube(requested, cap) },
                searchStandard = { _, _ -> error("Disabled JioSaavn") }, searchLossless = { error("Disabled Archive") },
                searchYouTube = { error("Exact video must not search") })
            val policy = PlaybackSourcePolicy(ceiling, sources = PlaybackSource.newInstallDefaults)
            val result = resolver.resolve(song, ceiling.audioQuality, sourcePolicy = policy)
            assertEquals(30_000L, testScheduler.currentTime - start)
            assertEquals(1, calls)
            assertEquals("YouTube Music", result.source)
            assertTrue(policy.acceptsRemoteTrack(result))
        }
    }

    @Test fun explicitlySelectedLosslessFileCannotBeReplacedByAFastLossyFallback() = runTest {
        val selected = song.copy(id = "ia_album:song.flac", audioUrl = "https://archive.org/download/album/song.flac",
            source = "INTERNET_ARCHIVE", isLossless = true)
        var fallbackCalls = 0
        val resolver = StreamResolver(ioDispatcher = StandardTestDispatcher(testScheduler),
            verifyArchive = { delay(1_500); it }, supportsLossless = { true },
            resolveAddon = { _, request, _, _ -> fallbackCalls++; request },
            resolveYouTube = { track, _, _, cap -> fallbackCalls++; youtube(track, cap) },
            searchStandard = { _, _ -> fallbackCalls++; emptyList() })
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(
            PlaybackSource("addon_server", PlaybackSourceKind.ADDON, "Server", baseUrl = "https://server.example")) + PlaybackSource.builtIns)
        val result = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertTrue(result.isLossless)
        assertEquals(selected.audioUrl, result.audioUrl)
        assertEquals(1_500L, testScheduler.currentTime)
        assertEquals(0, fallbackCalls)
    }

    @Test fun manySlowAddonsCannotStarveNativeAdmissionOrMultiplyTheGroupBudget() = runTest {
        val addons = (1..10).map { PlaybackSource("addon_$it", PlaybackSourceKind.ADDON, "Server $it") }
        var active = 0
        var peak = 0
        var nativeStarted = -1L
        val result = resolveProviderCandidates(addons + PlaybackSource.youtube) { source ->
            active++; peak = maxOf(peak, active)
            try {
                if (source.kind == PlaybackSourceKind.YOUTUBE) { nativeStarted = testScheduler.currentTime; "youtube" }
                else if (source == addons.first()) { delay(16_000); null }
                else awaitCancellation()
            } finally { active-- }
        }
        assertEquals("youtube", result)
        assertEquals(250L, nativeStarted)
        assertEquals(16_000L, testScheduler.currentTime)
        assertTrue(peak <= 4)
        assertEquals(0, active)
    }

    @Test fun switchingTrackCancelsEveryCandidateAndCannotPublishALateResult() = runTest {
        var active = 0
        var published = false
        val request = async {
            resolveProviderCandidates(listOf(PlaybackSource.archive) + native,
                defer = { it.kind == PlaybackSourceKind.INTERNET_ARCHIVE }) {
                active++
                try { awaitCancellation() } finally { active-- }
            }
            published = true
        }
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(3, active)
        request.cancelAndJoin()
        assertEquals(0, active)
        assertFalse(published)
    }
}
