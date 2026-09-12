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
}
