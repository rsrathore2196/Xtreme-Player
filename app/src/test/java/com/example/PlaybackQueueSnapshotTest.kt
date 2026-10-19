package com.example

import android.app.Application
import androidx.compose.runtime.toMutableStateList
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MusicTrack
import com.example.data.repository.MusicRepository
import com.example.performance.AppDispatchers
import com.example.playback.PlaybackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class PlaybackQueueSnapshotTest {
    @Test fun mainPublishesPersistentSnapshotsWithoutCopyingOrMutatingEarlierQueues() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val player = PlaybackManager(app, MusicRepository(app))
        try {
            val queue = withContext(AppDispatchers.CpuBounded) {
                List(10_000) { MusicTrack("song_$it", "Song $it", "Artist", "Album", 180_000, "", "https://audio.example/$it.m4a") }
                    .toMutableStateList()
            }
            PlaybackManager::class.java.getDeclaredField("activeQueue").apply { isAccessible = true }.set(player, queue)
            val snapshot = PlaybackManager::class.java.getDeclaredMethod("queueSnapshot").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val first = snapshot.invoke(player) as List<MusicTrack>
            assertSame("Publishing must reuse the O(1) persistent snapshot", queue.toList(), first)
            assertSame(first, snapshot.invoke(player))
            val changed = queue[42].copy(isLiked = true)
            PlaybackManager::class.java.getDeclaredMethod("replaceQueueTrack", Int::class.javaPrimitiveType,
                MusicTrack::class.java).apply { isAccessible = true }.invoke(player, 42, changed)
            @Suppress("UNCHECKED_CAST")
            val second = snapshot.invoke(player) as List<MusicTrack>
            assertFalse("Previously published queue stays immutable", first[42].isLiked)
            assertTrue(second[42].isLiked)
            assertEquals(first.size, second.size)
            assertSame(queue.toList(), second)
            assertSame(first[9_999], second[9_999])
        } finally { player.release(); Dispatchers.resetMain() }
    }
}
