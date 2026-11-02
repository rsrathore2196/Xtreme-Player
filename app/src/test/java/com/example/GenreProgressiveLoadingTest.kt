package com.example

import android.app.Application
import com.example.data.remote.YouTubeExploreParser
import com.example.data.repository.CatalogRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenreProgressiveLoadingTest {
    @Test fun emptyNativeGenreResponsesRemainEmptyWithoutLocalCandidates() {
        try { YouTubeExploreParser.categories(JSONObject("{\"contents\":{}}")); fail("No local genre fallback is permitted") }
        catch (_: java.io.IOException) { }
    }
    @Test fun indexDiscoveryUsesOneNativeBrowseWithTheSelectedRegion() = runBlocking {
        val calls = mutableListOf<String>()
        val repo = CatalogRepository(browseLoader = { id, params, token, country, language ->
            calls += id
            assertEquals("GB", country); assertEquals("en", language)
            assertNull(params); assertNull(token)
            """{"contents":{"gridRenderer":{"items":[{"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"Jazz"}]},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_jazz","params":"opaque"}}}}]}}}"""
        })
        assertEquals(listOf("Jazz"), repo.exploreCategories("GB", "en").map { it.title })
        assertEquals(listOf("FEmusic_moods_and_genres"), calls)
    }
    @Test fun failedNativeDiscoveryDoesNotSynthesizeGenresOrFanOutSearches() = runBlocking {
        var calls = 0
        val repo = CatalogRepository(browseLoader = { _, _, _, _, _ -> calls++; throw java.io.IOException("Offline") })
        try { repo.exploreCategories("CA", "en"); fail("Failure must not fabricate local categories") }
        catch (_: java.io.IOException) { }
        assertEquals(1, calls)
    }
}
