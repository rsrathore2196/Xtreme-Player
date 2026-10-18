package com.example

import android.app.Application
import com.example.data.local.ListeningEventEntity
import com.example.data.model.MusicTrack
import com.example.recommendation.HomeRecommendationPolicy
import com.example.recommendation.UserTasteProfile
import com.example.ui.viewmodel.MostListenedArtist
import com.example.ui.viewmodel.addPendingArtistPlays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeStartupWorkloadTest {
    private fun track(index: Int) = MusicTrack("track-$index", "Recording $index", "Artist $index",
        "Album $index", 180_000, "https://image/$index", "https://audio/$index",
        language = if (index % 2 == 0) "Punjabi" else "Hindi", genre = "dance")

    @Test fun cacheCheckpointStopsReadingOnceItsRetainedPrefixIsPrepared() = runTest {
        val tracks = (0..1500).map(::track)
        var visited = 0
        var checkpoints = 0
        val source = sequence { tracks.forEach { visited++; yield(it); yield(it) } }
        val result = HomeRecommendationPolicy.originalsCooperatively(source, 400) { checkpoints++ }
        assertEquals(HomeRecommendationPolicy.originals(tracks).take(400), result)
        assertEquals("Do not process the remaining historical catalog", 400, visited)
        assertTrue("Preparation must have safe interaction boundaries", checkpoints > 1)
    }

    @Test fun aBatchOfRejectedCandidatesStillHasCancellationBoundaries() = runTest {
        val rejected = track(0).copy(title = "Recording karaoke cover")
        var visited = 0
        var checkpoints = 0
        try {
            HomeRecommendationPolicy.originalsCooperatively(sequence {
                repeat(5000) { visited++; yield(rejected) }
            }, 12) {
                if (++checkpoints == 3) throw CancellationException("Navigated away")
            }
            fail("The cancelled generation must stop")
        } catch (_: CancellationException) {
            assertTrue("Rejected candidates must not run through the entire pool", visited < 200)
        }
    }

    @Test fun cooperativeHistoryRetainsLongTermPlaysSkipsLanguagesAndFavoriteSignals() = runTest {
        val now = 1_800_000_000_000L
        val tracks = (0..4).map(::track)
        val events = (0 until 5000).map { index -> ListeningEventEntity(
            id = index.toLong(), trackId = tracks[index % tracks.size].id, artistName = tracks[index % 5].artist,
            sessionId = "session-$index", timestamp = now - index * 60_000L,
            playedDurationMs = 100_000L, trackDurationMs = 180_000L,
            skipped = index % 5 == 0, eventType = "ACTUAL_PLAY") }
        var checkpoints = 0
        val result = UserTasteProfile.fromEventsCooperatively(events, tracks, listOf(tracks[1]), now) {
            checkpoints++
        }
        assertEquals(UserTasteProfile.fromEvents(events, tracks, listOf(tracks[1]), now), result)
        assertEquals(5000, result.playCounts.values.sum())
        assertEquals(1000, result.skipCounts.values.sum())
        assertEquals(5, result.meaningfulPlaysCount)
        assertTrue(result.languageAffinities.keys.containsAll(listOf("punjabi", "hindi")))
        assertTrue(result.favoriteRecordings.contains(result.keyFor(tracks[1])))
        assertTrue(checkpoints > 70)
    }

    @Test fun historicalScoringCanBeCancelledWithoutPublishingAnIncompleteProfile() = runTest {
        val tracks = (0 until 5000).map(::track)
        var checkpoints = 0
        var published = false
        try {
            UserTasteProfile.fromEventsCooperatively(emptyList(), tracks) {
                if (++checkpoints == 4) throw CancellationException("Selection changed")
            }
            published = true
        } catch (_: CancellationException) { }
        assertFalse(published)
        assertEquals(4, checkpoints)
    }

    @Test fun cachedArtistTotalsSurviveRecentOnlyPreparationAndNewPlaysAreAdded() {
        val song = track(0)
        val cached = listOf(MostListenedArtist(song.artist, 200, listOf(song)),
            MostListenedArtist("Long Term Artist", 150, listOf(track(1))))
        assertSame(cached, addPendingArtistPlays(cached, emptyList()))
        val updated = addPendingArtistPlays(cached, listOf(song, track(2)))
        assertEquals(201, updated.first().playCount)
        assertEquals(150, updated.first { it.name == "Long Term Artist" }.playCount)
        assertEquals(1, updated.first { it.name == "Artist 2" }.playCount)
    }
}
