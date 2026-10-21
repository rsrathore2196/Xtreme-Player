package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.ReactiveSettingsRepository
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.playback.AudioQualityCeiling
import com.example.playback.PlaybackSourcePolicy
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StreamingAddonIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: ReactiveSettingsRepository
    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val requests = CopyOnWriteArrayList<String>()
    private var stream: () -> String = { "" }
    private val song = MusicTrack("online_song", "Song", "Artist", "Album", 180_000, "cover", "https://aac.saavncdn.com/song_320.mp4")
    private val maximum = PlaybackSourcePolicy(AudioQualityCeiling.MAX)

    @Before fun setup() = runBlocking {
        withContext(Dispatchers.IO) { app.getSharedPreferences("xtreme_other_settings", 0).edit().clear().commit() }
        repository = ReactiveSettingsRepository(app)
        repository.preload()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = repository.preferences(name)
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a/token/") { exchange ->
                requests.add(exchange.requestURI.toString())
                val response = when {
                    exchange.requestURI.path.endsWith("manifest.json") -> """{"id":"personal","name":"My Music","resources":["search","stream"]}"""
                    exchange.requestURI.path.endsWith("search") -> """{"tracks":[
                        {"id":"wrong","title":"Song (Live)","artist":"Artist","album":"Album","duration":180},
                        {"id":"right","title":"Song","artist":"Artist","album":"Album","duration":180},
                        {"id":"missing","title":"Unknown"}]}"""
                    exchange.requestURI.path.endsWith("stream/right") -> stream()
                    else -> "{}"
                }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }; exchange.close()
            }
            start()
        }
        base = "http://127.0.0.1:${server.address.port}/a/token"
        stream = { """{"stream":{"url":"$base/song.flac","codec":"flac","format":"flac","bitrate":1411200}}""" }
    }
    @After fun cleanup() = runBlocking {
        repository.awaitPendingWrites(); repository.close(); server.stop(0)
    }
    private fun enable() {
        OtherSettingsPreferences.saveStreamingAddon(context, base, "My Music")
        OtherSettingsPreferences.setStreamingAddonEnabled(context, true)
    }

    @Test fun manifestConnectionNormalizesUrlAndSavingDoesNotEnableStreaming() = runBlocking {
        val manifest = StreamingAddons.connect("$base/manifest.json")
        assertEquals("My Music", manifest.name); assertEquals(base, manifest.baseUrl)
        assertEquals(listOf("/a/token/manifest.json"), requests.toList())
        OtherSettingsPreferences.saveStreamingAddon(context, manifest.baseUrl, manifest.name)
        assertFalse(OtherSettingsPreferences.playbackIntegrations(context).addonEnabled)
        assertFalse(OtherSettingsPreferences.playbackIntegrations(context).keepLyricsAwake)
        assertTrue(StreamingAddons.search(context, "Song", maximum).isEmpty())
        assertNull(StreamingAddons.resolve(context, song, maximum))
        assertEquals(1, requests.size)
    }

    @Test fun realSearchAndStreamRequestsVerifyIdentityAndDeduplicatePlaybackLookups() = runBlocking {
        enable()
        val results = coroutineScope { (0..7).map { async(Dispatchers.IO) {
            StreamingAddons.resolve(context, song, maximum)
        } }.awaitAll() }
        assertTrue(results.all { it?.audioUrl == "$base/song.flac" })
        assertTrue(results.all { it?.id == song.id && it.coverUrl == song.coverUrl })
        assertTrue(results.all { it?.source == "Streaming Addon: My Music" })
        assertEquals(1, requests.count { it.contains("/search?") })
        assertEquals(1, requests.count { it.contains("/stream/right?") })
        assertFalse(requests.any { it.contains("stream/wrong") })
        assertTrue(requests.all { "quality=hi_res" in it && "atmos=none" in it })
        assertNotNull(StreamingAddons.resolve(context, song, maximum))
        assertEquals(2, requests.size)
    }

    @Test fun customSearchResultKeepsItsProviderAndResolvesItsOwnIdWithoutAnotherSearch() = runBlocking {
        enable()
        val tracks = StreamingAddons.search(context, "Song", maximum)
        assertEquals(2, tracks.size)
        val selected = tracks.single { it.title == "Song" }
        assertTrue(selected.id.startsWith("addon_")); assertEquals("Artist", selected.artist)
        val played = StreamingAddons.resolve(context, selected, maximum)!!
        assertEquals(selected.id, played.id)
        assertEquals("$base/song.flac", played.audioUrl)
        assertEquals(1, requests.count { it.contains("/search?") })
    }

    @Test fun lossyQualityCeilingsRejectAnOversizedOrUnknownBitrateWithoutMislabelingIt() = runBlocking {
        enable()
        val policy = PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER)
        assertNull(StreamingAddons.resolve(context, song, policy))
        assertTrue(requests.all { "quality=low" in it })
        stream = { """{"data":{"url":"$base/song.aac","codec":"aac","format":"aac","bitrate":96000}}""" }
        val another = song.copy(id = "online_another")
        val aac = StreamingAddons.resolve(context, another, policy)!!
        assertEquals(96, aac.bitrateKbps); assertFalse(aac.isLossless)
        assertTrue(policy.acceptsRemoteTrack(aac))
        assertFalse(PlaybackSourcePolicy(AudioQualityCeiling.DATA_SAVER).acceptsRemoteTrack(aac.copy(audioUrl = "$base/unregistered.aac")))
    }

    @Test fun disablingAddonDuringLookupPreventsLateResultsFromBeingActivated() = runBlocking {
        enable()
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        stream = { entered.complete(Unit); runBlocking { released.await() }
            """{"url":"$base/late.flac","codec":"flac","format":"flac"}""" }
        try {
            val playback = async(Dispatchers.IO) { StreamingAddons.resolve(context, song, maximum) }
            withTimeout(3000) { entered.await() }
            OtherSettingsPreferences.setStreamingAddonEnabled(context, false)
            released.complete(Unit)
            assertNull(withTimeout(3000) { playback.await() })
        } finally { released.complete(Unit) }
    }

    @Test fun maximumFallsBackFromUnavailableHiResTierToTheSameLosslessRecording() = runBlocking {
        enable()
        stream = { if (requests.last().contains("quality=hi_res")) "{}" else
            """{"url":"$base/cd.flac","codec":"flac","format":"flac","bitrate":1411200}""" }
        val played = StreamingAddons.resolve(context, song, maximum)!!
        assertEquals("$base/cd.flac", played.audioUrl)
        assertTrue(played.isLossless)
        assertEquals(2, requests.count { it.contains("stream/right") })
        assertTrue(requests.last().contains("quality=lossless"))
    }

    @Test fun dashManifestCodecUnknownsHeaderScopeAndExpiredUrlsAreHandledAccurately() {
        val candidate = song.copy(source = "Streaming Addon: My Music")
        val body = JSONObject().put("url", "$base/audio/manifest.mpd").put("format", "dash")
            .put("headers", JSONObject().put("Authorization", "Bearer personal-token")).toString()
        val dash = StreamingAddons.parseStream(body, base, song, candidate, maximum)!!
        assertEquals(MimeTypes.APPLICATION_MPD, StreamingAddons.mime(dash.audioUrl))
        assertFalse(dash.isLossless); assertEquals(0, dash.bitrateKbps)
        assertEquals("Bearer personal-token", StreamingAddons.headers("$base/audio/chunk-1.m4s")["Authorization"])
        assertTrue(StreamingAddons.headers("https://another.example/audio/chunk-1.m4s").isEmpty())
        assertTrue(StreamingAddons.headers("$base/private/unrelated").isEmpty())
        // An active segmented song can outlive the lookup cache. Do not strip
        // its headers, while still refusing to reuse an expired URL for new play.
        @Suppress("UNCHECKED_CAST")
        val registry = StreamingAddons::class.java.getDeclaredField("transports").apply { isAccessible = true }
            .get(StreamingAddons) as Map<String, Any>
        val lease = registry.getValue(dash.audioUrl)
        lease.javaClass.getDeclaredField("expires").apply { isAccessible = true }.setLong(lease, 0L)
        assertFalse(StreamingAddons.isVerified(dash.audioUrl, maximum))
        assertEquals("Bearer personal-token", StreamingAddons.headers("$base/audio/chunk-2.m4s")["Authorization"])
        val expired = JSONObject().put("url", "$base/song.flac?expire=1").toString()
        assertNull(StreamingAddons.parseStream(expired, base, song, candidate, maximum))
        assertNull(StreamingAddons.parseStream("""{"encrypted":true,"url":"$base/protected.flac"}""", base, song, candidate, maximum))
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>$base/inline/</BaseURL></MPD>"""
        val inline = StreamingAddons.parseStream(JSONObject().put("manifestXml", xml)
            .put("headers", JSONObject().put("Authorization", "Bearer inline")).toString(), base, song, candidate, maximum)!!
        assertTrue(inline.audioUrl.startsWith("data:application/dash+xml;base64,"))
        assertEquals("Bearer inline", StreamingAddons.headers("$base/inline/chunk-1.m4s")["Authorization"])
    }
}
