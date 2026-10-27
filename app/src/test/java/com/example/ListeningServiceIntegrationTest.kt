package com.example

import android.app.Application
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.local.*
import com.example.service.MusicService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class ListeningServiceIntegrationTest {
    @Test fun serviceUsesMonotonicTimeAndPreSeekPositionToExcludeStallsAndPausedTime() {
        val service = Robolectric.buildService(MusicService::class.java).get()
        val snapshots = mutableListOf<ListeningSnapshot>()
        MusicService::class.java.getDeclaredField("listeningClock").apply { isAccessible = true }
            .set(service, ListeningClock(snapshots::add))
        val item = MediaItem.Builder().setMediaId("yt_abcdefghijk").setUri("https://audio/original")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song").setArtist("Main Artist").build()).build()
        var selected = item
        var position = 0L
        var playing = true
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> selected
                "getCurrentPosition" -> position
                "getDuration" -> 60_000L
                "getCurrentMediaItemIndex" -> 0
                "isPlaying" -> playing
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                else -> error("Unexpected player call ${method.name}")
            }
        } as ExoPlayer
        MusicService::class.java.getDeclaredField("exoPlayer").apply { isAccessible = true }.set(service, player)
        val sample = MusicService::class.java.getDeclaredMethod("sampleListening", Boolean::class.javaPrimitiveType, Player.PositionInfo::class.java)
            .apply { isAccessible = true }
        fun tick(millis: Long, nextPosition: Long, before: Player.PositionInfo? = null) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(millis)); position = nextPosition
            sample.invoke(service, false, before)
        }
        tick(0, 0)
        tick(15_000, 15_000)
        assertEquals(15_000, snapshots.last().listenedMs)
        tick(15_000, 15_000) // Ready but stalled: no additional audio progressed.
        val before = Player.PositionInfo("window", 0, item, "period", 0, 15_000, 15_000, C.INDEX_UNSET, C.INDEX_UNSET)
        tick(1000, 45_000, before) // Seeking while stalled cannot credit that stalled second.
        assertEquals(15_000, snapshots.last().listenedMs)
        val occurrence = snapshots.last().occurrence
        selected = item.buildUpon().setUri("https://audio/replacement").build()
        tick(15_000, 60_000)
        assertEquals(occurrence, snapshots.last().occurrence)
        assertEquals(30_000, snapshots.last().listenedMs)
        assertEquals(1, snapshots.last().days.values.sumOf { it.plays })
        playing = false
        tick(5000, 65_000)
        tick(300_000, 65_000)
        assertEquals(35_000, snapshots.last().listenedMs)
        playing = true
        tick(0, 65_000)
        tick(15_000, 80_000)
        assertEquals(50_000, snapshots.last().listenedMs)
        tick(60_000, 140_000) // A genuinely advancing minute must not be capped at 30 seconds.
        assertEquals(110_000, snapshots.last().listenedMs)
        tick(60_000, 145_000) // Only five seconds advanced during a delayed/stalled interval.
        assertEquals(115_000, snapshots.last().listenedMs)
    }
}
