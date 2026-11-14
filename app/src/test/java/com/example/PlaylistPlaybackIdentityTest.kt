package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.data.model.queueIdentity
import com.example.playback.matchesQueueSelection
import com.example.playback.completedQueueSelection
import com.example.playback.queueIdentity
import androidx.media3.common.Player
import com.example.service.PlaybackSessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaylistPlaybackIdentityTest {
    private val song = MusicTrack("yt_abcdefghijk", "Selected song", "Artist", "Album", 180_000, "", "")

    @Test fun lateCatalogAndOtherPlaylistCallbacksCannotAcknowledgeTheRequestedEntry() {
        val requested = song.copy(playlistEntryId = 42)
        assertFalse(matchesQueueSelection(requested, song.toMediaItem()))
        assertFalse(matchesQueueSelection(requested, song.copy(playlistEntryId = 41).toMediaItem()))
        assertFalse(matchesQueueSelection(requested, null))
        assertTrue(matchesQueueSelection(requested, requested.toMediaItem()))
        assertFalse(matchesQueueSelection(requested, requested.copy(id = "yt_lmnopqrstuv").toMediaItem()))
    }

    @Test fun queueReconciliationKeepsRepeatedPlaylistOccurrencesDistinct() {
        val occurrences = listOf(song.copy(playlistEntryId = 41), song.copy(playlistEntryId = 42), song)
        val known = occurrences.associateBy { it.queueIdentity }
        val items = occurrences.reversed().map { it.toMediaItem() }
        assertEquals(occurrences.reversed(), items.map { known[it.queueIdentity] })
        assertEquals(3, items.map { it.queueIdentity }.toSet().size)
    }

    @Test fun staleCompletionCannotSkipALoadingPlaylistSelection() {
        val selected = song.copy(playlistEntryId = 42)
        val current = selected.toMediaItem()
        for (state in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY)) {
            assertFalse("An old ENDED event cannot advance the new native selection",
                completedQueueSelection(selected, current, state, false))
        }
        assertFalse(completedQueueSelection(selected, current, Player.STATE_ENDED, true))
        assertFalse(completedQueueSelection(selected, song.toMediaItem(), Player.STATE_ENDED, false))
        assertTrue("Natural completion of the selected entry still advances normally",
            completedQueueSelection(selected, current, Player.STATE_ENDED, false))
    }

    @Test fun mixedDownloadedAndRemotePlaylistIsAlreadyUsableWithoutDatabaseReResolution() {
        val offline = song.copy(playlistEntryId = 42, audioUrl = "xtreme-offline://audio/download-key").toMediaItem()
        val remote = song.copy(id = "yt_lmnopqrstuv", playlistEntryId = 43).toMediaItem()
        assertTrue("A mixed playlist must use the service's immediate in-app queue path",
            listOf(offline, remote).all(PlaybackSessionStore::isReal))
        assertFalse(PlaybackSessionStore.isReal(song.toMediaItem().buildUpon().setUri("unknown://audio/no-track").build()))
    }
}
