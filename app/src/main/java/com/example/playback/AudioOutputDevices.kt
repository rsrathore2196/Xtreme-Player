package com.example.playback

import android.media.AudioDeviceInfo
import java.util.Locale

enum class AudioDeviceCategory { PHONE, EARBUDS, HEADPHONES, SPEAKER, CAR, HEARING_AID, USB, BLUETOOTH }

internal fun audioDeviceCategory(type: Int, name: String, isCar: Boolean = false): AudioDeviceCategory {
    val label = name.lowercase(Locale.ROOT)
    return when {
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioDeviceCategory.PHONE
        type == AudioDeviceInfo.TYPE_HEARING_AID -> AudioDeviceCategory.HEARING_AID
        isCar -> AudioDeviceCategory.CAR
        listOf("airpods max", "freebuds studio", "over-ear", "over ear").any(label::contains) -> AudioDeviceCategory.HEADPHONES
        listOf("earbud", "ear bud", "buds", "airpod", "earphone", "in-ear", "in ear", "tws", "wf-", "neckband", "bullets wireless").any(label::contains) -> AudioDeviceCategory.EARBUDS
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER || listOf("speaker", "soundbar", "soundlink", "boombox", "flip ", "charge ", "homepod").any(label::contains) -> AudioDeviceCategory.SPEAKER
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            listOf("headphone", "headset", "wh-", "quietcomfort", "over-ear").any(label::contains) -> AudioDeviceCategory.HEADPHONES
        type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioDeviceCategory.USB
        isBluetoothOutput(type) -> AudioDeviceCategory.BLUETOOTH
        else -> AudioDeviceCategory.SPEAKER
    }
}

internal fun isBluetoothOutput(type: Int): Boolean = type in setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST
)

private fun SoundOutputDevice.routePriority(): Int = when (type) {
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 4
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_USB_HEADSET -> 3
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> 0
    else -> 2
}

private fun SoundOutputDevice.physicalAddress(): String? = address.trim().lowercase(Locale.ROOT)
    .takeUnless { it.isBlank() || it == "00:00:00:00:00:00" || it == "02:00:00:00:00:00" }

private fun SoundOutputDevice.physicalName(): String? = name.trim().lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")
    .takeUnless { it.isBlank() || it in setOf("bluetooth audio", "bluetooth device", "bluetooth earbuds / audio", "bluetooth le audio", "unknown", "default") }

/** Android exposes profiles (A2DP, SCO, LE) separately, not just physical devices. */
internal fun normalizeAudioOutputs(
    connected: List<SoundOutputDevice>,
    selectedId: Int = -1,
    mediaRouteIds: Set<Int> = emptySet()
): List<SoundOutputDevice> {
    val groups = mutableListOf<MutableList<SoundOutputDevice>>()
    // Addressed media routes anchor groups before address-less hands-free aliases.
    for (device in connected.distinctBy { it.id }.sortedWith(
        compareByDescending<SoundOutputDevice> { it.physicalAddress() != null }.thenByDescending { it.routePriority() }
    )) {
        val matches = groups.filter { group ->
            val first = group.first()
            when {
                device.isBuiltInSpeaker && first.isBuiltInSpeaker -> true
                device.isBluetooth && first.isBluetooth -> {
                    val address = device.physicalAddress()
                    val other = first.physicalAddress()
                    if (address != null && other != null) address == other
                    else device.physicalName() != null && device.physicalName() == first.physicalName()
                }
                device.isUsb && first.isUsb -> device.physicalAddress()?.let { it == first.physicalAddress() } == true
                else -> false
            }
        }
        // A missing address must not merge two distinct, identically named devices.
        if (matches.size == 1) matches.single().add(device) else groups.add(mutableListOf(device))
    }
    val chosenGroup = groups.firstOrNull { group -> group.any { it.id in mediaRouteIds } }
        ?: groups.firstOrNull { group -> group.any { it.id == selectedId } }
        ?: groups.firstOrNull { group -> group.any { it.isWired || it.isUsb } }
        ?: groups.firstOrNull { group -> group.any { it.isBluetooth && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO } }
        ?: groups.firstOrNull { group -> group.any { it.isBuiltInSpeaker } }
        ?: groups.firstOrNull()
    return groups.map { group ->
        val route = group.maxWith(compareBy<SoundOutputDevice> { it.routePriority() }.thenBy { -it.id })
        route.copy(isSelected = group === chosenGroup)
    }.sortedWith(compareByDescending<SoundOutputDevice> { it.isSelected }.thenByDescending { it.isBuiltInSpeaker }.thenBy { it.name.lowercase(Locale.ROOT) })
}
