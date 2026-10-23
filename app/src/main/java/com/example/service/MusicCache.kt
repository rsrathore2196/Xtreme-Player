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
    private val saavnRate = Regex("_(320|160|96|48)\\.(?:mp4|m4a)(?:$|[?#])")

    internal fun fullyCached(context: Context, key: String, knownLength: Long? = null): Boolean = runCatching {
        val cache = getSimpleCache(context)
        val length = knownLength?.takeIf { it > 0 }
            ?: androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length > 0 && cache.isCached(key, 0L, length)
    }.getOrDefault(false)

    private fun offlineAudio(context: Context, video: String, cap: Int, sabr: Boolean,
        opusOnly: Boolean = false): YouTubeAudioStreamService.AudioStream? {
        val cached = YouTubeAudioStreamService.cachedStreams(video).filter {
            it.isSabr == sabr && !it.isHls && (!opusOnly || it.codec == "Opus") &&
                fullyCached(context, it.cacheKey, it.contentLength)
        }
        return cached.filter { com.example.data.remote.YouTubeAudioFormatPolicy.acceptsBitrate(it.bitrateKbps * 1000, cap) }
            .maxByOrNull { it.bitrateKbps }
    }

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    /** Read an existing owner's index without opening/scanning a cache at startup. */
    fun peekCacheUsage(): Pair<Long, Int>? = runCatching {
        simpleCacheInstance?.let { it.cacheSpace to it.keys.size }
    }.getOrNull()

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

    fun createCacheDataSourceFactory(context: Context, connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 15000): DataSource.Factory {
        val app = context.applicationContext
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(connectTimeoutMs)
            .setReadTimeoutMs(readTimeoutMs)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val rangedUpstream = DataSource.Factory { YouTubeMediaDataSource(app, upstreamFactory, httpDataSourceFactory) }

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
            override fun close() {
                try { delegate?.close() } finally {
                    delegate = null
                    peekCacheUsage()?.let { (bytes, count) ->
                        com.example.data.cache.SmartCacheManager.cacheUsageChanged(audioBytes = bytes, audioTrackCount = count)
                    }
                }
            }
        } }
        // Media3 calls this on its loader thread. Refresh signed video URLs at load time,
        // including restored queues, without holding the UI thread or storing stale URLs.
        return DataSource.Factory {
            // Per MediaPeriod/DataSource, not a global URL map: a new track uses
            // today's policy, while routine close/reopen/seek retains its format.
            var pinnedCeiling: Int? = null
            var offline = false
            var boundAudio: YouTubeAudioStreamService.AudioStream? = null
            var boundJioUri: android.net.Uri? = null
            ResolvingDataSource(cached.createDataSource()) { spec ->
                val host = spec.uri.host.orEmpty()
                val saavn = host == "saavncdn.com" || host.endsWith(".saavncdn.com")
                if (spec.uri.scheme in setOf("xtreme-sabr", "xtreme-youtube") || saavn) {
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                        throw IOException("Audio resolution must run on the Media3 loader thread")
                    if (pinnedCeiling == null) {
                        val policy = com.example.playback.NetworkQualityMonitor.getInstance(app).snapshot()
                        val referenceCap = YouTubeAudioStreamService.bitrateCapFromUri(spec.uri)
                        pinnedCeiling = minOf(policy.effectiveCeiling.maxBitrateBps, referenceCap ?: Int.MAX_VALUE)
                        offline = policy.connectionType == com.example.playback.NetworkConnectionType.OFFLINE
                    }
                }
                if (saavn) {
                    val target = boundJioUri ?: run {
                        val quality = AudioQualityPreferences.getSelectedQuality(app)
                        val current = saavnRate.find(spec.uri.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 320
                        val rate = com.example.playback.AudioQualityCeiling.saavnKbps(quality.kbps, current,
                            requireNotNull(pinnedCeiling))
                        val requested = com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(spec.uri.toString(), rate.toString())
                        val cached = if (offline) listOf(48, 96, 160, 320).map { value ->
                            value to com.example.data.remote.OnlineMusicApiService.formatUrlForQuality(spec.uri.toString(), value.toString())
                        }.filter { fullyCached(app, it.second) } else emptyList()
                        val available = cached.filter { it.first <= rate }.maxByOrNull { it.first }
                        if (offline && available == null) throw IOException("No cached audio fits the active quality profile")
                        android.net.Uri.parse(available?.second ?: requested)
                    }.also { boundJioUri = it }
                    spec.buildUpon().setUri(target).setKey(if (target == spec.uri) spec.key else target.toString()).build()
                } else if (spec.uri.scheme == "xtreme-sabr") {
                    val video = YouTubeAudioStreamService.videoIdFromUri(spec.uri)
                        ?: throw IOException("Invalid SABR recording")
                    val audio = (if (offline) offlineAudio(app, video, requireNotNull(pinnedCeiling), sabr = true,
                        opusOnly = spec.uri.getQueryParameter("codec") == "opus") else null) ?:
                        if (offline) throw IOException("No compatible cached SABR audio") else try { runBlocking(Dispatchers.IO) {
                        YouTubeAudioStreamService.resolveSabr(video, AudioQualityPreferences.getSelectedQuality(app),
                            maxBitrateBps = pinnedCeiling, requireOpus = spec.uri.getQueryParameter("codec") == "opus")
                    } } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt(); throw IOException("Audio resolution interrupted", e)
                    } catch (e: Exception) { throw IOException("Couldn't resolve SABR audio", e) }
                    val expected = boundAudio?.cacheKey ?: SabrSessionRegistry.cacheKeyFromUri(spec.uri)
                    if ((boundAudio != null || spec.position > 0) && expected != audio.cacheKey) {
                        SabrSessionRegistry.recordReplacement(spec.uri.toString(), audio)
                        throw SabrStreamChangedException(audio)
                    }
                    boundAudio = audio
                    spec.buildUpon().setUri(android.net.Uri.parse(audio.url)).setKey(audio.cacheKey).build()
                } else if (spec.uri.scheme == "xtreme-youtube") {
                    val videoId = spec.uri.pathSegments.firstOrNull().orEmpty()
                    val hls = spec.uri.lastPathSegment?.endsWith(".m3u8") == true
                    val audio = (if (offline && !hls) offlineAudio(app, videoId, requireNotNull(pinnedCeiling), sabr = false,
                        opusOnly = spec.uri.getQueryParameter("codec") == "opus") else null) ?:
                        if (offline) throw IOException("No compatible cached YouTube audio") else try {
                        runBlocking(Dispatchers.IO) {
                            YouTubeAudioStreamService.resolve(videoId, AudioQualityPreferences.getSelectedQuality(app), hlsOnly = hls,
                                maxBitrateBps = pinnedCeiling, requireOpus = spec.uri.getQueryParameter("codec") == "opus")
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Audio resolution interrupted", e)
                    } catch (e: Exception) { throw IOException("Couldn't resolve YouTube audio", e) }
                    if (audio.isHls && !hls || audio.isSabr || boundAudio?.cacheKey?.let { it != audio.cacheKey } == true) {
                        SabrSessionRegistry.recordReplacement(spec.uri.toString(), audio)
                        throw SabrStreamChangedException(audio)
                    }
                    if (!hls && (boundAudio != null || spec.position > 0)) {
                        val expectedItag = boundAudio?.itag ?: spec.uri.getQueryParameter("itag")?.toIntOrNull()
                        val expectedLength = boundAudio?.contentLength ?: spec.uri.getQueryParameter("length")?.toLongOrNull()
                        if (expectedItag != null && expectedItag != audio.itag ||
                            expectedLength != null && audio.contentLength != null && expectedLength != audio.contentLength) {
                            SabrSessionRegistry.recordReplacement(spec.uri.toString(), audio)
                            throw SabrStreamChangedException(audio)
                        }
                    }
                    boundAudio = audio
                    spec.buildUpon().setUri(audio.url).setKey(if (hls) spec.key else audio.cacheKey)
                        .setHttpRequestHeaders(spec.httpRequestHeaders + audio.headers).build()
                } else if (YouTubeAudioStreamService.isMediaUrl(spec.uri.toString())) {
                    val audio = YouTubeAudioStreamService.cachedForUrl(spec.uri.toString())
                    spec.buildUpon().setKey(audio?.takeUnless { it.isHls }?.cacheKey ?: spec.key)
                        .setHttpRequestHeaders(spec.httpRequestHeaders + YouTubeAudioStreamService.headersFor(spec.uri.toString())).build()
                } else spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders +
                    com.example.data.remote.StreamingAddons.headers(spec.uri.toString())).build()
            }
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
            com.example.data.cache.SmartCacheManager.cacheUsageChanged(audioBytes = cache.cacheSpace,
                audioTrackCount = cache.keys.size)
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
