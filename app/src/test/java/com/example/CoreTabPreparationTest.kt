package com.example

import android.app.Application
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.repository.MusicRepository
import com.example.performance.BackgroundWorkCoordinator
import com.example.playback.PlaybackManager
import com.example.ui.viewmodel.PlayerViewModel
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoreTabPreparationTest {
    @Test fun dataOnlyPrewarmSharesOnePlaylistReadAndLeavesHistoricalAndLikedListsUnopened() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val reads = Collections.synchronizedList(mutableListOf<String>())
        val db = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java)
            .setQueryCallback({ sql, _ -> if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) reads.add(sql) }, Executor { it.run() }).build()
        val repository = MusicRepository(db.musicDao(), app)
        val player = PlaybackManager(app, repository)
        val store = ViewModelStore()
        BackgroundWorkCoordinator.foreground(true)
        try {
            db.musicDao().insertPlaylist(PlaylistEntity(1, "Saved playlist"))
            db.musicDao().insertOrUpdateTrack(TrackEntity("liked", "Liked song", "Singer", "Album", 120_000, "", "", isLiked = true))
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(repository, player, app) as T
            })[PlayerViewModel::class.java]
            withTimeout(5_000) { vm.settingsReady.first { it } }
            reads.clear()
            coroutineScope { repeat(3) { launch { vm.prepareStartupTabs() } } }
            vm.prepareStartupTabs()
            assertEquals(listOf("Saved playlist"), vm.playlists.value.map { it.title })
            assertTrue(vm.favoriteTracks.value.isEmpty())
            assertTrue(vm.recentlyPlayed.value.isEmpty())
            assertEquals(1, reads.count { it.contains("FROM playlists", ignoreCase = true) })
            assertFalse(reads.toList().any { it.contains("FROM listening_", ignoreCase = true) ||
                it.contains("FROM playlist_tracks", ignoreCase = true) || it.contains("FROM tracks", ignoreCase = true) })
            assertTrue(vm.searchState.value.exploreCategories.isEmpty())
        } finally {
            store.clear(); player.release(); db.close(); BackgroundWorkCoordinator.foreground(false); Dispatchers.resetMain()
        }
    }
}
