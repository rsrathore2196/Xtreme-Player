package com.example.recommendation

import android.content.Context
import android.util.AtomicFile
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Derived cache only: source history/favorites remain authoritative. No synchronous disk API. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
object UserTasteProfileStore {
    private const val FILE_NAME = "user_taste_profile.json"
    private const val VERSION = 2
    private const val MAX_BYTES = 2 * 1024 * 1024
    internal data class Snapshot(val contextKey: String, val fingerprint: String,
        val completeHistory: Boolean, val profile: UserTasteProfile) {
        fun matches(key: String, signature: String, nowMs: Long = System.currentTimeMillis()): Boolean =
            contextKey == key && fingerprint == signature && nowMs - profile.lastUpdatedMs in 0..3_600_000L
    }
    private data class Write(val directory: File, val snapshot: Snapshot)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedProfile = AtomicReference<Snapshot?>(null)
    private val fileMutex = Mutex()
    private val writes = MutableStateFlow<Write?>(null)

    init {
        scope.launch {
            writes.filterNotNull().debounce(2_000L).collectLatest { write ->
              try {
                BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.MAINTENANCE,
                    requireForeground = false) {
                    if (writes.value != write) return@startup
                    val bytes = withContext(BackgroundWorkCoordinator.optionalCpuDispatcher) { encode(write.snapshot) }
                    fileMutex.withLock {
                        if (writes.value == write) writeAtomically(write.directory, bytes)
                    }
                }
              } catch (e: CancellationException) { throw e }
              catch (e: Exception) { android.util.Log.w("UserTasteProfileStore", "Taste persistence unavailable", e) }
            }
        }
    }

    internal fun peekSnapshot(contextKey: String): Snapshot? = cachedProfile.get()?.takeIf { it.contextKey == contextKey }
    fun peek(contextKey: String): UserTasteProfile? = peekSnapshot(contextKey)?.profile

    internal suspend fun loadAsync(context: Context, contextKey: String): Snapshot? = withContext(Dispatchers.IO) {
        peekSnapshot(contextKey)?.let { return@withContext it }
        fileMutex.withLock {
            peekSnapshot(contextKey)?.let { return@withLock it }
            val before = cachedProfile.get()
            val loaded = try {
                val file = AtomicFile(File(context.filesDir, FILE_NAME))
                if (!file.baseFile.exists() && !File(context.filesDir, "$FILE_NAME.bak").exists()) return@withLock null
                val bytes = file.openRead().use { input ->
                    val content = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (content.size() + count > MAX_BYTES) return@withLock null
                        content.write(buffer, 0, count)
                    }
                    content.toByteArray()
                }
                withContext(BackgroundWorkCoordinator.optionalCpuDispatcher) { decode(bytes.toString(Charsets.UTF_8)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("UserTasteProfileStore", "Cached taste unavailable", e)
                null
            }
            if (loaded != null) cachedProfile.compareAndSet(before, loaded)
            (peekSnapshot(contextKey) ?: loaded?.takeIf { it.contextKey == contextKey })
        }
    }

    internal fun save(context: Context, snapshot: Snapshot) {
        cachedProfile.set(snapshot)
        // Only callers already doing IO obtain the directory; no ViewModel constructor reads it.
        writes.value = Write(context.filesDir, snapshot)
    }

    private fun writeAtomically(directory: File, bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) return
        val target = AtomicFile(File(directory, FILE_NAME))
        var output: java.io.FileOutputStream? = null
        try {
            output = target.startWrite()
            output.write(bytes)
            target.finishWrite(output)
        } catch (e: Exception) {
            target.failWrite(output)
            android.util.Log.w("UserTasteProfileStore", "Could not persist derived taste", e)
        }
    }

    internal fun encode(snapshot: Snapshot): ByteArray = serialize(snapshot.profile)
        .put("contextKey", snapshot.contextKey).put("fingerprint", snapshot.fingerprint)
        .put("completeHistory", snapshot.completeHistory).toString().toByteArray(Charsets.UTF_8)

    internal fun decode(text: String): Snapshot? = runCatching {
        val root = JSONObject(text)
        if (root.optInt("version") != VERSION || root.optString("contextKey").isBlank() ||
            root.optString("fingerprint").isBlank()) return null
        Snapshot(root.getString("contextKey"), root.getString("fingerprint"),
            root.optBoolean("completeHistory"), deserialize(root) ?: return null)
    }.getOrNull()

    /** Includes changed rows, not just a last event ID: milestones update existing rows. */
    internal suspend fun fingerprint(events: List<com.example.data.local.ListeningEventEntity>,
        tracks: List<com.example.data.model.MusicTrack>, favorites: List<com.example.data.model.MusicTrack>,
        contextKey: String, checkpoint: suspend () -> Unit = { kotlinx.coroutines.yield() }): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        add(contextKey)
        events.forEachIndexed { index, event ->
            if (index % 64 == 0) checkpoint()
            add(event.toString())
        }
        for (list in listOf(tracks, favorites)) {
            add("metadata")
            list.sortedBy { it.id }.forEachIndexed { index, track ->
                if (index % 64 == 0) checkpoint()
                add(listOf(track.id, track.title, track.artist, track.singers, track.album,
                    track.isrc, track.durationMs, track.genre, track.language).toString())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun serialize(profile: UserTasteProfile): JSONObject {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("lastUpdatedMs", profile.lastUpdatedMs)
        root.put("meaningfulPlaysCount", profile.meaningfulPlaysCount)
        root.put("isColdStart", profile.isColdStart)

        fun mapToJson(map: Map<String, Float>): JSONObject {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v.toDouble()) }
            return obj
        }

        fun intMapToJson(map: Map<String, Int>): JSONObject {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            return obj
        }

        fun stringMapToJson(map: Map<String, String>): JSONObject {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            return obj
        }

        root.put("artistAffinities", mapToJson(profile.artistAffinities))
        root.put("genreAffinities", mapToJson(profile.genreAffinities))
        root.put("languageAffinities", mapToJson(profile.languageAffinities))
        root.put("moodAffinities", mapToJson(profile.moodAffinities))
        root.put("playCounts", intMapToJson(profile.playCounts))
        root.put("skipCounts", intMapToJson(profile.skipCounts))
        root.put("recordingAliases", stringMapToJson(profile.recordingAliases))

        root.put("topRecentTrackIds", JSONArray(profile.topRecentTrackIds))
        root.put("recentRecordings", JSONArray(profile.recentRecordings))
        root.put("favoriteRecordings", JSONArray(profile.favoriteRecordings))

        return root
    }

    private fun deserialize(root: JSONObject): UserTasteProfile? {
        return try {

            fun jsonToFloatMap(obj: JSONObject?): Map<String, Float> {
                if (obj == null) return emptyMap()
                val map = mutableMapOf<String, Float>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.getDouble(key).toFloat()
                    require(value.isFinite())
                    map[key] = value
                }
                return map
            }

            fun jsonToIntMap(obj: JSONObject?): Map<String, Int> {
                if (obj == null) return emptyMap()
                val map = mutableMapOf<String, Int>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.optInt(key)
                }
                return map
            }

            fun jsonToStringMap(obj: JSONObject?): Map<String, String> {
                if (obj == null) return emptyMap()
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.optString(key)
                }
                return map
            }

            fun jsonToStringList(arr: JSONArray?): List<String> {
                if (arr == null) return emptyList()
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    list.add(arr.optString(i))
                }
                return list
            }

            fun jsonToStringSet(arr: JSONArray?): Set<String> {
                if (arr == null) return emptySet()
                val set = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    set.add(arr.optString(i))
                }
                return set
            }

            UserTasteProfile(
                artistAffinities = jsonToFloatMap(root.optJSONObject("artistAffinities")),
                genreAffinities = jsonToFloatMap(root.optJSONObject("genreAffinities")),
                languageAffinities = jsonToFloatMap(root.optJSONObject("languageAffinities")),
                moodAffinities = jsonToFloatMap(root.optJSONObject("moodAffinities")),
                topRecentTrackIds = jsonToStringList(root.optJSONArray("topRecentTrackIds")),
                meaningfulPlaysCount = root.optInt("meaningfulPlaysCount", 0),
                isColdStart = root.optBoolean("isColdStart", true),
                lastUpdatedMs = root.optLong("lastUpdatedMs", 0L),
                playCounts = jsonToIntMap(root.optJSONObject("playCounts")),
                skipCounts = jsonToIntMap(root.optJSONObject("skipCounts")),
                recentRecordings = jsonToStringSet(root.optJSONArray("recentRecordings")),
                favoriteRecordings = jsonToStringSet(root.optJSONArray("favoriteRecordings")),
                recordingAliases = jsonToStringMap(root.optJSONObject("recordingAliases"))
            )
        } catch (e: Exception) {
            android.util.Log.w("UserTasteProfileStore", "Deserialization error", e)
            null
        }
    }
}
