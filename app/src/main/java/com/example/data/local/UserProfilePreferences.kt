package com.example.data.local

import android.content.Context
import android.content.SharedPreferences

data class UserProfile(
    val name: String = "",
    val languages: List<String> = listOf("English", "Hindi", "Punjabi"),
    val country: String = "India",
    val countryCode: String = "IN",
    val flag: String = "🇮🇳",
    val appLanguage: String = "English",
    val isOnboardingCompleted: Boolean = false
) {
    fun getInitials(): String {
        if (name.isBlank()) return "U"
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].first().uppercase()}${parts[1].first().uppercase()}"
            parts.isNotEmpty() -> parts[0].take(2).uppercase()
            else -> "U"
        }
    }
}

object UserProfilePreferences {
    private const val PREFS_NAME = "xtreme_user_profile_prefs"
    private const val KEY_NAME = "user_name"
    private const val KEY_LANGUAGES = "user_languages"
    private const val KEY_COUNTRY = "user_country"
    private const val KEY_COUNTRY_CODE = "user_country_code"
    private const val KEY_COUNTRY_FLAG = "user_country_flag"
    private const val KEY_APP_LANGUAGE = "user_app_language"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getUserProfile(context: Context): UserProfile {
        val prefs = getPrefs(context)
        val name = prefs.getString(KEY_NAME, "") ?: ""
        val languagesRaw = prefs.getString(KEY_LANGUAGES, "English,Hindi,Punjabi") ?: "English,Hindi,Punjabi"
        val languages = languagesRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val country = prefs.getString(KEY_COUNTRY, "India") ?: "India"
        val countryCode = prefs.getString(KEY_COUNTRY_CODE, "IN") ?: "IN"
        val flag = prefs.getString(KEY_COUNTRY_FLAG, "🇮🇳") ?: "🇮🇳"
        val appLanguage = prefs.getString(KEY_APP_LANGUAGE, "English") ?: "English"
        val isOnboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

        return UserProfile(
            name = name,
            languages = if (languages.isNotEmpty()) languages else listOf("English", "Hindi", "Punjabi"),
            country = country,
            countryCode = countryCode,
            flag = flag,
            appLanguage = appLanguage,
            isOnboardingCompleted = isOnboardingCompleted
        )
    }

    fun saveUserProfile(context: Context, profile: UserProfile) {
        val prefs = getPrefs(context)
        val languagesRaw = profile.languages.joinToString(",")
        prefs.edit()
            .putString(KEY_NAME, profile.name.trim())
            .putString(KEY_LANGUAGES, languagesRaw)
            .putString(KEY_COUNTRY, profile.country.trim())
            .putString(KEY_COUNTRY_CODE, profile.countryCode.trim())
            .putString(KEY_COUNTRY_FLAG, profile.flag.trim())
            .putString(KEY_APP_LANGUAGE, profile.appLanguage.trim())
            .putBoolean(KEY_ONBOARDING_COMPLETED, profile.isOnboardingCompleted)
            .apply()
    }

    fun isOnboardingCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }
}
