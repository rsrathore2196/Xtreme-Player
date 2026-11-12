package com.example

import android.media.AudioDeviceInfo as Device
import com.example.playback.*
import org.junit.Assert.*
import org.junit.Test

class AudioOutputDevicesTest {
    private fun output(id: Int, name: String, type: Int, address: String = "") = SoundOutputDevice(
        id, name, "Bluetooth", type, false,
        isBuiltInSpeaker = type == Device.TYPE_BUILTIN_SPEAKER || type == Device.TYPE_BUILTIN_SPEAKER_SAFE,
        isBluetooth = isBluetoothOutput(type),
        isWired = type == Device.TYPE_WIRED_HEADPHONES,
        isUsb = type == Device.TYPE_USB_DEVICE || type == Device.TYPE_USB_HEADSET,
        address = address
    )
    private val phone = output(1, "This phone", Device.TYPE_BUILTIN_SPEAKER)
    private val buds = output(2, "OnePlus Nord Buds 3", Device.TYPE_BLUETOOTH_A2DP, "AA:BB:CC:DD:EE:01")

    @Test fun earbudsProfilesBecomeOneDeviceAndSelectionUsesTheMediaRoute() {
        val sco = output(3, buds.name, Device.TYPE_BLUETOOTH_SCO, buds.address.lowercase())
        val devices = normalizeAudioOutputs(listOf(sco, phone, buds), selectedId = sco.id)
        assertEquals(2, devices.size)
        assertEquals(listOf(buds.id, phone.id), devices.map { it.id })
        assertEquals(buds.id, devices.single { it.isSelected }.id)
        assertEquals(AudioDeviceCategory.EARBUDS, devices.first().category)
    }

    @Test fun addressLessAliasesMergeByNormalizedNameOnOlderPhones() {
        val a2dp = buds.copy(address = "")
        val sco = output(3, "  ONEPLUS   NORD BUDS 3  ", Device.TYPE_BLUETOOTH_SCO)
        assertEquals(2, normalizeAudioOutputs(listOf(phone, sco, a2dp)).size)
    }

    @Test fun differentAddressesKeepIdenticallyNamedPhysicalDevicesSeparate() {
        val another = buds.copy(id = 4, address = "AA:BB:CC:DD:EE:02")
        val outputs = normalizeAudioOutputs(listOf(phone, buds, another))
        assertEquals(3, outputs.size)
        assertEquals(setOf(1, 2, 4), outputs.map { it.id }.toSet())
    }

    @Test fun anAddressMatchesEvenWhenProfileNamesDiffer() {
        val sco = output(3, "Hands-free audio", Device.TYPE_BLUETOOTH_SCO, buds.address)
        assertEquals(2, normalizeAudioOutputs(listOf(phone, buds, sco)).size)
    }

    @Test fun unknownNamesAndAddressesDoNotCombineUnrelatedDevices() {
        val one = output(2, "Bluetooth audio", Device.TYPE_BLUETOOTH_A2DP)
        val two = output(3, "Bluetooth audio", Device.TYPE_BLUETOOTH_SCO)
        assertEquals(3, normalizeAudioOutputs(listOf(phone, one, two)).size)
    }

    @Test fun lowEnergyAndClassicProfilesPreferLowEnergyAndCollapseSafeSpeakerAlias() {
        val le = buds.copy(id = 4, type = Device.TYPE_BLE_HEADSET)
        val safeSpeaker = phone.copy(id = 5, type = Device.TYPE_BUILTIN_SPEAKER_SAFE)
        val devices = normalizeAudioOutputs(listOf(phone, safeSpeaker, buds, le), mediaRouteIds = setOf(le.id))
        assertEquals(2, devices.size)
        assertEquals(le.id, devices.single { it.isSelected }.id)
        assertEquals(phone.id, devices.single { it.isBuiltInSpeaker }.id)
    }

    @Test fun disconnectRemovesTheDeviceAndDoesNotInventAnOutput() {
        assertEquals(phone.id, normalizeAudioOutputs(listOf(phone), selectedId = buds.id).single { it.isSelected }.id)
        assertTrue(normalizeAudioOutputs(emptyList(), selectedId = buds.id).isEmpty())
        assertEquals(1, normalizeAudioOutputs(listOf(buds)).size)
    }

    @Test fun actualMediaRouteWinsOverAnOldSelectionAndWiredWinsTheDefault() {
        assertEquals(phone.id, normalizeAudioOutputs(listOf(phone, buds), buds.id, setOf(phone.id)).single { it.isSelected }.id)
        val wired = output(5, "Wired headphones", Device.TYPE_WIRED_HEADPHONES)
        assertEquals(wired.id, normalizeAudioOutputs(listOf(phone, buds, wired)).single { it.isSelected }.id)
    }

    @Test fun iconsDistinguishEarbudsHeadphonesSpeakersAndOtherCategories() {
        assertEquals(AudioDeviceCategory.PHONE, phone.category)
        assertEquals(AudioDeviceCategory.EARBUDS, buds.category)
        assertEquals(AudioDeviceCategory.EARBUDS, output(3, "USB-C earphones", Device.TYPE_USB_HEADSET).category)
        assertEquals(AudioDeviceCategory.HEADPHONES, output(4, "Sony WH-1000XM5", Device.TYPE_BLUETOOTH_A2DP).category)
        assertEquals(AudioDeviceCategory.HEADPHONES, output(5, "AirPods Max", Device.TYPE_BLUETOOTH_A2DP).category)
        assertEquals(AudioDeviceCategory.SPEAKER, output(6, "JBL Flip 6", Device.TYPE_BLUETOOTH_A2DP).category)
        assertEquals(AudioDeviceCategory.SPEAKER, output(7, "Living room", Device.TYPE_BLE_SPEAKER).category)
        assertEquals(AudioDeviceCategory.HEARING_AID, output(8, "Hearing aid", Device.TYPE_HEARING_AID).category)
        assertEquals(AudioDeviceCategory.USB, output(9, "USB DAC", Device.TYPE_USB_DEVICE).category)
        assertEquals(AudioDeviceCategory.BLUETOOTH, output(10, "Unknown audio", Device.TYPE_BLUETOOTH_A2DP).category)
    }
}
