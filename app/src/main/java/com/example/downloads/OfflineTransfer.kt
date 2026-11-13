package com.example.downloads

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/** Downloads complete manifests/keys/segments or a complete progressive resource into permanent storage. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object OfflineTransfer {
    suspend fun save(store: OfflineDownloads, id: String, plan: OfflinePlan, upstream: DataSource.Factory,
        progress: (Long, Long) -> Unit = { _, _ -> }): Long {
        store.retainCache(id)
        try {
        val cache = store.dataSource(id, plan.progressive, upstream)
        val item = MediaItem.Builder().setUri(plan.uri).setMimeType(plan.mime).build()
        val downloader: Downloader = when (plan.mime) {
            MimeTypes.APPLICATION_M3U8 -> HlsDownloader(item, cache)
            MimeTypes.APPLICATION_MPD -> DashDownloader(item, cache)
            else -> ProgressiveDownloader(item, cache)
        }
        try {
            runInterruptible { downloader.download { total, bytes, _ -> progress(total, bytes) } }
            return store.cache(id).cacheSpace.takeIf { it > 0L } ?: throw IOException("No audio was saved")
        } finally { downloader.cancel() }
        } finally { store.releaseOwner(id) }
    }
}
