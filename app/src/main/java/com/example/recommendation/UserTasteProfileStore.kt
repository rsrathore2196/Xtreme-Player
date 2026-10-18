package com.example.recommendation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Persists and loads the computed UserTasteProfile to disk on Dispatchers.IO.
 * Ensures the app immediately restores the user's taste profile on resume/reopen
 * without recomputing 5,000 historical listening events on the main/worker threads.
 */
object UserTasteProfileStore {
    private const val FILE_NAME = "user_taste_profile.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedProfile = AtomicReference<UserTasteProfile?>(null)

    fun peek(): UserTasteProfile? = cachedProfile.get()

    /**
     * Synchronous best-effort read for instant startup/reopen initialization.
     * Checks in-memory cache first, then reads persisted JSON.
     */
    fun getOrLoad(context: Context): UserTasteProfile? {
        cachedProfile.get()?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val jsonStr = file.readText()
            deserialize(jsonStr)?.also { cachedProfile.set(it) }
        } catch (e: Exception) {
            android.util.Log.w("UserTasteProfileStore", "Failed to load cached taste profile", e)
            null
        }
    }

    suspend fun loadAsync(context: Context): UserTasteProfile? = withContext(Dispatchers.IO) {
        getOrLoad(context)
    }

    /**
     * Asynchronously serializes and saves the taste profile to disk on Dispatchers.IO.
     * Uses atomic file replacement to prevent corruption.
     */
    fun save(context: Context, profile: UserTasteProfile) {
        cachedProfile.set(profile)
        val appContext = context.applicationContext
        scope.launch {
            try {
                val json = serialize(profile)
                val targetFile = File(appContext.filesDir, FILE_NAME)
                val tempFile = File(appContext.filesDir, "$FILE_NAME.tmp")
                tempFile.writeText(json.toString())
                if (tempFile.exists()) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                }
            } catch (e: Exception) {
                android.util.Log.w("UserTasteProfileStore", "Failed to persist taste profile", e)
            }
        }
    }

    private fun serialize(profile: UserTasteProfile): JSONObject {
        val root = JSONObject()
        root.put("version", 1)
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

    private fun deserialize(jsonStr: String): UserTasteProfile? {
        return try {
            val root = JSONObject(jsonStr)
            if (root.optInt("version") != 1) return null

            fun jsonToFloatMap(obj: JSONObject?): Map<String, Float> {
                if (obj == null) return emptyMap()
                val map = mutableMapOf<String, Float>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.optDouble(key).toFloat()
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
