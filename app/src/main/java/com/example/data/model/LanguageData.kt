package com.example.data.model

data class LanguageOption(
    val id: String,
    val name: String,
    val nativeScript: String,
    val searchKeyword: String
)

object LanguageData {
    val allLanguages: List<LanguageOption> = listOf(
        LanguageOption("en", "English", "English", "English Pop Hits"),
        LanguageOption("pa", "Punjabi", "ਪੰਜਾਬੀ", "Top Punjabi Hits"),
        LanguageOption("hi", "Hindi", "हिन्दी", "Latest Bollywood Hits"),
        LanguageOption("es", "Spanish", "Español", "Top Latin Hits"),
        LanguageOption("ko", "Korean", "한국어", "K-Pop Hits"),
        LanguageOption("ja", "Japanese", "日本語", "J-Pop Anime Hits"),
        LanguageOption("fr", "French", "Français", "French Hits"),
        LanguageOption("de", "German", "Deutsch", "German Pop Hits"),
        LanguageOption("ar", "Arabic", "العربية", "Arabic Hits"),
        LanguageOption("te", "Telugu", "తెలుగు", "Telugu Top Hits"),
        LanguageOption("ta", "Tamil", "தமிழ்", "Tamil Top Hits"),
        LanguageOption("bn", "Bengali", "বাংলা", "Bengali Hits"),
        LanguageOption("mr", "Marathi", "मराठी", "Marathi Hits"),
        LanguageOption("ur", "Urdu", "اردو", "Urdu Ghazal Hits"),
        LanguageOption("it", "Italian", "Italiano", "Italian Hits"),
        LanguageOption("pt", "Portuguese", "Português", "Brazilian Hits"),
        LanguageOption("tr", "Turkish", "Türkçe", "Turkish Hits"),
        LanguageOption("ru", "Russian", "Русский", "Russian Hits"),
        LanguageOption("zh", "Chinese", "中文", "Mandopop Hits"),
        LanguageOption("ml", "Malayalam", "മലയാളം", "Malayalam Hits"),
        LanguageOption("kn", "Kannada", "ಕನ್ನಡ", "Kannada Hits"),
        LanguageOption("gu", "Gujarati", "ગુજરાતી", "Gujarati Hits"),
        LanguageOption("bho", "Bhojpuri", "भोजपुरी", "Bhojpuri Hits")
    )
}
