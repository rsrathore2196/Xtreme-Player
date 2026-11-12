package com.example.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.Immutable
import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class SoundOutputDevice(
    val id: Int,
    val name: String,
    val typeName: String,
    val type: Int,
    val isSelected: Boolean,
    val isBuiltInSpeaker: Boolean = false,
    val isBluetooth: Boolean = false,
    val isWired: Boolean = false,
    val isUsb: Boolean = false,
    val isCar: Boolean = false,
    val address: String = "",
    val category: AudioDeviceCategory = audioDeviceCategory(type, name, isCar)
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AudioDeviceManager {
    private const val TAG = "AudioDeviceManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshGeneration = AtomicInteger()
    private val routingMutex = Mutex()

    private val _availableDevices = MutableStateFlow<List<SoundOutputDevice>>(emptyList())
    val availableDevices: StateFlow<List<SoundOutputDevice>> = _availableDevices.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<Int>(-1)
    val selectedDeviceId: StateFlow<Int> = _selectedDeviceId.asStateFlow()

    @Volatile private var audioManager: AudioManager? = null
    private var isInitialized = false
    @Volatile private var preferredDeviceId = -1

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        val app = context.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager = am
        isInitialized = true

        refreshDevices(app)

        try {
            am?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio device connected: ${addedDevices?.size} devices")
                    refreshDevices(app)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio device disconnected: ${removedDevices?.size} devices")
                    refreshDevices(app)
                }
            }, Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register AudioDeviceCallback: ${e.message}")
        }
    }

    fun refreshDevices(context: Context) {
        val generation = refreshGeneration.incrementAndGet()
        val app = context.applicationContext
        scope.launch { refreshDevicesOnIo(app, generation) }
    }

    private fun refreshDevicesOnIo(context: Context, generation: Int) {
        val am = audioManager ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        if (am == null) {
            if (generation == refreshGeneration.get()) {
                _availableDevices.value = emptyList()
                _selectedDeviceId.value = -1
            }
            return
        }

        try {
            // GET_DEVICES_OUTPUTS contains connected endpoints, never the paired-device list.
            val connected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { isAudioOutput(it.type) }
                .map { device ->
                    val isSpeaker = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
                    val isCar = !isSpeaker && isCarAudioDevice(device)
                    SoundOutputDevice(
                        id = device.id,
                        name = if (isSpeaker) "This phone" else getDeviceFriendlyName(device),
                        typeName = if (isCar) "Car audio" else getDeviceTypeLabel(device.type),
                        type = device.type,
                        isSelected = false,
                        isBuiltInSpeaker = isSpeaker,
                        isBluetooth = isBluetoothOutput(device.type),
                        isWired = isWiredType(device.type),
                        isUsb = isUsbType(device.type),
                        isCar = isCar,
                        address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else ""
                    )
                }
            val mediaRouteIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    am.getAudioDevicesForAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).map { it.id }.toSet()
                } catch (_: Exception) { emptySet() }
            } else emptySet()
            val resolved = normalizeAudioOutputs(connected, preferredDeviceId, mediaRouteIds)
            if (generation != refreshGeneration.get()) return
            _selectedDeviceId.value = resolved.firstOrNull { it.isSelected }?.id ?: -1
            _availableDevices.value = resolved
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing audio devices: ${e.message}", e)
            if (generation == refreshGeneration.get()) {
                _availableDevices.value = emptyList()
                _selectedDeviceId.value = -1
            }
        }
    }

    fun selectDevice(context: Context, deviceId: Int, player: Player? = null) {
        val app = context.applicationContext
        scope.launch { routeDevice(app, deviceId, player) }
    }

    internal suspend fun routeDevice(context: Context, deviceId: Int, player: Player? = null) = withContext(Dispatchers.IO) {
      routingMutex.withLock {
        refreshGeneration.incrementAndGet()
        val am = audioManager ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val targetDevice = devices?.find { it.id == deviceId }
        if (targetDevice == null) {
            refreshDevices(context)
            return@withLock
        }
        preferredDeviceId = deviceId
        _selectedDeviceId.value = deviceId

        Log.i(TAG, "Selecting audio output device: id=$deviceId, name=${getDeviceFriendlyName(targetDevice)}")

        // 1. Route directly on ExoPlayer if provided
        try {
            if (player is ExoPlayer) {
                withContext(Handler(player.applicationLooper).asCoroutineDispatcher()) {
                    player.setPreferredAudioDevice(targetDevice)
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "Failed setting preferred audio device on player: ${e.message}")
        }

        // This is media playback. Communication-device routing is for calls and
        // can activate a second, low-quality SCO profile for the same earbuds.

        // Update in-memory device list state
        _availableDevices.value = _availableDevices.value.map {
            it.copy(isSelected = (it.id == deviceId))
        }
      }
    }

    private fun isAudioOutput(type: Int): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                } else false
            }
        }
    }

    private fun isCarAudioDevice(device: AudioDeviceInfo): Boolean {
        val name = device.productName?.toString().orEmpty()
        return Regex("\\b(car|carplay|android auto|uconnect|mbux|idrive|toyota|honda|ford|bmw|audi|mercedes|hyundai|kia|mazda|nissan|subaru|lexus|volvo)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(name)
    }

    private fun isWiredType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    }

    private fun isUsbType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }

    private fun getDeviceFriendlyName(device: AudioDeviceInfo): String {
        val prodName = try {
            device.productName?.toString()?.trim()
        } catch (_: Exception) {
            null
        }

        if (!prodName.isNullOrBlank()) {
            return prodName
        }

        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth audio"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio Device"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                ) {
                    "Bluetooth LE audio"
                } else {
                    "Audio Output (${device.id})"
                }
            }
        }
    }

    private fun getDeviceTypeLabel(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Built-in speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Accessibility"
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
            else -> "Audio output"
        }
    }
}
