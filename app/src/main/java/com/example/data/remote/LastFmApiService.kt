package com.example.data.remote

import android.util.LruCache
import android.content.Context
import android.os.SystemClock
import com.example.BuildConfig
import com.example.data.cache.LastFmCachedResponse
import com.example.data.cache.LastFmResponseCache
import com.example.data.repository.CatalogNames
import com.example.recommendation.DiscoveryRequestCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Last.fm supplies identities/order only; it is never an audio-stream provider. */
object LastFmApiService {
    data class Track(val title: String, val artist: String, val mbid: String = "", val durationMs: Long = 0L,
        val fetchedAt: Long = 0L, val expiresAt: Long = 0L) {
        val key: String get() = CatalogNames.normalize(artist) + "|" + CatalogNames.normalize(title)
    }
    data class TrackPage(val tracks: List<Track>, val fetchedAt: Long)
    data class ArtistPage(val names: List<String>, val expiresAt: Long)
    private data class Cached(val body: String, val fetchedAt: Long)
    private val cache = LruCache<String, Cached>(96)
    private val locks = Array(16) { Mutex() }
    private val slots = Semaphore(2)
    private val startGate = Mutex()
    private var nextStartAt = 0L
    private val retryAfter = AtomicLong(0L)
    @Volatile private var applicationContext: Context? = null
    const val CHART_TTL = 24 * 60 * 60_000L
    const val CONTEXT_TTL = 12 * 60 * 60_000L
    private val client by lazy { OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        // Every actual attempt must pass the coroutine rate gate, including retries.
        .retryOnConnectionFailure(false).followRedirects(false).build() }
    val isConfigured: Boolean get() = BuildConfig.LASTFM_API_KEY.isNotBlank()
    const val CONFIGURATION_ERROR = "Last.fm needs an API key. Configure LASTFM_API_KEY in the app build."

    fun initialize(context: Context) { applicationContext = context.applicationContext }

    private suspend fun readCache(keys: List<String>, ttl: Long): Cached? {
        val memory = keys.mapNotNull(cache::get).maxByOrNull { it.fetchedAt }
        if (memory != null && System.currentTimeMillis() - memory.fetchedAt in 0 until ttl) return memory
        val context = applicationContext ?: return memory
        return try {
            val disk = keys.mapNotNull { LastFmResponseCache.get(context).responses().get(it) }
                .maxByOrNull { it.fetchedAt }?.let { Cached(it.body, it.fetchedAt) }
            listOfNotNull(memory, disk).maxByOrNull { it.fetchedAt }
                ?.also { value -> keys.forEach { key -> cache.put(key, value) } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { memory }
    }

    private suspend fun storeCache(keys: List<String>, value: Cached) {
        keys.forEach { cache.put(it, value) }
        val context = applicationContext ?: return
        try {
            LastFmResponseCache.get(context).responses().put(keys.map { LastFmCachedResponse(it, value.body, value.fetchedAt) })
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { android.util.Log.w("LastFm", "Response cache unavailable; retaining memory cache") }
    }

    private suspend fun awaitStartSlot() = startGate.withLock {
        delay((nextStartAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        // Four starts/second, with headroom below Last.fm's five-request limit.
        nextStartAt = SystemClock.elapsedRealtime() + 250L
    }

    private suspend fun request(method: String, ttl: Long = CHART_TTL, recordingId: String? = null,
        vararg parameters: Pair<String, String>): Cached = withContext(Dispatchers.IO) {
        if (!isConfigured) throw IOException(CONFIGURATION_ERROR)
        // Canonical endpoint keys consolidate requests across shelves and case/whitespace variants.
        val key = method + parameters.sortedBy { it.first }.joinToString("&", prefix = "?") {
            "${it.first}=${android.net.Uri.encode(DiscoveryRequestCoordinator.normalize(it.second))}" }
        val keys = listOf(key) + listOfNotNull(recordingId?.takeIf { it.isNotBlank() }?.let { "recording:$it:$key" })
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val saved = readCache(keys, ttl)
            val now = System.currentTimeMillis()
            // Manual pulls vary/rerank the local pool; they never bypass a valid endpoint TTL.
            if (saved != null && now - saved.fetchedAt in 0 until ttl) return@withLock saved
            if (now < retryAfter.get()) return@withLock saved ?: throw IOException("Last.fm is busy. Please retry later.")
            try {
                val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
                    .addQueryParameter("method", method).addQueryParameter("api_key", BuildConfig.LASTFM_API_KEY)
                    .addQueryParameter("format", "json").apply {
                        parameters.forEach { (name, value) -> addQueryParameter(name, value) }
                    }.build().toString()
                val body = slots.withPermit { DiscoveryRequestCoordinator.network {
                    awaitStartSlot()
                    if (System.currentTimeMillis() < retryAfter.get()) throw IOException("Last.fm is busy. Please retry later.")
                    client.catalogGet(url)
                } }
                withContext(Dispatchers.Default) {
                    val root = JSONObject(body)
                    if (root.has("error")) {
                        val code = root.optInt("error")
                        if (code in setOf(10, 11, 16, 26, 29)) retryAfter.set(System.currentTimeMillis() + 60_000L)
                        throw IOException(when (code) {
                            10, 26 -> "Last.fm rejected the configured API key."
                            11, 16, 29 -> "Last.fm is temporarily busy. Please retry later."
                            else -> "Last.fm could not return this recommendation."
                        })
                    }
                    val resultName = when (method) {
                        "track.getSimilar" -> "similartracks"
                        "artist.getSimilar" -> "similarartists"
                        "artist.getTopTracks" -> "toptracks"
                        else -> "tracks"
                    }
                    if (root.optJSONObject(resultName) == null)
                        throw IOException("Last.fm returned an incomplete recommendation response.")
                }
                Cached(body, System.currentTimeMillis()).also { storeCache(keys, it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is CatalogHttpException && e.status == 429)
                    retryAfter.set(System.currentTimeMillis() + (e.retryAfter ?: 60L).coerceIn(30L, 3600L) * 1000L)
                saved ?: throw e
            }
        }
    }

    private suspend fun tracks(result: Cached, rootName: String, ttl: Long = CHART_TTL): TrackPage = withContext(Dispatchers.Default) {
        val root = JSONObject(result.body).optJSONObject(rootName) ?: throw IOException("Invalid Last.fm track response")
        val array = root.optJSONArray("track") ?: root.optJSONObject("track")?.let { JSONArray().put(it) } ?: JSONArray()
        val tracks = (0 until array.length()).mapNotNull { index ->
            val track = array.optJSONObject(index) ?: return@mapNotNull null
            val title = track.optString("name").trim()
            val artist = track.optJSONObject("artist")?.let { it.optString("name").ifBlank { it.optString("#text") } }
                ?: track.optString("artist")
            if (title.isBlank() || artist.isBlank() || title == "null" || artist == "null" ||
                track.has("match") && track.optDouble("match", 0.0) <= 0.0) return@mapNotNull null
            val seconds = track.optLong("duration", 0L).takeIf { it in 1..86_400L } ?: 0L
            Track(CatalogNames.clean(title), CatalogNames.clean(artist), track.optString("mbid"), seconds * 1000L,
                result.fetchedAt, result.fetchedAt + ttl)
        }.distinctBy { it.key }
        TrackPage(tracks, result.fetchedAt)
    }

    suspend fun countryTopTracks(country: String): TrackPage =
        tracks(request("geo.getTopTracks", parameters = arrayOf("country" to country,
            "limit" to "60", "page" to "1")), "tracks")

    suspend fun tagTopTracks(tag: String, page: Int = 1): List<Track> =
        tracks(request("tag.getTopTracks", parameters = arrayOf("tag" to tag,
            "limit" to "60", "page" to page.coerceIn(1, 3).toString())), "tracks").tracks

    suspend fun globalTopTracks(): List<Track> =
        tracks(request("chart.getTopTracks", parameters = arrayOf("limit" to "60", "page" to "1")), "tracks").tracks

    suspend fun similarTracks(artist: String, title: String, recordingId: String? = null): List<Track> =
        tracks(request("track.getSimilar", ttl = CONTEXT_TTL, recordingId = recordingId,
            parameters = arrayOf("artist" to artist, "track" to title,
            "autocorrect" to "1", "limit" to "50")), "similartracks", CONTEXT_TTL).tracks

    suspend fun artistTopTracks(artist: String): List<Track> =
        tracks(request("artist.getTopTracks", parameters = arrayOf("artist" to artist,
            "autocorrect" to "1", "limit" to "30")), "toptracks").tracks

    suspend fun similarArtists(artist: String): ArtistPage = withContext(Dispatchers.Default) {
        val result = request("artist.getSimilar", ttl = CONTEXT_TTL,
            parameters = arrayOf("artist" to artist, "autocorrect" to "1", "limit" to "8"))
        val root = JSONObject(result.body).optJSONObject("similarartists") ?: throw IOException("Invalid Last.fm artist response")
        val artists = root.optJSONArray("artist") ?: root.optJSONObject("artist")?.let { JSONArray().put(it) } ?: JSONArray()
        val names = (0 until artists.length()).mapNotNull { index -> artists.optJSONObject(index)?.takeIf {
            it.optDouble("match", 0.0) > 0.0
        }?.optString("name")?.takeIf { it.isNotBlank() }?.let(CatalogNames::clean) }.distinctBy(CatalogNames::normalize)
        ArtistPage(names, result.fetchedAt + CONTEXT_TTL)
    }
}
