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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow

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
    private val _launchSettings = kotlinx.coroutines.flow.MutableStateFlow<com.example.data.local.StartupSettings?>(null)
    val launchSettings = _launchSettings.asStateFlow()
    private var launchSettingsJob: kotlinx.coroutines.Job? = null

    fun prepareLaunchSettings() {
        settingsCache.peek()?.let { _launchSettings.value = it; return }
        if (launchSettingsJob?.isActive == true) return
        _launchSettings.value = null
        launchSettingsJob = startupScope.launch {
            _launchSettings.value = try { loadStartupSettings() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("XtremeMusicApp", "Launch preferences unavailable", error)
                com.example.data.local.StartupSettings(profile =
                    com.example.data.local.UserProfilePreferences.peekUserProfile() ?: com.example.data.local.UserProfile())
            }
        }
    }

    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(this, repository).also {
            val player = it
            startupScope.launch {
                // Observe service-owned listening measurements without opening storage on Main.
                com.example.recommendation.RecommendationListeningObserver(this@XtremeMusicApp).start(player)
            }
        }
    }

    private val asynchronousImageLoader by lazy {
        com.example.ui.util.AsyncImageLoader(this, ::createImageLoader)
    }
    override fun newImageLoader(): ImageLoader = asynchronousImageLoader
    internal suspend fun awaitImageLoader(): ImageLoader = asynchronousImageLoader.await()

    private fun createImageLoader(): ImageLoader {
        com.example.performance.MaintenanceScheduler.onCacheUsed(this)
        return ImageLoader.Builder(this)
            .components { add(com.example.ui.util.ArtworkResolutionInterceptor()) }
            .eventListenerFactory {
                if (androidx.tracing.Trace.isEnabled()) com.example.ui.util.ArtworkTraceEventListener()
                else coil.EventListener.NONE
            }
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
        // Coil remains a lazy singleton; the first visible image owns cache creation.
        coil.Coil.setImageLoader(asynchronousImageLoader)
        // Registration only: the client is initialized by an actual request.
        OnlineMusicApiService.initialize(this)

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
