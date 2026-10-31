package com.example.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.webkit.*
import com.example.performance.AppDispatchers
import com.example.playback.PlaybackDiagnostics
import com.metrolist.innertubex.models.YouTubeClient
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Playback-only WebView host for the official anonymous YouTube attestation protocol. */
internal class YouTubeTokenWebView private constructor() : YouTubeTokenBrowser {
    private val loaded = CompletableDeferred<Unit>()
    private val snapshot = CompletableDeferred<String>()
    private val authorized = CompletableDeferred<Unit>()
    private val requests = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val ids = AtomicLong()
    @Volatile override var alive = true
        private set
    @Volatile override var expiresAtMs = 0L
        private set
    private var view: WebView? = null

    private inner class Bridge {
        @JavascriptInterface fun loaded() { if (alive) loaded.complete(Unit) }
        @JavascriptInterface fun snapshot(value: String) {
            if (alive && value.length in 1..MAX_RESPONSE) snapshot.complete(value)
        }
        @JavascriptInterface fun authorized() { if (alive) authorized.complete(Unit) }
        @JavascriptInterface fun token(id: String, bytes: String) {
            if (alive && bytes.length <= 40_000) requests[id]?.complete(bytes)
        }
        @JavascriptInterface fun failed(id: String, @Suppress("UNUSED_PARAMETER") stage: String) {
            // JS errors are intentionally opaque: they can contain VM data or tokens.
            val error = IOException("YouTube attestation script failed")
            if (id.isEmpty()) fail(error) else requests[id]?.completeExceptionally(error)
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // Trusted local page; navigation and file access are disabled.
    private suspend fun create(context: Context, html: String) = withContext(Dispatchers.Main.immediate) {
        check(alive)
        val web = WebView(context.applicationContext)
        view = web
        web.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching {
                web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
            }
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = YouTubeClient.WEB_REMIX.userAgent
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (allowedResource(request.url.toString())) null else
                    WebResourceResponse("text/plain", "utf-8", byteArrayOf().inputStream())
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(IOException("YouTube attestation page failed"))
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererProcessCrashed = true
                lastCrashTimestampMs = SystemClock.elapsedRealtime()
                fail(IOException("YouTube attestation renderer stopped"))
                destroyView()
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Attestation")
        web.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
    }

    private suspend fun evaluate(script: String) = withContext(Dispatchers.Main.immediate) {
        check(alive)
        checkNotNull(view).evaluateJavascript(script, null)
    }

    private suspend fun initialize(context: Context, http: OkHttpClient) {
        val html = withContext(AppDispatchers.Io) { context.assets.open("youtube_attestation.html").bufferedReader().use { it.readText() } }
        val challenge = withContext(AppDispatchers.CpuBounded) { parseChallenge(post(http, "Create", JSONArray().put(REQUEST_KEY))) }
        val interpreter = challenge.script.ifBlank {
            val url = challenge.scriptUrl.takeIf(::allowedResource)
                ?: throw IOException("YouTube attestation interpreter unavailable")
            request(http, Request.Builder().url(url).header("User-Agent", YouTubeClient.WEB_REMIX.userAgent).build())
        }
        create(context, html)
        loaded.await()
        evaluate("audioAttestation.start(${JSONObject.quote(interpreter)},${JSONObject.quote(challenge.program)},${JSONObject.quote(challenge.globalName)})")
        val proof = snapshot.await()
        val integrity = post(http, "GenerateIT", JSONArray().put(REQUEST_KEY).put(proof))
        val (token, lifetime) = withContext(AppDispatchers.CpuBounded) { parseIntegrity(integrity) }
        evaluate("audioAttestation.authorize(${JSONArray(token.map { it.toInt() and 255 })})")
        authorized.await()
        val margin = minOf(60_000L, lifetime / 10)
        expiresAtMs = SystemClock.elapsedRealtime() + lifetime - margin
    }

    override suspend fun mint(identifier: String): String {
        check(alive && expiresAtMs > SystemClock.elapsedRealtime())
        val id = ids.incrementAndGet().toString()
        val response = CompletableDeferred<String>()
        requests[id] = response
        try {
            evaluate("audioAttestation.mint(${JSONObject.quote(id)},${JSONObject.quote(identifier)})")
            return withContext(AppDispatchers.CpuBounded) {
                val bytes = JSONArray(response.await())
                require(bytes.length() in 1..8192)
                val decoded = ByteArray(bytes.length()) { index ->
                    bytes.getInt(index).also { require(it in 0..255) }.toByte()
                }
                Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_WRAP)
            }
        } finally { requests.remove(id) }
    }

    private fun fail(error: Exception) {
        alive = false
        loaded.completeExceptionally(error)
        snapshot.completeExceptionally(error)
        authorized.completeExceptionally(error)
        requests.values.forEach { it.completeExceptionally(error) }
    }
    private fun destroyView() {
        view?.let { web ->
            view = null
            runCatching {
                web.stopLoading()
                web.removeJavascriptInterface("Attestation")
                web.destroy()
            }
        }
    }
    override suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        fail(IOException("YouTube attestation session closed"))
        destroyView()
    }

    companion object {
        @Volatile var rendererProcessCrashed: Boolean = false
        @Volatile var lastCrashTimestampMs: Long = 0L
        const val RENDERER_CRASH_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes

        fun isRendererInCooldown(now: Long = SystemClock.elapsedRealtime()): Boolean {
            if (!rendererProcessCrashed) return false
            if (now - lastCrashTimestampMs >= RENDERER_CRASH_COOLDOWN_MS) {
                // Cooldown period expired, allow recovery attempt
                return false
            }
            return true
        }

        fun markRecoverySuccess() {
            rendererProcessCrashed = false
            lastCrashTimestampMs = 0L
        }

        private const val MAX_RESPONSE = 4 * 1024 * 1024
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        // Public anonymous attestation client identifier, not a user/API credential.
        private const val CLIENT_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        internal data class Challenge(val script: String, val scriptUrl: String, val program: String, val globalName: String)

        suspend fun open(context: Context, http: OkHttpClient): YouTubeTokenBrowser {
            if (isRendererInCooldown()) {
                throw IOException("YouTube attestation WebView in cooldown after renderer crash")
            }
            val browser = YouTubeTokenWebView()
            try {
                browser.initialize(context, http)
                markRecoverySuccess()
                return browser
            } catch (error: Throwable) {
                browser.close()
                throw error
            }
        }
        internal fun allowedResource(address: String): Boolean = address.toHttpUrlOrNull()?.let { url ->
            url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                listOf("youtube.com", "google.com", "gstatic.com", "googleapis.com").any { url.host == it || url.host.endsWith(".$it") }
        } == true
        private fun decode(value: String) = Base64.decode(value.replace('-', '+').replace('_', '/').replace('.', '='), Base64.DEFAULT)
        internal fun parseChallenge(body: String): Challenge {
            val root = JSONArray(body)
            val data = if (root.opt(1) is String) {
                val bytes = decode(root.getString(1))
                JSONArray(ByteArray(bytes.size) { (bytes[it].toInt() + 97).toByte() }.toString(Charsets.UTF_8))
            } else root.getJSONArray(0)
            fun wrappedString(index: Int): String = data.optJSONArray(index)?.let { values ->
                (0 until minOf(values.length(), 32)).firstNotNullOfOrNull { (values.opt(it) as? String)?.takeIf(String::isNotBlank) }
            }.orEmpty()
            val scriptUrl = wrappedString(2).let { if (it.startsWith("//")) "https:$it" else it }
            return Challenge(wrappedString(1), scriptUrl, data.get(4) as String, data.get(5) as String).also {
                require(it.program.isNotBlank() && it.globalName.length in 1..256)
                require(it.script.isNotBlank() || allowedResource(it.scriptUrl))
            }
        }
        internal fun parseIntegrity(body: String): Pair<ByteArray, Long> {
            val data = JSONArray(body)
            val token = decode(data.getString(0))
            val seconds = data.getLong(1)
            require(token.size in 1..64_000 && seconds > 0)
            return token to minOf(seconds, 86_400L) * 1000L
        }
        private suspend fun post(http: OkHttpClient, method: String, body: JSONArray): String = request(http,
            Request.Builder().url("https://www.youtube.com/api/jnn/v1/$method")
                .header("User-Agent", YouTubeClient.WEB_REMIX.userAgent)
                .header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
                .header("x-goog-api-key", CLIENT_KEY).header("x-user-agent", "grpc-web-javascript/0.1")
                .post(body.toString().toRequestBody("application/json+protobuf".toMediaType())).build())

        private suspend fun request(http: OkHttpClient, request: Request): String = suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        runCatching {
                            PlaybackDiagnostics.youtubeEvent("", "ATTESTATION_HTTP", httpStatus = it.code)
                            if (!it.isSuccessful) throw PlaybackDiagnostics.Failure(
                                PlaybackDiagnostics.Reason.NETWORK, PlaybackDiagnostics.Stage.EXTRACTION)
                            val source = it.body.source()
                            source.request(MAX_RESPONSE.toLong() + 1)
                            if (source.buffer.size > MAX_RESPONSE) throw IOException("Oversized YouTube attestation response")
                            source.readUtf8()
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
}
