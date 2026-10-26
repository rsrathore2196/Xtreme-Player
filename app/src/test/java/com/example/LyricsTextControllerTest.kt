package com.example

import android.app.Application
import com.example.data.model.*
import com.example.data.remote.LyricsTextTransformer
import com.example.data.remote.LyricsTextTransformer.Mode
import com.example.data.remote.LyricsTextTransformer.Result
import com.example.ui.components.LyricsTextController
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class LyricsTextControllerTest {
    private val source = TrackLyrics("song", "Title", "Artist", true, listOf(LyricLine(1000, "दिल मेरा")))
    @Test fun preparingOriginalLyricsNeverStartsTextConversion() = runTest {
        var requests = 0
        val controller = LyricsTextController(source, this) { _, _ -> requests++; Result.Unavailable }
        testScheduler.advanceUntilIdle()
        assertSame(source, controller.state.value.lyrics)
        assertEquals(0, requests)
    }
    @Test fun toggleOnlyActivatesAfterSuccessAndOffImmediatelyRestoresTheOriginal() = runTest {
        val answer = CompletableDeferred<Result>(); var calls = 0
        val translated = source.copy(lines = source.lines.map { it.copy(translation = "My heart") })
        val controller = LyricsTextController(source, this) { _, _ -> calls++; answer.await() }
        controller.toggle(Mode.TRANSLATE)
        testScheduler.runCurrent()
        assertNull(controller.state.value.active); assertEquals(Mode.TRANSLATE, controller.state.value.loading)
        answer.complete(Result.Ready(translated)); testScheduler.runCurrent()
        assertEquals(Mode.TRANSLATE, controller.state.value.active)
        controller.toggle(Mode.TRANSLATE)
        assertSame(source, controller.state.value.lyrics); assertNull(controller.state.value.active)
        controller.toggle(Mode.TRANSLATE)
        assertEquals(Mode.TRANSLATE, controller.state.value.active); assertEquals(1, calls)
    }
    @Test fun switchingModesOrChangingTracksRejectsLateCallbacks() = runTest {
        val late = CompletableDeferred<Result>(); val latest = CompletableDeferred<Result>()
        val controller = LyricsTextController(source, this) { _, mode ->
            withContext(NonCancellable) { if (mode == Mode.TRANSLATE) late.await() else latest.await() }
        }
        controller.toggle(Mode.TRANSLATE); testScheduler.runCurrent()
        controller.toggle(Mode.ROMANIZE); testScheduler.runCurrent()
        latest.complete(Result.Ready(source.copy(hasRomanization = true))); testScheduler.runCurrent()
        late.complete(Result.Ready(source.copy(hasTranslation = true))); testScheduler.runCurrent()
        assertEquals(Mode.ROMANIZE, controller.state.value.active)
        val pending = CompletableDeferred<Result>()
        val oldTrack = LyricsTextController(source, this) { _, _ -> withContext(NonCancellable) { pending.await() } }
        oldTrack.toggle(Mode.TRANSLATE); testScheduler.runCurrent(); oldTrack.cancelPending()
        pending.complete(Result.Ready(source.copy(hasTranslation = true))); testScheduler.runCurrent()
        assertNull(oldTrack.state.value.active); assertSame(source, oldTrack.state.value.lyrics)
    }
    @Test fun failuresAndSameLanguageNeverShowAnActiveConversion() = runTest {
        for (answer in listOf(Result.Unavailable, Result.SameLanguage, Result.AlreadyRomanized)) {
            val controller = LyricsTextController(source, this) { _, _ -> answer }
            controller.toggle(Mode.TRANSLATE); testScheduler.runCurrent()
            assertNull(controller.state.value.active); assertNull(controller.state.value.loading)
            assertSame(source, controller.state.value.lyrics); assertNotNull(controller.state.value.message)
        }
    }
    @Test fun cancellingALoadingToggleLeavesOriginalTextAndNoRunningRequest() = runTest {
        var cancelled = false
        val controller = LyricsTextController(source, this) { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }
        controller.toggle(Mode.ROMANIZE); testScheduler.runCurrent(); controller.toggle(Mode.ROMANIZE); testScheduler.runCurrent()
        assertTrue(cancelled); assertNull(controller.state.value.active); assertNull(controller.state.value.loading)
        assertSame(source, controller.state.value.lyrics)
    }
}
