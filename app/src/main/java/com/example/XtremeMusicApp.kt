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

class XtremeMusicApp : Application(), ImageLoaderFactory {

    lateinit var database: MusicDatabase
        private set

    lateinit var repository: MusicRepository
        private set

    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(this, repository)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB smart image disk cache
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        _instance = this

        // Initialize security configuration
        try {
            SecurityConfig.getDESKey(this)
        } catch (e: Exception) {
            android.util.Log.e("XtremeMusicApp", "Failed to initialize security: ${e.message}")
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

        // Initialize repository - with null safety checks
        try {
            repository = MusicRepository(database.musicDao())
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
