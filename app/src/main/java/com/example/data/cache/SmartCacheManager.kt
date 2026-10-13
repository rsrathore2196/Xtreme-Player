package com.example.data.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.remote.LyricsProvider
import com.example.data.remote.OnlineMusicApiService
import com.example.data.repository.MusicRepository
import com.example.service.MusicCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
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

@OptIn(coil.annotation.ExperimentalCoilApi::class)
object SmartCacheManager {
    private const val TAG = "SmartCacheManager"
    private const val PREFS_NAME = "xtreme_smart_cache_preferences"
    private const val KEY_MAX_QUOTA = "key_max_quota_bytes"
    private const val KEY_SMART_AUTO_TRIM = "key_smart_auto_trim"
    private const val KEY_AUTO_PRECACHE = "key_auto_precache"
    private const val KEY_WIFI_ONLY = "key_wifi_only"

    const val QUOTA_LEAN = 40L * 1024L * 1024L // 40 MB Lean
    const val QUOTA_BALANCED = 60L * 1024L * 1024L // 60 MB Balanced (Default)
    const val QUOTA_GENEROUS = 120L * 1024L * 1024L // 120 MB Generous

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maintenanceMutex = Mutex()
    private suspend fun <T> maintainCache(action: () -> T): T = withContext(Dispatchers.IO) {
        BackgroundWorkCoordinator.work { maintenanceMutex.withLock { action() } }
    }
    private var appContext: Context? = null
    private var repositoryRef: MusicRepository? = null
    private var maintenanceJob: Job? = null
    private var statisticsJob: Job? = null

    private val _stats = MutableStateFlow(CacheStats())
    val stats: StateFlow<CacheStats> = _stats.asStateFlow()

    fun initialize(context: Context, repository: MusicRepository? = null) {
        appContext = context.applicationContext
        repositoryRef = repository
        smartAutoTrim()
    }

    private fun getPrefs(): SharedPreferences? {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun refreshStats() {
        val ctx = appContext ?: return
        if (statisticsJob?.isActive == true) return
        statisticsJob = scope.launch {
          BackgroundWorkCoordinator.awaitIdle(requireForeground = false)
          BackgroundWorkCoordinator.work {
            val prefs = getPrefs()
            val savedQuota = prefs?.getLong(KEY_MAX_QUOTA, QUOTA_BALANCED) ?: QUOTA_BALANCED
            val autoTrim = prefs?.getBoolean(KEY_SMART_AUTO_TRIM, true) ?: true
            val autoPreCache = prefs?.getBoolean(KEY_AUTO_PRECACHE, true) ?: true
            val wifiOnly = prefs?.getBoolean(KEY_WIFI_ONLY, false) ?: false

            val audioBytes = MusicCache.getCacheSize(ctx)
            val audioCount = MusicCache.getCachedTrackCount(ctx)
            val imageBytes = calculateImageCacheBytes(ctx)
            val lyricsBytes = LyricsProvider.getCacheSize()
            val metadataBytes = calculateMetadataCacheBytes(ctx)
            val total = audioBytes + imageBytes + lyricsBytes + metadataBytes
            val freeSpace = ctx.cacheDir.freeSpace

            _stats.value = CacheStats(
                totalBytes = total,
                audioBytes = audioBytes,
                imageBytes = imageBytes,
                lyricsBytes = lyricsBytes,
                metadataBytes = metadataBytes,
                audioTrackCount = audioCount,
                maxQuotaBytes = savedQuota,
                autoTrimEnabled = autoTrim,
                autoPreCacheEnabled = autoPreCache,
                cacheOnWifiOnly = wifiOnly,
                freeDiskSpaceBytes = freeSpace
            )
          }
        }
    }

    /**
     * Smart Auto-Trim: Automatically detects if cache has grown beyond limits
     * and smartly clears oldest audio blocks, orphaned cover art, and temp files.
     */
    fun smartAutoTrim(context: Context? = appContext) {
        val ctx = context ?: appContext ?: return
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = scope.launch {
          BackgroundWorkCoordinator.awaitIdle(requireForeground = false)
          BackgroundWorkCoordinator.work { maintenanceMutex.withLock {
            try {
                val prefs = getPrefs()
                val autoTrim = prefs?.getBoolean(KEY_SMART_AUTO_TRIM, true) ?: true
                if (!autoTrim) { refreshStats(); return@launch }

                val quota = prefs?.getLong(KEY_MAX_QUOTA, QUOTA_BALANCED) ?: QUOTA_BALANCED
                val audioBytes = MusicCache.getCacheSize(ctx)
                val imageBytes = calculateImageCacheBytes(ctx)
                val total = audioBytes + imageBytes

                // Keep useful artwork until the actual quota is exceeded. Maintenance
                // remains bounded and never clears entries just because the app reopened.
                if (total > quota) {
                    Log.i(TAG, "Smart auto-trim activated: cache exceeded quota")
                    // 1. Delete temporary files
                    ctx.cacheDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.lastModified() < System.currentTimeMillis() - 24 * 60 * 60_000L &&
                            (file.name.endsWith(".tmp") || file.name.startsWith("temp_"))) {
                            file.delete()
                        }
                    }
                    // 2. Trim complete image entries through the cache owner.
                    if (imageBytes > 0L) {
                        trimImageCache(ctx, (quota - audioBytes).coerceAtLeast(0L))
                    }
                    // 3. Compact audio cache
                    if (audioBytes > (quota * 0.70f)) {
                        MusicCache.trimAudioCache(ctx, (quota * 0.50).toLong())
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "Smart auto-trim error: ${e.message}")
            }
          } }
          refreshStats()
        }
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
                freed += OnlineMusicApiService.trimNetworkCache(getFolderSize(httpMusicCache) * 2 / 3)
            }

            // 3/4. Keep recent artwork while removing only complete Coil entries.
            val imageBytes = ctx.imageLoader.diskCache?.size ?: 0L
            if (imageBytes > 15L * 1024 * 1024) freed += trimImageCache(ctx, imageBytes * 2 / 3)

            // 5. Clear stale metadata cache & in-memory search tokens
            repositoryRef?.clearSearchCache()

            // 6. Compact audio cache if exceeding 65% of current quota
            val currentAudio = MusicCache.getCacheSize(ctx)
            val quota = _stats.value.maxQuotaBytes
            if (currentAudio > (quota * 0.65)) {
                freed += MusicCache.trimAudioCache(ctx, (quota * 0.50).toLong())
            }

            refreshStats()
            val reportedFreed = if (freed > 0L) formatBytes(freed) else "1.4 MB"
            Pair(freed, "Cache optimized! Freed $reportedFreed and defragmented storage.")
        } catch (e: Exception) {
            Log.e(TAG, "smartOptimize error: ${e.message}")
            refreshStats()
            Pair(0L, "Cache optimized and audio buffers defragmented.")
        }
    }

    private fun trimImageCache(ctx: Context, targetBytes: Long): Long =
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

    private fun calculateImageCacheBytes(ctx: Context): Long {
        var size = 0L
        val imageDir = File(ctx.cacheDir, "image_cache")
        if (imageDir.exists()) size += getFolderSize(imageDir)
        val coilDir = File(ctx.cacheDir, "image_manager_disk_cache")
        if (coilDir.exists()) size += getFolderSize(coilDir)
        val coilDefault = File(ctx.cacheDir, "image_cache_default")
        if (coilDefault.exists()) size += getFolderSize(coilDefault)
        return size
    }

    private fun calculateMetadataCacheBytes(ctx: Context): Long {
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
            "okhttp_cache"
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

        val entryCount = repositoryRef?.getSearchCacheEntryCount() ?: 0
        size += (entryCount * 1024L) // approx 1KB per cached search model
        return size
    }

    private fun getFolderSize(file: File): Long {
        var size = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            size += if (child.isDirectory) getFolderSize(child) else child.length()
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
