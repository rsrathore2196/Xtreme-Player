package com.example.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class BandState(
    val index: Short,
    val centerFreqHz: Int,
    val levelMb: Short,
    val minLevelMb: Short = -1500,
    val maxLevelMb: Short = 1500
) {
    val displayFrequency: String
        get() = when {
            centerFreqHz >= 1000 -> "${centerFreqHz / 1000}kHz"
            else -> "${centerFreqHz}Hz"
        }

    val displayLevelDb: String
        get() {
            val db = levelMb.toFloat() / 100f
            return if (db > 0) "+${String.format("%.1f", db)} dB" else "${String.format("%.1f", db)} dB"
        }
}

data class AudioEffectsState(
    val isEnabled: Boolean = true,
    val bassBoostStrength: Int = 600, // 0..1000
    val virtualizerStrength: Int = 450, // 0..1000
    val bands: List<BandState> = emptyList(),
    val selectedPreset: String = "Electronic",
    val availablePresets: List<String> = listOf("Flat", "Bass Boost", "Electronic", "Rock", "Dance", "Vocal", "Acoustic", "Hip-Hop"),
    val audioSessionId: Int = 0
)

object AudioEffectsManager {
    private const val TAG = "AudioEffectsManager"

    private var currentSessionId: Int = 0
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private val defaultFrequencies = listOf(60, 230, 910, 3600, 14000)

    private val _effectsState = MutableStateFlow(
        AudioEffectsState(
            bands = defaultFrequencies.mapIndexed { index, freq ->
                BandState(
                    index = index.toShort(),
                    centerFreqHz = freq,
                    levelMb = 0,
                    minLevelMb = -1500,
                    maxLevelMb = 1500
                )
            }
        )
    )
    val effectsState: StateFlow<AudioEffectsState> = _effectsState.asStateFlow()

    private fun isEffectTypeAvailable(type: UUID): Boolean {
        return try {
            val descriptors = AudioEffect.queryEffects() ?: return false
            descriptors.any { it.type == type }
        } catch (t: Throwable) {
            false
        }
    }

    @Synchronized
    fun attachAudioSession(sessionId: Int) {
        if (sessionId <= 0 || sessionId == currentSessionId) return
        Log.d(TAG, "Attaching Audio Effects to Session ID: $sessionId")
        currentSessionId = sessionId
        releaseEffects()

        // 1. Initialize Equalizer (only if system hardware/HAL reports availability)
        if (isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_EQUALIZER)) {
            try {
                val eq = Equalizer(0, sessionId)
                eq.enabled = _effectsState.value.isEnabled
                equalizer = eq

                val numBands = eq.numberOfBands
                val levelRange = try {
                    eq.bandLevelRange
                } catch (e: Exception) {
                    shortArrayOf(-1500, 1500)
                }
                val minLevel = levelRange.getOrElse(0) { -1500 }
                val maxLevel = levelRange.getOrElse(1) { 1500 }

                val bandsList = mutableListOf<BandState>()
                for (i in 0 until numBands) {
                    val bandIndex = i.toShort()
                    val centerFreq = try {
                        eq.getCenterFreq(bandIndex) / 1000 // mHz to Hz
                    } catch (e: Exception) {
                        defaultFrequencies.getOrElse(i) { 1000 * (i + 1) }
                    }
                    val currentLevel = try {
                        eq.getBandLevel(bandIndex)
                    } catch (e: Exception) {
                        0.toShort()
                    }
                    bandsList.add(
                        BandState(
                            index = bandIndex,
                            centerFreqHz = centerFreq,
                            levelMb = currentLevel,
                            minLevelMb = minLevel,
                            maxLevelMb = maxLevel
                        )
                    )
                }

                // Read hardware presets if available
                val hwPresets = mutableListOf<String>()
                try {
                    for (p in 0 until eq.numberOfPresets) {
                        hwPresets.add(eq.getPresetName(p.toShort()))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware presets not queried: ${e.message}")
                }

                val finalPresets = if (hwPresets.isNotEmpty()) {
                    (listOf("Flat", "Bass Boost", "Electronic") + hwPresets).distinct()
                } else {
                    _effectsState.value.availablePresets
                }

                _effectsState.update {
                    it.copy(
                        audioSessionId = sessionId,
                        bands = if (bandsList.isNotEmpty()) bandsList else it.bands,
                        availablePresets = finalPresets
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Equalizer could not be initialized on session $sessionId: ${t.message}")
            }
        }

        // 2. Initialize BassBoost (only if system hardware/HAL reports availability)
        if (isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_BASS_BOOST)) {
            try {
                val bb = BassBoost(0, sessionId)
                if (bb.strengthSupported) {
                    bb.enabled = _effectsState.value.isEnabled
                    bb.setStrength(_effectsState.value.bassBoostStrength.toShort())
                    bassBoost = bb
                }
            } catch (t: Throwable) {
                Log.w(TAG, "BassBoost could not be initialized on session $sessionId: ${t.message}")
            }
        }

        // 3. Initialize Virtualizer (only if system hardware/HAL reports availability)
        if (isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_VIRTUALIZER)) {
            try {
                val virt = Virtualizer(0, sessionId)
                if (virt.strengthSupported) {
                    virt.enabled = _effectsState.value.isEnabled
                    virt.setStrength(_effectsState.value.virtualizerStrength.toShort())
                    virtualizer = virt
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Virtualizer could not be initialized on session $sessionId: ${t.message}")
            }
        }

        // Apply current preset to the newly attached session
        applyPresetInternal(_effectsState.value.selectedPreset)
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        _effectsState.update { it.copy(isEnabled = enabled) }
        try {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled
            virtualizer?.enabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error setting effect enabled: ${e.message}")
        }
    }

    @Synchronized
    fun setBandLevel(bandIndex: Short, levelMb: Short) {
        val clampedLevel = levelMb.coerceIn(
            _effectsState.value.bands.getOrNull(bandIndex.toInt())?.minLevelMb ?: -1500,
            _effectsState.value.bands.getOrNull(bandIndex.toInt())?.maxLevelMb ?: 1500
        )

        try {
            equalizer?.setBandLevel(bandIndex, clampedLevel)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting band level: ${e.message}")
        }

        _effectsState.update { state ->
            val updatedBands = state.bands.map { band ->
                if (band.index == bandIndex) band.copy(levelMb = clampedLevel) else band
            }
            state.copy(bands = updatedBands, selectedPreset = "Custom")
        }
    }

    @Synchronized
    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _effectsState.update { it.copy(bassBoostStrength = clamped) }
        try {
            bassBoost?.setStrength(clamped.toShort())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bass boost strength: ${e.message}")
        }
    }

    @Synchronized
    fun setVirtualizer(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _effectsState.update { it.copy(virtualizerStrength = clamped) }
        try {
            virtualizer?.setStrength(clamped.toShort())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting virtualizer strength: ${e.message}")
        }
    }

    @Synchronized
    fun applyPreset(presetName: String) {
        _effectsState.update { it.copy(selectedPreset = presetName) }
        applyPresetInternal(presetName)
    }

    private fun applyPresetInternal(presetName: String) {
        val eq = equalizer

        // First check if native hardware preset exists
        if (eq != null) {
            try {
                for (p in 0 until eq.numberOfPresets) {
                    if (eq.getPresetName(p.toShort()).equals(presetName, ignoreCase = true)) {
                        eq.usePreset(p.toShort())
                        // Read back updated band levels
                        val updated = _effectsState.value.bands.map { band ->
                            val level = eq.getBandLevel(band.index)
                            band.copy(levelMb = level)
                        }
                        _effectsState.update { it.copy(bands = updated) }
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native preset matching failed, using custom curve: ${e.message}")
            }
        }

        // Custom curve calculations (in mB, where 100 mB = 1 dB)
        val bandDbs: List<Short> = when (presetName.lowercase()) {
            "bass boost" -> listOf(900, 600, 100, 0, 0)
            "electronic" -> listOf(700, 300, -100, 400, 800)
            "rock" -> listOf(600, 200, -200, 300, 700)
            "dance" -> listOf(800, 400, 0, 500, 600)
            "vocal" -> listOf(-300, 100, 800, 600, -200)
            "acoustic" -> listOf(400, 200, 100, 400, 500)
            "hip-hop" -> listOf(1000, 700, 0, 300, 500)
            else -> listOf(0, 0, 0, 0, 0) // Flat
        }

        val updatedBands = _effectsState.value.bands.mapIndexed { index, band ->
            val targetLevel = bandDbs.getOrElse(index) { 0.toShort() }
            try {
                eq?.setBandLevel(band.index, targetLevel)
            } catch (e: Exception) {
                // Safe ignore if hardware band doesn't exist
            }
            band.copy(levelMb = targetLevel)
        }

        // Also adjust bass boost and virtualizer according to preset
        val targetBass = when (presetName.lowercase()) {
            "bass boost" -> 950
            "electronic" -> 750
            "dance" -> 800
            "hip-hop" -> 900
            "rock" -> 600
            "vocal" -> 200
            else -> 400
        }
        val targetVirt = when (presetName.lowercase()) {
            "electronic" -> 700
            "dance" -> 650
            "rock" -> 500
            "acoustic" -> 450
            else -> 300
        }

        setBassBoost(targetBass)
        setVirtualizer(targetVirt)

        _effectsState.update {
            it.copy(
                bands = updatedBands,
                bassBoostStrength = targetBass,
                virtualizerStrength = targetVirt
            )
        }
    }

    @Synchronized
    fun resetAll() {
        applyPreset("Flat")
    }

    @Synchronized
    fun detachAudioSession() {
        releaseEffects()
        currentSessionId = 0
    }

    private fun releaseEffects() {
        try {
            equalizer?.release()
            equalizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing equalizer: ${e.message}")
        }
        try {
            bassBoost?.release()
            bassBoost = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing bass boost: ${e.message}")
        }
        try {
            virtualizer?.release()
            virtualizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtualizer: ${e.message}")
        }
    }
}
