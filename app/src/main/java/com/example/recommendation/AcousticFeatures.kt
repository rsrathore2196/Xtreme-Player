package com.example.recommendation

import com.example.data.model.MusicTrack
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * High-dimensional acoustic vector representing a musical track's physical & psychological characteristics:
 * - Tempo (BPM): 60 to 190 BPM
 * - Key: Pitch class (0 = C, 1 = C#, ..., 11 = B)
 * - Energy: Perceptual intensity and activity (0.0 to 1.0)
 * - Valence: Musical positiveness / mood (0.0 = dark/sad, 1.0 = euphoric/happy)
 * - Danceability: Regularity of rhythm and beat strength (0.0 to 1.0)
 * - Loudness: Overall loudness in decibels (-30.0 dB to 0.0 dB)
 * - Acousticness: Confidence measure of whether the track is acoustic (0.0 to 1.0)
 */
data class AcousticFeatures(
    val tempoBpm: Float,
    val key: Int,
    val energy: Float,
    val valence: Float,
    val danceability: Float,
    val loudnessDb: Float,
    val acousticness: Float
) {
    /**
     * Normalized Euclidean distance between two acoustic vectors (0.0 = identical, 1.0 = maximum distance).
     */
    fun normalizedDistance(other: AcousticFeatures): Float {
        val tempoDiff = ((tempoBpm - other.tempoBpm) / 120f).coerceIn(-1f, 1f).pow(2)
        val keyDiff = ((abs(key - other.key) % 6) / 6f).pow(2)
        val energyDiff = (energy - other.energy).pow(2)
        val valenceDiff = (valence - other.valence).pow(2)
        val danceDiff = (danceability - other.danceability).pow(2)
        val loudDiff = ((loudnessDb - other.loudnessDb) / 30f).coerceIn(-1f, 1f).pow(2)
        val acousticDiff = (acousticness - other.acousticness).pow(2)

        val sumSq = (tempoDiff * 0.25f) +
                (keyDiff * 0.05f) +
                (energyDiff * 0.25f) +
                (valenceDiff * 0.20f) +
                (danceDiff * 0.15f) +
                (loudDiff * 0.05f) +
                (acousticDiff * 0.05f)

        return sqrt(sumSq).coerceIn(0f, 1f)
    }

    /**
     * Percentage BPM difference between this track and a candidate track.
     */
    fun bpmDeltaPercent(other: AcousticFeatures): Float {
        val base = if (tempoBpm > 0) tempoBpm else 120f
        return abs(tempoBpm - other.tempoBpm) / base
    }

    companion object {
        // Curated precise acoustic vectors for known catalogue tracks
        private val KNOWN_FEATURES = mapOf(
            "xtreme_01" to AcousticFeatures(128f, 7, 0.85f, 0.65f, 0.78f, -5.2f, 0.08f), // Cybernetic Horizon (Synthwave)
            "xtreme_02" to AcousticFeatures(116f, 2, 0.72f, 0.55f, 0.70f, -6.8f, 0.12f), // Midnight Drive (Electronic)
            "xtreme_03" to AcousticFeatures(132f, 0, 0.88f, 0.48f, 0.82f, -4.5f, 0.05f), // Hyperdrive Echoes (Synthwave)
            "xtreme_04" to AcousticFeatures(82f,  4, 0.35f, 0.60f, 0.52f, -14.0f, 0.72f), // Starlight Lofi Chill (Chillhop)
            "xtreme_05" to AcousticFeatures(126f, 9, 0.92f, 0.72f, 0.85f, -4.0f, 0.04f), // Electric Pulse (Electronic)
            "xtreme_06" to AcousticFeatures(138f, 5, 0.86f, 0.58f, 0.62f, -5.0f, 0.15f), // Neon Boulevard (Rock)
            "xtreme_07" to AcousticFeatures(120f, 1, 0.78f, 0.80f, 0.75f, -5.8f, 0.18f), // Solar Flare (Pop)
            "xtreme_08" to AcousticFeatures(96f,  10, 0.45f, 0.68f, 0.65f, -11.2f, 0.65f), // Deep Velvet Groove (Jazz)
            "xtreme_09" to AcousticFeatures(92f,  3, 0.75f, 0.62f, 0.84f, -6.2f, 0.22f), // Underground Odyssey (Hip-Hop)
            "xtreme_10" to AcousticFeatures(75f,  6, 0.28f, 0.40f, 0.30f, -16.5f, 0.85f)  // Quantum Echoes (Ambient)
        )

        /**
         * Extracts or deterministically computes the audio acoustic vector embedding for any track.
         */
        fun forTrack(track: MusicTrack): AcousticFeatures {
            KNOWN_FEATURES[track.id]?.let { return it }

            // Content-based heuristic mapping for online and imported tracks
            val genreLower = track.genre.lowercase()
            val baseBpm = when {
                genreLower.contains("ambient") || genreLower.contains("meditation") -> 70f
                genreLower.contains("lofi") || genreLower.contains("lo-fi") || genreLower.contains("chill") -> 84f
                genreLower.contains("hip-hop") || genreLower.contains("hiphop") || genreLower.contains("rap") -> 94f
                genreLower.contains("jazz") || genreLower.contains("blues") -> 100f
                genreLower.contains("pop") || genreLower.contains("bollywood") || genreLower.contains("punjabi") -> 118f
                genreLower.contains("electronic") || genreLower.contains("dance") || genreLower.contains("edm") -> 128f
                genreLower.contains("synthwave") || genreLower.contains("rock") -> 134f
                else -> 115f
            }

            // Pseudo-hash seed based on title & artist to get stable, realistic variances
            val seed = (track.title.hashCode() xor track.artist.hashCode()).let { abs(it) }
            val bpmJitter = ((seed % 19) - 9).toFloat()
            val key = seed % 12

            val energy = when {
                genreLower.contains("ambient") -> 0.25f + ((seed % 15) / 100f)
                genreLower.contains("lofi") || genreLower.contains("chill") -> 0.38f + ((seed % 20) / 100f)
                genreLower.contains("electronic") || genreLower.contains("rock") -> 0.78f + ((seed % 18) / 100f)
                else -> 0.60f + ((seed % 25) / 100f)
            }.coerceIn(0.1f, 0.98f)

            val valence = (0.35f + ((seed / 7 % 55) / 100f)).coerceIn(0.1f, 0.95f)
            val danceability = when {
                genreLower.contains("electronic") || genreLower.contains("pop") || genreLower.contains("hip") -> 0.72f + ((seed % 20) / 100f)
                genreLower.contains("ambient") -> 0.28f + ((seed % 15) / 100f)
                else -> 0.58f + ((seed % 25) / 100f)
            }.coerceIn(0.15f, 0.95f)

            val loudness = when {
                genreLower.contains("ambient") -> -18.0f + ((seed % 6).toFloat())
                genreLower.contains("lofi") -> -13.0f + ((seed % 5).toFloat())
                else -> -6.5f + ((seed % 4).toFloat())
            }

            val acousticness = when {
                genreLower.contains("ambient") || genreLower.contains("chill") || genreLower.contains("jazz") -> 0.65f
                genreLower.contains("electronic") || genreLower.contains("synthwave") -> 0.08f
                else -> 0.25f
            }

            return AcousticFeatures(
                tempoBpm = (baseBpm + bpmJitter).coerceIn(60f, 185f),
                key = key,
                energy = energy,
                valence = valence,
                danceability = danceability,
                loudnessDb = loudness,
                acousticness = acousticness
            )
        }
    }
}
