package com.example.recommendation

import android.util.Log
import com.example.data.model.MusicTrack
import kotlin.math.abs

/**
 * Advanced Vibe and Mood Matching Engine.
 *
 * Implements mood classification and tempo/feel alignment:
 * - Extracts emotional mood tags (Romantic, Sad/Melancholic, Workout/Energetic, Party/Dance, Chill/Lofi, etc.)
 *   using multilingual keywords (Hindi, Punjabi, English) + high-dimensional acoustic vectors.
 * - Computes BPM (tempo) and acoustic energy/valence similarity so transitions maintain a natural "feel".
 */
object MoodVibeAnalyzer {
    private const val TAG = "MoodVibeAnalyzer"

    // Primary High-Level Mood Categories
    const val MOOD_ROMANTIC = "Romantic"
    const val MOOD_SAD_MELANCHOLIC = "Sad & Melancholic"
    const val MOOD_PARTY_DANCE = "Party & Dance"
    const val MOOD_WORKOUT_ENERGETIC = "Workout & Energetic"
    const val MOOD_CHILL_LOFI = "Chill & Lo-Fi"
    const val MOOD_DEVOTIONAL_SUFI = "Devotional & Sufi"
    const val MOOD_UPBEAT_HAPPY = "Upbeat & Feel Good"

    // Multilingual mood keyword lexicons (Hindi, Punjabi, English)
    private val ROMANTIC_KEYWORDS = setOf(
        "romantic", "love", "heart", "ishq", "pyaar", "mohabbat", "slow dance",
        "dil", "humsafar", "soulmate", "deewana", "sanam", "chand", "hawa",
        "tere bin", "raatan", "kasam", "pehla nasha", "tum hi ho", "channa"
    )

    private val SAD_KEYWORDS = setOf(
        "sad", "heartbreak", "dard", "judaai", "breakup", "alone", "crying",
        "melancholy", "tears", "tanhai", "gham", "bichhad", "broken", "rona",
        "alvida", "yaad", "dukh", "bewafa", "chhod", "lonely", "darkness", "zakhmi"
    )

    private val PARTY_KEYWORDS = setOf(
        "party", "dance", "club", "bhangra", "daru", "nach", "dj", "bass",
        "groove", "thumka", "dhol", "shava", "patiala", "peg", "beat", "edm",
        "billo", "gabru", "disco", "sharab", "hookah", "drop", "remix"
    )

    private val WORKOUT_KEYWORDS = setOf(
        "workout", "gym", "pump", "motivation", "energy", "power", "beast",
        "run", "fast", "hype", "biceps", "jatt", "badmashi", "unstoppable",
        "fire", "rage", "warrior", "sultan", "tiger", "roar", "fighter"
    )

    private val CHILL_KEYWORDS = setOf(
        "chill", "lofi", "relax", "rain", "peace", "calm", "soothing",
        "acoustic", "midnight", "slowed", "reverb", "coffee", "night drive",
        "sleep", "gentle", "ambient", "meditation", "breeze", "floating"
    )

    private val SUFI_KEYWORDS = setOf(
        "bhajan", "kirtan", "sufi", "spiritual", "peaceful", "gurbani",
        "divine", "prayer", "shabad", "qawwali", "waheguru", "radhe", "krishna",
        "sai", "allah", "moula", "fakira", "rooh"
    )

    data class MoodProfile(
        val primaryMood: String,
        val tags: Set<String>,
        val tempoBpm: Float,
        val energy: Float,
        val valence: Float,
        val danceability: Float
    )

    data class VibeMatchResult(
        val vibeScore: Double, // 0 to 35 points
        val bpmScore: Double,  // 0 to 25 points
        val matchingTags: Set<String>,
        val bpmDifference: Float,
        val isVibeMatch: Boolean
    )

    /**
     * Analyzes track title, album, artist, genre, and acoustic features
     * to extract comprehensive mood tags and acoustic metadata.
     */
    fun analyzeMood(track: MusicTrack): MoodProfile {
        val features = AcousticFeatures.forTrack(track)
        val textToScan = "${track.title} ${track.album} ${track.artist} ${track.genre} ${track.writer}".lowercase()

        val detectedTags = mutableSetOf<String>()

        // 1. Keyword-based matching
        if (ROMANTIC_KEYWORDS.any { textToScan.contains(it) }) {
            detectedTags.add("romantic")
            detectedTags.add("love")
        }
        if (SAD_KEYWORDS.any { textToScan.contains(it) }) {
            detectedTags.add("sad")
            detectedTags.add("melancholic")
        }
        if (PARTY_KEYWORDS.any { textToScan.contains(it) }) {
            detectedTags.add("party")
            detectedTags.add("dance")
        }
        if (WORKOUT_KEYWORDS.any { textToScan.contains(it) }) {
            detectedTags.add("workout")
            detectedTags.add("energetic")
        }
        if (CHILL_KEYWORDS.any { textToScan.contains(it) }) {
            detectedTags.add("chill")
            detectedTags.add("lofi")
        }
        if (SUFI_KEYWORDS.any { textToScan.contains(it) }) {
            detectedTags.add("sufi")
            detectedTags.add("spiritual")
        }

        // 2. Acoustic Feature-based augmentation (Machine Learning / Psychological feel approximation)
        // High energy & high BPM
        if (features.energy >= 0.75f && features.tempoBpm >= 120f) {
            detectedTags.add("energetic")
            if (features.danceability >= 0.65f) detectedTags.add("dance")
        }
        // Low valence & low energy (Sad / Melancholic feel)
        if (features.valence < 0.42f && features.energy < 0.52f) {
            detectedTags.add("melancholic")
            if (features.tempoBpm < 95f) detectedTags.add("sad")
        }
        // High valence & high danceability (Upbeat / Party feel)
        if (features.valence >= 0.60f && features.danceability >= 0.65f) {
            detectedTags.add("upbeat")
            detectedTags.add("feel-good")
        }
        // Mellow acoustic (Chill / Romantic feel)
        if (features.acousticness >= 0.45f && features.tempoBpm in 70f..115f) {
            detectedTags.add("chill")
            if (features.valence in 0.35f..0.75f) detectedTags.add("romantic")
        }

        // 3. Determine Primary Mood
        val primary = when {
            detectedTags.contains("sad") || detectedTags.contains("melancholic") -> MOOD_SAD_MELANCHOLIC
            detectedTags.contains("party") || (detectedTags.contains("dance") && features.energy >= 0.70f) -> MOOD_PARTY_DANCE
            detectedTags.contains("workout") || (features.energy >= 0.82f && features.tempoBpm >= 125f) -> MOOD_WORKOUT_ENERGETIC
            detectedTags.contains("romantic") -> MOOD_ROMANTIC
            detectedTags.contains("sufi") || detectedTags.contains("spiritual") -> MOOD_DEVOTIONAL_SUFI
            detectedTags.contains("chill") || detectedTags.contains("lofi") -> MOOD_CHILL_LOFI
            detectedTags.contains("upbeat") || features.valence >= 0.65f -> MOOD_UPBEAT_HAPPY
            features.energy >= 0.65f -> MOOD_PARTY_DANCE
            else -> MOOD_CHILL_LOFI
        }

        // Add primary category slug to tags
        when (primary) {
            MOOD_ROMANTIC -> detectedTags.add("romantic")
            MOOD_SAD_MELANCHOLIC -> detectedTags.add("sad")
            MOOD_PARTY_DANCE -> detectedTags.add("party")
            MOOD_WORKOUT_ENERGETIC -> detectedTags.add("workout")
            MOOD_CHILL_LOFI -> detectedTags.add("chill")
            MOOD_DEVOTIONAL_SUFI -> detectedTags.add("sufi")
            MOOD_UPBEAT_HAPPY -> detectedTags.add("upbeat")
        }

        return MoodProfile(
            primaryMood = primary,
            tags = detectedTags,
            tempoBpm = features.tempoBpm,
            energy = features.energy,
            valence = features.valence,
            danceability = features.danceability
        )
    }

    /**
     * Compares the mood profile of the currently playing track with a candidate track.
     * Computes:
     * 1. Vibe Score (up to 35 points)
     * 2. BPM Proximity Score (up to 25 points)
     */
    fun computeVibeMatch(current: MoodProfile, candidate: MoodProfile): VibeMatchResult {
        val matchingTags = current.tags.intersect(candidate.tags)
        val isSamePrimary = current.primaryMood == candidate.primaryMood

        // Vibe scoring (max 35 points)
        var vibeScore = 0.0
        if (isSamePrimary) {
            vibeScore += 20.0
        } else if (isCompatibleMood(current.primaryMood, candidate.primaryMood)) {
            vibeScore += 12.0
        }

        // Shared tag points (5 points each, up to 10 points)
        val tagPoints = (matchingTags.size * 5.0).coerceAtMost(10.0)
        vibeScore += tagPoints

        // Emotional valence and energy continuity (up to 5 points)
        val valenceDiff = abs(current.valence - candidate.valence)
        val energyDiff = abs(current.energy - candidate.energy)
        val continuityPoints = ((1.0 - ((valenceDiff + energyDiff) / 2.0)) * 5.0).coerceIn(0.0, 5.0)
        vibeScore += continuityPoints
        vibeScore = vibeScore.coerceIn(0.0, 35.0)

        // BPM proximity scoring (max 25 points)
        val bpmDiff = abs(current.tempoBpm - candidate.tempoBpm)
        val baseBpm = if (current.tempoBpm > 0) current.tempoBpm else 120f
        val bpmDeltaPercent = bpmDiff / baseBpm

        val bpmScore = when {
            bpmDeltaPercent <= 0.08f -> 25.0 // within 8% BPM: seamless rhythmic blend
            bpmDeltaPercent <= 0.15f -> 18.0 // within 15% BPM: smooth natural tempo
            bpmDeltaPercent <= 0.25f -> 10.0 // within 25% BPM: acceptable tempo change
            bpmDeltaPercent <= 0.35f -> 5.0  // noticeable tempo shift
            else -> 0.0                      // jarring tempo jump
        }

        val isVibeMatch = isSamePrimary || matchingTags.isNotEmpty()

        return VibeMatchResult(
            vibeScore = vibeScore,
            bpmScore = bpmScore,
            matchingTags = matchingTags,
            bpmDifference = bpmDiff,
            isVibeMatch = isVibeMatch
        )
    }

    private fun isCompatibleMood(m1: String, m2: String): Boolean {
        return (m1 == MOOD_ROMANTIC && m2 == MOOD_CHILL_LOFI) ||
                (m1 == MOOD_CHILL_LOFI && m2 == MOOD_ROMANTIC) ||
                (m1 == MOOD_PARTY_DANCE && m2 == MOOD_WORKOUT_ENERGETIC) ||
                (m1 == MOOD_WORKOUT_ENERGETIC && m2 == MOOD_PARTY_DANCE) ||
                (m1 == MOOD_UPBEAT_HAPPY && m2 == MOOD_PARTY_DANCE) ||
                (m1 == MOOD_DEVOTIONAL_SUFI && m2 == MOOD_CHILL_LOFI)
    }
}
