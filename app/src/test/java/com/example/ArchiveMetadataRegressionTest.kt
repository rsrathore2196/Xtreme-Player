package com.example

import android.app.Application
import com.example.data.remote.InternetArchiveApiService
import com.example.data.remote.ArchiveAudioMetadata
import com.example.data.model.MusicTrack
import com.example.data.repository.StreamResolver
import com.example.playback.LosslessDiagnostics.Reason
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
    @Test fun incompleteIndividualTagsAreNotReportedAsAConfirmedEmptyCatalogue() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Collection","creator":"Various Artists","rights":"public domain"},
            "files":[{"name":"track01.flac","format":"Flac"},{"name":"track02.flac","format":"Flac"}]}""")
        val rejected = mutableListOf<Pair<com.example.playback.LosslessDiagnostics.Reason, String>>()
        assertTrue(InternetArchiveApiService.parseLosslessItem(doc, root,
            onRejected = { reason, detail -> rejected.add(reason to detail) }, readHeader = { null }).isEmpty())
        assertEquals(2, rejected.size)
        assertTrue(rejected.all { it.first == com.example.playback.LosslessDiagnostics.Reason.METADATA_INCOMPLETE })
        assertTrue(rejected.first().second.contains("track01.flac"))
    }
    @Test fun archiveAccessDenialMissingFilesAndTimeoutsHaveDifferentDiagnosticOutcomes() {
        assertEquals(Reason.UNAUTHORIZED, InternetArchiveApiService.archiveFailureReason(com.example.data.remote.CatalogHttpException(403, null)))
        assertEquals(Reason.SOURCE_VERIFICATION_FAILED, InternetArchiveApiService.archiveFailureReason(com.example.data.remote.CatalogHttpException(404, null)))
        assertEquals(Reason.NETWORK_TIMEOUT, InternetArchiveApiService.archiveFailureReason(java.net.SocketTimeoutException()))
    }
    @Test fun verifiedFileDoesNotWaitForUnrelatedAlbumHeaders() = runBlocking {
        val request = MusicTrack("yt_song", "Song", "Artist", "Album", 180000, "", "")
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","rights":"public domain"},"files":[
            {"name":"01 Song.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"02.flac","format":"Flac"}
        ]}""")
        val result = kotlinx.coroutines.withTimeoutOrNull(500) {
            InternetArchiveApiService.parseLosslessItem(doc, root, request) { kotlinx.coroutines.awaitCancellation() }
        }
        assertNotNull("An unrelated slow header must not lose an already verified recording", result)
        assertEquals("ia_album:01 Song.flac", result!!.single().id)
    }

    @Test fun explicitFileVerificationStillReachesTheSelectedCopy() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","rights":"public domain"},"files":[
            {"name":"first.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"selected.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"}
        ]}""")
        val request = MusicTrack("ia_album:selected.flac", "Song", "Artist", "Album", 180000, "", "")
        val result = InternetArchiveApiService.parseLosslessItem(doc, root, request)
        assertTrue(result.any { it.id == request.id })
        assertEquals(2, InternetArchiveApiService.parseLosslessItem(doc, root).size)
    }

    @Test fun aRejectedFileDoesNotHideAnotherVerifiedFlacCopy() = runBlocking {
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","rights":"public domain"},"files":[
            {"name":"first.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"second.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"}
        ]}""")
        val request = MusicTrack("yt_song", "Song", "Artist", "Album", 180000, "", "")
        val rejected = "https://archive.org/download/album/first.flac"
        val result = InternetArchiveApiService.parseLosslessItem(doc, root, request, excludedUrls = setOf(rejected))
        assertEquals("ia_album:second.flac", result.single().id)
    }

    @Test fun maximumResolverReceivesVerifiedFlacBeforeAnUnrelatedHeaderCanTimeout() = runBlocking {
        val request = MusicTrack("yt_song", "Song", "Artist", "Album", 180000, "", "")
        val root = JSONObject("""{"metadata":{"title":"Album","creator":"Artist","rights":"public domain"},"files":[
            {"name":"01 Song.flac","format":"Flac","title":"Song","artist":"Artist","length":"180"},
            {"name":"02.flac","format":"Flac"}
        ]}""")
        val policy = com.example.playback.PlaybackSourcePolicy(com.example.playback.AudioQualityCeiling.MAX,
            sources = listOf(com.example.playback.PlaybackSource.archive, com.example.playback.PlaybackSource.youtube))
        val resolver = StreamResolver(supportsLossless = { true }, resolveYouTube = { _, _, _, _ -> null },
            searchYouTube = { emptyList() }, findLossless = { track, excluded, _ ->
                InternetArchiveApiService.LosslessSearchPage(InternetArchiveApiService.parseLosslessItem(
                    doc, root, track, excludedUrls = excluded) { kotlinx.coroutines.awaitCancellation() })
            })
        try {
            val resolved = kotlinx.coroutines.withTimeout(2_000) {
                resolver.resolve(request, policy.ceiling.audioQuality, sourcePolicy = policy)
            }
            assertEquals(request.id, resolved.id)
            assertEquals("https://archive.org/download/album/01%20Song.flac", resolved.audioUrl)
            assertEquals("INTERNET_ARCHIVE", resolved.source)
            assertTrue(resolved.isLossless)
        } finally { resolver.clear() }
    }

}
