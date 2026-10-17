package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.RecommendationCache
import com.example.data.local.MusicDatabase
import com.example.data.local.TrackEntity
import com.example.data.local.UserProfile
import com.example.data.local.UserProfilePreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.recommendation.DiscoveryRequestCoordinator
import com.example.recommendation.UnifiedRecommendationEngine
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HomeRepositoryLoadingTest {
    @Test fun testHomeRepositoryInstantRenderAndDeferredLoading() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val profile = UserProfile(languages = listOf("English"), country = "United States", countryCode = "US")
        UserProfilePreferences.saveUserProfile(app, profile)
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java).build()
        val cache = RecommendationCache.get(app)
        val retainedCatalog = MusicRepository::class.java.getDeclaredField("memoryCachedCatalog").apply { isAccessible = true }
        retainedCatalog.set(null, null)
        val tracks = (1..16).map { index -> MusicTrack(
            id = "online_home$index", title = "Original Song $index", artist = "Artist $index",
            album = "Album $index", durationMs = 180_000, coverUrl = "https://artwork.example/$index.jpg",
            audioUrl = "https://aac.saavncdn.com/song${index}_320.mp4", language = "English", year = "1998",
            isCached = true, source = "JIOSAAVN") }
        try {
            db.musicDao().insertOrUpdateTracks(tracks.map(TrackEntity::fromMusicTrack))
            val repo = MusicRepository(db.musicDao(), app)
            // A paused optional pipeline must not prevent the initial local catalog.
            BackgroundWorkCoordinator.foreground(false)
            BackgroundWorkCoordinator.scrolling(true)
            val initial = withTimeout(5_000) { repo.getInitialCatalog(profile) }
            assertEquals(tracks.map { it.id }.toSet(), initial.map { it.id }.toSet())
            assertTrue(initial.all(UnifiedRecommendationEngine::verified))

            // Supply genuine-shaped discovery metadata and known verified mappings,
            // rather than depending on live YouTube/JioSaavn availability in a unit test.
            val candidates = tracks.mapIndexed { index, track -> track.copy(
                id = "yt_" + index.toString().padStart(11, '0'), audioUrl = "", source = "YouTube Music") }
            for ((operation, query) in listOf("charts" to "FEmusic_charts",
                "search" to "english popular songs United States", "search" to "english 1990s original hits")) {
                cache.put("discovery-v1:US:$operation:${DiscoveryRequestCoordinator.normalize(query)}", candidates, 86_400_000)
            }
            BackgroundWorkCoordinator.firstFrameRendered()
            BackgroundWorkCoordinator.homeContentReady()
            BackgroundWorkCoordinator.cachedHomeRestored()
            BackgroundWorkCoordinator.foreground(true)
            BackgroundWorkCoordinator.scrolling(false)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            val regional = withTimeout(5_000) { repo.getCountryAndLanguageHits(profile, 10) }
            val era = withTimeout(5_000) { repo.getEraHits(10) }
            assertEquals(10, regional.size)
            assertEquals(10, era.size)
            assertTrue((regional + era).all {
                UnifiedRecommendationEngine.verified(it) && it.recommendationOnly && it.source == "JIOSAAVN"
            })
            assertTrue(era.all { it.year.toInt() in 1990..2009 })
            assertTrue(regional.all { it.language == "English" })
        } finally {
            BackgroundWorkCoordinator.scrolling(false)
            BackgroundWorkCoordinator.foreground(false)
            retainedCatalog.set(null, null)
            val scope = RecommendationCache::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(cache) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
            val database = RecommendationCache::class.java.getDeclaredField("database\$delegate").apply { isAccessible = true }.get(cache) as Lazy<*>
            if (database.isInitialized()) (database.value as androidx.room.RoomDatabase).close()
            RecommendationCache::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            db.close()
        }
    }

    @Test fun legacyProviderLabelsStillRequireVerifiedCatalogAndCdnIdentity() {
        val restored = MusicTrack("online_song", "Song", "Artist", "Album", 180_000,
            "cover", "https://aac.saavncdn.com/song_320.mp4", source = "HD Stream")
        assertTrue(UnifiedRecommendationEngine.verified(restored))
        assertFalse(UnifiedRecommendationEngine.verified(restored.copy(id = "yt_abcdefghijk")))
        assertFalse(UnifiedRecommendationEngine.verified(restored.copy(audioUrl = "https://audio.example/song.mp4")))
        assertFalse(UnifiedRecommendationEngine.verified(restored.copy(audioUrl = "https://saavncdn.com.evil.example/song.mp4")))
        assertFalse(UnifiedRecommendationEngine.verified(restored.copy(source = "YouTube Music")))
        assertFalse(UnifiedRecommendationEngine.verified(restored.copy(isLossless = true)))
    }
}
