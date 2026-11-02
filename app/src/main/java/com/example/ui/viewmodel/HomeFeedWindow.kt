package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable

@Immutable
data class HomeFeedWindow(val visibleCount: Int = 4) {
    fun appendNext(available: Int): HomeFeedWindow = if (visibleCount >= available) this
        else copy(visibleCount = (visibleCount + 2).coerceAtMost(available))
}

/** A layout traversal, cache response or retained-tab return is never scroll intent. */
internal class HomeRevealGesture {
    private var active = false
    private var sequence = 0L
    private var consumed = 0L
    private var completedPage = 0L
    private var chainedPages = 0

    fun forwardInput(): Long {
        if (!active) { active = true; sequence++ }
        return sequence
    }

    fun settled() { active = false }

    fun afterPage(intent: Long, nearTail: Boolean, revision: Long): Boolean {
        if (revision <= completedPage) return false
        completedPage = revision
        if (intent == 0L || intent != consumed || !nearTail || chainedPages >= 3) return false
        chainedPages++
        return true
    }

    fun consumeNearTail(intent: Long, nearTail: Boolean): Boolean {
        if (!nearTail || intent <= consumed) return false
        consumed = intent
        chainedPages = 0
        return true
    }
}
