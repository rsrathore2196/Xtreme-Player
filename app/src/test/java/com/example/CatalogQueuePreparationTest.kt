package com.example

import android.app.Application
import android.os.Looper
import com.example.data.model.MusicTrack
import com.example.data.repository.CatalogDestination
import com.example.data.repository.CatalogPlaylist
import com.example.ui.viewmodel.CatalogDetailState
import com.example.ui.viewmodel.preparePlaybackQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CatalogQueuePreparationTest {
    private fun track(id: String, artist: String = "Original Artist") = MusicTrack(id, id, artist,
        "Album", 180_000, "", "https://audio.example/$id.m4a")

    @Test fun largeCatalogDeduplicationRunsOffMainAndPreservesFirstMetadataAndOrder() = runBlocking {
        val readOnMain = AtomicBoolean()
        val source = List(10_000) { track("song_$it") }
        val observed = object : AbstractList<MusicTrack>() {
            override val size get() = source.size
            override fun get(index: Int): MusicTrack {
                if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
                return source[index]
            }
        }
        val state = CatalogDetailState(CatalogDestination.Playlist(CatalogPlaylist("123", "Playlist", "")),
            tracks = observed, pending = listOf(track("song_5", "Duplicate metadata"), track("last")))
        val queue = state.preparePlaybackQueue(source[5])
        assertFalse("Large list traversal must use CpuBounded", readOnMain.get())
        assertEquals(10_001, queue.size)
        assertEquals("song_0", queue.first().id)
        assertEquals("last", queue.last().id)
        assertSame(source[5], queue[5])
    }

    @Test fun emptyCatalogRetainsTheSelectedRecording() = runBlocking {
        val selected = track("selected")
        val state = CatalogDetailState(CatalogDestination.Playlist(CatalogPlaylist("123", "Playlist", "")))
        assertEquals(listOf(selected), state.preparePlaybackQueue(selected))
    }
}
