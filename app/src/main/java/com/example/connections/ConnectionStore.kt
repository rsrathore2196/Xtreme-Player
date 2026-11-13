package com.example.connections

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.remote.YouTubeAudioStreamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class YouTubeConnection(val id: String, val name: String, val cookie: String,
    val visitor: String = "", val dataSyncId: String = "", val authUser: String = "0") {
    override fun toString() = "YouTubeConnection(connected)"
}
internal data class SpotifyConnection(val cookie: String, val bearer: String, val expiresAt: Long,
    val name: String = "Spotify", val clientToken: String = "") {
    override fun toString() = "SpotifyConnection(connected)"
}
internal data class LastFmConnection(val apiKey: String = "", val secret: String = "", val session: String = "",
    val username: String = "", val enabled: Boolean = true, val nowPlaying: Boolean = true,
    val primaryArtistOnly: Boolean = true, val minDurationSeconds: Int = 30,
    val thresholdPercent: Int = 50, val thresholdSeconds: Int = 240, val pendingToken: String = "") {
    override fun toString() = "LastFmConnection(connected=${session.isNotBlank()})"
}
internal data class ConnectionState(val youtube: List<YouTubeConnection> = emptyList(), val selectedYouTube: String = "",
    val spotify: SpotifyConnection? = null, val lastFm: LastFmConnection = LastFmConnection()) {
    val activeYouTube get() = youtube.firstOrNull { it.id == selectedYouTube }
}

/** Authentication material is encrypted with the device Keystore and excluded from backups. */
internal class ConnectionStore internal constructor(context: Context) {
    private val context = context.applicationContext
    private val lock = Mutex()
    private var loaded = false
    private var encrypted: android.content.SharedPreferences? = null
    private val _state = MutableStateFlow(ConnectionState())
    val state = _state.asStateFlow()
    suspend fun load(): Unit = withContext(Dispatchers.IO) { lock.withLock {
        if (loaded) return@withLock
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(context, PREFS, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val root = JSONObject(prefs.getString("connections", "{}") ?: "{}")
        val youtube = root.optJSONArray("youtube") ?: JSONArray()
        val sessions = (0 until youtube.length()).map { index -> youtube.getJSONObject(index).let {
            YouTubeConnection(it.getString("id"), it.getString("name"), it.getString("cookie"),
                it.optString("visitor"), it.optString("sync"), it.optString("user", "0")) } }
        val spotify = root.optJSONObject("spotify")?.let { SpotifyConnection(it.getString("cookie"),
            it.optString("bearer"), it.optLong("expires"), it.optString("name", "Spotify"), it.optString("client")) }
        val fm = root.optJSONObject("lastfm") ?: JSONObject()
        val lastFm = LastFmConnection(fm.optString("key"), fm.optString("secret"), fm.optString("session"),
            fm.optString("name"), fm.optBoolean("enabled", true), fm.optBoolean("now", true), fm.optBoolean("primary", true),
            fm.optInt("min", 30), fm.optInt("percent", 50), fm.optInt("seconds", 240), fm.optString("pending"))
        encrypted = prefs
        _state.value = ConnectionState(sessions, root.optString("active"), spotify, lastFm)
        loaded = true
        applyDiscoveryAccount()
    } }
    suspend fun change(transform: (ConnectionState) -> ConnectionState) = withContext(Dispatchers.IO) {
        load()
        lock.withLock {
            val next = transform(_state.value)
            val fm = next.lastFm
            val root = JSONObject().put("active", next.selectedYouTube)
                .put("youtube", JSONArray(next.youtube.map { JSONObject().put("id", it.id).put("name", it.name)
                    .put("cookie", it.cookie).put("visitor", it.visitor).put("sync", it.dataSyncId).put("user", it.authUser) }))
                .put("lastfm", JSONObject().put("key", fm.apiKey).put("secret", fm.secret).put("session", fm.session)
                    .put("name", fm.username).put("enabled", fm.enabled).put("now", fm.nowPlaying).put("primary", fm.primaryArtistOnly)
                    .put("min", fm.minDurationSeconds).put("percent", fm.thresholdPercent).put("seconds", fm.thresholdSeconds)
                    .put("pending", fm.pendingToken))
            next.spotify?.let { root.put("spotify", JSONObject().put("cookie", it.cookie).put("bearer", it.bearer)
                .put("expires", it.expiresAt).put("name", it.name).put("client", it.clientToken)) }
            check(encrypted!!.edit().putString("connections", root.toString()).commit()) { "Could not save connection" }
            _state.value = next
            applyDiscoveryAccount()
        }
    }
    private fun applyDiscoveryAccount() {
        val yt = _state.value.activeYouTube
        YouTubeAudioStreamService.setDiscoveryConnection(yt?.cookie, yt?.visitor, yt?.dataSyncId, yt?.authUser)
    }
    suspend fun addYouTube(name: String, cookie: String, visitor: String, sync: String, user: String) {
        require(cookieValue(cookie, "SAPISID").isNotBlank() || cookieValue(cookie, "__Secure-3PAPISID").isNotBlank()) {
            "The sign-in session is not ready yet"
        }
        val identity = digest(sync.ifBlank { "${cookieValue(cookie, "SAPISID").ifBlank { cookieValue(cookie, "__Secure-3PAPISID") }}|$user" })
        val session = YouTubeConnection(identity, name.ifBlank { "YouTube Music" }, cookie, visitor, sync, user)
        change { it.copy(youtube = it.youtube.filterNot { old -> old.id == identity } + session, selectedYouTube = identity) }
    }
    companion object {
        const val PREFS = "account_connections_secure"
        @android.annotation.SuppressLint("StaticFieldLeak") // Constructor retains only applicationContext.
        @Volatile private var instance: ConnectionStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: ConnectionStore(context.applicationContext).also { instance = it }
        }
        fun cookieValue(cookie: String, name: String): String = cookie.split(';').firstOrNull {
            it.substringBefore('=').trim() == name }?.substringAfter('=', "")?.trim().orEmpty()
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
