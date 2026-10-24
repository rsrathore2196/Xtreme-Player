package com.example.playback

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.Immutable
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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

    // UI mutations only hold this lock for small immutable-state updates. DSP
    // snapshots are published by the worker without invoking native audio effects.
    private val stateLock = Any()
    private var revision = 0L
    private data class Request(val revision: Long, val state: AudioEffectsState)
    private val commands = Channel<Unit>(Channel.CONFLATED)
    private val effectsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
        scope.launch {
            for (ignored in commands) {
                val request = synchronized(stateLock) { Request(revision, _effectsState.value) }
                try { reconcile(request) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Audio effect command failed", e) }
            }
        }
    }

    private fun updateDesired(transform: (AudioEffectsState) -> AudioEffectsState) {
        synchronized(stateLock) {
            val previous = _effectsState.value
            val next = transform(previous)
            if (next == previous) return
            revision++
            _effectsState.value = next
            commands.trySend(Unit)
        }
    }

    fun attachAudioSession(sessionId: Int) {
        if (sessionId > 0) updateDesired { it.copy(audioSessionId = sessionId) }
    }
    fun detachAudioSession() { updateDesired { it.copy(audioSessionId = 0) } }
    fun setEnabled(enabled: Boolean) { updateDesired { it.copy(isEnabled = enabled) } }
    fun setBandLevel(bandIndex: Short, levelMb: Short) { updateDesired { state ->
        val band = state.bands.firstOrNull { it.index == bandIndex } ?: return@updateDesired state
        val level = levelMb.coerceIn(band.minLevelMb, band.maxLevelMb)
        if (band.levelMb == level) state else state.copy(
            bands = state.bands.map { if (it.index == bandIndex) it.copy(levelMb = level) else it },
            selectedPreset = "Custom", crystalClarityEnabled = false)
    } }
    fun setBassBoost(strength: Int) { updateDesired { state ->
        val value = strength.coerceIn(0, 1000)
        if (value == state.bassBoostStrength) state else state.copy(bassBoostStrength = value,
            selectedPreset = "Custom", crystalClarityEnabled = false)
    } }
    fun setVirtualizer(strength: Int) { updateDesired { state ->
        val value = strength.coerceIn(0, 1000)
        if (value == state.virtualizerStrength) state else state.copy(virtualizerStrength = value,
            selectedPreset = "Custom", crystalClarityEnabled = false)
    } }
    fun setLoudnessGain(gainMb: Int) { updateDesired { state ->
        val value = gainMb.coerceIn(0, 800)
        if (value == state.loudnessGainMb) state else state.copy(loudnessGainMb = value,
            selectedPreset = "Custom", crystalClarityEnabled = false)
    } }
    fun applyPreset(presetName: String) { updateDesired { presetState(it, presetName) } }
    fun setCrystalClarityEnabled(enabled: Boolean) { updateDesired {
        if (it.crystalClarityEnabled == enabled) it
        else it.copy(crystalClarityEnabled = enabled, isEnabled = it.isEnabled || enabled)
    } }
    fun resetAll(enabled: Boolean? = null) { updateDesired { state ->
        presetState(state, "Flat").copy(isEnabled = enabled ?: state.isEnabled)
    } }

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

    // The render thread reads one complete immutable snapshot at buffer boundaries.
    // Coalescing keeps rapid slider changes cheap; obsolete worker states never win.
    private fun reconcile(request: Request) {
        synchronized(stateLock) {
            if (revision != request.revision) return
            PsychoacousticAudioProcessor.settings.set(request.state)
            AudioSignalPath.update { it.copy(clarityEnabled = request.state.crystalClarityEnabled,
                eqActive = request.state.isEnabled && !it.directApproved,
                dspActive = (request.state.isEnabled || it.spatialActive || it.skipSilenceActive) && !it.directApproved) }
        }
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

    private fun presetState(state: AudioEffectsState, presetName: String): AudioEffectsState {

        // Conservative tonal starting points for this five-band filter layout, not
        // headphone-specific correction. Keep bass/width stages separate to avoid stacking boosts.
        // Anchors: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz and 14 kHz. Levels are millibels.
        val bandDbs: List<Short> = when (presetName.lowercase().trim()) {
            "crystal clarity" -> listOf(0, -100, 50, 150, 200)
            "studio master" -> listOf(50, -50, 0, 50, 100)
            "bass booster", "bass boost" -> listOf(400, 200, 0, -50, 0)
            "deep sub" -> listOf(500, 100, -50, 0, 0)
            "bass reducer" -> listOf(-450, -250, 0, 0, 0)
            "electronic / edm", "electronic", "edm" -> listOf(350, 150, -100, 150, 200)
            "rock & metal", "rock", "metal" -> listOf(250, 100, -100, 200, 150)
            "hip-hop & r&b", "hip-hop", "r&b" -> listOf(400, 200, -50, 100, 100)
            "dance & club", "dance", "club" -> listOf(350, 150, -50, 150, 200)
            "pop commercial", "pop" -> listOf(150, 0, 50, 150, 150)
            "vocal & podcast", "vocal enhance", "vocal", "podcast" -> listOf(-250, -100, 150, 250, -50)
            "acoustic live", "acoustic" -> listOf(100, -50, 50, 150, 100)
            "classical concert", "classical" -> listOf(100, 0, -50, 50, 150)
            "jazz lounge", "jazz" -> listOf(150, 50, 0, 100, 100)
            "treble booster", "treble boost" -> listOf(0, 0, 50, 200, 350)
            "treble reducer" -> listOf(0, 0, 0, -200, -350)
            "dynamic cinema", "cinema" -> listOf(250, -50, 100, 150, 100)
            "car audio (cabin dynamics)", "car audio", "car" -> listOf(-100, -200, 50, 150, 100)
            "gaming & spatial", "gaming" -> listOf(-100, -100, 100, 250, 100)
            else -> listOf(0, 0, 0, 0, 0)
        }

        val updatedBands = state.bands.mapIndexed { index, band ->
            val targetLevel = if (state.bands.size == 5) {
                bandDbs.getOrElse(index) { 0.toShort() }
            } else {
                interpolateGain(band.centerFreqHz, bandDbs)
            }
            val clampedLevel = targetLevel.coerceIn(band.minLevelMb, band.maxLevelMb)
            band.copy(levelMb = clampedLevel)
        }

        // The curve already supplies its bass contour. Do not add a second bass
        // boost or positive make-up gain; the float processor retains its limiter/headroom.
        val targetBass = 0
        val targetVirt = when (presetName.lowercase().trim()) {
            "gaming & spatial", "gaming" -> 300
            "dynamic cinema", "cinema" -> 150
            else -> 0
        }
        val targetGain = 0

        return state.copy(
            bands = updatedBands,
            bassBoostStrength = targetBass,
            virtualizerStrength = targetVirt,
            loudnessGainMb = targetGain,
            selectedPreset = presetName,
            crystalClarityEnabled = presetName.equals("Crystal Clarity", ignoreCase = true)
        )
    }

}
