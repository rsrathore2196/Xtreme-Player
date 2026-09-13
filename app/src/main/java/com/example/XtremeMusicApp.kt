package com.example

import android.app.Application
import com.example.config.SecurityConfig
import com.example.data.local.MusicDatabase
import com.example.data.repository.MusicRepository
import com.example.data.remote.OnlineMusicApiService
import com.example.playback.PlaybackManager

class XtremeMusicApp : Application() {

    lateinit var database: MusicDatabase
        private set

    lateinit var repository: MusicRepository
        private set

    val playbackManager: PlaybackManager by lazy {
        PlaybackManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

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
        lateinit var instance: XtremeMusicApp
            private set

        /**
         * Get the application instance with null safety.
         * Throws an exception if the app hasn't been initialized.
         */
        fun getInstance(): XtremeMusicApp {
            if (!::instance.isInitialized) {
                throw RuntimeException("XtremeMusicApp instance not initialized")
            }
            return instance
        }
    }
}
