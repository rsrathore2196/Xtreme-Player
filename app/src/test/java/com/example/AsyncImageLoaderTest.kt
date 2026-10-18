package com.example

import android.app.Application
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.ui.util.AsyncImageLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AsyncImageLoaderTest {
    @Test fun initializationFailureBecomesAnImageErrorInsteadOfCrashingTheUi() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = AsyncImageLoader(context) { throw java.io.IOException("Unavailable cache") }
        try {
            val request = ImageRequest.Builder(context).data("https://cover.example/image.jpg").build()
            assertTrue(loader.execute(request) is coil.request.ErrorResult)
        } finally { loader.shutdown() }
    }
    @Test fun earlyRequestsSuspendAndShareOneBackgroundConstruction() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val builds = AtomicInteger()
        val mainBuilds = AtomicInteger()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val loader = AsyncImageLoader(context) {
            builds.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) mainBuilds.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ImageLoader.Builder(context).diskCache(null).memoryCache(null).build()
        }
        try {
            loader.start()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val request = ImageRequest.Builder(context).data(ColorDrawable(android.graphics.Color.RED)).size(32).build()
            val first = async { loader.execute(request) }
            val second = async { loader.execute(request) }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            // A UI command can execute while both image requests await cache initialization.
            var uiRan = false
            withContext(Dispatchers.Main.immediate) { uiRan = true }
            assertTrue(uiRan)
            release.countDown()
            assertTrue(first.await() is SuccessResult)
            assertTrue(second.await() is SuccessResult)
            assertSame(loader.await(), loader.await())
            assertEquals(1, builds.get())
            assertEquals(0, mainBuilds.get())
        } finally {
            release.countDown()
            loader.shutdown()
            Dispatchers.resetMain()
        }
    }
}
