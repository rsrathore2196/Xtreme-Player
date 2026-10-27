package com.example.recommendation

import android.app.Application
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class AutoplayRecommendationTest {
    private fun song(number: Int, language: String = "Punjabi", genre: String = "Romantic") = MusicTrack(
        "yt_video${number.toString().padStart(6, '0')}", "Song $number", "Artist $number", "Album $number",
        180_000L, "", "", language = language, genre = genre, source = "YOUTUBE_MUSIC", recommendationOnly = true)

    @Test fun repeatedCallbacksCannotEvictRecentlyPlayedSelectionsFromSessionMemory() {
        val engine = RecommendationEngine()
        val recent = (1..30).map(::song)
        recent.forEach { engine.recordTrackPlayed(it); engine.recordTrackPlayed(it) }
        assertEquals(recent, engine.getSessionTracks())
        val next = song(31)
        val result = engine.buildInfiniteAutoplayRecommendations(recent.last(), listOf(recent.first(), next))
        assertEquals(listOf(next), result)
    }

    @Test fun finalAutoplayGateRejectsPersistedRecentRecordingsEvenWithAnotherProviderId() {
        val old = song(1).copy(isrc = "USAAA2300001")
        val alias = old.copy(id = song(2).id)
        val fresh = song(3)
        val key = HomeRecommendationPolicy.metadataKey(old)
        val taste = UserTasteProfile.COLD_START_PROFILE.copy(recentRecordings = setOf(key),
            recordingAliases = HomeRecommendationPolicy.aliases(old).associateWith { key })
        assertEquals(listOf(fresh), RecommendationEngine().buildInfiniteAutoplayRecommendations(song(4),
            listOf(alias, fresh), tasteProfile = taste))
    }

    @Test fun radioUsesCurrentSongLanguageMoodAndTheSelectedCountry() = runBlocking {
        val requests = mutableListOf<Triple<String, String, String>>()
        val seed = song(1)
        val engine = UnifiedRecommendationEngine(lookup = { operation, query, country, _, _, _ ->
            requests.add(Triple(operation, query, country))
            if (operation == "related") emptyList() else listOf(song(2, "Hindi"), song(3))
        })
        val result = engine.radio(seed, UserProfile(languages = listOf("Hindi", "English"), country = "Canada", countryCode = "CA"),
            UserTasteProfile.COLD_START_PROFILE, emptyList(), 5)
        assertEquals(listOf(song(3).id), result.map { it.id })
        assertTrue(requests.all { it.third == "CA" })
        assertTrue(requests.any { it.second.contains("punjabi romantic songs like", true) })
    }

    @Test fun cachedRadioCandidatesCannotBypassRecencyLanguageOrMoodRanking() = runBlocking {
        var networkCalls = 0
        val engine = UnifiedRecommendationEngine(lookup = { _, _, _, _, _, _ -> networkCalls++; emptyList() })
        val recent = song(2)
        val matching = song(3)
        val otherMood = song(4, genre = "Dance")
        val alreadyQueued = song(5)
        val taste = UserTasteProfile.COLD_START_PROFILE.copy(recentRecordings = setOf(HomeRecommendationPolicy.metadataKey(recent)))
        val result = engine.radioCandidates(song(1), UserProfile(), taste, listOf(alreadyQueued),
            listOf(recent, song(6, "Hindi"), otherMood, matching, alreadyQueued), 10)
        assertEquals(0, networkCalls)
        assertEquals(listOf(matching.id, otherMood.id), result.map { it.id })
    }

    @Test fun networkRadioRejectsQueuedAliasesAndRecentHistoryAtTheFinalGate() = runBlocking {
        val queued = song(2)
        val recent = song(3)
        val fresh = song(4)
        val engine = UnifiedRecommendationEngine(lookup = { _, _, _, _, _, _ ->
            listOf(queued.copy(id = song(20).id), recent, fresh)
        })
        val taste = UserTasteProfile.COLD_START_PROFILE.copy(recentRecordings = setOf(HomeRecommendationPolicy.metadataKey(recent)))
        val result = engine.radio(song(1), UserProfile(), taste, listOf(queued), 5)
        assertEquals(listOf(fresh.id), result.map { it.id })
    }
}
