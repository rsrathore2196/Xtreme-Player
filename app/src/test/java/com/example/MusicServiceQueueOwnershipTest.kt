package com.example

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.MusicDao
import com.example.data.local.PlaybackSourcesPreferences
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.data.remote.InternetArchiveApiService.LosslessSearchPage
import com.example.data.repository.MusicRepository
import com.example.data.repository.StreamResolver
import com.example.playback.*
import com.example.service.MusicService
import com.example.service.PolicyResolvingMediaSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers

/** Real service -> repository -> resolver boundary; only remote providers are fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Suppress("DEPRECATION")
class MusicServiceQueueOwnershipTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val selected = MusicTrack("online_song", "Song", "Artist", "Album", 180_000, "",
        "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN", bitrateKbps = 320)
    private val archive = selected.copy(id = "ia_album:song.flac", source = "INTERNET_ARCHIVE", isLossless = true,
        audioUrl = "https://archive.org/download/album/song.flac", qualityBadge = "Lossless")
    private lateinit var lookupScope: CoroutineScope
    private lateinit var resolver: StreamResolver
    private lateinit var repository: MusicRepository
    private lateinit var service: MusicService
    private lateinit var monitor: NetworkQualityMonitor
    private val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()
    private val requests = AtomicInteger()
    private val sources = mutableListOf<Pair<MediaSource, MediaSource.MediaSourceCaller>>()
    private var standardAvailable = true
    private var standardGate: CompletableDeferred<Unit>? = null
    private var archiveDelayMs: Long? = null

    @Before fun setup() = runBlocking {
        StreamResolver.clearVerifiedLosslessCache()
        PlaybackSourcesPreferences.save(app, PlaybackSource.builtIns)
        AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.MAX)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val capabilities = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        shadowOf(connectivity).apply {
            clearAllNetworks()
            val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, true)
            addNetwork(network, info); setActiveNetworkInfo(info); setDefaultNetworkActive(true)
            setNetworkCapabilities(network, capabilities)
        }
        monitor = NetworkQualityMonitor.getInstance(app)
        withTimeout(3_000) {
            while (PlaybackSourcesPreferences.load(app) != PlaybackSource.builtIns) delay(5)
            // Finish the monitor's initial preference readers before a following
            // case can replace Robolectric's application/shared-preference clock.
            while (!monitor.sourcePolicy.value.ready || monitor.sourcePolicy.value.key != monitor.sourcePolicySnapshot().key) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }
        assertTrue(monitor.sourcePolicySnapshot().allowsArchive)
        lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        resolver = StreamResolver(lookupScope = lookupScope, supportsLossless = { true },
            searchStandard = { _, _ -> standardGate?.await(); if (standardAvailable) listOf(selected) else emptyList() },
            fetchById = { _, _ -> standardGate?.await(); selected.takeIf { standardAvailable } },
            searchYouTube = { emptyList() }, resolveYouTube = { _, _, _, _ -> null },
            archiveAllowed = { monitor.sourcePolicySnapshot().key == it.key },
            findLossless = { _, _, allowed ->
                assertNotEquals("Archive work must stay off Main", Looper.getMainLooper(), Looper.myLooper())
                requests.incrementAndGet(); entered.complete(Unit)
                try {
                    archiveDelayMs?.let { delay(it) } ?: released.await()
                    assertTrue("A surviving owner must still allow the recording", allowed())
                    LosslessSearchPage(listOf(archive))
                } catch (error: CancellationException) { cancelled.complete(Unit); throw error }
            })
        val dao = Proxy.newProxyInstance(MusicDao::class.java.classLoader, arrayOf(MusicDao::class.java)) { _, method, _ ->
            error("Queue preparation must not query Room: ${method.name}")
        } as MusicDao
        repository = MusicRepository(dao, app)
        ReflectionHelpers.setField(repository, "streamResolver", resolver)
        service = Robolectric.buildService(MusicService::class.java).get()
        // Use the actual service method without running unrelated Application startup jobs.
        val application = XtremeMusicApp()
        ReflectionHelpers.setField(application, "repository\$delegate", lazyOf(repository))
        ReflectionHelpers.setField(service, "mApplication", application)
    }

    @After fun cleanup() = runBlocking {
        sources.forEach { (source, caller) -> source.releaseSource(caller) }
        if (::resolver.isInitialized) resolver.clear()
        if (::lookupScope.isInitialized) lookupScope.coroutineContext[Job]?.cancelAndJoin()
        if (::repository.isInitialized) ReflectionHelpers.getField<CoroutineScope>(repository, "initialCatalogScope").coroutineContext[Job]?.cancelAndJoin()
        if (::service.isInitialized) ReflectionHelpers.getField<CoroutineScope>(service, "serviceScope").coroutineContext[Job]?.cancelAndJoin()
        if (::monitor.isInitialized) ReflectionHelpers.getField<CoroutineScope>(monitor, "scope").coroutineContext[Job]?.cancelAndJoin()
        ReflectionHelpers.setStaticField(NetworkQualityMonitor::class.java, "instance", null)
        StreamResolver.clearVerifiedLosslessCache()
    }

    private suspend fun resolveQueued(item: MediaItem): MediaItem = suspendCoroutineUninterceptedOrReturn { continuation ->
        try {
            MusicService::class.java.getDeclaredMethod("resolveQueuedSource", MediaItem::class.java, Continuation::class.java)
                .apply { isAccessible = true }.invoke(service, item, continuation)
        } catch (error: InvocationTargetException) { throw error.targetException }
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(4_000) {
        while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
    }

    @Test fun serviceQueueItemsRetainTheRecordingMetadataRequiredForArchiveEligibility() {
        val item = selected.copy(isrc = "USABC2400001").toMediaItem()
        assertEquals(selected.durationMs, item.mediaMetadata.extras!!.getLong("duration"))
        assertEquals("USABC2400001", item.mediaMetadata.extras!!.getString("isrc"))
    }

    @Test fun releasingAnActuallyPreparedQueueSourceCannotLeaveSpeculativeArchiveWorkRunning() = runBlocking {
        val policy = monitor.sourcePolicySnapshot()
        var timeline: Timeline? = null
        val transport = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app)
        val source = PolicyResolvingMediaSource(selected.toMediaItem(), transport, lookupScope,
            resolvedIsCurrent = { it.mediaMetadata.extras?.getString("source_policy_key") == monitor.sourcePolicySnapshot().key },
            resolve = ::resolveQueued)
        val caller = MediaSource.MediaSourceCaller { _, value -> timeline = value }
        source.prepareSource(caller, null); sources.add(source to caller)
        until { source.maybeThrowSourceInfoRefreshError(); entered.isCompleted }
        until { timeline != null }
        assertEquals(selected.id, source.mediaItem.mediaId)
        assertEquals(policy.key, source.mediaItem.mediaMetadata.extras!!.getString("source_policy_key"))
        source.releaseSource(caller); sources.clear()
        withTimeout(2_000) { cancelled.await() }
        assertFalse(resolver.hasPendingLosslessDiscovery(selected.id, policy.key))
        assertEquals(1, requests.get())
    }

    @Test fun speculativeServiceResolutionSharesAnAudibleLookupsResultWithoutOwningItsLifetime() = runBlocking {
        val audible = async(Dispatchers.IO) { repository.resolvePlayableTrack(selected) }
        withTimeout(3_000) { entered.await() }
        val queued = async(Dispatchers.IO) { resolveQueued(selected.toMediaItem()) }
        val prepared = withTimeout(4_000) { queued.await() }
        assertEquals(selected.id, prepared.mediaId)
        assertEquals(selected.audioUrl, prepared.localConfiguration!!.uri.toString())
        assertEquals("The service's speculative reader must join the existing Archive flight", 1, requests.get())
        assertFalse(cancelled.isCompleted)
        assertEquals(selected.audioUrl, audible.await().audioUrl)
        released.complete(Unit)
        until { StreamResolver.getCachedLosslessMatch(selected) != null }
        assertEquals(archive.audioUrl, StreamResolver.getCachedLosslessMatch(selected)!!.audioUrl)
        assertEquals(1, requests.get())
    }

    @Test fun removingAnUnresolvedQueueSourceCancelsItsLastReaderAndCannotPublishALateTimeline() = runBlocking {
        standardGate = CompletableDeferred()
        var timeline: Timeline? = null
        val source = PolicyResolvingMediaSource(selected.copy(audioUrl = "").toMediaItem(),
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app), lookupScope, resolve = ::resolveQueued)
        val caller = MediaSource.MediaSourceCaller { _, value -> timeline = value }
        source.prepareSource(caller, null); sources.add(source to caller)
        until { source.maybeThrowSourceInfoRefreshError(); entered.isCompleted }
        source.releaseSource(caller); sources.clear()
        withTimeout(2_000) { cancelled.await() }
        standardGate!!.complete(Unit); shadowOf(Looper.getMainLooper()).idle()
        assertNull(timeline)
        assertFalse(resolver.hasPendingLosslessDiscovery(selected.id, monitor.sourcePolicySnapshot().key))
        assertEquals(1, requests.get())
    }

    @Test fun aRequiredQueuedArchiveRecordingCanFinishVerificationWhenNoLossyFallbackExists() = runBlocking {
        standardAvailable = false
        archiveDelayMs = StreamResolver.LOSSLESS_LOOKUP_TIMEOUT_MS + 700L
        val resolved = withTimeout(6_000) { withContext(Dispatchers.IO) { resolveQueued(selected.copy(audioUrl = "").toMediaItem()) } }
        assertEquals(selected.id, resolved.mediaId)
        assertEquals(archive.audioUrl, resolved.localConfiguration!!.uri.toString())
        assertTrue(resolved.mediaMetadata.extras!!.getBoolean("isLossless"))
        assertFalse(resolved.mediaMetadata.extras!!.getBoolean("audio_actual"))
        assertEquals(1, requests.get())
    }

    @Test fun releasingAnUnresolvedServiceSourceCannotCancelAnotherAudibleOwner() = runBlocking {
        val audible = async(Dispatchers.IO) { repository.resolvePlayableTrack(selected) }
        withTimeout(3_000) { entered.await(); audible.await() }
        standardGate = CompletableDeferred()
        val source = PolicyResolvingMediaSource(selected.copy(audioUrl = "").toMediaItem(),
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app), lookupScope, resolve = ::resolveQueued)
        val caller = MediaSource.MediaSourceCaller { _, _ -> fail("A released unresolved source cannot publish a timeline") }
        source.prepareSource(caller, null); sources.add(source to caller)
        val flights = ReflectionHelpers.getField<MutableMap<*, *>>(resolver, "archiveFlights")
        until { synchronized(flights) { flights.values.any { flight ->
            ReflectionHelpers.getField<Set<*>>(flight, "readers").isNotEmpty()
        } } }
        source.releaseSource(caller); sources.clear()
        assertTrue(resolver.hasPendingLosslessDiscovery(selected.id, monitor.sourcePolicySnapshot().key))
        assertFalse(cancelled.isCompleted)
        assertEquals(1, requests.get())
        standardGate!!.complete(Unit); released.complete(Unit)
        until { StreamResolver.getCachedLosslessMatch(selected) != null }
        assertEquals(archive.audioUrl, StreamResolver.getCachedLosslessMatch(selected)!!.audioUrl)
        assertFalse(cancelled.isCompleted)
        assertEquals(1, requests.get())
    }

    @Test fun threeTracksAutomaticallyAdvanceThroughTheRealServiceResolverAndMedia3PcmOutput() = playRealQueue(0)
    @Test fun customPlaylistStartsAtTheSelectedEntryAndPlaysItBeforeAdvancing() = playRealQueue(1)

    private fun playRealQueue(startIndex: Int) = runBlocking {
        AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.HIGH)
        assertFalse(monitor.sourcePolicySnapshot().allowsArchive)
        val capabilities = org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(android.media.MediaFormat.createAudioFormat(androidx.media3.common.MimeTypes.AUDIO_RAW, 48000, 2))
            .setIsEncoder(false).build()
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
            .setName("OMX.google.raw.decoder").setIsEncoder(false).setCapabilities(capabilities).build())
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        val samples = wav()
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val prepared = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val pcmStreams = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val streamOffset = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        val audioWrites = AtomicInteger()
        val firstAudio = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        val advancing = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val audioListener = org.robolectric.shadows.ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (data.isNotEmpty()) {
                audioWrites.incrementAndGet(); pcmStreams.add(streamOffset.get())
                firstAudio.putIfAbsent(streamOffset.get(), android.os.SystemClock.elapsedRealtime())
            }
        }
        val transport = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(app).setDataSourceFactory {
            val data = androidx.media3.datasource.ByteArrayDataSource(samples)
            object : androidx.media3.datasource.DataSource by data {
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    opened.add(spec.uri.toString()); return data.open(spec)
                }
            }
        }
        val factory = object : MediaSource.Factory by transport {
            override fun createMediaSource(item: MediaItem): MediaSource = PolicyResolvingMediaSource(item, transport, lookupScope,
                resolvedIsCurrent = { it.mediaMetadata.extras?.getString("source_policy_key") == monitor.sourcePolicySnapshot().key }) {
                prepared.getOrPut(it.mediaId) { AtomicInteger() }.incrementAndGet()
                resolveQueued(it)
            }
        }
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                val actual = BitPerfectAudioSink(context, reportSignalPath = false, allowDirect = { false })
                return object : androidx.media3.exoplayer.audio.AudioSink by actual {
                    override fun setOutputStreamOffsetUs(offset: Long) {
                        streamOffset.set(offset); actual.setOutputStreamOffsetUs(offset)
                    }
                }
            }
        }.forceDisableMediaCodecAsynchronousQueueing()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(app, renderers).setMediaSourceFactory(factory).build()
        val transitions = mutableListOf<Pair<String, Int>>()
        val states = mutableListOf<Int>()
        var failure: androidx.media3.common.PlaybackException? = null
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { item?.let { transitions.add(it.mediaId to reason) } }
            override fun onPlaybackStateChanged(state: Int) { states.add(state) }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failure = error }
        })
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioPositionAdvancing(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long) { advancing.add(playoutStartSystemTimeMs) }
        })
        org.robolectric.shadows.ShadowAudioTrack.addAudioDataListener(audioListener)
        try {
            val tracks = (1..3).map { selected.copy(id = "online_queue_$it", title = "Song $it", durationMs = 600,
                playlistEntryId = if (startIndex > 0) 100L + it else 0L,
                audioUrl = "https://aac.saavncdn.com/queue_${it}_320.mp4") }
            val expected = tracks.drop(startIndex)
            player.skipSilenceEnabled = true // Exercise retained silence/EOS across all three sources too.
            player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L); player.prepare(); player.play()
            withTimeout(12_000) {
                while (player.playbackState != androidx.media3.common.Player.STATE_ENDED) {
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                    failure?.let { throw AssertionError("Actual Media3 source preparation failed; policy=${monitor.sourcePolicySnapshot().key}", it) }
                    delay(5)
                }
            }
            assertEquals(expected.map { it.id }, transitions.map { it.first })
            assertEquals(expected.size - 1, transitions.count { it.second == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO })
            assertTrue(states.contains(androidx.media3.common.Player.STATE_READY))
            assertEquals(2, player.currentMediaItemIndex)
            assertTrue(player.playWhenReady)
            assertEquals(expected.map { it.audioUrl }.toSet(), opened.toSet())
            assertTrue(prepared.values.all { it.get() == 1 })
            assertEquals(expected.size, prepared.size)
            assertTrue("AudioTrack received decoded PCM", audioWrites.get() > 0)
            assertTrue("Media3 observed its audio output clock advancing", advancing.isNotEmpty())
            assertEquals("Each source produced PCM under its own renderer stream offset", expected.size, pcmStreams.size)
            assertEquals(expected.size, firstAudio.size)
            println("Media3 queue evidence: transitions=$transitions, states=$states, firstPcmByStream=$firstAudio, " +
                "advancingCallbacks=${advancing.size}, audioWrites=${audioWrites.get()}")
            assertEquals(0, requests.get())
        } finally {
            player.release()
            org.robolectric.shadows.ShadowAudioTrack.removeAudioDataListener(audioListener)
            org.robolectric.shadows.ShadowMediaCodecList.reset()
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil.clearDecoderInfoCache()
        }
    }

    /** Generated PCM avoids remote audio, copyright material and extra test assets. */
    private fun wav(): ByteArray {
        val frames = 28_800
        return java.nio.ByteBuffer.allocate(44 + frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 4); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(48000); putInt(48000 * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(frames * 4)
            repeat(frames) { index -> repeat(2) { putShort(if (index < 9600) 4000 else 0) } }
        }.array()
    }
}
