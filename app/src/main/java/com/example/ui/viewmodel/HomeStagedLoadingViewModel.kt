package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.data.local.UserProfile
import com.example.data.model.MusicTrack
import com.example.ui.ai.HomeShelf

/**
 * Loading stages for staged / prioritized startup architecture:
 * - Priority 1 (0s Immediate): Instant UI shell, navigation, mini-player & offline cached room state
 * - Priority 2 (1-3s Fast): Asynchronous primary recommendations, visible hero track & primary charts
 * - Priority 3 (Background Lazy): Secondary lists, metadata enrichment, deeper recommendations
 */
enum class LoadingStage {
    PRIORITY_1_INSTANT_SHELL,
    PRIORITY_2_ESSENTIAL_CONTENT,
    PRIORITY_3_BACKGROUND_ENRICHED
}

@Immutable
data class StagedSection<T>(
    val items: List<T> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
data class HomeState(
    val stage: LoadingStage = LoadingStage.PRIORITY_1_INSTANT_SHELL,
    val userProfile: UserProfile? = null,
    val greeting: String = "",
    val heroTrack: MusicTrack? = null,
    val recentlyPlayed: List<MusicTrack> = emptyList(),
    val favoriteTracks: List<MusicTrack> = emptyList(),
    val primaryRecommendations: List<MusicTrack> = emptyList(),
    val shelves: List<HomeShelf> = emptyList(),
    val isSilentRefreshing: Boolean = false
)

/** Compatibility name; HomeViewModel owns the only startup/refresh pipeline. */
@Deprecated("Use HomeViewModel; staged loading is integrated into the active Home pipeline")
typealias HomeStagedLoadingViewModel = HomeViewModel
