package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.example.playback.SleepTimerManager
import com.example.service.PlaybackSessionStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackPersistenceLaneTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun restoredTimerAndRapidUserChangesKeepOnlyTheLatestDeadline() = runBlocking {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        val old = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(30)
        withContext(Dispatchers.IO) {
            context.getSharedPreferences("playback_sleep_timer", 0).edit()
                .putInt("boot_count", 7).putLong("elapsed_deadline", old)
                .putLong("wall_deadline", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30)).commit()
            SleepTimerManager.preload(context)
        }
        SleepTimerManager.initialize(context)
        assertEquals(old, SleepTimerManager.deadline.value)
        val pauses = AtomicInteger()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            if (method.name == "pause") pauses.incrementAndGet()
            null
        } as Player
        SleepTimerManager.attach(context, player)
        try {
            SleepTimerManager.start(context, 10_000)
            SleepTimerManager.cancel()
            SleepTimerManager.start(context, 60_000)
            val selected = SleepTimerManager.deadline.value
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
            assertEquals(selected, SleepTimerManager.deadline.value)
            assertEquals(0, pauses.get())
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.SECONDS)
            assertEquals(0L, SleepTimerManager.deadline.value)
            assertEquals(1, pauses.get())
        } finally { SleepTimerManager.detach(player) }
    }

    @Test fun sessionCheckpointRestoresTheLatestQueuePositionAndExactIds() = runBlocking {
        fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://audio.example/$id.m4a")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(id).setArtist("Artist")
                .setExtras(android.os.Bundle().apply { putBoolean("queue_collection", true) }).build()).build()
        val items = listOf(item("online_first"), item("online_selected"), item("online_next"))
        PlaybackSessionStore.checkpoint(context, PlaybackSessionStore.Snapshot(items, 0, 500))
        PlaybackSessionStore.checkpoint(context, PlaybackSessionStore.Snapshot(items, 1, 42_000))
        val restored = withTimeout(5_000) {
            var latest: PlaybackSessionStore.Snapshot?
            do {
                latest = PlaybackSessionStore.restore(context)
                if (latest?.positionMs != 42_000L) delay(10)
            } while (latest?.positionMs != 42_000L)
            latest
        }
        assertEquals(1, restored.index)
        assertEquals(42_000L, restored.positionMs)
        assertEquals(items.map { it.mediaId }, restored.items.map { it.mediaId })
        assertTrue(restored.items.all { it.mediaMetadata.extras!!.getBoolean("queue_collection") })
    }
}
