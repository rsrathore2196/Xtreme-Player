package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.HomeCardCache
import com.example.data.cache.SavedHomeCards
import com.example.data.model.*
import com.example.data.remote.YouTubeHomeParser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeHomeLayoutPolicyTest {
    @Test fun trendingFallbackCandidatesKeepNativeVideoTrackCoverAndOnlyTheSameVideoAlternatives() {
        val track = MusicTrack("yt_${NativeFeedFixtures.id(1)}", "Song", "Singer", "Album", 180_000,
            "https://lh3.googleusercontent.com/native-cover=w600-h600", "")
        val video = "https://i.ytimg.com/vi/${NativeFeedFixtures.id(1)}/hq720.jpg"
        val candidates = com.example.ui.util.nativeSongArtworkFallbacks(track, video)
        assertEquals(video, candidates.first())
        assertEquals(track.coverUrl, candidates[1])
        assertEquals(candidates.size, candidates.distinct().size)
        assertTrue(candidates.drop(2).all { it.contains("/vi/${NativeFeedFixtures.id(1)}/") })
    }
    private fun video(type: String = "MUSIC_VIDEO_TYPE_OMV") = NativeFeedFixtures.song(1).apply {
        getJSONObject("musicResponsiveListItemRenderer").put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject()
            .put("videoId", NativeFeedFixtures.id(1)).put("watchEndpointMusicSupportedConfigs", JSONObject()
                .put("watchEndpointMusicConfig", JSONObject().put("musicVideoType", type)))))
            .put("thumbnail", JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail", JSONObject()
                .put("thumbnails", org.json.JSONArray().put(JSONObject().put("url", "https://i.ytimg.com/vi/${NativeFeedFixtures.id(1)}/hq720.jpg")
                    .put("width", 1280).put("height", 720))))))
    }
    private fun parse(title: String, item: JSONObject) = YouTubeHomeParser.parseHomeFeed(JSONObject(
        NativeFeedFixtures.home(NativeFeedFixtures.collectionShelf(title, item)))).shelves.single()
    @Test fun quickPicksIsAnExactNativeSongShelfWithoutChangingOtherMusicOrCollections() {
        assertEquals(NativeShelfKind.QUICK_PICKS, parse("Quick Picks", NativeFeedFixtures.song(1)).nativeKind())
        assertEquals(NativeShelfKind.STANDARD, parse("Your Quick Picks albums", NativeFeedFixtures.album(2)).nativeKind())
        assertEquals(NativeShelfKind.STANDARD, parse("Quick Picks", NativeFeedFixtures.album(2)).nativeKind())
    }
    @Test fun videoMetadataAndExactNativeHeadingsAreBothRequired() {
        for (title in listOf("Music Videos", "New Music Videos")) {
            val shelf = parse(title, video())
            assertEquals(NativeShelfKind.MUSIC_VIDEOS, shelf.nativeKind())
            val song = shelf.items.single() as ServerHomeItem.Song
            assertEquals("MUSIC_VIDEO_TYPE_OMV", song.videoType)
            assertTrue(song.landscapeThumbnail.endsWith("hq720.jpg"))
            assertEquals(NativeShelfKind.STANDARD, parse(title, video("MUSIC_VIDEO_TYPE_ATV")).nativeKind())
            assertEquals(NativeShelfKind.STANDARD, parse(title, NativeFeedFixtures.song(1, "Official music video")).nativeKind())
        }
        assertEquals(NativeShelfKind.STANDARD, parse("Recommended songs", video()).nativeKind())
    }
    @Test fun verifiedVideoMetadataSurvivesHomeCacheRestoration() = runBlocking {
        val shelf = parse("Music Videos", video())
        val cache = HomeCardCache(ApplicationProvider.getApplicationContext<Application>())
        cache.saveHome(SavedHomeCards("IN|anonymous", serverShelves = listOf(shelf), serverFetchSucceeded = true))
        val restored = cache.readServer("IN|anonymous")!!.serverShelves.single()
        assertEquals(shelf.items.map { (it as ServerHomeItem.Song).copy(track = it.track.copy(audioUrl = "", recommendationOnly = true)) }, restored.items)
        assertEquals(NativeShelfKind.MUSIC_VIDEOS, restored.nativeKind())
    }
    @Test fun videoShelfKeepsLandscapeThumbnailsWhilePlaybackPrefersTheNativeSquareCover() {
        val video = video()
        val thumbs = video.getJSONObject("musicResponsiveListItemRenderer").getJSONObject("thumbnail")
            .getJSONObject("musicThumbnailRenderer").getJSONObject("thumbnail").getJSONArray("thumbnails")
        thumbs.put(JSONObject().put("url", "https://lh3.googleusercontent.com/album=w600-h600-l90-rj")
            .put("width", 600).put("height", 600))
        val shelf = parse("Music Videos", video)
        val entry = shelf.items.single() as ServerHomeItem.Song
        assertEquals(NativeShelfKind.MUSIC_VIDEOS, shelf.nativeKind())
        assertTrue(entry.landscapeThumbnail.endsWith("hq720.jpg"))
        assertEquals("https://lh3.googleusercontent.com/album=w600-h600-l90-rj", entry.track.coverUrl)
        assertEquals("A square crop in video metadata is not native audio-cover proof", "", entry.squareSongThumbnail)
    }

    @Test fun trendingNativeAudioProofIsPreservedWithoutTreatingVideoCropsOrPlaylistsAsSongArt() = runBlocking {
        val item = video("MUSIC_VIDEO_TYPE_ATV")
        item.getJSONObject("musicResponsiveListItemRenderer").put("thumbnail", org.json.JSONObject().put("thumbnails",
            org.json.JSONArray().put(org.json.JSONObject().put("url", "https://lh3.googleusercontent.com/album=w600-h600-l90-rj")
                .put("width", 600).put("height", 600))))
        val shelf = parse("Trending", item)
        assertTrue(shelf.requiresSongArtwork()); assertEquals(NativeShelfKind.STANDARD, shelf.nativeKind())
        assertEquals("https://lh3.googleusercontent.com/album=w600-h600-l90-rj", (shelf.items.single() as ServerHomeItem.Song).squareSongThumbnail)
        val cache = HomeCardCache(ApplicationProvider.getApplicationContext())
        cache.save(SavedHomeCards("US|anonymous", serverShelves = listOf(shelf)))
        val restored = cache.read("US|anonymous")!!.serverShelves.single()
        val original = shelf.items.single() as ServerHomeItem.Song
        assertEquals(listOf(original.copy(track = original.track.copy(recommendationOnly = true, audioUrl = ""))), restored.items)
        assertTrue(parse("Trending", video()).requiresSongArtwork())
        assertFalse(parse("Trending Community Playlists", NativeFeedFixtures.playlist(2)).requiresSongArtwork())
        assertFalse(parse("New Music Videos", video()).requiresSongArtwork())
    }

}
