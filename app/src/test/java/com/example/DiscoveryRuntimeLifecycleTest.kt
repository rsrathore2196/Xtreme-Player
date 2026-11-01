package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MusicDatabase
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.repository.MusicRepository
import com.example.playback.PlaybackManager
import com.metrolist.innertubex.InnerTube
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiscoveryRuntimeLifecycleTest {
    @Test fun serviceTeardownCancelsItsAccountObserverAndClearsThePublishedSession() = runBlocking {
        val service = YouTubeAudioStreamService
        val runtime = service.javaClass.getDeclaredMethod("runtime").apply { isAccessible = true }.invoke(service)
        val engine = runtime.javaClass.getDeclaredMethod("engine", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(runtime, 320_000, false)
        val session = engine.javaClass.getDeclaredField("innerTube").apply { isAccessible = true }.get(engine) as InnerTube
        session.replaceSession("SAPISID=fixture-session", "fixture-visitor", "fixture-account", "1", true)
        withTimeout(5_000) { service.discoveryAccountChanges.first { it != null } }
        val observer = runtime.javaClass.getDeclaredField("accountObserver").apply { isAccessible = true }.get(runtime) as Job
        assertTrue(observer.isActive)
        service.releaseResources()
        withTimeout(5_000) { observer.join() }
        assertTrue(observer.isCancelled); assertNull(service.discoveryAccountChanges.value)
    }
    @Test fun sessionExclusionKeepsOnlyUniqueIdsAndTheExistingCount() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = MusicDatabase.createInMemoryDatabase(app)
        val player = PlaybackManager(app, MusicRepository(db.musicDao(), app))
        try {
            val track = MusicTrack("song", "Song", "Singer", "Album", 180_000, "", "")
            repeat(3) { player.recommendationEngine.recordTrackPlayed(track) }
            player.recommendationEngine.recordTrackPlayed(track.copy(id = "another"))
            assertEquals(2, player.recommendationEngine.size)
            assertEquals(setOf("song", "another"), player.recommendationEngine.getSessionTrackIds())
            player.recommendationEngine.clear(); assertEquals(0, player.recommendationEngine.size)
        } finally { player.release(); db.close() }
    }
}
