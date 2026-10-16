package com.example.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.data.local.AudioQualityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** One application-context monitor, shared by Settings, Media3 and the resolvers. */
class NetworkQualityMonitor private constructor(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferenceWrites = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var latest = ActiveQualityState()
    val currentMaxBitrateBps: Int get() = latest.effectiveCeiling.maxBitrateBps
    val currentAudioQuality: AudioQuality get() = latest.effectiveCeiling.audioQuality
    val allowsLosslessUpgrade: Boolean get() = latest.effectiveCeiling == AudioQualityCeiling.MAX &&
        latest.connectionType != NetworkConnectionType.OFFLINE

    val preferences: StateFlow<NetworkAudioPreferences> = AudioQualityPreferences.observeNetworkSettings(app)
        .distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), NetworkAudioPreferences())

    val connectionType: StateFlow<NetworkConnectionType> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(NetworkConnectionType.OFFLINE)
            awaitClose { }
            return@callbackFlow
        }
        val active = AtomicReference<Network?>(null)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                active.set(network)
                trySend(classify(manager.getNetworkCapabilities(network)))
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == active.get() || network == manager.activeNetwork) {
                    active.set(network)
                    trySend(classify(capabilities))
                }
            }
            override fun onLost(network: Network) {
                // A late loss callback for Wi-Fi must not erase the new cellular default.
                if (active.compareAndSet(network, null)) {
                    val replacement = manager.activeNetwork?.takeUnless { it == network }
                    active.set(replacement)
                    trySend(classify(replacement?.let(manager::getNetworkCapabilities)))
                }
            }
        }
        var registered = false
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            val network = manager.activeNetwork
            active.set(network)
            trySend(classify(network?.let(manager::getNetworkCapabilities)))
        } catch (error: RuntimeException) {
            android.util.Log.w("NetworkQualityMonitor", "Default network callback unavailable", error)
            trySend(NetworkConnectionType.OFFLINE)
        }
        awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged().flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), NetworkConnectionType.OFFLINE)

    val activeQualityCeiling: StateFlow<ActiveQualityState> = combine(connectionType, preferences) { type, prefs ->
        policy(type, prefs).also { latest = it }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), latest)

    /** Called on resolver/loader IO threads; also works before a UI/service subscribes. */
    fun snapshot(): ActiveQualityState {
        val type = try {
            classify(connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) })
        } catch (_: SecurityException) { NetworkConnectionType.OFFLINE }
        return policy(type, AudioQualityPreferences.getNetworkSettings(app)).also { latest = it }
    }

    fun setWifiCeiling(ceiling: AudioQualityCeiling) { scope.launch(preferenceWrites) { AudioQualityPreferences.setWifiQualityCeiling(app, ceiling) } }
    fun setCellularCeiling(ceiling: AudioQualityCeiling) { scope.launch(preferenceWrites) { AudioQualityPreferences.setCellularQualityCeiling(app, ceiling) } }
    fun setProtectHotspots(enabled: Boolean) { scope.launch(preferenceWrites) { AudioQualityPreferences.setTreatMeteredWifiAsCellular(app, enabled) } }

    /** Compatibility entry point for former global-quality callers. No hidden global cap. */
    fun setActiveCeiling(ceiling: AudioQualityCeiling) { scope.launch(preferenceWrites) {
        val active = snapshot()
        val prefs = AudioQualityPreferences.getNetworkSettings(app)
        val wifi = active.connectionType == NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET ||
            active.connectionType == NetworkConnectionType.METERED_WIFI && !prefs.treatMeteredWifiAsCellular
        if (wifi) AudioQualityPreferences.setWifiQualityCeiling(app, ceiling)
        else AudioQualityPreferences.setCellularQualityCeiling(app, ceiling)
    } }

    private fun policy(type: NetworkConnectionType, prefs: NetworkAudioPreferences) = ActiveQualityState(
        when (type) {
            NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET -> prefs.wifiQualityCeiling
            NetworkConnectionType.METERED_WIFI -> if (prefs.treatMeteredWifiAsCellular) prefs.cellularQualityCeiling else prefs.wifiQualityCeiling
            NetworkConnectionType.CELLULAR, NetworkConnectionType.OFFLINE -> prefs.cellularQualityCeiling
        }, type
    )

    private fun classify(caps: NetworkCapabilities?): NetworkConnectionType {
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkConnectionType.OFFLINE
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                if (unmetered) NetworkConnectionType.UNMETERED_WIFI_OR_ETHERNET else NetworkConnectionType.METERED_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkConnectionType.CELLULAR
            // An opaque VPN/unknown transport must not accidentally opt into uncapped Wi-Fi.
            else -> NetworkConnectionType.CELLULAR
        }
    }

    companion object {
        @Volatile private var instance: NetworkQualityMonitor? = null
        fun getInstance(context: Context): NetworkQualityMonitor = instance ?: synchronized(this) {
            instance ?: NetworkQualityMonitor(context).also { instance = it }
        }
    }
}
