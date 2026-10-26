package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.remote.OnlineMusicApiService
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.CatalogArtist
import com.example.recommendation.HomeRecommendationPolicy
import com.example.ui.viewmodel.addPendingArtistPlays
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtistIdentityRegressionTest {
    private fun song() = MusicTrack("online_recording", "Song", "Main Artist, Composer, Guest", "Album",
        200_000L, "https://example.com/album.jpg", "", singers = "Main Artist, Background Singer, Guest", writer = "Composer")

    @Test fun structuredPrimaryCollaboratorsExcludeOtherContributorListsAndDeduplicateAliases() {
        val track = song().copy(primaryArtists = listOf("Main Artist", "Second Artist", "MAIN ARTIST", "Third Artist"))
        assertEquals(listOf("Main Artist", "Second Artist", "Third Artist"), HomeRecommendationPolicy.mainArtists(track))
        val ranking = addPendingArtistPlays(emptyList(), listOf(track, track))
        assertEquals(3, ranking.size)
        assertTrue(ranking.all { it.playCount == 2 })
        assertFalse(ranking.any { it.name in listOf("Composer", "Guest", "Background Singer") })
    }
    @Test fun uncertainLegacyCreditsNeverPromoteSingersOrWriters() {
        assertEquals(listOf("Main Artist"), HomeRecommendationPolicy.mainArtists(song()))
        assertTrue(HomeRecommendationPolicy.mainArtists(song().copy(artist = "Unknown Artist")).isEmpty())
        assertTrue(HomeRecommendationPolicy.mainArtists(song().copy(artist = "Composer")).isEmpty())
        assertEquals(listOf("Main Artist"), HomeRecommendationPolicy.mainArtists(song().copy(artist = "Composer, Main Artist, Guest")))
        assertEquals(listOf("Main Artist"), HomeRecommendationPolicy.mainArtists(song().copy(artist = "Main Artist feat. Guest")))
        // A composer is eligible only when the provider explicitly credits them as primary.
        assertEquals(listOf("Composer"), HomeRecommendationPolicy.mainArtists(song().copy(primaryArtists = listOf("Composer"))))
    }
    @Test fun structuredBandNameIsNotInventedIntoSeparateArtists() {
        assertEquals(listOf("Simon & Garfunkel"), HomeRecommendationPolicy.mainArtists(
            song().copy(primaryArtists = listOf("Simon & Garfunkel"))))
    }
    @Test fun providerPrimaryCreditsSurviveRoomMetadataConversion() {
        val map = JSONObject().put("primary_artists", JSONArray().put(JSONObject().put("name", "Main Artist"))
            .put(JSONObject().put("name", "Second Artist")))
            .put("featured_artists", JSONArray().put(JSONObject().put("name", "Guest")))
            .put("artists", JSONArray().put(JSONObject().put("name", "Composer").put("role", "composer")))
        val parsed = requireNotNull(OnlineMusicApiService.parseSong(JSONObject().put("id", "song")
            .put("title", "Song").put("type", "song").put("more_info", JSONObject().put("artistMap", map)),
            allowMissingStream = true))
        assertEquals(listOf("Main Artist", "Second Artist"), parsed.primaryArtists)
        assertEquals(parsed.primaryArtists, TrackEntity.fromMusicTrack(parsed).toMusicTrack().primaryArtists)
        assertEquals(parsed.primaryArtists, HomeRecommendationPolicy.mainArtists(parsed))
    }
    @Test fun allLinkedYoutubeCollaboratorsSurvivePlaybackMetadataAndRanking() {
        fun run(name: String, id: String, type: String = "MUSIC_PAGE_TYPE_ARTIST") = JSONObject().put("text", name)
            .put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", id)
                .put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig",
                    JSONObject().put("pageType", type)))))
        fun column(rows: JSONArray) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
            JSONObject().put("text", JSONObject().put("runs", rows)))
        val names = listOf("Lead Artist", "Second Singer", "Third Singer", "Fourth Singer")
        val artists = JSONArray()
        names.forEachIndexed { i, name -> artists.put(run(name, "UCartist$i")).put(JSONObject().put("text", ", ")) }
        artists.put(run("Album", "MPREalbum", "MUSIC_PAGE_TYPE_ALBUM"))
        val responsive = JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
            .put("playlistItemData", JSONObject().put("videoId", "abcdefghijk"))
            .put("flexColumns", JSONArray().put(column(JSONArray().put(JSONObject().put("text", "Song"))))
                .put(column(artists))))
        val track = requireNotNull(YouTubeMusicApiService.parseAnyRenderer(responsive))
        assertEquals(names, track.primaryArtists)
        assertEquals(names, TrackEntity.fromMusicTrack(track).toMusicTrack().primaryArtists)
        assertEquals(names.toSet(), addPendingArtistPlays(emptyList(), listOf(track)).map { it.name }.toSet())
        val queue = JSONObject().put("playlistPanelVideoRenderer", JSONObject().put("videoId", "abcdefghijk")
            .put("title", JSONObject().put("simpleText", "Song"))
            .put("shortBylineText", JSONObject().put("runs", JSONArray().put(run(names[0], "UCartist0"))))
            .put("longBylineText", JSONObject().put("runs", JSONArray().put(run(names[0], "UCartist0"))
                .put(JSONObject().put("text", " & ")).put(run(names[1], "UCartist1"))
                .put(JSONObject().put("text", " • ")).put(run("Unrelated", "UCother")))))
        val queued = requireNotNull(YouTubeMusicApiService.parseAnyRenderer(queue))
        assertEquals(names.take(2), queued.primaryArtists)
        assertEquals(names.take(2).joinToString(", "), queued.artist)
        assertEquals(2, addPendingArtistPlays(emptyList(), listOf(queued)).size)
    }

    @Test fun corroboratedLegacySingerCreditsRetainDuetsWithoutPromotingMixedContributorLists() {
        val duet = song().copy(artist = "Lead, Second", singers = "Lead, Second", writer = "Writer")
        assertEquals(listOf("Lead", "Second"), HomeRecommendationPolicy.mainArtists(duet))
        assertEquals(listOf("Main Artist"), HomeRecommendationPolicy.mainArtists(song()))
        assertEquals(listOf("Lead"), HomeRecommendationPolicy.mainArtists(duet.copy(artist = "Lead feat. Guest")))
    }

    @Test fun persistentVerifiedPortraitsRestoreWithoutCatalogOrAlbumFallbacks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
        fun entry(source: String, image: String) = JSONObject().put("version", 3).put("source", source)
            .put("image", image).put("expires", System.currentTimeMillis() + 86_400_000L)
            .put("albumArtwork", "https://example.com/album.jpg").toString()
        prefs.edit().putString("portrait:cached profile artist", entry("youtube", "https://example.com/verified-portrait.jpg"))
            .putString("portrait:cached youtube artist", entry("youtube", "https://example.com/profile.jpg"))
            .putString("portrait:legacy album artist", entry("catalog", "https://example.com/album.jpg"))
            .putString("portrait:stage name", JSONObject(entry("youtube", "https://example.com/portrait.jpg"))
                .put("version", 4).put("canonicalName", "Official Name").toString()).commit()
        ArtistPhotoRepository.prewarm(context).await()
        assertEquals("https://example.com/verified-portrait.jpg", ArtistPhotoRepository.initialPhoto(CatalogArtist("", "Cached Profile Artist")))
        assertEquals("https://example.com/profile.jpg", ArtistPhotoRepository.initialPhoto(CatalogArtist("", "Cached YouTube Artist")))
        assertNull(ArtistPhotoRepository.initialPhoto(CatalogArtist("", "Legacy Album Artist", image = "https://example.com/album.jpg")))
        assertNull(ArtistPhotoRepository.initialPhoto(CatalogArtist("", "Unverified", image = "https://example.com/album.jpg")))
        val aliases = song().copy(primaryArtists = listOf("Stage Name", "Official Name"))
        assertEquals(1, HomeRecommendationPolicy.mainArtists(aliases).size)
        assertEquals(1, addPendingArtistPlays(emptyList(), listOf(aliases)).single().playCount)
    }
}
