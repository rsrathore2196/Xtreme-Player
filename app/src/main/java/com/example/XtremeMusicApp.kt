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
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.BackgroundWorkCoordinator.StartupPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class XtremeMusicApp : Application(), ImageLoaderFactory {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile internal var initializedImageDiskCache: DiskCache? = null
        private set

    val database: MusicDatabase by lazy { MusicDatabase.getDatabase(this) }
    val repository: MusicRepository by lazy {
        MusicRepository(this).also { com.example.data.cache.SmartCacheManager.configure(this, it) }
    }
    private val settingsCache = com.example.data.local.StartupSettingsCache(this)
    val startupSettings: com.example.data.local.StartupSettings? get() = settingsCache.peek()
    internal fun takeInitialStartupSettings(): com.example.data.local.StartupSettings? =
        settingsCache.peek()
    internal suspend fun loadStartupSettings() = settingsCache.load()
    private val _startupReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    val startupReady: kotlinx.coroutines.flow.StateFlow<Boolean> = _startupReady

    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(this, repository).also {
            val player = it
            startupScope.launch {
                // The observer is essential to playback, but acquiring its DAOs is not UI work.
                com.example.recommendation.RecommendationListeningObserver(database.listeningEventDao(), database.musicDao()).start(player)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components { add(com.example.ui.util.ArtworkResolutionInterceptor()) }
            // Bound actual decode work, without putting instant memory-cache hits
            // behind slow network requests in the artwork interceptor.
            .decoderDispatcher(BackgroundWorkCoordinator.optionalCpuDispatcher)
            .okHttpClient(okhttp3.OkHttpClient.Builder()
                .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 4 })
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
                    .also { initializedImageDiskCache = it }
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
        // Only the local appearance/profile snapshot gates the first themed frame.
        startupScope.launch {
            loadStartupSettings()
            _startupReady.value = true
            startupSettings?.profile?.let { profile ->
                com.example.data.cache.HomeCardCache.preloadAsync(this@XtremeMusicApp,
                    "${profile.countryCode}|${profile.languages.joinToString(",")}", previewOnly = true)
            }
        }

        // Context registration does not read storage or initialize the network client.
        OnlineMusicApiService.initialize(this)
        // One asynchronous local preference; no DSP or disk work on the first-render path.
        com.example.playback.AudioEffectsManager.initialize(this)

        // Every heavy prewarm competes in the same admission queue as catalog,
        // history and cache work. On-demand playback/images bypass this queue.
        startupScope.launch {
            val coordinator = BackgroundWorkCoordinator
            coordinator.startup(StartupPriority.DEFERRED, label = "Palette prewarm") { runCatching { com.example.ui.util.TrackPaletteCache.initialize(this@XtremeMusicApp) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Palette prewarm unavailable", it) } }
            coordinator.startup(StartupPriority.DEFERRED, label = "Image loader prewarm") { runCatching { imageLoader }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Image loader prewarm unavailable", it) } }
            coordinator.startup(StartupPriority.DEFERRED, label = "Artwork metadata prewarm") { runCatching { com.example.data.repository.iTunesArtworkRepository.getInstance(this@XtremeMusicApp) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Artwork prewarm unavailable", it) } }
            coordinator.startup(StartupPriority.DEFERRED, label = "Playback key prewarm") { runCatching { SecurityConfig.getDESKey(this@XtremeMusicApp) } }
            coordinator.startup(StartupPriority.DEFERRED, label = "Portrait cache prewarm") { runCatching { com.example.data.repository.ArtistPhotoRepository.prewarm(this@XtremeMusicApp).await() }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Artist prewarm unavailable", it) } }
            coordinator.startup(StartupPriority.DEFERRED, label = "Audio devices prewarm") { runCatching { com.example.playback.AudioDeviceManager.init(this@XtremeMusicApp) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Audio devices unavailable", it) } }
            com.example.data.cache.SmartCacheManager.configure(this@XtremeMusicApp, repository)
            coordinator.startup(StartupPriority.MAINTENANCE, requireForeground = false) { runCatching { com.example.data.cache.SmartCacheManager.initialize(this@XtremeMusicApp, repository) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Cache maintenance unavailable", it) } }
        }
    }

    override fun onTerminate() {
        settingsCache.close()
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
