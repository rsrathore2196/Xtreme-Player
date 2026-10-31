package com.example

import android.app.Application
import com.example.data.cache.HomeCacheIdentity
import com.example.data.local.UserProfile
import com.example.data.remote.YouTubeHomeParser
import com.example.recommendation.HomeUserMode
import com.example.recommendation.DiscoveryRequestCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NewUserHomePolicyTest {
    @Test fun discoveryKeysDeduplicateEquivalentWhitespaceAndCase() {
        assertEquals(DiscoveryRequestCoordinator.normalize("Punjabi new releases Canada"), DiscoveryRequestCoordinator.normalize("  PUNJABI   new\treleases Canada  "))
    }
    @Test fun onlyAuthoritativeHistoryConfirmsANewUser() {
        assertEquals(HomeUserMode.NEW_USER, HomeUserMode.confirmed(0, false))
        assertEquals(HomeUserMode.EXISTING_USER, HomeUserMode.confirmed(4000, false))
        assertNotEquals(HomeUserMode.NEW_USER, HomeUserMode.UNAVAILABLE)
    }
    @Test fun musicLanguageChangesCannotInvalidateRegionalHomeIncludingAtColdStartup() {
        val profile = UserProfile(countryCode = "IN", languages = listOf("Hindi"))
        val changed = profile.copy(languages = listOf("Punjabi", "English"))
        assertEquals(HomeCacheIdentity.home(profile), HomeCacheIdentity.home(changed))
        assertNotEquals(HomeCacheIdentity.profile(profile), HomeCacheIdentity.profile(changed))
    }
    @Test fun profileIdentityNormalizesLanguageCaseOrderAndWhitespace() {
        val profile = UserProfile(countryCode = "CA", languages = listOf(" Punjabi ", "English"))
        assertEquals(HomeCacheIdentity.profile(profile), HomeCacheIdentity.profile(profile.copy(languages = listOf("english", "punjabi", "English"))))
    }
    @Test fun countryAndAccountChangesPartitionBothCacheIdentities() {
        val profile = UserProfile(countryCode = "GB", languages = listOf("English"))
        assertNotEquals(HomeCacheIdentity.home(profile, "account-a"), HomeCacheIdentity.home(profile, "account-b"))
        assertNotEquals(HomeCacheIdentity.profile(profile, "account-a"), HomeCacheIdentity.profile(profile, "account-b"))
        assertNotEquals(HomeCacheIdentity.home(profile), HomeCacheIdentity.home(profile.copy(countryCode = "DE")))
    }
    @Test fun serverShelvesDoNotDependOnLocalListeningOrMinimumTasteCounts() {
        val feed = YouTubeHomeParser.parseHomeFeed(org.json.JSONObject(NativeFeedFixtures.home(NativeFeedFixtures.shelf("Native discoveries", 2, 1))))
        assertEquals(1, feed.shelves.size); assertEquals(2, feed.shelves.single().tracks.size)
        assertEquals(HomeUserMode.NEW_USER, HomeUserMode.confirmed(0, false))
    }
}
