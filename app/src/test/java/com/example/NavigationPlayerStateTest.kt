package com.example

import com.example.data.model.MusicTrack
import com.example.playback.PlayerUiState
import com.example.ui.viewmodel.navigationPlayerSnapshot
import org.junit.Assert.*
import org.junit.Test

class NavigationPlayerStateTest {
    private val track = MusicTrack("one", "Song", "Artist", "Album", 180_000, "cover", "audio")
    @Test fun queueProgressAndDecoderUpdatesDoNotChangeNavigationState() {
        val state = PlayerUiState(currentTrack = track, isPlaying = true)
        val changed = state.copy(queue = List(1000) { track.copy(id = "track-$it") },
            currentPositionMs = 2500, bufferedPositionMs = 9000, qualityBadge = "FLAC", sessionMemoryCount = 200)
        assertEquals(navigationPlayerSnapshot(state), navigationPlayerSnapshot(changed))
    }
    @Test fun transportAndTrackChangesRemainImmediatelyObservable() {
        val state = PlayerUiState(currentTrack = track)
        assertNotEquals(navigationPlayerSnapshot(state), navigationPlayerSnapshot(state.copy(isPlaying = true)))
        assertNotEquals(navigationPlayerSnapshot(state), navigationPlayerSnapshot(state.copy(isLoading = true)))
        assertNotEquals(navigationPlayerSnapshot(state), navigationPlayerSnapshot(state.copy(currentTrack = track.copy(id = "two"))))
    }
}
