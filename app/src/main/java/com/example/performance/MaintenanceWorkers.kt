package com.example.performance

import android.content.Context
import androidx.work.*
import com.example.data.cache.ManagedImageDiskCache
import com.example.data.cache.SmartCacheManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.TimeUnit

/** Scheduling is called on IO after the first draw, never by a provider on Main. */
object MaintenanceScheduler {
    internal const val CACHE_WORK = "xtreme-cache-maintenance-v1"
    internal const val PERIODIC_CACHE = "xtreme-periodic-cache-v1"
    internal const val AUTO_BACKUP = "xtreme-auto-backup-v1"
    private val backupScheduling = Mutex()
    private val schedulingScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + AppDispatchers.Io)
    internal const val LOG_WORK = "xtreme-log-cleanup-v1"
    internal const val INITIAL_DELAY_MINUTES = 15L
    private val workerLock = Mutex()
    private val requested = java.util.concurrent.atomic.AtomicBoolean()

    internal fun cacheRequest() = OneTimeWorkRequestBuilder<CacheMaintenanceWorker>()
        .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()

    fun schedule(context: Context) {
        updateAutoBackup(context)
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(PERIODIC_CACHE, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheMaintenanceWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        manager.enqueueUniquePeriodicWork(LOG_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LogCleanupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true).build()).build())
    }
    internal fun backupRequest() = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()

    fun updateAutoBackup(context: Context) {
        val app = context.applicationContext
        schedulingScope.launch {
          backupScheduling.withLock {
           try {
            val manager = WorkManager.getInstance(app)
            if (com.example.data.local.BackupRestoreManager.isAutoBackupEnabled(app)) {
                manager.enqueueUniquePeriodicWork(AUTO_BACKUP, ExistingPeriodicWorkPolicy.KEEP,
                    backupRequest())
            } else manager.cancelUniqueWork(AUTO_BACKUP)
           } catch (cancelled: CancellationException) { throw cancelled }
           catch (error: Exception) { android.util.Log.w("AutoBackup", "Backup scheduling will retry on next startup", error) }
          }
        }
    }

    fun requestCacheMaintenance(context: Context) {
        if (!requested.compareAndSet(false, true)) return
        try { WorkManager.getInstance(context).enqueueUniqueWork(CACHE_WORK, ExistingWorkPolicy.KEEP, cacheRequest()) }
        catch (error: Exception) { requested.set(false); throw error }
    }
    internal fun cacheWorkStarted() { requested.set(false) }
    internal suspend fun <T> serial(block: suspend () -> T): T = workerLock.withLock { block() }
}

class CacheMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(AppDispatchers.Io) {
        try {
            BackgroundWorkCoordinator.maintenance { MaintenanceScheduler.serial {
                MaintenanceScheduler.cacheWorkStarted()
                BackgroundWorkCoordinator.awaitMaintenanceIdle()
                val app = applicationContext as? com.example.XtremeMusicApp
                // Await the prepared singleton; never open a second Coil cache/journal.
                val cache = app?.awaitImageLoader()?.diskCache as? ManagedImageDiskCache
                cache?.flushAccessIndex()
                // Retire only obsolete provider metadata, never the user database.
                if (applicationContext.getDatabasePath("lastfm_recommendations.db").exists())
                    applicationContext.deleteDatabase("lastfm_recommendations.db")
                com.example.data.remote.LyricsProvider.maintainDiskCache()
                SmartCacheManager.performScheduledMaintenance(applicationContext)
            } }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.w("CacheMaintenance", "Deferred maintenance unavailable", error)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

class LogCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(AppDispatchers.Io) {
        BackgroundWorkCoordinator.maintenance { MaintenanceScheduler.serial {
            BackgroundWorkCoordinator.awaitMaintenanceIdle()
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
            // Only app-owned diagnostic directories; never descend into Coil/Media3/HTTP caches.
            for (name in listOf("logs", "crash_reports", "diagnostics")) {
                val root = File(applicationContext.cacheDir, name)
                if (!root.exists()) continue
                val pending = java.util.ArrayDeque<File>().apply { add(root) }
                val visited = mutableSetOf<String>()
                var count = 0
                while (pending.isNotEmpty()) {
                    val directory = pending.removeFirst()
                    val path = directory.canonicalPath
                    if (!path.startsWith(root.canonicalPath + File.separator) && path != root.canonicalPath || !visited.add(path)) continue
                    for (file in directory.listFiles().orEmpty()) {
                        if (++count % 64 == 0) {
                            currentCoroutineContext().ensureActive()
                            yield()
                            BackgroundWorkCoordinator.yieldToInteraction(bypass = { !BackgroundWorkCoordinator.uiForeground.value })
                        }
                        if (file.isDirectory) pending.add(file)
                        else if (file.extension in setOf("log", "txt", "tmp") && file.lastModified() < cutoff) file.delete()
                    }
                }
            }
        } }
        Result.success()
    }
}

/** Reuses the same writer, storage grants, maintenance ownership and diagnostics as manual backup. */
class AutoBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(AppDispatchers.Io) {
        if (!com.example.data.local.BackupRestoreManager.isAutoBackupEnabled(applicationContext)) return@withContext Result.success()
        BackgroundWorkCoordinator.maintenance { MaintenanceScheduler.serial {
            BackgroundWorkCoordinator.awaitMaintenanceIdle()
            if (!com.example.data.local.BackupRestoreManager.isAutoBackupEnabled(applicationContext)) return@serial Result.success()
            val database = com.example.data.local.MusicDatabase.getDatabase(applicationContext)
            val saved = com.example.data.local.BackupRestoreManager.createBackup(applicationContext, database)
            saved.exceptionOrNull()?.let { android.util.Log.w("AutoBackup", "Scheduled backup could not be saved", it) }
            if (saved.isSuccess) Result.success() else if (runAttemptCount < 2) Result.retry() else Result.failure()
        } }
    }
}
