package com.example.downloads

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Permanent, non-evicting storage, independent of the temporary streaming cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineDownloads internal constructor(
    context: Context,
    index: DownloadIndex? = null
) {
    private val context = context.applicationContext
    private val index = index ?: AtomicDownloadIndex { File(this.context.noBackupFilesDir, "offline_audio/index.json") }
    private val lock = Mutex()
    private val commands = Mutex()
    private var loaded = false
    private val root get() = File(context.noBackupFilesDir, "offline_audio").apply { mkdirs() }
    private val _records = MutableStateFlow<Map<String, DownloadRecord>>(emptyMap())
    val records = _records.asStateFlow()
    private val _preferences = MutableStateFlow(DownloadPreferences())
    val preferences = _preferences.asStateFlow()
    private val _collections = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    // Keep the full requested membership even when a later-page track is removed.
    // A paginated header must not mistake its remaining downloads for a complete collection.
    internal val collections = _collections.asStateFlow()
    private val caches = mutableMapOf<String, SimpleCache>()
    private val owners = mutableMapOf<String, Int>()
    private val pendingDeletion = mutableSetOf<String>()
    private val database by lazy { StandaloneDatabaseProvider(context) }

    suspend fun load() = withContext(Dispatchers.IO) {
        val first = lock.withLock { val first = !loaded; loadLocked(); first }
        if (first) reconcileQueue()
    }
    private suspend fun reconcileQueue() {
        for (record in records.value.values) {
            if (record.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) continue
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName(record.track.id)).get().firstOrNull()
            when (work?.state) {
                androidx.work.WorkInfo.State.CANCELLED -> update(record.track.id) { it.copy(status = DownloadStatus.PAUSED) }
                androidx.work.WorkInfo.State.FAILED -> update(record.track.id) { it.copy(status = DownloadStatus.FAILED, error = "Download interrupted. Tap retry.") }
                null, androidx.work.WorkInfo.State.SUCCEEDED -> schedule(record.track.id)
                else -> Unit
            }
        }
    }
    private fun loadLocked() {
        if (loaded) return
        index.read()?.let { bytes ->
            val saved = JSONObject(String(bytes, Charsets.UTF_8))
            val items = saved.optJSONArray("records") ?: JSONArray()
            _records.value = (0 until items.length()).map { OfflineJson.record(items.getJSONObject(it)) }
                .associateBy { it.track.id }
            _preferences.value = DownloadPreferences(saved.optBoolean("wifi", true),
                runCatching { DownloadQuality.valueOf(saved.optString("quality")) }.getOrDefault(DownloadQuality.HIGH))
            val collections = saved.optJSONObject("collections") ?: JSONObject()
            _collections.value = collections.keys().asSequence().associateWith { key ->
                val ids = collections.optJSONArray(key) ?: JSONArray()
                (0 until ids.length()).map { ids.getString(it) }.distinct()
            }
        }
        loaded = true
    }
    private fun commitLocked(
        records: Map<String, DownloadRecord> = _records.value,
        preferences: DownloadPreferences = _preferences.value,
        collections: Map<String, List<String>> = _collections.value
    ) {
        val payload = JSONObject().put("version", 1).put("wifi", preferences.wifiOnly)
            .put("quality", preferences.quality.name)
            .put("collections", JSONObject().also { saved ->
                collections.forEach { (key, ids) -> saved.put(key, JSONArray(ids)) }
            })
            .put("records", JSONArray(records.values.map(OfflineJson::record))).toString().toByteArray()
        index.write(payload)
        // Observers must never see a completed mutation that failed to reach disk.
        _records.value = records
        _preferences.value = preferences
        _collections.value = collections
    }
    internal suspend fun update(id: String, persist: Boolean = true, transform: (DownloadRecord) -> DownloadRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            loadLocked()
            _records.value[id]?.let { before ->
                val changed = transform(before)
                if (changed == before) return@let
                val next = _records.value + (id to changed)
                if (persist) commitLocked(records = next) else _records.value = next
            }
        }
    }
    internal suspend fun put(record: DownloadRecord) = withContext(Dispatchers.IO) {
        lock.withLock { loadLocked(); commitLocked(records = _records.value + (record.track.id to record)) }
    }
    suspend fun enqueue(tracks: List<MusicTrack>, collection: String = "", collectionKey: String? = null) = withContext(Dispatchers.IO) {
        commands.withLock {
            load()
            val queued = lock.withLock {
                val old = _records.value
                val oldCollections = _collections.value
                val next = old.toMutableMap()
                val ids = mutableListOf<String>()
                for (track in tracks.distinctBy { it.id }) {
                    if (Uri.parse(track.audioUrl).scheme in setOf("file", "content", SCHEME)) continue
                    val previous = next[track.id]
                    if (previous?.status in setOf(DownloadStatus.COMPLETE, DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED)) continue
                    synchronized(caches) { check(track.id !in pendingDeletion) { "The removed audio is still in use" } }
                    next[track.id] = previous?.copy(status = DownloadStatus.QUEUED, error = null) ?: DownloadRecord(track, collection = collection)
                    ids += track.id
                }
                val collections = if (collectionKey == null) oldCollections else oldCollections +
                    (collectionKey to tracks.distinctBy { it.id }.filter {
                        Uri.parse(it.audioUrl).scheme !in setOf("file", "content", SCHEME) || it.id in next
                    }.map { it.id })
                if (next != old || collections != oldCollections) commitLocked(records = next, collections = collections)
                ids
            }
            queued.forEach(::schedule)
        }
    }
    private fun schedule(id: String) {
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf("track_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                if (_preferences.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true).build())
            .addTag(WORK_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request).result.get()
    }
    suspend fun setPreferences(value: DownloadPreferences) = updatePreferences { value }
    suspend fun updatePreferences(transform: (DownloadPreferences) -> DownloadPreferences) = withContext(Dispatchers.IO) {
        commands.withLock {
            load()
            val reschedule = lock.withLock {
                val value = transform(_preferences.value)
                val networkChanged = value.wifiOnly != _preferences.value.wifiOnly
                val ids = if (networkChanged) _records.value.values.filter {
                    it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
                }.map { it.track.id } else emptyList()
                val next = if (ids.isEmpty()) _records.value else _records.value.toMutableMap().apply {
                    ids.forEach { id -> get(id)?.let { put(id, it.copy(status = DownloadStatus.PAUSED)) } }
                }
                if (value != _preferences.value) commitLocked(records = next, preferences = value)
                ids
            }
            if (reschedule.isNotEmpty()) {
                // Stop the old requests while their records are PAUSED. Their
                // cancellation handlers must not pause a newly queued replacement.
                val workManager = WorkManager.getInstance(context)
                reschedule.forEach { workManager.cancelUniqueWork(workName(it)).result.get() }
                lock.withLock {
                    val next = _records.value.toMutableMap()
                    reschedule.forEach { id -> next[id]?.let {
                        if (it.status == DownloadStatus.PAUSED) next[id] = it.copy(status = DownloadStatus.QUEUED, error = null)
                    } }
                    commitLocked(records = next)
                }
                reschedule.forEach(::schedule)
            }
        }
    }
    suspend fun pause(id: String): Unit = commands.withLock {
        update(id) { if (it.status == DownloadStatus.COMPLETE) it else it.copy(status = DownloadStatus.PAUSED) }
        withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get() }
    }
    suspend fun resume(id: String) = commands.withLock {
        update(id) { if (it.status == DownloadStatus.COMPLETE) it else it.copy(status = DownloadStatus.QUEUED, error = null) }
        if (records.value[id]?.status == DownloadStatus.QUEUED) withContext(Dispatchers.IO) { schedule(id) }
    }
    suspend fun remove(id: String): Unit = removeRecords(setOf(id))
    suspend fun clear(): Unit = removeRecords(null)

    private suspend fun removeRecords(selected: Set<String>?): Unit = withContext(Dispatchers.IO) {
        commands.withLock {
            load()
            val removed = lock.withLock {
                val ids = selected ?: _records.value.keys.toSet()
                val next = _records.value - ids
                if (next != _records.value) commitLocked(records = next)
                synchronized(caches) {
                    pendingDeletion += ids
                    ids.forEach { if (owners[it] == null) disposeCacheLocked(it) }
                }
                ids
            }
            // One index commit for an entire collection. Existing readers hold leases
            // until close, while the removed entries cannot acquire new playback leases.
            val workManager = WorkManager.getInstance(context)
            if (selected == null) workManager.cancelAllWorkByTag(WORK_TAG).result.get()
            else removed.forEach { workManager.cancelUniqueWork(workName(it)).result.get() }
        }
    }
    internal fun directory(id: String): File = File(root, downloadKey(id)).apply { mkdirs() }
    internal fun cache(id: String): SimpleCache = synchronized(caches) {
        caches.getOrPut(id) { SimpleCache(File(directory(id), "media"), NoOpCacheEvictor(), database) }
    }
    internal fun releaseCache(id: String) = synchronized(caches) { caches.remove(id)?.release(); Unit }
    internal fun retainCache(id: String, expected: DownloadRecord? = null): Boolean = synchronized(caches) {
        if (id in pendingDeletion || expected != null && _records.value[id] != expected) return@synchronized false
        owners[id] = (owners[id] ?: 0) + 1
        true
    }
    internal fun releaseOwner(id: String) = synchronized(caches) {
        val count = (owners[id] ?: 1) - 1
        if (count > 0) owners[id] = count else { owners.remove(id); disposeCacheLocked(id) }
    }
    private fun disposeCacheLocked(id: String) {
        caches.remove(id)?.release()
        if (id in pendingDeletion) {
            val folder = File(root, downloadKey(id))
            val media = File(folder, "media")
            if (media.exists()) SimpleCache.delete(media, database)
            check(!folder.exists() || folder.deleteRecursively()) { "Could not remove saved audio" }
            pendingDeletion.remove(id)
        }
    }
    internal fun clearPartial(id: String) {
        val cache = cache(id)
        cache.keys.toList().forEach(cache::removeResource)
    }
    internal fun dataSource(id: String, progressive: Boolean, upstream: DataSource.Factory? = null): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache(id)).setCacheKeyFactory { spec ->
            if (progressive) "audio" else spec.uri.toString()
        }.setUpstreamDataSourceFactory(upstream).apply { if (upstream == null) setCacheWriteDataSinkFactory(null) }

    suspend fun localTrack(track: MusicTrack): MusicTrack? = withContext(Dispatchers.IO) {
        load()
        val saved = records.value[track.id]?.takeIf { it.status == DownloadStatus.COMPLETE && it.plan != null } ?: return@withContext null
        // Check the cache on disk as well as the ledger. Clearing app data/files must never leave phantom downloads.
        if (!retainCache(track.id, saved)) return@withContext null
        val missing = try { cache(track.id).keys.isEmpty() } finally { releaseOwner(track.id) }
        if (missing) {
            update(track.id) { if (it != saved) it else it.copy(status = DownloadStatus.FAILED, error = "Saved audio is missing. Download again.") }
            return@withContext null
        }
        if (records.value[track.id] != saved) return@withContext null
        asOfflineTrack(track, saved)
    }
    internal suspend fun localQueue(tracks: List<MusicTrack>): List<MusicTrack> = withContext(Dispatchers.IO) {
        load()
        tracks.mapNotNull { track -> records.value[track.id]?.takeIf { it.status == DownloadStatus.COMPLETE }
            ?.let { asOfflineTrack(track, it) } }
    }
    private fun asOfflineTrack(track: MusicTrack, saved: DownloadRecord): MusicTrack =
        track.copy(audioUrl = Uri.Builder().scheme(SCHEME).authority("audio").appendPath(downloadKey(track.id)).build().toString(),
            coverUrl = saved.artwork.takeIf { File(it).isFile }?.let { Uri.fromFile(File(it)).toString() } ?: track.coverUrl,
            bitrateKbps = saved.track.bitrateKbps, qualityBadge = saved.track.qualityBadge,
            isLossless = saved.track.isLossless, isCached = true)
    /** Loader opens only the saved cache. A miss fails locally rather than silently streaming. */
    internal fun source(item: MediaItem): MediaSource {
        val record = records.value[item.mediaId]?.takeIf { it.status == DownloadStatus.COMPLETE }
            ?: throw IOException("This download is no longer on the device")
        val plan = record.plan ?: throw IOException("Downloaded audio is incomplete")
        val lazySource = DataSource.Factory {
            object : DataSource {
                private var delegate: DataSource? = null
                private val listeners = mutableListOf<TransferListener>()
                override fun addTransferListener(listener: TransferListener) { listeners += listener; delegate?.addTransferListener(listener) }
                override fun open(dataSpec: DataSpec): Long {
                    check(delegate == null) { "Offline data source is already open" }
                    if (!retainCache(item.mediaId, record)) throw IOException("This download is no longer on the device")
                    try {
                        val source = dataSource(item.mediaId, plan.progressive).createDataSource()
                        delegate = source
                        listeners.forEach(source::addTransferListener)
                        return source.open(dataSpec)
                    } catch (error: Exception) { if (delegate == null) releaseOwner(item.mediaId) else close(); throw error }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(delegate).read(buffer, offset, length)
                override fun getUri(): Uri? = delegate?.uri
                override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
                override fun close() {
                    val source = delegate ?: return
                    delegate = null
                    try { source.close() } finally { releaseOwner(item.mediaId) }
                }
            }
        }
        val child = DefaultMediaSourceFactory(context).setDataSourceFactory(lazySource)
            .createMediaSource(item.buildUpon().setUri(plan.uri).setMimeType(plan.mime).setCustomCacheKey(null).build())
        return object : WrappingMediaSource(child) {
            override fun getMediaItem() = item
            override fun canUpdateMediaItem(mediaItem: MediaItem) = false
            private fun offlineTimeline(timeline: Timeline) = object : ForwardingTimeline(timeline) {
                override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window {
                    super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                    window.mediaItem = item
                    return window
                }
            }
            override fun getInitialTimeline(): Timeline? = child.initialTimeline?.let(::offlineTimeline)
            override fun onChildSourceInfoRefreshed(timeline: Timeline) { refreshSourceInfo(offlineTimeline(timeline)) }
        }
    }
    companion object {
        const val SCHEME = "xtreme-offline"
        const val WORK_TAG = "offline_audio_download"
        internal fun workName(id: String) = "offline:${downloadKey(id)}"
        @android.annotation.SuppressLint("StaticFieldLeak") // Constructor retains only applicationContext.
        @Volatile private var instance: OfflineDownloads? = null
        fun get(context: Context): OfflineDownloads = instance ?: synchronized(this) {
            instance ?: OfflineDownloads(context.applicationContext).also { instance = it }
        }
    }
}
