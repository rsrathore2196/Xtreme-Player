package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubePrimaryRoutingTest {
    private val original = MusicTrack("online_original", "Exact Song", "Main Artist", "Original Album",
        180_000, "https://art.example/high-quality.jpg", "https://aac.saavncdn.com/original_320.mp4",
        primaryArtists = listOf("Main Artist"), isLiked = true)
    private val video = original.copy(id = "yt_abcdefghijk", audioUrl = "", source = "YouTube Music", bitrateKbps = 0)
    @After fun clear() { StreamResolver.clearVerifiedLosslessCache() }
    private fun resolved(request: MusicTrack, cap: Int, codec: String = "Opus", rate: Int = minOf(80, cap / 1000)) =
        request.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=$cap",
            bitrateKbps = rate, qualityBadge = if (rate > 0) "$codec • $rate kbps" else codec, source = "YouTube Music")

    @Test fun optionalSourcesOffUsesYoutubeAtEveryTierAndKeepsPlaylistMetadata() = runBlocking {
        for (ceiling in AudioQualityCeiling.entries) for (enhancer in listOf(false)) {
            val calls = mutableListOf<String>()
            val resolver = StreamResolver(searchStandard = { _, _ -> calls += "jio"; listOf(original) },
                searchLossless = { error("Lossy profiles must skip Archive") }, fetchById = { _, _ -> error("YouTube is available") },
                searchYouTube = { calls += "youtube-search"; listOf(video) },
                resolveYouTube = { track, _, _, cap -> calls += "youtube-audio"; resolved(track, cap) })
            val onlyYoutube = PlaybackSource.builtIns.map { it.copy(enabled = it.kind == PlaybackSourceKind.YOUTUBE) }
            val result = resolver.resolve(original, ceiling.audioQuality,
                sourcePolicy = PlaybackSourcePolicy(ceiling, enhancer, sources = onlyYoutube))
            assertFalse(calls.contains("jio"))
            assertEquals("youtube-search", calls.first())
            assertEquals("YouTube Music", result.source); assertFalse(result.isLossless)
            assertEquals(original.id, result.id); assertEquals(original.title, result.title)
            assertEquals(original.artist, result.artist); assertEquals(original.album, result.album)
            assertEquals(original.coverUrl, result.coverUrl); assertTrue(result.isLiked)
            assertTrue(result.bitrateKbps * 1000 <= ceiling.maxBitrateBps)
        }
    }

    @Test fun selectedYoutubeIdNeverTriggersAnotherMetadataSearch() = runBlocking {
        val resolver = StreamResolver(searchYouTube = { error("The selected video ID is already known") },
            searchStandard = { _, _ -> error("A working YouTube stream has priority") },
            resolveYouTube = { track, _, _, cap -> resolved(track, cap, "AAC") })
        val result = resolver.resolve(video, AudioQuality.HIGH_160)
        assertEquals(video.id, result.id)
        assertEquals("YouTube Music", result.source)
    }

    @Test fun maximumOrdersArchiveThenJioThenYoutubeAndDoesNotLabelFallbacksLossless() = runBlocking {
        for (enhancer in listOf(false, true)) for (jioAvailable in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val resolver = StreamResolver(searchLossless = { calls += "archive"; emptyList() },
                searchStandard = { _, _ -> calls += "jio"; if (jioAvailable) listOf(original) else emptyList() },
                fetchById = { _, _ -> calls += "jio-details"; if (jioAvailable) original else null },
                searchYouTube = { calls += "youtube-search"; listOf(video) },
                resolveYouTube = { track, _, _, cap -> calls += "youtube-audio"; resolved(track, cap) })
            val result = resolver.resolve(video, AudioQuality.HI_RES_LOSSLESS,
                sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, enhancer, sources = listOf(PlaybackSource.archive, PlaybackSource.jio, PlaybackSource.youtube)))
            assertEquals("archive", calls.first())
            assertTrue(calls.indexOf("jio") > 0)
            if (jioAvailable) { assertEquals("JIOSAAVN", result.source); assertFalse(calls.contains("youtube-audio")) }
            else { assertEquals("YouTube Music", result.source); assertTrue(calls.indexOf("youtube-audio") > calls.indexOf("jio")) }
            assertFalse(result.isLossless); assertFalse(result.qualityBadge.contains("FLAC"))
            assertEquals(video.id, result.id)
        }
    }

    @Test fun enabledJioUsesItsFixedRankWithoutChangingOriginalCatalogOrArtwork() = runBlocking {
        val calls = mutableListOf<String>()
        val resolver = StreamResolver(searchYouTube = { error("Known video ID") },
            resolveYouTube = { _, _, _, _ -> calls += "youtube"; null },
            searchStandard = { _, _ -> calls += "jio"; listOf(original) })
        val result = resolver.resolve(video, AudioQuality.ULTRA_HD_320)
        assertEquals(listOf("jio"), calls)
        assertEquals(video.id, result.id); assertEquals(video.coverUrl, result.coverUrl)
        assertEquals("JIOSAAVN", result.source); assertEquals(320, result.bitrateKbps)
    }

    @Test fun unavailableJioFallsBackToTheSameYoutubeCatalogRecording() = runBlocking {
        val calls = mutableListOf<String>()
        val resolver = StreamResolver(searchStandard = { _, _ -> calls += "jio"; emptyList() },
            resolveYouTube = { track, _, _, cap -> calls += "youtube"; resolved(track, cap) })
        val result = resolver.resolve(video, AudioQuality.ULTRA_HD_320)
        assertEquals("jio", calls.first()); assertEquals("youtube", calls.last())
        assertEquals("YouTube Music", result.source)
        assertEquals(video.id, result.id); assertEquals(video.coverUrl, result.coverUrl)
    }

    @Test fun providerMappingsAreReusedAcrossQualityChangesWithoutReusingSignedUrls() = runBlocking {
        var searches = 0
        val caps = mutableListOf<Int>()
        val resolver = StreamResolver(searchYouTube = { searches++; listOf(video) },
            searchStandard = { _, _ -> error("YouTube is available") },
            resolveYouTube = { track, _, _, cap -> caps += cap; resolved(track, cap) })
        val standard = resolver.resolve(original, AudioQuality.HIGH_160)
        val saver = resolver.resolve(original, AudioQuality.MEDIUM_96)
        assertEquals(1, searches)
        assertEquals(listOf(160_000, 96_000), caps)
        assertNotEquals(standard.audioUrl, saver.audioUrl)
        assertEquals(original.id, saver.id)
    }

    @Test fun wrongYoutubeRecordingIsNotExtractedAndFallsBackToTheExactJioTrack() = runBlocking {
        val resolver = StreamResolver(searchYouTube = { listOf(video.copy(title = "Exact Song (Live)"), video.copy(durationMs = 260_000)) },
            resolveYouTube = { _, _, _, _ -> error("Never extract a mismatched recording") })
        val result = resolver.resolve(original, AudioQuality.ULTRA_HD_320)
        assertEquals(original.audioUrl, result.audioUrl); assertEquals("JIOSAAVN", result.source)
    }

    @Test fun unknownYoutubeBitrateIsKeptUnknownRatherThanEstimated() = runBlocking {
        val resolver = StreamResolver(resolveYouTube = { track, _, _, cap -> resolved(track, cap, rate = 0) })
        val result = resolver.resolve(video, AudioQuality.MEDIUM_96,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER))
        assertEquals(0, result.bitrateKbps); assertEquals("Opus", result.qualityBadge)
        assertTrue(PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER).acceptsRemoteTrack(result))
    }
}
