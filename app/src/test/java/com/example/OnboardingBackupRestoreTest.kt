package com.example

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingBackupRestoreTest {
    private lateinit var context: Context
    private lateinit var db: MusicDatabase
    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        db = MusicDatabase.createInMemoryDatabase(context)
        UserProfilePreferences.saveUserProfile(context, UserProfile())
    }
    @After fun close() { db.close() }

    private fun legacy(profile: JSONObject? = null) = JSONObject().put("schemaVersion", 1)
        .put("favoriteTracks", JSONArray().put(JSONObject().put("id", "liked"))).apply {
            profile?.let { put("userProfile", it) }
        }

    @Test fun completeBackupRestoresProfileSettingsAndLibraryBeforeFinishingOnboarding() = runBlocking {
        val profile = UserProfile("Alex", listOf("Spanish", "Korean"), "Spain", "ES", "🇪🇸", "Spanish", true)
        UserProfilePreferences.saveUserProfile(context, profile)
        ThemePreferences.saveThemeSelection(context, AppThemeMode.LIGHT,
            ThemePresets.CleanDay.toCustomThemeState().copy(accentColorHex = "#FF3366", cardColorHex = "#F5EFEB"))
        OtherSettingsPreferences.setFullScreenAlbumArt(context, true)
        OtherSettingsPreferences.setTextSizeIndex(context, 5)
        OtherSettingsPreferences.setLyricsProvider(context, "LrcLib")
        context.getSharedPreferences("xtreme_audio_quality_prefs", 0).edit().putString("wifi_quality_ceiling", "HIGH").commit()
        context.getSharedPreferences("xtreme_playback_sources_prefs", 0).edit().putString("sources_order", "youtube,jiosaavn")
            .putBoolean("source_enabled_youtube", true).commit()
        context.getSharedPreferences("xtreme_smart_cache_preferences", 0).edit().putLong("key_max_quota_bytes", 123456789L)
            .putBoolean("key_wifi_only", true).putLong("stats_audio", 9999).commit()
        LyricsOffsetManager.updateCurrentOffset(context, "liked", -300)
        RecentSearchPreferences.addRecentSearch(context, "Song")
        com.example.playback.AudioEffectsManager.applyPreset("Bass Booster")
        com.example.playback.AudioEffectsManager.setEnabled(true)
        val equalizer = com.example.playback.AudioEffectsManager.effectsState.value
        db.musicDao().insertOrUpdateTrack(TrackEntity("liked", "Song", "Artist", "Album", 120000, "cover", "audio", isLiked = true))
        val preferences = BackupSettings.capture(context)
        val folder = File(context.filesDir, "onboarding-backup")
        BackupRestoreManager.setAutoBackupLocation(context, folder.absolutePath)
        try {
            val file = BackupRestoreManager.createBackup(context, db).getOrThrow()
            val body = File(file.filePath).readText()
            val json = JSONObject(body)
            assertEquals(5, json.getInt("schemaVersion"))
            assertFalse(json.getJSONObject("settings").getJSONObject("xtreme_smart_cache_preferences").has("stats_audio"))
            assertFalse(json.getJSONObject("settings").getJSONObject("xtreme_backup_preferences").has("auto_backup_location"))
            BackupSettings.write(context, preferences.mapValues { emptyMap() })
            com.example.playback.AudioEffectsManager.resetAll(enabled = false)
            UserProfilePreferences.saveUserProfile(context, UserProfile())
            val result = BackupRestoreManager.restoreBackup(context, db, rawJson = body, finishOnboarding = true)
            assertTrue(result.message, result.success)
            assertFalse(result.needsOnboardingDetails)
            assertEquals(profile, UserProfilePreferences.getUserProfile(context))
            assertEquals(preferences, BackupSettings.read(context))
            assertEquals(-300, LyricsOffsetManager.getEffectiveOffset(context, "liked"))
            assertEquals(equalizer, com.example.playback.AudioEffectsManager.effectsState.value)
            assertTrue(db.musicDao().getTrackById("liked")!!.isLiked)
        } finally { folder.deleteRecursively() }
    }

    @Test fun olderBackupsWithoutProfileRestoreDataButStillRequireTheDetailsPage() = runBlocking {
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = legacy().toString(), finishOnboarding = true)
        assertTrue(result.message, result.success)
        assertTrue(result.needsOnboardingDetails)
        assertFalse(UserProfilePreferences.isOnboardingCompleted(context))
        assertTrue(UserProfilePreferences.getUserProfile(context).name.isBlank())
        assertTrue(db.musicDao().getTrackById("liked")!!.isLiked)
    }

    @Test fun partialProfilesDoNotCompleteOnboardingAndKeepRestoredValuesForPrefilling() = runBlocking {
        for (profile in listOf(JSONObject().put("name", "Alex"),
            JSONObject().put("country", "Spain").put("countryCode", "ES").put("languages", JSONArray().put("Spanish")),
            JSONObject().put("name", "Alex").put("country", "Spain").put("countryCode", "ES"))) {
            val result = BackupRestoreManager.restoreBackup(context, db, rawJson = legacy(profile).toString(), finishOnboarding = true)
            assertTrue(result.message, result.success)
            assertTrue(result.needsOnboardingDetails)
            assertFalse(UserProfilePreferences.isOnboardingCompleted(context))
        }
        assertEquals("Alex", UserProfilePreferences.getUserProfile(context).name)
        assertEquals(listOf("Spanish"), UserProfilePreferences.getUserProfile(context).languages)
    }

    @Test fun olderCompleteProfilesSkipDetailsWithoutOverwritingAbsentSettings() = runBlocking {
        OtherSettingsPreferences.setTextSizeIndex(context, 5)
        val profile = JSONObject().put("name", "Alex").put("country", "Spain").put("countryCode", "ES")
            .put("languages", JSONArray().put("Spanish"))
        val result = BackupRestoreManager.restoreBackup(context, db, rawJson = legacy(profile).toString(), finishOnboarding = true)
        assertTrue(result.message, result.success)
        assertFalse(result.needsOnboardingDetails)
        assertTrue(UserProfilePreferences.isOnboardingCompleted(context))
        assertEquals("🇪🇸", UserProfilePreferences.getUserProfile(context).flag)
        assertEquals(5, OtherSettingsPreferences.getTextSizeIndex(context))
    }

    @Test fun onboardingCompletionIsPersistedOnlyAfterTheLibraryTransactionCommits() = runBlocking {
        val completedWrites = mutableListOf<Boolean>()
        val observed = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = baseContext.getSharedPreferences(name, mode)
                if (name != "xtreme_user_profile_prefs") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            private var completed = false
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value); return this
                            }
                            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                                if (key == "onboarding_completed") completed = value
                                editor.putBoolean(key, value); return this
                            }
                            override fun commit(): Boolean {
                                completedWrites.add(completed)
                                if (completed) assertFalse("Do not bypass onboarding before the import commits", db.inTransaction())
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        val profile = JSONObject().put("name", "Alex").put("country", "Spain").put("countryCode", "ES")
            .put("languages", JSONArray().put("Spanish"))
        val result = BackupRestoreManager.restoreBackup(observed, db, rawJson = legacy(profile).toString(), finishOnboarding = true)
        assertTrue(result.message, result.success)
        assertEquals(listOf(false, true), completedWrites)
    }

    @Test fun invalidSettingsCannotModifyLibraryProfileOrPreferences() = runBlocking {
        val before = BackupSettings.read(context)
        for (settings in listOf(
            JSONObject().put("../arbitrary", JSONObject()),
            JSONObject().put("xtreme_other_settings", JSONObject().put("text_size_index", JSONObject().put("type", "string").put("value", "bad"))),
            JSONObject().put("xtreme_other_settings", JSONObject().put("haptics_enabled", JSONObject().put("type", "boolean").put("value", "true")))
        )) {
            val result = BackupRestoreManager.restoreBackup(context, db,
                rawJson = legacy().put("schemaVersion", 5).put("settings", settings).toString(), finishOnboarding = true)
            assertFalse(result.success)
            assertTrue(db.musicDao().getAllTracksSync().isEmpty())
            assertFalse(UserProfilePreferences.isOnboardingCompleted(context))
            assertEquals(before, BackupSettings.read(context))
        }
    }

    @Test fun settingsWriteFailureRollsBackSettingsAndRoomAndDoesNotFinishOnboarding() = runBlocking {
        val before = BackupSettings.read(context)
        val imported = mapOf("xtreme_other_settings" to mapOf<String, Any>("text_size_index" to 5),
            "xtreme_audio_quality_prefs" to mapOf<String, Any>("wifi_quality_ceiling" to "HIGH"))
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = baseContext.getSharedPreferences(name, mode)
                if (name != "xtreme_audio_quality_prefs") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            private var fail = false
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                if (key == "wifi_quality_ceiling" && value == "HIGH") fail = true
                                editor.putString(key, value)
                                return this
                            }
                            override fun commit(): Boolean = editor.commit() && !fail
                        }
                    }
                }
            }
        }
        val result = BackupRestoreManager.restoreBackup(failing, db,
            rawJson = legacy().put("schemaVersion", 5).put("settings", BackupSettings.encode(imported)).toString(), finishOnboarding = true)
        assertFalse(result.success)
        assertTrue(db.musicDao().getAllTracksSync().isEmpty())
        assertFalse(UserProfilePreferences.isOnboardingCompleted(context))
        assertEquals(before, BackupSettings.read(context))
    }
}
