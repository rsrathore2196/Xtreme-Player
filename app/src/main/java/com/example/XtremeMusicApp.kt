package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.example.config.SecurityConfig
import com.example.data.local.MusicDatabase
import com.example.data.repository.MusicRepository
import com.example.data.remote.OnlineMusicApiService
import com.example.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class XtremeMusicApp : Application(), ImageLoaderFactory {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: MusicDatabase
        private set

    lateinit var repository: MusicRepository
        private set

    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(this, repository).also {
            com.example.recommendation.RecommendationListeningObserver(database.listeningEventDao(), database.musicDao()).start(it)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components { add(com.example.ui.util.ArtworkResolutionInterceptor()) }
            .okHttpClient(okhttp3.OkHttpClient.Builder()
                .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 24; maxRequestsPerHost = 6 })
                .eventListenerFactory { object : okhttp3.EventListener() {
                    private var work: AutoCloseable? = null
                    override fun callStart(call: okhttp3.Call) { work = com.example.performance.BackgroundWorkCoordinator.busy() }
                    override fun callEnd(call: okhttp3.Call) { work?.close(); work = null }
                    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { work?.close(); work = null }
                } }.build())
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                val diskCache = DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(60L * 1024 * 1024) // 60 MB high-speed disk cache
                    .build()
                com.example.data.cache.ManagedImageDiskCache(diskCache, cacheDir.resolve("artwork_cache_keys.json"))
            }
            .bitmapConfig(android.graphics.Bitmap.Config.HARDWARE)
            .allowHardware(true)
            .allowRgb565(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        _instance = this
        com.example.ui.util.TrackPaletteCache.initialize(this)

        // Initialize security configuration
        startupScope.launch {
            com.example.performance.BackgroundWorkCoordinator.work {
                runCatching { SecurityConfig.getDESKey(this@XtremeMusicApp) }
            }
        }

        // Initialize API service with application context
        try {
            OnlineMusicApiService.initialize(this)
        } catch (e: Exception) {
            android.util.Log.e("XtremeMusicApp", "Failed to initialize OnlineMusicApiService: ${e.message}")
        }

        // Initialize database - with null safety checks
        try {
            database = MusicDatabase.getDatabase(this)
        } catch (e: Exception) {
            android.util.Log.e("XtremeMusicApp", "Failed to initialize database: ${e.message}")
            throw RuntimeException("Critical: Cannot initialize database", e)
        }

        // Construct the shared resolver off Main and warm its URL cache from Room.
        startupScope.launch {
            com.example.data.repository.iTunesArtworkRepository.getInstance(this@XtremeMusicApp)
        }

        // Initialize repository - with null safety checks
        try {
            repository = MusicRepository(database.musicDao(), this)
        } catch (e: Exception) {
            android.util.Log.e("XtremeMusicApp", "Failed to initialize repository: ${e.message}")
            throw RuntimeException("Critical: Cannot initialize repository", e)
        }

        // Initialize Audio Output Device Manager
        try {
            com.example.playback.AudioDeviceManager.init(this)
        } catch (e: Exception) {
            android.util.Log.e("XtremeMusicApp", "Failed to initialize AudioDeviceManager: ${e.message}")
        }

        // Initialize Smart Cache Manager
        try {
            com.example.data.cache.SmartCacheManager.initialize(this, repository)
        } catch (e: Exception) {
            android.util.Log.e("XtremeMusicApp", "Failed to initialize SmartCacheManager: ${e.message}")
        }
    }

    override fun onTerminate() {
        // Clean up sensitive data if needed
        try {
            SecurityConfig.clearStoredKeys(this)
        } catch (e: Exception) {
            android.util.Log.e("XtremeMusicApp", "Error clearing security config: ${e.message}")
        }
        super.onTerminate()
    }

    companion object {
        private var _instance: XtremeMusicApp? = null

        @JvmStatic
        fun getInstance(): XtremeMusicApp {
            return _instance ?: throw IllegalStateException("XtremeMusicApp instance not initialized")
        }

        val instance: XtremeMusicApp
            @JvmName("getAppInstance")
            get() = getInstance()
    }
}
