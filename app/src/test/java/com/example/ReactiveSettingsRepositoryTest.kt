package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ReactiveSettingsRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class ReactiveSettingsRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun uiReadsAndOptimisticEditsNeverOpenPreferenceFilesOnMain() = runBlocking {
        val mainOpens = AtomicInteger()
        val observed = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                if (Looper.myLooper() == Looper.getMainLooper()) mainOpens.incrementAndGet()
                return super.getSharedPreferences(name, mode)
            }
        }
        ReactiveSettingsRepository(observed).use { repository ->
            repository.preload()
            val prefs = repository.preferences("xtreme_other_settings")
            prefs.edit().putBoolean("haptics_enabled", false).apply()
            assertFalse(prefs.getBoolean("haptics_enabled", true))
            assertEquals(false, repository.observe("xtreme_other_settings").value["haptics_enabled"])
            repository.awaitPendingWrites()
            assertEquals(0, mainOpens.get())
            assertFalse(withContext(Dispatchers.IO) {
                context.getSharedPreferences("xtreme_other_settings", 0).getBoolean("haptics_enabled", true)
            })
        }
    }

    @Test fun rapidEditsAndAnExplicitRestoreCommitKeepTheirOrdering() = runBlocking {
        ReactiveSettingsRepository(context).use { repository ->
            repository.preload()
            val prefs = repository.preferences("xtreme_theme_prefs")
            repeat(60) { prefs.edit().putInt("revision", it).apply() }
            assertTrue(withContext(Dispatchers.IO) { prefs.edit().putInt("revision", 60).commit() })
            prefs.edit().putInt("revision", 61).apply()
            repository.awaitPendingWrites()
            shadowOf(Looper.getMainLooper()).idle()
            repository.awaitPendingWrites()
            assertEquals(61, prefs.getInt("revision", -1))
            assertEquals(61, withContext(Dispatchers.IO) { context.getSharedPreferences("xtreme_theme_prefs", 0).getInt("revision", -1) })
        }
    }

    @Test fun externalBackupWritesRefreshTheSameMemorySnapshot() = runBlocking {
        ReactiveSettingsRepository(context).use { repository ->
            repository.preload()
            val prefs = repository.preferences("xtreme_theme_prefs")
            withContext(Dispatchers.IO) {
                context.getSharedPreferences("xtreme_theme_prefs", 0).edit().putString("selected_theme_mode", "light").commit()
            }
            shadowOf(Looper.getMainLooper()).idle()
            repository.awaitPendingWrites()
            assertEquals("light", prefs.getString("selected_theme_mode", null))
        }
    }

    @Test fun editBeforePreloadPreservesOtherSavedKeysAndDoesNotRevertTheNewValue() = runBlocking {
        withContext(Dispatchers.IO) {
            context.getSharedPreferences("xtreme_other_settings", 0).edit().clear()
                .putInt("saved", 12).putInt("changed", 1).commit()
        }
        ReactiveSettingsRepository(context).use { repository ->
            val prefs = repository.preferences("xtreme_other_settings")
            prefs.edit().putInt("changed", 2).apply()
            repository.preload()
            repository.awaitPendingWrites()
            assertEquals(12, prefs.getInt("saved", 0))
            assertEquals(2, prefs.getInt("changed", 0))
        }
    }

    @Test fun mainThreadCommitIsRejectedInsteadOfBlockingOrClaimingDurability() {
        ReactiveSettingsRepository(context).use { repository ->
            assertThrows(IllegalStateException::class.java) { repository.preferences("test").edit().putInt("value", 1).commit() }
        }
    }
}
