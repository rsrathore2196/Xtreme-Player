package com.example.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class iTunesSongResult(
    val artworkUrl100: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val durationMs: Long = 0L
)

data class iTunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<iTunesSongResult> = emptyList()
)

interface iTunesApiService {
    suspend fun searchSong(term: String, media: String = "music", limit: Int = 1): iTunesSearchResponse
}

class iTunesApiServiceImpl(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
) : iTunesApiService {

    override suspend fun searchSong(term: String, media: String, limit: Int): iTunesSearchResponse = withContext(Dispatchers.IO) {
        if (term.isBlank()) return@withContext iTunesSearchResponse()
        try {
            val encodedTerm = URLEncoder.encode(term.trim(), "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedTerm&media=$media&entity=song&limit=$limit"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "XtremePlayer/1.9.0 (Android)")
                .build()

            run {
                val body = client.catalogRequest(request)
                val root = JSONObject(body)
                val count = root.optInt("resultCount", 0)
                val resultsArr = root.optJSONArray("results") ?: return@withContext iTunesSearchResponse(count)
                val list = mutableListOf<iTunesSongResult>()
                for (i in 0 until resultsArr.length()) {
                    val obj = resultsArr.getJSONObject(i)
                    list.add(
                        iTunesSongResult(
                            artworkUrl100 = obj.optString("artworkUrl100").takeIf { it.isNotBlank() },
                            trackName = obj.optString("trackName").takeIf { it.isNotBlank() },
                            artistName = obj.optString("artistName").takeIf { it.isNotBlank() },
                            collectionName = obj.optString("collectionName").takeIf { it.isNotBlank() },
                            durationMs = obj.optLong("trackTimeMillis", 0L)
                        )
                    )
                }
                iTunesSearchResponse(resultCount = count, results = list)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
