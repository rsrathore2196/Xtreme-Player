package com.example.ui.util

import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Queue copies/shuffles share the CPU budget; a newer selection cancels pending preparation. */
internal class CollectionPlaybackPreparation(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = AppDispatchers.CpuBounded,
    private val onPlay: (MusicTrack, List<MusicTrack>) -> Unit
) {
    private var pending: Job? = null
    fun play(track: MusicTrack, queue: List<MusicTrack>) {
        cancel()
        onPlay(track, queue)
    }
    fun shuffle(tracks: List<MusicTrack>) {
        cancel()
        if (tracks.isEmpty()) return
        pending = scope.launch {
            val queue = withContext(dispatcher) { tracks.shuffled() }
            onPlay(queue.first(), queue)
        }
    }
    fun cancel() { pending?.cancel(); pending = null }
}
