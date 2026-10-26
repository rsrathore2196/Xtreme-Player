package com.example.recommendation

import com.example.performance.AppDispatchers

import com.example.data.cache.RecommendationCache
import com.example.data.cache.RecommendationTrackCodec
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeMusicApiService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Native InnerTubeX WEB_REMIX metadata only. No player/extraction request is made here. */
internal class YouTubeRecommendationDiscovery(private val cache: RecommendationCache?) {
    suspend fun tracks(operation: String, value: String, country: String, force: Boolean,
        essential: Boolean, ttl: Long): List<MusicTrack> {
        val key = "discovery-v1:$country:$operation:${DiscoveryRequestCoordinator.normalize(value)}"
        val cached = cache?.get(key)
        val now = System.currentTimeMillis()
        if (cached != null && now < cached.expiresAt && (!force || now - cached.fetchedAt < 30_000L))
            return decode(cached.body)
        if (!force && cached != null && operation == "related" &&
            now - cached.expiresAt in 0L..ttl) {
            val retained = decode(cached.body)
            if (retained.isNotEmpty()) {
                DiscoveryRequestCoordinator.revalidateWhenIdle(key) {
                    tracks(operation, value, country, force = true, essential = false, ttl = ttl)
                }
                return retained
            }
        }
        return DiscoveryRequestCoordinator.shared(key, essential, network = false) {
            val latest = cache?.get(key)
            val time = System.currentTimeMillis()
            if (latest != null && time < latest.expiresAt && (!force || time - latest.fetchedAt < 30_000L))
                return@shared decode(latest.body)
            try {
                // Leave time for the established metadata endpoint when an anonymous
                // InnerTubeX session times out or returns an empty renderer variant.
                val root = withTimeoutOrNull(if (operation == "search") 3_500L else 5_000L) {
                    try { response(operation, value, country, essential) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        android.util.Log.w("HomeDiscovery", "Native metadata unavailable: ${e.javaClass.simpleName}")
                        JSONObject()
                    }
                } ?: JSONObject()
                var found = withContext(AppDispatchers.CpuBounded) { if (operation == "charts") emptyList() else parse(root) }
                // The related tab is returned by next as a browse endpoint on some
                // anonymous clients. Follow the real endpoint, never invent a radio ID.
                if (operation == "related" && found.size < 12) {
                    val related = withContext(AppDispatchers.CpuBounded) { endpoints(root, "related").firstOrNull() }
                    if (related != null) found = found + response("browse", related, country, essential).let { withContext(AppDispatchers.CpuBounded) { parse(it) } }
                }
                if (operation == "charts") {
                    val playlist = withContext(AppDispatchers.CpuBounded) { regionalChart(root, country) }
                    if (playlist != null) found = response("browse", playlist, country, essential)
                        .let { withContext(AppDispatchers.CpuBounded) { parse(it) } }
                }
                if (operation == "search" && found.isEmpty()) {
                    found = withTimeoutOrNull(3_500L) { DiscoveryRequestCoordinator.network(essential) {
                        YouTubeMusicApiService.searchSongsPage(value, useInnerTubeX = false,
                            forceRefresh = force, countryCode = country).tracks
                    } }.orEmpty()
                }
                val result = withContext(AppDispatchers.CpuBounded) {
                    HomeRecommendationPolicy.originals(found).filter { it.id.startsWith("yt_") &&
                        HomeRecommendationPolicy.known(it.artist) }.take(48)
                }
                if (result.isNotEmpty()) cache?.put(key, result, ttl)
                result.ifEmpty { latest?.let { decode(it.body) }.orEmpty() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("HomeDiscovery", "Keeping cached discovery after ${e.javaClass.simpleName}")
                latest?.let { decode(it.body) }.orEmpty()
            }
        }
    }
    private suspend fun decode(body: String): List<MusicTrack> = withContext(AppDispatchers.CpuBounded) {
        RecommendationTrackCodec.decode(body)
    }
    private suspend fun response(operation: String, value: String, country: String, essential: Boolean): JSONObject =
        DiscoveryRequestCoordinator.network(essential) {
            val body = YouTubeAudioStreamService.discoveryResponse(when (operation) { "charts" -> "browse"; else -> operation }, value, country)
            withContext(AppDispatchers.CpuBounded) { JSONObject(body) }
        }
    private fun parse(root: JSONObject): List<MusicTrack> {
        val found = linkedMapOf<String, MusicTrack>()
        walk(root) { obj ->
            YouTubeMusicApiService.parseAnyRenderer(obj)?.let { track ->
                // Selected discovery identity is retained for mapping, not streaming.
                found.putIfAbsent(track.id, track.copy(audioUrl = "", bitrateKbps = 0))
            }
        }
        return found.values.toList()
    }
    private fun regionalChart(root: JSONObject, countryCode: String): String? {
        if (!countryCode.matches(Regex("[A-Z]{2}"))) return null
        val country = java.util.Locale("", countryCode).getDisplayCountry(java.util.Locale.ENGLISH).lowercase()
        var result: String? = null
        walk(root) { node ->
            val card = node.optJSONObject("musicTwoRowItemRenderer") ?: return@walk
            val label = (card.optJSONObject("title")?.toString().orEmpty() + card.optJSONObject("subtitle")?.toString().orEmpty()).lowercase()
            // Never assume the globally popular list is the selected country's chart.
            if (result == null && country.isNotBlank() && country in label && "top" in label && "song" in label && "video" !in label) {
                val endpoint = card.optJSONObject("navigationEndpoint")
                val browse = endpoint?.optJSONObject("browseEndpoint")?.optString("browseId")
                val playlist = endpoint?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")
                result = browse?.takeIf { it.startsWith("VL") } ?: playlist?.takeIf(String::isNotBlank)?.let { "VL$it" }
            }
        }
        return result
    }
    private fun endpoints(root: JSONObject, name: String): List<String> = buildList {
        walk(root) { node -> node.optJSONObject("tabRenderer")?.let { tab ->
            if (tab.optString("title").contains(name, true)) {
                walk(tab) { nested -> nested.optJSONObject("browseEndpoint")?.optString("browseId")
                    ?.takeIf(String::isNotBlank)?.let(::add) }
            }
        } }
    }.distinct()
    private fun walk(value: Any?, depth: Int = 0, visit: (JSONObject) -> Unit) {
        if (depth > 48) return
        when (value) {
            is JSONObject -> { visit(value); value.keys().forEach { walk(value.opt(it), depth + 1, visit) } }
            is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), depth + 1, visit)
        }
    }
}
