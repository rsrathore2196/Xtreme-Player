package com.example

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.data.local.ActualPlaySnapshotRow
import com.example.data.local.ListeningEventEntity
import com.example.data.local.MusicDatabase
import com.example.data.model.MusicTrack
import com.example.data.repository.MusicRepository
import com.example.data.repository.StreamResolver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import com.example.playback.*
import com.example.service.NetworkAudioQualityPolicy
import com.example.ui.viewmodel.sameLocalHomeSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class P0PlaybackOwnershipTest {
    private val policy = PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = PlaybackSource.builtIns)
    @Test fun unresolvedCurrentItemsBelongToTheLazySourceUntilTheyArePrepared() {
        val item = MediaItem.Builder().setMediaId("yt_abcdefghijk").setUri("xtreme-youtube://audio/abcdefghijk/stream.audio").build()
        assertTrue(NetworkAudioQualityPolicy.awaitingResolution(item, policy, Player.STATE_BUFFERING))
        assertTrue(NetworkAudioQualityPolicy.awaitingResolution(item, policy, Player.STATE_IDLE))
        assertFalse(NetworkAudioQualityPolicy.awaitingResolution(item, policy, Player.STATE_READY))
        assertFalse(NetworkAudioQualityPolicy.awaitingResolution(item.buildUpon().setUri("file:///music.flac").build(), policy, Player.STATE_BUFFERING))
        val prepared = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(Bundle().apply {
            putString("source_policy_key", policy.key)
        }).build()).build()
        assertFalse(NetworkAudioQualityPolicy.awaitingResolution(prepared, policy, Player.STATE_BUFFERING))
        assertTrue(NetworkAudioQualityPolicy.awaitingResolution(prepared, policy.copy(ceiling = AudioQualityCeiling.HIGH), Player.STATE_BUFFERING))
    }
    @Test fun specificArchiveFailureSurvivesGenericFallbackAndOptionalPreparationEvents() {
        val id = "diagnostic-song"
        try {
            LosslessDiagnostics.record(id, LosslessDiagnostics.Reason.UNAUTHORIZED, "archive-license", "item=example: no explicit reuse license")
            LosslessDiagnostics.record(id, LosslessDiagnostics.Reason.NO_RESULTS, "fallback")
            LosslessDiagnostics.beginPreparation(id)
            LosslessDiagnostics.record(id, LosslessDiagnostics.Reason.PREBUFFER_FAILED, "prebuffer")
            assertEquals(LosslessDiagnostics.Reason.PREBUFFER_FAILED, LosslessDiagnostics.last(id)?.reason)
            assertEquals(LosslessDiagnostics.Reason.UNAUTHORIZED, LosslessDiagnostics.lastMeaningful(id)?.reason)
            assertEquals("archive-license", LosslessDiagnostics.lastMeaningful(id)?.stage)
        } finally { LosslessDiagnostics.clear(id) }
    }
    @Test fun homeIgnoresOnlyTheObsoleteBroadRevisionWhilePreservingEveryListeningChange() {
        val event = ListeningEventEntity(trackId = "song", playedDurationMs = 30_000, eventType = "ACTUAL_PLAY")
        val before = listOf(ActualPlaySnapshotRow(1, event, "old-broad", "artists:1", "recent:1", "representatives:1"))
        assertTrue(sameLocalHomeSnapshot(before, before.map { it.copy(inputRevision = "unrelated favorite") }))
        assertFalse(sameLocalHomeSnapshot(before, before.map { it.copy(event = event.copy(playedDurationMs = 60_000)) }))
        assertFalse(sameLocalHomeSnapshot(before, before.map { it.copy(artistMetadataRevision = "representatives:2") }))
        assertFalse(sameLocalHomeSnapshot(before, before.map { it.copy(trackMetadataRevision = "recent:2") }))
        assertFalse(sameLocalHomeSnapshot(before, before.map { it.copy(total = 2) }))
    }
    @Test fun optionalSeekCoverageFailureCannotBlacklistAnOtherwiseVerifiedArchiveRecording() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext<Application>())
        val repository = MusicRepository(db.musicDao())
        val track = MusicTrack("seek-check", "Song", "Artist", "Album", 180_000, "", "")
        val archive = track.copy(id = "ia_seek-check:song.flac", audioUrl = "https://archive.org/download/seek-check/song.flac",
            isLossless = true, source = "INTERNET_ARCHIVE")
        try {
            StreamResolver.cacheVerifiedLosslessMatch(track, archive)
            LosslessDiagnostics.record(track.id, LosslessDiagnostics.Reason.SEEK_UNAVAILABLE, "prebuffer", "No safe seek evidence")
            repository.rejectLosslessUpgrade(track, archive.audioUrl)
            assertEquals(archive.audioUrl, StreamResolver.getCachedLosslessMatch(track)?.audioUrl)
            assertFalse(StreamResolver.isLosslessUrlRejected(archive.audioUrl))
        } finally { db.close(); StreamResolver.invalidateLosslessMatch(track, archive.audioUrl); LosslessDiagnostics.clear(track.id) }
    }
}
