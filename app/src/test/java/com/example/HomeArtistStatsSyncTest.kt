package com.example

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.remote.*
import com.example.data.repository.*
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.HomeViewModel
import java.io.File
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeArtistStatsSyncTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: MusicDatabase
    private lateinit var player: PlaybackManager
    private val store = ViewModelStore()
    private var clock: Job? = null
    private fun song(n: Int) = MusicTrack("yt_${NativeFeedFixtures.id(n)}", "Song $n", "Artist $n", "Album", 180_000, "", "")
    private fun total(track: MusicTrack, millis: Long, plays: Long = 1, origin: String = "local") = ListeningTotal(
        origin, "2026-11", "artist", track.artist, track.id, track.title, track.artist, "", track.coverUrl,
        track.durationMs, "", millis, plays)
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        BackgroundWorkCoordinator.foreground(true); BackgroundWorkCoordinator.windowFocus(true)
        BackgroundWorkCoordinator.firstFrameRendered(); BackgroundWorkCoordinator.scrolling(false); BackgroundWorkCoordinator.touch(false)
        BackgroundWorkCoordinator.frame(2, 1)
        db = MusicDatabase.createInMemoryDatabase(app)
        player = PlaybackManager(app, MusicRepository(db.musicDao(), app))
        clock = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) { ShadowSystemClock.advanceBy(Duration.ofMillis(25)); delay(25) }
        }
    }
    @After fun close() = runBlocking<Unit> {
        store.clear(); player.release(); db.close(); clock?.cancelAndJoin()
        BackgroundWorkCoordinator.foreground(false); Dispatchers.resetMain()
    }
    private fun model(): HomeViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
                MusicRepository(db.musicDao(), app), player, app, db,
                homeLoader = { _, _, _ -> YouTubeHomeParser.ParsedHomeFeed((1..3).map {
                    ServerHomeShelf("native-$it", "Native $it", tracks = listOf(song(it))) }, null) },
                profileLoader = { _, _, _ -> YouTubeMusicApiService.ProfileShelfResult(ServerHomeShelf("profile", "Music for You", tracks = listOf(song(1)))) },
                supplementalLoader = { _, _, _ -> YouTubeHomeParser.ParsedHomeFeed(emptyList(), null) },
                idleAdmission = {}, unlockIdleAdmission = { awaitCancellation() }, connectivityUpdates = emptyFlow()) as T
        }
        return ViewModelProvider(store, factory)[HomeViewModel::class.java].also {
            it.onHomeFrameDrawn(); it.setHomeVisible(true)
        }
    }
    @Test fun homeExtendsTheSameLifetimeStatsRankingToFifteenInsteadOfRankingRawOccurrences() = runBlocking<Unit> {
        for (n in 1..18) {
            val track = song(n)
            db.musicDao().insertOrUpdateTrack(TrackEntity.fromMusicTrack(track))
            repeat(19 - n) { occurrence -> db.listeningEventDao().insertListeningEvent(ListeningEventEntity(
                trackId = track.id, artistName = track.artist, eventType = "ACTUAL_PLAY", playedDurationMs = 30_000,
                sessionId = "$n-$occurrence")) }
            db.listeningStatsDao().put(total(track, n * 60_000L, (19 - n).toLong()))
        }
        val stats = ListeningSummary.from(emptyList(), db.listeningStatsDao().range("artist", 7, "0000-01", "9999-12"))
        val vm = model()
        withTimeout(5_000) { vm.mostListenedArtists.first { it.size == 15 } }
        assertEquals(stats.artists.map { it.artist }, vm.mostListenedArtists.value.take(5).map { it.name })
        assertEquals((18 downTo 4).map { song(it).artist }, vm.mostListenedArtists.value.map { it.name })
        assertEquals(1, vm.mostListenedArtists.value.first().playCount)
    }
    @Test fun aSavedDurationCheckpointReordersHomeWithoutAnotherHistoryOccurrenceOrManualRefresh() = runBlocking<Unit> {
        val stats = ListeningStatsRepository(app, db, File(app.noBackupFilesDir, "sync-test.json"))
        val day = LocalDate.of(2026, 11, 2).toString()
        fun snapshot(n: Int, millis: Long) = ListeningSnapshot("session-$n", song(n),
            mapOf(day to ListeningDay(millis, 1)), millis, finished = false, playing = true)
        try {
            stats.record(snapshot(1, 60_000)); stats.record(snapshot(2, 70_000)); stats.flush()
            val vm = model()
            withTimeout(5_000) { vm.mostListenedArtists.first { it.firstOrNull()?.name == song(2).artist } }
            val revision = db.listeningEventDao().getHomeInputRevision()
            stats.record(snapshot(1, 75_000)); stats.flush()
            assertEquals("The 15s totals checkpoint intentionally leaves the 30s history publication unchanged", revision,
                db.listeningEventDao().getHomeInputRevision())
            assertEquals(song(1).artist, stats.summary(ListeningPeriod.LIFETIME).artists.first().artist)
            withTimeout(5_000) { vm.mostListenedArtists.first { it.firstOrNull()?.name == song(1).artist } }
            assertEquals(1, vm.mostListenedArtists.value.first().playCount)
        } finally { stats.close() }
    }
    @Test fun restoredStatsWithoutRawEventsRemainLiveAcrossHiddenHomeRepeatedMergeAndDeletion() = runBlocking<Unit> {
        db.listeningStatsDao().put(total(song(1), 60_000))
        val vm = model()
        withTimeout(5_000) { vm.mostListenedArtists.first { it.singleOrNull()?.name == song(1).artist } }
        assertTrue(db.listeningEventDao().getActualPlays().isEmpty())
        @Suppress("UNCHECKED_CAST")
        val visible = vm.javaClass.getDeclaredField("homeVisible").apply { isAccessible = true }.get(vm) as StateFlow<Boolean>
        vm.setHomeVisible(false); withTimeout(5_000) { visible.first { !it } }
        val restored = listOf(total(song(2), 120_000, 2, origin = "backup"))
        db.withTransaction { ListeningStatsBackup.merge(db.listeningStatsDao(), restored) }
        assertNull(withTimeoutOrNull(200) { vm.mostListenedArtists.first { it.firstOrNull()?.name == song(2).artist } })
        vm.setHomeVisible(true)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.firstOrNull()?.name == song(2).artist } }
        db.withTransaction { ListeningStatsBackup.merge(db.listeningStatsDao(), restored) }
        assertEquals(2, vm.mostListenedArtists.value.first().playCount)
        db.withTransaction { db.openHelper.writableDatabase.execSQL("DELETE FROM listening_totals") }
        withTimeout(5_000) { vm.mostListenedArtists.first { it.isEmpty() } }
        assertFalse(vm.hasListenedArtists.value)
    }
    @Test fun importingLegacyStatsWhileHomeIsAlreadyOpenQualifiesTheRestoredUser() = runBlocking<Unit> {
        val vm = model()
        withTimeout(5_000) { vm.userMode.first { it == com.example.recommendation.HomeUserMode.NEW_USER } }
        assertTrue(vm.mostListenedArtists.value.isEmpty())
        db.withTransaction { ListeningStatsBackup.merge(db.listeningStatsDao(), listOf(total(song(1), 60_000, origin = "backup"))) }
        withTimeout(5_000) { vm.mostListenedArtists.first { it.singleOrNull()?.name == song(1).artist } }
        assertTrue(db.listeningEventDao().getActualPlays().isEmpty())
    }
    @Test fun verifiedAliasesRefreshTheSameStatsRankingWhenTheArtistShelfBecomesRelevant() = runBlocking<Unit> {
        fun alias(n: Int) = song(n).copy(artist = "Verified Alias Test $n")
        db.saveArtistTotal(alias(1), 60_000); db.saveArtistTotal(alias(2), 30_000); db.saveArtistTotal(alias(3), 70_000)
        val vm = model()
        withTimeout(5_000) { vm.mostListenedArtists.first { it.firstOrNull()?.name == alias(3).artist } }
        val prefs = app.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
        prefs.edit().apply {
            for (n in 1..2) putString("portrait:${CatalogNames.normalize(alias(n).artist)}", org.json.JSONObject()
                .put("version", 5).put("source", "youtube").put("image", "https://artist.example/portrait.jpg")
                .put("expires", System.currentTimeMillis() + 86_400_000).put("catalogId", "UC1234567890123456789012")
                .put("canonicalName", "Merged Artist").toString())
        }.commit()
        ArtistPhotoRepository.restoreSaved(app, (1..2).map { CatalogArtist("", alias(it).artist) })
        withTimeout(5_000) { while (!vm.javaClass.getDeclaredField("artistIdentityDirty").apply { isAccessible = true }.getBoolean(vm)) delay(10) }
        val expected = ListeningSummary.from(emptyList(), db.listeningStatsDao().range("artist", 7, "0000-01", "9999-12"))
        assertEquals(alias(3).artist, vm.mostListenedArtists.value.first().name)
        vm.setArtistSectionRelevant(true)
        withTimeout(5_000) { vm.mostListenedArtists.first { it.map { artist -> artist.name } == expected.artists.map { it.artist } } }
        assertEquals(2, vm.mostListenedArtists.value.size)
    }
}

/** Committed monthly totals, matching the source used by Lifetime Stats. */
internal suspend fun MusicDatabase.saveArtistTotal(track: MusicTrack, millis: Long = 30_000, plays: Long = 1) {
    listeningStatsDao().put(ListeningTotal("test", "2026-11", "artist", track.artist.lowercase(), track.id, track.title,
        track.artist, "", track.coverUrl, track.durationMs, track.isrc, millis, plays))
}
