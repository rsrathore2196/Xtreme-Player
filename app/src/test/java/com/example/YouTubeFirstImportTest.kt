package com.example

import android.app.Application
import com.example.data.importer.*
import com.example.data.model.MusicTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class YouTubeFirstImportTest {
    private val original = ImportedTrackMeta("Exact Song", "Main Artist", "Album", 180_000)
    private val youtube = MusicTrack("yt_abcdefghijk", "Exact Song", "Main Artist", "Album", 180_000,
        "https://images.example/original.jpg", "", bitrateKbps = 0, source = "YouTube Music")
    private val jio = youtube.copy(id = "online_exact", audioUrl = "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN")

    @Test fun aConfidentYoutubeCatalogMatchNeedsNeitherExtractionNorJioVerification() = runBlocking {
        val result = PlaylistImportEngine.matchTrack(original, forceRefresh = true,
            findYouTube = { _, _ -> listOf(youtube) }, findJio = { error("A primary catalog match needs no Jio lookup") })
        assertEquals(MatchStatus.EXACT_MATCH, result.matchStatus)
        assertEquals(youtube.id, result.matchedTrack!!.id)
        assertEquals("", result.matchedTrack.audioUrl)
        assertEquals(youtube.coverUrl, result.matchedTrack.coverUrl)
    }

    @Test fun wrongVersionsAndDurationsInYoutubeFallBackToExactJioOnlyAfterYoutubeQueries() = runBlocking {
        val calls = mutableListOf<String>()
        val result = PlaylistImportEngine.matchTrack(original, forceRefresh = true,
            findYouTube = { _, _ -> calls += "youtube"; listOf(youtube.copy(title = "Exact Song (Live)"), youtube.copy(durationMs = 240_000)) },
            findJio = { calls += "jio"; listOf(jio) })
        assertEquals(MatchStatus.EXACT_MATCH, result.matchStatus)
        assertEquals(jio.id, result.matchedTrack!!.id)
        assertEquals("youtube", calls.first()); assertEquals("jio", calls.last())
    }

    @Test fun successfulCatalogMatchesAreCachedWithoutSignedAudioUrls() = runBlocking {
        val selected = original.copy(originalTitle = "Cached Exact Song")
        val candidate = youtube.copy(title = selected.originalTitle)
        var searches = 0
        PlaylistImportEngine.matchTrack(selected, forceRefresh = true,
            findYouTube = { _, _ -> searches++; listOf(candidate) }, findJio = { error("No fallback needed") })
        val cached = PlaylistImportEngine.matchTrack(selected,
            findYouTube = { _, _ -> error("Use the successful catalog match") }, findJio = { error("No fallback needed") })
        assertEquals(1, searches); assertEquals(candidate.id, cached.matchedTrack!!.id)
        assertEquals("", cached.matchedTrack.audioUrl)
    }

    @Test fun aKnownYoutubePlaylistIdIsPreservedWithoutAnyCatalogLookup() = runBlocking {
        val result = PlaylistImportEngine.matchTrack(original.copy(youtubeVideoId = "abcdefghijk"), forceRefresh = true,
            findYouTube = { _, _ -> error("The playlist already supplied the exact ID") }, findJio = { error("No mandatory matching") })
        assertEquals(MatchStatus.EXACT_MATCH, result.matchStatus)
        assertEquals("yt_abcdefghijk", result.matchedTrack!!.id)
    }
}
