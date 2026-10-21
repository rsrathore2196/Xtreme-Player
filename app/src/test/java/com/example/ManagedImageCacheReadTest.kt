package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil.disk.DiskCache
import com.example.data.cache.ManagedImageDiskCache
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManagedImageCacheReadTest {
    @Test fun repeatedArtworkReadsTrackAccessWithoutQueryingOwnerSize() = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.cacheDir, "read-bookkeeping-test").apply { deleteRecursively(); mkdirs() }
        val delegate = DiskCache.Builder().directory(directory).maxSizeBytes(1024 * 1024).build()
        val reads = AtomicInteger()
        val observed = object : DiskCache by delegate {
            override val size: Long get() { reads.incrementAndGet(); return delegate.size }
        }
        val managed = ManagedImageDiskCache(observed, File(app.cacheDir, "read-bookkeeping-keys.json"))
        val editor = managed.openEditor("cover")!!
        editor.data.toFile().writeText("cached artwork")
        editor.commit()
        assertTrue(reads.get() > 0)
        reads.set(0)
        repeat(25) { managed.openSnapshot("cover")!!.close() }
        assertEquals("A cache hit cannot report size changes", 0, reads.get())
        assertTrue(managed.remove("cover"))
        assertTrue(reads.get() > 0)
        managed.clear()
    }
}
