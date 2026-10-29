package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.performance.BackgroundWorkCoordinator

/** Schedule one session check independently of tab selection and permission dialogs. */
@Composable
internal fun AutomaticAppUpdateCheck(owner: Any, claim: () -> Boolean, check: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentClaim by rememberUpdatedState(claim)
    val currentCheck by rememberUpdatedState(check)
    LaunchedEffect(owner, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            BackgroundWorkCoordinator.awaitFirstFrame()
            BackgroundWorkCoordinator.awaitInitialPresentation()
            if (currentClaim()) currentCheck()
        }
    }
}
