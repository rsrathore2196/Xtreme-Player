package com.example.recommendation

import android.content.Context
import com.example.data.model.MusicTrack
import com.example.ui.ai.HomeShelf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight local snapshot of Home Screen recommendation shelves.
 * Enables Stale-While-Revalidate pattern: UI renders cached shelves instantly (<5ms)
 * while background tasks asynchronously recompute fresh recommendations.
 */
data class RecommendationSnapshot(
    val shelves: List<HomeShelf> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isPersonalized: Boolean = false
) {
    companion object {
        private const val PREFS_NAME = "xtreme_rec_snapshot_prefs"
        private const val KEY_SNAPSHOT_JSON = "key_snapshot_json"

        @Volatile
        private var memoryCachedSnapshot: RecommendationSnapshot? = null

        fun getCachedSnapshot(context: Context): RecommendationSnapshot? {
            memoryCachedSnapshot?.let { return it }
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val jsonStr = prefs.getString(KEY_SNAPSHOT_JSON, null) ?: return null
                val root = JSONObject(jsonStr)
                val timestamp = root.optLong("timestamp", 0L)
                val isPersonalized = root.optBoolean("isPersonalized", false)
                val shelvesArr = root.optJSONArray("shelves") ?: return null

                val shelves = mutableListOf<HomeShelf>()
                for (i in 0 until shelvesArr.length()) {
                    val shelfObj = shelvesArr.getJSONObject(i)
                    val id = shelfObj.optString("id", "")
                    val title = shelfObj.optString("title", "")
                    val subtitle = shelfObj.optString("subtitle", "")
                    val tracksArr = shelfObj.optJSONArray("tracks") ?: JSONArray()

                    val tracks = mutableListOf<MusicTrack>()
                    for (j in 0 until tracksArr.length()) {
                        val tObj = tracksArr.getJSONObject(j)
                        tracks.add(
                            MusicTrack(
                                id = tObj.optString("id", ""),
                                title = tObj.optString("title", ""),
                                artist = tObj.optString("artist", ""),
                                album = tObj.optString("album", ""),
                                durationMs = tObj.optLong("durationMs", 0L),
                                coverUrl = tObj.optString("coverUrl", ""),
                                audioUrl = tObj.optString("audioUrl", ""),
                                bitrateKbps = tObj.optInt("bitrateKbps", 320),
                                qualityBadge = tObj.optString("qualityBadge", ""),
                                genre = tObj.optString("genre", ""),
                                isLiked = tObj.optBoolean("isLiked", false),
                                singers = tObj.optString("singers", ""),
                                writer = tObj.optString("writer", ""),
                                language = tObj.optString("language", "Hindi"),
                                year = tObj.optString("year", ""),
                                source = tObj.optString("source", "HD Stream"),
                                isLossless = tObj.optBoolean("isLossless", false)
                            )
                        )
                    }
                    shelves.add(HomeShelf(id = id, title = title, subtitle = subtitle, tracks = tracks))
                }
                val snap = RecommendationSnapshot(timestamp = timestamp, isPersonalized = isPersonalized, shelves = shelves)
                memoryCachedSnapshot = snap
                snap
            } catch (_: Exception) {
                null
            }
        }

        fun saveSnapshot(context: Context, snapshot: RecommendationSnapshot) {
            memoryCachedSnapshot = snapshot
            try {
                val root = JSONObject().apply {
                    put("timestamp", snapshot.timestamp)
                    put("isPersonalized", snapshot.isPersonalized)
                    val shelvesArr = JSONArray()
                    for (shelf in snapshot.shelves) {
                        val shelfObj = JSONObject().apply {
                            put("id", shelf.id)
                            put("title", shelf.title)
                            put("subtitle", shelf.subtitle)
                            val tracksArr = JSONArray()
                            for (t in shelf.tracks) {
                                val tObj = JSONObject().apply {
                                    put("id", t.id)
                                    put("title", t.title)
                                    put("artist", t.artist)
                                    put("album", t.album)
                                    put("durationMs", t.durationMs)
                                    put("coverUrl", t.coverUrl)
                                    put("audioUrl", t.audioUrl)
                                    put("bitrateKbps", t.bitrateKbps)
                                    put("qualityBadge", t.qualityBadge)
                                    put("genre", t.genre)
                                    put("isLiked", t.isLiked)
                                    put("singers", t.singers)
                                    put("writer", t.writer)
                                    put("language", t.language)
                                    put("year", t.year)
                                    put("source", t.source)
                                    put("isLossless", t.isLossless)
                                }
                                tracksArr.put(tObj)
                            }
                            put("tracks", tracksArr)
                        }
                        shelvesArr.put(shelfObj)
                    }
                    put("shelves", shelvesArr)
                }

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_SNAPSHOT_JSON, root.toString()).apply()
            } catch (_: Exception) {}
        }
    }
}
