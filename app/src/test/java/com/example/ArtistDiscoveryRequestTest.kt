package com.example

import android.app.Application
import com.example.data.remote.YouTubeAudioStreamService
import com.example.data.remote.YouTubeExploreParser
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ArtistDiscoveryRequestTest {
    @Test fun artistFilterSurvivesNativeSearchAndTheVerifiedBrowseRequestUsesItsChannel() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val artistParams = "EgWKAQIgAWoKEAkQChAFEAMQBA=="
        val artist = JSONObject("""{"contents":{"musicShelfRenderer":{"contents":[{
            "musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCverified",
            "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Exact Artist"}]}}}],
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/profile=w600-h600","width":600}]}}}}
        }]}}}""")
        val profile = """{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Exact Artist"}]},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/profile=w1200-h1200","width":1200}]}}}}},"contents":{}}"""
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer().also { chain.request().body?.writeTo(it) }
            val request = JSONObject(buffer.readUtf8())
            val path = chain.request().url.encodedPath
            requests += path to request
            val response = when {
                path.endsWith("/search") && request.optString("params") == artistParams -> artist.toString()
                path.endsWith("/browse") -> profile
                else -> "{\"contents\":{}}"
            }
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(response.toResponseBody()).build()
        }.build()
        val http = HttpClient(OkHttp) {
            engine { preconfigured = network }
            install(ContentNegotiation) { json(YouTubeAudioStreamService.playerJson) }
        }
        val inner = InnerTube(http).apply { visitorData = "fixture-visitor" }
        try {
            val body = YouTubeAudioStreamService.requestDiscovery(inner, "search", "Exact Artist", artistParams)
            val match = YouTubeExploreParser.artistMatches(JSONObject(body), "Exact Artist").single()
            assertEquals("UCverified", match.id)
            val page = YouTubeAudioStreamService.requestDiscovery(inner, "browse", match.id)
            val result = YouTubeExploreParser.artistProfile(JSONObject(page), match)
            assertEquals("UCverified", result.artist.id)
            assertTrue(result.artist.image.contains("profile=w1200"))
            assertEquals(2, requests.size)
            assertEquals(artistParams, requests[0].second.getString("params"))
            assertEquals("UCverified", requests[1].second.getString("browseId"))
            // Ordinary recommendation discovery retains its existing songs-only filter.
            YouTubeAudioStreamService.requestDiscovery(inner, "search", "Song")
            assertEquals("EgWKAQIIAWoKEAkQBRAKEAMQBA==", requests.last().second.getString("params"))
        } finally { inner.close(); http.close() }
    }
}
