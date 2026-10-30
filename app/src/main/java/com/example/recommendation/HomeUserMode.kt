package com.example.recommendation

enum class HomeUserMode {
    LOADING,
    NEW_USER,
    EXISTING_USER,
    UNAVAILABLE;

    companion object {
        fun confirmed(eventCount: Int, hasFavorites: Boolean): HomeUserMode {
            return if (eventCount > 0 || hasFavorites) EXISTING_USER else NEW_USER
        }
    }
}
