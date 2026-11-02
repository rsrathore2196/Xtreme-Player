package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.SmartCacheManager
import com.example.data.local.ReactiveSettingsRepository
import com.example.performance.MaintenanceScheduler
import com.example.service.MusicCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = XtremeMusicApp::class, instrumentedPackages = ["com.example.data.cache"])
class SmartCacheInitializationTest {
    @Test fun actualCacheUseRestoresSavedPolicyWithoutOpeningCachesOrScanningDirectories() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<XtremeMusicApp>()
        withContext(Dispatchers.IO) {
            ReactiveSettingsRepository.preferences(app, "xtreme_smart_cache_preferences").edit()
                .putLong("key_max_quota_bytes", SmartCacheManager.QUOTA_GENEROUS)
                .putBoolean("key_smart_auto_trim", false).putBoolean("key_auto_precache", false)
                .putBoolean("key_wifi_only", true).commit()
        }
        assertNull(app.initializedImageDiskCache); assertNull(MusicCache.peekCacheUsage())
        MaintenanceScheduler.onCacheUsed(app)
        val stats = withTimeout(5_000) { SmartCacheManager.stats.first { it.maxQuotaBytes == SmartCacheManager.QUOTA_GENEROUS } }
        assertFalse(stats.autoTrimEnabled); assertFalse(stats.autoPreCacheEnabled); assertTrue(stats.cacheOnWifiOnly)
        assertNull(app.initializedImageDiskCache); assertNull(MusicCache.peekCacheUsage())
        val retained = SmartCacheManager.stats.value
        MaintenanceScheduler.onCacheUsed(app)
        assertSame(retained, SmartCacheManager.stats.value)
    }
}
