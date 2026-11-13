package com.example.connections

import com.example.downloads.downloadKey
import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    @Test fun permitsOnlySecureProviderNavigation() {
        assertTrue(ConnectionHosts.allowed("https://accounts.google.com/ServiceLogin", "youtube"))
        assertTrue(ConnectionHosts.allowed("https://music.youtube.com/", "youtube"))
        assertTrue(ConnectionHosts.allowed("https://accounts.spotify.com/login", "spotify"))
        assertTrue(ConnectionHosts.allowed("https://open.spotify.com/", "spotify"))
    }
    @Test fun rejectsHostSpoofingAndUntrustedSchemes() {
        listOf("https://music.youtube.com.evil.test/", "https://evil.test/?youtube.com", "https://youtube.com@evil.test/",
            "http://music.youtube.com/", "javascript:alert(1)", "file:///data/data/", "https://music.youtube.com:444/",
            "https://music.youtube.com@accounts.google.com/", "intent://music.youtube.com/").forEach {
            assertFalse(it, ConnectionHosts.allowed(it, "youtube"))
        }
        assertFalse(ConnectionHosts.allowed("https://spotify.com.evil.test/", "spotify"))
        assertFalse(ConnectionHosts.allowed("https://music.youtube.com/", "spotify"))
    }
    @Test fun cookieLookupRequiresExactNamesAndPreservesValueEquals() {
        assertEquals("secret=value", ConnectionStore.cookieValue("__Secure-SAPISID=wrong; SAPISID=secret=value; SID=other", "SAPISID"))
        assertEquals("", ConnectionStore.cookieValue("XSAPISID=wrong; SID=other", "SAPISID"))
        assertEquals("token", ConnectionStore.cookieValue("other=yes; sp_dc=token", "sp_dc"))
    }
    @Test fun signsSortedLastFmParametersWithUtf8() {
        assertEquals("9ac306496295a8866c4a8673395540eb", lastFmSignature(linkedMapOf("token" to "token", "method" to "auth.getSession", "api_key" to "key"), "secret"))
        assertEquals(lastFmSignature(mapOf("track" to "गीत", "artist" to "Björk"), "shared"),
            lastFmSignature(linkedMapOf("artist" to "Björk", "track" to "गीत"), "shared"))
    }
    @Test fun excludesProtocolFieldsFromLastFmSignature() {
        val params = mapOf("api_key" to "key", "method" to "auth.getToken")
        assertEquals(lastFmSignature(params, "secret"), lastFmSignature(params + mapOf("format" to "json", "callback" to "fn", "api_sig" to "old"), "secret"))
    }
    @Test fun seeksAndPausedWallTimeCannotQualifyAPlay() {
        assertFalse(shouldScrobble(200_000, 12_000, 30, 50, 240))
        assertFalse(shouldScrobble(200_000, 99_999, 30, 50, 240))
        assertTrue(shouldScrobble(200_000, 100_000, 30, 50, 240))
    }
    @Test fun longTracksQualifyAtConfiguredMaximum() {
        assertFalse(shouldScrobble(900_000, 239_999, 30, 50, 240))
        assertTrue(shouldScrobble(900_000, 240_000, 30, 50, 240))
    }
    @Test fun shortOrUnknownDurationTracksAreNotScrobbled() {
        assertFalse(shouldScrobble(30_000, 30_000, 30, 50, 240))
        assertFalse(shouldScrobble(0, 900_000, 30, 50, 240))
        assertFalse(shouldScrobble(60_000, -1, 30, 50, 240))
    }
    @Test fun configuredProgressThresholdIsRespected() {
        assertFalse(shouldScrobble(120_000, 60_000, 30, 75, 240))
        assertTrue(shouldScrobble(120_000, 90_000, 30, 75, 240))
        assertFalse(shouldScrobble(60_000, 60_000, 60, 50, 240))
    }
    @Test fun downloadPathsAreStableAndCannotTraverseStorage() {
        val key = downloadKey("yt_../track?x=/../../secret")
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
        assertEquals(key, downloadKey("yt_../track?x=/../../secret"))
        assertNotEquals(downloadKey("yt_song"), downloadKey("song"))
    }
    @Test fun credentialsNeverAppearInStateStringRepresentations() {
        val fm = LastFmConnection(apiKey = "api-secret", secret = "shared-secret", session = "session-secret")
        val youtube = YouTubeConnection("id", "name", "cookie-secret")
        val spotify = SpotifyConnection("cookie-secret", "bearer-secret", 123)
        val state = ConnectionState(listOf(youtube), "id", spotify, fm).toString()
        listOf("api-secret", "shared-secret", "session-secret", "cookie-secret", "bearer-secret").forEach { assertFalse(state.contains(it)) }
    }
}
