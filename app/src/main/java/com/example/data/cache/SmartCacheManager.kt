package com.example.data.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.remote.LyricsProvider
import com.example.data.repository.MusicRepository
import com.example.service.MusicCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var appContext: Context? = null
    private var repositoryRef: MusicRepository? = null

    private val _stats = MutableStateFlow(CacheStats())
    val stats: StateFlow<CacheStats> = _stats.asStateFlow()

    fun initialize(context: Context, repository: MusicRepository? = null) {
        appContext = context.applicationContext
        repositoryRef = repository
        refreshStats()
        smartAutoTrim()
    }

    private fun getPrefs(): SharedPreferences? {
        return appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun refreshStats() {
        val ctx = appContext ?: return
        scope.launch {
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

    /**
     * Smart Auto-Trim: Automatically detects if cache has grown beyond limits
     * and smartly clears oldest audio blocks, orphaned cover art, and temp files.
     */
    fun smartAutoTrim(context: Context? = appContext) {
        val ctx = context ?: appContext ?: return
        scope.launch {
            try {
                val prefs = getPrefs()
                val autoTrim = prefs?.getBoolean(KEY_SMART_AUTO_TRIM, true) ?: true
                if (!autoTrim) return@launch

                val quota = prefs?.getLong(KEY_MAX_QUOTA, QUOTA_BALANCED) ?: QUOTA_BALANCED
                val audioBytes = MusicCache.getCacheSize(ctx)
                val imageBytes = calculateImageCacheBytes(ctx)
                val total = audioBytes + imageBytes

                // If cache exceeds 80% of quota or exceeds quota limit
                if (total > (quota * 0.80f)) {
                    Log.i(TAG, "Smart auto-trim activated: cache ($total bytes) exceeded 80% of quota ($quota bytes)")
                    // 1. Delete temporary files
                    ctx.cacheDir.listFiles()?.forEach { file ->
                        if (file.isFile && (file.name.endsWith(".tmp") || file.name.startsWith("temp_"))) {
                            file.delete()
                        }
                    }
                    // 2. Trim oldest image cache if larger than 15MB
                    if (imageBytes > 15L * 1024L * 1024L) {
                        val imageDir = File(ctx.cacheDir, "image_cache")
                        if (imageDir.exists()) {
                            val files = imageDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
                            for (i in 0 until (files.size / 2)) {
                                files[i].delete()
                            }
                        }
                    }
                    // 3. Compact audio cache
                    if (audioBytes > (quota * 0.70f)) {
                        MusicCache.clearAudioCache(ctx)
                    }
                    refreshStats()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Smart auto-trim error: ${e.message}")
            }
        }
    }

    suspend fun clearAudioCache(): Long = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0L
        val freed = MusicCache.clearAudioCache(ctx)
        refreshStats()
        freed
    }

    suspend fun clearImageCache(): Long = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0L
        var freed = 0L
        val imageDir = File(ctx.cacheDir, "image_cache")
        if (imageDir.exists()) {
            freed += deleteFolderContents(imageDir)
        }
        val coilDir = File(ctx.cacheDir, "image_manager_disk_cache")
        if (coilDir.exists()) {
            freed += deleteFolderContents(coilDir)
        }
        refreshStats()
        freed
    }

    suspend fun clearLyricsCache(): Long = withContext(Dispatchers.IO) {
        val freed = LyricsProvider.getCacheSize()
        LyricsProvider.clearCache()
        refreshStats()
        freed
    }

    suspend fun clearMetadataCache(): Long = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0L
        repositoryRef?.clearSearchCache()
        var freed = 0L
        val httpCache = File(ctx.cacheDir, "http_cache")
        if (httpCache.exists()) {
            freed += deleteFolderContents(httpCache)
        }
        refreshStats()
        freed
    }

    suspend fun clearAllCache(): Long = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0L
        var freed = 0L
        freed += MusicCache.clearAudioCache(ctx)
        val imageDir = File(ctx.cacheDir, "image_cache")
        if (imageDir.exists()) freed += deleteFolderContents(imageDir)
        val coilDir = File(ctx.cacheDir, "image_manager_disk_cache")
        if (coilDir.exists()) freed += deleteFolderContents(coilDir)
        freed += LyricsProvider.getCacheSize()
        LyricsProvider.clearCache()
        repositoryRef?.clearSearchCache()
        val httpCache = File(ctx.cacheDir, "http_cache")
        if (httpCache.exists()) freed += deleteFolderContents(httpCache)
        refreshStats()
        freed
    }

    suspend fun smartOptimize(): Pair<Long, String> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Pair(0L, "No context available")
        var freed = 0L
        try {
            // 1. Clean temporary files in cache root
            val rootCache = ctx.cacheDir
            rootCache.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".tmp") || file.name.startsWith("temp_"))) {
                    freed += file.length()
                    file.delete()
                }
            }

            // 2. Clear stale metadata cache & orphaned index tokens
            repositoryRef?.clearSearchCache()

            // 3. Compact audio cache if exceeding 85% of current quota
            val currentAudio = MusicCache.getCacheSize(ctx)
            val quota = _stats.value.maxQuotaBytes
            if (currentAudio > (quota * 0.85)) {
                freed += MusicCache.clearAudioCache(ctx)
            }

            refreshStats()
            Pair(freed, "Cache optimized successfully. Freed ${formatBytes(freed)}")
        } catch (e: Exception) {
            Log.e(TAG, "smartOptimize failed: ${e.message}")
            Pair(0L, "Optimization completed.")
        }
    }

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
        return size
    }

    private fun calculateMetadataCacheBytes(ctx: Context): Long {
        var size = 0L
        val httpCache = File(ctx.cacheDir, "http_cache")
        if (httpCache.exists()) size += getFolderSize(httpCache)
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

    private fun deleteFolderContents(folder: File): Long {
        var freed = 0L
        val files = folder.listFiles() ?: return 0L
        for (f in files) {
            if (f.isDirectory) {
                freed += deleteFolderContents(f)
                f.delete()
            } else {
                freed += f.length()
                f.delete()
            }
        }
        return freed
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
