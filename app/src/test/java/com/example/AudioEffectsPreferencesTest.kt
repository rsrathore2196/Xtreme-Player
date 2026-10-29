package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AudioEffectsPreferences
import com.example.playback.AudioEffectsState
import com.example.playback.BandState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioEffectsPreferencesTest {
    private val base = AudioEffectsState(bands = listOf(BandState(0, 60, 0), BandState(1, 230, 0)))

    @Test fun portableConfigurationRoundTripsWithoutRestoringTheOldAudioSession() {
        val selected = base.copy(isEnabled = true, crystalClarityEnabled = true, bassBoostStrength = 600,
            virtualizerStrength = 300, loudnessGainMb = 150, selectedPreset = "Custom", audioSessionId = 123,
            bands = base.bands.map { it.copy(levelMb = 500) })
        val raw = AudioEffectsPreferences.encode(selected)
        assertFalse(raw.contains("audioSession"))
        assertEquals(selected.copy(audioSessionId = 456), AudioEffectsPreferences.decode(raw, base.copy(audioSessionId = 456)))
    }

    @Test fun invalidEqualizerValuesAreRejectedBeforeRestoreCanPublishThem() {
        for ((key, bad) in listOf("bass" to 1001, "virtualizer" to -1, "loudness" to 1000, "enabled" to "true", "preset" to "Unknown")) {
            val raw = JSONObject(AudioEffectsPreferences.encode(base)).put(key, bad)
            try { AudioEffectsPreferences.decode(raw.toString(), base); fail("Accepted invalid $key") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun importedConfigurationIsDurableInTheSettingsStore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val selected = base.copy(isEnabled = true, selectedPreset = "Custom", bassBoostStrength = 500)
        withContext(Dispatchers.IO) { AudioEffectsPreferences.write(context, selected) }
        val raw = context.getSharedPreferences(AudioEffectsPreferences.STORE, 0).getString(AudioEffectsPreferences.KEY, null)!!
        assertEquals(selected, AudioEffectsPreferences.decode(raw, base))
    }
}
