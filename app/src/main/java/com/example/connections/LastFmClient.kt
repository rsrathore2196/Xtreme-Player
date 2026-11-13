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
import com.example.BuildConfig

internal class LastFmException(val code: Int) : IOException("Last.fm request failed ($code)")
internal class LastFmConfigurationException : IllegalStateException("Last.fm sign-in is unavailable in this build")
internal data class LastFmAppCredentials(val apiKey: String, val secret: String) {
    val configured get() = apiKey.isNotBlank() && secret.isNotBlank()
    override fun toString() = "LastFmAppCredentials(configured=$configured)"
}
internal fun lastFmSignature(parameters: Map<String, String>, secret: String): String {
    val value = parameters.filterKeys { it !in setOf("format", "callback", "api_sig") }.toSortedMap()
        .entries.joinToString("") { it.key + it.value } + secret
    return MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
internal object LastFmClient {
    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build() }
    internal fun appCredentials() = LastFmAppCredentials(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_SECRET)

    /** Passwords are sent only to the service over HTTPS and never become connection state. */
    suspend fun signIn(username: String, password: String, previous: LastFmConnection,
        credentials: LastFmAppCredentials = appCredentials(), client: OkHttpClient = http): LastFmConnection {
        require(username.isNotBlank() && password.isNotBlank()) { "Enter your username and password" }
        // Preserve reconnect support for existing installations with saved credentials.
        val app = credentials.takeIf { it.configured }
            ?: LastFmAppCredentials(previous.apiKey, previous.secret).takeIf { it.configured }
            ?: throw LastFmConfigurationException()
        val connection = previous.copy(apiKey = app.apiKey, secret = app.secret)
        val result = call(connection, "auth.getMobileSession", mapOf("username" to username.trim(), "password" to password), client)
        val session = result.optJSONObject("session") ?: throw IOException("Missing Last.fm session")
        val key = (session.opt("key") as? String)?.takeIf(String::isNotBlank) ?: throw IOException("Missing Last.fm session")
        val name = (session.opt("name") as? String)?.takeIf(String::isNotBlank) ?: throw IOException("Missing Last.fm account")
        return connection.copy(session = key, username = name, pendingToken = "")
    }

    suspend fun call(connection: LastFmConnection, method: String, extra: Map<String, String> = emptyMap(),
        client: OkHttpClient = http): JSONObject = withContext(Dispatchers.IO) {
        if (connection.apiKey.isBlank() || connection.secret.isBlank()) throw LastFmConfigurationException()
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
        client.newCall(request).execute().use { response ->
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
