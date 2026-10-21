package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** LastWave's lyrics-only screen-on behavior; no wake lock or background service. */
@Composable
internal fun KeepLyricsScreenAwake(visible: Boolean, enabled: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val keepAwake = visible && enabled && state == Lifecycle.State.RESUMED
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        if (keepAwake) view.keepScreenOn = true
        onDispose { if (keepAwake) view.keepScreenOn = previous }
    }
}
