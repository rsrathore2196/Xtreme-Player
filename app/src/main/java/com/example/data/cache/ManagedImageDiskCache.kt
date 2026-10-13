package com.example.data.cache

import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/** Retains Coil's cache ownership while exposing LRU maintenance through its public API. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class ManagedImageDiskCache(private val delegate: DiskCache, private val keyFile: File) : DiskCache by delegate {
    private val lock = Any()
    private val diskWriteLock = Any()
    private val accesses = LinkedHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded = false
    private var saveScheduled = false
    private var generation = 0

    private fun loadKeys() {
        if (loaded) return
        loaded = true
        runCatching {
            if (keyFile.length() in 1..1_000_000) {
                val entries = JSONArray(keyFile.readText())
                for (index in 0 until minOf(entries.length(), 2000)) {
                    val row = entries.optJSONArray(index) ?: continue
                    val key = row.optString(0)
                    if (key.isNotBlank()) accesses[key] = row.optLong(1)
                }
            }
        }
    }

    private fun accessed(key: String) = synchronized(lock) {
        loadKeys()
        accesses.remove(key)
        accesses[key] = System.currentTimeMillis()
        while (accesses.size > 2000) accesses.remove(accesses.keys.first())
        if (!saveScheduled) {
            saveScheduled = true
            scope.launch {
                delay(2_000)
                val (version, snapshot) = synchronized(lock) { generation to accesses.toList() }
                synchronized(diskWriteLock) {
                    runCatching {
                        val entries = JSONArray()
                        snapshot.forEach { (entry, time) -> entries.put(JSONArray().put(entry).put(time)) }
                        if (synchronized(lock) { version == generation }) {
                            val temp = File(keyFile.parentFile, "${keyFile.name}.tmp")
                            temp.writeText(entries.toString())
                            if (!temp.renameTo(keyFile)) temp.delete()
                        }
                    }
                }
                synchronized(lock) { saveScheduled = false }
            }
        }
    }

    override fun openSnapshot(key: String): DiskCache.Snapshot? = delegate.openSnapshot(key)?.also { accessed(key) }
    override fun openEditor(key: String): DiskCache.Editor? = delegate.openEditor(key)?.also { accessed(key) }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun get(key: String): DiskCache.Snapshot? = openSnapshot(key)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun edit(key: String): DiskCache.Editor? = openEditor(key)

    override fun clear() {
        delegate.clear()
        synchronized(diskWriteLock) {
            synchronized(lock) {
                loadKeys()
                generation++
                accesses.clear()
            }
            keyFile.delete()
        }
    }

    /** Unknown legacy entries stay under Coil's own size limit; never delete its journal/data files. */
    fun trimToSize(targetBytes: Long): Long {
        val before = delegate.size
        val keys = synchronized(lock) { loadKeys(); accesses.entries.sortedBy { it.value }.map { it.key } }
        for (key in keys) {
            if (delegate.size <= targetBytes.coerceAtLeast(0L)) break
            if (delegate.remove(key)) synchronized(lock) { accesses.remove(key) }
        }
        return (before - delegate.size).coerceAtLeast(0L)
    }
}
