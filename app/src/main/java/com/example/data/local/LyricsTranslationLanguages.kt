package com.example.data.local

import androidx.compose.runtime.Immutable
import java.util.Locale

/** Translation targets supported by the existing lyrics translation service. Store BCP-47 tags only. */
object LyricsTranslationLanguages {
    const val SYSTEM = ""
    private val tags = ("af sq am ar hy as ay az bm eu be bn bho bs bg ca ceb zh-CN zh-TW co hr cs da dv doi nl en " +
        "eo et ee fil fi fr fy gl ka de el gn gu ht ha haw he hi hmn hu is ig ilo id ga it ja jv kn kk km rw " +
        "gom ko kri ku ckb ky lo la lv ln lt lg lb mk mai mg ms ml mt mi mr mni-Mtei lus mn my ne no ny or " +
        "om ps fa pl pt pa qu ro ru sm sa gd nso sr st sn sd si sk sl so es su sw sv tg ta tt te th ti ts tr " +
        "tk ak uk ur ug uz vi cy xh yi yo zu").split(' ')

    @Immutable
    data class Option(val tag: String, val name: String, val nativeName: String, val englishName: String) {
        fun matches(query: String): Boolean = listOf(tag, name, nativeName, englishName).any { it.contains(query.trim(), true) }
    }

    fun normalize(tag: String): String {
        if (tag.isBlank() || tag.equals("system", true)) return SYSTEM
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        val canonical = when (locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "tl" -> "fil"
            else -> locale.toLanguageTag()
        }
        return tags.firstOrNull { it.equals(canonical, true) }
            ?: tags.firstOrNull { it == locale.language } ?: SYSTEM
    }

    fun effectiveTag(preference: String, systemTag: String): String = normalize(preference).ifEmpty { systemTag }

    fun name(tag: String, displayLocale: Locale): String = when (val normalized = normalize(tag)) {
        SYSTEM -> "System default"
        "zh-CN" -> "Chinese (Simplified)"
        "zh-TW" -> "Chinese (Traditional)"
        "mni-Mtei" -> "Meiteilon (Manipuri)"
        "gom" -> "Konkani"
        else -> Locale.forLanguageTag(normalized).getDisplayLanguage(displayLocale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(displayLocale) else it.toString() }
    }

    /** Locale labels and sorting are prepared on the shared CPU lane by the picker. */
    fun options(displayLocale: Locale): List<Option> = listOf(Option(SYSTEM, "System default", "Follow app/device language", "System default")) +
        tags.map { tag ->
            val native = Locale.forLanguageTag(tag)
            Option(tag, name(tag, displayLocale), native.getDisplayName(native), name(tag, Locale.ENGLISH))
        }.sortedBy { it.name.lowercase(displayLocale) }
}
