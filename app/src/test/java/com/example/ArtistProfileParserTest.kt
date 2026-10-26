package com.example

import android.app.Application
import com.example.data.remote.YouTubeExploreParser
import com.example.data.repository.CatalogArtist
import com.example.data.repository.CatalogProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtistProfileParserTest {
    private fun text(s: String) = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", s)))
    private fun image(s: String) = JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail",
        JSONObject().put("thumbnails", JSONArray().put(JSONObject().put("url", s).put("width", 1200)))))
    private fun item(name: String, id: String, type: String) = JSONObject().put("musicTwoRowItemRenderer", JSONObject()
        .put("title", text(name)).put("thumbnailRenderer", image("https://lh3.googleusercontent.com/$id=w600-h600"))
        .put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", id)
            .put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig",
                JSONObject().put("pageType", type))))))
    private fun root(visual: Boolean = false): JSONObject {
        val header = JSONObject().put("title", text("Main Artist"))
            .put(if (visual) "foregroundThumbnail" else "thumbnail", image("https://lh3.googleusercontent.com/portrait=w1200-h1200"))
            .put("subscriptionButton2", JSONObject().put("subscribeButtonRenderer", JSONObject()
                .put("subscriberCountWithSubscribeText", text("1.2M subscribers"))))
            .put("monthlyListenerCount", text("3.4M monthly listeners"))
        val album = JSONObject().put("musicCarouselShelfRenderer", JSONObject().put("header", JSONObject()
            .put("musicCarouselShelfBasicHeaderRenderer", JSONObject().put("title", text("Albums"))))
            .put("contents", JSONArray().put(item("Release", "MPRErelease", "MUSIC_PAGE_TYPE_ALBUM"))))
        val related = JSONObject().put("musicCarouselShelfRenderer", JSONObject().put("header", JSONObject()
            .put("musicCarouselShelfBasicHeaderRenderer", JSONObject().put("title", text("Fans might also like"))))
            .put("contents", JSONArray().put(item("Other Artist", "UCother", "MUSIC_PAGE_TYPE_ARTIST"))))
        val bio = JSONObject().put("musicDescriptionShelfRenderer", JSONObject().put("description", text("Official biography")))
        return JSONObject().put("header", JSONObject().put(if (visual) "musicVisualHeaderRenderer" else "musicImmersiveHeaderRenderer", header))
            .put("contents", JSONArray().put(album).put(related).put(bio))
    }
    @Test fun officialHeaderProvidesPortraitRealCountsBioReleasesAndRelatedArtists() {
        for (visual in listOf(false, true)) {
            val profile = YouTubeExploreParser.artistProfile(root(visual), CatalogArtist("UCmain", "Main Artist"))
            assertTrue(profile.artist.image.contains("portrait"))
            assertEquals("1.2M subscribers", profile.subscriberCount)
            assertEquals("3.4M monthly listeners", profile.monthlyListeners)
            assertEquals("Official biography", profile.description)
            assertEquals("Release", profile.releases.single().albums.single().title)
            assertEquals(CatalogProvider.YOUTUBE_MUSIC, profile.releases.single().albums.single().provider)
            assertEquals("UCother", profile.relatedArtists.single().id)
        }
    }
    @Test fun missingCountsRemainUnknownAndAlbumArtIsNeverUsedForThePortrait() {
        val response = root()
        val header = response.getJSONObject("header").getJSONObject("musicImmersiveHeaderRenderer")
        header.remove("thumbnail"); header.remove("subscriptionButton2"); header.remove("monthlyListenerCount")
        val profile = YouTubeExploreParser.artistProfile(response, CatalogArtist("UCmain", "Main Artist"))
        assertEquals("", profile.artist.image)
        assertEquals("", profile.subscriberCount)
        assertEquals("", profile.monthlyListeners)
    }
    @Test(expected = IOException::class) fun wrongArtistHeaderIsRejected() {
        YouTubeExploreParser.artistProfile(root(), CatalogArtist("UCother", "Unrelated Artist"))
    }
    @Test fun artistSearchCannotAcceptSongRowsOrSeveralSameNameChannels() {
        val artist = item("Main Artist", "UCmain", "MUSIC_PAGE_TYPE_ARTIST")
        val song = item("Main Artist", "MPREsong", "MUSIC_PAGE_TYPE_ALBUM")
        assertEquals("UCmain", YouTubeExploreParser.artistMatches(JSONObject().put("contents", JSONArray().put(artist).put(song)), "Main Artist").single().id)
        val ambiguous = JSONObject().put("contents", JSONArray().put(artist).put(item("Main Artist", "UCsecond", "MUSIC_PAGE_TYPE_ARTIST")))
        assertEquals(2, YouTubeExploreParser.artistMatches(ambiguous, "Main Artist").size)
    }
    @Test fun exactTopArtistCardFindsDiljitWithoutSelectingOtherSameNameChannels() {
        val card = item("Diljit Dosanjh", "UCofficial", "MUSIC_PAGE_TYPE_ARTIST").getJSONObject("musicTwoRowItemRenderer")
        val response = JSONObject().put("contents", JSONArray().put(JSONObject().put("musicCardShelfRenderer", card))
            .put(item("Diljit Dosanjh", "UCother", "MUSIC_PAGE_TYPE_ARTIST")))
        assertEquals("UCofficial", YouTubeExploreParser.artistMatch(response, "Diljit Dosanjh")?.id)
        assertNull(YouTubeExploreParser.artistMatch(response, "Unrelated Artist"))
        assertNull(YouTubeExploreParser.artistMatch(JSONObject().put("contents", JSONArray()
            .put(item("Same Name", "UCone", "MUSIC_PAGE_TYPE_ARTIST"))
            .put(item("Same Name", "UCtwo", "MUSIC_PAGE_TYPE_ARTIST"))), "Same Name"))
    }

    @Test fun nestedResponsiveArtistHeaderAndAlbumGridPreserveRealProfileFields() {
        val header = JSONObject().put("title", text("Diljit Dosanjh"))
            .put("thumbnail", JSONObject().put("thumbnailViewModel", JSONObject().put("image", JSONObject()
                .put("sources", JSONArray().put(JSONObject().put("url", "https://yt3.googleusercontent.com/official=w1200-h1200").put("width", 1200))))))
            .put("subscriberCountText", text("8.1M"))
            .put("monthlyAudienceCount", JSONObject().put("content", "24M monthly audience"))
            .put("description", JSONObject().put("content", "Artist biography"))
        val grid = JSONObject().put("gridRenderer", JSONObject().put("header", JSONObject().put("gridHeaderRenderer",
            JSONObject().put("title", text("Albums")))).put("items", JSONArray().put(item("Release", "MPREone", "MUSIC_PAGE_TYPE_ALBUM"))))
        val response = JSONObject().put("contents", JSONObject().put("twoColumnBrowseResultsRenderer", JSONObject()
            .put("header", JSONObject().put("musicResponsiveHeaderRenderer", header)).put("contents", JSONArray().put(grid))))
        val profile = YouTubeExploreParser.artistProfile(response, CatalogArtist("UCofficial", "Diljit Dosanjh"))
        assertTrue(profile.artist.image.contains("official"))
        assertEquals("8.1M", profile.subscriberCount)
        assertEquals("24M monthly audience", profile.monthlyListeners)
        assertEquals("Artist biography", profile.description)
        assertEquals("MPREone", profile.releases.single().albums.single().id)
    }

    @Test fun artistPageSongsKeepCollaboratorsAlreadyVerifiedByTheirQueueByline() {
        fun credit(name: String, id: String) = JSONObject().put("text", name).put("navigationEndpoint", JSONObject()
            .put("browseEndpoint", JSONObject().put("browseId", id).put("browseEndpointContextSupportedConfigs",
                JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", "MUSIC_PAGE_TYPE_ARTIST")))))
        val song = JSONObject().put("playlistPanelVideoRenderer", JSONObject().put("videoId", "abcdefghijk")
            .put("title", text("Duet"))
            .put("longBylineText", JSONObject().put("runs", JSONArray().put(credit("Main Artist", "UCmain"))
                .put(JSONObject().put("text", " & ")).put(credit("Second Artist", "UCsecond")))))
        val response = root().put("contents", JSONArray().put(JSONObject().put("musicShelfRenderer",
            JSONObject().put("contents", JSONArray().put(song)))))
        val profile = YouTubeExploreParser.artistProfile(response, CatalogArtist("UCmain", "Main Artist"))
        assertEquals(listOf("Main Artist", "Second Artist"), profile.songs.single().primaryArtists)
    }

}
