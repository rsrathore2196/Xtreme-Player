package com.example

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.example.service.PolicyResolvingMediaSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PolicyResolvingMediaSourceTest {
    private val original = MediaItem.Builder().setMediaId("online_legacy")
        .setUri("https://aac.saavncdn.com/song_320.mp4").build()
    private val youtube = original.buildUpon().setUri("xtreme-youtube://audio/abcdefghijk/stream.audio?cap=320000").build()
    private suspend fun until(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
    }

    private class Factory : MediaSource.Factory {
        var prepared = 0
        var periods = 0
        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = object : BaseMediaSource() {
            private var item = mediaItem
            override fun getMediaItem() = item
            override fun prepareSourceInternal(listener: TransferListener?) {
                prepared++
                refreshSourceInfo(SinglePeriodTimeline(1_000_000L, true, false, false, null, item))
            }
            override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
                periods++
                return Proxy.newProxyInstance(MediaPeriod::class.java.classLoader, arrayOf(MediaPeriod::class.java)) { _, _, _ -> null } as MediaPeriod
            }
            override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit
            override fun releaseSourceInternal() = Unit
            override fun maybeThrowSourceInfoRefreshError() = Unit
            override fun canUpdateMediaItem(mediaItem: MediaItem) = item.localConfiguration?.uri == mediaItem.localConfiguration?.uri
            override fun updateMediaItem(mediaItem: MediaItem) { item = mediaItem }
        }
    }

    @Test fun unresolvedQueueEntryWaitsForThePrimaryProviderBeforePreparingAnyAudio() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val answer = CompletableDeferred<MediaItem>()
        val started = CompletableDeferred<Unit>()
        val factory = Factory()
        val source = PolicyResolvingMediaSource(original, factory, scope) {
            assertNotEquals("Resolution must stay off Main", Looper.getMainLooper(), Looper.myLooper())
            started.complete(Unit); answer.await()
        }
        var timeline: Timeline? = null
        val caller = MediaSource.MediaSourceCaller { _, value -> timeline = value }
        source.prepareSource(caller, null)
        try {
            withTimeout(3_000) { started.await() }
            assertEquals(0, factory.prepared)
            assertNull(timeline)
            answer.complete(youtube)
            until { timeline != null }
            assertEquals(1, factory.prepared)
            assertEquals(youtube.localConfiguration?.uri, timeline!!.getWindow(0, Timeline.Window()).mediaItem.localConfiguration?.uri)
            assertEquals(original.mediaId, source.mediaItem.mediaId)
        } finally { source.releaseSource(caller); scope.cancel() }
    }

    @Test fun lazyMaskingPeriodUsesTheResolvedChildAndSurvivesMetadataOnlyUpdates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val factory = Factory()
        val deferred = PolicyResolvingMediaSource(original, factory, scope) { youtube }
        val source = MaskingMediaSource(deferred, true)
        var timeline: Timeline? = null
        val caller = MediaSource.MediaSourceCaller { _, value -> timeline = value }
        source.prepareSource(caller, null)
        val period = source.createPeriod(MediaSource.MediaPeriodId(source.timeline.getUidOfPeriod(0)), DefaultAllocator(true, 65536), 0)
        try {
            until { factory.periods == 1 }
            assertEquals(1, factory.prepared)
            assertEquals(youtube.localConfiguration?.uri, timeline!!.getWindow(0, Timeline.Window()).mediaItem.localConfiguration?.uri)
            val metadataOnly = youtube.buildUpon().setMediaMetadata(
                youtube.mediaMetadata.buildUpon().setTitle("The same track").build()).build()
            assertTrue(deferred.canUpdateMediaItem(metadataOnly))
            deferred.updateMediaItem(metadataOnly)
            assertEquals("The same track", deferred.mediaItem.mediaMetadata.title)
            assertEquals(1, factory.prepared)
        } finally { source.releasePeriod(period); source.releaseSource(caller); scope.cancel() }
    }

    @Test fun releasedSourceCannotInstallALateLookupResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val factory = Factory()
        val source = PolicyResolvingMediaSource(original, factory, scope) {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            finished.complete(Unit); youtube
        }
        var publications = 0
        val caller = MediaSource.MediaSourceCaller { _, _ -> publications++ }
        source.prepareSource(caller, null)
        try {
            withTimeout(3_000) { started.await() }
            source.releaseSource(caller)
            release.complete(Unit)
            withTimeout(3_000) { finished.await() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, factory.prepared); assertEquals(0, publications)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun failedResolutionPropagatesWithoutPreparingTheOldJioUrl() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failed = CompletableDeferred<Unit>()
        val factory = Factory()
        val source = PolicyResolvingMediaSource(original, factory, scope) { failed.complete(Unit); throw IOException("All providers unavailable") }
        val caller = MediaSource.MediaSourceCaller { _, _ -> error("No usable timeline") }
        source.prepareSource(caller, null)
        try {
            withTimeout(3_000) { failed.await() }
            var observed = false
            until {
                try { source.maybeThrowSourceInfoRefreshError() } catch (expected: IOException) { observed = true }
                observed
            }
            assertEquals(0, factory.prepared)
        } finally { source.releaseSource(caller); scope.cancel() }
    }
}
