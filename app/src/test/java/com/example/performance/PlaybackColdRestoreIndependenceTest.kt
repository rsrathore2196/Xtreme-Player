package com.example.performance

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MusicDatabase
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, instrumentedPackages = ["com.example.performance"])
class PlaybackColdRestoreIndependenceTest {
    @Test fun headlessPlaybackRestorationDoesNotNeedAHomeOrActivityFrame() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val coordinator = BackgroundWorkCoordinator
        coordinator.foreground(false)
        val firstFrame = coordinator.javaClass.getDeclaredField("firstFrame").apply { isAccessible = true }
            .get(null) as CompletableDeferred<*>
        assertFalse("Fixture starts without an Activity", firstFrame.isCompleted)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = MusicDatabase.createInMemoryDatabase(app)
        val player = PlaybackManager(app, MusicRepository(db.musicDao(), app))
        try {
            val restored = player.javaClass.getDeclaredField("sessionRestored").apply { isAccessible = true }.get(player) as CompletableDeferred<*>
            withTimeout(5_000) { restored.await() }
            assertFalse("Playback restoration must not synthesize a UI milestone", firstFrame.isCompleted)
        } finally { player.release(); db.close(); Dispatchers.resetMain() }
    }
}
