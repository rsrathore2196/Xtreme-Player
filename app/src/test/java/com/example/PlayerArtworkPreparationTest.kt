package com.example

import android.app.Application
import androidx.compose.ui.graphics.Color
import com.example.performance.BackgroundWorkCoordinator
import com.example.ui.util.TrackPaletteCache
import com.example.ui.util.extractPlayerArtworkColors
import com.example.ui.util.preparePlayerArtworkColors
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerArtworkPreparationTest {
    private val colors = extractPlayerArtworkColors(IntArray(16) { 0xff3060c0.toInt() }, 4, 4)!!

    @Before fun setup() {
        TrackPaletteCache.clear()
        BackgroundWorkCoordinator.foreground(true)
        BackgroundWorkCoordinator.firstFrameRendered()
        (BackgroundWorkCoordinator::class.java.getDeclaredField("navigationUntil").apply { isAccessible = true }
            .get(null) as AtomicLong).set(0L)
    }

    @After fun cleanup() {
        TrackPaletteCache.clear()
        BackgroundWorkCoordinator.foreground(false)
    }

    @Test fun prefetchAndVisiblePlayerShareOnePreparationAndReuseItsTexture() = runTest {
        var preparations = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val prefetch = async {
            preparePlayerArtworkColors("same-cover") {
                preparations++
                started.complete(Unit)
                finish.await()
                colors
            }
        }
        started.await()
        val visible = async { preparePlayerArtworkColors("same-cover") { preparations++; colors } }
        runCurrent()
        assertFalse("The visible player must await the existing analysis", visible.isCompleted)
        finish.complete(Unit)
        assertSame(prefetch.await(), visible.await())
        assertSame(colors, preparePlayerArtworkColors("same-cover") { preparations++; colors })
        assertEquals("Only one sample decode/color extraction is needed", 1, preparations)
    }

    @Test fun cancellationReleasesTheFlightSoTheVisiblePlayerCanFinish() = runTest {
        val started = CompletableDeferred<Unit>()
        val prefetch = async {
            preparePlayerArtworkColors("cancelled-cover") {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        val visible = async { preparePlayerArtworkColors("cancelled-cover") { colors } }
        runCurrent()
        prefetch.cancelAndJoin()
        assertSame(colors, visible.await())
        assertSame(colors, TrackPaletteCache.get("cancelled-cover"))
    }

    @Test fun cacheMissWaitsForNavigationAndCanBeCancelledWhileWaiting() = runTest {
        var preparations = 0
        val transition = BackgroundWorkCoordinator.navigationBusy()
        try {
            val abandoned = async { preparePlayerArtworkColors("waiting-cover") { preparations++; colors } }
            runCurrent()
            assertEquals(0, preparations)
            abandoned.cancelAndJoin()
            val visible = async { preparePlayerArtworkColors("waiting-cover") { preparations++; colors } }
            runCurrent()
            assertEquals(0, preparations)
            transition.close()
            advanceTimeBy(50)
            runCurrent()
            assertSame(colors, visible.await())
            assertEquals(1, preparations)
        } finally { transition.close() }
    }

    @Test fun restoredColorsRegenerateTheirTextureAndDifferentCoversStaySeparate() = runTest {
        TrackPaletteCache.put("restored-cover", colors.copy(colorField = null))
        var preparations = 0
        assertSame(colors, preparePlayerArtworkColors("restored-cover") { preparations++; colors })
        val other = colors.copy(dominantColor = Color.Red)
        assertSame(other, preparePlayerArtworkColors("other-cover") { preparations++; other })
        assertSame(colors, TrackPaletteCache.get("restored-cover"))
        assertSame(other, TrackPaletteCache.get("other-cover"))
        assertEquals(2, preparations)
    }

    @Test fun unavailableArtworkDoesNotPoisonTheCacheOrBlockRetry() = runTest {
        assertNull(preparePlayerArtworkColors("retry-cover") { null })
        assertNull(TrackPaletteCache.get("retry-cover"))
        assertSame(colors, preparePlayerArtworkColors("retry-cover") { colors })
    }
}
