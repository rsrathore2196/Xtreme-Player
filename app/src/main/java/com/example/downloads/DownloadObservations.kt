package com.example.downloads

import com.example.data.model.MusicTrack
import com.example.performance.AppDispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal data class DownloadSummary(val bytes: Long, val saved: Int, val partial: Boolean, val hasRecords: Boolean)

/** Progress from another track must not invalidate a track menu or a saved collection. */
internal fun OfflineDownloads.observeRecord(id: String?) = records.map { it[id] }.distinctUntilChanged()
internal fun OfflineDownloads.observeStatus(id: String) = records.map { it[id]?.status }.distinctUntilChanged()
internal fun OfflineDownloads.observeSavedCount() = records.map { records ->
    records.values.count { it.status == DownloadStatus.COMPLETE && it.plan != null }
}.distinctUntilChanged().flowOn(AppDispatchers.CpuBounded)

internal fun OfflineDownloads.observeSavedRecords() = records.map { records ->
    records.values.filter { it.status == DownloadStatus.COMPLETE && it.plan != null }
}.distinctUntilChanged().map { saved ->
    // Sorting occurs only when the saved collection actually changes.
    saved.sortedWith(compareByDescending<DownloadRecord> { it.addedAt }.thenBy { it.track.id })
}.flowOn(AppDispatchers.CpuBounded)

internal fun OfflineDownloads.observeSummary() = records.map { records ->
    var bytes = 0L
    var saved = 0
    var partial = false
    for (record in records.values) {
        bytes += record.bytes.coerceAtLeast(0)
        if (record.status == DownloadStatus.COMPLETE) saved++
        else if (record.bytes > 0) partial = true
    }
    DownloadSummary(bytes, saved, partial, records.isNotEmpty())
}.distinctUntilChanged().flowOn(AppDispatchers.CpuBounded)

internal fun OfflineDownloads.observeCollectionProgress(tracks: List<MusicTrack>, key: String, complete: Boolean) =
    collections.map { it[key] }.distinctUntilChanged()
        .map { CollectionDownloadMembership(tracks, it, complete) }
        .combine(records) { membership, records -> membership.progress(records) }
        .distinctUntilChanged().flowOn(AppDispatchers.CpuBounded)
