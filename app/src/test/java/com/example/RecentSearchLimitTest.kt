package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ReactiveSettingsRepository
import com.example.data.local.RecentSearchPreferences
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class RecentSearchLimitTest {
    @Test fun oldHistoryIsCappedAndNewQueriesKeepTwentyMostRecentWithoutDuplicates() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val preferences = ReactiveSettingsRepository.preferences(app, "xtreme_recent_searches_prefs")
        preferences.edit().putString("recent_search_queries", JSONArray((1..25).map { "Query $it" }).toString()).apply()
        assertEquals((1..20).map { "Query $it" }, RecentSearchPreferences.getRecentSearches(app))
        assertEquals(listOf("query 3", "Query 1", "Query 2") + (4..20).map { "Query $it" },
            RecentSearchPreferences.addRecentSearch(app, "query 3"))
        val saved = RecentSearchPreferences.addRecentSearch(app, "Newest")
        assertEquals(20, saved.size)
        assertEquals("Newest", saved.first())
        assertEquals(saved, RecentSearchPreferences.getRecentSearches(app))
        assertEquals(saved.drop(1), RecentSearchPreferences.removeRecentSearch(app, "Newest"))
        RecentSearchPreferences.clearRecentSearches(app)
        assertTrue(RecentSearchPreferences.getRecentSearches(app).isEmpty())
    }
}
