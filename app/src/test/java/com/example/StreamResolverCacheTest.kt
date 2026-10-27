package com.example

import android.app.Application
import android.util.LruCache
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.repository.StreamResolver
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
@Config(sdk = [34], application = Application::class)
class StreamResolverCacheTest {
    private val video = "abcdefghijk"
    private val song = MusicTrack("yt_$video", "Song", "Artist", "Album", 180_000, "cover", "", source = "YouTube Music")
    private val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = PlaybackSource.newInstallDefaults)
    @Suppress("UNCHECKED_CAST")
    private val audioCache get() = YouTubeAudioStreamService::class.java.getDeclaredField("cache")
        .apply { isAccessible = true }.get(null) as LruCache<String, YouTubeAudioStreamService.AudioStream>
    @Before fun setup() { audioCache.evictAll(); StreamResolver.clearVerifiedLosslessCache() }
    @After fun cleanup() { audioCache.evictAll(); StreamResolver.clearVerifiedLosslessCache() }
    private fun playable(request: MusicTrack, cap: Int): MusicTrack {
        val low = cap <= 96_000
        val stream = YouTubeAudioStreamService.AudioStream("https://rr.googlevideo.com/videoplayback?itag=${if (low) 139 else 251}",
            if (low) 48 else 146, false, "representation:$cap", System.currentTimeMillis() + 120_000, emptyMap(),
            if (low) "AAC" else "Opus", if (low) 139 else 251, 10_000, video, false, 512_000, "native", selectionCapBps = minOf(cap, 320_000))
        audioCache.put("$video:$cap:false", stream)
        return request.copy(audioUrl = YouTubeAudioStreamService.playbackUri(request.id, stream.url),
            bitrateKbps = stream.bitrateKbps, qualityBadge = stream.qualityBadge)
    }

    @Test fun freshPreferredYoutubeResolutionDoesNotRepeatMetadataOrAudioRequests() = runBlocking {
        var calls = 0
        val resolver = StreamResolver(resolveYouTube = { track, _, _, cap -> calls++; playable(track, cap) },
            searchYouTube = { error("Already have a video ID") }, searchStandard = { _, _ -> error("Disabled provider") })
        val first = resolver.resolve(song, policy.ceiling.audioQuality, sourcePolicy = policy)
        val second = resolver.resolve(song.copy(coverUrl = "better-cover", isLiked = true), policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(1, calls)
        assertEquals(first.audioUrl, second.audioUrl)
        assertEquals("better-cover", second.coverUrl)
        assertTrue(second.isLiked)
    }

    @Test fun expiredMissingFailedAndQualityChangedTransportsCannotBypassExtraction() = runBlocking {
        var calls = 0
        val resolver = StreamResolver(resolveYouTube = { track, _, _, cap -> calls++; playable(track, cap) })
        val first = resolver.resolve(song, policy.ceiling.audioQuality, sourcePolicy = policy)
        for ((key, stream) in audioCache.snapshot()) audioCache.put(key, stream.copy(expiresAt = 0))
        resolver.resolve(song, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(2, calls)
        audioCache.evictAll()
        resolver.resolve(song, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(3, calls)
        resolver.resolve(song, policy.ceiling.audioQuality, sourcePolicy = policy, forceRefresh = true, failedUrl = first.audioUrl)
        assertEquals(4, calls)
        val low = policy.copy(ceiling = AudioQualityCeiling.DATA_SAVER)
        val result = resolver.resolve(song, low.ceiling.audioQuality, sourcePolicy = low)
        assertEquals(5, calls)
        assertEquals(48, result.bitrateKbps)
    }

    @Test fun aCachedFallbackCannotSkipAHigherPriorityProviderThatIsNowAvailable() = runBlocking {
        var available = false
        var youtubeCalls = 0
        var jioCalls = 0
        val enabled = policy.copy(sources = PlaybackSource.builtIns)
        val resolver = StreamResolver(resolveYouTube = { track, _, _, cap -> youtubeCalls++; if (available) playable(track, cap) else null },
            searchStandard = { _, _ -> jioCalls++; listOf(song.copy(id = "online_match", audioUrl = "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN")) })
        assertEquals("JIOSAAVN", resolver.resolve(song, enabled.ceiling.audioQuality, sourcePolicy = enabled).source)
        available = true
        assertEquals("YouTube Music", resolver.resolve(song, enabled.ceiling.audioQuality, sourcePolicy = enabled).source)
        assertEquals(2, youtubeCalls)
        assertEquals(1, jioCalls)
    }

    @Test fun cachedArchiveVerificationIsReusedButAnExplicitRejectedUrlIsNot() = runBlocking {
        val selected = song.copy(id = "ia_album:track.flac", audioUrl = "https://archive.org/download/album/track.flac", source = "INTERNET_ARCHIVE", isLossless = true)
        val maximum = policy.copy(ceiling = AudioQualityCeiling.MAX, sources = PlaybackSource.builtIns)
        var verifications = 0
        val resolver = StreamResolver(verifyArchive = { verifications++; it }, supportsLossless = { true },
            resolveYouTube = { _, _, _, _ -> null }, searchYouTube = { emptyList() }, searchStandard = { _, _ -> emptyList() })
        resolver.resolve(selected, maximum.ceiling.audioQuality, sourcePolicy = maximum)
        resolver.resolve(selected, maximum.ceiling.audioQuality, sourcePolicy = maximum)
        assertEquals(1, verifications)
        try { resolver.resolve(selected, maximum.ceiling.audioQuality, sourcePolicy = maximum,
            rejectedUrls = setOf(selected.audioUrl)) }
        catch (_: com.example.data.repository.TrackUnavailableException) { }
        assertEquals(2, verifications)
    }
}
