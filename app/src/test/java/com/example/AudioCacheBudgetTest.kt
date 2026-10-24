package com.example

import android.app.Application
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.cache.SmartCacheManager
import com.example.service.AdjustableAudioCacheEvictor
import com.example.service.MusicCache
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioCacheBudgetTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun limitsClampLegacyValuesAndUseLongArithmeticForEightGigabytes() {
        assertEquals(512L * 1024 * 1024, SmartCacheManager.normalizeQuota(40L * 1024 * 1024))
        assertEquals(8L * 1024 * 1024 * 1024, SmartCacheManager.normalizeQuota(Long.MAX_VALUE))
        assertEquals(2L * 1024 * 1024 * 1024, SmartCacheManager.normalizeQuota(2L * 1024 * 1024 * 1024))
        assertEquals(SmartCacheManager.MAX_CACHE_BYTES, SmartCacheManager.audioBudget(SmartCacheManager.MAX_CACHE_BYTES) +
            SmartCacheManager.ARTWORK_CACHE_MAX_BYTES)
    }

    @Test fun reducingTheLiveBudgetEvictsOldestAudioAndGrowingItRetainsMoreAudio() {
        val directory = context.cacheDir.resolve("adjustable-audio-test").apply { deleteRecursively() }
        val evictor = AdjustableAudioCacheEvictor(240)
        val cache = SimpleCache(directory, evictor, StandaloneDatabaseProvider(context))
        fun write(key: String) {
            val hole = cache.startReadWrite(key, 0, 80)
            try {
                val file = cache.startFile(key, 0, 80)
                file.writeBytes(ByteArray(80))
                cache.commitFile(file, 80)
            } finally { cache.releaseHoleSpan(hole) }
        }
        try {
            write("a"); write("b"); write("c")
            Thread.sleep(5) // Distinguish a real cache read's millisecond recency timestamp.
            assertTrue(cache.startReadWrite("a", 0, 80).isCached)
            evictor.setMaximumBytes(cache, 160)
            assertEquals(160, cache.cacheSpace)
            assertTrue(cache.isCached("a", 0, 80))
            assertFalse(cache.isCached("b", 0, 80))
            assertTrue(cache.isCached("c", 0, 80))
            evictor.setMaximumBytes(cache, 320)
            write("d")
            assertEquals(240, cache.cacheSpace)
        } finally { cache.release() }
        val reopened = SimpleCache(directory, AdjustableAudioCacheEvictor(320), StandaloneDatabaseProvider(context))
        try {
            assertTrue(reopened.isCached("a", 0, 80))
            assertTrue(reopened.isCached("c", 0, 80))
            assertTrue(reopened.isCached("d", 0, 80))
        } finally { reopened.release(); directory.deleteRecursively() }
    }

    @Test fun selectingQuotaPersistsAndResizesTheExistingPlaybackCacheWithoutReopeningIt() = runBlocking<Unit>(Dispatchers.IO) {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val owner = MusicCache::class.java.getDeclaredField("simpleCacheInstance").apply { isAccessible = true }
        (owner.get(null) as? SimpleCache)?.release(); owner.set(null, null)
        SmartCacheManager.configure(context)
        SmartCacheManager.setQuota(40L * 1024 * 1024)
        val cache = MusicCache.getSimpleCache(context)
        try {
            assertEquals(SmartCacheManager.MIN_CACHE_BYTES, SmartCacheManager.configuredQuota(context))
            SmartCacheManager.setQuota(SmartCacheManager.MAX_CACHE_BYTES)
            val field = MusicCache::class.java.getDeclaredField("audioEvictor").apply { isAccessible = true }
            withTimeout(3_000) {
                while (synchronized(cache) { (field.get(null) as AdjustableAudioCacheEvictor).maximumBytes } !=
                    SmartCacheManager.audioBudget(SmartCacheManager.MAX_CACHE_BYTES)) delay(5)
            }
            assertTrue(context.getSharedPreferences("xtreme_smart_cache_preferences", 0).edit().commit())
            assertEquals(SmartCacheManager.MAX_CACHE_BYTES,
                context.getSharedPreferences("xtreme_smart_cache_preferences", 0).getLong("key_max_quota_bytes", 0))
            assertSame(cache, MusicCache.getSimpleCache(context))
            assertTrue(context.cacheDir.resolve("xtreme_audio_cache").isDirectory)
        } finally {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            cache.release(); owner.set(null, null)
        }
    }
}
