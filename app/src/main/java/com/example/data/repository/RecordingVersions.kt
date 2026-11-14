package com.example.data.repository

import java.util.Locale

/** Recording labels are identity evidence, never noise to remove from a playback request. */
internal object RecordingVersions {
    private val labels = Regex(
        "(?i)\\b(?:live|re[ -]?mix(?:ed)?|rmx|mix(?:ed)?|remaster(?:ed)?|radio[ -]?edit|" +
            "acoustic|unplugged|instrumental|karaoke|cover|slow(?:ed)?(?:[ -]?down)?|" +
            "reverb(?:ed|erated)?|(?:sped|speed)[ -]?up|nightcore|daycore|lo[ -]?fi|" +
            "bass[ -]?boost(?:ed)?|[238]d(?:[ -]?audio)?|mash[ -]?up|bootleg|" +
            "chopped|screwed|extended|demo|mono|stereo|edit)\\b"
    )
    private val slowedReverb = Regex("(?i)\\bslowed\\s*(?:and|[+&])\\s*reverb\\b")
    private fun canonical(value: String): String = when (val label = value.lowercase(Locale.ROOT).replace("-", " ")) {
        "remastered" -> "remaster"
        "re mix", "re mixed", "remixed", "rmx" -> "remix"
        "mixed" -> "mix"
        "slow", "slow down", "slowdown", "slowed down", "sloweddown" -> "slowed"
        "reverbed", "reverberated" -> "reverb"
        "spedup", "speedup", "speed up" -> "sped up"
        "lo fi" -> "lofi"
        "bass boost", "bass boosted" -> "bass boosted"
        "mash up" -> "mashup"
        else -> label.removeSuffix(" audio")
    }
    fun markers(title: String): Set<String> = labels.findAll(title).map { canonical(it.value) }.toSet()
    fun normalize(title: String): String = labels.replace(title) { canonical(it.value) }
        .replace(slowedReverb, "slowed reverb")
}
