package com.example.service

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import com.example.performance.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Lazy Media3 boundary for unresolved queue entries. Resolution uses the same
 * repository as an explicit selection; only the resolved child may open audio.
 * Media3's masking source owns period UID mapping until that child is ready.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PolicyResolvingMediaSource(
    item: MediaItem,
    private val factory: MediaSource.Factory,
    private val scope: CoroutineScope,
    private val resolutionTimeoutMs: Long = com.example.data.repository.StreamResolver.PLAYBACK_RESOLUTION_TIMEOUT_MS,
    private val resolvedIsCurrent: (MediaItem) -> Boolean = { true },
    private val resolve: suspend (MediaItem) -> MediaItem
) : CompositeMediaSource<Unit>() {
    private var item = item
    private var child: MediaSource? = null
    private var job: Job? = null
    private var revision = 0L
    private var failure: IOException? = null

    override fun getMediaItem() = item
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        failure = null
        val generation = ++revision
        val handler = Handler(checkNotNull(Looper.myLooper()))
        val requested = item
        job = scope.launch(AppDispatchers.Io) {
            try {
                val resolved = withTimeout(resolutionTimeoutMs) { resolve(requested) }
                ensureActive()
                check(resolved.mediaId == requested.mediaId) { "Resolved a different catalog identity" }
                handler.post {
                    if (generation == revision) try {
                        if (!resolvedIsCurrent(resolved)) throw com.example.playback.PlaybackDiagnostics.Failure(
                            com.example.playback.PlaybackDiagnostics.Reason.POLICY,
                            com.example.playback.PlaybackDiagnostics.Stage.VALIDATION)
                        item = resolved
                        val prepared = factory.createMediaSource(resolved)
                        child = prepared // A cached child can publish its timeline synchronously.
                        prepareChildSource(Unit, prepared)
                    } catch (error: Exception) { failure = IOException("Unable to prepare the resolved stream", error) }
                }
            } catch (cancelled: CancellationException) {
                // A nested/shared lookup may be cancelled while this source is
                // still selected. It must fail, rather than buffer without a child.
                handler.post { if (generation == revision && child == null)
                    failure = IOException("Queue stream resolution cancelled before preparation", cancelled) }
                throw cancelled
            }
            catch (error: Exception) {
                handler.post { if (generation == revision) failure = error as? IOException ?: IOException(error) }
            }
        }
        job?.invokeOnCompletion { error ->
            // Also covers a scope already cancelled before launch could execute.
            if (error != null) handler.post { if (generation == revision && child == null && failure == null)
                failure = IOException("Queue stream preparation did not complete", error) }
        }
    }

    override fun onChildSourceInfoRefreshed(id: Unit, mediaSource: MediaSource, timeline: Timeline) {
        refreshSourceInfo(timeline)
    }
    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child).createPeriod(id, allocator, startPositionUs)
    override fun releasePeriod(mediaPeriod: MediaPeriod) { checkNotNull(child).releasePeriod(mediaPeriod) }
    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean = mediaItem.mediaId == item.mediaId &&
        child?.canUpdateMediaItem(mediaItem) == true
    override fun updateMediaItem(mediaItem: MediaItem) { item = mediaItem; child?.updateMediaItem(mediaItem) }
    override fun releaseSourceInternal() {
        ++revision
        job?.cancel(); job = null
        super.releaseSourceInternal()
        child = null
    }
}
