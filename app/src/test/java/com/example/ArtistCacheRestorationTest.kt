package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.LruCache
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.CatalogArtist
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArtistCacheRestorationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private class Reads(private val backing: SharedPreferences) : SharedPreferences by backing {
        val allReads = AtomicInteger()
        val strings = CopyOnWriteArrayList<String>()
        override fun getAll(): MutableMap<String, *> { allReads.incrementAndGet(); return backing.all }
        override fun getString(key: String?, defValue: String?): String? {
            strings.add(key.orEmpty()); return backing.getString(key, defValue)
        }
    }
    private fun context(reads: Reads) = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "artist_images_v2") reads else super.getSharedPreferences(name, mode)
    }
    @Suppress("UNCHECKED_CAST")
    private fun memory() = ArtistPhotoRepository::class.java.getDeclaredField("memory").apply { isAccessible = true }
        .get(null) as LruCache<String, Any>
    @Before @After fun reset() = runBlocking {
        BackgroundWorkCoordinator.scrolling(false)
        val field = ArtistPhotoRepository::class.java.getDeclaredField("warmup").apply { isAccessible = true }
        (field.get(null) as? Deferred<*>)?.cancelAndJoin()
        field.set(null, null)
        ArtistPhotoRepository::class.java.getDeclaredField("restoreDemanded").apply { isAccessible = true }.setBoolean(null, false)
        memory().evictAll()
    }
    private fun seed(hot: Boolean): Reads {
        val prefs = app.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
        val edit = prefs.edit().clear()
        repeat(1100) { index ->
            edit.putString("portrait:artist $index", JSONObject().put("version", 4).put("source", "youtube")
                .put("image", "https://portraits.example/$index.jpg")
                .put("expires", System.currentTimeMillis() + 86400000L).toString())
        }
        if (hot) edit.putStringSet(ArtistPhotoRepository.HOT_PORTRAIT_KEYS, (0..599).map { "portrait:artist $it" }.toSet())
        check(edit.commit())
        return Reads(prefs)
    }
    @Test fun indexedRestorationIsBoundedAndColdStoredPortraitsNeverNeedNetwork() = runBlocking {
        val reads = seed(hot = true)
        val context = context(reads)
        ArtistPhotoRepository.prewarm(context).await()
        assertEquals(0, reads.allReads.get())
        assertEquals(ArtistPhotoRepository.MAX_RESTORED_PORTRAITS, reads.strings.size)
        assertEquals("https://portraits.example/1099.jpg",
            withTimeout(2000) { ArtistPhotoRepository.photo(context, CatalogArtist("", "Artist 1099")) })
        assertEquals(0, reads.allReads.get())
        assertTrue(reads.strings.contains("portrait:artist 1099"))
        val before = reads.strings.size
        ArtistPhotoRepository.photo(context, CatalogArtist("", "Artist 1099"))
        assertEquals(before, reads.strings.size)
    }
    @Test fun legacyCacheKeepsPersistentEntriesBeyondTheBoundedRestore() = runBlocking {
        val reads = seed(hot = false)
        val context = context(reads)
        ArtistPhotoRepository.prewarm(context).await()
        assertEquals(ArtistPhotoRepository.MAX_RESTORED_PORTRAITS, memory().size())
        val cold = (0..1099).first { !memory().snapshot().containsKey("portrait:artist $it") }
        assertEquals("https://portraits.example/$cold.jpg",
            withTimeout(2000) { ArtistPhotoRepository.photo(context, CatalogArtist("", "Artist $cold")) })
        assertEquals(1, reads.allReads.get())
    }
    @Test fun aVisiblePortraitPromotesAutomaticRestoreDuringInteraction() = runBlocking {
        val reads = seed(hot = true)
        val context = context(reads)
        BackgroundWorkCoordinator.scrolling(true)
        val automatic = ArtistPhotoRepository.prewarm(context, automatic = true)
        delay(75)
        assertFalse(automatic.isCompleted)
        assertEquals("https://portraits.example/1099.jpg",
            withTimeout(2000) { ArtistPhotoRepository.photo(context, CatalogArtist("", "Artist 1099")) })
        assertTrue(automatic.isCompleted)
    }
    @Test fun obsoleteNegativeArtistLookupsAreRetriedButVerifiedPortraitsAndCurrentCooldownsSurvive() = runBlocking {
        val prefs = app.getSharedPreferences("artist_images_v2", Context.MODE_PRIVATE)
        fun row(version: Int, url: String = "", id: String = "") = JSONObject().put("version", version)
            .put("source", "youtube").put("image", url).put("catalogId", id)
            .put("expires", System.currentTimeMillis() + 86400000L).toString()
        prefs.edit().clear()
            .putString("portrait:old miss", row(5))
            .putString("portrait:current miss", row(6))
            .putString("portrait:verified artist", row(5, "https://lh3.googleusercontent.com/portrait=w600-h600", "UCverified"))
            .commit()
        ArtistPhotoRepository.prewarm(app).await()
        assertNull(memory().get("portrait:old miss"))
        assertNotNull(memory().get("portrait:current miss"))
        assertEquals("https://lh3.googleusercontent.com/portrait=w600-h600",
            ArtistPhotoRepository.cachedPhoto(CatalogArtist("UCverified", "Verified Artist")))
    }

}
