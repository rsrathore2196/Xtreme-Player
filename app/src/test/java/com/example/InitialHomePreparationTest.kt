package com.example

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.HomeCardCache
import com.example.data.cache.SavedHomeCards
import com.example.data.local.UserProfile
import com.example.data.local.UserProfilePreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.StartupGroup
import com.example.performance.StartupStatus
import com.example.performance.StartupTask
import com.example.playback.PlaybackManager
import com.example.recommendation.UnifiedRecommendationEngine
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.SectionState
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class InitialHomePreparationTest {
    @Test fun confirmedEmptyHistoryUsesOnlyCachedDiscoveryShelvesWithoutANetworkBootstrapOrVisibleHome() = prepare(false)
    @Test fun returningUserWithCachedShelvesDoesNotWaitForFreshTaste() = prepare(true)

    private fun prepare(existingUser: Boolean) = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        var player: PlaybackManager? = null
        val cache = HomeCardCache(app)
        val coordinator = BackgroundWorkCoordinator
        @Suppress("UNCHECKED_CAST")
        val readiness = BackgroundWorkCoordinator::class.java.getDeclaredField("_startupReadiness")
            .apply { isAccessible = true }.get(null) as kotlinx.coroutines.flow.MutableStateFlow<com.example.performance.StartupReadiness>
        readiness.value = com.example.performance.StartupReadiness()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("initialStartedAt")
            .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
        try {
            com.example.data.local.MusicDatabase.getDatabase(app).listeningEventDao().pruneOldEvents(Long.MAX_VALUE)
            val profile = UserProfile(countryCode = "US", country = "United States", languages = listOf("English"), isOnboardingCompleted = true)
            UserProfilePreferences.saveUserProfile(app, profile)
            fun track(id: String) = MusicTrack("online_$id", "Recording $id", "Original Artist", "Original Album",
                180_000, "https://artwork/$id.jpg", "https://aac.saavncdn.com/$id.mp4",
                language = "English", source = "JIOSAAVN", recommendationOnly = true)
            fun section(id: String) = SectionState(id, tracks = listOf(track(id)), isLoaded = true)
            val quick = section("quick"); val era = section("era"); val chart = section("chart")
            cache.save(SavedHomeCards("US|English", "signal", 1, System.currentTimeMillis(), quick, era, chart,
                SectionState("Recently Played"), SectionState("Recommended for You"), emptyList(),
                quick.tracks, era.tracks, chart.tracks, quick.tracks + era.tracks + chart.tracks,
                recommendationSource = UnifiedRecommendationEngine.SOURCE))
            coordinator.firstFrameRendered()
            coordinator.foreground(true)
            // Robolectric rewinds uptime between fixtures while keeping the
            // process coordinator. Rebase its navigation/frame samples here.
            coordinator.frame(2, 1)
            (BackgroundWorkCoordinator::class.java.getDeclaredField("navigationUntil")
                .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicLong).set(0L)
            coordinator.startupTask(StartupTask.SETTINGS, StartupStatus.READY)
            // This data-only fixture has no PlayerViewModel/local tab host.
            coordinator.startupTask(StartupTask.LOCAL_TABS, StartupStatus.READY)
            // This data-only fixture does not mount/measure the retained tab host.
            listOf(StartupTask.HOME_LAYOUT, StartupTask.SEARCH_LAYOUT, StartupTask.LIBRARY_LAYOUT).forEach {
                coordinator.startupTask(it, StartupStatus.READY)
            }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            if (existingUser) {
                val db = com.example.data.local.MusicDatabase.getDatabase(app)
                val played = track("previous-play")
                db.musicDao().insertOrUpdateTrack(com.example.data.local.TrackEntity.fromMusicTrack(played))
                repeat(300) { index ->
                    db.listeningEventDao().insertListeningEvent(com.example.data.local.ListeningEventEntity.fromTrack(
                        played, "saved-session-$index", 30_000L, played.durationMs, "ACTUAL_PLAY"))
                }
            }
            val repository = MusicRepository(app)
            player = PlaybackManager(app, repository)
            val home = HomeViewModel(repository, player, app)
            store.put("initial-home", home)
            // Deliberately never setHomeVisible(true): this also covers restoring
            // Search/Library, where startup must not require a visible Home page.
            val ready = try {
                withTimeout(10_000L) {
                    // AppDispatchers may already retain Android's real Main
                    // from another UI fixture. Drain its paused host looper as
                    // IO returns; replacing Dispatchers.Main alone cannot do so.
                    while (!coordinator.startupReadiness.value.preparationComplete) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(10L)
                    }
                    coordinator.startupReadiness.value
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Initial preparation stalled: ${coordinator.startupReadiness.value.tasks}", error)
            }
            assertFalse("A draw is still required", ready.complete)
            assertEquals(StartupStatus.READY, ready.status(StartupGroup.RECENTS))
            assertEquals(StartupStatus.READY, ready.status(StartupGroup.PLAYER))
            assertEquals(StartupStatus.FALLBACK_READY, ready.tasks[StartupTask.QUICK])
            assertEquals(StartupStatus.SKIPPED, ready.tasks[StartupTask.ERA])
            assertEquals(if (existingUser) com.example.recommendation.HomeUserMode.EXISTING_USER else
                com.example.recommendation.HomeUserMode.NEW_USER, home.userMode.value)
            assertEquals(if (existingUser) StartupStatus.FALLBACK_READY else StartupStatus.SKIPPED,
                ready.tasks[StartupTask.PERSONALIZATION])
            listOf(StartupTask.PERSONALIZED, StartupTask.INSPIRED,
                StartupTask.RECENT_ARTISTS).forEach { assertEquals("$it must not initialize", StartupStatus.SKIPPED, ready.tasks[it]) }
            assertEquals(StartupStatus.FALLBACK_READY, ready.tasks[StartupTask.CHART])
            assertEquals(!existingUser, home.listeningHistoryCard.value.tracks.isEmpty())
            assertTrue(home.mostListenedArtists.value.isEmpty())
            assertNull(player.uiState.value.currentTrack)
            assertEquals(quick.tracks, home.recommendationCards.value.quick.tracks)
            assertTrue(home.recommendationCards.value.era.tracks.isEmpty())
            assertEquals(com.example.ui.viewmodel.HomeFeedWindow.INITIAL, home.feedWindow.value.sections)
            assertEquals("Unrevealed cards must remain available in the cache", era.tracks,
                cache.peek("US|English")?.era?.tracks)
            assertFalse(home.eraCard.value.isLoading)
            assertFalse(home.recommendationCards.value.quick.isLoading)
            assertNotNull(home.heroSeedTrack.value)
            coordinator.initialContentDrawn()
            repeat(3) { coordinator.frame(5, 10) }
            withTimeout(2_000L) { coordinator.awaitInitialPresentationSettled() }
            assertTrue(coordinator.startupReadiness.value.complete)
            val completedPresentation = coordinator.startupReadiness.value
            val played = track("first-real-play")
            val database = com.example.data.local.MusicDatabase.getDatabase(app)
            database.musicDao().insertOrUpdateTrack(com.example.data.local.TrackEntity.fromMusicTrack(played))
            database.listeningEventDao().insertListeningEvent(com.example.data.local.ListeningEventEntity.fromTrack(
                played, "real-session", 30_000L, played.durationMs, "ACTUAL_PLAY"))
            withTimeout(3_000L) {
                while (home.userMode.value != com.example.recommendation.HomeUserMode.EXISTING_USER) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10L)
                }
            }
            assertSame("New history must not restart startup or the modal", completedPresentation,
                coordinator.startupReadiness.value)
        } finally {
            coordinator.foreground(false)
            store.clear(); player?.release(); cache.close()
            // The process coordinator outlives this fixture's Application. Even
            // an assertion failure must not leave other scheduler tests modal.
            (BackgroundWorkCoordinator::class.java.getDeclaredField("initialPresentationPending")
                .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicBoolean).set(false)
            Dispatchers.resetMain()
        }
    }
}
