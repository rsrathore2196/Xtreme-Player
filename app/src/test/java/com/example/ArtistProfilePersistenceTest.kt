package com.example

import android.app.Application
import android.content.Context
import android.util.LruCache
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.RecommendationCache
import com.example.data.remote.YouTubeExploreParser
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArtistProfilePersistenceTest {
    @Test fun freshPersistentProfileRestoresBrowseIdPortraitAndDetailsWithoutNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artist = CatalogArtist("UCprofile", "Cached Profile", image = "https://lh3.googleusercontent.com/profile=w600-h600")
        val album = CatalogAlbum("MPREalbum", "Release", listOf(artist), "https://lh3.googleusercontent.com/album=w600-h600", "2026", CatalogProvider.YOUTUBE_MUSIC)
        val profile = CatalogArtistProfile(artist, "Official biography", "12K subscribers", "84K monthly listeners",
            releases = listOf(ArtistReleaseSection("Albums", listOf(album))), relatedArtists = listOf(CatalogArtist("UCrelated", "Related Artist")))
        val cache = RecommendationCache.get(context)
        cache.putBody("artist-profile-v1:cached profile", YouTubeExploreParser.encodeArtistProfile(profile), 3_600_000L, checkpoint = true)
        // Simulate a process restart: clear memory in both cache layers, retaining Room.
        for ((owner, fieldName) in listOf(YouTubeMusicApiService to "profileCache", cache to "memory")) {
            (owner.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(owner) as LruCache<*, *>).evictAll()
        }
        val restored = withTimeout(2_000L) { YouTubeMusicApiService.artistProfile(CatalogArtist("", artist.name), context) }
        assertNotNull(restored)
        assertEquals(artist, restored!!.artist)
        assertEquals(profile.description, restored.description)
        assertEquals(profile.subscriberCount, restored.subscriberCount)
        assertEquals(profile.monthlyListeners, restored.monthlyListeners)
        assertEquals(album.id, restored.releases.single().albums.single().id)
        assertEquals(profile.relatedArtists, restored.relatedArtists)
        // A later known-channel request shares the same restored entry.
        assertSame(restored, withTimeout(2_000L) { YouTubeMusicApiService.artistProfile(artist, context) })
    }

    @Test fun verifiedPortraitReloadsWithoutWaitingForAnyProfileOrDiscographyRequest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artist = CatalogArtist("UCdiljit", "Diljit Dosanjh", image = "https://yt3.googleusercontent.com/diljit=w600-h600")
        RecommendationCache.get(context).putBody("artist-channel-v1:diljit dosanjh",
            YouTubeExploreParser.encodeArtistIdentity(artist), 86_400_000L, checkpoint = true)
        val restored = withTimeout(2_000L) { YouTubeMusicApiService.artistIdentity(CatalogArtist("", artist.name), context) }
        assertEquals(artist, restored)
        assertEquals(artist, withTimeout(2_000L) { YouTubeMusicApiService.artistIdentity(artist, context) })
    }

    @Test(expected = java.io.IOException::class)
    fun sameNamedDifferentChannelCannotOverwriteTheRequestedArtist() {
        val artist = CatalogArtist("UCfirst", "Same Name")
        val body = YouTubeExploreParser.encodeArtistProfile(CatalogArtistProfile(artist))
        YouTubeExploreParser.decodeArtistProfile(body, CatalogArtist("UCsecond", "Same Name"))
    }
}
