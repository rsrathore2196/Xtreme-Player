package com.example

import com.example.ui.screens.settings.DeviceStorage
import com.example.ui.screens.settings.DownloadStorageUsage
import org.junit.Assert.*
import org.junit.Test

class DownloadStorageTest {
    @Test fun separatesOfflineAudioFromOtherUsedSpaceAndAvailableSpace() {
        val usage = DownloadStorageUsage(DeviceStorage(128_000, 32_000), 16_000)
        assertEquals(96_000L, usage.usedBytes)
        assertEquals(80_000L, usage.otherBytes)
        assertEquals(.75f, usage.usedFraction, .0001f)
        assertEquals(.125f, usage.audioFraction, .0001f)
        assertEquals(usage.totalBytes, usage.audioBytes + usage.otherBytes + usage.availableBytes)
    }

    @Test fun unavailableOrEmptyStorageNeverProducesInvalidFractions() {
        for (device in listOf(DeviceStorage(0, 0), DeviceStorage(-1, -1), DeviceStorage(100, 200))) {
            val usage = DownloadStorageUsage(device, 500)
            assertEquals(0f, usage.usedFraction, 0f)
            assertEquals(0f, usage.audioFraction, 0f)
            assertTrue(usage.otherBytes >= 0)
        }
    }

    @Test fun fullStorageAndStaleDownloadSizesKeepTheBarWithinItsBounds() {
        val full = DownloadStorageUsage(DeviceStorage(100, -1), 150)
        assertEquals(1f, full.usedFraction, 0f)
        assertEquals(1f, full.audioFraction, 0f)
        assertEquals(0L, full.otherBytes)
        val empty = DownloadStorageUsage(DeviceStorage(Long.MAX_VALUE, Long.MAX_VALUE), -1)
        assertEquals(0f, empty.audioFraction, 0f)
        assertEquals(0L, empty.audioBytes)
    }
}
