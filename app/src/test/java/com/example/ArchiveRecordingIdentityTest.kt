package com.example

import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import com.example.data.repository.StreamResolver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveRecordingIdentityTest {
    private val selected = MusicTrack("online_song", "Song (Official Audio)", "Artist", "Album", 180000, "", "")
    private val flac = selected.copy(id = "ia_album:song.flac", title = "Song", source = "INTERNET_ARCHIVE",
        audioUrl = "https://archive.org/download/album/song.flac", isLossless = true)
    @Test fun strongIdentityAllowsSmallDurationAndReleaseMetadataDifferences() {
        assertNotNull(RecordingIdentity.losslessConfidence(selected, flac.copy(album = "Greatest Hits", durationMs = 184500)))
        assertNull(RecordingIdentity.losslessConfidence(selected, flac.copy(durationMs = 185001)))
        assertNotNull(RecordingIdentity.losslessConfidence(selected, flac.copy(artist = "Unknown Artist")))
        assertNull(RecordingIdentity.losslessConfidence(selected, flac.copy(artist = "Unknown Artist", album = "")))
    }
    @Test fun verifiedIsrcCanReplaceMissingEvidenceButCannotOverrideConflictingKnownIdentity() {
        val request = selected.copy(isrc = "USABC2000001")
        assertNotNull(RecordingIdentity.losslessConfidence(request, flac.copy(isrc = request.isrc, durationMs = 0)))
        assertNull(RecordingIdentity.losslessConfidence(request, flac.copy(isrc = "USABC2000002")))
        assertNull(RecordingIdentity.losslessConfidence(request, flac.copy(isrc = request.isrc, artist = "Other Artist")))
        assertNull(RecordingIdentity.losslessConfidence(request, flac.copy(isrc = request.isrc, title = "Song (Live)")))
    }
    @Test fun unsupportedVersionsSourcesAndContainersAreRejected() {
        for (version in listOf("Live", "Remix", "Cover", "Karaoke", "Instrumental", "Remastered"))
            assertFalse(StreamResolver.matchesLosslessUpgrade(selected, flac.copy(title = "Song ($version)")))
        assertFalse(StreamResolver.matchesLosslessUpgrade(selected, flac.copy(album = "Album (Remastered)")))
        assertFalse(StreamResolver.matchesLosslessUpgrade(selected, flac.copy(audioUrl = "https://other.example/song.flac")))
        assertFalse(StreamResolver.matchesLosslessUpgrade(selected, flac.copy(audioUrl = "https://archive.org/download/album/song.mp3")))
    }
}
