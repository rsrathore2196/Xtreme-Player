package com.example

import android.app.Application
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.ArchiveAudioMetadata
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchiveMetadataRegressionTest {
    private val doc = JSONObject("""{"identifier":"album"}""")
    @Test fun collectionFilesUseTheirOwnTitleArtistAndAlternativeDurationTags() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Whole Album","creator":"Various Artists",
            "length":"3600","rights":"public domain"},"files":[
            {"name":"first.flac","format":"Flac","track_title":"First Song","album_artist":"Artist One","duration":"180"},
            {"name":"second.flac","format":"Flac","song_title":"Second Song","artist":"Artist Two","runtime":"3:02"}
        ]}""")
        val result = InternetArchiveApiService.parseLosslessItem(doc, root)
        assertEquals(listOf("First Song", "Second Song"), result.map { it.title })
        assertEquals(listOf("Artist One", "Artist Two"), result.map { it.artist })
        assertEquals(listOf(180000L, 182000L), result.map { it.durationMs })
        assertEquals(2, result.map { it.id }.distinct().size)
        assertTrue(result.all { it.isLossless })
    }
    @Test fun collectionDurationAndDescriptiveFilenamesCannotInventIndividualRecordings() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","length":"3600",
            "rights":"public domain"},"files":[
            {"name":"Artist - Song.flac","format":"Flac","length":"180"},
            {"name":"another.flac","format":"Flac","title":"Another Song"}
        ]}""")
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root).isEmpty())
    }
    @Test fun aSingleRecordingMayUseItsActualItemDurationButStillRequiresPlaybackRights() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Single Song","creator":"Artist","duration":"181",
            "rights":"public domain"},"files":[{"name":"song.flac","format":"Flac"}]}""")
        assertEquals(181000L, InternetArchiveApiService.parseLosslessItem(doc, root).single().durationMs)
        root.getJSONObject("metadata").put("rights", "all rights reserved")
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root).isEmpty())
    }
    @Test fun missingCollectionFileTagsAreRecoveredFromTheActualFlacHeader() = runBlocking {
        val request = MusicTrack("online_song", "Song", "Artist", "Original Album", 180000, "", "")
        val root = JSONObject("""{"metadata":{"title":"Original Album","creator":"Various Artists",
            "rights":"public domain"},"files":[{"name":"01.flac","format":"Flac"}]}""")
        var probes = 0
        val recovered = InternetArchiveApiService.parseLosslessItem(doc, root, request) { url ->
            assertEquals("https://archive.org/download/album/01.flac", url)
            probes++
            ArchiveAudioMetadata.Tags("Song", "Artist", "Original Album", durationMs = 183000,
                sampleRate = 96000, bitDepth = 24, channels = 2)
        }.single()
        assertEquals(1, probes)
        assertTrue(StreamResolver.matchesLosslessUpgrade(request, recovered))
        assertEquals(183000L, recovered.durationMs)
        assertEquals("Song", recovered.title)
        assertEquals("Artist", recovered.artist)
        assertEquals("INTERNET_ARCHIVE", recovered.source)
    }
    @Test fun probingNeverBypassesRightsOrInventsIdentityFromAnAttractiveFilename() = runBlocking {
        val request = MusicTrack("online_song", "Song", "Artist", "Album", 180000, "", "")
        val root = JSONObject("""{"metadata":{"title":"Collection","creator":"Artist",
            "rights":"all rights reserved"},"files":[{"name":"Artist - Song.flac","format":"Flac"}]}""")
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root, request) {
            fail("An unauthorized item must not even fetch a header"); null
        }.isEmpty())
        root.getJSONObject("metadata").put("rights", "public domain")
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root, request) { null }.isEmpty())
    }
    @Test fun structuredDiscoveryIncludesAlbumCreatorAndIsrcQueriesRatherThanOnlySongAndTerms() {
        val request = MusicTrack("song", "Song (Official Audio)", "Artist", "Original Album", 180000, "", "",
            isrc = "US-ABC-20-00001")
        val plans = InternetArchiveApiService.recordingQueries(request)
        assertTrue(plans.any { it == "creator:\"artist\"" })
        assertTrue(plans.any { it.contains("title:\"original album\"") })
        assertTrue(plans.any { it == "title:\"original album\"" })
        assertTrue(plans.any { it.contains("USABC2000001") })
        assertFalse(plans.any { it.contains("official audio", true) })
    }
}
