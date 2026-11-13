package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.connections.*
import com.example.data.local.MusicDatabase
import com.example.data.local.TrackEntity
import com.example.data.model.MusicTrack
import com.example.data.remote.YouTubeAudioStreamService
import com.metrolist.innertubex.InnerTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import android.webkit.CookieManager
import com.example.data.local.ListeningSnapshot
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConnectionsIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = ConnectionStore.get(context)
    private var original = ConnectionState()
    @Before fun save() = runBlocking(Dispatchers.IO) { store.load(); original = store.state.value; store.change { ConnectionState() } }
    @After fun restore() = runBlocking(Dispatchers.IO) { store.change { original } }

    @Test fun credentialsAreEncryptedAndSessionsSurviveReopen() = runBlocking(Dispatchers.IO) {
        store.addYouTube("Personal", "SAPISID=fake-cookie-sensitive123; SID=fake", "visitor", "profile", "1")
        store.change { it.copy(spotify = SpotifyConnection("fake-spotify-sensitive123", "fake-bearer-sensitive123", 123),
            lastFm = LastFmConnection("fake-key-sensitive123", "fake-secret-sensitive123", "fake-session-sensitive123", "listener")) }
        val reopened = ConnectionStore(context).apply { load() }
        assertEquals(store.state.value, reopened.state.value)
        val encrypted = File(context.applicationInfo.dataDir, "shared_prefs/${ConnectionStore.PREFS}.xml").readText()
        listOf("fake-cookie-sensitive123", "fake-spotify-sensitive123", "fake-bearer-sensitive123", "fake-secret-sensitive123", "fake-session-sensitive123")
            .forEach { assertFalse(encrypted.contains(it)) }
    }
    @Test fun connectedYouTubeCredentialsNeverEnterPlaybackSession() = runBlocking(Dispatchers.IO) {
        store.addYouTube("Profile A", "SAPISID=fake-signed-in-account", "visitor", "profile-a", "0")
        assertNotNull(YouTubeAudioStreamService.discoveryAccountSession())
        val service = YouTubeAudioStreamService
        val runtime = service.javaClass.getDeclaredMethod("runtime").apply { isAccessible = true }.invoke(service)
        val engine = runtime.javaClass.getDeclaredMethod("engine", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(runtime, 320_000, false)
        val tube = engine.javaClass.getDeclaredField("innerTube").apply { isAccessible = true }.get(engine) as InnerTube
        assertTrue(tube.sessionSnapshot().cookie.isNullOrBlank())
        assertTrue(tube.sessionSnapshot().sapisid.isNullOrBlank())
        store.change { it.copy(youtube = emptyList(), selectedYouTube = "") }
        assertNull(YouTubeAudioStreamService.discoveryAccountSession())
        assertTrue(tube.sessionSnapshot().cookie.isNullOrBlank())
    }
    @Test fun selectingOrRemovingProfilesChangesOnlyMetadataIdentity() = runBlocking(Dispatchers.IO) {
        store.addYouTube("A", "SAPISID=fake-account", "v", "profile-a", "0")
        val first = store.state.value.activeYouTube!!.id
        store.addYouTube("B", "SAPISID=fake-account", "v", "profile-b", "0")
        val second = store.state.value.activeYouTube!!.id
        assertNotEquals(first, second)
        assertEquals(2, store.state.value.youtube.size)
        store.change { it.copy(selectedYouTube = first) }
        assertEquals("profile-a", YouTubeAudioStreamService.discoveryAccountSession()?.dataSyncId)
        store.change { it.copy(youtube = it.youtube.filterNot { profile -> profile.id == first }, selectedYouTube = second) }
        assertEquals("profile-b", YouTubeAudioStreamService.discoveryAccountSession()?.dataSyncId)
    }
    @Test fun parsesSpotifyLibraryAndBothTrackEnvelopeFormats() {
        val id = "1234567890123456789012"
        val items = JSONArray("""[{"item":{"_uri":"spotify:playlist:$id","data":{"__typename":"Playlist","name":"Playlist","images":{"items":[{"sources":[{"url":"small","width":64},{"url":"large","width":640}]}]}}}}]""")
        assertEquals(listOf(ConnectedCollection("spotify", id, "Playlist", "large")), parseSpotifyCollections(items))
        val song = JSONObject("""{"uri":"spotify:track:$id","name":"Song","artists":{"items":[{"profile":{"name":"Singer"}}]},"duration":{"totalMilliseconds":180000},"albumOfTrack":{"name":"Album","coverArt":{"sources":[{"url":"cover","width":600}]}}}""")
        val normal = JSONArray().put(JSONObject().put("itemV2", JSONObject().put("data", song)))
        val liked = JSONArray().put(JSONObject().put("track", JSONObject().put("_uri", "spotify:track:$id").put("data", song)))
        assertEquals(parseSpotifyTracks(normal), parseSpotifyTracks(liked))
        assertEquals("Singer", parseSpotifyTracks(normal).single().artist)
        assertEquals(180_000L, parseSpotifyTracks(liked).single().duration)
    }
    @Test fun skipsEpisodesUnavailableTracksAndSpoofedIds() {
        assertTrue(parseSpotifyTracks(JSONArray("""[{"itemV2":{"data":{"name":"Episode","uri":"spotify:episode:invalid"}}},{"itemV2":{"data":null}}]""")).isEmpty())
        assertTrue(parseSpotifyCollections(JSONArray("""[{"item":{"_uri":"spotify:playlist:../outside","data":{"__typename":"Playlist","name":"Bad"}}}]""")).isEmpty())
    }
    @Test fun extractsOpaqueYouTubeContinuationTokens() {
        assertEquals("next-token", youtubeContinuation(JSONObject("""{"continuations":[{"nextContinuationData":{"continuation":"next-token"}}]}""")))
        assertEquals("command-token", youtubeContinuation(JSONObject("""{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"command-token"}}}}""")))
        assertNull(youtubeContinuation(JSONObject("{}")))
        assertEquals("My channel", youtubeProfileName(JSONObject("""{"header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"My channel"}]}}}}""")))
    }
    @Test fun renewedYouTubeCookiesUpdateTheExistingProfile() = runBlocking(Dispatchers.IO) {
        store.addYouTube("Name", "SAPISID=first-session", "visitor", "same-profile", "0")
        val id = store.state.value.selectedYouTube
        store.addYouTube("Renamed", "SAPISID=renewed-session", "visitor", "same-profile", "1")
        assertEquals(id, store.state.value.selectedYouTube)
        assertEquals(1, store.state.value.youtube.size)
        assertEquals("SAPISID=renewed-session", store.state.value.activeYouTube!!.cookie)
    }
    @Test fun lastFmNowPlayingUpdatesAgainAfterPauseAndResume() = runBlocking(Dispatchers.IO) {
        store.change { it.copy(lastFm = LastFmConnection("key", "secret", "session", "listener")) }
        val first = CompletableDeferred<Unit>(); val resumed = CompletableDeferred<Unit>(); val count = AtomicInteger()
        val scrobbler = LastFmScrobbler(context) { _, params ->
            assertEquals("Song", params["track"])
            when (count.incrementAndGet()) { 1 -> first.complete(Unit); 2 -> resumed.complete(Unit) }
        }
        try {
            val song = MusicTrack("scrobble-test", "Song", "Artist", "Album", 180_000, "", "")
            scrobbler.accept(ListeningSnapshot("occurrence", song, emptyMap(), 1000, false, playing = true))
            withTimeout(30_000) { first.await() }
            scrobbler.accept(ListeningSnapshot("occurrence", song, emptyMap(), 1000, false, playing = false))
            scrobbler.accept(ListeningSnapshot("occurrence", song, emptyMap(), 2000, false, playing = true))
            withTimeout(30_000) { resumed.await() }
            assertEquals(2, count.get())
        } finally { scrobbler.close() }
    }
    @Test fun browserLoginCookiesCannotEnterTheDefaultPlaybackProfile() = runBlocking {
        val complete = CompletableDeferred<Unit>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (!ConnectionBrowserProfile.isSupported()) {
                try { ConnectionBrowserProfile.cookies(); fail("An unsupported WebView must not fall back to the shared cookie jar") }
                catch (_: IllegalStateException) { complete.complete(Unit) }
            } else {
                val origin = "https://music.youtube.com"
                val shared = CookieManager.getInstance()
                val accounts = ConnectionBrowserProfile.cookies()
                shared.setCookie(origin, "profile_isolation_test=anonymous; Path=/; Secure") {
                    accounts.setCookie(origin, "profile_isolation_test=connected; Path=/; Secure") {
                        try {
                            assertTrue(shared.getCookie(origin).contains("profile_isolation_test=anonymous"))
                            assertFalse(shared.getCookie(origin).contains("profile_isolation_test=connected"))
                            assertTrue(accounts.getCookie(origin).contains("profile_isolation_test=connected"))
                            complete.complete(Unit)
                        } catch (error: Throwable) { complete.completeExceptionally(error) }
                        finally { shared.setCookie(origin, "profile_isolation_test=; Max-Age=0; Path=/; Secure")
                            accounts.setCookie(origin, "profile_isolation_test=; Max-Age=0; Path=/; Secure") }
                    }
                }
            }
        }
        withTimeout(180_000) { complete.await() }
    }
    @Test fun importingAgainRefreshesOnePlaylistAndPreservesLikesAndOccurrences() = runBlocking(Dispatchers.IO) {
        val db = MusicDatabase.getDatabase(context)
        val dao = db.musicDao()
        val library = ConnectedLibraries(context)
        val collection = ConnectedCollection("spotify", "test-import", "Imported playlist")
        val track = MusicTrack("connection-test-track", "Song", "Artist", "Album", 180_000, "", "https://example.test/song")
        try {
            dao.mergeTrackMetadata(TrackEntity.fromMusicTrack(track.copy(isLiked = true)))
            dao.updateFavorite(track.id, true)
            library.saveImport(collection, "test-account", listOf(track, track))
            val firstId = dao.importedPlaylistId("spotify", "test-account", "test-import")!!
            val first = dao.getAllPlaylistsSync().single { it.playlistId == firstId }
            assertEquals("Imported from Spotify", first.description)
            assertEquals(2, dao.getPlaylistWithTracks(first.playlistId).first()!!.musicTracks.size)
            library.saveImport(collection, "test-account", listOf(track))
            val againId = dao.importedPlaylistId("spotify", "test-account", "test-import")!!
            assertEquals(firstId, againId)
            assertEquals(1, dao.getPlaylistWithTracks(againId).first()!!.musicTracks.size)
            assertTrue(dao.getTrackById(track.id)!!.isLiked)
            dao.deletePlaylist(againId)
            assertNull(dao.importedPlaylistId("spotify", "test-account", "test-import"))
        } finally { dao.importedPlaylistId("spotify", "test-account", "test-import")?.let { dao.deletePlaylist(it) }
            dao.updateFavorite(track.id, false) }
    }
}
