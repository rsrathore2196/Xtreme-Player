package com.example.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.service.MusicCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Warm the existing audio cache without stopping the audible player or taking audio focus. */
internal object LosslessStreamPrebuffer {
    private val losslessMimes = setOf("audio/flac", "audio/raw", "audio/alac", "audio/wav", "audio/x-wav", "audio/aiff")
    suspend fun prepare(context: Context, item: MediaItem, expectedDurationMs: Long, currentPosition: () -> Long,
        stillCurrent: () -> Boolean): Boolean = withContext(Dispatchers.Main) {
        val factory = withContext(Dispatchers.IO) { MusicCache.createCacheDataSourceFactory(context.applicationContext) }
        if (!stillCurrent()) return@withContext false
        val staging = ExoPlayer.Builder(context.applicationContext)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_500, 2_000, 500, 1_000).build())
            .build()
        try {
            staging.setAudioAttributes(AudioAttributes.DEFAULT, false)
            staging.volume = 0f
            staging.playWhenReady = false
            staging.setMediaItem(item, currentPosition().coerceAtLeast(0L))
            staging.prepare()
            withTimeoutOrNull(6_000L) {
                var retargets = 0
                while (stillCurrent() && staging.playerError == null) {
                    val position = currentPosition().coerceAtLeast(0L)
                    if (staging.playbackState == Player.STATE_READY) {
                        if (staging.duration > 0 && kotlin.math.abs(staging.duration - expectedDurationMs) > 2_000L)
                            return@withTimeoutOrNull false
                        val mime = staging.audioFormat?.sampleMimeType
                        if (mime != null && mime !in losslessMimes)
                            return@withTimeoutOrNull false
                    }
                    if (staging.playbackState == Player.STATE_READY && staging.audioFormat != null &&
                        staging.currentPosition <= position && staging.bufferedPosition - position >= 1_000L)
                        return@withTimeoutOrNull true
                    if (staging.playbackState == Player.STATE_READY && staging.bufferedPosition <= position) {
                        if (++retargets > 3) return@withTimeoutOrNull false
                        staging.seekTo(position)
                    }
                    delay(100L)
                }
                false
            } ?: false
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { staging.release() }
        }
    }
}
