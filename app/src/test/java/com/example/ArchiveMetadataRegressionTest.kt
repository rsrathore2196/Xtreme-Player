package com.example

import android.app.Application
import com.example.data.remote.InternetArchiveApiService
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
}
