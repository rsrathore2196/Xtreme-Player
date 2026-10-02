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
import androidx.compose.runtime.Immutable
import kotlin.math.ln

@Immutable
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

@Immutable
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
        "Bass Booster",
        "Deep Sub",
        "Bass Reducer",
        "Electronic / EDM",
        "Rock & Metal",
        "Hip-Hop & R&B",
        "Dance & Club",
        "Pop Commercial",
        "Vocal & Podcast",
        "Acoustic Live",
        "Classical Concert",
        "Jazz Lounge",
        "Treble Booster",
        "Treble Reducer",
        "Dynamic Cinema",
        "Car Audio (Cabin Dynamics)",
        "Gaming & Spatial"
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

    /**
     * Interpolates EQ target dB for a specific center frequency using logarithmic band reference anchors.
     */
    private fun interpolateGain(freqHz: Int, anchorDbs: List<Short>): Short {
        if (anchorDbs.size == 5) {
            val anchors = listOf(60, 230, 910, 3600, 14000)
            if (freqHz <= anchors.first()) return anchorDbs.first()
            if (freqHz >= anchors.last()) return anchorDbs.last()

            for (i in 0 until anchors.size - 1) {
                val f1 = anchors[i]
                val f2 = anchors[i + 1]
                if (freqHz in f1..f2) {
                    val logF1 = ln(f1.toDouble())
                    val logF2 = ln(f2.toDouble())
                    val logF = ln(freqHz.toDouble())
                    val ratio = ((logF - logF1) / (logF2 - logF1)).toFloat().coerceIn(0f, 1f)
                    val g1 = anchorDbs[i].toFloat()
                    val g2 = anchorDbs[i + 1].toFloat()
                    return (g1 + ratio * (g2 - g1)).toInt().toShort()
                }
            }
        }
        return anchorDbs.getOrNull(0) ?: 0
    }

    private fun applyPresetInternal(presetName: String) {
        val eq = equalizer

        // Professional Acoustic EQ Curves (mB = 1/100 dB):
        // Anchors: [60Hz Sub-bass, 230Hz Mid-bass, 910Hz Midrange, 3.6kHz Presence, 14kHz Brilliance]
        val bandDbs: List<Short> = when (presetName.lowercase().trim()) {
            "crystal clarity" -> listOf(200, -100, 150, 450, 650)
            "studio master" -> listOf(150, 0, 100, 200, 300)
            "bass booster", "bass boost" -> listOf(850, 550, 100, 100, 200)
            "deep sub" -> listOf(950, 400, -50, 100, 150)
            "bass reducer" -> listOf(-600, -350, 100, 200, 200)
            "electronic / edm", "electronic", "edm" -> listOf(750, 400, -100, 450, 750)
            "rock & metal", "rock", "metal" -> listOf(600, 300, -150, 400, 650)
            "hip-hop & r&b", "hip-hop", "r&b" -> listOf(850, 500, 50, 250, 450)
            "dance & club", "dance", "club" -> listOf(750, 450, 0, 500, 650)
            "pop commercial", "pop" -> listOf(400, 200, 250, 400, 500)
            "vocal & podcast", "vocal", "podcast" -> listOf(-350, -50, 600, 500, 150)
            "acoustic live", "acoustic" -> listOf(300, 250, 200, 350, 500)
            "classical concert", "classical" -> listOf(350, 200, 150, 300, 450)
            "jazz lounge", "jazz" -> listOf(400, 300, 150, 250, 400)
            "treble booster", "treble boost" -> listOf(-100, 0, 200, 600, 900)
            "treble reducer" -> listOf(200, 100, 0, -350, -600)
            "dynamic cinema", "cinema" -> listOf(700, 200, 300, 450, 600)
            "car audio (cabin dynamics)", "car audio", "car" -> listOf(800, -100, 300, 500, 700)
            "gaming & spatial", "gaming" -> listOf(450, 100, 400, 650, 500)
            else -> listOf(0, 0, 0, 0, 0) // Flat reference neutral
        }

        val updatedBands = _effectsState.value.bands.mapIndexed { index, band ->
            val targetLevel = if (_effectsState.value.bands.size == 5) {
                bandDbs.getOrElse(index) { 0.toShort() }
            } else {
                interpolateGain(band.centerFreqHz, bandDbs)
            }
            val clampedLevel = targetLevel.coerceIn(band.minLevelMb, band.maxLevelMb)
            try {
                eq?.setBandLevel(band.index, clampedLevel)
            } catch (e: Exception) {
                Log.w(TAG, "Band ${band.index} level could not be applied: ${e.message}")
            }
            band.copy(levelMb = clampedLevel)
        }

        val targetBass = when (presetName.lowercase().trim()) {
            "crystal clarity" -> 200
            "studio master" -> 150
            "bass booster", "bass boost" -> 850
            "deep sub" -> 950
            "bass reducer" -> 0
            "electronic / edm", "electronic", "edm" -> 750
            "dance & club", "dance", "club" -> 750
            "hip-hop & r&b", "hip-hop", "r&b" -> 800
            "rock & metal", "rock", "metal" -> 500
            "pop commercial", "pop" -> 400
            "car audio (cabin dynamics)", "car audio", "car" -> 550
            "acoustic live", "acoustic" -> 200
            "classical concert", "classical" -> 150
            "jazz lounge", "jazz" -> 350
            "treble booster", "treble boost" -> 100
            "treble reducer" -> 150
            "dynamic cinema", "cinema" -> 650
            "gaming & spatial", "gaming" -> 400
            "vocal & podcast", "vocal", "podcast" -> 0
            else -> 0 // Flat
        }

        val targetVirt = when (presetName.lowercase().trim()) {
            "crystal clarity" -> 450
            "studio master" -> 250
            "bass booster", "bass boost" -> 200
            "deep sub" -> 150
            "bass reducer" -> 100
            "electronic / edm", "electronic", "edm" -> 600
            "dance & club", "dance", "club" -> 550
            "rock & metal", "rock", "metal" -> 350
            "hip-hop & r&b", "hip-hop", "r&b" -> 300
            "pop commercial", "pop" -> 350
            "car audio (cabin dynamics)", "car audio", "car" -> 450
            "acoustic live", "acoustic" -> 450
            "classical concert", "classical" -> 500
            "jazz lounge", "jazz" -> 400
            "treble booster", "treble boost" -> 350
            "treble reducer" -> 100
            "dynamic cinema", "cinema" -> 750
            "gaming & spatial", "gaming" -> 800
            "vocal & podcast", "vocal", "podcast" -> 100
            else -> 0 // Flat
        }

        // Automatic headroom / loudness matching to prevent digital clipping
        val targetGain = when (presetName.lowercase().trim()) {
            "crystal clarity" -> 120
            "studio master" -> 80
            "bass booster", "bass boost" -> 250
            "deep sub" -> 200
            "bass reducer" -> 150
            "electronic / edm", "electronic", "edm" -> 220
            "dance & club", "dance", "club" -> 250
            "hip-hop & r&b", "hip-hop", "r&b" -> 240
            "rock & metal", "rock", "metal" -> 180
            "pop commercial", "pop" -> 160
            "car audio (cabin dynamics)", "car audio", "car" -> 350
            "vocal & podcast", "vocal", "podcast" -> 200
            "acoustic live", "acoustic" -> 120
            "classical concert", "classical" -> 100
            "jazz lounge", "jazz" -> 140
            "treble booster", "treble boost" -> 120
            "treble reducer" -> 100
            "dynamic cinema", "cinema" -> 300
            "gaming & spatial", "gaming" -> 250
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
