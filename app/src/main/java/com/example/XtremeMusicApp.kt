package com.example

import com.example.performance.AppDispatchers

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
import com.example.performance.BackgroundWorkCoordinator
import com.example.performance.BackgroundWorkCoordinator.StartupPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class XtremeMusicApp : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
    private val startupScope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    @Volatile internal var initializedImageDiskCache: DiskCache? = null
        private set

    val database: MusicDatabase by lazy { MusicDatabase.getDatabase(this) }
    val repository: MusicRepository by lazy {
        MusicRepository(this).also { com.example.data.cache.SmartCacheManager.configure(this, it) }
    }
    internal val settingsRepository by lazy { com.example.data.local.ReactiveSettingsRepository(this) }
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

    private val asynchronousImageLoader by lazy {
        com.example.ui.util.AsyncImageLoader(this, ::createImageLoader)
    }
    override fun newImageLoader(): ImageLoader = asynchronousImageLoader
    internal suspend fun awaitImageLoader(): ImageLoader = asynchronousImageLoader.await()

    private fun createImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components { add(com.example.ui.util.ArtworkResolutionInterceptor()) }
            // Bound actual decode work, without putting instant memory-cache hits
            // behind slow network requests in the artwork interceptor.
            .decoderDispatcher(AppDispatchers.CpuBounded)
            .transformationDispatcher(AppDispatchers.CpuBounded)
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
                    .maxSizeBytes(com.example.data.cache.SmartCacheManager.ARTWORK_CACHE_MAX_BYTES)
                    .build()
                com.example.data.cache.ManagedImageDiskCache(diskCache, cacheDir.resolve("artwork_cache_keys.json"))
                    .also { initializedImageDiskCache = it }
            }
            .bitmapConfig(if (android.os.Build.VERSION.SDK_INT >= 26) android.graphics.Bitmap.Config.HARDWARE else android.graphics.Bitmap.Config.ARGB_8888)
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
        // Install a lightweight handle before Compose. Construction and opening both
        // caches happen eagerly on IO; early requests await the same instance.
        coil.Coil.setImageLoader(asynchronousImageLoader)
        asynchronousImageLoader.start()
        // Only the local appearance/profile snapshot gates the first themed frame.
        startupScope.launch {
            BackgroundWorkCoordinator.startupTask(com.example.performance.StartupTask.SETTINGS,
                com.example.performance.StartupStatus.RUNNING)
            settingsRepository.preload()
            loadStartupSettings()
            BackgroundWorkCoordinator.startupTask(com.example.performance.StartupTask.SETTINGS,
                com.example.performance.StartupStatus.READY)
            _startupReady.value = true
            startupSettings?.profile?.let { profile ->
                com.example.data.cache.HomeCardCache.preloadAsync(this@XtremeMusicApp,
                    "${profile.countryCode}|${profile.languages.joinToString(",")}", previewOnly = true)
            }
        }

        startupScope.launch { com.example.playback.SleepTimerManager.preload(this@XtremeMusicApp) }
        startupScope.launch {
            // Even creating WorkManager's own database is optional IO work. Do not
            // launch it directly from the first-frame notification.
            BackgroundWorkCoordinator.startup(StartupPriority.DEFERRED, label = "Maintenance scheduling") {
                com.example.data.cache.SmartCacheManager.initialize(this@XtremeMusicApp)
                com.example.performance.MaintenanceScheduler.schedule(this@XtremeMusicApp)
            }
        }

        startupScope.launch {
            BackgroundWorkCoordinator.awaitFirstFrame()
            com.example.performance.DeferredLibraryInitialization.initialize(this@XtremeMusicApp)
        }

        // Context registration does not read storage or initialize the network client.
        OnlineMusicApiService.initialize(this)

        // Every heavy prewarm competes in the same admission queue as catalog,
        // history and cache work. On-demand playback/images bypass this queue.
        startupScope.launch {
            val coordinator = BackgroundWorkCoordinator
            coordinator.startup(StartupPriority.DEFERRED, label = "Palette prewarm") { runCatching { com.example.ui.util.TrackPaletteCache.initialize(this@XtremeMusicApp) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Palette prewarm unavailable", it) } }
            coordinator.startup(StartupPriority.DEFERRED, label = "Artwork metadata prewarm") { runCatching { com.example.data.repository.iTunesArtworkRepository.getInstance(this@XtremeMusicApp) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Artwork prewarm unavailable", it) } }
            coordinator.startup(StartupPriority.DEFERRED, label = "Playback key prewarm") { runCatching { SecurityConfig.getDESKey(this@XtremeMusicApp) } }
            // Reuse the small shared portrait cache restore. Visible portraits can
            // request it independently, without waiting for optional prewarming.
            runCatching { com.example.data.repository.ArtistPhotoRepository.prewarm(this@XtremeMusicApp).await() }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Artist prewarm unavailable", it) }
            coordinator.startup(StartupPriority.DEFERRED, label = "Audio devices prewarm") { runCatching { com.example.playback.AudioDeviceManager.init(this@XtremeMusicApp) }
                .onFailure { android.util.Log.w("XtremeMusicApp", "Audio devices unavailable", it) } }
        }
    }

    override fun onTerminate() {
        settingsCache.close()
        settingsRepository.close()
        asynchronousImageLoader.shutdown()
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
