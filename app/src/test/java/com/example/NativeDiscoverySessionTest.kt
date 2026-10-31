package com.example

import android.app.Application
import com.example.data.remote.YouTubeAudioStreamService
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeDiscoverySessionTest {
    @Test fun genuineSdkAccountDiscoveryStaysIsolatedFromAnonymousRequestsAndPlayback() = runBlocking {
        val requests = mutableListOf<Pair<Request, JSONObject>>()
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            requests += chain.request() to JSONObject(body)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"contents\":{}}".toResponseBody()).build()
        }.build()
        val http = HttpClient(OkHttp) {
            engine { preconfigured = network }
            install(ContentNegotiation) { json(YouTubeAudioStreamService.playerJson) }
        }
        val playback = InnerTube(http).apply {
            replaceSession("SAPISID=fixture-session", "fixture-visitor", "fixture-sync", "1", true)
            locale = YouTubeLocale(gl = "US", hl = "en")
        }
        val before = playback.sessionSnapshot()
        val account = playback.createIsolatedSession(true).apply { locale = YouTubeLocale(gl = "IN", hl = "en") }
        val anonymous = playback.createIsolatedSession(false).apply {
            locale = YouTubeLocale(gl = "GB", hl = "en"); regionOverrideActive = true
        }
        try {
            YouTubeAudioStreamService.requestDiscovery(account, "browse", "FEmusic_home", setLogin = true)
            assertTrue(requests.last().first.header("Authorization").orEmpty().startsWith("SAPISIDHASH "))
            assertTrue(requests.last().first.header("Cookie").orEmpty().contains("fixture-session"))
            assertEquals("IN", requests.last().second.getJSONObject("context").getJSONObject("client").getString("gl"))
            assertEquals("FEmusic_home", requests.last().second.getString("browseId"))

            YouTubeAudioStreamService.requestDiscovery(anonymous, "browse", "FEmusic_home", continuation = "page-two")
            assertNull(requests.last().first.header("Authorization"))
            assertFalse(requests.last().first.header("Cookie").orEmpty().contains("fixture-session"))
            assertEquals("GB", requests.last().second.getJSONObject("context").getJSONObject("client").getString("gl"))
            assertEquals("page-two", requests.last().second.getString("continuation"))
            assertTrue(requests.last().second.optString("browseId").isBlank())

            YouTubeAudioStreamService.requestDiscovery(anonymous, "radio", "abcdefghijk")
            assertTrue(requests.last().first.url.encodedPath.endsWith("/next"))
            assertEquals("RDAMVMabcdefghijk", requests.last().second.getString("playlistId"))
            assertEquals("abcdefghijk", requests.last().second.getString("videoId"))
            assertEquals(before, playback.sessionSnapshot())
        } finally { account.close(); anonymous.close(); playback.close(); http.close() }
    }
}
