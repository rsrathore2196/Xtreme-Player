package com.example.data.remote

import android.text.Html
import android.util.Base64
import com.example.BuildConfig
import com.example.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** One lookup session: bounded parallel requests, shared TTML downloads and cancellable HTTP. */
internal class LyricsSources(private val identity: SongIdentity) {
    private val title = LyricsProvider.sanitizeTitle(identity.title).first
    private val artist = identity.artist.orEmpty()
    private val duration = (identity.durationMs ?: 0L) / 1000
    private val documents = ConcurrentHashMap<String, Deferred<String?>>()
    private val slots = Semaphore(3)
    private val videoId = identity.trackId.orEmpty().removePrefix("yt_").takeIf { identity.trackId?.startsWith("yt_") == true && it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    private data class Source(val name: String, val fetch: suspend () -> TrackLyrics?)

    suspend fun lookup(initial: TrackLyrics?, onCandidate: (TrackLyrics) -> Unit): TrackLyrics? = supervisorScope {
        var best = initial
        val rich = listOf(
            Source("lrc.red") { lrcRed(this@supervisorScope) },
            Source("BiniLyrics") { bini(this@supervisorScope) },
            Source("BetterLyrics") { better("getLyrics", "BetterLyrics") },
            Source("PaxSenix") { paxApple() },
            Source("LyricsPlus") { plus() },
            Source("Unison") { unison() }
        )
        val word = listOf(
            Source("BetterLyrics Portato") { better("qq/getLyrics", "BetterLyrics Portato") },
            Source("PaxSenix: Spotify") { pax("spotify") },
            Source("PaxSenix: Musixmatch") { pax("musixmatch") },
            Source("Musixmatch") { musixmatch() },
            Source("KuGou") { kugou() },
            Source("YouTube captions") { captions() }
        )
        // A quick, verified fallback must not wait for every rich provider to time out.
        // Two rolling workers retain rich/word priority; the third HTTP slot lets
        // LRCLIB (then exact-video lyrics) supply usable text while timing upgrades race.
        val latest = AtomicReference(initial)
        try {
            withTimeoutOrNull(18_000L) {
                channelFlow {
                    suspend fun publish(source: Source) {
                        val candidate = safely { withTimeoutOrNull(6_000L) { source.fetch() } }
                            ?.takeIf { LyricsDocuments.verified(identity, it) }
                        if (candidate != null) send(candidate)
                    }
                    suspend fun prioritized(sources: List<Source>, budget: Long) {
                        withTimeoutOrNull(budget) {
                            val next = AtomicInteger()
                            coroutineScope {
                                repeat(2) {
                                    launch {
                                        while (isActive) {
                                            val source = sources.getOrNull(next.getAndIncrement()) ?: break
                                            publish(source)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val fastFallback = launch {
                        publish(Source("LRCLIB") { lrclib() })
                        if (videoId != null && latest.get()?.let { LyricsDocuments.quality(it) >= 1 } != true) {
                            publish(Source("YouTube Music") { youtubeMusic() })
                        }
                    }
                    prioritized(rich, 8_000L)
                    prioritized(word, 6_000L)
                    fastFallback.join()
                    // Plain-text scraping only helps when no timed lyrics were found.
                    if (latest.get()?.let { LyricsDocuments.quality(it) >= 1 } != true) {
                        withTimeoutOrNull(4_000L) { publish(Source("Genius") { genius() }) }
                    }
                }.firstOrNull { candidate ->
                    if (LyricsDocuments.meaningfulUpgrade(candidate, best)) {
                        best = candidate
                        latest.set(candidate)
                        onCandidate(candidate)
                    }
                    // Stopping collection cancels the producer and all unfinished providers.
                    best?.let { LyricsDocuments.assessment(it).let { timing -> timing.tier == 3 && timing.complete } } == true
                }
            }
        } finally { documents.values.forEach { if (!it.isCompleted) it.cancel() } }
        best
    }

    private suspend fun safely(block: suspend () -> TrackLyrics?): TrackLyrics? = try { block() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private fun url(base: String, vararg query: Pair<String, Any?>): String = base.toHttpUrl().newBuilder().apply {
        query.forEach { (key, value) -> if (value != null && value.toString().isNotBlank()) addQueryParameter(key, value.toString()) }
    }.build().toString()
    private suspend fun get(url: String, bearer: String? = null): String? = slots.withPermit { withContext(Dispatchers.IO) { request(Request.Builder().url(url).apply {
        header("User-Agent", "XtremePlayer/1.8 (lyrics)")
        header("Accept", "application/json, application/ttml+xml, text/plain, text/html")
        if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
    }.build()) } }
    private suspend fun shared(scope: CoroutineScope, address: String): String? {
        val existing = documents[address]
        if (existing != null) return existing.await()
        val created = scope.async(start = CoroutineStart.LAZY) { get(address) }
        val actual = documents.putIfAbsent(address, created) ?: created
        if (actual !== created) created.cancel()
        return actual.await()
    }
    private fun parse(raw: String?, source: String): TrackLyrics? = raw?.let { LyricsDocuments.parse(identity, it, source) }
    private fun array(value: JSONArray?): List<JSONObject> = if (value == null) emptyList() else (0 until value.length()).mapNotNull { value.optJSONObject(it) }
    private fun matches(name: String, singer: String, seconds: Double = 0.0): Boolean {
        if (name.isBlank()) return false
        if (duration > 0 && seconds > 0 && abs(duration - seconds) > 5) return false
        return LyricsDocuments.identityMatches(identity, name, singer)
    }
    private fun isrc(value: String?): String? = value?.replace("-", "")?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }
    private suspend fun lrcRed(scope: CoroutineScope): TrackLyrics? {
        isrc(identity.isrc)?.let { code -> parse(shared(scope, "https://lrc.red/s/$code.ttml"), "lrc.red")?.let { return it } }
        val json = get(url("https://lrc.red/search.json", "q" to "$title $artist"))?.let(::JSONObject) ?: return null
        val hit = array(json.optJSONArray("hits")).firstOrNull { matches(it.optString("title"), it.optString("artist"), it.optDouble("duration", 0.0)) } ?: return null
        val code = isrc(hit.optString("isrc")) ?: return null
        return parse(shared(scope, "https://lrc.red/s/$code.ttml"), "lrc.red")
    }
    private suspend fun bini(scope: CoroutineScope): TrackLyrics? {
        val address = isrc(identity.isrc)?.let { url("https://lyrics-api.binimum.org/", "isrc" to it) }
            ?: url("https://lyrics-api.binimum.org/", "track" to title, "artist" to artist, "album" to identity.album, "duration" to duration.takeIf { it > 0 })
        val json = get(address)?.let(::JSONObject) ?: return null
        val hit = array(json.optJSONArray("results")).firstOrNull {
            matches(it.optString("track_name"), it.optString("artist_name"), it.optDouble("duration", 0.0))
        } ?: return null
        val document = hit.optString("lyricsUrl").toHttpUrl()
        if (document.scheme != "https" || document.host !in setOf("lrc.red", "lyrics-api.binimum.org")) return null
        return parse(shared(scope, document.toString()), "BiniLyrics")
    }
    private suspend fun better(path: String, name: String): TrackLyrics? {
        val body = get(url("https://lyrics-api.boidu.dev/$path", "s" to title, "a" to artist,
            "d" to duration.takeIf { it > 0 }, "al" to identity.album)) ?: return null
        val metadata = runCatching { JSONObject(body).optJSONObject("metadata") }.getOrNull()
        if (metadata != null) {
            val foundTitle = metadata.optString("title", metadata.optString("trackName"))
            val foundArtist = metadata.optString("artist", metadata.optString("artistName"))
            if (foundTitle.isNotBlank() && foundArtist.isNotBlank() && !matches(foundTitle, foundArtist)) return null
        }
        return parse(body, name)
    }

    private suspend fun plus(source: String? = null): TrackLyrics? {
        var best: TrackLyrics? = null
        for (host in listOf("https://lyricsplus.prjktla.my.id", "https://lyricsplus.binimum.org", "https://lyricsplus.atomix.one")) {
            val body = get(url("$host/v2/lyrics/get", "title" to title, "artist" to artist,
                "duration" to duration.takeIf { it > 0 }, "album" to identity.album, "isrc" to identity.isrc, "source" to source)) ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val meta = json.optJSONObject("metadata")
            if (meta != null && meta.optString("title").isNotBlank() && !matches(meta.optString("title"), meta.optString("artist"))) continue
            val candidate = parse(body, if (source == null) "LyricsPlus" else "Musixmatch (LyricsPlus)") ?: continue
            if (best == null || LyricsDocuments.comparator.compare(candidate, best) > 0) best = candidate
            if (LyricsDocuments.quality(candidate) >= 3) break
        }
        return best
    }
    private suspend fun unison(): TrackLyrics? {
        val json = get(url("https://unison.boidu.dev/lyrics", "song" to title, "artist" to artist,
            "album" to identity.album, "duration" to duration.takeIf { it > 0 }))?.let(::JSONObject) ?: return null
        if (!json.optBoolean("success")) return null
        val data = json.optJSONObject("data") ?: return null
        if (data.optString("song").isNotBlank() && !matches(data.optString("song"), data.optString("artist"))) return null
        return parse(data.optString("lyrics"), "Unison")
    }
    private suspend fun paxApple(): TrackLyrics? {
        // Public catalog identifiers are Apple song ids; no scraped developer token is needed.
        val search = get(url("https://itunes.apple.com/search", "term" to "$title $artist", "entity" to "song", "limit" to 8))?.let(::JSONObject) ?: return null
        val hit = array(search.optJSONArray("results")).firstOrNull { matches(it.optString("trackName"), it.optString("artistName"), it.optDouble("trackTimeMillis", 0.0) / 1000) } ?: return null
        return parse(get(url("https://lyrics.paxsenix.org/apple-music/lyrics", "id" to hit.optString("trackId"), "ttml" to true)), "PaxSenix")
    }
    private suspend fun pax(source: String): TrackLyrics? {
        val key = BuildConfig.PAXSENIX_API_KEY.takeIf(String::isNotBlank) ?: return null
        val body = if (source == "spotify") {
            val search = get(url("https://api.paxsenix.org/spotify/search", "q" to "$title $artist"), key)?.let(::JSONObject)
            val hit = search?.let { objects(it).firstOrNull { row ->
                matches(row.optString("name", row.optString("title")), artistNames(row), row.optDouble("duration_ms", 0.0) / 1000)
            } }
            hit?.optString("id")?.takeIf(String::isNotBlank)?.let { get(url("https://api.paxsenix.org/lyrics/spotify", "id" to it), key) }
        } else get(url("https://api.paxsenix.org/lyrics/musixmatch", "t" to title, "a" to artist, "d" to duration), key)
        val name = "PaxSenix: ${if (source == "spotify") "Spotify" else "Musixmatch"}"
        parse(body, name)?.let { return it }
        val fallback = get(url("https://api.paxsenix.org/lyrics/lrcget", "q" to "$title $artist"), key) ?: return null
        val json = runCatching { org.json.JSONTokener(fallback).nextValue() }.getOrNull() ?: return null
        return objects(json).filter { row ->
            matches(row.optString("trackName", row.optString("title")), row.optString("artistName", row.optString("artist")), row.optDouble("duration", 0.0))
        }.mapNotNull { parse(it.toString(), name) }.maxWithOrNull(LyricsDocuments.comparator)
    }
    private fun artistNames(row: JSONObject): String = row.optString("artist").ifBlank {
        array(row.optJSONArray("artists")).joinToString(", ") { it.optString("name") }
    }
    private suspend fun musixmatch(): TrackLyrics? {
        val key = BuildConfig.MUSIXMATCH_API_KEY
        if (key.isBlank()) return plus("musixmatch-word,musixmatch")
        suspend fun api(path: String, vararg args: Pair<String, Any?>): JSONObject? = get(url("https://api.musixmatch.com/ws/1.1/$path", *args, "apikey" to key))
            ?.let(::JSONObject)?.optJSONObject("message")?.optJSONObject("body")
        val search = api("track.search", "q_track" to title, "q_artist" to artist, "page_size" to 5) ?: return null
        val hit = array(search.optJSONArray("track_list")).mapNotNull { it.optJSONObject("track") }
            .firstOrNull { matches(it.optString("track_name"), it.optString("artist_name"), it.optDouble("track_length", 0.0)) } ?: return null
        val id = hit.optLong("track_id")
        parse(api("track.richsync.get", "track_id" to id)?.optJSONObject("richsync")?.optString("richsync_body"), "Musixmatch")?.let { return it }
        parse(api("track.subtitle.get", "track_id" to id, "subtitle_format" to "lrc")?.optJSONObject("subtitle")?.optString("subtitle_body"), "Musixmatch")?.let { return it }
        return parse(api("track.lyrics.get", "track_id" to id)?.optJSONObject("lyrics")?.optString("lyrics_body"), "Musixmatch")
    }
    private suspend fun musicPost(path: String, fields: JSONObject): JSONObject? {
        fields.put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20250901.01.00").put("hl", "en")))
        val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/$path")
            .header("User-Agent", "Mozilla/5.0").header("Referer", "https://music.youtube.com/")
            .post(fields.toString().toRequestBody("application/json".toMediaType())).build()
        return slots.withPermit { withContext(Dispatchers.IO) { request(request) } }?.let(::JSONObject)
    }
    private suspend fun captions(): TrackLyrics? {
        val id = videoId ?: return null
        val params = Base64.encodeToString(byteArrayOf(10, 11) + id.toByteArray(), Base64.NO_WRAP)
        val doc = musicPost("get_transcript", JSONObject().put("params", params)) ?: return null
        val lines = objects(doc).mapNotNull { it.optJSONObject("transcriptCueRenderer") }.mapNotNull {
            val text = youtubeText(it.optJSONObject("cue")).trim(' ', '\n', '♪')
            val time = it.optString("startOffsetMs").toLongOrNull() ?: return@mapNotNull null
            if (text.isBlank()) null else LyricLine(time, text, it.optString("durationMs").toLongOrNull() ?: 0)
        }.toList()
        return LyricsDocuments.build(identity, "YouTube captions", lines)
    }
    private suspend fun youtubeMusic(): TrackLyrics? {
        val id = videoId ?: return null
        val next = musicPost("next", JSONObject().put("videoId", id)) ?: return null
        val endpoint = objects(next).mapNotNull { it.optJSONObject("browseEndpoint") }.firstOrNull { it.optString("browseId").startsWith("MPLYt_") } ?: return null
        val page = musicPost("browse", JSONObject().put("browseId", endpoint.optString("browseId")).apply {
            if (endpoint.has("params")) put("params", endpoint.optString("params"))
        }) ?: return null
        val shelf = objects(page).mapNotNull { it.optJSONObject("musicDescriptionShelfRenderer") }.firstOrNull() ?: return null
        return parse(youtubeText(shelf.optJSONObject("description")), "YouTube Music")
    }
    private fun youtubeText(json: JSONObject?): String = json?.optString("simpleText")?.ifBlank {
        array(json.optJSONArray("runs")).joinToString("") { it.optString("text") }
    }.orEmpty()
    private suspend fun lrclib(): TrackLyrics? {
        var best: TrackLyrics? = null
        if (duration > 0) {
            val direct = get(url("https://lrclib.net/api/get", "track_name" to title, "artist_name" to artist,
                "album_name" to identity.album, "duration" to duration))
            direct?.let { raw ->
                val row = runCatching { JSONObject(raw) }.getOrNull()
                if (row != null && matches(row.optString("trackName"), row.optString("artistName"), row.optDouble("duration", 0.0)))
                    best = parse(raw, "LRCLIB")
            }
            if (best?.isSynced == true) return best
        }
        val body = get(url("https://lrclib.net/api/search", "track_name" to title, "artist_name" to artist)) ?: return best
        val candidates = array(runCatching { JSONArray(body) }.getOrNull())
            .filter { matches(it.optString("trackName"), it.optString("artistName"), it.optDouble("duration", 0.0)) }
            .mapNotNull { parse(it.toString(), "LRCLIB") }
        return (candidates + listOfNotNull(best)).maxWithOrNull(LyricsDocuments.comparator)
    }
    private suspend fun kugou(): TrackLyrics? {
        val search = get(url("https://lyrics.kugou.com/search", "ver" to 1, "man" to "yes", "client" to "pc",
            "keyword" to "$title $artist", "duration" to (identity.durationMs ?: 0)))?.let(::JSONObject) ?: return null
        val candidates = array(search.optJSONArray("candidates")).filter { matches(it.optString("song"), it.optString("singer"), it.optDouble("duration", 0.0) / 1000) }
        for (hit in candidates.take(2)) {
            val body = get(url("https://lyrics.kugou.com/download", "ver" to 1, "client" to "pc", "id" to hit.optString("id"),
                "accesskey" to hit.optString("accesskey"), "fmt" to "lrc", "charset" to "utf8"))?.let(::JSONObject) ?: continue
            parse(String(Base64.decode(body.optString("content"), Base64.DEFAULT), Charsets.UTF_8), "KuGou")?.let { return it }
        }
        return null
    }
    private suspend fun genius(): TrackLyrics? {
        val search = get(url("https://genius.com/api/search/multi", "q" to "$title $artist"))?.let(::JSONObject) ?: return null
        val hit = objects(search).mapNotNull { it.optJSONObject("result") }.firstOrNull {
            matches(it.optString("title"), it.optString("artist_names", it.optJSONObject("primary_artist")?.optString("name").orEmpty()))
        } ?: return null
        val address = hit.optString("url").toHttpUrl()
        if (address.scheme != "https" || address.host != "genius.com") return null
        val page = get(address.toString()) ?: return null
        val sections = Regex("<div[^>]*data-lyrics-container=[\"']true[\"'][^>]*>(.*?)</div>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(page).map { Html.fromHtml(it.groupValues[1].replace(Regex("<br\\s*/?>"), "<br>"), Html.FROM_HTML_MODE_LEGACY).toString() }.toList()
        return parse(sections.joinToString("\n"), "Genius")
    }
    private fun objects(value: Any?, depth: Int = 0): Sequence<JSONObject> = sequence {
        if (depth > 28) return@sequence
        when (value) {
            is JSONObject -> { yield(value); value.keys().forEach { yieldAll(objects(value.opt(it), depth + 1)) } }
            is JSONArray -> for (i in 0 until value.length()) yieldAll(objects(value.opt(i), depth + 1))
        }
    }
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
        private suspend fun request(request: Request): String? = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = runCatching {
                            if (!it.isSuccessful) null else it.body?.source()?.let { source ->
                                source.request(2_000_001L)
                                if (source.buffer.size > 2_000_000L) null else source.readUtf8()
                            }
                        }.getOrNull()
                        if (!continuation.isCancelled) continuation.resume(text)
                    }
                }
            })
        }
    }
}
