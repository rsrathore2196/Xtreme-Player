package com.example.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
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

    // UI mutations only hold this lock for small immutable-state updates. Native
    // audiofx calls are owned exclusively by the IO worker and never hold it.
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
        else presetState(it, if (enabled) "Crystal Clarity" else "Flat")
    } }
    fun resetAll(enabled: Boolean? = null) { updateDesired { state ->
        presetState(state, "Flat").copy(isEnabled = enabled ?: state.isEnabled)
    } }

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

    private var sessionBands: List<BandState> = emptyList()
    private var appliedState: AudioEffectsState? = null
    private var initialized = false

    private fun current(request: Request): Boolean = synchronized(stateLock) { revision == request.revision }

    /** Reconcile a complete snapshot, never an obsolete individual slider/preset command. */
    private fun reconcile(request: Request) {
        if (!current(request)) return
        val desired = request.state
        if (desired.audioSessionId != currentSessionId) {
            releaseEffects()
            currentSessionId = desired.audioSessionId
        }
        if (!current(request)) return
        if (!desired.isEnabled || currentSessionId <= 0) {
            releaseEffects()
            return
        }
        if (!initialized) {
            initEffectsForSession(currentSessionId)
            initialized = true
        }
        if (!current(request)) return
        val previous = appliedState
        // Any interruption requires the next snapshot to reconcile all native values.
        appliedState = null
        val bands = if (sessionBands.isEmpty()) desired.bands else if (desired.selectedPreset != "Custom") {
            presetState(desired.copy(bands = sessionBands), desired.selectedPreset).bands
        } else sessionBands.map { band ->
            val points = desired.bands.sortedBy { it.centerFreqHz }
            val lower = points.lastOrNull { it.centerFreqHz <= band.centerFreqHz } ?: points.firstOrNull()
            val upper = points.firstOrNull { it.centerFreqHz >= band.centerFreqHz } ?: points.lastOrNull()
            val level = if (lower == null || upper == null) 0 else if (lower.centerFreqHz == upper.centerFreqHz) lower.levelMb.toInt()
            else {
                val fraction = ((ln(band.centerFreqHz.coerceAtLeast(1).toDouble()) - ln(lower.centerFreqHz.coerceAtLeast(1).toDouble())) /
                    (ln(upper.centerFreqHz.coerceAtLeast(1).toDouble()) - ln(lower.centerFreqHz.coerceAtLeast(1).toDouble()))).coerceIn(0.0, 1.0)
                (lower.levelMb + (upper.levelMb - lower.levelMb) * fraction).toInt()
            }
            band.copy(levelMb = level.coerceIn(band.minLevelMb.toInt(), band.maxLevelMb.toInt()).toShort())
        }
        val actualBands = ArrayList<BandState>(bands.size)
        for (band in bands) {
            if (!current(request)) return
            val eq = equalizer
            val prior = previous?.bands?.firstOrNull { it.index == band.index }
            var actual = band
            if (eq != null && prior != band) {
                effect("Equalizer band") { eq.setBandLevel(band.index, band.levelMb) }
                effect("Read equalizer band") { actual = band.copy(levelMb = eq.getBandLevel(band.index)) }
            }
            actualBands.add(actual)
        }
        var actual = desired.copy(bands = actualBands)
        if (!current(request)) return
        if (previous?.bassBoostStrength != desired.bassBoostStrength) bassBoost?.let { bb ->
            effect("Bass boost") { bb.setStrength(desired.bassBoostStrength.toShort()) }
            effect("Read bass boost") { actual = actual.copy(bassBoostStrength = bb.roundedStrength.toInt()) }
        }
        if (!current(request)) return
        if (previous?.virtualizerStrength != desired.virtualizerStrength) virtualizer?.let { virt ->
            effect("Virtualizer") { virt.setStrength(desired.virtualizerStrength.toShort()) }
            effect("Read virtualizer") { actual = actual.copy(virtualizerStrength = virt.roundedStrength.toInt()) }
        }
        if (!current(request)) return
        if (previous?.loudnessGainMb != desired.loudnessGainMb) loudnessEnhancer?.let { loudness ->
            effect("Loudness") { loudness.setTargetGain(desired.loudnessGainMb) }
            effect("Read loudness") { actual = actual.copy(loudnessGainMb = loudness.targetGain.toInt()) }
        }
        if (!current(request)) return
        if (previous == null) {
            effect("Enable equalizer") { equalizer?.enabled = true }
            if (!current(request)) return
            effect("Enable bass boost") { bassBoost?.enabled = true }
            if (!current(request)) return
            effect("Enable virtualizer") { virtualizer?.enabled = true }
            if (!current(request)) return
            effect("Enable loudness") { loudnessEnhancer?.enabled = true }
        }
        synchronized(stateLock) {
            if (revision == request.revision) {
                appliedState = actual
                if (_effectsState.value != actual) _effectsState.value = actual
            }
        }
    }

    private fun effect(name: String, operation: () -> Unit) {
        try { operation() }
        catch (e: Exception) { Log.w(TAG, "$name could not be applied: ${e.message}") }
    }

    private fun initEffectsForSession(sessionId: Int) {
        effect("Equalizer initialization") {
            equalizer = Equalizer(0, sessionId)
            val eq = equalizer ?: return@effect
            eq.enabled = false
            val range = eq.bandLevelRange
            sessionBands = (0 until eq.numberOfBands.toInt()).map { i ->
                BandState(i.toShort(), eq.getCenterFreq(i.toShort()) / 1000, 0,
                    range.getOrElse(0) { -1500 }, range.getOrElse(1) { 1500 })
            }
        }
        effect("BassBoost initialization") {
            val bb = BassBoost(0, sessionId)
            if (bb.strengthSupported) { bassBoost = bb; bb.enabled = false } else bb.release()
        }
        effect("Virtualizer initialization") {
            val virt = Virtualizer(0, sessionId)
            if (virt.strengthSupported) { virtualizer = virt; virt.enabled = false } else virt.release()
        }
        effect("LoudnessEnhancer initialization") {
            loudnessEnhancer = LoudnessEnhancer(sessionId)
            loudnessEnhancer?.enabled = false
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

        val updatedBands = state.bands.mapIndexed { index, band ->
            val targetLevel = if (state.bands.size == 5) {
                bandDbs.getOrElse(index) { 0.toShort() }
            } else {
                interpolateGain(band.centerFreqHz, bandDbs)
            }
            val clampedLevel = targetLevel.coerceIn(band.minLevelMb, band.maxLevelMb)
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

        return state.copy(
            bands = updatedBands,
            bassBoostStrength = targetBass,
            virtualizerStrength = targetVirt,
            loudnessGainMb = targetGain,
            selectedPreset = presetName,
            crystalClarityEnabled = presetName.equals("Crystal Clarity", ignoreCase = true)
        )
    }

    private fun releaseEffects() {
        // Clear ownership before native release, even when a vendor implementation throws.
        val effects = listOfNotNull<android.media.audiofx.AudioEffect>(equalizer, bassBoost, virtualizer, loudnessEnhancer)
        equalizer = null; bassBoost = null; virtualizer = null; loudnessEnhancer = null
        sessionBands = emptyList(); appliedState = null; initialized = false
        effects.forEach { value ->
            effect("Disable audio effect") { value.enabled = false }
            effect("Release audio effect") { value.release() }
        }
    }
}
