package com.example.data.cache

import coil.disk.DiskCache
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/** Retains Coil's cache ownership while exposing LRU maintenance through its public API. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class ManagedImageDiskCache(private val delegate: DiskCache, private val keyFile: File) : DiskCache by delegate {
    private val lock = Any()
    private val diskWriteLock = Any()
    private val accesses = LinkedHashMap<String, Long>()
    private val keysLoaded = CompletableDeferred<Unit>()
    private val keyLoadMutex = Mutex()
    private var persistedRevision = 0L
    private var generation = 0
    private var accessRevision = 0L

    private suspend fun loadKeys() = keyLoadMutex.withLock {
        if (keysLoaded.isCompleted) return@withLock
        val version = synchronized(lock) { generation }
        try {
            val text = synchronized(diskWriteLock) {
                runCatching { if (keyFile.length() in 1..1_000_000) keyFile.readText() else null }.getOrNull()
            }
            val restored = withContext(BackgroundWorkCoordinator.optionalCpuDispatcher) { runCatching {
                val entries = JSONArray(text ?: "[]")
                val keys = LinkedHashMap<String, Long>()
                for (index in 0 until minOf(entries.length(), 2000)) {
                    val row = entries.optJSONArray(index) ?: continue
                    val key = row.optString(0)
                    if (key.isNotBlank()) keys[key] = row.optLong(1)
                }
                keys.toList().sortedBy { it.second }
            }.getOrDefault(emptyList()) }
            synchronized(lock) {
                // A clear during loading must not resurrect old keys. Newer live accesses win.
                if (version == generation) {
                    val live = accesses.toList()
                    accesses.clear()
                    restored.forEach { (key, time) ->
                        accesses[key] = time
                    }
                    live.forEach { (key, time) -> accesses.remove(key); accesses[key] = time }
                    while (accesses.size > 2000) accesses.remove(accesses.keys.first())
                }
            }
        } finally {
            keysLoaded.complete(Unit)
        }
    }

    /** Includes decoded-memory hits without opening a file or persisting on Main. */
    internal fun recordAccess(key: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val previous = accesses[key]
        if (previous != null && now - previous in 0 until 60_000L) return@synchronized
        accesses.remove(key)
        accesses[key] = now
        accessRevision++
        while (accesses.size > 2000) accesses.remove(accesses.keys.first())
        // The delayed WorkManager owner checkpoints this advisory LRU index.
    }

    /** Advisory access metadata only. Coil remains the sole disk-cache/journal owner. */
    suspend fun flushAccessIndex() {
        loadKeys()
        val (version, revision, snapshot) = synchronized(lock) {
            Triple(generation, accessRevision, accesses.toList())
        }
        if (revision == persistedRevision) return
        val json = withContext(BackgroundWorkCoordinator.optionalCpuDispatcher) {
            val entries = JSONArray()
            snapshot.forEachIndexed { index, (entry, time) ->
                if (index % 128 == 0) BackgroundWorkCoordinator.yieldToInteraction(bypass = { !BackgroundWorkCoordinator.uiForeground.value })
                entries.put(JSONArray().put(entry).put(time))
            }
            entries.toString()
        }
        synchronized(diskWriteLock) {
            if (synchronized(lock) { version == generation }) {
                val temp = File(keyFile.parentFile, "${keyFile.name}.tmp")
                temp.writeText(json)
                check(temp.renameTo(keyFile)) { "Could not checkpoint artwork access index" }
                persistedRevision = revision
            }
        }
    }

    override fun openSnapshot(key: String): DiskCache.Snapshot? = delegate.openSnapshot(key)?.also {
        recordAccess(key)
    }
    override fun openEditor(key: String): DiskCache.Editor? = delegate.openEditor(key)?.let { editor ->
        recordAccess(key)
        object : DiskCache.Editor by editor {
            override fun commit() {
                editor.commit()
                SmartCacheManager.cacheUsageChanged(imageBytes = delegate.size)
            }
            override fun commitAndOpenSnapshot(): DiskCache.Snapshot? = editor.commitAndOpenSnapshot().also {
                SmartCacheManager.cacheUsageChanged(imageBytes = delegate.size)
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun commitAndGet(): DiskCache.Snapshot? = commitAndOpenSnapshot()
        }
    }
    override fun remove(key: String): Boolean = removeEntry(key, reportUsage = true)

    private fun removeEntry(key: String, reportUsage: Boolean): Boolean = delegate.remove(key).also { removed ->
        if (removed) {
            synchronized(lock) { if (accesses.remove(key) != null) accessRevision++ }
            if (reportUsage) SmartCacheManager.cacheUsageChanged(imageBytes = delegate.size)
        }
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun get(key: String): DiskCache.Snapshot? = openSnapshot(key)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun edit(key: String): DiskCache.Editor? = openEditor(key)

    override fun clear() {
        delegate.clear()
        SmartCacheManager.cacheUsageChanged(imageBytes = delegate.size)
        synchronized(diskWriteLock) {
            synchronized(lock) {
                generation++
                accessRevision++
                accesses.clear()
            }
            keyFile.delete()
        }
    }

    /** Unknown legacy entries stay under Coil's own size limit; never delete its journal/data files. */
    suspend fun trimToSize(targetBytes: Long): Long {
        // Explicit maintenance must not await a deferred job while holding its
        // own admission lease; share the same bounded key initialization instead.
        loadKeys()
        val before = delegate.size
        val entries = BackgroundWorkCoordinator.finishBoundedBatch {
            withContext(com.example.performance.AppDispatchers.CpuBounded) {
                synchronized(lock) { accesses.toList() }.sortedBy { it.second }
            }
        }
        for ((index, entry) in entries.withIndex()) {
            if (index % 32 == 0) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                kotlinx.coroutines.yield()
            }
            val (key, lastAccess) = entry
            if (delegate.size <= targetBytes.coerceAtLeast(0L)) break
            // Covers accessed since this maintenance snapshot are no longer LRU.
            if (synchronized(lock) { accesses[key] != lastAccess }) continue
            removeEntry(key, reportUsage = false)
        }
        SmartCacheManager.cacheUsageChanged(imageBytes = delegate.size)
        return (before - delegate.size).coerceAtLeast(0L)
    }
}
