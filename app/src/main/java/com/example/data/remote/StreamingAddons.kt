/* HTTP Addon protocol client; GPL-3.0. */
package com.example.data.remote

import android.content.Context
import android.net.Uri
import com.example.data.local.OtherSettingsPreferences
import com.example.data.model.MusicTrack
import com.example.data.repository.RecordingIdentity
import com.example.performance.AppDispatchers
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSourcePolicy
import com.example.playback.PlaybackSource
import com.example.playback.PlaybackSourceKind
import com.example.playback.PlaybackDiagnostics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-installed HTTP provider. No requests, client construction or services while disabled.
 * Uses standard manifest/search/stream protocol, not an arbitrary audio proxy.
 * Requests are bounded/cancellable and every catalog substitution proves recording identity.
 */
object StreamingAddons {
    data class Manifest(val name: String, val baseUrl: String)
    private data class Transport(val headers: Map<String, String>, val mime: String?, val bitrate: Int,
        val expires: Long, val headerScope: String?, val sourceId: String, val baseUrl: String, val provider: String)
    private val transports = ConcurrentHashMap<String, Transport>()
    private data class Cached(val track: MusicTrack?, val expires: Long)
    private val resolutions = ConcurrentHashMap<String, Cached>()
    private data class Configuration(val manifest: Manifest, val defaults: Map<String, String>,
        val qualities: List<String>, val stereoValue: String, val expires: Long)
    private val configurations = ConcurrentHashMap<String, Configuration>()
    private val manifestFlights = Array(8) { kotlinx.coroutines.sync.Mutex() }
    private val permits = Semaphore(2)
    private val client by lazy { OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 }).build() }

    fun normalize(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTP or HTTPS server URL")
        require(url.username.isEmpty() && url.password.isEmpty()) { "Use your add-on's token URL, without username/password" }
        val trimmed = url.encodedPath.trimEnd('/')
        val path = if (trimmed.substringAfterLast('/').endsWith(".json", true)) trimmed.substringBeforeLast('/') else trimmed
        return url.newBuilder().encodedPath(path.ifBlank { "/" }).fragment(null).build().toString().trimEnd('/')
    }
    private fun endpoint(base: String, path: String, query: String? = null, quality: String? = null): HttpUrl {
        val builder = requireNotNull(base.toHttpUrlOrNull()).newBuilder()
        path.split('/').forEach(builder::addPathSegment)
        if (query != null) builder.setQueryParameter("q", query)
        if (quality != null) builder.setQueryParameter("quality", quality).setQueryParameter("atmos", "none")
        return builder.build()
    }
    private suspend fun get(url: HttpUrl): String = permits.withPermit {
        val call = client.newCall(Request.Builder().url(url).header("X-LW-Intent", "stream")
            .header("User-Agent", "XtremePlayer/${com.example.BuildConfig.VERSION_NAME}").build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Add-on connection failed", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("Add-on returned HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Empty add-on response")
                            // Bounded even when a server omits Content-Length.
                            val source = body.source()
                            source.request(262_145L)
                            if (source.buffer.size > 262_144L) throw IOException("Add-on response is too large")
                            val result = source.readUtf8()
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    suspend fun connect(raw: String): Manifest = configuration(normalize(raw)).manifest

    private suspend fun configuration(base: String): Configuration = withContext(AppDispatchers.Io) {
        configurations[base]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withContext it }
        val lock = manifestFlights[(base.hashCode() and Int.MAX_VALUE) % manifestFlights.size]
        lock.lock()
        try {
            configurations[base]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withContext it }
            val body = get(endpoint(base, "manifest.json"))
            withContext(AppDispatchers.CpuBounded) {
                val root = JSONObject(body)
                require(root.optString("name").isNotBlank() || root.optString("id").isNotBlank()) {
                    "This server did not return a valid add-on manifest"
                }
                val resources = root.optJSONArray("resources")
                require(resources == null || resources.length() == 0 || (0 until resources.length()).any {
                    resources.optString(it).lowercase() in setOf("search", "stream")
                }) { "This manifest does not provide music search or streaming" }
                val defaults = linkedMapOf<String, String>()
                val qualities = mutableListOf<String>()
                var stereoValue = "none"
                val settings = root.optJSONArray("settings") ?: JSONArray()
                fun scalar(value: Any?): String? = value?.takeIf { it is String || it is Number || it is Boolean }
                    ?.toString()?.takeIf { it.length <= 1024 }
                for (index in 0 until minOf(settings.length(), 32)) {
                    val setting = settings.optJSONObject(index) ?: continue
                    val key = setting.optString("key").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: continue
                    scalar(setting.opt("default"))?.let { defaults[key] = it }
                    if (key == "quality" || key == "atmos") {
                        val options = setting.optJSONArray("options") ?: JSONArray()
                        val values = (0 until minOf(options.length(), 32)).mapNotNull { i ->
                            scalar(options.optJSONObject(i)?.opt("value") ?: options.opt(i))
                        }
                        if (key == "quality") qualities.addAll(values)
                        else stereoValue = (values + listOfNotNull(defaults[key])).firstOrNull {
                            it.lowercase(Locale.ROOT) in setOf("0", "false", "off", "no", "none", "never", "stereo")
                        } ?: "none"
                    }
                }
                Configuration(Manifest(root.optString("name").ifBlank { root.optString("id") }.trim().take(100), base),
                    defaults.toMap(), qualities.distinct(), stereoValue, System.currentTimeMillis() + 600_000L).also {
                    if (configurations.size >= 32) configurations.entries.minByOrNull { it.value.expires }?.let { configurations.remove(it.key) }
                    configurations[base] = it
                }
            }
        } finally { lock.unlock() }
    }
    private suspend fun configuredEndpoint(base: String, path: String, query: String? = null, tier: String): HttpUrl {
        val config = try { withTimeoutOrNull(2_500L) { configuration(base) } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val builder = endpoint(base, path, query).newBuilder()
        config?.defaults?.forEach { (key, value) -> builder.setQueryParameter(key, value) }
        val options = config?.qualities.orEmpty()
        val requested = options.firstOrNull { it.equals(tier, true) } ?: run {
            val hints = when (tier.lowercase(Locale.ROOT)) {
                "hi_res", "hires" -> listOf("hi_res", "hi-res", "hires", "max", "best", "lossless", "flac", "hifi")
                "lossless" -> listOf("lossless", "flac", "hifi", "hi_res", "hi-res", "hires", "max", "best")
                else -> listOf("high", "320", "normal", "standard")
            }
            hints.firstNotNullOfOrNull { hint -> options.firstOrNull { it.lowercase(Locale.ROOT).replace(' ', '_').contains(hint) } }
        } ?: tier
        builder.setQueryParameter("quality", requested)
        // The selected recording is stereo; never let a server's default change its mix.
        builder.setQueryParameter("atmos", config?.stereoValue ?: "none")
        if (query != null) builder.setQueryParameter("q", query)
        return builder.build()
    }
    private fun quality(ceiling: AudioQualityCeiling) = when (ceiling) {
        AudioQualityCeiling.MAX -> "LOSSLESS"
        AudioQualityCeiling.HIGH -> "high"
        AudioQualityCeiling.NORMAL, AudioQualityCeiling.DATA_SAVER -> "low"
    }
    private fun fingerprint(base: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(base.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    internal fun catalogTrackId(base: String, id: String) = "addon_${fingerprint(base)}:" +
        android.util.Base64.encodeToString(id.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    fun ownsTrack(source: PlaybackSource, id: String): Boolean =
        source.kind == PlaybackSourceKind.ADDON && id.startsWith("addon_${fingerprint(source.baseUrl)}:")
    fun fromSource(url: String, sourceId: String): Boolean = transports[url]?.sourceId == sourceId
    internal fun registeredSourceId(url: String): String? = transports[url]?.sourceId
    private fun providerId(base: String, id: String): String? {
        val prefix = "addon_${fingerprint(base)}:"
        if (!id.startsWith(prefix)) return null
        return runCatching { String(android.util.Base64.decode(id.removePrefix(prefix), android.util.Base64.URL_SAFE)) }.getOrNull()
    }
    private fun array(body: String): JSONArray {
        if (body.trimStart().startsWith('[')) return JSONArray(body)
        var root = JSONObject(body)
        repeat(3) {
            for (key in listOf("tracks", "data", "results", "items")) root.optJSONArray(key)?.let { return it }
            root = root.optJSONObject("data") ?: return JSONArray()
        }
        return JSONArray()
    }
    private suspend fun search(base: String, name: String, query: String, ceiling: AudioQualityCeiling,
        tier: String = quality(ceiling)): List<MusicTrack> {
        val body = get(configuredEndpoint(base, "search", query.trim(), tier))
        return withContext(AppDispatchers.CpuBounded) {
            val rows = array(body)
            (0 until minOf(rows.length(), 20)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id"); val title = row.optString("title"); val artist = row.optString("artist")
                if (id.isBlank() || title.isBlank() || artist.isBlank()) return@mapNotNull null
                MusicTrack(catalogTrackId(base, id), title, artist, row.optString("album"),
                    (row.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0),
                    row.optString("artworkURL", row.optString("artworkUrl", "")), "",
                    source = "Streaming Addon: $name", isrc = row.optString("isrc"), qualityBadge = "Custom stream")
            }
        }
    }
    private suspend fun searchPreferred(base: String, name: String, query: String, ceiling: AudioQualityCeiling): List<MusicTrack> {
        val first = search(base, name, query, ceiling)
        if (first.isNotEmpty() || ceiling != AudioQualityCeiling.MAX) return first
        val highResolution = search(base, name, query, ceiling, "hi_res")
        return highResolution.ifEmpty { search(base, name, query, ceiling, "lossless") }
    }
    private suspend fun activeAddons(context: Context, policy: PlaybackSourcePolicy): List<PlaybackSource> {
        OtherSettingsPreferences.loadPlaybackSources(context)
        val configured = policy.configuredSources
        return configured.filter { it.kind == PlaybackSourceKind.ADDON && it.enabled &&
            it.available(policy.ceiling) && it.baseUrl.isNotBlank() }
    }
    private suspend fun stillEnabled(context: Context, source: PlaybackSource, policy: PlaybackSourcePolicy): Boolean {
        OtherSettingsPreferences.loadPlaybackSources(context)
        return OtherSettingsPreferences.playbackIntegrations(context).sourceList(policy.ceiling)
            .any { it.id == source.id && it.enabled && it.baseUrl == source.baseUrl && it.available(policy.ceiling) }
    }
    suspend fun search(context: Context, query: String, policy: PlaybackSourcePolicy): List<MusicTrack> = withContext(AppDispatchers.Io) {
        if (!policy.online || query.isBlank()) return@withContext emptyList()
        val sources = activeAddons(context, policy)
        val completed = ConcurrentHashMap<String, List<MusicTrack>>()
        // Retain successful servers if another server times out. Existing HTTP
        // permits bound all servers together, not a separate pool for each URL.
        withTimeoutOrNull(12_000L) { coroutineScope {
            sources.map { source -> async {
                try {
                    val found = com.example.recommendation.DiscoveryRequestCoordinator.shared(
                        "addon-search:${source.id}:${query.trim()}:${policy.key}", essential = true, network = false) {
                        searchPreferred(source.baseUrl, source.name, query, policy.ceiling)
                    }
                    if (stillEnabled(context, source, policy)) completed[source.id] = found
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            } }.awaitAll()
        } }
        sources.flatMap { completed[it.id].orEmpty() }
    }
    suspend fun resolve(context: Context, track: MusicTrack, policy: PlaybackSourcePolicy,
        rejected: Set<String> = emptySet(), source: PlaybackSource? = null): MusicTrack? = withContext(AppDispatchers.Io) {
        if (!policy.online) return@withContext null
        val active = activeAddons(context, policy)
        val selectedSource = (if (source == null) active.firstOrNull() else source.takeIf { it in active })
            ?: return@withContext null
        val key = withContext(AppDispatchers.CpuBounded) {
            "${selectedSource.id}:${fingerprint(selectedSource.baseUrl)}:${RecordingIdentity.key(track)}:${policy.ceiling.name}"
        }
        val cached = resolutions[key]?.takeIf { it.expires > System.currentTimeMillis() }
        val explicitRetry = policy.enhancer && policy.requestTrackId == track.id && policy.requestRevision > 0
        fun reuse(value: MusicTrack?) = value?.let { track.copy(audioUrl = it.audioUrl, source = it.source,
            isLossless = it.isLossless, bitrateKbps = it.bitrateKbps, qualityBadge = it.qualityBadge) }
        if (cached != null && (cached.track == null && !explicitRetry ||
                cached.track != null && (!explicitRetry || cached.track.isLossless) &&
                cached.track.audioUrl !in rejected && isVerified(cached.track.audioUrl, policy))) {
            return@withContext reuse(cached.track).takeIf { stillEnabled(context, selectedSource, policy) }
        }
        val result = com.example.recommendation.DiscoveryRequestCoordinator.shared("addon:$key:${rejected.hashCode()}",
            essential = true, network = false) {
            val value = resolveUncached(context, selectedSource, track, policy, rejected)
            if (resolutions.size >= 128) resolutions.entries.minByOrNull { it.value.expires }?.let { resolutions.remove(it.key) }
            resolutions[key] = Cached(value, System.currentTimeMillis() + if (value == null) 5_000L else 120_000L)
            listOfNotNull(value)
        }.firstOrNull()
        reuse(result).takeIf { stillEnabled(context, selectedSource, policy) }
    }
    private suspend fun resolveUncached(context: Context, source: PlaybackSource, track: MusicTrack, policy: PlaybackSourcePolicy,
        rejected: Set<String>): MusicTrack? = withContext(AppDispatchers.Io) {
        if (!source.available(policy.ceiling) || !policy.online ||
            Uri.parse(track.audioUrl).scheme in setOf("file", "content")) return@withContext null
        val base = source.baseUrl
        val selected = providerId(base, track.id)
        try {
            var completed = false
            val result = withTimeoutOrNull(15_000L) {
                val candidates = if (selected != null) listOf(track) else {
                    val queries = withContext(AppDispatchers.CpuBounded) {
                        val title = com.example.data.repository.StreamResolver.extractCleanTitle(track.title, track.artist)
                        val artist = RecordingIdentity.artistNames(track).firstOrNull().orEmpty()
                        listOf("$title $artist".trim(), "${track.title} ${track.artist}".trim(), title).filter(String::isNotBlank).distinct()
                    }
                    var matched = emptyList<MusicTrack>()
                    for (query in queries) {
                        val found = searchPreferred(base, source.name, query, policy.ceiling)
                        matched = withContext(AppDispatchers.CpuBounded) { found.mapNotNull { candidate ->
                            RecordingIdentity.providerConfidence(track, candidate)?.let { candidate to it }
                        }.sortedByDescending { it.second }.take(2).map { it.first } }
                        if (matched.isNotEmpty()) break
                    }
                    matched
                }
                val tiers = listOf("hi_res", "LOSSLESS", "lossless", "high")
                val tried = mutableSetOf<HttpUrl>()
                var lossyFallback: MusicTrack? = null
                for (tier in tiers) for (candidate in candidates) {
                    val id = providerId(base, candidate.id) ?: continue
                    val url = configuredEndpoint(base, "stream", tier = tier).newBuilder().addPathSegment(id).build()
                    if (!tried.add(url)) continue
                    val body = try { get(url) } catch (e: CancellationException) { throw e }
                        catch (_: IOException) { continue }
                    val resolved = withContext(AppDispatchers.CpuBounded) {
                        try { parseStream(body, base, track, candidate, policy, source.id) }
                        catch (_: org.json.JSONException) { null } // An unsupported tier must not reject the entire server.
                    }
                        ?: continue
                    if (!stillEnabled(context, source, policy)) return@withTimeoutOrNull null
                    if (resolved.audioUrl !in rejected) {
                        if (resolved.isLossless) { completed = true; return@withTimeoutOrNull resolved }
                        if (lossyFallback == null || resolved.bitrateKbps > lossyFallback.bitrateKbps) lossyFallback = resolved
                    }
                }
                completed = true
                if (lossyFallback != null) return@withTimeoutOrNull lossyFallback
                PlaybackDiagnostics.record(track.id, policy, PlaybackSourceKind.ADDON, PlaybackDiagnostics.Stage.RESOLUTION,
                    if (candidates.isEmpty()) PlaybackDiagnostics.Reason.NO_MATCH else PlaybackDiagnostics.Reason.NO_FORMAT, source.id)
                null
            }
            if (!completed) PlaybackDiagnostics.record(track.id, policy, PlaybackSourceKind.ADDON,
                PlaybackDiagnostics.Stage.RESOLUTION, PlaybackDiagnostics.Reason.TIMEOUT, source.id)
            result
        } catch (e: CancellationException) { throw e }
        catch (error: Exception) {
            PlaybackDiagnostics.record(track.id, policy, PlaybackSourceKind.ADDON,
                PlaybackDiagnostics.Stage.RESOLUTION, PlaybackDiagnostics.reason(error), source.id)
            null
        }
    }
    internal fun parseStream(body: String, base: String, requested: MusicTrack, candidate: MusicTrack,
        policy: PlaybackSourcePolicy, sourceId: String = PlaybackSource.addonId(base)): MusicTrack? {
        if (!policy.maximum) return null
        val root = JSONObject(body)
        val value = root.optJSONObject("stream") ?: root.optJSONObject("data") ?: root
        val encrypted = value.opt("encrypted")
        if (encrypted != null && encrypted != JSONObject.NULL && encrypted.toString().lowercase(Locale.ROOT) !in setOf("false", "0", "none", "")) return null
        val format = value.optString("format").lowercase(Locale.ROOT)
        val codec = value.optString("codec").ifBlank { value.optString("fileCodec") }.ifBlank {
            format.takeIf { it in setOf("flac", "alac", "pcm", "wav") }.orEmpty()
        }.ifBlank {
            when (value.optString("mimeType").lowercase(Locale.ROOT)) {
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/alac", "audio/x-alac" -> "alac"
                "audio/wav", "audio/x-wav" -> "wav"
                else -> ""
            }
        }.lowercase(Locale.ROOT)
        val rawBitrate = value.optInt("bitrate", 0)
        val bitrate = if (rawBitrate > 10000) rawBitrate / 1000 else rawBitrate
        if (!policy.maximum && (bitrate <= 0 || bitrate.toLong() * 1000 > policy.ceiling.maxBitrateBps)) return null
        val manifest = value.optString("manifestXml")
        var inlineXml = manifest
        val raw = value.optString("dataUrl").ifBlank { value.optString("url") }
        val stream = when {
            manifest.isNotBlank() -> {
                if (!manifest.contains("<MPD") || manifest.contains("<!DOCTYPE", true) || manifest.contains("<!ENTITY", true)) return null
                "data:application/dash+xml;base64," + android.util.Base64.encodeToString(manifest.toByteArray(), android.util.Base64.NO_WRAP)
            }
            raw.startsWith("data:application/dash+xml;base64,") -> {
                val xml = runCatching { String(android.util.Base64.decode(raw.substringAfter(','), android.util.Base64.DEFAULT)) }.getOrNull()
                    ?: return null
                if (!xml.contains("<MPD") || xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) return null
                inlineXml = xml
                raw
            }
            else -> base.toHttpUrlOrNull()?.resolve(raw)?.toString()?.takeIf { raw.isNotBlank() } ?: return null
        }
        val transport = value.optString("manifest").ifBlank { value.optString("mediaType") }.lowercase(Locale.ROOT)
        val declaredMime = value.optString("mimeType").lowercase(Locale.ROOT)
        val mime = when {
            manifest.isNotBlank() || stream.startsWith("data:application/dash+xml") || format in setOf("dash", "mpd") ||
                transport in setOf("dash", "mpd") || declaredMime == androidx.media3.common.MimeTypes.APPLICATION_MPD -> androidx.media3.common.MimeTypes.APPLICATION_MPD
            format in setOf("hls", "m3u8") || transport in setOf("hls", "m3u8") || declaredMime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
            else -> null
        }
        val headers = value.optJSONObject("headers")?.let { obj -> obj.keys().asSequence().take(32).mapNotNull { key ->
            val v = obj.optString(key)
            (key to v).takeIf { key.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding") &&
                !key.contains('\n') && !key.contains('\r') && !v.contains('\n') && !v.contains('\r') && v.length < 8192 }
        }.toMap() }.orEmpty()
        val now = System.currentTimeMillis()
        fun epoch(value: String?): Long? = value?.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
            ?: value?.let { if (android.os.Build.VERSION.SDK_INT >= 26)
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() else null }
        val urlExpiry = stream.toHttpUrlOrNull()?.let { uri ->
            (uri.queryParameter("expire") ?: uri.queryParameter("expires") ?: uri.queryParameter("Expires"))?.toLongOrNull()
                ?.let { if (it < 100_000_000_000L) it * 1000 else it }
        }
        val declaredExpiry = epoch(value.optString("expiresAt").takeIf(String::isNotBlank))
        val expires = listOfNotNull(urlExpiry, declaredExpiry).minOrNull() ?: (now + 180_000L)
        if (expires <= now + 5_000L) return null
        if (transports.size >= 128) transports.entries.minByOrNull { it.value.expires }?.let { transports.remove(it.key) }
        val scope = stream.toHttpUrlOrNull()?.toString() ?: Regex("<BaseURL[^>]*>\\s*(https?://[^<]+)\\s*</BaseURL>")
            .find(inlineXml)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")?.toHttpUrlOrNull()?.toString()
        val provider = policy.configuredSources.firstOrNull { it.id == sourceId && it.kind == PlaybackSourceKind.ADDON }?.name
            ?.let { "Streaming Addon: $it" } ?: candidate.source
        transports[stream] = Transport(headers, mime, bitrate, minOf(expires - 5_000L, now + 180_000L), scope, sourceId, base, provider)
        return requested.copy(audioUrl = stream, source = provider, bitrateKbps = bitrate,
            isLossless = codec in setOf("flac", "alac", "pcm", "wav"), qualityBadge = "Custom stream")
    }
    fun isVerified(url: String, policy: PlaybackSourcePolicy): Boolean = transports[url]?.let {
        it.expires > System.currentTimeMillis() && policy.maximum &&
            policy.playbackSources.any { source -> source.kind == PlaybackSourceKind.ADDON && source.id == it.sourceId && source.baseUrl == it.baseUrl }
    } == true
    // An active manifest can outlive its new-selection TTL. Keep origin-scoped
    // loading permission separate from reusable URL freshness.
    fun hasRegisteredTransport(url: String): Boolean = transportFor(url) != null
    fun allowsRegisteredTransport(url: String, policy: PlaybackSourcePolicy): Boolean = transportFor(url)?.let { transport ->
        policy.maximum && policy.playbackSources.any { it.kind == PlaybackSourceKind.ADDON &&
            it.id == transport.sourceId && it.baseUrl == transport.baseUrl }
    } == true
    private fun transportFor(url: String): Transport? {
        transports[url]?.let { return it }
        val target = url.toHttpUrlOrNull() ?: return null
        return transports.entries.firstOrNull { (_, transport) ->
            val origin = transport.headerScope?.toHttpUrlOrNull()
            transport.mime in setOf(androidx.media3.common.MimeTypes.APPLICATION_MPD,
                androidx.media3.common.MimeTypes.APPLICATION_M3U8) && origin != null &&
                origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port &&
                target.encodedPath.startsWith(origin.encodedPath.substringBeforeLast('/') + "/")
        }?.value
    }
    fun headers(url: String): Map<String, String> {
        // Resolution TTLs forbid reuse for a NEW selection. An existing HLS/DASH
        // session still needs its origin-scoped headers after that TTL elapses.
        // The registry stays bounded; URL expiry is enforced separately above.
        // A manifest's segment headers stay within the same origin and directory;
        // never forward a custom server's Authorization/Cookie to another host.
        return transportFor(url)?.headers.orEmpty()
    }
    fun mime(url: String): String? = transports[url]?.mime
    fun providerName(url: String): String? = transports[url]?.provider
}
