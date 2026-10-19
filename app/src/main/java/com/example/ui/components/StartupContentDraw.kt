package com.example.ui.components

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.performance.AppDispatchers
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Release background readiness fences after preparation; no modal UI or input gate. */
@Composable
internal fun ObserveInitialContentReadiness() {
    val checkpoint by remember {
        BackgroundWorkCoordinator.startupReadiness.map { it.preparationComplete to it.presentationGeneration }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = BackgroundWorkCoordinator.startupReadiness.value.let {
        it.preparationComplete to it.presentationGeneration
    })
    val (prepared, generation) = checkpoint
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    LaunchedEffect(lifecycle, view, prepared, generation) {
        if (!prepared || BackgroundWorkCoordinator.startupReadiness.value.complete) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            view.awaitStartupContentDraw { BackgroundWorkCoordinator.initialContentDrawn() }
            withContext(AppDispatchers.Io) { BackgroundWorkCoordinator.awaitInitialPresentationSettled() }
        }
    }
}

/** One requested real traversal, even when Compose reuses the parent's RenderNode.
 * Called on Main after the prepared snapshot has entered composition. */
internal suspend fun View.awaitStartupContentDraw(onDrawn: () -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
    val observer = viewTreeObserver
    val main = Handler(Looper.getMainLooper())
    var observed = false
    lateinit var listener: ViewTreeObserver.OnDrawListener
    fun remove() {
        if (observer.isAlive) observer.removeOnDrawListener(listener)
        else if (viewTreeObserver.isAlive) viewTreeObserver.removeOnDrawListener(listener)
    }
    listener = ViewTreeObserver.OnDrawListener {
        if (!observed && continuation.isActive) {
            observed = true
            onDrawn()
            // Do not remove a listener inside dispatchOnDraw. Continue after the
            // traversal; there is no frame ticker, forced layout or polling draw.
            main.post {
                remove()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
    observer.addOnDrawListener(listener)
    continuation.invokeOnCancellation { main.post { remove() } }
    postInvalidateOnAnimation()
}
