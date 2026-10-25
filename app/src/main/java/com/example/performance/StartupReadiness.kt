package com.example.performance

import androidx.compose.runtime.Immutable

enum class StartupGroup { HOME, RECENTS, PLAYER }

enum class StartupStatus {
    PENDING, RUNNING, READY, FALLBACK_READY, SKIPPED, UNAVAILABLE;

    val terminal: Boolean get() = this == READY || this == FALLBACK_READY || this == SKIPPED || this == UNAVAILABLE
}

/** Real units of initial work. Only the bounded viewport artwork batch is required;
 * offscreen image enrichment and future refreshes are not startup prerequisites. */
enum class StartupTask(val group: StartupGroup) {
    SETTINGS(StartupGroup.HOME), HOME_CACHE(StartupGroup.HOME), FAVORITES(StartupGroup.HOME),
    LOCAL_TABS(StartupGroup.HOME),
    HOME_LAYOUT(StartupGroup.HOME), SEARCH_LAYOUT(StartupGroup.HOME), LIBRARY_LAYOUT(StartupGroup.HOME),
    INITIAL_ARTWORK(StartupGroup.HOME),
    PERSONALIZATION(StartupGroup.HOME), QUICK(StartupGroup.HOME), ERA(StartupGroup.HOME),
    CHART(StartupGroup.HOME), PERSONALIZED(StartupGroup.HOME), SIMILAR(StartupGroup.HOME),
    INSPIRED(StartupGroup.HOME), HERO(StartupGroup.HOME), PRESENTATION(StartupGroup.HOME),
    RECENT_HISTORY(StartupGroup.RECENTS), RECENT_ARTISTS(StartupGroup.RECENTS),
    PLAYER_SESSION(StartupGroup.PLAYER), PLAYER_CONNECTION(StartupGroup.PLAYER)
}

@Immutable
data class StartupReadiness(
    val tasks: Map<StartupTask, StartupStatus> = StartupTask.entries.associateWith { StartupStatus.PENDING },
    val presentationGeneration: Long = 0L
) {
    val preparationComplete: Boolean get() = tasks.all { (task, status) ->
        task == StartupTask.PRESENTATION || status.terminal
    }
    val complete: Boolean get() = tasks.values.all { it.terminal }
    val progress: Float get() = tasks.values.count { it.terminal }.toFloat() / tasks.size
    val hasUnavailable: Boolean get() = tasks.values.any { it == StartupStatus.UNAVAILABLE }
    val detail: String get() {
        if (complete) return if (hasUnavailable) "Ready with available music" else "Ready to listen"
        return when (tasks.entries.firstOrNull { it.value == StartupStatus.RUNNING }?.key) {
            StartupTask.SETTINGS -> "Restoring your preferences"
            StartupTask.HOME_CACHE -> "Restoring your saved music"
            StartupTask.FAVORITES -> "Syncing your favorite music"
            StartupTask.LOCAL_TABS -> "Restoring Search and Library"
            StartupTask.HOME_LAYOUT, StartupTask.SEARCH_LAYOUT, StartupTask.LIBRARY_LAYOUT -> "Preparing your screens"
            StartupTask.INITIAL_ARTWORK -> "Preparing your album artwork"
            StartupTask.PERSONALIZATION -> "Preparing your listening preferences"
            StartupTask.RECENT_HISTORY, StartupTask.RECENT_ARTISTS -> "Syncing your listening history"
            StartupTask.PLAYER_SESSION, StartupTask.PLAYER_CONNECTION -> "Restoring your last session"
            StartupTask.QUICK, StartupTask.ERA, StartupTask.CHART, StartupTask.PERSONALIZED,
            StartupTask.SIMILAR, StartupTask.INSPIRED -> "Finding music for your taste"
            StartupTask.HERO, StartupTask.PRESENTATION -> "Preparing the final view"
            null -> "Preparing your music"
        }
    }

    fun status(group: StartupGroup): StartupStatus {
        // Presentation is the final global draw fence, not a dependency between
        // Home/Recents/Player indicators. Each group reports its own preparation.
        val values = tasks.filterKeys { it.group == group && it != StartupTask.PRESENTATION }.values
        return when {
            values.all { it.terminal } -> when {
                StartupStatus.UNAVAILABLE in values -> StartupStatus.UNAVAILABLE
                StartupStatus.FALLBACK_READY in values -> StartupStatus.FALLBACK_READY
                else -> StartupStatus.READY
            }
            values.any { it != StartupStatus.PENDING } -> StartupStatus.RUNNING
            else -> StartupStatus.PENDING
        }
    }

    internal fun expirePreparation(): StartupReadiness = if (complete) this else copy(
        tasks = tasks.mapValues { (task, status) ->
            if (status.terminal) status
            else if (task == StartupTask.PRESENTATION) StartupStatus.FALLBACK_READY
            else StartupStatus.UNAVAILABLE
        })

    internal fun withStatus(task: StartupTask, status: StartupStatus): StartupReadiness {
        // Process-scoped completion survives Activity recreation and warm reopening.
        if (complete || tasks.getValue(task).terminal || tasks[task] == status) return this
        return copy(tasks = tasks + (task to status))
    }
}
