package com.example.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundCpuBudgetTest {
    @Test fun lowCoreDevicesUseOneBackgroundCpuLaneAndLargerDevicesKeepTwo() {
        for (cores in -1..3) assertEquals(1, backgroundCpuParallelism(cores))
        for (cores in 4..32) assertEquals(2, backgroundCpuParallelism(cores))
    }
}
