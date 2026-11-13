package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubePlayerArtworkResolver
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SongArtworkResolutionTest {
    private val videoId = "abcdefghijk"
    private val square = "https://lh3.googleusercontent.com/official-album=w600-h600-l90-rj"
    private val video = MusicTrack("yt_$videoId", "My Song (Official Video) | Cast | Singer", "Singer", "Video release", 230_000,
        "https://lh3.googleusercontent.com/video-crop=w600-h600-l90-rj", "audio")
    private fun text(value: String) = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
    private fun image(url: String) = JSONObject().put("thumbnails", JSONArray().put(
        JSONObject().put("url", url).put("width", 600).put("height", 600)))
    private fun results(artist: String = "Singer", title: String = "My Song"): String {
        fun column(content: JSONObject) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", content))
        val credit = text(artist)
        credit.getJSONArray("runs").getJSONObject(0).put("navigationEndpoint", JSONObject()
            .put("browseEndpoint", JSONObject().put("browseId", "UC-performer")))
        val renderer = JSONObject().put("playlistItemData", JSONObject().put("videoId", "bcdefghijkl"))
            .put("flexColumns", JSONArray().put(column(text(title))).put(column(credit)))
            .put("thumbnail", image(square)).put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
        return JSONObject().put("contents", JSONArray().put(JSONObject().put("musicResponsiveListItemRenderer", renderer))).toString()
    }
    @Before fun prepare() { BackgroundWorkCoordinator.foreground(true); BackgroundWorkCoordinator.firstFrameRendered() }
    @After fun cleanup() { BackgroundWorkCoordinator.foreground(false) }

    @Test fun signedOutVideoWithoutNativePairFindsOfficialSongDespitePromotionalCaptionAndDuration() = runBlocking {
        var queries = 0
        val resolver = YouTubePlayerArtworkResolver({ "{}" }, { queries++; results() })
        assertEquals(square, resolver.resolve(video, essential = true))
        assertEquals(1, queries)
        assertEquals("audio", video.audioUrl)
        assertTrue(video.coverUrl.contains("video-crop"))
    }

    @Test fun unknownShelfPerformerIsRecoveredFromTheRequestedVideosOwnWatchRow() = runBlocking {
        val credit = text("Singer")
        credit.getJSONArray("runs").getJSONObject(0).put("navigationEndpoint", JSONObject()
            .put("browseEndpoint", JSONObject().put("browseId", "UC-performer")))
        val root = JSONObject().put("playlistPanelVideoRenderer", JSONObject().put("videoId", videoId)
            .put("title", text("My Song")).put("longBylineText", credit).put("musicVideoType", "MUSIC_VIDEO_TYPE_OMV"))
        val resolver = YouTubePlayerArtworkResolver({ root.toString() }, { results() })
        assertEquals(square, resolver.resolve(video.copy(title = "My Song", artist = "Unknown"), essential = true))
    }

    @Test fun temporaryMetadataFailureCanRecoverImmediatelyAndWrongVersionsAndArtistsRemainRejected() = runBlocking {
        var unavailable = true
        val resolver = YouTubePlayerArtworkResolver({ if (unavailable) throw IOException("Temporary") else "{}" },
            { if (unavailable) throw IOException("Temporary") else results() })
        assertNull(resolver.resolve(video, essential = true))
        unavailable = false
        assertEquals(square, resolver.resolve(video, essential = true))
        assertNull(YouTubePlayerArtworkResolver.matchingSongCover(JSONObject(results(title = "My Song (Live)")), video))
        assertNull(YouTubePlayerArtworkResolver.matchingSongCover(JSONObject(results(artist = "Different Singer")), video))
    }

    @Test fun titleAloneCannotSupplyMissingArtistProofButAnExplicitArtistPrefixCan() {
        val unknown = video.copy(title = "Singer | Official Video", artist = "Unknown")
        assertNull(YouTubePlayerArtworkResolver.matchingSongCover(JSONObject(results(title = "Singer")), unknown))
        val credited = video.copy(title = "Singer - My Song (Official Video)", artist = "Unknown")
        assertEquals(square, YouTubePlayerArtworkResolver.matchingSongCover(JSONObject(results()), credited))
    }
}
