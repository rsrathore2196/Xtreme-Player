package com.example

import android.app.Application
import android.content.Context
import android.media.MediaFormat
import android.net.*
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.PlaybackSourcesPreferences
import com.example.data.model.MusicTrack
import com.example.data.remote.StreamingAddons
import com.example.playback.*
import com.example.service.MusicCache
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
@Suppress("DEPRECATION")
class AdaptiveLosslessPreparationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: HttpServer
    private lateinit var item: MediaItem
    private val requests = CopyOnWriteArrayList<String>()

    @Before fun setup() = runBlocking {
        AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.MAX)
        val manager = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        manager.clearAllNetworks()
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED); addTransportType(NetworkCapabilities.TRANSPORT_WIFI) }
        manager.addNetwork(network, info); manager.setActiveNetworkInfo(info)
        manager.setDefaultNetworkActive(true); manager.setNetworkCapabilities(network, caps)
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createAudioFormat(MimeTypes.AUDIO_FLAC, 44100, 1)).setIsEncoder(false).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("OMX.google.flac.decoder")
            .setIsEncoder(false).setCapabilities(capabilities).build())
        MediaCodecUtil.clearDecoderInfoCache()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.add(exchange.requestURI.path)
                val file = if (exchange.requestURI.path == "/play") "manifest.mpd" else exchange.requestURI.path.removePrefix("/")
                val bytes = this@AdaptiveLosslessPreparationTest.javaClass.getResourceAsStream("/audio/adaptive-lossless/$file")!!.use { it.readBytes() }
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }; exchange.close()
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val source = PlaybackSource(PlaybackSource.addonId(base), PlaybackSourceKind.ADDON, "Test server", baseUrl = base)
        val sources = listOf(source) + PlaybackSource.builtIns
        PlaybackSourcesPreferences.save(app, sources)
        withTimeout(3000) { while (PlaybackSourcesPreferences.load(app) != sources) delay(10) }
        val song = MusicTrack("yt_abcdefghijk", "Song", "Artist", "Album", 12000, "", "")
        val resolved = StreamingAddons.parseStream("""{"url":"$base/play","format":"flac","manifest":"dash"}""",
            base, song, song, PlaybackSourcePolicy(AudioQualityCeiling.MAX, sources = sources))!!
        item = MediaItem.Builder().setMediaId(song.id).setUri(resolved.audioUrl).setMimeType(StreamingAddons.mime(resolved.audioUrl)).build()
    }
    @After fun cleanup() = runBlocking {
        server.stop(0)
        withContext(Dispatchers.IO) {
            MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }.let {
                (it.get(null) as? SimpleCache)?.release(); it.set(null, null)
            }
        }
        val field = NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? NetworkQualityMonitor)?.let {
            (NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel()
        }
        field.set(null, null)
    }
    private suspend fun prepare(duration: Long = 12000, current: Boolean = true): Boolean = coroutineScope {
        val job = async(Dispatchers.IO) { LosslessStreamPrebuffer.prepareAdaptiveSource(app, item, duration, { current }) }
        withTimeout(20000) { while (!job.isCompleted) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10)); delay(10) } }
        job.await()
    }
    @Test fun media3PreparesRealFlacDashInitializationWithoutASecondPlayer() = runBlocking {
        assertTrue(LosslessStreamPrebuffer.isAdaptiveSource(item))
        val prepared = prepare()
        assertTrue("Adaptive preparation failed: ${LosslessDiagnostics.last(item.mediaId)}; requested $requests", prepared)
        assertTrue(requests.contains("/play"))
        assertTrue("Actual initialization bytes must be read", requests.contains("/init.m4s"))
        assertFalse("A different recording duration cannot activate", prepare(60000))
    }
    @Test fun staleSelectionNeverStartsAdaptiveNetworkPreparation() = runBlocking {
        assertFalse(prepare(current = false))
        assertTrue(requests.isEmpty())
    }
}
