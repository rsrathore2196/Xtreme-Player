package com.example

import com.example.data.model.MusicTrack
import com.example.downloads.*
import org.junit.Assert.*
import org.junit.Test

class CollectionDownloadProgressTest {
    private val tracks = (1..4).map { MusicTrack("$it", "Song $it", "Artist", "Album", 120_000, "", "https://example.test/$it") }

    @Test fun combinesSavedAndPartialTracksAndIgnoresDuplicatePlaylistEntries() {
        val records = tracks.mapIndexed { i, track -> track.id to DownloadRecord(track,
            if (i < 2) DownloadStatus.COMPLETE else DownloadStatus.DOWNLOADING,
            bytes = if (i == 2) 500 else 0, total = 1000) }.toMap()
        val progress = collectionDownloadProgress(tracks + tracks.first(), records)
        assertEquals(4, progress.total)
        assertEquals(2, progress.completed)
        assertEquals(.625f, progress.fraction!!, .001f)
        assertTrue(progress.active)
        assertFalse(progress.complete)
    }

    @Test fun pauseFailureAndResumeKeepTheAlreadyDownloadedShare() {
        for (status in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.QUEUED)) {
            val record = DownloadRecord(tracks.first(), status, bytes = 500, total = 1000)
            val progress = collectionDownloadProgress(tracks.take(1), mapOf(record.track.id to record))
            assertEquals(.5f, progress.fraction!!, .001f)
            assertFalse(progress.complete)
        }
    }

    @Test fun unknownSizesAreIndeterminateUntilATrackContributesKnownProgress() {
        val records = mapOf("1" to DownloadRecord(tracks.first(), DownloadStatus.DOWNLOADING))
        assertNull(collectionDownloadProgress(tracks, records).fraction)
        val progress = collectionDownloadProgress(tracks, records + ("2" to DownloadRecord(tracks[1], DownloadStatus.COMPLETE)))
        assertEquals(.25f, progress.fraction!!, .001f)
    }

    @Test fun aSavedVisiblePageCannotClaimThatTheWholeCollectionIsDownloaded() {
        val records = tracks.take(2).associate { it.id to DownloadRecord(it, DownloadStatus.COMPLETE) }
        val unknown = collectionDownloadProgress(tracks.take(2), records, collectionComplete = false)
        assertFalse(unknown.complete)
        assertNull(unknown.fraction)
        val known = collectionDownloadProgress(tracks.take(2), records, tracks.map { it.id }, collectionComplete = false)
        assertFalse(known.complete)
        assertEquals(4, known.total)
        assertEquals(.5f, known.fraction!!, .001f)
    }

    @Test fun removingALaterPageTrackKeepsTheCollectionIncomplete() {
        val records = tracks.dropLast(1).associate { it.id to DownloadRecord(it, DownloadStatus.COMPLETE) }
        val progress = collectionDownloadProgress(tracks.take(1), records, tracks.map { it.id }, collectionComplete = false)
        assertEquals(.75f, progress.fraction!!, .001f)
        assertFalse(progress.complete)
    }

    @Test fun localAudioIsAlreadyAvailableAndEmptyCollectionsAreNotComplete() {
        val local = tracks.take(3).mapIndexed { i, track -> track.copy(audioUrl = listOf("file:///music/a", "content://music/b", "xtreme-offline://c")[i]) }
        assertTrue(collectionDownloadProgress(local, emptyMap()).complete)
        val empty = collectionDownloadProgress(emptyList(), emptyMap())
        assertFalse(empty.complete)
        assertEquals(0f, empty.fraction!!, 0f)
    }
}
