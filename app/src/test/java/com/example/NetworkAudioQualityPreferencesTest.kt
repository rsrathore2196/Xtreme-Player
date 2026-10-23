package com.example

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioQualityPreferences
import com.example.playback.AudioQuality
import com.example.playback.AudioQualityCeiling
import com.example.playback.NetworkConnectionType
import com.example.playback.NetworkQualityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@Suppress("DEPRECATION")
class NetworkAudioQualityPreferencesTest {
    @Test fun testAudioRouting3TierHierarchy() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("xtreme_audio_quality_prefs", 0).edit().clear().commit()
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        fun connect(wifi: Boolean, metered: Boolean = false) {
            val type = if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE
            val network = ShadowNetwork.newInstance(type)
            val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true)
            val caps = ShadowNetworkCapabilities.newInstance()
            val capabilities = shadowOf(caps)
            capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            capabilities.addTransportType(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
            if (!metered) capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            else capabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            shadow.addNetwork(network, info)
            shadow.setActiveNetworkInfo(info)
            shadow.setDefaultNetworkActive(true)
            shadow.setNetworkCapabilities(network, caps)
            shadow.networkCallbacks.toList().forEach { it.onAvailable(network); it.onCapabilitiesChanged(network, caps) }
        }
        connect(wifi = true)
        val monitor = NetworkQualityMonitor.getInstance(app)
        val collector = launch(Dispatchers.IO) { monitor.activeQualityCeiling.collect { } }
        suspend fun awaitProfile(type: NetworkConnectionType, ceiling: AudioQualityCeiling) {
            withTimeout(5_000) {
                while (monitor.activeQualityCeiling.value.let { it.connectionType != type || it.effectiveCeiling != ceiling }) {
                    // SharedPreferences delivers change notifications on Android's Main looper.
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
        try {
            awaitProfile(NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET, AudioQualityCeiling.MAX)
            assertTrue(monitor.allowsLosslessUpgrade)
            AudioQualityPreferences.setSelectedQuality(app, AudioQuality.ULTRA_HD_320)
            awaitProfile(NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET, AudioQualityCeiling.HIGH)
            assertEquals(AudioQuality.ULTRA_HD_320, AudioQualityPreferences.getSelectedQuality(app))
            assertEquals(AudioQualityCeiling.HIGH, AudioQualityPreferences.getNetworkSettings(app).wifiQualityCeiling)
            AudioQualityPreferences.setSelectedQuality(app, AudioQuality.HI_RES_LOSSLESS)
            awaitProfile(NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET, AudioQualityCeiling.MAX)
            assertEquals(AudioQuality.HI_RES_LOSSLESS, AudioQualityPreferences.getSelectedQuality(app))

            monitor.setWifiCeiling(AudioQualityCeiling.DATA_SAVER)
            awaitProfile(NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET, AudioQualityCeiling.DATA_SAVER)
            assertEquals(AudioQuality.MEDIUM_96, AudioQualityPreferences.getSelectedQuality(app))
            assertEquals(96_000, monitor.sourcePolicySnapshot().ceiling.maxBitrateBps)
            monitor.setWifiCeiling(AudioQualityCeiling.MAX)
            awaitProfile(NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET, AudioQualityCeiling.MAX)

            connect(wifi = true, metered = true)
            awaitProfile(NetworkConnectionType.METERED_WIFI, AudioQualityCeiling.HIGH)
            assertFalse(monitor.allowsLosslessUpgrade)
            monitor.setProtectHotspots(false)
            awaitProfile(NetworkConnectionType.METERED_WIFI, AudioQualityCeiling.MAX)
            assertTrue(monitor.allowsLosslessUpgrade)
            monitor.setProtectHotspots(true)
            awaitProfile(NetworkConnectionType.METERED_WIFI, AudioQualityCeiling.HIGH)

            connect(wifi = false, metered = true)
            awaitProfile(NetworkConnectionType.CELLULAR, AudioQualityCeiling.HIGH)
            AudioQualityPreferences.setSelectedQuality(app, AudioQuality.HI_RES_LOSSLESS)
            awaitProfile(NetworkConnectionType.CELLULAR, AudioQualityCeiling.MAX)
            assertTrue(monitor.allowsLosslessUpgrade)
            assertEquals(AudioQualityCeiling.MAX, AudioQualityPreferences.getNetworkSettings(app).cellularQualityCeiling)
            monitor.setCellularCeiling(AudioQualityCeiling.NORMAL)
            awaitProfile(NetworkConnectionType.CELLULAR, AudioQualityCeiling.NORMAL)
            assertEquals(AudioQuality.HIGH_160, AudioQualityPreferences.getSelectedQuality(app))
            assertFalse(monitor.allowsLosslessUpgrade)
            monitor.setCellularCeiling(AudioQualityCeiling.DATA_SAVER)
            awaitProfile(NetworkConnectionType.CELLULAR, AudioQualityCeiling.DATA_SAVER)
            assertEquals(AudioQuality.MEDIUM_96, AudioQualityPreferences.getSelectedQuality(app))
            assertFalse(monitor.allowsLosslessUpgrade)
            assertEquals(AudioQualityCeiling.MAX, AudioQualityPreferences.getNetworkSettings(app).wifiQualityCeiling)
        } finally {
            collector.cancelAndJoin()
            val scope = NetworkQualityMonitor::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(monitor) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
            NetworkQualityMonitor::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }
}
