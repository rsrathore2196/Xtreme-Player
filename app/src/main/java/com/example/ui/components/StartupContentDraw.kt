package com.example.ui.components

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
