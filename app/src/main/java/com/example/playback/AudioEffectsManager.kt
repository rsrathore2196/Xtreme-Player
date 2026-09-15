package com.example.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val isEnabled: Boolean = false,
    val crystalClarityEnabled: Boolean = false,
    val bassBoostStrength: Int = 0, // 0..1000
    val virtualizerStrength: Int = 0, // 0..1000
    val loudnessGainMb: Int = 0, // 0..800 mB
    val bands: List<BandState> = emptyList(),
    val selectedPreset: String = "Flat",
    val availablePresets: List<String> = listOf(
        "Flat",
        "Crystal Clarity",
        "Studio Master",
        "Bass Boost",
        "Electronic",
        "Rock",
        "Hip-Hop",
        "Dance",
        "Pop",
        "Vocal & Podcast",
        "Acoustic",
        "Car Audio (Cabin Dynamics)",
        "Treble Boost"
    ),
    val audioSessionId: Int = 0
)

object AudioEffectsManager {
    private const val TAG = "AudioEffectsManager"

    private var currentSessionId: Int = 0
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

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

    @Synchronized
    fun attachAudioSession(sessionId: Int) {
        if (sessionId <= 0 || sessionId == currentSessionId) return
        Log.d(TAG, "Attaching Audio Effects to Session ID: $sessionId")
        currentSessionId = sessionId
        releaseEffects()

        _effectsState.update { it.copy(audioSessionId = sessionId) }

        // Allocate DSP effects only if enabled
        if (_effectsState.value.isEnabled) {
            initEffectsForSession(sessionId)
        }
    }

    private fun initEffectsForSession(sessionId: Int) {
        // 1. Equalizer initialization
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

            _effectsState.update {
                it.copy(
                    audioSessionId = sessionId,
                    bands = if (bandsList.isNotEmpty()) bandsList else it.bands
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Equalizer initialization bypassed: ${t.message}")
        }

        // 2. BassBoost initialization
        try {
            val bb = BassBoost(0, sessionId)
            if (bb.strengthSupported) {
                bb.enabled = _effectsState.value.isEnabled
                bb.setStrength(_effectsState.value.bassBoostStrength.toShort())
                bassBoost = bb
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BassBoost initialization bypassed: ${t.message}")
        }

        // 3. Virtualizer initialization
        try {
            val virt = Virtualizer(0, sessionId)
            if (virt.strengthSupported) {
                virt.enabled = _effectsState.value.isEnabled
                virt.setStrength(_effectsState.value.virtualizerStrength.toShort())
                virtualizer = virt
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Virtualizer initialization bypassed: ${t.message}")
        }

        // 4. LoudnessEnhancer initialization (transparent dynamic gain)
        try {
            val le = LoudnessEnhancer(sessionId)
            le.enabled = _effectsState.value.isEnabled
            le.setTargetGain(_effectsState.value.loudnessGainMb)
            loudnessEnhancer = le
        } catch (t: Throwable) {
            Log.w(TAG, "LoudnessEnhancer bypassed: ${t.message}")
        }

        // Apply selected preset curves & tuning
        applyPresetInternal(_effectsState.value.selectedPreset)
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        _effectsState.update { it.copy(isEnabled = enabled) }
        if (enabled) {
            if (equalizer == null && currentSessionId > 0) {
                initEffectsForSession(currentSessionId)
            } else {
                try {
                    equalizer?.enabled = true
                    bassBoost?.enabled = true
                    virtualizer?.enabled = true
                    loudnessEnhancer?.enabled = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error enabling effects: ${e.message}")
                }
            }
        } else {
            releaseEffects()
        }
    }

    @Synchronized
    fun setBandLevel(bandIndex: Short, levelMb: Short) {
        val band = _effectsState.value.bands.getOrNull(bandIndex.toInt())
        val clampedLevel = levelMb.coerceIn(
            band?.minLevelMb ?: -1500,
            band?.maxLevelMb ?: 1500
        )

        try {
            equalizer?.setBandLevel(bandIndex, clampedLevel)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting band level: ${e.message}")
        }

        _effectsState.update { state ->
            val updatedBands = state.bands.map { b ->
                if (b.index == bandIndex) b.copy(levelMb = clampedLevel) else b
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
    fun setLoudnessGain(gainMb: Int) {
        val clamped = gainMb.coerceIn(0, 800)
        _effectsState.update { it.copy(loudnessGainMb = clamped) }
        try {
            loudnessEnhancer?.setTargetGain(clamped)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting loudness gain: ${e.message}")
        }
    }

    @Synchronized
    fun applyPreset(presetName: String) {
        _effectsState.update { it.copy(selectedPreset = presetName) }
        applyPresetInternal(presetName)
    }

    private fun applyPresetInternal(presetName: String) {
        val eq = equalizer

        // Acoustic EQ Curves (mB = 1/100 dB): [60Hz Sub-bass, 230Hz Mid-bass, 910Hz Midrange, 3.6kHz Presence, 14kHz Brilliance]
        val bandDbs: List<Short> = when (presetName.lowercase()) {
            "crystal clarity" -> listOf(250, -100, 150, 450, 600)
            "studio master" -> listOf(150, 0, 100, 250, 350)
            "bass boost", "deep bass" -> listOf(850, 550, 100, 150, 250)
            "electronic", "edm" -> listOf(700, 350, -100, 450, 700)
            "rock", "metal" -> listOf(550, 250, -150, 350, 600)
            "hip-hop", "r&b" -> listOf(850, 500, 50, 250, 450)
            "dance", "club" -> listOf(750, 400, 0, 500, 600)
            "pop", "commercial" -> listOf(350, 150, 200, 350, 450)
            "vocal & podcast", "vocal" -> listOf(-350, -50, 550, 450, 100)
            "acoustic", "classical" -> listOf(300, 200, 150, 300, 450)
            "car audio (cabin dynamics)", "car audio", "car" -> listOf(750, -150, 250, 450, 650)
            "treble boost" -> listOf(-100, 0, 200, 550, 850)
            else -> listOf(0, 0, 0, 0, 0) // Flat reference
        }

        val updatedBands = _effectsState.value.bands.mapIndexed { index, band ->
            val targetLevel = bandDbs.getOrElse(index) { 0.toShort() }
            val clampedLevel = targetLevel.coerceIn(band.minLevelMb, band.maxLevelMb)
            try {
                eq?.setBandLevel(band.index, clampedLevel)
            } catch (e: Exception) {
                Log.w(TAG, "Band ${band.index} level could not be applied: ${e.message}")
            }
            band.copy(levelMb = clampedLevel)
        }

        val targetBass = when (presetName.lowercase()) {
            "crystal clarity" -> 250
            "studio master" -> 150
            "bass boost", "deep bass" -> 850
            "electronic", "edm" -> 700
            "dance", "club" -> 750
            "hip-hop", "r&b" -> 800
            "rock", "metal" -> 450
            "pop", "commercial" -> 350
            "car audio (cabin dynamics)", "car audio", "car" -> 500
            "acoustic", "classical" -> 150
            "treble boost" -> 100
            "vocal & podcast", "vocal" -> 0
            else -> 0 // Flat
        }

        val targetVirt = when (presetName.lowercase()) {
            "crystal clarity" -> 500
            "studio master" -> 300
            "bass boost", "deep bass" -> 200
            "electronic", "edm" -> 600
            "dance", "club" -> 600
            "rock", "metal" -> 350
            "hip-hop", "r&b" -> 250
            "pop", "commercial" -> 350
            "car audio (cabin dynamics)", "car audio", "car" -> 400
            "acoustic", "classical" -> 400
            "treble boost" -> 350
            "vocal & podcast", "vocal" -> 100
            else -> 0 // Flat
        }

        val targetGain = when (presetName.lowercase()) {
            "crystal clarity" -> 150
            "studio master" -> 100
            "bass boost", "deep bass" -> 300
            "electronic", "edm" -> 250
            "dance", "club" -> 300
            "hip-hop", "r&b" -> 250
            "rock", "metal" -> 200
            "pop", "commercial" -> 150
            "car audio (cabin dynamics)", "car audio", "car" -> 400
            "vocal & podcast", "vocal" -> 200
            "acoustic", "classical" -> 100
            "treble boost" -> 100
            else -> 0 // Flat
        }

        setBassBoost(targetBass)
        setVirtualizer(targetVirt)
        setLoudnessGain(targetGain)

        _effectsState.update {
            it.copy(
                bands = updatedBands,
                bassBoostStrength = targetBass,
                virtualizerStrength = targetVirt,
                loudnessGainMb = targetGain,
                crystalClarityEnabled = presetName.equals("Crystal Clarity", ignoreCase = true)
            )
        }
    }

    @Synchronized
    fun setCrystalClarityEnabled(enabled: Boolean) {
        _effectsState.update { it.copy(crystalClarityEnabled = enabled) }
        if (enabled) {
            applyPreset("Crystal Clarity")
        } else {
            applyPreset("Flat")
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
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing loudness enhancer: ${e.message}")
        }
    }
}
