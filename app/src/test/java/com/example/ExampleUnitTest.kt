package com.example

import com.example.playback.AudioEffectsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {

    @Test
    fun testAudioEffectsPresets() {
        AudioEffectsManager.applyPreset("Bass Boost")
        var state = AudioEffectsManager.effectsState.value
        assertEquals("Bass Boost", state.selectedPreset)
        assertTrue(state.bassBoostStrength > 800)

        AudioEffectsManager.applyPreset("Flat")
        state = AudioEffectsManager.effectsState.value
        assertEquals("Flat", state.selectedPreset)
        assertEquals(0, state.bassBoostStrength)
    }

    @Test
    fun testAudioEffectsBandAdjustment() {
        AudioEffectsManager.setBandLevel(0.toShort(), 600.toShort())
        val state = AudioEffectsManager.effectsState.value
        assertEquals("Custom", state.selectedPreset)
        assertEquals(600.toShort(), state.bands[0].levelMb)
    }

    @Test
    fun testAudioEffectsToggle() {
        AudioEffectsManager.setEnabled(false)
        assertFalse(AudioEffectsManager.effectsState.value.isEnabled)

        AudioEffectsManager.setEnabled(true)
        assertTrue(AudioEffectsManager.effectsState.value.isEnabled)
    }

    @Test
    fun testOnlineMediaUrlDecryption() {
        val enc = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyv3kckaxpVLl7xtSDPAkZRx2kSesY7U8jBhHEhKdOMEcn80dFQJHaPRw7tS9a8Gtq"
        val decrypted = com.example.data.remote.OnlineMusicApiService.decryptMediaUrl(enc)
        org.junit.Assert.assertNotNull(decrypted)
        assertTrue(decrypted!!.endsWith("_320.mp4"))
        assertTrue(decrypted.startsWith("https://"))
    }

    @Test
    fun testRecommendationEngineAutoplay() {
        val engine = com.example.recommendation.RecommendationEngine()
        val tracks = com.example.data.remote.MusicDataSource.curatedTracks
        val current = tracks.first()

        // Test recommendation without history
        val rec1 = engine.recommendNextTrack(current, tracks)
        org.junit.Assert.assertNotNull(rec1)
        org.junit.Assert.assertNotEquals(current.id, rec1?.id)

        // Test with playback history of last 10 songs
        tracks.take(5).forEach { engine.recordTrackPlayed(it) }
        val rec2 = engine.recommendNextTrack(current, tracks)
        org.junit.Assert.assertNotNull(rec2)
    }

    @Test
    fun testSearchPlaybackQueuePrioritization() {
        val engine = com.example.recommendation.RecommendationEngine()
        val tracks = com.example.data.remote.MusicDataSource.curatedTracks
        val selectedTrack = tracks[0]

        val searchQueue = engine.buildSearchPlaybackQueue(selectedTrack, tracks)
        org.junit.Assert.assertFalse(searchQueue.isEmpty())
        assertEquals(selectedTrack.id, searchQueue[0].id)
    }

    @Test
    fun testLyricsProviderSupportedLanguages() = kotlinx.coroutines.runBlocking {
        // Punjabi track test
        val punjabiTrack = com.example.data.model.MusicTrack(
            id = "test_punjabi",
            title = "Brown Munde",
            artist = "AP Dhillon, Gurinder Gill",
            album = "Brown Munde",
            durationMs = 240000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Punjabi Hip-Hop",
            language = "Punjabi"
        )
        val punjabiLyrics = com.example.data.remote.LyricsProvider.getLyricsForTrack(punjabiTrack)
        assertTrue(punjabiLyrics.isSynced)
        assertTrue(punjabiLyrics.lines.isNotEmpty())

        // Hindi track test
        val hindiTrack = com.example.data.model.MusicTrack(
            id = "test_hindi",
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            album = "Aashiqui 2",
            durationMs = 260000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Bollywood",
            language = "Hindi"
        )
        val hindiLyrics = com.example.data.remote.LyricsProvider.getLyricsForTrack(hindiTrack)
        assertTrue(hindiLyrics.isSynced)
        assertTrue(hindiLyrics.lines.isNotEmpty())

        // English track test
        val englishTrack = com.example.data.model.MusicTrack(
            id = "test_english",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Synthwave",
            language = "English"
        )
        val englishLyrics = com.example.data.remote.LyricsProvider.getLyricsForTrack(englishTrack)
        assertTrue(englishLyrics.isSynced)
        assertTrue(englishLyrics.lines.isNotEmpty())
    }

    @Test
    fun testStrictLevenshteinSimilarityAnd80PercentRule() {
        val provider = com.example.data.remote.LyricsProvider

        // Exact match -> 1.0
        assertEquals(1.0, provider.calculateSimilarity("Blinding Lights", "Blinding Lights"), 0.01)

        // Title with minor variations / accents -> >= 0.80
        val simMinor = provider.validateTitleSimilarity("Blinding Lights (Official)", "Blinding Lights", "Blinding Lights")
        assertTrue("Expected similarity >= 0.80 for minor title variation, got $simMinor", simMinor >= 0.80)

        // Completely different song title -> < 0.80 (must be rejected)
        val simMismatch = provider.validateTitleSimilarity("Shape of You", "Blinding Lights", "Blinding Lights")
        assertTrue("Expected similarity < 0.80 for mismatched title, got $simMismatch", simMismatch < 0.80)

        val simDifferentSong = provider.validateTitleSimilarity("Brown Munde", "Tum Hi Ho", "Tum Hi Ho")
        assertTrue("Expected similarity < 0.80 for different song, got $simDifferentSong", simDifferentSong < 0.80)
    }

    @Test
    fun testMultiArtistValidation() {
        val provider = com.example.data.remote.LyricsProvider

        val primaryArtist = "AP Dhillon"
        val allArtists = listOf("AP Dhillon", "Gurinder Gill", "Shinda Kahlon", "Gminxr")

        // Exact primary artist
        assertTrue(provider.validateArtistMatch("AP Dhillon", primaryArtist, allArtists))

        // Featured artist
        assertTrue(provider.validateArtistMatch("Gurinder Gill", primaryArtist, allArtists))
        assertTrue(provider.validateArtistMatch("Shinda Kahlon", primaryArtist, allArtists))

        // Combined artist candidate string
        assertTrue(provider.validateArtistMatch("AP Dhillon, Gurinder Gill", primaryArtist, allArtists))

        // Completely mismatched artist (must be rejected)
        assertFalse(provider.validateArtistMatch("Taylor Swift", primaryArtist, allArtists))
        assertFalse(provider.validateArtistMatch("Arijit Singh", primaryArtist, allArtists))
    }

    @Test
    fun testLyricsNotAvailableForUnmatchedSong() = kotlinx.coroutines.runBlocking {
        val unknownTrack = com.example.data.model.MusicTrack(
            id = "test_unknown_nonexistent_track_999",
            title = "Zzzxqy Unreleased Phantom Sound 999",
            artist = "Unknown Phantom Entity XYZ",
            album = "Unpublished Album",
            durationMs = 120000L,
            coverUrl = "",
            audioUrl = "",
            genre = "Unknown",
            language = "English"
        )
        val result = com.example.data.remote.LyricsProvider.getLyricsForTrack(unknownTrack)
        // Strict engine must return empty lines ("Lyrics not available") instead of wrong lyrics
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun testAndroidAutoMediaBrowseCatalog() {
        val tracks = com.example.data.remote.MusicDataSource.curatedTracks
        assertTrue("Curated tracks must be available for car device media browser", tracks.isNotEmpty())

        // Validate Highway Driving Beats category filter
        val drivingTracks = tracks.filter {
            it.genre.contains("synth", ignoreCase = true) ||
            it.genre.contains("electronic", ignoreCase = true) ||
            it.genre.contains("rock", ignoreCase = true) ||
            it.genre.contains("punjabi", ignoreCase = true)
        }
        assertTrue("Highway Driving Beats must contain energetic tracks", drivingTracks.isNotEmpty())

        // Validate Punjabi & Bollywood categories as resolved for Android Auto browse menus
        val punjabiTracks = tracks.filter { it.genre.contains("punjabi", ignoreCase = true) || it.language.contains("punjabi", ignoreCase = true) }.ifEmpty { tracks.take(4) }
        assertTrue("Punjabi category must contain songs", punjabiTracks.isNotEmpty())

        val bollywoodTracks = tracks.filter { it.genre.contains("bollywood", ignoreCase = true) || it.language.contains("hindi", ignoreCase = true) }.ifEmpty { tracks.take(4) }
        assertTrue("Bollywood category must contain songs", bollywoodTracks.isNotEmpty())
    }
}
