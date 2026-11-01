package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.MusicTrack
import com.example.data.remote.*
import com.example.data.repository.LocalArtistHistory
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FinalServerRecommendationsTest {
    private fun song(id: String, artist: String = "Real Artist") = MusicTrack(id, "Real Song", artist, "Album", 180_000,
        "https://art/$id", "", primaryArtists = listOf(artist))
    @Test fun ordinaryNativeFailureUsesExactlyOneBoundedFallback() = runBlocking {
        var calls = 0
        val wanted = YouTubeHomeParser.ParsedHomeFeed(emptyList(), "cursor")
        val response = YouTubeMusicApiService.homeWithFallback({ calls++; throw java.io.IOException("native unavailable") },
            { calls++; wanted })
        assertEquals(wanted, response); assertEquals(2, calls)
    }
    @Test fun cancellationNeverStartsAnHttpFallback() = runBlocking {
        var fallback = false
        try {
            YouTubeMusicApiService.homeWithFallback({ throw CancellationException("user switched context") },
                { fallback = true; error("must not run") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(fallback)
    }
    @Test fun successfulEmptyContinuationIsDifferentFromAFailedRequest() = runBlocking {
        val empty = YouTubeHomeParser.ParsedHomeFeed(emptyList(), null)
        assertEquals(empty, YouTubeMusicApiService.homeWithFallback({ empty }, { error("No extra request") }))
        val failed = YouTubeMusicApiService.homeWithFallback({ throw java.io.IOException() }, { throw java.io.IOException() })
        assertTrue(failed.isError); assertFalse(empty.isError)
    }
    @Test fun validNativeDataDoesNotTriggerTheFallback() = runBlocking {
        val wanted = YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.shelf("Native", 1))))
        assertEquals(wanted, YouTubeMusicApiService.homeWithFallback({ wanted }, { error("Unnecessary request") }))
    }
    @Test fun nonYoutubeSeedsRejectTitleOnlyWrongArtistAndAlternateRecordings() {
        val seed = song("online_selected")
        val wrong = song("yt_abcdefghijk", "Unrelated Artist")
        val remix = song("yt_lmnopqrstuv").copy(title = "Real Song (Remix)", durationMs = 250_000)
        val exact = song("yt_01234567890")
        assertNull(YouTubeMusicApiService.matchRadioSeed(seed, listOf(wrong, remix)))
        assertSame(exact, YouTubeMusicApiService.matchRadioSeed(seed, listOf(wrong, remix, exact)))
    }
    @Test fun actualOccurrencesExcludeFeedbackZeroTimeAndDuplicateCheckpoints() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        try {
            val track = song("played"); db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
            val dao = db.listeningEventDao()
            for (type in listOf("STARTED", "MEANINGFUL_PLAY", "COMPLETED", "EARLY_SKIP"))
                dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, sessionId = "same", eventType = type, playedDurationMs = 90_000))
            dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, sessionId = "zero", eventType = "ACTUAL_PLAY"))
            repeat(2) { dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist,
                sessionId = "same", eventType = "ACTUAL_PLAY", playedDurationMs = 45_000)) }
            dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, sessionId = "repeat", eventType = "ACTUAL_PLAY", playedDurationMs = 60_000))
            assertEquals(2, LocalArtistHistory.read(db).single().playCount)
            assertEquals(listOf(track.id), LocalArtistHistory.read(db).single().tracks.map { it.id })
            dao.pruneOldEvents(Long.MAX_VALUE)
            assertTrue(LocalArtistHistory.read(db).isEmpty())
            dao.insertListeningEvent(ListeningEventEntity(trackId = track.id, artistName = track.artist, eventType = "ACTUAL_PLAY", playedDurationMs = 30_000))
            assertEquals(1, LocalArtistHistory.read(db).single().playCount)
        } finally { db.close() }
    }
    @Test fun artistNormalizationAndAllHistoricalRecordsKeepThePlayCountMetricAnd15Limit() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        try {
            for (index in 0..20) {
                val track = song("track-$index", "Artist $index")
                db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
                repeat(index + 1) { occurrence -> db.listeningEventDao().insertListeningEvent(ListeningEventEntity(trackId = track.id,
                    artistName = track.artist, sessionId = "$index:$occurrence", timestamp = occurrence.toLong(), eventType = "ACTUAL_PLAY", playedDurationMs = 30_000)) }
            }
            val result = LocalArtistHistory.read(db)
            assertEquals(15, result.size); assertEquals("Artist 20", result.first().name); assertEquals(21, result.first().playCount)
            val counts = listOf(ActualTrackPlayCount("a", "", 4, 1), ActualTrackPlayCount("b", "", 3, 2))
            val normalized = LocalArtistHistory.rank(counts, listOf(song("a", "Real Artist"), song("b", " real artist ")))
            assertEquals(1, normalized.size); assertEquals(7, normalized.single().playCount); assertEquals(2, normalized.single().tracks.size)
        } finally { db.close() }
    }
}
