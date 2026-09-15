package com.example.playback

import android.content.Context
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
    val isCar: Boolean = false
)

object AudioDeviceManager {
    private const val TAG = "AudioDeviceManager"

    private val _availableDevices = MutableStateFlow<List<SoundOutputDevice>>(emptyList())
    val availableDevices: StateFlow<List<SoundOutputDevice>> = _availableDevices.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<Int>(-1)
    val selectedDeviceId: StateFlow<Int> = _selectedDeviceId.asStateFlow()

    private var audioManager: AudioManager? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager = am
        isInitialized = true

        refreshDevices(context)

        try {
            am?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio device connected: ${addedDevices?.size} devices")
                    refreshDevices(context)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio device disconnected: ${removedDevices?.size} devices")
                    refreshDevices(context)
                }
            }, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register AudioDeviceCallback: ${e.message}")
        }
    }

    fun refreshDevices(context: Context) {
        val am = audioManager ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        if (am == null) {
            _availableDevices.value = listOf(getFallbackSpeakerDevice())
            return
        }

        try {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val currentSelected = _selectedDeviceId.value
            val list = mutableListOf<SoundOutputDevice>()

            for (device in devices) {
                if (isAudioOutput(device.type)) {
                    val isSpeaker = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
                    val isBt = isBluetoothType(device.type)
                    val isWired = isWiredType(device.type)
                    val isUsb = isUsbType(device.type)
                    val isCar = isCarAudioDevice(device)

                    val typeLabel = if (isCar) "Car Audio System" else getDeviceTypeLabel(device.type)
                    val friendlyName = getDeviceFriendlyName(device)

                    list.add(
                        SoundOutputDevice(
                            id = device.id,
                            name = friendlyName,
                            typeName = typeLabel,
                            type = device.type,
                            isSelected = (currentSelected == device.id),
                            isBuiltInSpeaker = isSpeaker,
                            isBluetooth = isBt,
                            isWired = isWired,
                            isUsb = isUsb,
                            isCar = isCar
                        )
                    )
                }
            }

            // If no specific speaker device was explicitly found in the array, add default speaker
            if (list.none { it.isBuiltInSpeaker }) {
                list.add(0, getFallbackSpeakerDevice())
            }

            // Resolve selection: if nothing selected (-1 or not in list), mark speaker or first device
            if (list.none { it.isSelected }) {
                val defaultDev = list.find { it.isBluetooth } ?: list.find { it.isBuiltInSpeaker } ?: list.first()
                val resolved = list.map { it.copy(isSelected = it.id == defaultDev.id) }
                _availableDevices.value = resolved
            } else {
                _availableDevices.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing audio devices: ${e.message}", e)
            _availableDevices.value = listOf(getFallbackSpeakerDevice())
        }
    }

    fun selectDevice(context: Context, deviceId: Int, player: Player? = null) {
        _selectedDeviceId.value = deviceId
        val am = audioManager ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val targetDevice = devices?.find { it.id == deviceId }

        Log.i(TAG, "Selecting audio output device: id=$deviceId, name=${targetDevice?.let { getDeviceFriendlyName(it) } ?: "Auto/Default"}")

        // 1. Route directly on ExoPlayer if provided
        try {
            if (player is ExoPlayer) {
                if (targetDevice != null && !isFallbackId(deviceId)) {
                    player.setPreferredAudioDevice(targetDevice)
                } else {
                    player.setPreferredAudioDevice(null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting preferred audio device on player: ${e.message}")
        }

        // 2. Route via Android Audio system (API 31+ Communication Device or Speakerphone)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                if (targetDevice != null && !isFallbackId(deviceId)) {
                    am.setCommunicationDevice(targetDevice)
                } else {
                    am.clearCommunicationDevice()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting communication device: ${e.message}")
        }

        // Update in-memory device list state
        _availableDevices.value = _availableDevices.value.map {
            it.copy(isSelected = (it.id == deviceId))
        }
    }

    private fun isFallbackId(id: Int): Boolean = id == -100

    private fun getFallbackSpeakerDevice(): SoundOutputDevice {
        return SoundOutputDevice(
            id = -100,
            name = "Phone Speaker",
            typeName = "Built-in Speaker",
            type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            isSelected = true,
            isBuiltInSpeaker = true
        )
    }

    private fun isAudioOutput(type: Int): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
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
        if (device.type == AudioDeviceInfo.TYPE_AUX_LINE) {
            return true
        }
        val name = (device.productName?.toString() ?: "").lowercase()
        val carKeywords = listOf(
            "car", "auto", "sync", "uconnect", "mbux", "idrive", "toyota", "honda", "ford",
            "bmw", "audi", "mercedes", "hyundai", "kia", "pioneer", "kenwood", "alpine",
            "carplay", "handsfree", "car kit", "automotive", "mazda", "nissan", "subaru", "lexus", "volvo"
        )
        return carKeywords.any { name.contains(it) }
    }

    private fun isBluetoothType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        }
        return false
    }

    private fun isWiredType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    }

    private fun isUsbType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
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
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Earbuds / Audio"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio Device"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                ) {
                    "Bluetooth LE Audio"
                } else {
                    "Audio Output (${device.id})"
                }
            }
        }
    }

    private fun getDeviceTypeLabel(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Internal Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Wireless"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C Audio"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Accessibility"
            else -> "Audio Output"
        }
    }
}
