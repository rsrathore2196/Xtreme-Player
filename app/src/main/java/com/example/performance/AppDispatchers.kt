package com.example.performance

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** Process-wide execution lanes. Never create another Default.limitedParallelism view.
 * Components accepting a CoroutineDispatcher can inject a test dispatcher instead.
 * Media3's audio renderer/loader threads deliberately do not use this CPU budget. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object AppDispatchers {
    val Main: CoroutineDispatcher = Dispatchers.Main.immediate
    val CpuBounded: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.Default.limitedParallelism(2), preemptible = true)
    val Io: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.IO)
    val Database: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.IO.limitedParallelism(4))
    val HomeBookkeeping: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.IO.limitedParallelism(1))
    val Imports: CoroutineDispatcher = BackgroundDispatcher(Dispatchers.IO.limitedParallelism(2))
}

/** Cooperative UI/optional CPU work carries this element. Critical playback does not. */
internal class NonEssentialWork(
    val owner: Job?,
    val canRun: () -> Boolean,
    val awaitAdmission: suspend () -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NonEssentialWork>
}

/** Decorates the existing shared pools, without allocating another executor or CPU budget. */
internal class BackgroundDispatcher(
    private val delegate: CoroutineDispatcher,
    private val preemptible: Boolean = false
) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            val optional = context[NonEssentialWork]
            if (preemptible && optional != null && optional.owner?.isActive != false &&
                context[Job]?.isActive != false && !optional.canRun()) {
                // Park a continuation, not a pool thread. Cancellation also releases the
                // continuation immediately so its finally blocks/leases can be cleaned up.
                val gateContext = context.minusKey(ContinuationInterceptor).minusKey(NonEssentialWork) + Dispatchers.IO
                CoroutineScope(gateContext).launch(start = CoroutineStart.UNDISPATCHED) {
                    try { optional.awaitAdmission() }
                    finally { dispatch(context, block) }
                }
            } else atBackgroundPriority(block)
        }
    }

    private fun atBackgroundPriority(block: Runnable) {
        val tid = Process.myTid()
        val previous = Process.getThreadPriority(tid)
        val lower = previous < Process.THREAD_PRIORITY_BACKGROUND
        try {
            if (lower) Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
            block.run()
        } finally {
            // Default/IO workers are shared. Never leave a reused worker demoted,
            // and never change Main or Media3's decoder/renderer/loader priorities.
            if (lower) Process.setThreadPriority(tid, previous)
        }
    }
}
