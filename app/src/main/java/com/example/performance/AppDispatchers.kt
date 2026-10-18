package com.example.performance

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Process-wide execution lanes. Never create another Default.limitedParallelism view.
 * Components accepting a CoroutineDispatcher can inject a test dispatcher instead.
 * Media3's audio renderer/loader threads deliberately do not use this CPU budget. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object AppDispatchers {
    val Main: CoroutineDispatcher = Dispatchers.Main.immediate
    val CpuBounded: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)
    val Io: CoroutineDispatcher = Dispatchers.IO
    val Database: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)
    val HomeBookkeeping: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    val Imports: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
}
