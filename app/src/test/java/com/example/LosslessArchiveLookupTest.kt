package com.example

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.data.local.MusicDao
import com.example.data.model.MusicTrack
import com.example.data.model.toMediaItem
import com.example.data.remote.InternetArchiveApiService.LosslessSearchPage
import com.example.data.repository.MusicRepository
import com.example.playback.*
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@Suppress("DEPRECATION")
class LosslessArchiveLookupTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val track = MusicTrack("online_song", "Song (Official Audio)", "Artist", "Album", 180_000,
        "cover", "https://aac.saavncdn.com/song_320.mp4", source = "JIOSAAVN", bitrateKbps = 320)
    private val archive = track.copy(id = "ia_album:song.flac", title = "Song", source = "INTERNET_ARCHIVE",
        audioUrl = "https://archive.org/download/album/song.flac", isLossless = true, qualityBadge = "Lossless")
    private val dao = Proxy.newProxyInstance(MusicDao::class.java.classLoader, arrayOf(MusicDao::class.java)) { _, method, _ ->
        error("Archive discovery must not query Room: ${method.name}")
    } as MusicDao

    @Before fun ready() = runBlocking {
        clear() // No process-wide monitor may retain another Robolectric Application.
        com.example.data.repository.StreamResolver.clearVerifiedLosslessCache()
        app.getSharedPreferences("xtreme_audio_quality_prefs", 0).edit().clear().commit()
        com.example.data.local.PlaybackSourcesPreferences.save(app, PlaybackSource.builtIns)
        withTimeout(3_000) {
            while (com.example.data.local.PlaybackSourcesPreferences.load(app) != PlaybackSource.builtIns) delay(10)
        }
        connect(wifi = true)
    }

    @Test fun disabledArchiveCannotIssueRequestsEvenWithMaximumSelected() = runBlocking {
        val sources = PlaybackSource.builtIns.map { it.copy(enabled = it.kind == PlaybackSourceKind.YOUTUBE) }
        com.example.data.local.PlaybackSourcesPreferences.save(app, sources)
        withTimeout(3_000) {
            while (com.example.data.local.PlaybackSourcesPreferences.load(app) != sources) delay(10)
        }
        val repo = MusicRepository(dao, app) { _, _ -> error("Disabled Archive must never be searched") }
        assertEquals(AudioQualityCeiling.MAX, NetworkQualityMonitor.getInstance(app).snapshot().effectiveCeiling)
        assertNull(repo.findLosslessUpgrade(track))
        assertEquals(LosslessDiagnostics.Reason.NETWORK_POLICY_BLOCKED, LosslessDiagnostics.last(track.id)?.reason)
    }

    @After fun clear() = runBlocking {
        com.example.data.repository.StreamResolver.clearVerifiedLosslessCache()
        val monitor = NetworkQualityMonitor.getInstance(app)
        val scope = NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(monitor) as CoroutineScope
        scope.coroutineContext[Job]?.cancelAndJoin()
        NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun connect(wifi: Boolean, metered: Boolean = false) {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val type = if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE
        val network = ShadowNetwork.newInstance(type)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addTransportType(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
            if (!metered) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            else removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        shadow.addNetwork(network, info)
        shadow.setActiveNetworkInfo(info)
        shadow.setDefaultNetworkActive(true)
        shadow.setNetworkCapabilities(network, caps)
        shadow.networkCallbacks.toList().forEach { it.onAvailable(network); it.onCapabilitiesChanged(network, caps) }
    }

    @Test fun cappedWifiOrCellularProfilesCannotIssueArchiveRequests() = runBlocking {
        val repo = MusicRepository(dao, app) { _, _ -> fail("A capped profile must not query Archive"); LosslessSearchPage() }
        for (wifi in listOf(true, false)) {
            connect(wifi)
            for (quality in listOf(AudioQualityCeiling.HIGH, AudioQualityCeiling.NORMAL, AudioQualityCeiling.DATA_SAVER)) {
                AudioQualityPreferences.setWifiQualityCeiling(app, if (wifi) quality else AudioQualityCeiling.MAX)
                AudioQualityPreferences.setCellularQualityCeiling(app, if (wifi) AudioQualityCeiling.MAX else quality)
                assertEquals("The requested capped profile must be authoritative before discovery", quality,
                    NetworkQualityMonitor.getInstance(app).snapshot().effectiveCeiling)
                assertNull(repo.findLosslessUpgrade(track))
            }
        }
    }

    @Test fun bothWifiAndMobileMaximumCanFindTheExactRecordingWithoutTouchingItsCatalogIdentity() = runBlocking {
        for (wifi in listOf(true, false)) {
            connect(wifi)
            AudioQualityPreferences.setWifiQualityCeiling(app, if (wifi) AudioQualityCeiling.MAX else AudioQualityCeiling.HIGH)
            AudioQualityPreferences.setCellularQualityCeiling(app, if (wifi) AudioQualityCeiling.HIGH else AudioQualityCeiling.MAX)
            var queries = 0
            val repo = MusicRepository(dao, app) { query, size ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                assertEquals("Song artist", query)
                assertEquals(4, size)
                queries++
                LosslessSearchPage(listOf(archive.copy(artist = "Other Artist"),
                    archive.copy(title = "Song (Live)"), archive.copy(durationMs = 183_000), archive))
            }
            val found = repo.findLosslessUpgrade(track)
            assertEquals(archive, found)
            // The injected provider owns these responses. Production lookups
            // reuse verified matches; fake provider calls bypass that global cache.
            assertEquals(1, queries)
            assertEquals("online_song", track.id)
            assertEquals("JIOSAAVN", track.source)
        }
    }

    @Test fun hotspotUsesMobilePolicyAndCannotBeEnabledByAnUnusedWifiMaximumSetting() = runBlocking {
        connect(wifi = true, metered = true)
        var queries = 0
        val repo = MusicRepository(dao, app) { _, _ -> queries++; LosslessSearchPage(listOf(archive)) }
        assertNull(repo.findLosslessUpgrade(track))
        assertEquals(0, queries)
        AudioQualityPreferences.setCellularQualityCeiling(app, AudioQualityCeiling.MAX)
        assertEquals(archive, repo.findLosslessUpgrade(track))
        assertEquals(1, queries)
    }

    @Test fun networkDowngradeBetweenQueryPagesStopsFurtherArchiveRequests() = runBlocking {
        var queries = 0
        val repo = MusicRepository(dao, app) { _, _ ->
            queries++
            AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.HIGH)
            LosslessSearchPage()
        }
        assertNull(repo.findLosslessUpgrade(track))
        assertEquals(1, queries)
    }

    @Test fun responseFromOldMaximumProfileCannotStartPrebufferAfterQualityIsLowered() = runBlocking {
        val repo = MusicRepository(dao, app) { _, _ ->
            AudioQualityPreferences.setWifiQualityCeiling(app, AudioQualityCeiling.HIGH)
            LosslessSearchPage(listOf(archive))
        }
        assertNull(repo.findLosslessUpgrade(track))
        assertNull(LosslessStreamPrebuffer.prepare(app, archive.toMediaItem(), track.durationMs,
            currentPosition = { error("Capped profiles must not reach player access or prebuffering") },
            stillCurrent = { error("Reject capped profiles before requesting Main-thread player work") }))
    }

    @Test fun confirmedMissesAreCachedButCancelledRequestsReleaseTheWorkerWithoutPoisoningMatches() = runBlocking {
        var queries = 0
        val misses = MusicRepository(dao, app) { _, _ -> queries++; LosslessSearchPage() }
        assertNull(misses.findLosslessUpgrade(track))
        assertEquals(2, queries)
        assertNull(misses.findLosslessUpgrade(track))
        assertEquals(2, queries)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        assertNull(misses.findLosslessUpgrade(track))
        assertEquals(4, queries)

        val entered = CompletableDeferred<Unit>()
        var cancelFirst = true
        val repo = MusicRepository(dao, app) { _, _ ->
            if (cancelFirst) { entered.complete(Unit); awaitCancellation() }
            LosslessSearchPage(listOf(archive))
        }
        val request = launch { repo.findLosslessUpgrade(track) }
        withTimeout(2000) { entered.await() }
        request.cancelAndJoin()
        cancelFirst = false
        assertEquals(archive, withTimeout(2000) { repo.findLosslessUpgrade(track) })
    }
    @Test fun incompleteMetadataUsesOnlyAShortFailureCacheAndDoesNotHideALaterVerifiedRecording() = runBlocking {
        var queries = 0
        var available = false
        val repo = MusicRepository(dao, app) { _, _ ->
            queries++
            if (available) LosslessSearchPage(listOf(archive))
            else LosslessSearchPage(isSuccessful = false, failure = LosslessDiagnostics.Reason.NETWORK_TIMEOUT)
        }
        assertNull(repo.findLosslessUpgrade(track))
        assertEquals(2, queries)
        assertEquals(LosslessDiagnostics.Reason.NETWORK_TIMEOUT, LosslessDiagnostics.last(track.id)?.reason)
        assertNull(repo.findLosslessUpgrade(track))
        assertEquals(2, queries)
        available = true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        assertEquals(archive, repo.findLosslessUpgrade(track))
        assertEquals(3, queries)
    }
}
