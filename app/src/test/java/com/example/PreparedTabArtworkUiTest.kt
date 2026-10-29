package com.example

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.StartupReadiness
import com.example.performance.StartupTask
import com.example.performance.StartupStatus
import com.example.ui.components.MainTabPager
import com.example.ui.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreparedTabArtworkUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun measuredHiddenTabsDecodeTheirFirstViewportBeforeTheFirstNavigationAndKeepThePainter() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val coordinator = BackgroundWorkCoordinator
        @Suppress("UNCHECKED_CAST")
        val readiness = coordinator.javaClass.getDeclaredField("_startupReadiness").apply { isAccessible = true }
            .get(null) as MutableStateFlow<StartupReadiness>
        (coordinator.javaClass.getDeclaredField("initialDeadlineJob").apply { isAccessible = true }.get(null) as? kotlinx.coroutines.Job)?.cancel()
        for (name in listOf("initialStartedAt", "navigationUntil")) {
            (coordinator.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicLong).set(0)
        }
        readiness.value = StartupReadiness()
        coordinator.firstFrameRendered(); coordinator.foreground(true); coordinator.scrolling(false)
        coordinator.startupTask(StartupTask.HOME_CACHE, StartupStatus.READY)
        coordinator.startupTask(StartupTask.LOCAL_TABS, StartupStatus.READY)
        val files = List(3) { page -> java.io.File.createTempFile("prepared-tab-$page-", ".png").apply {
            android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(40 + page * 60, 80, 120))
            }.let { bitmap -> outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        } }
        val oldLoader = coil.Coil.imageLoader(context)
        val decoded = AtomicInteger()
        val loader = coil.ImageLoader.Builder(context).components {
            add(ArtworkResolutionInterceptor())
            add(coil.intercept.Interceptor { chain -> chain.proceed(chain.request).also { result ->
                if (result is coil.request.SuccessResult && result.dataSource != coil.decode.DataSource.MEMORY_CACHE) decoded.incrementAndGet()
            } })
        }.build()
        coil.Coil.setImageLoader(loader)
        lateinit var view: android.view.View
        lateinit var pager: PagerState
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        val successes = BooleanArray(3)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { MaterialTheme {
                view = LocalView.current
                pager = rememberPagerState(pageCount = { 3 })
                scope = rememberCoroutineScope()
                MainTabPager(pager, pageCovered = false, prepareStartup = true) { page ->
                    Column(Modifier.fillMaxSize()) {
                        coil.compose.AsyncImage(rememberOptimizedImageRequest(files[page].toURI().toString(), targetSize = 100),
                            null, Modifier.size(44.dp), onSuccess = { successes[page] = true })
                    }
                }
            } }
            compose.waitUntil(8_000) {
                compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
                compose.runOnIdle { view.viewTreeObserver.dispatchOnDraw() }
                successes.all { it } && listOf(StartupTask.HOME_LAYOUT, StartupTask.SEARCH_LAYOUT, StartupTask.LIBRARY_LAYOUT)
                    .all { readiness.value.tasks.getValue(it).terminal }
            }
            assertEquals("Only Home has ever been selected", 0, pager.currentPage)
            val before = decoded.get()
            assertTrue(before >= 3)
            compose.runOnIdle { scope.launch { pager.animateScrollToPage(2) } }
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            assertEquals("The first Library visit reuses its prepared painter and decoded size", before, decoded.get())
        } finally {
            readiness.value = readiness.value.expirePreparation()
            (coordinator.javaClass.getDeclaredField("initialPresentationPending").apply { isAccessible = true }.get(null) as AtomicBoolean).set(false)
            coordinator.foreground(false)
            coil.Coil.setImageLoader(oldLoader); loader.shutdown(); files.forEach { it.delete() }
        }
    }
}
