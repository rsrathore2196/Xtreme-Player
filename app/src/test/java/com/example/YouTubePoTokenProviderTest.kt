package com.example

import android.app.Application
import android.util.Base64
import com.example.data.remote.YouTubePoTokenProvider
import com.example.data.remote.YouTubeTokenBrowser
import com.example.data.remote.YouTubeTokenWebView
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class YouTubePoTokenProviderTest {
    private class Browser(override val expiresAtMs: Long = 60_000L) : YouTubeTokenBrowser {
        override var alive = true
        val minted = mutableListOf<String>()
        var suspended = false
        override suspend fun mint(identifier: String): String {
            minted += identifier
            if (suspended) awaitCancellation()
            return "bound:$identifier"
        }
        override suspend fun close() { alive = false }
    }

    @Test fun nativeStrategyCanSelectTokenRequiredMusicClientsWithoutCreatingABrowser() = runTest {
        var opens = 0
        val provider = YouTubePoTokenProvider({ opens++; Browser() }, { true }, { 0 })
        val strategy = ContentAwareFallbackStrategy()
        val request = ClientSelectionRequest(ContentHints(wantVideo = false), authenticated = false, webViewAvailable = true)
        val without = strategy.selectClients(request)
        assertTrue(without.rejected.any { it.manifest.id == "WEB_REMIX" && it.reasons.any { reason -> "token" in reason.lowercase() } })
        val with = strategy.selectClients(request.copy(availablePoTokenProviders = provider.capabilities.providers))
        assertTrue(with.candidates.any { it.manifest?.id == "WEB_REMIX" })
        assertEquals(0, opens)
    }

    @Test fun concurrentRequestsShareOneSessionAndKeepVisitorAndVideoBindingsSeparate() = runTest {
        var opens = 0
        val browser = Browser()
        val provider = YouTubePoTokenProvider({ opens++; browser }, { true }, { 0 })
        val tokens = (1..20).map { async { provider.getPoToken("abcdefghijk", "visitor", null) } }.awaitAll()
        assertEquals(1, opens)
        assertEquals(listOf("visitor", "abcdefghijk"), browser.minted)
        tokens.forEach {
            assertEquals("bound:visitor", it?.playerRequestToken)
            assertEquals("bound:abcdefghijk", it?.streamingDataToken)
            assertEquals("visitor", it?.visitorData)
        }
        provider.getPoToken("lmnopqrstuv", "visitor", null)
        assertEquals(listOf("visitor", "abcdefghijk", "lmnopqrstuv"), browser.minted)
        provider.close()
        assertFalse(browser.alive)
    }

    @Test fun expiredOrCrashedSessionsAreClosedAndRecreated() = runTest {
        var now = 0L
        val opened = mutableListOf<Browser>()
        val provider = YouTubePoTokenProvider({ Browser(now + 100).also(opened::add) }, { true }, { now })
        provider.getPoToken("abcdefghijk", "visitor", null)
        now = 100
        provider.getPoToken("abcdefghijk", "visitor", null)
        assertEquals(2, opened.size)
        assertFalse(opened.first().alive)
        opened.last().alive = false
        provider.getPoToken("abcdefghijk", "visitor", null)
        assertEquals(3, opened.size)
        provider.close()
    }

    @Test fun changingVisitorOrAccountNeverReusesBoundTokens() = runTest {
        val opened = mutableListOf<Browser>()
        val provider = YouTubePoTokenProvider({ Browser().also(opened::add) }, { true }, { 0 })
        provider.getPoToken("abcdefghijk", "visitor-a", null)
        val next = provider.getPoToken("abcdefghijk", "visitor-b", null)
        assertEquals("bound:visitor-b", next?.playerRequestToken)
        assertFalse(opened.first().alive)
        provider.getPoToken("abcdefghijk", "visitor-b", "account changed")
        assertEquals(3, opened.size)
        provider.close()
    }

    @Test fun cancellationAndTimeoutCloseTheBrowserAndLeaveNoStaleRequest() = runTest {
        val opened = mutableListOf<Browser>()
        val provider = YouTubePoTokenProvider({ Browser().apply { suspended = opened.isEmpty() }.also(opened::add) }, { true }, { 0 })
        val lookup = launch { provider.getPoToken("abcdefghijk", "visitor", null) }
        testScheduler.runCurrent()
        lookup.cancelAndJoin()
        assertFalse(opened.first().alive)
        assertNotNull(provider.getPoToken("abcdefghijk", "visitor", null))
        provider.close()
        val browser = Browser().apply { suspended = true }
        val timeout = YouTubePoTokenProvider({ browser }, { true }, { 0 }, timeoutMs = 100)
        assertNull(timeout.getPoToken("abcdefghijk", "visitor", null))
        assertFalse(browser.alive)
        timeout.close()
    }

    @Test fun temporaryFailuresHaveBoundedRetriesAndShortCooldown() = runTest {
        var now = 0L
        var opens = 0
        val provider = YouTubePoTokenProvider({ opens++; throw IOException("transient") }, { true }, { now })
        assertNull(provider.getPoToken("abcdefghijk", "visitor", null))
        assertEquals(2, opens)
        assertNull(provider.getPoToken("abcdefghijk", "visitor", null))
        assertEquals(2, opens)
        now = 5_001
        assertNull(provider.getPoToken("abcdefghijk", "visitor", null))
        assertEquals(4, opens)
        provider.close()
    }

    @Test fun unavailableWebviewAndClosedProviderDoNotOpenOrAdvertiseIt() = runTest {
        var opens = 0
        val unavailable = YouTubePoTokenProvider({ opens++; Browser() }, { false }, { 0 })
        assertTrue(unavailable.capabilities.providers.isEmpty())
        assertNull(unavailable.getPoToken("abcdefghijk", "visitor", null))
        unavailable.close()
        val provider = YouTubePoTokenProvider({ opens++; Browser() }, { true }, { 0 })
        provider.close()
        assertTrue(provider.capabilities.providers.isEmpty())
        assertNull(provider.getPoToken("abcdefghijk", "visitor", null))
        assertEquals(0, opens)
    }

    @Test fun invalidationDropsOnlyAttestationAndAllowsFreshTokens() = runTest {
        var opens = 0
        val provider = YouTubePoTokenProvider({ opens++; Browser() }, { true }, { 0 })
        provider.getPoToken("abcdefghijk", "visitor", null)
        provider.invalidateAttestation()
        provider.getPoToken("abcdefghijk", "visitor", null)
        assertEquals(2, opens)
        provider.close()
    }

    @Test fun bothOfficialChallengeEncodingsAndServerExpiryAreParsed() {
        val data = JSONArray().put(0, "message").put(1, JSONArray().put("official interpreter"))
            .put(2, JSONArray().put("https://www.gstatic.com/interpreter.js"))
            .put(4, "program").put(5, "vm")
        val plain = JSONArray().put(data).toString()
        val bytes = data.toString().toByteArray()
        val encoded = Base64.encodeToString(ByteArray(bytes.size) { (bytes[it].toInt() - 97).toByte() }, Base64.URL_SAFE or Base64.NO_WRAP)
        val scrambled = JSONArray().put(0).put(encoded).toString()
        assertEquals(YouTubeTokenWebView.parseChallenge(plain), YouTubeTokenWebView.parseChallenge(scrambled))
        assertEquals("vm", YouTubeTokenWebView.parseChallenge(plain).globalName)
        data.put(1, JSONArray().put(org.json.JSONObject.NULL).put("wrapped interpreter"))
            .put(2, JSONArray().put("//www.gstatic.com/interpreter.js"))
        assertEquals("wrapped interpreter", YouTubeTokenWebView.parseChallenge(JSONArray().put(data).toString()).script)
        data.put(1, org.json.JSONObject.NULL)
        val externalScript = YouTubeTokenWebView.parseChallenge(JSONArray().put(data).toString())
        assertEquals("", externalScript.script)
        assertEquals("https://www.gstatic.com/interpreter.js", externalScript.scriptUrl)
        val integrity = YouTubeTokenWebView.parseIntegrity("[\"AQID\",3600]")
        assertArrayEquals(byteArrayOf(1, 2, 3), integrity.first)
        assertEquals(3_600_000L, integrity.second)
        val bytesWithStandardBase64Symbols = byteArrayOf(-5, -1, -2)
        val standardIntegrity = JSONArray().put(Base64.encodeToString(bytesWithStandardBase64Symbols, Base64.NO_WRAP)).put(3600).toString()
        assertArrayEquals(bytesWithStandardBase64Symbols, YouTubeTokenWebView.parseIntegrity(standardIntegrity).first)
        assertThrows(IllegalArgumentException::class.java) { YouTubeTokenWebView.parseIntegrity("[\"AQID\",0]") }
        assertFalse(YouTubeTokenWebView.allowedResource("https://www.gstatic.com.evil.example/interpreter.js"))
        assertFalse(YouTubeTokenWebView.allowedResource("file:///tmp/interpreter.js"))
    }
}
