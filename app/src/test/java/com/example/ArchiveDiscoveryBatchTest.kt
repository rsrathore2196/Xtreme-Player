package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.InternetArchiveApiService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveDiscoveryBatchTest {
    private val selected = MusicTrack("online_song", "Song", "Artist", "Album", 180000, "", "")
    private val recording = selected.copy(id = "ia_album:song.flac", source = "INTERNET_ARCHIVE",
        audioUrl = "https://archive.org/download/album/song.flac", isLossless = true)
    private val documents = listOf(JSONObject("""{"identifier":"verified"}"""), JSONObject("""{"identifier":"slow"}"""))

    @Test fun aVerifiedFileDoesNotWaitForASlowUnrelatedCollectionAndCancelsItsRequest() = runTest {
        val slowEntered = CompletableDeferred<Unit>()
        var cancelled = false
        val result = InternetArchiveApiService.collectRecordingFiles(selected, documents) { doc ->
            if (doc.getString("identifier") == "verified") {
                slowEntered.await()
                listOf(recording)
            } else {
                slowEntered.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        assertEquals(listOf(recording), result.filterNotNull().flatten())
        assertTrue(cancelled)
    }
    @Test fun aRejectedRepresentationCannotStopTheSearchBeforeAnotherVerifiedCopyArrives() = runTest {
        val result = InternetArchiveApiService.collectRecordingFiles(selected, documents,
            excludedUrls = setOf(recording.audioUrl)) { doc ->
            if (doc.getString("identifier") == "verified") listOf(recording)
            else { delay(50); listOf(recording.copy(audioUrl = "https://archive.org/download/other/song.flac")) }
        }
        assertEquals(2, result.filterNotNull().flatten().size)
        assertTrue(result.filterNotNull().flatten().any { it.audioUrl != recording.audioUrl })
    }
}
