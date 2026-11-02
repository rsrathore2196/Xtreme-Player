package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable

@Immutable
data class HomeFeedWindow(val visibleCount: Int = 4) {
    fun appendNext(available: Int): HomeFeedWindow = if (visibleCount >= available) this
        else copy(visibleCount = (visibleCount + 1).coerceAtMost(available))
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
