package com.example.service

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/** Refresh/replacement is asynchronous and owned by the existing player recovery path. */
internal class YouTubeStreamRefreshRequiredException(cause: IOException) :
    IOException("YouTube audio requires a fresh stream", cause)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (generateSequence<Throwable>(info.exception) { it.cause }.take(8).any {
            it is YouTubeStreamRefreshRequiredException || it is SabrStreamChangedException
        }) return C.TIME_UNSET
        return super.getRetryDelayMsFor(info)
    }
}
