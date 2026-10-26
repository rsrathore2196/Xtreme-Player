package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.recommendation.UnifiedRecommendationEngine.Shelf

/** Display ownership is retained by Home, independently of cached discovery pools. */
enum class HomeFeedSection(val shelf: Shelf?) {
    QUICK(Shelf.QUICK), CHART(Shelf.CHART), ARTISTS(null), ERA(Shelf.ERA),
    PERSONALIZED(Shelf.PERSONALIZED), INSPIRED(Shelf.INSPIRED)
}

@Immutable
data class HomeFeedWindow(val sections: List<HomeFeedSection> = INITIAL) {
    val shelves: Set<Shelf> get() = sections.mapNotNullTo(linkedSetOf()) { it.shelf }

    fun appendNext(eligible: Set<HomeFeedSection>): HomeFeedWindow {
        val next = HomeFeedSection.entries.firstOrNull { it !in sections && it in eligible } ?: return this
        return copy(sections = sections + next)
    }

    companion object {
        val INITIAL = listOf(HomeFeedSection.QUICK, HomeFeedSection.CHART)
        val BOOTSTRAP_SHELVES = setOf(Shelf.QUICK, Shelf.CHART)
    }
}

/** A layout traversal, cache response or retained-tab return is never scroll intent. */
internal class HomeRevealGesture {
    private var active = false
    private var sequence = 0L
    private var consumed = 0L

    fun forwardInput(): Long {
        if (!active) { active = true; sequence++ }
        return sequence
    }

    fun settled() { active = false }

    fun consumeNearTail(intent: Long, nearTail: Boolean): Boolean {
        if (!nearTail || intent <= consumed) return false
        consumed = intent
        return true
    }
}
