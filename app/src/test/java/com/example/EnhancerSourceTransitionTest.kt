package com.example

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.*
import com.example.service.PlaybackSessionStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EnhancerSourceTransitionTest {
    private val video = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180000, "cover",
        "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000&itag=251", bitrateKbps = 146,
        qualityBadge = "Opus • 146 kbps", source = "YouTube Music", isLiked = true)
    private val jio = video.copy(id = "online_match", audioUrl = "https://aac.saavncdn.com/song_320.mp4",
        source = "JIOSAAVN", bitrateKbps = 320, qualityBadge = "AAC • 320 kbps")
    private val archive = video.copy(id = "ia_album:song.flac", audioUrl = "https://archive.org/download/album/song.flac",
        source = "INTERNET_ARCHIVE", isLossless = true, bitrateKbps = 900, qualityBadge = "FLAC")
    @Before fun clear() { StreamResolver.clearVerifiedLosslessCache() }
    @After fun finish() { StreamResolver.clearVerifiedLosslessCache() }
    private fun resolver() = StreamResolver(searchStandard = { _, _ -> listOf(jio) }, searchLossless = { listOf(archive) },
        supportsLossless = { true }, resolveYouTube = { track, _, _, _ -> video.copy(id = track.id) })

    @Test fun highEnhancerUsesAnEligibleHigherSourceAndOffTargetsTheOriginalYoutubeRecording() = runBlocking {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, enhancer = true)
        val origin = SourceTransitionOrigin.capture(video, policy)!!
        val resolver = resolver()
        val upgraded = resolver.resolve(video, policy.ceiling.audioQuality, sourcePolicy = policy, manualUpgrade = true)
        assertEquals("JIOSAAVN", upgraded.source); assertEquals(320, upgraded.bitrateKbps)
        assertEquals(video.id, upgraded.id); assertEquals(video.coverUrl, upgraded.coverUrl)
        // Restore is an explicit provider target, even if Jio is first in the normal list.
        val off = policy.copy(enhancer = false, sources = listOf(PlaybackSource.jio, PlaybackSource.youtube, PlaybackSource.archive))
        val restored = resolver.resolve(origin.track, off.ceiling.audioQuality, sourcePolicy = off,
            preferredSourceId = origin.sourceId)
        assertEquals(video.audioUrl, restored.audioUrl); assertEquals(video.id, restored.id)
        assertEquals("YouTube Music", restored.source); assertTrue(restored.isLiked)
    }

    @Test fun maximumAllowsExplicitArchiveEnhancementAndOffDoesNotSearchArchiveAgain() = runBlocking {
        var lookups = 0
        val resolver = StreamResolver(searchLossless = { lookups++; listOf(archive) }, supportsLossless = { true },
            searchStandard = { _, _ -> error("A verified Archive recording is available") },
            resolveYouTube = { track, _, _, _ -> video.copy(id = track.id) })
        val on = PlaybackSourcePolicy(AudioQualityCeiling.MAX, true, sources = PlaybackSource.builtIns)
        assertNotEquals(on.key, on.copy(enhancer = false).key)
        val origin = SourceTransitionOrigin.capture(video, on)!!
        val upgraded = resolver.resolve(video, on.ceiling.audioQuality, sourcePolicy = on, manualUpgrade = true)
        assertEquals("INTERNET_ARCHIVE", upgraded.source); assertTrue(upgraded.isLossless)
        val restored = resolver.resolve(origin.track, on.ceiling.audioQuality, sourcePolicy = on.copy(enhancer = false),
            preferredSourceId = origin.sourceId)
        assertEquals("YouTube Music", restored.source); assertFalse(restored.isLossless)
        assertEquals(1, lookups)
    }

    @Test fun turningOffCanRestoreAnOriginalJioProviderAfterAnArchiveUpgrade() = runBlocking {
        val playing = jio.copy(id = video.id)
        val on = PlaybackSourcePolicy(AudioQualityCeiling.MAX, true, sources = listOf(PlaybackSource.archive, PlaybackSource.jio, PlaybackSource.youtube))
        val origin = SourceTransitionOrigin.capture(playing, on)!!
        val resolver = resolver()
        assertTrue(resolver.resolve(playing, on.ceiling.audioQuality, sourcePolicy = on, manualUpgrade = true).isLossless)
        val restored = resolver.resolve(origin.track, on.ceiling.audioQuality, sourcePolicy = on.copy(enhancer = false),
            preferredSourceId = origin.sourceId)
        assertEquals(jio.audioUrl, restored.audioUrl)
        assertEquals("JIOSAAVN", restored.source); assertFalse(restored.isLossless)
        assertEquals(video.id, restored.id)
    }

    @Test fun enhancerWorksWithinLowerQualityLimitsWithoutContactingForbiddenProviders() = runBlocking {
        for (ceiling in listOf(AudioQualityCeiling.DATA_SAVER, AudioQualityCeiling.NORMAL)) {
            val resolver = StreamResolver(searchStandard = { _, _ -> error("Quality blocks Jio") },
                searchLossless = { error("Quality blocks Archive") },
                resolveYouTube = { track, _, _, cap -> track.copy(
                    audioUrl = "xtreme-youtube://audio/abcdefghijk/stream.audio?cap=$cap", bitrateKbps = 80, qualityBadge = "Opus • 80 kbps") })
            val playing = video.copy(bitrateKbps = 80, qualityBadge = "Opus • 80 kbps")
            val result = resolver.resolve(playing, ceiling.audioQuality, sourcePolicy = PlaybackSourcePolicy(ceiling, true), manualUpgrade = true)
            assertEquals("YouTube Music", result.source); assertFalse(result.isLossless)
            assertTrue(result.bitrateKbps * 1000 <= ceiling.maxBitrateBps)
        }
    }

    @Test fun unsuccessfulUpgradeKeepsTheExactAudibleSourceAndNeverResolvesYoutubeAgain() = runBlocking {
        var youtubeLookups = 0
        val resolver = StreamResolver(searchStandard = { _, _ -> emptyList() },
            resolveYouTube = { _, _, _, _ -> youtubeLookups++; error("The original keeps playing without a second extraction") })
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, enhancer = true)
        val unchanged = resolver.resolve(video, policy.ceiling.audioQuality, sourcePolicy = policy, manualUpgrade = true)
        assertEquals(video.audioUrl, unchanged.audioUrl)
        assertEquals(video.source, unchanged.source)
        assertEquals(video.id, unchanged.id)
        assertEquals(0, youtubeLookups)
    }

    @Test fun manualUpgradeAtTheHighestEligibleSourceCannotDowngradeToYoutube() = runBlocking {
        var lowerProviderCalls = 0
        val resolver = StreamResolver(searchStandard = { _, _ -> error("The current source does not need resolving again") },
            resolveYouTube = { _, _, _, _ -> lowerProviderCalls++; null })
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, enhancer = true)
        val unchanged = resolver.resolve(jio, policy.ceiling.audioQuality, sourcePolicy = policy, manualUpgrade = true)
        assertEquals(jio.audioUrl, unchanged.audioUrl)
        assertEquals(jio.bitrateKbps, unchanged.bitrateKbps)
        assertEquals(0, lowerProviderCalls)
    }

    @Test fun returningOneSongToYoutubeDoesNotPinTheNextSongOrChangeSourcePreferences() {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, returnToYouTube = true,
            requestTrackId = video.id, requestRevision = 1L)
        assertEquals(PlaybackSourceKind.YOUTUBE, policy.resolutionSourcesFor(video.id).first().kind)
        assertEquals(PlaybackSourceKind.INTERNET_ARCHIVE, policy.resolutionSourcesFor("yt_nexttrackid").first().kind)
        assertEquals(PlaybackSourceKind.INTERNET_ARCHIVE, policy.playbackSources.first().kind)
    }

    @Test fun reducedNetworkQualityUsesYoutubeWhenBothCurrentAndOriginalSourcesAreBlocked() {
        val standard = PlaybackSourcePolicy(AudioQualityCeiling.NORMAL)
        assertTrue(standard.shouldReturnToYouTube(archive, PlaybackSource.jio.id))
        assertTrue(standard.shouldReturnToYouTube(archive, PlaybackSource.archive.id))
        val maximum = PlaybackSourcePolicy(AudioQualityCeiling.MAX,
            sources = listOf(PlaybackSource.jio.copy(enabled = false), PlaybackSource.archive, PlaybackSource.youtube))
        assertFalse("A permitted current stream remains safe if its original source was disabled",
            maximum.shouldReturnToYouTube(archive, PlaybackSource.jio.id))
    }

    @Test fun cancelledServiceLookupCannotClearANewerControllerRequest() {
        try {
            AudioSignalPath.source(video.id, "YouTube Music", PlaybackSource.youtube.id, uri = video.audioUrl)
            AudioSignalPath.sourceRequest(video.id, true, "service:1")
            AudioSignalPath.sourceRequest(video.id, true, "controller:1")
            AudioSignalPath.sourceRequest(video.id, false, "service:1")
            assertTrue(AudioSignalPath.state.value.sourceTransitionInProgress)
            AudioSignalPath.sourceRequest("another-track", false, "controller:1")
            assertTrue(AudioSignalPath.state.value.sourceTransitionInProgress)
            AudioSignalPath.sourceRequest(video.id, false, "controller:1")
            assertFalse(AudioSignalPath.state.value.sourceTransitionInProgress)
        } finally { AudioSignalPath.reset() }
    }

    @Test fun verifiedFallbackAboveYoutubeCanRevertEvenWhenAnEarlierSourceHadNoMatch() {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX)
        val actualJio = SignalPathState(trackId = video.id, sourceId = PlaybackSource.jio.id,
            streamUri = jio.audioUrl, quality = StreamQualityBadge("AAC", 320, 44100, verified = true))
        assertTrue(policy.hasVerifiedUpgrade(actualJio))
        val returned = actualJio.copy(sourceId = PlaybackSource.youtube.id, streamUri = video.audioUrl,
            enhancerOriginalSourceId = PlaybackSource.jio.id, enhancerOriginalUri = jio.audioUrl,
            quality = StreamQualityBadge("OPUS", 146, 48000, verified = true))
        assertFalse("A quality-enforced return to YouTube is not an upgrade", policy.hasVerifiedUpgrade(returned))
    }

    @Test fun nominal320AacVbrOverheadDoesNotTriggerUpgradeRollbackOrWeakenLowerQualityLimits() {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH)
        val actual = jio.copy(bitrateKbps = 329, qualityBadge = "AAC • 329 kbps")
        assertTrue(policy.acceptsRemoteTrack(actual))
        assertTrue(policy.acceptsStreamBitrate(329, android.net.Uri.parse(jio.audioUrl)))
        assertTrue(policy.hasVerifiedUpgrade(SignalPathState(trackId = video.id, sourceId = PlaybackSource.jio.id,
            streamUri = jio.audioUrl, quality = StreamQualityBadge("AAC", 329, 44100, verified = true))))
        assertFalse(policy.acceptsRemoteTrack(actual.copy(bitrateKbps = 400)))
        assertFalse(policy.acceptsRemoteTrack(actual.copy(audioUrl = "https://other.example/song_320.mp4")))
        assertFalse(policy.acceptsStreamBitrate(329, android.net.Uri.parse(video.audioUrl)))
        assertFalse(policy.copy(ceiling = AudioQualityCeiling.NORMAL).acceptsRemoteTrack(actual))
        assertFalse(policy.copy(ceiling = AudioQualityCeiling.DATA_SAVER).acceptsStreamBitrate(97, android.net.Uri.parse(video.audioUrl)))
        assertFalse(policy.copy(ceiling = AudioQualityCeiling.DATA_SAVER).acceptsRemoteTrack(actual))
        assertEquals(329, actual.bitrateKbps)
    }

    @Test fun originalTransportBookmarkCannotChangeArtworkOrBeAppliedToAnotherTrack() {
        val origin = SourceTransitionOrigin.capture(video, PlaybackSourcePolicy(AudioQualityCeiling.HIGH))!!
        val extras = Bundle().apply { SourceTransitionOrigin.write(this, origin) }
        val item = MediaItem.Builder().setMediaId(video.id).setUri(archive.audioUrl)
            .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).build()
        val current = video.copy(audioUrl = archive.audioUrl, coverUrl = "new-high-quality-art", isLossless = true)
        val restored = SourceTransitionOrigin.read(item, current)!!
        assertEquals(video.audioUrl, restored.track.audioUrl)
        assertEquals("new-high-quality-art", restored.track.coverUrl)
        assertFalse(restored.track.isLossless)
        assertNull(SourceTransitionOrigin.read(item, current.copy(id = "yt_zyxwvutsrqp")))
        SourceTransitionOrigin.write(extras, null)
        assertTrue(extras.isEmpty)
    }

    @Config(sdk = [28])
    @Test fun sessionDiskCheckpointKeepsTheOriginalProviderAndPosition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val origin = SourceTransitionOrigin.capture(video, PlaybackSourcePolicy(AudioQualityCeiling.MAX))!!
        val item = MediaItem.Builder().setMediaId(video.id).setUri(jio.audioUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(video.title).setArtist(video.artist).setExtras(
                Bundle().apply { SourceTransitionOrigin.write(this, origin) }).build()).build()
        PlaybackSessionStore.checkpoint(app, PlaybackSessionStore.Snapshot(listOf(item), 0, 47890))
        withTimeout(5000) { while (true) {
            val complete = withContext(Dispatchers.IO) { runCatching {
                val saved = PlaybackSessionStore::class.java.getDeclaredField("file").apply { isAccessible = true }
                    .get(null) as? android.util.AtomicFile ?: return@runCatching false
                val root = JSONObject(saved.baseFile.readText())
                root.optLong("position") == 47890L && root.getJSONArray("queue").getJSONObject(0).has("enhancerOrigin")
            }.getOrDefault(false) }
            if (complete) break
            delay(10)
        } }
        PlaybackSessionStore::class.java.getDeclaredField("latest").apply { isAccessible = true }.set(null, null)
        val restored = withTimeout(5000) { PlaybackSessionStore.restore(app) }!!
        assertEquals(47890L, restored.positionMs); assertEquals(0, restored.index)
        assertEquals(video.audioUrl, SourceTransitionOrigin.read(restored.items.single(), video)!!.track.audioUrl)
    }
    @Test fun verifiedTopSourceIsAutomaticallyEnhancedButCatalogIdentityAndPendingFormatsAreNot() {
        val ordered = listOf(PlaybackSource.jio, PlaybackSource.youtube, PlaybackSource.archive)
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = ordered)
        val playing = SignalPathState(trackId = video.id, source = "JioSaavn", sourceId = PlaybackSource.jio.id,
            quality = StreamQualityBadge("AAC", 320, 44100, verified = true))
        assertTrue(policy.automaticallyEnhanced(playing))
        assertFalse(policy.automaticallyEnhanced(playing.copy(quality = playing.quality.copy(verified = false))))
        assertFalse(policy.automaticallyEnhanced(playing.copy(sourceId = PlaybackSource.youtube.id)))
        assertFalse(policy.copy(ceiling = AudioQualityCeiling.NORMAL).automaticallyEnhanced(playing))
        assertTrue("Requesting a return must not pretend the source already changed",
            policy.copy(returnToYouTube = true).automaticallyEnhanced(playing))
        assertEquals(PlaybackSource.jio.id, SourceTransitionOrigin.sourceId(video.id,
            android.net.Uri.parse(jio.audioUrl), policy))
    }

    @Test fun automaticEnhancementOffExplicitlyRestoresYoutubeEvenWithAnOriginallyOffSavedToggle() = runBlocking {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.HIGH, sources = listOf(PlaybackSource.jio, PlaybackSource.youtube))
        val off = policy.copy(returnToYouTube = true)
        assertFalse(off.enhancer)
        assertNotEquals(policy.key, off.key)
        assertEquals(PlaybackSourceKind.YOUTUBE, off.resolutionSources.first().kind)
        val playing = jio.copy(id = video.id)
        val result = resolver().resolve(playing, off.ceiling.audioQuality, sourcePolicy = off,
            preferredSourceId = PlaybackSource.youtube.id)
        assertEquals("YouTube Music", result.source)
        assertEquals(video.id, result.id)
        assertEquals(video.audioUrl, result.audioUrl)
    }

    @Test fun explicitArchiveEnhancementCanFinishBeyondTheInitialPlaybackLookupDeadline() = runBlocking {
        val resolver = StreamResolver(searchLossless = { delay(StreamResolver.LOSSLESS_LOOKUP_TIMEOUT_MS + 100); listOf(archive) },
            supportsLossless = { true }, searchStandard = { _, _ -> error("The licensed exact recording is available") },
            resolveYouTube = { _, _, _, _ -> error("Do not downgrade a verified enhancement") })
        val currentUri = video.audioUrl
        val result = resolver.resolve(video, AudioQuality.HI_RES_LOSSLESS,
            sourcePolicy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, true, sources = listOf(PlaybackSource.archive, PlaybackSource.jio, PlaybackSource.youtube)), manualUpgrade = true)
        assertTrue(result.isLossless); assertEquals(archive.audioUrl, result.audioUrl)
        assertEquals(currentUri, video.audioUrl)
    }

    @Test fun automaticArchiveOffReturnsToYoutubeAndCannotMistakeAnAacDecoderForLossless() = runBlocking {
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = listOf(PlaybackSource.archive, PlaybackSource.jio, PlaybackSource.youtube))
        val signal = SignalPathState(trackId = archive.id, sourceId = PlaybackSource.archive.id,
            quality = StreamQualityBadge("FLAC", 900, 44100, 16, lossless = true, verified = true))
        assertTrue(policy.automaticallyEnhanced(signal))
        assertFalse(policy.automaticallyEnhanced(signal.copy(quality = StreamQualityBadge("AAC", 320, verified = true))))
        val resolver = StreamResolver(searchLossless = { error("An explicit return to YouTube must not search Archive") },
            searchStandard = { _, _ -> error("An explicit return to YouTube must not select Jio") },
            searchYouTube = { listOf(video) }, resolveYouTube = { request, _, _, _ -> video.copy(id = request.id) })
        val off = policy.copy(returnToYouTube = true)
        val result = resolver.resolve(archive, off.ceiling.audioQuality, sourcePolicy = off,
            preferredSourceId = PlaybackSource.youtube.id)
        assertEquals(archive.id, result.id)
        assertEquals(video.audioUrl, result.audioUrl)
        assertEquals("YouTube Music", result.source); assertFalse(result.isLossless)
    }

}
