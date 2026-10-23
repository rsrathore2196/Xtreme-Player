package com.example.data.remote

import android.os.SystemClock
import android.os.Build
import android.annotation.SuppressLint
import android.webkit.WebView
import com.example.playback.PlaybackDiagnostics
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** One anonymous, memory-only attestation session shared by the playback extractors. */
@SuppressLint("WebViewApiAvailability") // SDK-guarded platform probe; no AndroidX WebKit dependency is needed.
internal class YouTubePoTokenProvider(
    private val openBrowser: suspend () -> YouTubeTokenBrowser,
    private val supported: () -> Boolean = { Build.VERSION.SDK_INT < 26 || WebView.getCurrentWebViewPackage() != null },
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val timeoutMs: Long = 18_000L
) : TokenProvider {
    private data class Session(val visitor: String, val account: String, val browser: YouTubeTokenBrowser,
        val visitorToken: String, val videos: LinkedHashMap<String, String> = linkedMapOf())
    private val lock = Mutex()
    private var session: Session? = null
    private var retryAfterMs = 0L
    @Volatile private var closed = false
    override val capabilities: TokenProviderCapabilities
        get() = if (!closed && runCatching(supported).getOrDefault(false))
            TokenProviderCapabilities(setOf(PoTokenProviderKind.WEB_BOTGUARD), usesWebView = true)
        else TokenProviderCapabilities()

    override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? {
        if (closed || !YouTubeAudioStreamService.isVideoId(videoId) || visitorData.isBlank() ||
            visitorData.length > 4096 || capabilities.providers.isEmpty()) return null
        // Credentials never enter diagnostics, disk, or the browser bridge.
        val account = cookie?.takeIf(String::isNotEmpty)?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }.orEmpty()
        return withTimeoutOrNull(timeoutMs) {
            lock.withLock {
                if (closed) return@withLock null
                if (now() < retryAfterMs) return@withLock null
                repeat(2) { attempt ->
                    try {
                        var current = session
                        if (current == null || current.visitor != visitorData || current.account != account ||
                            !current.browser.alive || current.browser.expiresAtMs <= now()) {
                            discard()
                            val browser = openBrowser()
                            try {
                                current = Session(visitorData, account, browser, browser.mint(visitorData))
                                check(current.visitorToken.isNotBlank() && browser.alive && browser.expiresAtMs > now())
                                session = current
                            } catch (error: Throwable) {
                                withContext(NonCancellable) { browser.close() }
                                throw error
                            }
                        }
                        val active = checkNotNull(current)
                        val videoToken = active.videos[videoId] ?: active.browser.mint(videoId).also {
                            check(it.isNotBlank())
                            active.videos[videoId] = it
                            while (active.videos.size > 16) active.videos.remove(active.videos.keys.first())
                        }
                        check(active.browser.alive && active.browser.expiresAtMs > now())
                        // Native v0.8.6 chooses these by binding, not by their field names:
                        // VISITOR_DATA -> playerRequestToken, VIDEO_ID -> streamingDataToken.
                        return@withLock PoTokenResult(active.visitorToken, videoToken, visitorData)
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { discard() }
                        throw cancelled
                    } catch (error: Exception) {
                        discard()
                        PlaybackDiagnostics.youtubeEvent(videoId, "TOKEN_FAILED", reason = PlaybackDiagnostics.reason(error))
                        if (attempt == 1) {
                            retryAfterMs = now() + 5_000L
                            return@withLock null
                        }
                    }
                }
                null
            }
        }.also {
            currentCoroutineContext().ensureActive()
            if (it == null) PlaybackDiagnostics.youtubeEvent(videoId, "TOKEN_UNAVAILABLE")
        }
    }

    private suspend fun discard() {
        val old = session.also { session = null }
        if (old != null) withContext(NonCancellable) { old.browser.close() }
    }
    override suspend fun invalidateAttestation() = lock.withLock { discard() }
    override suspend fun close() {
        closed = true
        lock.withLock { discard() }
    }
}

internal interface YouTubeTokenBrowser {
    val alive: Boolean
    /** Monotonic deadline from the integrity service, with a renewal margin. */
    val expiresAtMs: Long
    suspend fun mint(identifier: String): String
    suspend fun close()
}
