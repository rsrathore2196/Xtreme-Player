package com.example.connections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class LastFmException(val code: Int) : IOException("Last.fm request failed ($code)")
internal fun lastFmSignature(parameters: Map<String, String>, secret: String): String {
    val value = parameters.filterKeys { it !in setOf("format", "callback", "api_sig") }.toSortedMap()
        .entries.joinToString("") { it.key + it.value } + secret
    return MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
internal object LastFmClient {
    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    suspend fun call(connection: LastFmConnection, method: String, extra: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        require(connection.apiKey.isNotBlank() && connection.secret.isNotBlank()) { "Enter your Last.fm API key and secret" }
        val params = mutableMapOf("api_key" to connection.apiKey, "method" to method).apply {
            if (method.startsWith("track.")) put("sk", connection.session)
            putAll(extra)
        }
        val body = FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, value) }
            add("api_sig", lastFmSignature(params, connection.secret)); add("format", "json")
        }.build()
        val request = Request.Builder().url("https://ws.audioscrobbler.com/2.0/").post(body)
            .header("User-Agent", "Xtreme Player").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Last.fm is unavailable")
            val root = JSONObject(response.body?.string() ?: throw IOException("Empty Last.fm response"))
            if (root.has("error")) throw LastFmException(root.optInt("error"))
            root
        }
    }
}

internal fun shouldScrobble(durationMs: Long, listenedMs: Long, minimumSeconds: Int, percent: Int, seconds: Int): Boolean {
    if (durationMs <= minimumSeconds.coerceAtLeast(30) * 1000L) return false
    val threshold = minOf(durationMs * percent.coerceIn(1, 100) / 100, seconds.coerceAtLeast(30) * 1000L)
    return listenedMs.coerceAtLeast(0) >= threshold
}
