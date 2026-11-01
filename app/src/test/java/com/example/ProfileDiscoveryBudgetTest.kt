package com.example

import android.app.Application
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeMusicApiService
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileDiscoveryBudgetTest {
    private fun song(id: String) = MusicTrack(id, id, "Singer", "Album", 180_000, "", "")

    @Test fun failedAndTimedOutLanguagesCannotDiscardSuccessfulResults() = runTest {
        val calls = mutableListOf<String>()
        val result = YouTubeMusicApiService.profileShelfResult("IN", listOf("Hindi", "Punjabi", "English")) { query, _, region ->
            calls += query; assertEquals("IN", region)
            when (query) {
                "Hindi Hits" -> YouTubeMusicApiService.SongSearchPage(listOf(song("success")), null)
                "Punjabi Hits" -> throw java.io.IOException("Offline")
                else -> { delay(10_000); error("The request must time out") }
            }
        }
        assertFalse(result.isError)
        assertEquals(listOf("success"), result.shelf!!.tracks.map { it.id })
        assertEquals(3, calls.size)
    }
    @Test fun rotationDeduplicatesPreferencesAndMakesAtMostThreeQueries() = runTest {
        val calls = mutableListOf<String>()
        val result = YouTubeMusicApiService.profileShelfResult("gb", listOf("Hindi", " hindi ", "Punjabi", "English", "Tamil"), 2) { query, _, region ->
            calls += query; assertEquals("GB", region)
            YouTubeMusicApiService.SongSearchPage(listOf(song(query)), null)
        }
        assertEquals(listOf("English Hits", "Tamil Hits", "Hindi Hits"), calls)
        assertEquals(calls, result.shelf!!.tracks.map { it.title })
    }
    @Test fun anEmptySuccessIsDifferentFromCompleteFailureAndCancellationPropagates() = runTest {
        val empty = YouTubeMusicApiService.profileShelfResult("IN", emptyList()) { _, _, _ -> YouTubeMusicApiService.SongSearchPage(emptyList(), null) }
        assertFalse(empty.isError); assertNull(empty.shelf)
        val failed = YouTubeMusicApiService.profileShelfResult("IN", listOf("Hindi")) { _, _, _ -> throw java.io.IOException() }
        assertTrue(failed.isError)
        try {
            YouTubeMusicApiService.profileShelfResult("IN", listOf("Hindi")) { _, _, _ -> throw CancellationException("Country changed") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun aGenuinelyEmptyHomeResponseNeverStartsAnotherRequest() = runTest {
        val empty = com.example.data.remote.YouTubeHomeParser.ParsedHomeFeed(emptyList(), null, accountIdentity = "anonymous")
        assertSame(empty, YouTubeMusicApiService.homeWithFallback({ empty }, { error("No fallback for success") }))
    }
}
