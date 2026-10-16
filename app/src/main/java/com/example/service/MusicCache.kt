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
import java.io.IOException
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object MusicCache {
    const val DEFAULT_CACHE_SIZE = 50L * 1024L * 1024L // 50 MB lean LRU cache for 320kbps streams
    private const val CACHE_SIZE = DEFAULT_CACHE_SIZE

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    fun getSimpleCache(context: Context): SimpleCache {
        simpleCacheInstance?.let { return it }
        return synchronized(this) { simpleCacheInstance ?: run {
            val cacheDir = File(context.cacheDir, "xtreme_audio_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE)
            val databaseProvider = StandaloneDatabaseProvider(context)
            val cache = SimpleCache(cacheDir, evictor, databaseProvider)
            simpleCacheInstance = cache
            cache
        } }
    }

    fun createCacheDataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val rangedUpstream = DataSource.Factory { YouTubeMediaDataSource(app, upstreamFactory) }

        // Creating a Media3 factory happens on Main. Cache recovery happens only
        // when its data source opens on Media3's loader thread.
        val cached = DataSource.Factory { object : DataSource {
            private var delegate: DataSource? = null
            private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
            override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
                listeners.add(listener); delegate?.addTransferListener(listener)
            }
            override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                val source = CacheDataSource.Factory().setCache(getSimpleCache(app))
                    .setUpstreamDataSourceFactory(rangedUpstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
                listeners.forEach(source::addTransferListener)
                delegate = source
                return source.open(spec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                checkNotNull(delegate).read(buffer, offset, length)
            override fun getUri() = delegate?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
            override fun close() { try { delegate?.close() } finally { delegate = null } }
        } }
        // Media3 calls this on its loader thread. Refresh signed video URLs at load time,
        // including restored queues, without holding the UI thread or storing stale URLs.
        return ResolvingDataSource.Factory(cached) { spec ->
            if (spec.uri.scheme == "xtreme-sabr") {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    throw IOException("SABR cache loading must run on the Media3 loader thread")
                val cacheKey = SabrSessionRegistry.cacheKeyFromUri(spec.uri)
                val quality = AudioQualityPreferences.getSelectedQuality(app)
                if (spec.uri.getQueryParameter("cap")?.toIntOrNull() != minOf(quality.kbps, 320)) {
                    // Apply changed preferences before even a fully cached old
                    // representation can bypass the SABR upstream data source.
                    val audio = runBlocking(Dispatchers.IO) {
                        YouTubeAudioStreamService.resolveSabr(
                            requireNotNull(YouTubeAudioStreamService.videoIdFromUri(spec.uri)), quality)
                    }
                    SabrSessionRegistry.recordReplacement(spec.uri.toString(), audio)
                    throw SabrStreamChangedException(audio)
                }
                spec.buildUpon().setKey(cacheKey).build()
            } else if (spec.uri.scheme == "xtreme-youtube") {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    throw IOException("YouTube audio resolution must run on the Media3 loader thread")
                val videoId = spec.uri.pathSegments.firstOrNull().orEmpty()
                val hls = spec.uri.lastPathSegment?.endsWith(".m3u8") == true
                val audio = try {
                    runBlocking(Dispatchers.IO) {
                        YouTubeAudioStreamService.resolve(videoId, AudioQualityPreferences.getSelectedQuality(context), hlsOnly = hls)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Audio resolution interrupted", e)
                } catch (e: Exception) {
                    throw IOException("Couldn't resolve YouTube audio", e)
                }
                if (audio.isHls && !hls || audio.isSabr) {
                    // The old progressive extractor/cache offsets cannot be reused
                    // against a SABR container, even when the audio itag is equal.
                    SabrSessionRegistry.recordReplacement(spec.uri.toString(), audio)
                    throw SabrStreamChangedException(audio)
                }
                if (!hls) {
                    val expectedItag = spec.uri.getQueryParameter("itag")?.toIntOrNull()
                    val expectedLength = spec.uri.getQueryParameter("length")?.toLongOrNull()
                    if (expectedItag != null && expectedItag != audio.itag ||
                        expectedLength != null && audio.contentLength != null && expectedLength != audio.contentLength)
                        throw IOException("Audio format changed; reopen the stream at its playback position")
                }
                spec.buildUpon().setUri(audio.url).setKey(if (hls) spec.key else audio.cacheKey)
                    .setHttpRequestHeaders(spec.httpRequestHeaders + audio.headers).build()
            } else if (YouTubeAudioStreamService.isMediaUrl(spec.uri.toString())) {
                val audio = YouTubeAudioStreamService.cachedForUrl(spec.uri.toString())
                spec.buildUpon().setKey(audio?.takeUnless { it.isHls }?.cacheKey ?: spec.key)
                    .setHttpRequestHeaders(spec.httpRequestHeaders + YouTubeAudioStreamService.headersFor(spec.uri.toString())).build()
            } else spec
        }
    }

    fun getCacheSize(context: Context): Long {
        return try {
            getSimpleCache(context).cacheSpace
        } catch (e: Exception) {
            val cacheDir = File(context.cacheDir, "xtreme_audio_cache")
            if (cacheDir.exists()) getFolderSize(cacheDir) else 0L
        }
    }

    fun getCachedTrackCount(context: Context): Int {
        return try {
            getSimpleCache(context).keys.size
        } catch (e: Exception) {
            val cacheDir = File(context.cacheDir, "xtreme_audio_cache")
            if (cacheDir.exists()) (cacheDir.listFiles()?.count { it.isFile && it.name.endsWith(".exo") } ?: 0) else 0
        }
    }

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
