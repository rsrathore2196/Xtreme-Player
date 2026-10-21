package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedLibrarySnapshotsTest {
    @Test fun homeLibraryAndReentryShareOneAuthoritativeObserverAndConvertedSnapshot() = runBlocking {
        val db = MusicDatabase.createInMemoryDatabase(ApplicationProvider.getApplicationContext())
        val favorites = AtomicInteger()
        val recents = AtomicInteger()
        val row = TrackEntity("one", "Song", "Artist", "Album", 180_000, "cover", "url",
            isLiked = true, lastPlayedAt = 123L)
        val source = MutableStateFlow(listOf(row))
        val dao = object : MusicDao by db.musicDao() {
            override fun getFavoriteTracks() = source.onStart { favorites.incrementAndGet() }
            override fun getRecentlyPlayedTracks() = source.onStart { recents.incrementAndGet() }
        }
        val repo = MusicRepository(dao)
        try {
            withTimeout(5_000) {
                val first = repo.getFavoriteTracks().first()
                val recent = repo.getRecentlyPlayedTracks().first()
                repeat(3) {
                    assertSame(first, repo.getFavoriteTracks().first())
                    assertSame(recent, repo.getRecentlyPlayedTracks().first())
                }
                assertEquals(1, favorites.get()); assertEquals(1, recents.get())
                source.value = listOf(row.copy(title = "Edited"))
                assertEquals("Edited", repo.getFavoriteTracks().first { it.first().title == "Edited" }.single().title)
                assertEquals("Edited", repo.getRecentlyPlayedTracks().first { it.first().title == "Edited" }.single().title)
                assertEquals(1, favorites.get()); assertEquals(1, recents.get())
            }
        } finally {
            val scope = MusicRepository::class.java.getDeclaredField("initialCatalogScope").apply { isAccessible = true }
                .get(repo) as CoroutineScope
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }
}
