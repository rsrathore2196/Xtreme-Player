package com.example.ui.components

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.TrackLyrics
import com.example.data.model.MusicTrack
import com.example.data.remote.LyricsTextTransformer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SAF may recreate the Activity while playback advances. Persist only the import identity. */
internal val LyricsImportTargetSaver = Saver<MusicTrack?, List<String>>(
    save = { track -> track?.let { listOf(it.id, it.title, it.artist) }.orEmpty() },
    restore = { values -> values.takeIf { it.size == 3 }?.let { MusicTrack(it[0], it[1], it[2], "", 0, "", "") } }
)

/** Player-local request ownership; neither lyrics nor source playback is ever replaced. */
internal class LyricsTextController(
    private val original: TrackLyrics?,
    private val scope: CoroutineScope,
    private val transform: suspend (TrackLyrics, LyricsTextTransformer.Mode) -> LyricsTextTransformer.Result
) {
    data class State(val lyrics: TrackLyrics?, val active: LyricsTextTransformer.Mode? = null,
        val loading: LyricsTextTransformer.Mode? = null, val message: String? = null)
    private val mutable = MutableStateFlow(State(original))
    val state: StateFlow<State> = mutable.asStateFlow()
    private val prepared = mutableMapOf<LyricsTextTransformer.Mode, TrackLyrics>()
    private var job: Job? = null
    private var generation = 0L

    fun toggle(mode: LyricsTextTransformer.Mode) {
        val lyrics = original?.takeIf { it.lines.isNotEmpty() } ?: return
        val previous = mutable.value
        cancelPending()
        if (previous.active == mode || previous.loading == mode) {
            mutable.value = State(original)
            return
        }
        prepared[mode]?.let { mutable.value = State(it, active = mode); return }
        mutable.value = State(original, loading = mode)
        val request = generation
        job = scope.launch {
            val result = try { transform(lyrics, mode) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LyricsTextTransformer.Result.Unavailable }
            ensureActive()
            if (generation != request) return@launch
            mutable.value = when (result) {
                is LyricsTextTransformer.Result.Ready -> {
                    prepared[mode] = result.lyrics
                    State(result.lyrics, active = mode)
                }
                LyricsTextTransformer.Result.AlreadyRomanized -> State(original, message = "These lyrics already use Latin script")
                LyricsTextTransformer.Result.SameLanguage -> State(original, message = "These lyrics are already in your app language")
                LyricsTextTransformer.Result.Unavailable -> State(original, message = "Lyrics conversion is unavailable. Please try again later")
            }
        }
    }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
    fun cancelPending() {
        generation++
        job?.cancel(); job = null
        if (mutable.value.loading != null) mutable.value = State(original)
    }
}

@Composable
internal fun rememberLyricsTextController(lyrics: TrackLyrics?, visible: Boolean): LyricsTextController {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val language = config.locales[0].toLanguageTag()
    val scope = rememberCoroutineScope()
    val transformer = remember(context) { LyricsTextTransformer.get(context) }
    val controller = remember(lyrics, language) {
        LyricsTextController(lyrics, scope) { original, mode -> transformer.transform(original, mode, language) }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.cancelPending()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller) { onDispose { controller.cancelPending() } }
    LaunchedEffect(controller, visible) { if (!visible) controller.cancelPending() }
    return controller
}
