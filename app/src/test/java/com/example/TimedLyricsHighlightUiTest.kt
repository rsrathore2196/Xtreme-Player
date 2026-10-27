package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.example.data.model.*
import com.example.ui.components.SyncedLyricsView
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LocalAppColors
import com.github.takahirom.roborazzi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class, qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimedLyricsHighlightUiTest {
    @get:Rule val compose = createComposeRule()
    private val position = mutableLongStateOf(1_100L)
    private var text = "sunlight waiting"
    private var light = false
    private var renderedPosition = Long.MIN_VALUE
    private var capturedLine: Pair<BufferedImage, TextLayoutResult>? = null

    private fun show(syllables: Boolean = true, lightBackground: Boolean = false, wrapped: Boolean = false, highContrast: Boolean = false) {
        text = if (wrapped) "sunlight\nwaiting" else "sunlight waiting"
        light = lightBackground
        val parts = if (syllables) listOf(LyricSyllable(1_000, 1_000, "sun"), LyricSyllable(3_000, 1_000, "light")) else emptyList()
        val line = LyricLine(1_000, text, 7_000,
            listOf(LyricWord(1_000, 3_000, "sunlight", parts), LyricWord(6_000, 1_500, "waiting")),
            isReliableDuration = true)
        val lyrics = TrackLyrics("timed", "Song", "Artist", true, listOf(line),
            hasWordTiming = true, hasSyllableTiming = syllables)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val currentTime = position.longValue
            SideEffect { renderedPosition = currentTime }
            CompositionLocalProvider(LocalAppColors provides DarkAppColors.copy(highContrastTypography = highContrast)) {
                Box(Modifier.fillMaxSize().background(if (light) Color.White else Color.Black)) {
                    SyncedLyricsView(lyrics, currentPositionMs = currentTime, isPlaying = false,
                        isDark = !light, accentColor = if (light) Color.Black else Color.White,
                        onSeekTo = {}, currentPositionProvider = { position.longValue }, viewportAnimating = false)
                }
            }
        } }
        compose.mainClock.advanceTimeBy(800)
    }

    private fun at(time: Long) {
        capturedLine = null
        compose.runOnIdle { position.longValue = time }
        compose.waitUntil(3_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { renderedPosition == time }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun ink(range: IntRange): Int {
        val (image, layout) = capturedLine ?: captureLine().also { capturedLine = it }
        val scaleX = image.width.toFloat() / layout.size.width
        val scaleY = image.height.toFloat() / layout.size.height
        var maximum = 0
        for (offset in range) {
            val box = layout.getBoundingBox(offset)
            for (y in floor(box.top * scaleY).toInt().coerceAtLeast(0) until ceil(box.bottom * scaleY).toInt().coerceAtMost(image.height)) {
                for (x in floor(box.left * scaleX).toInt().coerceAtLeast(0) until ceil(box.right * scaleX).toInt().coerceAtMost(image.width)) {
                    val pixel = image.getRGB(x, y)
                    val alpha = (pixel ushr 24) and 255
                    val red = (pixel shr 16) and 255
                    val value = (red * alpha + (if (light) 255 else 0) * (255 - alpha)) / 255
                    maximum = maxOf(maximum, if (light) 255 - value else value)
                }
            }
        }
        return maximum
    }

    private fun captureLine(): Pair<BufferedImage, TextLayoutResult> {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val file = java.io.File.createTempFile("timed-lyrics-", ".png")
        try {
            node.captureRoboImage(file.absolutePath, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
            val image: BufferedImage = javax.imageio.ImageIO.read(file)
            val debugFile = java.io.File("build/reports/lyrics-review/${android.os.Build.VERSION.SDK_INT}-${if (light) "light" else "dark"}-${position.longValue}.png")
            debugFile.parentFile?.mkdirs()
            javax.imageio.ImageIO.write(image, "png", debugFile)
            return image to layout
        } finally { file.delete() }
    }

    @Test fun syllableHighlightLeavesFutureSyllablesAndWordsDim() {
        show()
        at(2_500)
        at(2_600)
        assertTrue("Sung syllables remain highlighted", ink(0..2) > 230)
        assertTrue("Future syllables must not inherit the active paragraph paint", ink(4..7) < 190)
        assertTrue("Future words keep their inactive contrast even when their line is focused", ink(9..15) < 130)
    }

    @Test fun completedWordsHoldThroughVocalGaps() {
        show()
        at(4_500)
        assertTrue("A completed word stays bright during a vocal gap", ink(0..7) > 230)
        assertTrue("A vocal gap cannot activate the next word", ink(9..15) < 190)
    }

    @Test fun highContrastDoesNotActivateFutureWords() {
        show(highContrast = true)
        at(2_500)
        at(2_600)
        assertTrue("Sung syllables retain full contrast", ink(0..2) > 230)
        assertTrue("High contrast cannot highlight a word before its vocal", ink(9..15) < 130)
    }

    @Test fun laterWordsBrightenOnlyAsTheirVocalsArrive() {
        show()
        at(7_000)
        assertTrue("The next word brightens when its vocal arrives", ink(9..12) > 230)
        assertTrue("Its final syllables remain dim until the reveal arrives", ink(14..15) < 190)
    }

    @Test fun backwardSeekingResetsTheFutureWordHighlight() {
        show()
        at(7_000)
        at(2_600)
        assertTrue("Backward seeking resets the future-word highlight", ink(9..15) < 190)
    }

    @Test fun wordTimingRevealsOnlyTheCurrentWord() {
        show(syllables = false)
        at(2_500)
        at(2_600)
        assertTrue(ink(0..2) > 230)
        assertTrue(ink(9..15) < 190)
    }

    @Test fun lightBackdropUsesTheSameProgressiveContrast() {
        show(syllables = false, lightBackground = true)
        at(2_500)
        at(2_600)
        assertTrue(ink(0..2) > 230)
        assertTrue(ink(9..15) < 190)
    }

    @Test fun wrappedUpcomingWordsStayDim() {
        show(wrapped = true)
        at(2_500)
        at(2_600)
        assertTrue(ink(9..15) < 190)
    }
}
