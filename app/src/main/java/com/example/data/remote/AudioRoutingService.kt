package com.example.data.remote

import android.content.Context
import com.example.data.local.AudioQualityPreferences
import com.example.data.model.MusicTrack
import com.example.playback.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RoutedSearchResult(
    val tracks: List<MusicTrack>,
    val isLossless: Boolean
)

/**
 * Audio Routing Service implementing 3-Tier Multi-Source Search & Playback Fallback Routing:
 *
 * SCENARIO A: User Settings = "Hi-Res Lossless"
 *   1. Primary Source (Internet Archive API):
 *      - Strict Lossless format verification (FLAC, WAV, AIFF, ALAC, APE, WavPack, DSD).
 *      - If valid streamable lossless audio verified, returns lossless stream and isLossless = true.
 *   2. Secondary Source (JioSaavn API Fallback):
 *      - Triggered if Internet Archive has no lossless files, times out, or fails.
 *      - Returns 320kbps CD-quality audio stream.
 *   3. Tertiary Source (YouTube Music API Fallback):
 *      - Triggered if JioSaavn has no matching results or fails.
 *
 * SCENARIO B: User Settings != "Hi-Res Lossless" (Standard / High / Ultra HD)
 *   1. Primary Source: JioSaavn API (highest available quality stream, e.g. 320kbps).
 *   2. Secondary Source Fallback: YouTube Music API.
 *   (Internet Archive API is bypassed completely).
 */
object AudioRoutingService {

    suspend fun resolveSearchTracks(
        context: Context,
        query: String,
        limit: Int = 25
    ): RoutedSearchResult = withContext(Dispatchers.IO) {
        val quality = AudioQualityPreferences.getSelectedQuality(context)

        if (quality == AudioQuality.HI_RES_LOSSLESS) {
            // ============================================================
            // SCENARIO A: Hi-Res Lossless Mode Enabled
            // ============================================================

            // 1. Primary Source: Internet Archive API (Lossless verification)
            try {
                val losslessTracks = InternetArchiveApiService.searchLosslessTracks(query, maxResults = limit)
                if (losslessTracks.isNotEmpty()) {
                    return@withContext RoutedSearchResult(tracks = losslessTracks, isLossless = true)
                }
            } catch (_: Exception) {
                // Tier 1 timeout or failure -> Fallback to Tier 2
            }

            // 2. Secondary Source Fallback: JioSaavn API
            try {
                val jioTracks = OnlineMusicApiService.searchSongs(query, limit = limit)
                if (jioTracks.isNotEmpty()) {
                    return@withContext RoutedSearchResult(tracks = jioTracks, isLossless = false)
                }
            } catch (_: Exception) {
                // Tier 2 failure -> Fallback to Tier 3
            }

            // 3. Tertiary Source Fallback: YouTube Music API
            try {
                val ytTracks = YouTubeMusicApiService.searchSongs(query, limit = limit)
                if (ytTracks.isNotEmpty()) {
                    return@withContext RoutedSearchResult(tracks = ytTracks, isLossless = false)
                }
            } catch (_: Exception) {
                // Exhausted all tiers
            }

            RoutedSearchResult(tracks = emptyList(), isLossless = false)
        } else {
            // ============================================================
            // SCENARIO B: Standard / High / Ultra HD (Lossy Streaming)
            // Internet Archive is completely bypassed
            // ============================================================

            // 1. Primary Source: JioSaavn API
            try {
                val jioTracks = OnlineMusicApiService.searchSongs(query, limit = limit)
                if (jioTracks.isNotEmpty()) {
                    return@withContext RoutedSearchResult(tracks = jioTracks, isLossless = false)
                }
            } catch (_: Exception) {
                // Tier 1 failure -> Fallback to Tier 2
            }

            // 2. Secondary Source Fallback: YouTube Music API
            try {
                val ytTracks = YouTubeMusicApiService.searchSongs(query, limit = limit)
                return@withContext RoutedSearchResult(tracks = ytTracks, isLossless = false)
            } catch (_: Exception) {
                RoutedSearchResult(tracks = emptyList(), isLossless = false)
            }
        }
    }
}
