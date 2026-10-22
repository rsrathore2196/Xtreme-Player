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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
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
            id = "yt_" + index.toString().padStart(11, '0'), title = "Original Song $index", artist = "Artist $index",
            album = "Album $index", durationMs = 180_000, coverUrl = "https://artwork.example/$index.jpg",
            audioUrl = "https://aac.saavncdn.com/song${index}_320.mp4", language = "English", year = "1998",
            isCached = true, source = "YouTube Music") }
        try {
            db.musicDao().insertOrUpdateTracks(tracks.map(TrackEntity::fromMusicTrack))
            val repo = MusicRepository(db.musicDao(), app)
            // A paused optional pipeline must not prevent the initial local catalog.
            BackgroundWorkCoordinator.foreground(false)
            BackgroundWorkCoordinator.scrolling(true)
            val initial = withTimeout(5_000) { repo.getInitialCatalog(profile) }
            assertEquals(tracks.map { it.id }.toSet(), initial.map { it.id }.toSet())
            assertTrue(initial.all(UnifiedRecommendationEngine::verified))

            // Supply original YouTube catalog metadata, without a JioSaavn mapping
            // or live provider availability check.
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
            // The process-wide coordinator survives Robolectric's method clock
            // reset; completed cards from other fixtures must not be in the future.
            BackgroundWorkCoordinator::class.java.getDeclaredField("lastCardCompleted")
                .apply { isAccessible = true }.setLong(null, 0L)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            val regional = withTimeout(5_000) { repo.getCountryAndLanguageHits(profile, 10) }
            // Separate shelf requests still share the coordinator's card gap.
            ShadowSystemClock.advanceBy(Duration.ofMillis(750))
            val era = withTimeout(5_000) { repo.getEraHits(10) }
            assertEquals(10, regional.size)
            assertEquals(10, era.size)
            assertTrue((regional + era).all {
                UnifiedRecommendationEngine.verified(it) && it.recommendationOnly && it.source == "YouTube Music"
            })
            assertTrue(era.all { it.year.toInt() in 1990..2009 })
            assertTrue(regional.all { it.language == "English" })

            cache.put("discovery-v1:US:search:${DiscoveryRequestCoordinator.normalize("english   new releases United States")}",
                candidates, 86_400_000)
            val firstPresentation = DiscoveryRequestCoordinator.HomeRefresh(false, initialPresentation = true,
                essentialShelves = setOf(UnifiedRecommendationEngine.Shelf.QUICK, UnifiedRecommendationEngine.Shelf.CHART))
            // Exercise the real engine/cache/matching path with the legacy flag
            // deliberately false: initialPresentation must still cap each shelf.
            val batches = withTimeout(5_000) { withContext(firstPresentation) {
                UnifiedRecommendationEngine(app).refresh(profile, "United States",
                    com.example.recommendation.UserTasteProfile.COLD_START_PROFILE, null,
                    emptyList(), emptyList(), tracks, false,
                    shelves = firstPresentation.essentialShelves, startupBatch = false).toList()
            } }
            assertEquals(firstPresentation.essentialShelves, batches.flatMap { it.requested }.toSet())
            val quick = batches.last { it.quick.isNotEmpty() }.quick
            val chart = batches.last { !it.chart.isNullOrEmpty() }.chart.orEmpty()
            assertEquals(3, quick.size)
            assertEquals(3, chart.size)
            assertTrue((quick + chart).all { UnifiedRecommendationEngine.verified(it) && it.recommendationOnly })

            // A confirmed empty discovery shelf must not suppress the other
            // completed shelf or trigger unrelated JioSaavn filler/network work.
            cache.put("discovery-v1:US:search:${DiscoveryRequestCoordinator.normalize("english   new releases United States")}",
                emptyList(), 86_400_000)
            cache.put("discovery-v1:US:browse:femusic_home", emptyList(), 86_400_000)
            val partial = withTimeout(5_000) { withContext(firstPresentation) {
                UnifiedRecommendationEngine(app).refresh(profile, "United States",
                    com.example.recommendation.UserTasteProfile.COLD_START_PROFILE, null,
                    emptyList(), emptyList(), tracks, false,
                    shelves = firstPresentation.essentialShelves).toList()
            } }
            assertTrue(partial.flatMap { it.quick }.isEmpty())
            assertEquals(3, partial.last { !it.chart.isNullOrEmpty() }.chart.orEmpty().size)


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

    @Test fun recommendationsRequireRealYoutubeCatalogIdentityNotJioAvailability() {
        val song = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 180_000,
            "https://images.example/cover.jpg", "", bitrateKbps = 0, source = "YouTube Music")
        assertTrue(UnifiedRecommendationEngine.verified(song))
        assertFalse(UnifiedRecommendationEngine.verified(song.copy(id = "online_old")))
        assertFalse(UnifiedRecommendationEngine.verified(song.copy(id = "yt_bad")))
        assertFalse(UnifiedRecommendationEngine.verified(song.copy(title = "Song (Karaoke)")))
        assertFalse(UnifiedRecommendationEngine.verified(song.copy(isLossless = true)))
    }
}
