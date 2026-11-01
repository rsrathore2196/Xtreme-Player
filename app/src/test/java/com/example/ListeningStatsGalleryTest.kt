package com.example

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.ui.util.ListeningStatsImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

internal class StatsGalleryProvider(private val app: Context, private val failWrite: Boolean = false,
    private val failPublish: Boolean = false, private val failInsert: Boolean = false) : ContentProvider() {
    val destination: Uri = Uri.parse("content://media/external_primary/images/media/42")
    @Volatile var inserted: ContentValues? = null
    @Volatile var published: ContentValues? = null
    @Volatile var deleted = false
    @Volatile var onMain = false
    val bytes = object : ByteArrayOutputStream() {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (failWrite) throw IOException("Gallery write failed")
            super.write(buffer, offset, length)
        }
    }
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        onMain = onMain || Looper.myLooper() == Looper.getMainLooper()
        if (failInsert) return null
        inserted = ContentValues(values)
        shadowOf(app.contentResolver).registerOutputStream(destination, bytes)
        return destination
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        onMain = onMain || Looper.myLooper() == Looper.getMainLooper()
        if (failPublish) return 0
        published = ContentValues(values); return 1
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { deleted = true; return 1 }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri) = "image/png"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ListeningStatsGalleryTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun image() = File.createTempFile("stats-", ".png", app.cacheDir).apply {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
    @Test fun savingPublishesTheExactPngInPicturesWithoutAStoragePickerOrMainThreadIo() = runBlocking {
        val provider = StatsGalleryProvider(app)
        ShadowContentResolver.registerProviderInternal("media", provider)
        val source = image()
        try {
            val saved = ListeningStatsImage.saveToGallery(app, source, "Xtreme-Player-Monthly-Stats.png")
            assertEquals(provider.destination, saved)
            assertEquals("image/png", provider.inserted!!.getAsString(MediaStore.Images.Media.MIME_TYPE))
            assertEquals("Pictures/Xtreme Player", provider.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            assertEquals(0, provider.published!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            assertArrayEquals(source.readBytes(), provider.bytes.toByteArray())
            assertFalse(provider.deleted); assertFalse(provider.onMain)
        } finally { source.delete() }
    }
    @Test fun failedWritingOrPublishingRemovesThePendingGalleryEntryAndPreservesTheShareSource() = runBlocking {
        for (writeFailure in listOf(true, false)) {
            val provider = StatsGalleryProvider(app, failWrite = writeFailure, failPublish = !writeFailure)
            ShadowContentResolver.registerProviderInternal("media", provider)
            val source = image()
            try {
                try { ListeningStatsImage.saveToGallery(app, source, "Stats.png"); fail("A failed gallery write cannot report success") }
                catch (_: IOException) { }
                assertTrue(provider.deleted); assertTrue(source.isFile)
            } finally { source.delete() }
        }
    }
    @Test fun failedMediaStoreInsertionDoesNotReportASavedImage() = runBlocking {
        val provider = StatsGalleryProvider(app, failInsert = true)
        ShadowContentResolver.registerProviderInternal("media", provider)
        val source = image()
        try {
            try { ListeningStatsImage.saveToGallery(app, source, "Stats.png"); fail("No Gallery row was created") }
            catch (_: IOException) { }
            assertFalse(provider.deleted)
        } finally { source.delete() }
    }
    @Test @Config(sdk = [28]) fun olderAndroidUsesItsMediaStoreWithoutScopedStorageOnlyColumns() = runBlocking {
        val provider = StatsGalleryProvider(app)
        ShadowContentResolver.registerProviderInternal("media", provider)
        val source = image()
        try {
            ListeningStatsImage.saveToGallery(app, source, "Stats.png")
            assertFalse(provider.inserted!!.containsKey("is_pending"))
            assertFalse(provider.inserted!!.containsKey("relative_path"))
            assertArrayEquals(source.readBytes(), provider.bytes.toByteArray())
        } finally { source.delete() }
    }
}
