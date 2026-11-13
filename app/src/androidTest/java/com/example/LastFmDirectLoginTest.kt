package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.connections.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LastFmDirectLoginTest {
    private val credentials = LastFmAppCredentials("app-key", "app-secret")
    private fun client(body: String, inspect: (Map<String, String>) -> Unit = {}) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals("https://ws.audioscrobbler.com/2.0/", request.url.toString())
        assertEquals("POST", request.method)
        assertNull(request.header("Cookie"))
        val form = request.body as FormBody
        inspect((0 until form.size).associate { form.name(it) to form.value(it) })
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture").code(200)
            .body(body.toResponseBody()).build()
    }.build()

    @Test fun mobileLoginSignsTheAccountRequestAndPreservesScrobblePreferences() = runBlocking(Dispatchers.IO) {
        val previous = LastFmConnection(enabled = false, nowPlaying = false, thresholdPercent = 75, pendingToken = "old-token")
        val http = client("""{"session":{"key":"session-token","name":"Listener"}}""") { form ->
            assertEquals("auth.getMobileSession", form["method"])
            assertEquals("listener", form["username"])
            assertEquals(" pass +密碼 ", form["password"])
            assertEquals("app-key", form["api_key"])
            assertEquals("json", form["format"])
            assertEquals("2cb7810cb7b9497d9e29132d3636be87", form["api_sig"])
            assertFalse(form.containsKey("sk"))
            assertFalse(form.containsValue("app-secret"))
        }
        val connected = LastFmClient.signIn(" listener ", " pass +密碼 ", previous, credentials, http)
        assertEquals(previous.copy(apiKey = "app-key", secret = "app-secret", session = "session-token",
            username = "Listener", pendingToken = ""), connected)
        assertFalse(connected.toString().contains("pass"))
        assertFalse(credentials.toString().contains("app-secret"))
    }

    @Test fun missingBuildConfigurationDoesNotSendThePassword() = runBlocking(Dispatchers.IO) {
        var calls = 0
        try {
            LastFmClient.signIn("listener", "private-password", LastFmConnection(), LastFmAppCredentials("", ""), client("{}") { calls++ })
            fail("Expected a configuration error")
        } catch (_: LastFmConfigurationException) { }
        assertEquals(0, calls)
    }

    @Test fun savedCredentialsStillAllowExistingAccountsToReconnect() = runBlocking(Dispatchers.IO) {
        val old = LastFmConnection("legacy-key", "legacy-secret", "old-session", "Listener")
        val connected = LastFmClient.signIn("listener", "password", old, LastFmAppCredentials("", ""),
            client("""{"session":{"key":"new-session","name":"Listener"}}""") {
                assertEquals("legacy-key", it["api_key"])
            })
        assertEquals("new-session", connected.session)
        assertEquals("legacy-secret", connected.secret)
    }

    @Test fun rejectedCredentialsAndIncompleteSessionsNeverProduceAConnection() = runBlocking(Dispatchers.IO) {
        try {
            LastFmClient.signIn("listener", "incorrect", LastFmConnection(), credentials, client("""{"error":4}"""))
            fail("Expected authentication failure")
        } catch (failure: LastFmException) { assertEquals(4, failure.code) }
        for (body in listOf("{}", """{"session":{"name":"Listener"}}""", """{"session":{"key":"token"}}""",
            """{"session":{"key":123,"name":"Listener"}}""", """{"session":{"key":"token","name":null}}""")) {
            try {
                LastFmClient.signIn("listener", "password", LastFmConnection(), credentials, client(body))
                fail("Expected an invalid session failure")
            } catch (_: IOException) { }
        }
    }
}
