package com.example.connections

import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeMusicApiService
import org.json.JSONArray
import org.json.JSONObject

internal data class ConnectedYouTubePage(val tracks: List<MusicTrack>, val continuation: String?)

/** Read the selected collection, including its own pagination, rather than recommendations. */
internal fun connectedYouTubePage(root: JSONObject): ConnectedYouTubePage {
    fun find(value: Any?, name: String, depth: Int = 0): JSONObject? {
        if (depth > 64) return null
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) find(value.opt(i), name, depth + 1)?.let { return it }
            is JSONObject -> {
                if (value.has("musicCarouselShelfRenderer")) return null
                value.optJSONObject(name)?.let { return it }
                value.optJSONArray("tabs")?.let { tabs ->
                    val candidates = (0 until tabs.length()).mapNotNull { tabs.optJSONObject(it)?.optJSONObject("tabRenderer") }
                    val tab = candidates.firstOrNull { it.optBoolean("selected") } ?: candidates.firstOrNull()
                    find(tab?.opt("content"), name, depth + 1)?.let { return it }
                }
                value.keys().forEach { key ->
                    if (key != "tabs") find(value.opt(key), name, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }
    val continuation = root.optJSONObject("continuationContents")
    val shelf = continuation?.optJSONObject("musicPlaylistShelfContinuation")
        ?: continuation?.optJSONObject("musicShelfContinuation")
        ?: find(root.opt("contents"), "musicPlaylistShelfRenderer")
        ?: find(root.opt("contents"), "musicShelfRenderer")
        ?: listOf("onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints")
            .firstNotNullOfOrNull { find(root.opt(it), "appendContinuationItemsAction") }
        ?: return ConnectedYouTubePage(emptyList(), null)
    val items = shelf.optJSONArray("contents") ?: shelf.optJSONArray("continuationItems") ?: JSONArray()
    val tracks = (0 until items.length()).mapNotNull { index ->
        items.optJSONObject(index)?.let(YouTubeMusicApiService::parseAnyRenderer)
    }
    val next = shelf.optJSONArray("continuations")?.let { youtubeContinuation(JSONObject().put("continuations", it)) }
        ?: (0 until items.length()).firstNotNullOfOrNull { index ->
            items.optJSONObject(index)?.optJSONObject("continuationItemRenderer")?.let(::youtubeContinuation)
        }
    return ConnectedYouTubePage(tracks, next)
}
