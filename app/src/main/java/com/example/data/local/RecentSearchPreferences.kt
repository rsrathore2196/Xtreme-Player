package com.example.data.local

import android.content.Context
import org.json.JSONArray

object RecentSearchPreferences {
    private const val PREFS_NAME = "xtreme_recent_searches_prefs"
    private const val KEY_RECENT_SEARCHES = "recent_search_queries"
    private const val MAX_RECENT_SEARCHES = 5

    fun getRecentSearches(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_RECENT_SEARCHES, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optString(i)?.trim()
                if (!item.isNullOrEmpty() && !list.contains(item)) {
                    list.add(item)
                }
            }
            list.take(MAX_RECENT_SEARCHES)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecentSearch(context: Context, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return getRecentSearches(context)

        val current = getRecentSearches(context).toMutableList()
        // Remove existing case-insensitively
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        // Prepend new search query to front
        current.add(0, trimmed)
        val trimmedList = current.take(MAX_RECENT_SEARCHES)

        saveRecentSearches(context, trimmedList)
        return trimmedList
    }

    fun removeRecentSearch(context: Context, query: String): List<String> {
        val trimmed = query.trim()
        val current = getRecentSearches(context).toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }

        saveRecentSearches(context, current)
        return current
    }

    fun clearRecentSearches(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_RECENT_SEARCHES).apply()
    }

    private fun saveRecentSearches(context: Context, list: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(it) }
        prefs.edit().putString(KEY_RECENT_SEARCHES, jsonArray.toString()).apply()
    }
}
