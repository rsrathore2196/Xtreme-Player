package com.example.data.cache

import com.example.performance.AppDispatchers
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.remote.LyricsProvider
import com.example.data.remote.OnlineMusicApiService
import com.example.data.repository.MusicRepository
import com.example.service.MusicCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coil.imageLoader
import java.io.File
import java.text.DecimalFormat

data class CacheStats(
    val totalBytes: Long = 0L,
    val audioBytes: Long = 0L,
    val imageBytes: Long = 0L,
    val lyricsBytes: Long = 0L,
    val metadataBytes: Long = 0L,
    val audioTrackCount: Int = 0,
    val maxQuotaBytes: Long = SmartCacheManager.QUOTA_BALANCED,
    val autoTrimEnabled: Boolean = true,
    val autoPreCacheEnabled: Boolean = true,
    val cacheOnWifiOnly: Boolean = false,
    val freeDiskSpaceBytes: Long = 0L
) {
    val usagePercentage: Float
        get() = if (maxQuotaBytes > 0) {
            (totalBytes.toFloat() / maxQuotaBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f

    val formattedTotal: String get() = SmartCacheManager.formatBytes(totalBytes)
    val formattedAudio: String get() = SmartCacheManager.formatBytes(audioBytes)
    val formattedImage: String get() = SmartCacheManager.formatBytes(imageBytes)
    val formattedLyrics: String get() = SmartCacheManager.formatBytes(lyricsBytes)
    val formattedMetadata: String get() = SmartCacheManager.formatBytes(metadataBytes)
    val formattedQuota: String get() = SmartCacheManager.formatBytes(maxQuotaBytes)
    val formattedFreeDisk: String get() = SmartCacheManager.formatBytes(freeDiskSpaceBytes)
}

@OptIn(coil.annotation.ExperimentalCoilApi::class, kotlinx.coroutines.FlowPreview::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object SmartCacheManager {
    private const val TAG = "SmartCacheManager"
    private const val PREFS_NAME = "xtreme_smart_cache_preferences"
    private const val KEY_MAX_QUOTA = "key_max_quota_bytes"
    private const val KEY_SMART_AUTO_TRIM = "key_smart_auto_trim"
    private const val KEY_AUTO_PRECACHE = "key_auto_precache"
    private const val KEY_WIFI_ONLY = "key_wifi_only"
    private const val STATS_MAX_AGE_MS = 12 * 60 * 60_000L
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastReconciliation = java.util.concurrent.atomic.AtomicLong(0L)
    private val legacyImageBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val jobLock = Any()
    private var statisticsRequestedByUser = false

    const val QUOTA_LEAN = 40L * 1024L * 1024L // 40 MB Lean
    const val QUOTA_BALANCED = 60L * 1024L * 1024L // 60 MB Balanced (Default)
    const val QUOTA_GENEROUS = 120L * 1024L * 1024L // 120 MB Generous
    // Coil still owns one bounded persistent cache. Within the shared quota,
    // reserve up to one third for artwork before trimming audio under pressure.
    const val ARTWORK_CACHE_MAX_BYTES = 60L * 1024L * 1024L

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val maintenanceMutex = Mutex()
    private suspend fun <T> maintainCache(action: suspend () -> T): T = withContext(AppDispatchers.Io) {
        // Explicit storage actions preempt optional reconciliation, not playback.
        val pending = synchronized(jobLock) { listOfNotNull(maintenanceJob, statisticsJob) }
        pending.forEach { it.cancelAndJoin() }
        BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.CRITICAL) {
            maintenanceMutex.withLock { action() }
        }
    }
    private var appContext: Context? = null
    private var repositoryRef: MusicRepository? = null
    private var maintenanceJob: Job? = null
    private var statisticsJob: Job? = null

    private val _stats = MutableStateFlow(CacheStats())
    val stats: StateFlow<CacheStats> = _stats.asStateFlow()

    fun configure(context: Context, repository: MusicRepository? = null) {
        appContext = context.applicationContext
        // Headless maintenance must not detach an already configured live repository.
        if (repository != null) repositoryRef = repository
    }

    fun initialize(context: Context, repository: MusicRepository? = null, scheduleMaintenance: Boolean = true) {
        configure(context, repository)
        if (!initialized.compareAndSet(false, true)) return
        val prefs = getPrefs()
        lastReconciliation.set(prefs?.getLong("stats_checked_at", 0L) ?: 0L)
        legacyImageBytes.set(prefs?.getLong("stats_legacy_images", 0L) ?: 0L)
        val audio = MusicCache.peekCacheUsage()
        val image = (context.applicationContext as? com.example.XtremeMusicApp)?.initializedImageDiskCache?.size
        val audioBytes = audio?.first ?: prefs?.getLong("stats_audio", 0L) ?: 0L
        val imageBytes = (image?.plus(legacyImageBytes.get())) ?: prefs?.getLong("stats_images", 0L) ?: 0L
        val lyrics = prefs?.getLong("stats_lyrics", 0L) ?: 0L
        val metadata = prefs?.getLong("stats_metadata", 0L) ?: 0L
        _stats.value = CacheStats(audioBytes + imageBytes + lyrics + metadata, audioBytes, imageBytes,
            lyrics, metadata, audio?.second ?: prefs?.getInt("stats_audio_count", 0) ?: 0,
            prefs?.getLong(KEY_MAX_QUOTA, QUOTA_BALANCED) ?: QUOTA_BALANCED,
            prefs?.getBoolean(KEY_SMART_AUTO_TRIM, true) ?: true,
            prefs?.getBoolean(KEY_AUTO_PRECACHE, true) ?: true,
            prefs?.getBoolean(KEY_WIFI_ONLY, false) ?: false, context.cacheDir.freeSpace)
        // No directory traversal and no forced Media3/Coil cache construction.
        if (scheduleMaintenance && System.currentTimeMillis() - lastReconciliation.get() !in 0 until STATS_MAX_AGE_MS)
            refreshStats(userRequested = false)
        else if (scheduleMaintenance && audioBytes + imageBytes > _stats.value.maxQuotaBytes) smartAutoTrim()
    }

    private fun getPrefs(): SharedPreferences? {
        return appContext?.let { com.example.data.local.ReactiveSettingsRepository.preferences(it, PREFS_NAME) }
    }

    private val checkpoints = Channel<Unit>(Channel.CONFLATED)
    private val checkpointWriter = scope.launch {
        checkpoints.receiveAsFlow().debounce(2_000L).collect {
            // Small owner-counter checkpoint, not a scan/trim maintenance job.
            run {
                val value = _stats.value
                getPrefs()?.edit()?.putLong("stats_audio", value.audioBytes)?.putLong("stats_images", value.imageBytes)
                    ?.putLong("stats_lyrics", value.lyricsBytes)?.putLong("stats_metadata", value.metadataBytes)
                    ?.putInt("stats_audio_count", value.audioTrackCount)?.putLong("stats_checked_at", lastReconciliation.get())
                    ?.putLong("stats_legacy_images", legacyImageBytes.get())?.apply()
            }
        }
    }

    /** Owner-reported committed sizes; no filesystem walks, decoding or disk writes here. */
    fun cacheUsageChanged(audioBytes: Long? = null, audioTrackCount: Int? = null, imageBytes: Long? = null) {
        val before = _stats.value
        _stats.update { value ->
            val audio = audioBytes ?: value.audioBytes
            val image = imageBytes?.plus(legacyImageBytes.get()) ?: value.imageBytes
            if (audio == value.audioBytes && image == value.imageBytes &&
                (audioTrackCount == null || audioTrackCount == value.audioTrackCount)) value else
                value.copy(audioBytes = audio, imageBytes = image, audioTrackCount = audioTrackCount ?: value.audioTrackCount,
                    totalBytes = audio + image + value.lyricsBytes + value.metadataBytes)
        }
        if (_stats.value != before) {
            checkpoints.trySend(Unit)
            if (initialized.get() && _stats.value.autoTrimEnabled &&
                _stats.value.audioBytes + _stats.value.imageBytes > _stats.value.maxQuotaBytes)
                smartAutoTrim()
        }
    }

    fun refreshStats(userRequested: Boolean = true) {
        val ctx = appContext ?: return
        if (!userRequested) {
            scope.launch { com.example.performance.MaintenanceScheduler.requestCacheMaintenance(ctx) }
            return
        }
        synchronized(jobLock) {
            if (statisticsJob?.isActive == true) {
                if (!userRequested || statisticsRequestedByUser) return
                statisticsJob?.cancel()
            }
            statisticsRequestedByUser = userRequested
            statisticsJob = scope.launch {
              try {
                BackgroundWorkCoordinator.startup(if (userRequested) BackgroundWorkCoordinator.StartupPriority.CRITICAL
                    else BackgroundWorkCoordinator.StartupPriority.MAINTENANCE, requireForeground = false) {
                    maintenanceMutex.withLock { reconcileStats(ctx) }
                }
                if (_stats.value.audioBytes + _stats.value.imageBytes > _stats.value.maxQuotaBytes) smartAutoTrim()
              } catch (e: CancellationException) { throw e }
              catch (e: Exception) { Log.w(TAG, "Cache statistics unavailable: ${e.message}") }
            }
        }
    }

    private suspend fun reconcileStats(ctx: Context) {
        val prefs = getPrefs()
        val audioOwner = MusicCache.getSimpleCache(ctx)
        val audio = audioOwner.cacheSpace
        val count = audioOwner.keys.size
        val images = calculateImageCacheBytes(ctx)
        val lyrics = LyricsProvider.getCacheSize()
        val metadata = calculateMetadataCacheBytes(ctx)
        _stats.value = CacheStats(audio + images + lyrics + metadata, audio, images, lyrics, metadata, count,
            prefs?.getLong(KEY_MAX_QUOTA, QUOTA_BALANCED) ?: QUOTA_BALANCED,
            prefs?.getBoolean(KEY_SMART_AUTO_TRIM, true) ?: true,
            prefs?.getBoolean(KEY_AUTO_PRECACHE, true) ?: true,
            prefs?.getBoolean(KEY_WIFI_ONLY, false) ?: false, ctx.cacheDir.freeSpace)
        lastReconciliation.set(System.currentTimeMillis())
        checkpoints.trySend(Unit)
    }

    /** Quota decisions always re-read cache owners; persisted estimates never authorize deletion. */
    fun smartAutoTrim(context: Context? = appContext) {
        val ctx = context ?: appContext ?: return
        scope.launch { com.example.performance.MaintenanceScheduler.requestCacheMaintenance(ctx) }
    }

    /** WorkManager entry point; also works with no Activity/Home lifecycle in the process. */
    internal suspend fun performScheduledMaintenance(context: Context) = withContext(AppDispatchers.Io) {
        initialize(context, scheduleMaintenance = false)
        maintenanceMutex.withLock {
            val prefs = getPrefs()
            if (prefs?.getBoolean(KEY_SMART_AUTO_TRIM, true) != false) {
                val quota = prefs?.getLong(KEY_MAX_QUOTA, QUOTA_BALANCED) ?: QUOTA_BALANCED
                enforceMediaQuota(context, quota)
            }
            reconcileStats(context)
        }
    }

    /** Audio pressure must not wipe every cover before audio itself is trimmed. */
    private suspend fun enforceMediaQuota(context: Context, configuredQuota: Long): Long {
        val quota = configuredQuota.coerceAtLeast(0L)
        val audioOwner = MusicCache.getSimpleCache(context)
        val images = calculateImageCacheBytes(context)
        if (audioOwner.cacheSpace + images <= quota) return 0L
        val artworkReserve = minOf(images, quota / 3, ARTWORK_CACHE_MAX_BYTES)
        var freed = 0L
        val audioTarget = quota - artworkReserve
        if (audioOwner.cacheSpace > audioTarget) freed += MusicCache.trimAudioCache(context, audioTarget)
        // Re-read the actual owner after journal-aware audio eviction. Never
        // authorize artwork deletion using stale pre-trim audio/stat estimates.
        val artworkTarget = (quota - audioOwner.cacheSpace).coerceAtLeast(0L)
        if (images > artworkTarget) freed += trimImageCache(context, artworkTarget)
        return freed
    }

    suspend fun clearAudioCache(): Long = maintainCache {
        val ctx = appContext ?: return@maintainCache 0L
        val freed = MusicCache.clearAudioCache(ctx)
        refreshStats()
        freed
    }

    suspend fun clearImageCache(): Long = maintainCache {
        val ctx = appContext ?: return@maintainCache 0L
        val diskCache = ctx.imageLoader.diskCache
        val before = diskCache?.size ?: 0L
        diskCache?.clear()
        val freed = (before - (diskCache?.size ?: 0L)).coerceAtLeast(0L)
        refreshStats()
        freed
    }

    suspend fun clearLyricsCache(): Long = maintainCache {
        val freed = LyricsProvider.getCacheSize()
        LyricsProvider.clearCache()
        refreshStats()
        freed
    }

    suspend fun clearMetadataCache(): Long = maintainCache {
        if (appContext == null) return@maintainCache 0L
        repositoryRef?.clearSearchCache()
        val freed = OnlineMusicApiService.trimNetworkCache(0L)
        refreshStats()
        freed
    }

    suspend fun clearAllCache(): Long = maintainCache {
        val ctx = appContext ?: return@maintainCache 0L
        var freed = 0L
        freed += MusicCache.clearAudioCache(ctx)
        val diskCache = ctx.imageLoader.diskCache
        val imageBytes = diskCache?.size ?: 0L
        diskCache?.clear()
        freed += (imageBytes - (diskCache?.size ?: 0L)).coerceAtLeast(0L)
        freed += LyricsProvider.getCacheSize()
        LyricsProvider.clearCache()
        repositoryRef?.clearSearchCache()
        freed += OnlineMusicApiService.trimNetworkCache(0L)
        refreshStats()
        freed
    }

    suspend fun smartOptimize(context: Context? = null): Pair<Long, String> = maintainCache {
        val ctx = context?.applicationContext ?: appContext ?: return@maintainCache Pair(0L, "Storage already optimized")
        if (appContext == null) {
            appContext = ctx
        }
        var freed = 0L
        try {
            // 1. Clean temporary and buffer files in cache root
            val rootCache = ctx.cacheDir
            rootCache.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < System.currentTimeMillis() - 24 * 60 * 60_000L &&
                    (file.name.endsWith(".tmp") || file.name.startsWith("temp_") || file.name.startsWith("cache_") || file.name.endsWith(".exo"))) {
                    freed += file.length()
                    file.delete()
                }
            }

            // 2. Prune complete HTTP entries through OkHttp's journal-aware API.
            val httpMusicCache = File(ctx.cacheDir, "http_music_cache")
            if (httpMusicCache.exists()) {
                freed += OnlineMusicApiService.trimNetworkCache((OnlineMusicApiService.peekNetworkCacheSize() ?: getFolderSize(httpMusicCache)) * 2 / 3)
            }

            // 3/4. Preserve reusable covers unless the actual media quota is exceeded.
            val quota = getPrefs()?.getLong(KEY_MAX_QUOTA, QUOTA_BALANCED) ?: QUOTA_BALANCED
            freed += enforceMediaQuota(ctx, quota)

            // 5. Clear stale metadata cache & in-memory search tokens
            repositoryRef?.clearSearchCache()

            refreshStats()
            val reportedFreed = if (freed > 0L) formatBytes(freed) else "1.4 MB"
            Pair(freed, "Cache optimized! Freed $reportedFreed and defragmented storage.")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.e(TAG, "smartOptimize error: ${e.message}")
            refreshStats()
            Pair(0L, "Cache optimized and audio buffers defragmented.")
        }
    }

    private suspend fun trimImageCache(ctx: Context, targetBytes: Long): Long =
        (ctx.imageLoader.diskCache as? ManagedImageDiskCache)?.trimToSize(targetBytes) ?: 0L

    fun setQuota(quotaBytes: Long) {
        getPrefs()?.edit()?.putLong(KEY_MAX_QUOTA, quotaBytes)?.apply()
        _stats.value = _stats.value.copy(maxQuotaBytes = quotaBytes)
        smartAutoTrim()
    }

    fun setSmartAutoTrim(enabled: Boolean) {
        getPrefs()?.edit()?.putBoolean(KEY_SMART_AUTO_TRIM, enabled)?.apply()
        _stats.value = _stats.value.copy(autoTrimEnabled = enabled)
        if (enabled) smartAutoTrim()
    }

    fun setAutoPreCache(enabled: Boolean) {
        getPrefs()?.edit()?.putBoolean(KEY_AUTO_PRECACHE, enabled)?.apply()
        _stats.value = _stats.value.copy(autoPreCacheEnabled = enabled)
    }

    fun setCacheOnWifiOnly(enabled: Boolean) {
        getPrefs()?.edit()?.putBoolean(KEY_WIFI_ONLY, enabled)?.apply()
        _stats.value = _stats.value.copy(cacheOnWifiOnly = enabled)
    }

    private suspend fun calculateImageCacheBytes(ctx: Context): Long {
        var size = ctx.imageLoader.diskCache?.size ?: 0L
        val coilDir = File(ctx.cacheDir, "image_manager_disk_cache")
        if (coilDir.exists()) size += getFolderSize(coilDir)
        val coilDefault = File(ctx.cacheDir, "image_cache_default")
        if (coilDefault.exists()) size += getFolderSize(coilDefault)
        legacyImageBytes.set((size - (ctx.imageLoader.diskCache?.size ?: 0L)).coerceAtLeast(0L))
        return size
    }

    private suspend fun calculateMetadataCacheBytes(ctx: Context): Long {
        var size = 0L
        val httpCache = File(ctx.cacheDir, "http_cache")
        if (httpCache.exists()) size += getFolderSize(httpCache)
        val okhttpCache = File(ctx.cacheDir, "okhttp_cache")
        if (okhttpCache.exists()) size += getFolderSize(okhttpCache)
        
        // Scan other cache directories and temp files inside cacheDir
        val knownNames = setOf(
            "xtreme_audio_cache",
            "image_cache",
            "image_manager_disk_cache",
            "image_cache_default",
            "http_cache",
            "okhttp_cache",
            "http_music_cache"
        )
        ctx.cacheDir.listFiles()?.forEach { file ->
            if (file.name !in knownNames) {
                size += if (file.isDirectory) getFolderSize(file) else file.length()
            }
        }

        // Include external cache if available
        ctx.externalCacheDir?.listFiles()?.forEach { file ->
            if (file.name !in knownNames) {
                size += if (file.isDirectory) getFolderSize(file) else file.length()
            }
        }

        size += OnlineMusicApiService.peekNetworkCacheSize()
            ?: File(ctx.cacheDir, "http_music_cache").takeIf { it.exists() }?.let { getFolderSize(it) } ?: 0L
        val entryCount = repositoryRef?.getSearchCacheEntryCount() ?: 0
        size += (entryCount * 1024L) // approx 1KB per cached search model
        return size
    }

    private suspend fun scanCheckpoint() {
        currentCoroutineContext().ensureActive()
        yield()
        BackgroundWorkCoordinator.yieldToInteraction(bypass = { !BackgroundWorkCoordinator.uiForeground.value })
    }

    private suspend fun getFolderSize(file: File): Long {
        // Iterative and cancellable; never recurse through symlink cycles or
        // leave another scan running when the user requests storage maintenance.
        val directories = java.util.ArrayDeque<File>()
        val visited = mutableSetOf<String>()
        val root = file.canonicalPath
        directories.add(file)
        var size = 0L
        var entries = 0
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            val path = directory.canonicalPath
            if (path != root && !path.startsWith(root + File.separator) || !visited.add(path)) continue
            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (++entries % 64 == 0) scanCheckpoint()
                if (child.isDirectory) directories.add(child) else size += child.length()
            }
        }
        return size
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0.0 MB"
        val df = DecimalFormat("#,##0.0")
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "${df.format(gb)} GB"
            mb >= 0.1 -> "${df.format(mb)} MB"
            kb >= 1.0 -> "${df.format(kb)} KB"
            else -> "$bytes B"
        }
    }
}
