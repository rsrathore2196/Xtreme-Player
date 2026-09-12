package com.example

import android.app.Application
import com.example.data.local.MusicDatabase
import com.example.data.repository.MusicRepository
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

        database = MusicDatabase.getDatabase(this)
        repository = MusicRepository(database.musicDao())
    }

    override fun onTerminate() {
        super.onTerminate()
    }

    companion object {
        lateinit var instance: XtremeMusicApp
            private set
    }
}
