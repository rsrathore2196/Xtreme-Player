package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
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

    private var _userProfileFlow: MutableStateFlow<UserProfile>? = null
    fun peekUserProfile(): UserProfile? = synchronized(this) { _userProfileFlow?.value }

    fun getUserProfileFlow(context: Context): StateFlow<UserProfile> {
        synchronized(this) {
            var flow = _userProfileFlow
            if (flow == null) {
                flow = MutableStateFlow(getUserProfile(context))
                _userProfileFlow = flow
            }
            return flow.asStateFlow()
        }
    }

    private fun profileEditor(context: Context, profile: UserProfile): SharedPreferences.Editor =
        getPrefs(context).edit()
            .putString(KEY_NAME, profile.name.trim())
            .putString(KEY_LANGUAGES, profile.languages.joinToString(","))
            .putString(KEY_COUNTRY, profile.country.trim())
            .putString(KEY_COUNTRY_CODE, profile.countryCode.trim())
            .putString(KEY_COUNTRY_FLAG, profile.flag.trim())
            .putString(KEY_APP_LANGUAGE, profile.appLanguage.trim())
            .putBoolean(KEY_ONBOARDING_COMPLETED, profile.isOnboardingCompleted)

    fun saveUserProfile(context: Context, profile: UserProfile) {
        profileEditor(context, profile).apply()
        publishUserProfile(profile)
    }

    // Restore needs a write result before committing its database transaction.
    internal fun persistUserProfile(context: Context, profile: UserProfile): Boolean =
        profileEditor(context, profile).commit()

    // Publish only once restore's Room transaction has successfully committed.
    internal fun publishUserProfile(profile: UserProfile) {
        synchronized(this) {
            val flow = _userProfileFlow
            if (flow == null) {
                _userProfileFlow = MutableStateFlow(profile)
            } else {
                flow.value = profile
            }
        }
    }

    fun isOnboardingCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }
}
