package com.example.performance

/** Leave room for Main, rendering and audio on devices with only a few cores. */
internal fun backgroundCpuParallelism(processors: Int): Int = if (processors <= 3) 1 else 2
