package com.example

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.RecommendationCache
import com.example.data.cache.RecommendationCacheDatabase
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeExploreParser
import com.example.data.repository.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExploreBrowseCatalogTest {
    @Test fun albumPlaybackUsesTheExactReleaseCoverOnEveryPageWithoutAdditionalLookups() = runBlocking {
        var calls = 0
        val repository = CatalogRepository(browseLoader = { _, _, token, _, _ ->
            calls++
            page(if (token == null) "abcdefghijk" else "bcdefghijkl", if (token == null) "next" else null)
        })
        val cover = "https://lh3.googleusercontent.com/exact-release=w544-h544-l90-rj"
        val album = CatalogAlbum("MPRErelease", "Release", emptyList(), cover, "2026", CatalogProvider.YOUTUBE_MUSIC)
        val first = repository.albumSongs(album)
        val second = repository.albumSongs(album, first.browseContinuation)
        assertEquals(cover, first.tracks.single().coverUrl)
        assertEquals(cover, second.tracks.single().coverUrl)
        assertEquals("yt_abcdefghijk", first.tracks.single().id)
        assertEquals(2, calls)
        assertEquals(first, repository.albumSongs(album))
        assertEquals(2, calls)
        assertTrue(repository.albumSongs(album.copy(image = "")).tracks.single().coverUrl.contains("abcdefghijk"))
    }

    @Test fun artistRowThumbnailsUseTheLargestProviderImageRegardlessOfArrayOrder() {
        val row = song("abcdefghijk").getJSONObject("playlistVideoRenderer")
        row.put("thumbnailRenderer", JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail",
            JSONObject().put("thumbnails", JSONArray()
                .put(JSONObject().put("url", "https://lh3.googleusercontent.com/cover=w544-h544").put("width", 544))
                .put(JSONObject().put("url", "https://lh3.googleusercontent.com/cover=w60-h60").put("width", 60))))))
        val track = com.example.data.remote.YouTubeMusicApiService.parseAnyRenderer(JSONObject().put("playlistVideoRenderer", row))!!
        assertEquals("https://lh3.googleusercontent.com/cover=w544-h544", track.coverUrl)
    }
    private fun label(value: String) = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
    private fun button(name: String, id: String, params: String) = JSONObject().put("musicNavigationButtonRenderer",
        JSONObject().put("buttonText", label(name)).put("clickCommand", JSONObject().put("browseEndpoint",
            JSONObject().put("browseId", id).put("params", params))))
    private fun grid(name: String, vararg rows: JSONObject) = JSONObject().put("gridRenderer", JSONObject()
        .put("header", JSONObject().put("gridHeaderRenderer", JSONObject().put("title", label(name))))
        .put("items", JSONArray(rows.toList())))
    private fun categories(vararg groups: JSONObject) = JSONObject().put("contents", JSONObject()
        .put("singleColumnBrowseResultsRenderer", JSONObject().put("tabs", JSONArray().put(JSONObject()
            .put("tabRenderer", JSONObject().put("content", JSONObject().put("sectionListRenderer",
                JSONObject().put("contents", JSONArray(groups.toList()))))))))).toString()
    private fun collection(name: String, id: String, params: String, type: String): JSONObject = JSONObject()
        .put("musicTwoRowItemRenderer", JSONObject().put("title", label(name))
            .put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", id)
                .put("params", params).put("browseEndpointContextSupportedConfigs", JSONObject()
                    .put("browseEndpointContextMusicConfig", JSONObject().put("pageType", type))))))
    private fun song(id: String, name: String = "Song") = JSONObject().put("playlistVideoRenderer", JSONObject()
        .put("videoId", id).put("title", label(name)).put("shortBylineText", label("Artist"))
        .put("lengthSeconds", "180"))
    private fun page(id: String, token: String?): String = JSONObject().put("contents", JSONObject()
        .put("musicPlaylistShelfRenderer", JSONObject().put("contents", JSONArray().put(song(id))).apply {
            token?.let { put("continuations", JSONArray().put(JSONObject()
                .put("nextContinuationData", JSONObject().put("continuation", it)))) }
        })).toString()

    @Test fun categoryIndexPreservesOpaqueEndpointsAndDropsOnlyDuplicateShortcuts() {
        val root = JSONObject(categories(
            grid("For you", button("Focus", "FEmusic_mood", "focus")),
            grid("Moods", button("Focus", "FEmusic_mood", "focus"), button("Workout", "FEmusic_mood", "workout")),
            grid("Genres", button("Jazz", "FEmusic_genre", "jazz"), button("Pop", "FEmusic_genre", "pop"))))
        val result = YouTubeExploreParser.categories(root)
        assertEquals(listOf("Focus", "Workout", "Jazz", "Pop"), result.map { it.title })
        assertEquals(4, result.map { it.key }.distinct().size)
        assertEquals(listOf("focus", "workout", "jazz", "pop"), result.map { it.params })
        assertEquals(ExploreCategoryKind.MOOD, result.first().kind)
        assertEquals(ExploreCategoryKind.GENRE, result.last().kind)
        assertEquals(result, YouTubeExploreParser.decodeCategories(YouTubeExploreParser.encodeCategories(result)))
    }

    @Test fun excludedGenresAreFilteredAndDanceElectronicIsRenamed() {
        val root = JSONObject(categories(
            grid("Genres",
                button("Carnatic Classical", "FEmusic_carnatic", "carnatic"),
                button("Country & Americana", "FEmusic_country", "country"),
                button("Hindustani Classical", "FEmusic_hindustani", "hindustani"),
                button("Indie & Alternative", "FEmusic_indie", "indie"),
                button("Reggae & Caribbean", "FEmusic_reggae", "reggae"),
                button("Dance & Electronic", "FEmusic_dance", "dance"),
                button("Rock", "FEmusic_rock", "rock")
            )
        ))
        val result = YouTubeExploreParser.categories(root)
        assertEquals(listOf("Dance", "Rock"), result.map { it.title })
        assertEquals("FEmusic_dance", result.first().browseId)
        assertEquals("dance", result.first().params)
    }

    @Test fun categoryCollectionsKeepTheirProviderAndParametersWithoutResolvingAudio() {
        val root = JSONObject().put("contents", JSONObject().put("sectionListRenderer", JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("musicCarouselShelfRenderer", JSONObject()
                .put("contents", JSONArray()
                    .put(collection("Jazz Essentials", "VLjazz", "playlist-params", "MUSIC_PAGE_TYPE_PLAYLIST"))
                    .put(collection("Jazz Album", "MPREb_jazz", "album-params", "MUSIC_PAGE_TYPE_ALBUM"))
                    .put(song("abcdefghijk"))))))))
        val result = YouTubeExploreParser.page(root, "IN", "hi")
        assertEquals("VLjazz", result.playlists.single().id)
        assertEquals("playlist-params", result.playlists.single().browseParams)
        assertEquals(CatalogProvider.YOUTUBE_MUSIC, result.playlists.single().provider)
        assertEquals("album-params", result.albums.single().browseParams)
        assertEquals("IN", result.albums.single().countryCode)
        assertEquals("hi", result.albums.single().languageCode)
        assertEquals("", result.tracks.single().audioUrl)
        assertEquals(0, result.tracks.single().bitrateKbps)
        val restored = YouTubeExploreParser.decodePage(YouTubeExploreParser.encodePage(result), "IN", "hi")
        assertEquals(result, restored)
        assertNotEquals(CatalogDestination.Playlist(result.playlists.single()).key,
            CatalogDestination.Playlist(CatalogPlaylist("VLjazz", "Jazz Essentials", "")).key)
    }

    @Test fun youtubeCollectionsLoadOnePageAtATimeWithImmutableProgressAndLoopProtection() = runBlocking {
        val calls = mutableListOf<List<String?>>()
        val repository = CatalogRepository(browseLoader = { id, params, token, country, language ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            calls.add(listOf(id, params, token, country, language))
            when (token) {
                null -> page("abcdefghijk", "next-a")
                "next-a" -> page("bcdefghijkl", "next-b")
                else -> page("cdefghijklm", "next-a")
            }
        })
        val selected = CatalogPlaylist("VLexact", "Exact playlist", "", CatalogProvider.YOUTUBE_MUSIC,
            "opaque-params", "IN", "hi")
        val first = repository.playlistSongs(selected, 1)
        assertEquals(1, calls.size)
        assertEquals(2, first.nextPage)
        assertEquals(2, first.browseContinuation!!.page)
        val second = repository.playlistSongs(selected, 2, first.browseContinuation)
        assertEquals(3, second.nextPage)
        assertEquals(listOf("VLexact", "opaque-params", "next-a", "IN", "hi"), calls[1])
        val last = repository.playlistSongs(selected, 3, second.browseContinuation)
        assertNull(last.nextPage)
        assertNull(last.browseContinuation)
        assertEquals(3, calls.size)
        // Reusing a cursor is safe and reuses its exact cached response; it doesn't advance a hidden iterator.
        assertEquals(second, repository.playlistSongs(selected, 2, first.browseContinuation))
        assertEquals(3, calls.size)
    }

    @Test fun wrongCollectionCursorCannotBeUsedAndAlbumRoutingBypassesTheOtherCatalog() = runBlocking {
        val calls = AtomicInteger()
        val repository = CatalogRepository(browseLoader = { id, params, _, _, _ ->
            calls.incrementAndGet(); assertEquals("MPREb_exact", id); assertEquals("album-p", params)
            page("abcdefghijk", "next")
        })
        val album = CatalogAlbum("MPREb_exact", "Album", emptyList(), "", "", CatalogProvider.YOUTUBE_MUSIC, "album-p")
        val result = repository.albumSongs(album)
        assertEquals("yt_abcdefghijk", result.tracks.single().id)
        try {
            repository.albumSongs(album, result.browseContinuation!!.copy(browseId = "another-album"))
            fail("A cursor must remain bound to its original collection")
        } catch (_: IOException) { }
        assertEquals(1, calls.get())
    }

    @Test fun matchingCategoryRequestsShareTheNetworkAndLocaleKeysRemainIndependent() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val body = categories(grid("Genres", button("Pop", "FEmusic_genre", "pop")))
        val repository = CatalogRepository(browseLoader = { id, _, _, _, _ ->
            assertEquals("FEmusic_moods_and_genres", id)
            calls.incrementAndGet(); entered.complete(Unit); release.await(); body
        })
        val first = async { repository.exploreCategories("IN", "en") }
        withTimeout(3000) { entered.await() }
        val second = async { repository.exploreCategories("IN", "en") }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls.get())
        repository.exploreCategories("US", "en")
        repository.exploreCategories("IN", "hi")
        assertEquals(3, calls.get())
    }

    @Test fun temporaryFailureIsNotSavedAsAnEmptyCategoryIndex() = runBlocking {
        val calls = AtomicInteger()
        val repository = CatalogRepository(browseLoader = { _, _, _, _, _ ->
            if (calls.incrementAndGet() == 1) throw IOException("Temporary timeout")
            categories(grid("Genres", button("Pop", "FEmusic_genre", "pop")))
        })
        try { repository.exploreCategories(); fail("The first failure should propagate") } catch (_: IOException) { }
        assertEquals("Pop", repository.exploreCategories().single().title)
        assertEquals(2, calls.get())
    }

    @Test fun metadataCheckpointUsesExistingPersistentCacheWithoutChangingTrackDecodingLimits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cache = RecommendationCache.get(context)
        val key = "explore-test-checkpoint"
        cache.putBody(key, "category metadata", 60000, checkpoint = true)
        val database = Room.databaseBuilder(context, RecommendationCacheDatabase::class.java, "recommendation_cache.db").build()
        try { assertEquals("category metadata", database.entries().get(key)?.body) } finally { database.close() }
        val many = (1..100).map { MusicTrack("yt_$it", "Song $it", "Artist", "Album", 1, "", "") }
        val page = CatalogPage(tracks = many)
        assertEquals(100, YouTubeExploreParser.decodePage(YouTubeExploreParser.encodePage(page), "US", "en").tracks.size)
    }
}
