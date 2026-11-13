package com.example.ui.viewmodel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive

/** Main-thread ownership covers preparation too, so an older lookup cannot replace a newer selection. */
internal class PlaybackLaunchOwner {
    var current: Job? = null
        set(value) {
            if (field !== value) field?.cancel()
            field = value
        }

    fun <T, R> prepare(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        load: suspend () -> T,
        play: suspend (T) -> R
    ): Deferred<R> {
        val request = scope.async(dispatcher, start = CoroutineStart.LAZY) {
            val value = load()
            ensureActive()
            play(value)
        }
        current = request
        request.start()
        return request
    }
}
