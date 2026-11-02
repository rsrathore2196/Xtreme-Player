package com.example

import android.app.Application
import android.content.Context
import android.util.LruCache
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.YouTubeMusicApiService
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.CatalogArtist
import com.example.performance.BackgroundWorkCoordinator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VisibleArtistPortraitTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    @Suppress("UNCHECKED_CAST")
    private fun memory() = ArtistPhotoRepository::class.java.getDeclaredField("memory").apply { isAccessible = true }
        .get(null) as LruCache<String, Any>
    @Before fun setup() {
        memory().evictAll()
        app.getSharedPreferences("artist_images_v2", 0).edit().clear().commit()
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.firstFrameRendered()
        BackgroundWorkCoordinator.scrolling(false)
    }
    @After fun close() {
        ArtistPhotoRepository.identityRequest = YouTubeMusicApiService::artistIdentity
        BackgroundWorkCoordinator.scrolling(false)
        BackgroundWorkCoordinator.foreground(false)
        memory().evictAll()
    }
    private fun row(id: String, image: String = "", expires: Long = System.currentTimeMillis() + 86_400_000) = JSONObject()
        .put("version", 7).put("source", "youtube").put("catalogId", id).put("canonicalName", "Saved Artist")
        .put("image", image).put("expires", expires).toString()

    @Test fun coldVisibleRestoreReadsOnlyItsSavedEntryWithoutBroadPrewarmOrNetworkEvenDuringScroll() = runBlocking {
        val prefs = app.getSharedPreferences("artist_images_v2", 0)
        val editor = prefs.edit()
        repeat(1100) { editor.putString("portrait:unrelated $it", row("UC$it", "https://yt3.googleusercontent.com/$it.jpg")) }
        editor.putString("portrait:saved artist", row("UCsaved", "https://yt3.googleusercontent.com/saved.jpg")).commit()
        val reads = AtomicInteger(); val keys = CopyOnWriteArrayList<String>()
        val watched = object : android.content.SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> { reads.incrementAndGet(); return prefs.all }
            override fun getString(key: String?, defValue: String?): String? { keys += key.orEmpty(); return prefs.getString(key, defValue) }
        }
        val context = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = if (name == "artist_images_v2") watched else super.getSharedPreferences(name, mode)
        }
        ArtistPhotoRepository.identityRequest = { _, _ -> error("A saved verified portrait must not search") }
        BackgroundWorkCoordinator.scrolling(true)
        val artist = CatalogArtist("", "Saved Artist")
        ArtistPhotoRepository.restoreSaved(context, listOf(artist))
        assertEquals("https://yt3.googleusercontent.com/saved.jpg", ArtistPhotoRepository.initialPhoto(artist))
        assertEquals("UCsaved", ArtistPhotoRepository.cachedArtist(artist.name).id)
        assertEquals(0, reads.get()); assertEquals(listOf("portrait:saved artist"), keys)
        ArtistPhotoRepository.restoreSaved(context, listOf(artist))
        assertEquals(1, keys.size)
    }

    @Test fun concurrentVisibleConsumersShareOneRequestAndOneCancellationDoesNotCancelTheOther() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger(); val cancellations = AtomicInteger()
        val artist = CatalogArtist("UCshared", "Shared Artist")
        ArtistPhotoRepository.identityRequest = { selected, _ ->
            calls.incrementAndGet(); entered.complete(Unit)
            try { finish.await(); selected.copy(image = "https://yt3.googleusercontent.com/shared.jpg") }
            catch (cancelled: CancellationException) { cancellations.incrementAndGet(); throw cancelled }
        }
        val first = async { ArtistPhotoRepository.photo(app, artist, false) }
        withTimeout(2_000) { entered.await() }
        val second = async { ArtistPhotoRepository.photo(app, artist, false) }
        withTimeout(2_000) { while (readers() < 2) delay(10) }
        first.cancelAndJoin()
        assertEquals(0, cancellations.get()); assertEquals(1, calls.get())
        finish.complete(Unit)
        assertEquals("https://yt3.googleusercontent.com/shared.jpg", withTimeout(2_000) { second.await() })
        memory().evictAll()
        ArtistPhotoRepository.restoreSaved(app, listOf(artist))
        assertEquals("https://yt3.googleusercontent.com/shared.jpg", ArtistPhotoRepository.initialPhoto(artist))
    }

    @Test fun releasingTheLastOffscreenConsumerCancelsItsUnneededLookup() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        ArtistPhotoRepository.identityRequest = { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val consumer = async { ArtistPhotoRepository.photo(app, CatalogArtist("UCoffscreen", "Offscreen Artist"), false) }
        withTimeout(2_000) { entered.await() }
        consumer.cancelAndJoin()
        withTimeout(2_000) { cancelled.await() }
        assertEquals(0, readers())
    }

    @Test fun diskIdentityIsReusedAndTransientNetworkFailureHasABoundedCooldown() = runBlocking {
        app.getSharedPreferences("artist_images_v2", 0).edit()
            .putString("portrait:saved artist", row("UCsaved", expires = 0)).commit()
        val calls = AtomicInteger()
        ArtistPhotoRepository.identityRequest = { selected, _ ->
            assertEquals("UCsaved", selected.id)
            calls.incrementAndGet(); throw java.io.IOException("Temporary failure")
        }
        assertNull(ArtistPhotoRepository.photo(app, CatalogArtist("", "Saved Artist"), false))
        assertNull(ArtistPhotoRepository.photo(app, CatalogArtist("", "Saved Artist"), false))
        assertEquals(1, calls.get())
        val saved = JSONObject(app.getSharedPreferences("artist_images_v2", 0).getString("portrait:saved artist", "")!!)
        assertTrue(saved.getLong("expires") - System.currentTimeMillis() in 1..60_000)
        assertEquals("UCsaved", saved.getString("catalogId"))
    }

    @Test fun visibleLookupsRemainBoundedToTwoConcurrentRequests() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger(); val started = AtomicInteger()
        val finish = CompletableDeferred<Unit>()
        ArtistPhotoRepository.identityRequest = { selected, _ ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf); started.incrementAndGet()
            try { finish.await(); selected.copy(image = "https://yt3.googleusercontent.com/${selected.id}.jpg") }
            finally { active.decrementAndGet() }
        }
        val jobs = (1..6).map { n -> async { ArtistPhotoRepository.photo(app, CatalogArtist("UCbounded$n", "Bounded $n"), false) } }
        withTimeout(2_000) { while (started.get() < 2) delay(10) }
        assertEquals(2, started.get()); assertEquals(2, peak.get())
        finish.complete(Unit); withTimeout(2_000) { jobs.awaitAll() }
        assertEquals(6, started.get()); assertEquals(2, peak.get())
    }

    @Suppress("UNCHECKED_CAST")
    private fun readers(): Int {
        val flights = ArtistPhotoRepository::class.java.getDeclaredField("flights").apply { isAccessible = true }.get(null) as Map<String, Any>
        return synchronized(flights) { flights.values.sumOf { it.javaClass.getDeclaredField("readers").apply { isAccessible = true }.getInt(it) } }
    }
}
