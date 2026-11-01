package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService.LosslessSearchPage
import com.example.data.repository.StreamResolver
import com.example.data.repository.TrackUnavailableException
import com.example.playback.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveLookupLifecycleTest {
    private val selected = MusicTrack("online_song", "Song", "Artist", "Album", 180_000, "cover",
        "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN", bitrateKbps = 320)
    private val archive = selected.copy(id = "ia_album:song.flac", audioUrl = "https://archive.org/download/album/song.flac",
        source = "INTERNET_ARCHIVE", isLossless = true, qualityBadge = "Lossless")
    private val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = PlaybackSource.builtIns)
    @After fun reset() { StreamResolver.clearVerifiedLosslessCache() }

    @Test fun audibleFallbackDoesNotCancelOrRepeatThePendingVerifiedLookup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ready = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        var calls = 0; var cancelled = false
        val resolver = StreamResolver(ioDispatcher = Dispatchers.IO, lookupScope = scope,
            supportsLossless = { true }, resolveYouTube = { _, _, _, _ -> null }, findLossless = { _, _, _ ->
                calls++; entered.complete(Unit)
                try { ready.await(); LosslessSearchPage(listOf(archive)) }
                catch (e: CancellationException) { cancelled = true; throw e }
            })
        try {
            val fallback = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
            entered.await()
            assertEquals(selected.audioUrl, fallback.audioUrl); assertFalse(cancelled)
            val upgrade = async { resolver.discoverLosslessBackground(fallback, policyKey = policy.key) }
            ready.complete(Unit)
            assertEquals(archive.audioUrl, upgrade.await()?.audioUrl)
            assertEquals(1, calls); assertFalse(cancelled)
            assertEquals(archive.audioUrl, StreamResolver.getCachedLosslessMatch(selected)?.audioUrl)
            // Transport selection retains the catalog identity used by Media3 and queue guards.
            val resolved = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy)
            assertEquals(selected.id, resolved.id); assertEquals(archive.audioUrl, resolved.audioUrl)
            assertTrue(policy.allowsAutomaticReplacement(fallback, archive.copy(id = fallback.id)))
            assertEquals(1, calls)
        } finally { resolver.clear(); scope.cancel() }
    }
    @Test fun initialTimeoutDoesNotDiscardALongerSuccessfulVerification() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var calls = 0
        val resolver = StreamResolver(ioDispatcher = Dispatchers.IO, lookupScope = scope,
            supportsLossless = { true }, resolveYouTube = { _, _, _, _ -> null }, findLossless = { _, _, _ ->
                calls++; delay(5_000); LosslessSearchPage(listOf(archive))
            })
        try {
            // A separate short-lived waiter may time out without owning the producer.
            withTimeoutOrNull(3_000) { resolver.discoverLosslessBackground(selected, policyKey = policy.key) }
            val found = resolver.discoverLosslessBackground(selected, policyKey = policy.key)
            assertEquals(archive.audioUrl, found?.audioUrl); assertEquals(1, calls)
        } finally { resolver.clear(); scope.cancel() }
    }
    @Test fun changingTracksCancelsRetainedWorkAndAllowsALaterFreshAttempt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val resolver = StreamResolver(ioDispatcher = Dispatchers.IO, lookupScope = scope,
            supportsLossless = { true }, resolveYouTube = { _, _, _, _ -> null }, findLossless = { _, _, _ ->
                calls++
                if (calls == 1) { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
                LosslessSearchPage(listOf(archive))
            })
        try {
            resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy); entered.await()
            resolver.cancelLosslessDiscovery(selected.id); cancelled.await()
            assertNull(StreamResolver.getCachedLosslessMatch(selected))
            assertEquals(archive.audioUrl, resolver.discoverLosslessBackground(selected, policyKey = policy.key)?.audioUrl)
            assertEquals(2, calls)
        } finally { resolver.clear(); scope.cancel() }
    }
    @Test fun policyChangesCannotCacheOrActivateAStaleLookup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var allowed = true
        val resolver = StreamResolver(ioDispatcher = Dispatchers.IO, lookupScope = scope,
            supportsLossless = { true }, findLossless = { _, _, _ -> entered.complete(Unit); release.await(); LosslessSearchPage(listOf(archive)) })
        try {
            val request = async { resolver.discoverLosslessBackground(selected, allowed = { allowed }, policyKey = policy.key) }
            entered.await(); allowed = false; release.complete(Unit)
            assertNull(request.await()); assertNull(StreamResolver.getCachedLosslessMatch(selected))
        } finally { resolver.clear(); scope.cancel() }
    }
    @Test fun archiveRecoveryCanUseAnotherVerifiedFileButNeverTheRejectedUrl() = runBlocking {
        val other = archive.copy(id = "ia_other:song.flac", audioUrl = "https://archive.org/download/other/song.flac")
        var queries = 0
        val resolver = StreamResolver(ioDispatcher = Dispatchers.IO, supportsLossless = { true },
            findLossless = { _, excluded, _ -> queries++; assertTrue(archive.audioUrl in excluded); LosslessSearchPage(listOf(archive, other)) },
            resolveYouTube = { _, _, _, _ -> error("Same-source recovery cannot query YouTube") })
        val result = resolver.resolve(archive, policy.ceiling.audioQuality, forceRefresh = true,
            currentSourceOnly = true, failedUrl = archive.audioUrl, sourcePolicy = policy)
        assertEquals(archive.id, result.id); assertEquals(other.audioUrl, result.audioUrl); assertTrue(result.isLossless); assertEquals(1, queries)
    }
    @Test fun rejectedArchiveRecoveryFallsThroughInsteadOfReplayingTheFailedFile() = runBlocking {
        val resolver = StreamResolver(ioDispatcher = Dispatchers.IO, supportsLossless = { true },
            findLossless = { _, _, _ -> LosslessSearchPage(listOf(archive)) })
        try {
            resolver.resolve(archive, policy.ceiling.audioQuality, forceRefresh = true,
                currentSourceOnly = true, failedUrl = archive.audioUrl, sourcePolicy = policy)
            fail("The failed URL must remain rejected")
        } catch (_: TrackUnavailableException) { }
    }
    @Test fun incompleteAbsenceIsRetryableAndCannotPoisonTheNegativeCache() = runBlocking {
        var calls = 0
        val resolver = StreamResolver(ioDispatcher = Dispatchers.IO, supportsLossless = { true },
            findLossless = { _, _, _ ->
                if (++calls == 1) LosslessSearchPage(exhaustive = false, failure = LosslessDiagnostics.Reason.NO_RESULTS)
                else LosslessSearchPage(listOf(archive))
            })
        assertNull(resolver.discoverLosslessBackground(selected))
        assertEquals(LosslessDiagnostics.Reason.METADATA_INCOMPLETE, LosslessDiagnostics.last(selected.id)?.reason)
        assertEquals(archive.audioUrl, resolver.discoverLosslessBackground(selected)?.audioUrl); assertEquals(2, calls)
    }
    @Test fun slowArchiveVerificationCanStartPlaybackWhenEveryLossyFallbackIsUnavailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var calls = 0
        val resolver = StreamResolver(lookupScope = scope, supportsLossless = { true },
            searchStandard = { _, _ -> emptyList() }, fetchById = { _, _ -> null }, searchYouTube = { emptyList() },
            resolveYouTube = { _, _, _, _ -> null }, findLossless = { _, _, _ ->
                calls++; delay(3_500); LosslessSearchPage(listOf(archive))
            })
        try {
            val resolved = withTimeout(8_000) { resolver.resolve(selected.copy(audioUrl = ""), policy.ceiling.audioQuality, sourcePolicy = policy) }
            assertEquals(archive.audioUrl, resolved.audioUrl); assertEquals(selected.id, resolved.id)
            assertTrue(resolved.isLossless); assertEquals(1, calls)
        } finally { resolver.clear(); scope.cancel() }
    }
    @Test fun speculativeNextTrackPreparationDoesNotRetainOptionalDiscoveryAfterItsWaiterEnds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cancelled = CompletableDeferred<Unit>()
        val resolver = StreamResolver(lookupScope = scope, supportsLossless = { true }, resolveYouTube = { _, _, _, _ -> null },
            findLossless = { _, _, _ -> try { awaitCancellation() } finally { cancelled.complete(Unit) } })
        try {
            val result = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy, retainLosslessLookup = false)
            assertEquals(selected.audioUrl, result.audioUrl)
            withTimeout(2_000) { cancelled.await() }
            assertFalse(resolver.hasPendingLosslessDiscovery(selected.id, policy.key))
        } finally { resolver.clear(); scope.cancel() }
    }

    @Test fun aCancelledResolutionCannotStartASecondLateArchiveLookup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var calls = 0
        val resolver = StreamResolver(lookupScope = scope, supportsLossless = { true }, resolveYouTube = { _, _, _, _ -> null },
            findLossless = { _, _, _ -> calls++; LosslessSearchPage(listOf(archive)) })
        try {
            val old = resolver.losslessDiscoveryVersion(selected.id)
            resolver.cancelLosslessDiscovery(selected.id)
            val result = resolver.resolve(selected, policy.ceiling.audioQuality, sourcePolicy = policy, losslessVersion = old)
            assertEquals(selected.audioUrl, result.audioUrl); assertEquals(0, calls)
            assertFalse(resolver.hasPendingLosslessDiscovery(selected.id, policy.key))
            assertEquals(archive.audioUrl, resolver.discoverLosslessBackground(selected, policyKey = policy.key)?.audioUrl)
            assertEquals(1, calls)
        } finally { resolver.clear(); scope.cancel() }
    }
    @Test fun normalFallbackDoesNotVerifyAnArchiveUrlThatWasAlreadyRejected() = runBlocking {
        val resolver = StreamResolver(supportsLossless = { true }, verifyArchive = { error("A known rejected URL cannot consume the recovery budget") },
            fetchById = { _, _ -> selected }, searchStandard = { _, _ -> listOf(selected) }, resolveYouTube = { _, _, _, _ -> null })
        val result = resolver.resolve(archive, policy.ceiling.audioQuality, forceRefresh = true,
            failedUrl = archive.audioUrl, sourcePolicy = policy)
        assertEquals(selected.audioUrl, result.audioUrl); assertEquals(archive.id, result.id); assertFalse(result.isLossless)
    }

}
