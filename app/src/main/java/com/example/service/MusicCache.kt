package com.example.service

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.local.AudioQualityPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object MusicCache {
    const val DEFAULT_CACHE_SIZE = 50L * 1024L * 1024L // 50 MB lean LRU cache for 320kbps streams
    private const val CACHE_SIZE = DEFAULT_CACHE_SIZE

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

    fun createCacheDataSourceFactory(context: Context): DataSource.Factory {
        val cache = getSimpleCache(context)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Media3 calls this on its loader thread. Refresh signed video URLs at load time,
        // including restored queues, without holding the UI thread or storing stale URLs.
        return ResolvingDataSource.Factory(cached) { spec ->
            if (spec.uri.scheme == "xtreme-youtube") {
                val videoId = spec.uri.pathSegments.firstOrNull().orEmpty()
                val hls = spec.uri.lastPathSegment?.endsWith(".m3u8") == true
                val audio = runBlocking(Dispatchers.IO) {
                    YouTubeAudioStreamService.resolve(videoId, AudioQualityPreferences.getSelectedQuality(context), hlsOnly = hls)
                }
                spec.buildUpon().setUri(audio.url).setKey(if (hls) spec.key else audio.cacheKey)
                    .setHttpRequestHeaders(spec.httpRequestHeaders + YouTubeAudioStreamService.playbackHeaders).build()
            } else if (spec.uri.host?.endsWith(".googlevideo.com") == true) {
                spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders + YouTubeAudioStreamService.playbackHeaders).build()
            } else spec
        }
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
        return trimAudioCache(context, 0L)
    }

    /** Remove committed spans through Media3 so its index and active writers stay consistent. */
    fun trimAudioCache(context: Context, targetBytes: Long): Long {
        return try {
            val cache = getSimpleCache(context)
            val before = cache.cacheSpace
            val spans = cache.keys.flatMap { cache.getCachedSpans(it).toList() }
                .sortedBy { it.lastTouchTimestamp }
            for (span in spans) {
                if (cache.cacheSpace <= targetBytes.coerceAtLeast(0L)) break
                cache.removeSpan(span)
            }
            (before - cache.cacheSpace).coerceAtLeast(0L)
        } catch (e: Exception) {
            android.util.Log.w("MusicCache", "Cache trim error: ${e.message}")
            0L
        }
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
