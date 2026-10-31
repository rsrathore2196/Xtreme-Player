package com.example.data.cache

import com.example.data.local.UserProfile
import com.example.data.remote.YouTubeMusicApiService
import java.util.Locale

internal object HomeCacheIdentity {
    fun region(profile: UserProfile): String = profile.countryCode.trim().uppercase(Locale.ROOT)
        .takeIf { it in Locale.getISOCountries() } ?: Locale.getDefault().country.takeIf { it in Locale.getISOCountries() } ?: "US"
    fun home(profile: UserProfile, account: String = YouTubeMusicApiService.discoveryAccountIdentity()): String =
        "${region(profile)}|$account"
    fun profile(profile: UserProfile, account: String = YouTubeMusicApiService.discoveryAccountIdentity()): String =
        home(profile, account) + "|" + profile.languages.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().sorted().joinToString(",")
    fun fileKey(key: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
