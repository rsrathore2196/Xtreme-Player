package com.example.data.local

import android.content.Context
import com.example.playback.AudioEffectsState
import org.json.JSONArray
import org.json.JSONObject

/** Only portable equalizer choices; audio sessions and device capabilities are never restored. */
internal object AudioEffectsPreferences {
    const val STORE = "xtreme_audio_effects_prefs"
    const val KEY = "configuration"

    fun encode(state: AudioEffectsState): String = JSONObject().apply {
        put("enabled", state.isEnabled)
        put("clarity", state.crystalClarityEnabled)
        put("bass", state.bassBoostStrength)
        put("virtualizer", state.virtualizerStrength)
        put("loudness", state.loudnessGainMb)
        put("preset", state.selectedPreset)
        put("bands", JSONArray(state.bands.map { it.levelMb.toInt() }))
    }.toString()

    fun decode(raw: String, base: AudioEffectsState): AudioEffectsState {
        val json = JSONObject(raw)
        fun number(key: String, range: IntRange): Int {
            val value = json.get(key)
            require(value is Int && value in range) { "Invalid equalizer setting: $key" }
            return value
        }
        fun flag(key: String): Boolean {
            val value = json.get(key)
            require(value is Boolean) { "Invalid equalizer setting: $key" }
            return value
        }
        val preset = json.get("preset")
        require(preset is String && (preset == "Custom" || preset in base.availablePresets)) { "Invalid equalizer preset" }
        val bands = json.getJSONArray("bands")
        require(bands.length() == base.bands.size) { "Invalid equalizer bands" }
        return base.copy(isEnabled = flag("enabled"), crystalClarityEnabled = flag("clarity"),
            bassBoostStrength = number("bass", 0..1000), virtualizerStrength = number("virtualizer", 0..1000),
            loudnessGainMb = number("loudness", 0..800), selectedPreset = preset,
            bands = base.bands.mapIndexed { index, band ->
                val value = bands.get(index)
                require(value is Int && value in band.minLevelMb..band.maxLevelMb) { "Invalid equalizer band" }
                band.copy(levelMb = value.toShort())
            })
    }

    fun write(context: Context, state: AudioEffectsState) {
        check(ReactiveSettingsRepository.preferences(context, STORE).edit().putString(KEY, encode(state)).commit()) {
            "Could not save equalizer settings"
        }
    }
}
