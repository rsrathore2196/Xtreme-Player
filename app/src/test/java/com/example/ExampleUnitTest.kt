package com.example

import com.example.playback.AudioEffectsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testAudioEffectsPresets() {
        AudioEffectsManager.applyPreset("Bass Boost")
        var state = AudioEffectsManager.effectsState.value
        assertEquals("Bass Boost", state.selectedPreset)
        assertTrue(state.bassBoostStrength > 800)

        AudioEffectsManager.applyPreset("Flat")
        state = AudioEffectsManager.effectsState.value
        assertEquals("Flat", state.selectedPreset)
        assertEquals(400, state.bassBoostStrength)
    }

    @Test
    fun testAudioEffectsBandAdjustment() {
        AudioEffectsManager.setBandLevel(0.toShort(), 600.toShort())
        val state = AudioEffectsManager.effectsState.value
        assertEquals("Custom", state.selectedPreset)
        assertEquals(600.toShort(), state.bands[0].levelMb)
    }

    @Test
    fun testAudioEffectsToggle() {
        AudioEffectsManager.setEnabled(false)
        assertFalse(AudioEffectsManager.effectsState.value.isEnabled)

        AudioEffectsManager.setEnabled(true)
        assertTrue(AudioEffectsManager.effectsState.value.isEnabled)
    }
}
