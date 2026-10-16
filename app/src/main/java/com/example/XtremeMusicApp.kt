package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
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

    val database: MusicDatabase by lazy { MusicDatabase.getDatabase(this) }
    val repository: MusicRepository by lazy { MusicRepository(database.musicDao(), this) }
    @Volatile var startupSettings: com.example.data.local.StartupSettings? = null
        private set
    private val startupSettingsClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun takeInitialStartupSettings(): com.example.data.local.StartupSettings? =
        if (startupSettingsClaimed.compareAndSet(false, true)) startupSettings else null
    private val _startupReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    val startupReady: kotlinx.coroutines.flow.StateFlow<Boolean> = _startupReady

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
        startupScope.launch { com.example.ui.util.TrackPaletteCache.initialize(this@XtremeMusicApp) }

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

        // Prepare local dependencies/preferences off Main, before the first screen uses them.
        // This never waits for a network request or creates the audio service.
        startupScope.launch {
            repository
            startupSettings = com.example.data.local.StartupSettings.read(this@XtremeMusicApp)
            val profile = requireNotNull(startupSettings).profile
            // Context registration is cheap; explicit cache controls must work even
            // before the optional maintenance job is admitted below.
            com.example.data.cache.SmartCacheManager.configure(this@XtremeMusicApp, repository)
            com.example.data.cache.HomeCardCache.preloadAsync(this@XtremeMusicApp,
                "${profile.countryCode}|${profile.languages.joinToString(",")}")
            // Construct Coil and its local disk cache on IO before the first AsyncImage composition.
            // This performs no image fetch or decode and leaves all request/caching policies intact.
            runCatching { imageLoader }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Image loader prewarm unavailable", it) }
            _startupReady.value = true
            com.example.performance.BackgroundWorkCoordinator.awaitIdle(minimumUptimeMillis = 2_500L)
            com.example.data.repository.ArtistPhotoRepository.prewarm(this@XtremeMusicApp)
        }

        // Construct the shared resolver off Main and warm its URL cache from Room.
        startupScope.launch {
            com.example.data.repository.iTunesArtworkRepository.getInstance(this@XtremeMusicApp)
        }

        startupScope.launch {
            kotlinx.coroutines.delay(2_500L)
            com.example.performance.BackgroundWorkCoordinator.awaitNavigationIdle()
            runCatching { com.example.playback.AudioDeviceManager.init(this@XtremeMusicApp) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Audio devices unavailable", it) }
            com.example.performance.BackgroundWorkCoordinator.awaitIdle(minimumUptimeMillis = 5_000L)
            runCatching { com.example.data.cache.SmartCacheManager.initialize(this@XtremeMusicApp, repository) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Cache maintenance unavailable", it) }
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
