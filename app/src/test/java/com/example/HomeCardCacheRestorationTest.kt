package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.HomeCardCache
import com.example.data.cache.SavedHomeCards
import com.example.data.model.MusicTrack
import com.example.performance.BackgroundWorkCoordinator
import com.example.recommendation.UnifiedRecommendationEngine
import com.example.ui.viewmodel.SectionState
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeCardCacheRestorationTest {
    private lateinit var app: Application
    private val profile = "US|English"
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        clearMemory()
        BackgroundWorkCoordinator.firstFrameRendered()
        BackgroundWorkCoordinator.homeContentReady()
        BackgroundWorkCoordinator.cachedHomeRestored()
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.scrolling(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    }
    @After fun cleanup() {
        BackgroundWorkCoordinator.scrolling(false)
        BackgroundWorkCoordinator.foreground(false)
        clearMemory()
    }
    private fun clearMemory() {
        for (name in listOf("memory", "previews")) {
            val field = HomeCardCache::class.java.getDeclaredField(name).apply { isAccessible = true }
            (field.get(null) as AtomicReference<*>).set(null)
        }
    }
    private fun snapshot(generation: Long = 1): SavedHomeCards {
        fun track(id: String) = MusicTrack("online_$id", "Song $id", "Real Artist", "Original Album",
            180_000, "https://artwork/$id.jpg", "https://aac.saavncdn.com/$id.mp4", source = "JIOSAAVN",
            recommendationOnly = true)
        fun section(id: String) = SectionState(title = id, tracks = listOf(track(id)), isLoaded = true)
        val quick = section("quick"); val era = section("era"); val chart = section("chart")
        return SavedHomeCards(profile, "signal", generation, System.currentTimeMillis(), quick, era, chart,
            SectionState(title = "History"), SectionState(title = "Personalized"), emptyList(),
            quick.tracks, era.tracks, chart.tracks, quick.tracks + era.tracks + chart.tracks,
            recommendationSource = UnifiedRecommendationEngine.SOURCE, readyShelves = setOf("QUICK", "ERA", "CHART"))
    }
    private suspend fun writeSnapshot(value: SavedHomeCards) {
        val cache = HomeCardCache(app)
        cache.save(value)
        cache.close()
        val writer = HomeCardCache::class.java.getDeclaredField("writer").apply { isAccessible = true }
            .get(cache) as kotlinx.coroutines.Job
        withTimeout(5_000) { writer.join() }
        clearMemory()
    }

    @Test fun previewRestoresVisibleTracksAndFullReadEnrichesPools() = runBlocking {
        writeSnapshot(snapshot())
        val cache = HomeCardCache(app)
        try {
            val preview = cache.readInitial(profile)!!
            assertEquals("online_quick", preview.quick.tracks.single().id)
            assertTrue(preview.era.tracks.isEmpty())
            assertTrue(preview.quickCandidates.isEmpty())
            val full = cache.read(profile)!!
            assertEquals(preview.quick.tracks, full.quick.tracks)
            assertEquals("online_era", full.era.tracks.single().id)
            assertEquals("online_quick", full.quickCandidates.single().id)
        } finally { cache.close() }
    }

    @Test fun concurrentFullRestorationsReuseOneRetainedSnapshot() = runBlocking {
        writeSnapshot(snapshot())
        val cache = HomeCardCache(app)
        try {
            coroutineScope {
                val first = async { cache.read(profile) }
                val second = async { cache.read(profile) }
                assertSame(first.await(), second.await())
            }
        } finally { cache.close() }
    }

    @Test fun aLegacyCacheWithoutAPreviewStillRestoresVisibleCardsAndCompletePools() = runBlocking {
        writeSnapshot(snapshot())
        val file = java.io.File(app.filesDir, "home_cards_v1.json")
        val legacy = org.json.JSONObject(file.readText()).apply { remove("preview"); put("version", 2) }
        file.writeText(legacy.toString())
        val cache = HomeCardCache(app)
        try {
            assertEquals("online_quick", cache.readInitial(profile)!!.quick.tracks.single().id)
            assertEquals("online_era", cache.read(profile)!!.eraCandidates.single().id)
        } finally { cache.close() }
    }

    @Test fun anOldDiskReadCannotReplaceANewerInMemoryGeneration() = runBlocking {
        writeSnapshot(snapshot())
        val cache = HomeCardCache(app)
        try {
            BackgroundWorkCoordinator.scrolling(true)
            coroutineScope {
                val oldRead = async { cache.read(profile) }
                // The full decoder is cooperative and paused by the scroll; a new
                // publication can be retained without waiting on a cache/file lock.
                delay(50)
                val newer = snapshot(generation = 2)
                cache.save(newer)
                BackgroundWorkCoordinator.scrolling(false)
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                assertSame(newer, withTimeout(5_000) { oldRead.await() })
                assertSame(newer, cache.peekFull(profile))
            }
        } finally { BackgroundWorkCoordinator.scrolling(false); cache.close() }
    }
}
