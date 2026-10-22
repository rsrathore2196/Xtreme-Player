package com.example.data.local

import com.example.performance.AppDispatchers

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** IO-backed adapter for the existing preference files/keys (including backup compatibility).
 * UI reads use immutable memory snapshots. Edits publish immediately and persist in order on
 * a single IO consumer, using commit there instead of Android's lifecycle-blocking apply queue.
 * Explicit transactional commits remain synchronous ONLY on background callers. */
class ReactiveSettingsRepository(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    private val stores = ConcurrentHashMap<String, MemoryPreferences>()
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)
    init { scope.launch { for (write in writes) write() } }

    fun preferences(name: String): SharedPreferences = store(name).also {
        // Existing background helpers can keep their synchronous interface safely.
        if (Looper.myLooper() != Looper.getMainLooper()) it.load()
    }
    fun observe(name: String): StateFlow<Map<String, Any?>> = store(name).values

    /** Drain already queued external-restore notifications before taking a coherent snapshot. */
    suspend fun awaitPendingWrites() {
        val drained = CompletableDeferred<Unit>()
        writes.send { drained.complete(Unit) }
        drained.await()
    }
    internal suspend fun refreshExternalEdits() = withContext(AppDispatchers.Io) {
        stores.values.forEach { it.refreshFromDisk() }
    }

    suspend fun preload() = withContext(AppDispatchers.Io) {
        BackgroundWorkCoordinator.traceWork("Preferences preload") {
            startupFiles.forEach { store(it).load() }
        }
    }
    private fun store(name: String) = stores.computeIfAbsent(name) { MemoryPreferences(it) }

    override fun close() {
        stores.values.forEach { it.close() }
        writes.close()
        scope.cancel()
    }

    private inner class MemoryPreferences(private val name: String) : SharedPreferences {
        private val lock = Any()
        private val loadLock = Any()
        private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
        val values = MutableStateFlow<Map<String, Any?>>(emptyMap())
        private val pending = mutableMapOf<String, Long>()
        private var clearedAt = 0L
        private var revision = 0L
        @Volatile private var disk: SharedPreferences? = null
        fun close() { disk?.unregisterOnSharedPreferenceChangeListener(diskListener) }
        private val diskListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Even external backup/restore edits are copied on IO, never read by a Main listener.
            writes.trySend { refreshFromDisk(key) }
        }
        fun refreshFromDisk(key: String? = null) {
            val source = disk ?: return
            val fresh = source.all
            val changed = synchronized(lock) {
                val old = values.value
                val next = fresh.toMutableMap()
                if (clearedAt > 0L) next.clear()
                pending.keys.forEach { field ->
                    if (old.containsKey(field)) next[field] = old[field] else next.remove(field)
                }
                values.value = next.toMap()
                if (key == null) (old.keys + next.keys).filter { old[it] != next[it] }
                else listOfNotNull(key.takeIf { old[it] != next[it] })
            }
            notifyChanges(changed)
        }
        fun load() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Preference files must be opened on IO" }
            if (disk != null) return
            synchronized(loadLock) {
                if (disk != null) return
                val source = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                source.registerOnSharedPreferenceChangeListener(diskListener)
                val initial = source.all.toMutableMap()
                synchronized(lock) {
                    if (clearedAt > 0L) initial.clear()
                    pending.keys.forEach { key ->
                        if (values.value.containsKey(key)) initial[key] = values.value[key] else initial.remove(key)
                    }
                    values.value = initial.toMap()
                    disk = source
                }
            }
        }
        override fun getAll(): MutableMap<String, *> = values.value.toMutableMap()
        override fun contains(key: String?): Boolean = values.value.containsKey(key)
        override fun getString(key: String?, defValue: String?): String? = values.value[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values.value[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues?.toMutableSet()
        override fun getInt(key: String?, defValue: Int): Int = values.value[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values.value[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values.value[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values.value[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listener?.let(listeners::add)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listeners.remove(listener)
        }
        private fun notifyChanges(keys: List<String>) {
            if (keys.isEmpty() || listeners.isEmpty()) return
            val action = Runnable { keys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this, key) } } }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
        }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clear = false
            private fun put(key: String?, value: Any?) = apply { if (key != null) updates[key] = value }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear() = apply { clear = true }
            override fun apply() { submit() }
            override fun commit(): Boolean {
                check(Looper.myLooper() != Looper.getMainLooper()) { "Use asynchronous settings edits on Main" }
                return submit(durable = true).get()
            }
            private fun submit(durable: Boolean = false): CompletableFuture<Boolean> {
                val result = CompletableFuture<Boolean>()
                val edits = updates.toMap()
                val clearAll = clear
                val changed: List<String>
                synchronized(lock) {
                    val version = ++revision
                    val old = values.value
                    val next = if (clearAll) mutableMapOf() else old.toMutableMap()
                    if (clearAll) clearedAt = version
                    edits.forEach { (key, value) ->
                        if (value == null) next.remove(key) else next[key] = value
                        pending[key] = version
                    }
                    changed = (old.keys + next.keys).filter { old[it] != next[it] }
                    values.value = next.toMap()
                    // Queue order and optimistic update order are the same, including explicit commits.
                    writes.trySend {
                        try {
                            load()
                            // Rapid slider edits can supersede queued apply writes before
                            // they reach disk. A transactional commit is always a real barrier.
                            val effective = synchronized(lock) {
                                if (durable) edits else edits.filterKeys {
                                    clearedAt <= version && (pending[it] ?: version) <= version
                                }
                            }
                            val effectiveClear = clearAll && synchronized(lock) { durable || clearedAt <= version }
                            if (!durable && effective.isEmpty() && !effectiveClear) {
                                result.complete(true)
                                return@trySend
                            }
                            val editor = checkNotNull(disk).edit()
                            if (effectiveClear) editor.clear()
                            effective.forEach { (key, value) -> when (value) {
                                null -> editor.remove(key)
                                is String -> editor.putString(key, value)
                                is Int -> editor.putInt(key, value)
                                is Long -> editor.putLong(key, value)
                                is Boolean -> editor.putBoolean(key, value)
                                is Float -> editor.putFloat(key, value)
                                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                            } }
                            val success = editor.commit()
                            if (success) synchronized(lock) {
                                effective.keys.forEach { if (pending[it] == version) pending.remove(it) }
                                if (clearedAt == version) clearedAt = 0L
                            }
                            if (!success) android.util.Log.w("Settings", "Could not persist $name")
                            result.complete(success)
                        } catch (error: Exception) {
                            android.util.Log.w("Settings", "Could not persist $name", error)
                            result.completeExceptionally(error)
                        }
                    }
                }
                notifyChanges(changed)
                return result
            }
        }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val startupFiles = listOf("xtreme_theme_prefs", "xtreme_user_profile_prefs",
            "xtreme_other_settings", "xtreme_playback_sources_prefs", "xtreme_audio_quality_prefs", "playback_sleep_timer",
            "xtreme_backup_preferences", "xtreme_smart_cache_preferences")

        fun preferences(context: Context, name: String): SharedPreferences =
            (context.applicationContext as? com.example.XtremeMusicApp)?.settingsRepository?.preferences(name)
                ?: context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}
