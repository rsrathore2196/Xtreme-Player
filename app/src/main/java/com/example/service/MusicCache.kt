package com.example.service

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object MusicCache {
    private const val CACHE_SIZE = 150L * 1024L * 1024L // 150 MB LRU cache for 320kbps streams

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    @Synchronized
    fun getSimpleCache(context: Context): SimpleCache {
        return simpleCacheInstance ?: run {
            val cacheDir = File(context.cacheDir, "xtreme_audio_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE)
            val databaseProvider = StandaloneDatabaseProvider(context)
            val cache = SimpleCache(cacheDir, evictor, databaseProvider)
            simpleCacheInstance = cache
            cache
        }
    }

    fun createCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val cache = getSimpleCache(context)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @Synchronized
    fun getCacheSize(context: Context): Long {
        return try {
            getSimpleCache(context).cacheSpace
        } catch (e: Exception) {
            val cacheDir = File(context.cacheDir, "xtreme_audio_cache")
            if (cacheDir.exists()) getFolderSize(cacheDir) else 0L
        }
    }

    @Synchronized
    fun getCachedTrackCount(context: Context): Int {
        return try {
            getSimpleCache(context).keys.size
        } catch (e: Exception) {
            val cacheDir = File(context.cacheDir, "xtreme_audio_cache")
            if (cacheDir.exists()) (cacheDir.listFiles()?.count { it.isFile && it.name.endsWith(".exo") } ?: 0) else 0
        }
    }

    @Synchronized
    fun clearAudioCache(context: Context): Long {
        var freed = 0L
        try {
            val cache = getSimpleCache(context)
            freed = cache.cacheSpace
            val keys = cache.keys.toList()
            for (key in keys) {
                try {
                    cache.removeResource(key)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicCache", "Cache removeResource error: ${e.message}")
        }
        try {
            val cacheDir = File(context.cacheDir, "xtreme_audio_cache")
            if (cacheDir.exists()) {
                val files = cacheDir.listFiles() ?: emptyArray()
                for (file in files) {
                    if (file.isFile) {
                        freed += file.length()
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicCache", "Direct directory delete error: ${e.message}")
        }
        return freed
    }

    private fun getFolderSize(file: File): Long {
        var size = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            size += if (child.isDirectory) getFolderSize(child) else child.length()
        }
        return size
    }
}
