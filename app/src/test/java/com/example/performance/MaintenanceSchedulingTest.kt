package com.example.performance

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class MaintenanceSchedulingTest {
    @Test fun aHeadlessWorkerDoesNotDependOnHomeOrFirstFrameMilestones() = kotlinx.coroutines.test.runTest {
        BackgroundWorkCoordinator.foreground(false)
        val pausedNavigation = BackgroundWorkCoordinator.navigationBusy()
        try { kotlinx.coroutines.withTimeout(1_000L) { BackgroundWorkCoordinator.awaitMaintenanceIdle() } }
        finally { pausedNavigation.close() }
    }
    @Test fun maintenanceCannotRunDuringTheStartupWindow() {
        val request = MaintenanceScheduler.cacheRequest()
        assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.initialDelay)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertFalse(request.workSpec.constraints.requiresDeviceIdle())
    }

    @Test fun repeatedForegroundsKeepOnePeriodicCacheJobAndOneIdleLogJob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        MaintenanceScheduler.schedule(context)
        MaintenanceScheduler.schedule(context)
        val manager = WorkManager.getInstance(context)
        val caches = manager.getWorkInfosForUniqueWork(MaintenanceScheduler.PERIODIC_CACHE).get()
        val logs = manager.getWorkInfosForUniqueWork(MaintenanceScheduler.LOG_WORK).get()
        assertEquals(1, caches.size)
        assertEquals(1, logs.size)
        assertEquals(WorkInfo.State.ENQUEUED, caches.single().state)
        assertEquals(WorkInfo.State.ENQUEUED, logs.single().state)
        assertTrue(logs.single().constraints.requiresDeviceIdle())
        manager.cancelAllWork().result.get()
    }
}
