package com.example.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.data.model.MusicTrack

internal val MediaItem.playlistOccurrenceId: Long
    get() = mediaMetadata.extras?.getLong("playlist_entry_id", 0L) ?: 0L

internal val MediaItem.queueIdentity: String
    get() = playlistOccurrenceId.takeIf { it > 0L }?.let { "entry:$it" } ?: mediaId

/** The same recording in a saved playlist is a different selection from its catalog/queue copy. */
internal fun matchesQueueSelection(track: MusicTrack?, item: MediaItem?): Boolean =
    track != null && item != null && track.id == item.mediaId && track.playlistEntryId == item.playlistOccurrenceId

/** A delayed ENDED callback must not advance a newer item which is still preparing. */
internal fun completedQueueSelection(track: MusicTrack?, item: MediaItem?, nativeState: Int, selectionPending: Boolean): Boolean =
    !selectionPending && nativeState == Player.STATE_ENDED && matchesQueueSelection(track, item)
