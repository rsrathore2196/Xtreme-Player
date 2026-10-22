package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.playback.AudioQualityCeiling
import com.example.playback.NetworkQualityMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Configuration and preference persistence for playback audio sources.
 * Controls the prioritized fallback hierarchy and network quality gating.
 */
object PlaybackSourcesPreferences {
    private const val PREFS_NAME = "xtreme_playback_sources_prefs"
    private const val KEY_SOURCES_ORDER = "sources_order"
    private const val KEY_ENABLED_PREFIX = "source_enabled_"

    const val ID_ADDON = "addon"
    const val ID_JIOSAAVN = "jiosaavn"
    const val ID_INTERNET_ARCHIVE = "internet_archive"
    const val ID_YOUTUBE = "youtube"

    val DEFAULT_ORDER = listOf(ID_ADDON, ID_JIOSAAVN, ID_INTERNET_ARCHIVE, ID_YOUTUBE)

    data class SourceItem(
        val id: String,
        val name: String,
        val description: String,
        val isLossless: Boolean,
        val iconType: String,
        val isEnabled: Boolean,
        val isQualityAllowed: Boolean,
        val isAlwaysOn: Boolean = false,
        val qualityRequirementText: String? = null
    ) {
        val isEffectiveActive: Boolean
            get() = (isEnabled || isAlwaysOn) && isQualityAllowed
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return ReactiveSettingsRepository.preferences(context, PREFS_NAME)
    }

    fun getSourcesOrder(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_SOURCES_ORDER, null) ?: return DEFAULT_ORDER
        val saved = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val result = saved.toMutableList()
        DEFAULT_ORDER.forEach { if (!result.contains(it)) result.add(it) }
        return result
    }

    fun setSourcesOrder(context: Context, newOrder: List<String>) {
        val valid = newOrder.filter { DEFAULT_ORDER.contains(it) }.distinct()
        val complete = valid + DEFAULT_ORDER.filter { !valid.contains(it) }
        getPrefs(context).edit().putString(KEY_SOURCES_ORDER, complete.joinToString(",")).apply()
        com.example.data.repository.StreamResolver.invalidateCache()
    }

    fun swapSources(context: Context, fromIndex: Int, toIndex: Int) {
        val current = getSourcesOrder(context).toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            setSourcesOrder(context, current)
        }
    }

    fun isSourceEnabled(context: Context, sourceId: String): Boolean {
        if (sourceId == ID_YOUTUBE) return true
        if (sourceId == ID_ADDON) {
            val addon = OtherSettingsPreferences.playbackIntegrations(context)
            if (addon.addonUrl.isBlank()) return false
            return getPrefs(context).getBoolean(KEY_ENABLED_PREFIX + sourceId, addon.addonEnabled)
        }
        return getPrefs(context).getBoolean(KEY_ENABLED_PREFIX + sourceId, true)
    }

    fun setSourceEnabled(context: Context, sourceId: String, enabled: Boolean) {
        if (sourceId == ID_YOUTUBE) return
        getPrefs(context).edit().putBoolean(KEY_ENABLED_PREFIX + sourceId, enabled).apply()
        if (sourceId == ID_ADDON) {
            OtherSettingsPreferences.setStreamingAddonEnabled(context, enabled)
        }
        com.example.data.repository.StreamResolver.invalidateCache()
    }

    /**
     * Determines whether a source is accessible given the active audio quality ceiling.
     * - Data Saver / Standard: Only YouTube Music is allowed.
     * - High: JioSaavn and YouTube Music are allowed.
     * - Maximum / Lossless: All sources are allowed.
     */
    fun isQualityAllowed(sourceId: String, ceiling: AudioQualityCeiling): Boolean {
        return when (sourceId) {
            ID_YOUTUBE -> true
            ID_JIOSAAVN -> ceiling == AudioQualityCeiling.HIGH || ceiling == AudioQualityCeiling.MAX
            ID_INTERNET_ARCHIVE -> ceiling == AudioQualityCeiling.MAX
            ID_ADDON -> ceiling == AudioQualityCeiling.MAX
            else -> ceiling == AudioQualityCeiling.MAX
        }
    }

    fun getQualityRequirementText(sourceId: String, ceiling: AudioQualityCeiling): String? {
        if (isQualityAllowed(sourceId, ceiling)) return null
        return when (sourceId) {
            ID_JIOSAAVN -> "Requires High or Lossless streaming quality"
            ID_INTERNET_ARCHIVE -> "Requires Maximum / Lossless streaming quality"
            ID_ADDON -> "Requires Maximum / Lossless streaming quality"
            else -> "Requires higher streaming quality"
        }
    }

    fun getSources(context: Context): List<SourceItem> {
        val monitor = NetworkQualityMonitor.getInstance(context)
        val ceiling = monitor.snapshot().effectiveCeiling
        val order = getSourcesOrder(context)
        val addonSettings = OtherSettingsPreferences.playbackIntegrations(context)

        return order.map { id ->
            when (id) {
                ID_ADDON -> {
                    val hasUrl = addonSettings.addonUrl.isNotBlank()
                    val name = if (hasUrl && addonSettings.addonName.isNotBlank()) addonSettings.addonName else "Streaming Addon"
                    val desc = if (hasUrl) "Custom server • FLAC / Lossless • External source" else "Connect your personal or community music server"
                    SourceItem(
                        id = ID_ADDON,
                        name = name,
                        description = desc,
                        isLossless = true,
                        iconType = "addon",
                        isEnabled = hasUrl && isSourceEnabled(context, ID_ADDON),
                        isQualityAllowed = isQualityAllowed(ID_ADDON, ceiling),
                        isAlwaysOn = false,
                        qualityRequirementText = getQualityRequirementText(ID_ADDON, ceiling)
                    )
                }
                ID_JIOSAAVN -> SourceItem(
                    id = ID_JIOSAAVN,
                    name = "JioSaavn",
                    description = "May match the wrong version of a song. Enable at your own risk",
                    isLossless = false,
                    iconType = "jiosaavn",
                    isEnabled = isSourceEnabled(context, ID_JIOSAAVN),
                    isQualityAllowed = isQualityAllowed(ID_JIOSAAVN, ceiling),
                    isAlwaysOn = false,
                    qualityRequirementText = getQualityRequirementText(ID_JIOSAAVN, ceiling)
                )
                ID_INTERNET_ARCHIVE -> SourceItem(
                    id = ID_INTERNET_ARCHIVE,
                    name = "Internet Archive",
                    description = "Lossless • Community live & studio archives",
                    isLossless = true,
                    iconType = "internet_archive",
                    isEnabled = isSourceEnabled(context, ID_INTERNET_ARCHIVE),
                    isQualityAllowed = isQualityAllowed(ID_INTERNET_ARCHIVE, ceiling),
                    isAlwaysOn = false,
                    qualityRequirementText = getQualityRequirementText(ID_INTERNET_ARCHIVE, ceiling)
                )
                ID_YOUTUBE -> SourceItem(
                    id = ID_YOUTUBE,
                    name = "YouTube Music",
                    description = "Lossy • Full catalogue • Radio",
                    isLossless = false,
                    iconType = "youtube",
                    isEnabled = true,
                    isQualityAllowed = true,
                    isAlwaysOn = true,
                    qualityRequirementText = null
                )
                else -> SourceItem(
                    id = id,
                    name = id,
                    description = "",
                    isLossless = false,
                    iconType = "default",
                    isEnabled = isSourceEnabled(context, id),
                    isQualityAllowed = isQualityAllowed(id, ceiling),
                    isAlwaysOn = false,
                    qualityRequirementText = getQualityRequirementText(id, ceiling)
                )
            }
        }
    }

    /**
     * Returns the name of the currently preferred / top active source to display outside in Other Settings.
     */
    fun getActiveSourceName(context: Context): String {
        val sources = getSources(context)
        val top = sources.firstOrNull { it.isEffectiveActive }
        return top?.name ?: "YouTube Music"
    }

    /**
     * Checks if any high-quality / custom streaming source is actively enabled.
     */
    fun isAnyCustomSourceEnabled(context: Context): Boolean {
        return isSourceEnabled(context, ID_ADDON)
    }

    fun observeSources(context: Context): Flow<List<SourceItem>> {
        val app = context.applicationContext
        val monitor = NetworkQualityMonitor.getInstance(app)
        val prefsFlow = callbackFlow {
            val prefs = getPrefs(app)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                trySend(Unit)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
        val addonFlow = OtherSettingsPreferences.observePlaybackIntegrations(app)
        val qualityFlow = monitor.activeQualityCeiling

        return combine(prefsFlow, addonFlow, qualityFlow) { _, _, _ ->
            getSources(app)
        }.conflate().distinctUntilChanged().flowOn(Dispatchers.IO)
    }
}
