package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.data.repository.TrackUnavailableException
import com.example.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SourcePolicyRoutingTest {
    @Test fun upcomingVerificationExpiresAndInvalidatesOnSourcePolicyChange() {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH)
        val prepared = androidx.media3.common.MediaItem.Builder().setMediaId("queued").setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setExtras(android.os.Bundle().apply {
                putString("audio_policy_tier", policy.ceiling.audioQuality.name)
                putString("source_policy_key", policy.key); putLong("audio_policy_checked_at", 1_000L)
            }).build()).build()
        assertTrue(com.example.service.NetworkAudioQualityPolicy.preparedFor(prepared, policy, 1_001L))
        assertFalse(com.example.service.NetworkAudioQualityPolicy.preparedFor(prepared, policy.copy(enhancer = true), 1_001L))
        assertFalse(com.example.service.NetworkAudioQualityPolicy.preparedFor(prepared, policy, 301_001L))
        assertFalse(com.example.service.NetworkAudioQualityPolicy.preparedFor(prepared, policy.copy(online = false), 1_001L))
    }
    @Test fun upcomingSabrQualityChangeAllowsANativeLowBitrateCodecInsteadOfPinningOpus() {
        val item = androidx.media3.common.MediaItem.Builder().setMediaId("yt_abcdefghijk")
            .setUri("xtreme-sabr://audio/abcdefghijk?cap=320&codec=opus").build()
        val constrained = com.example.service.NetworkAudioQualityPolicy.constrain(item, 96_000)
        val uri = requireNotNull(constrained.localConfiguration).uri
        assertEquals("xtreme-youtube", uri.scheme)
        assertNull(uri.getQueryParameter("codec"))
        assertEquals("96000", uri.getQueryParameter("cap"))
    }

    @Test fun upcomingHlsQualityChangeCanReselectDirectLowBitrateAudio() {
        val item = androidx.media3.common.MediaItem.Builder().setMediaId("yt_abcdefghijk")
            .setUri("xtreme-youtube://audio/abcdefghijk/stream.m3u8?cap=320000&codec=opus&session=old")
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build()
        val constrained = com.example.service.NetworkAudioQualityPolicy.constrain(item, 96_000)
        val uri = requireNotNull(constrained.localConfiguration).uri
        assertEquals("stream.audio", uri.lastPathSegment)
        assertEquals("96000", uri.getQueryParameter("cap"))
        assertNull(uri.getQueryParameter("codec")); assertNull(uri.getQueryParameter("session"))
        assertNull(constrained.localConfiguration?.mimeType)
        assertEquals(item.mediaId, constrained.mediaId)
        assertSame(item, com.example.service.NetworkAudioQualityPolicy.constrain(item, 320_000))
    }

    @Test fun installedArchiveStreamRemainsUsableFromCacheOfflineWithoutAProviderLookup() = runBlocking {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, online = false)
        val resolver = StreamResolver(searchLossless = { error("Offline must not search Archive") },
            searchStandard = { _, _ -> error("Offline must not search Jio") },
            resolveYouTube = { _, _, _, _ -> error("Offline must not extract YouTube") })
        assertFalse(policy.allowsArchive)
        assertTrue(policy.acceptsRemoteTrack(archive))
        assertEquals(archive, resolver.resolve(archive, policy.ceiling.audioQuality, sourcePolicy = policy))
    }

    private val archiveFirst = listOf(PlaybackSource.archive, PlaybackSource.jio, PlaybackSource.youtube)
    private val song = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180_000, "cover", "",
        source = "YouTube Music", isLiked = true)
    private val jio = song.copy(id = "online_match", source = "JIOSAAVN",
        audioUrl = "https://aac.saavncdn.com/song_320.mp4", bitrateKbps = 320, qualityBadge = "AAC • 320 kbps")
    private val opus = song.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?codec=opus",
        bitrateKbps = 80, qualityBadge = "Opus • 80 kbps")
    private val archive = song.copy(id = "ia_album:song.flac", source = "INTERNET_ARCHIVE", isLossless = true,
        audioUrl = "https://archive.org/download/album/song.flac", qualityBadge = "FLAC • 16-bit/44.1 kHz")

    private fun resolver(standard: List<MusicTrack> = listOf(jio),
        verifiedArchive: MusicTrack? = archive, youtube: MusicTrack? = opus) = StreamResolver(
        searchStandard = { _, _ -> standard }, searchLossless = { emptyList() },
        fetchById = { _, _ -> standard.firstOrNull() }, searchYouTube = { listOf(song) },
        resolveYouTube = { request, _, _, _ -> youtube?.copy(id = request.id) },
        supportsLossless = { true },
        verifyArchive = { verifiedArchive })

    @Test fun cappedModesUseOnlyEligibleSourcesAndPreserveCatalogIdentityWithEnhancerOff() = runBlocking {
        for (ceiling in AudioQualityCeiling.entries.filter { it != AudioQualityCeiling.MAX }) {
            val result = resolver().resolve(song, ceiling.audioQuality,
                maxBitrateBps = ceiling.maxBitrateBps, sourcePolicy = PlaybackSourcePolicy(ceiling))
            assertEquals(song.id, result.id); assertEquals(song.title, result.title)
            assertEquals(song.coverUrl, result.coverUrl); assertTrue(result.isLiked)
            assertEquals(if (ceiling == AudioQualityCeiling.HIGH) "JIOSAAVN" else "YouTube Music", result.source)
            assertFalse(result.isLossless)
            assertEquals(if (ceiling == AudioQualityCeiling.HIGH) jio.audioUrl else opus.audioUrl, result.audioUrl)
        }
    }

    @Test fun enhancerKeepsYoutubeWhenHigherSourcesAreUnavailableWithinTheCeiling() = runBlocking {
        for (ceiling in AudioQualityCeiling.entries.filter { it != AudioQualityCeiling.MAX }) {
            val policy = PlaybackSourcePolicy(ceiling, enhancer = true)
            val result = resolver(standard = emptyList()).resolve(song, ceiling.audioQuality,
                maxBitrateBps = ceiling.maxBitrateBps, sourcePolicy = policy)
            assertEquals(opus.audioUrl, result.audioUrl)
            assertEquals("YouTube Music", result.source); assertFalse(result.isLossless)
            assertTrue(policy.acceptsRemoteTrack(result))
        }
    }

    @Test fun nativeYoutubeAacRemainsPlayableWhenOpusIsUnavailableAndJioIsDisabled() = runBlocking {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, true)
        val aac = opus.copy(audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000",
            qualityBadge = "AAC • 80 kbps")
        val result = resolver(standard = emptyList(), youtube = aac).resolve(song, policy.ceiling.audioQuality,
            maxBitrateBps = policy.ceiling.maxBitrateBps, sourcePolicy = policy)
        assertEquals("AAC • 80 kbps", result.qualityBadge)
        assertEquals("YouTube Music", result.source)
    }

    @Test fun enhancerOffStillUsesYoutubeWhenJioIsUnavailable() = runBlocking {
        val result = resolver(standard = emptyList()).resolve(song, AudioQuality.ULTRA_HD_320,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH,
                sources = listOf(PlaybackSource.jio.copy(enabled = false), PlaybackSource.youtube)))
        assertEquals("YouTube Music", result.source)
        assertEquals(opus.audioUrl, result.audioUrl)
    }

    @Test fun wrongJioRecordingCannotMakeAYoutubeResultPlayable() = runBlocking {
        for (wrong in listOf(jio.copy(title = "Song (Live)"), jio.copy(artist = "Different Artist"),
            jio.copy(title = "Song (Remix)"), jio.copy(durationMs = 240_000))) {
            try {
                resolver(standard = listOf(wrong), youtube = null).resolve(song, AudioQuality.ULTRA_HD_320,
                    sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH))
                fail("Conflicting recording must be unavailable")
            } catch (_: TrackUnavailableException) { }
        }
    }

    @Test fun maximumIgnoresEnhancerAndResolvesSelectedArchiveFileDirectly() = runBlocking {
        for (enhancer in listOf(false, true)) {
            val result = resolver(standard = emptyList()).resolve(archive, AudioQuality.HI_RES_LOSSLESS,
                sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, enhancer, sources = archiveFirst))
            assertEquals(archive.audioUrl, result.audioUrl); assertEquals(archive.id, result.id)
            assertTrue(result.isLossless); assertEquals("INTERNET_ARCHIVE", result.source)
        }
    }

    @Test fun unverifiedArchiveFileFallsBackOnlyToTheSameRecording() = runBlocking {
        val result = resolver(verifiedArchive = null).resolve(archive, AudioQuality.HI_RES_LOSSLESS,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = archiveFirst))
        assertEquals(archive.id, result.id); assertEquals(jio.audioUrl, result.audioUrl); assertFalse(result.isLossless)
    }

    @Test fun savedAddonTransportCannotBypassTheOrdinaryResolverAfterAddonRoutingIsDisabled() = runBlocking {
        val saved = jio.copy(audioUrl = "https://personal.example/song.flac", source = "Streaming Addon: Personal")
        val result = resolver().resolve(saved, AudioQuality.HI_RES_LOSSLESS,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = archiveFirst))
        assertEquals(jio.audioUrl, result.audioUrl)
        assertFalse(result.source.startsWith("Streaming Addon:"))
    }

    @Test fun temporaryArchiveFallbackCacheCannotOverrideTheNextExplicitFileSelection() = runBlocking {
        var available = false
        var verifications = 0
        val resolver = StreamResolver(searchStandard = { _, _ -> listOf(jio) }, fetchById = { _, _ -> jio },
            supportsLossless = { true }, verifyArchive = { requested ->
                verifications++; requested.takeIf { available }
            },
        searchYouTube = { emptyList() }, resolveYouTube = { _, _, _, _ -> null })
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = archiveFirst)
        val fallback = resolver.resolve(archive, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(jio.audioUrl, fallback.audioUrl)
        available = true
        val direct = resolver.resolve(archive, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(archive.audioUrl, direct.audioUrl)
        assertEquals(archive.id, direct.id)
        assertEquals(archive.source, direct.source)
        assertTrue(direct.isLossless)
        assertEquals(2, verifications)
    }

    @Test fun selectedArchiveTransportWithACatalogIdBypassesCachedAac() = runBlocking {
        var verifications = 0
        val resolver = StreamResolver(searchStandard = { _, _ -> listOf(jio) }, fetchById = { _, _ -> jio },
            supportsLossless = { true }, verifyArchive = { verifications++; it },
        searchYouTube = { emptyList() }, resolveYouTube = { _, _, _, _ -> null })
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = archiveFirst)
        assertEquals(jio.audioUrl, resolver.resolve(jio, policy.ceiling.audioQuality, sourcePolicy = policy).audioUrl)
        val selected = archive.copy(id = jio.id)
        val direct = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
        assertEquals(selected.audioUrl, direct.audioUrl)
        assertEquals(selected.id, direct.id)
        assertEquals(selected.coverUrl, direct.coverUrl)
        assertTrue(direct.isLossless)
        assertEquals(1, verifications)
    }

    @Test fun aDifferentArchiveFileCannotReplaceTheExplicitlySelectedFile() = runBlocking {
        val other = archive.copy(audioUrl = "https://archive.org/download/other/song.flac")
        val result = resolver(verifiedArchive = other).resolve(archive, AudioQuality.HI_RES_LOSSLESS,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = archiveFirst))
        assertEquals(jio.audioUrl, result.audioUrl)
        assertFalse(result.isLossless)
    }

    @Test fun maximumFallsBackToJioBeforeYoutubeRegardlessOfEnhancer() = runBlocking {
        val aac = opus.copy(qualityBadge = "AAC • 128 kbps", bitrateKbps = 128)
        for (enhancer in listOf(false, true)) {
            val result = resolver(youtube = aac).resolve(song, AudioQuality.HI_RES_LOSSLESS,
                sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, enhancer, sources = archiveFirst))
            assertEquals(jio.audioUrl, result.audioUrl); assertEquals("AAC • 320 kbps", result.qualityBadge)
        }
    }

    @Test fun cancellationOfNativeYoutubeLookupDoesNotStartFallbackOrCacheAStream() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val resolver = StreamResolver(searchStandard = { _, _ -> error("Cancellation is not unavailability") },
            resolveYouTube = { _, _, _, _ -> started.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } },
            searchYouTube = { emptyList() })
        val lookup = async { resolver.resolve(song, AudioQuality.ULTRA_HD_320,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, true,
                sources = PlaybackSource.newInstallDefaults)) }
        withTimeout(3_000) { started.await() }
        lookup.cancelAndJoin()
        assertTrue(stopped.isCompleted); assertEquals(0, resolver.cacheSize)
    }

    @Test fun temporaryPrebufferFailuresKeepTheirSpecificReasonAndShortRejection() {
        for (reason in listOf(LosslessDiagnostics.Reason.NETWORK_TIMEOUT, LosslessDiagnostics.Reason.METADATA_INCOMPLETE,
            LosslessDiagnostics.Reason.BUFFER_COVERAGE, LosslessDiagnostics.Reason.PREBUFFER_FAILED)) {
            LosslessDiagnostics.record(song.id, reason, "prebuffer")
            LosslessDiagnostics.prebufferRejected(song.id, false)
            assertEquals(reason, LosslessDiagnostics.last(song.id)?.reason)
            assertEquals(10_000L, LosslessDiagnostics.rejectionTtlMs(reason))
        }
        LosslessDiagnostics.prebufferRejected(song.id, true)
        assertEquals(LosslessDiagnostics.Reason.BUFFER_COVERAGE, LosslessDiagnostics.last(song.id)?.reason)
        assertEquals(300_000L, LosslessDiagnostics.rejectionTtlMs(LosslessDiagnostics.Reason.INVALID_FORMAT))
    }

    @Test fun targetedArchiveDiscoveryDoesNotRequireAnAlbumLevelFlacIndex() {
        val query = com.example.data.remote.InternetArchiveApiService.searchExpression("creator:\"artist\"", targeted = true)
        assertTrue(query.contains("mediatype:audio")); assertTrue(query.contains("mediatype:etree"))
        assertFalse(query.contains("format:"))
    }

    @Test fun smallEmbeddedTitleTypoDoesNotRequireIdenticalAlbumRelease() {
        val original = song.copy(title = "The Beautiful Song", album = "Original Album")
        val tagged = archive.copy(title = "The Beautifull Song", album = "Compilation", durationMs = 181_000)
        assertNotNull(com.example.data.repository.RecordingIdentity.losslessConfidence(original, tagged))
        assertNull(com.example.data.repository.RecordingIdentity.losslessConfidence(original, tagged.copy(title = "The Beautiful Song (Live)")))
    }

    @Test fun anOfflineSourceNeverTriggersProviderSearchOrExtraction() = runBlocking {
        val resolver = StreamResolver(searchStandard = { _, _ -> error("Offline discovery") },
            searchLossless = { error("Offline Archive lookup") },
        searchYouTube = { emptyList() }, resolveYouTube = { _, _, _, _ -> null })
        val result = resolver.resolve(jio, AudioQuality.ULTRA_HD_320,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, online = false))
        assertEquals(jio.audioUrl, result.audioUrl)
    }
}
