package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import com.example.performance.AppDispatchers
import com.example.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** One persistent source list. Legacy single-server settings are read only for migration. */
object PlaybackSourcesPreferences {
    private const val PREFS_NAME = "xtreme_playback_sources_prefs"
    private const val KEY_CONFIG = "sources_v2"
    const val ID_ADDON = "addon"
    const val ID_JIOSAAVN = "jiosaavn"
    const val ID_INTERNET_ARCHIVE = "internet_archive"
    const val ID_YOUTUBE = "youtube"
    val DEFAULT_ORDER = listOf(ID_INTERNET_ARCHIVE, ID_JIOSAAVN, ID_YOUTUBE)
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.HomeBookkeeping)
    private val reads = Mutex()
    private val writes = Mutex()
    private data class Snapshot(val key: String?, val sources: List<PlaybackSource>)
    private val memory = AtomicReference(Snapshot(null, emptyList()))
    private fun prefs(context: Context) = ReactiveSettingsRepository.preferences(context, PREFS_NAME)

    fun snapshot(context: Context): List<PlaybackSource> {
        val raw = prefs(context).getString(KEY_CONFIG, null)
        val value = memory.get()
        return if (value.key == raw) value.sources else
            if (raw != null) PlaybackSource.ordered(emptyList()) else emptyList()
    }

    private fun legacy(context: Context, preferences: SharedPreferences): List<PlaybackSource> {
        val order = preferences.getString("sources_order", null)
        val flags = preferences.all.keys.any { it.startsWith("source_enabled_") }
        if (order == null && !flags) return emptyList()
        val old = OtherSettingsPreferences.playbackIntegrations(context)
        val custom = old.addonUrl.takeIf(String::isNotBlank)?.let { url ->
            PlaybackSource(PlaybackSource.addonId(url), PlaybackSourceKind.ADDON,
                old.addonName.ifBlank { "Custom server" },
                preferences.getBoolean("source_enabled_addon", old.addonEnabled), url)
        }
        val ids = order?.split(',')?.map(String::trim).orEmpty()
        val builtin = PlaybackSource.builtIns.map { it.copy(enabled = preferences.getBoolean("source_enabled_${it.id}", it.id in ids)) }
        val available = builtin + listOfNotNull(custom)
        return PlaybackSource.ordered(ids.mapNotNull { id -> if (id == ID_ADDON) custom else available.find { it.id == id } } + available)
    }

    suspend fun load(context: Context): List<PlaybackSource> = com.example.performance.BackgroundWorkCoordinator.finishBoundedBatch {
        readCurrent(context)
    }

    // Finish this small settings transaction before yielding: an optional reader
    // must never park with the lock needed by a foreground playback request.
    private suspend fun readCurrent(context: Context): List<PlaybackSource> = withContext(AppDispatchers.Io) {
        reads.withLock {
            val preferences = prefs(context)
            var loaded: Snapshot? = null
            while (loaded == null) {
                currentCoroutineContext().ensureActive()
                val raw = preferences.getString(KEY_CONFIG, null)
                val cached = memory.get().takeIf { raw != null && it.key == raw }
                val parsed = if (raw == null) legacy(context, preferences) else cached?.sources
                    ?: withContext(AppDispatchers.CpuBounded) {
                        runCatching {
                            val array = JSONArray(raw)
                            (0 until minOf(array.length(), 19)).mapNotNull { index ->
                                val row = array.optJSONObject(index) ?: return@mapNotNull null
                                val kind = PlaybackSourceKind.entries.firstOrNull { it.name == row.optString("kind") } ?: return@mapNotNull null
                                if (kind != PlaybackSourceKind.ADDON) {
                                    val builtin = PlaybackSource.builtIns.first { it.kind == kind }
                                    builtin.copy(id = row.optString("id").ifBlank { builtin.id },
                                        name = row.optString("name").take(100).ifBlank { builtin.name },
                                        enabled = row.optBoolean("enabled", false), baseUrl = row.optString("url"))
                                } else {
                                    val url = runCatching { com.example.data.remote.StreamingAddons.normalize(row.optString("url")) }
                                        .getOrNull() ?: return@mapNotNull null
                                    PlaybackSource(row.optString("id").ifBlank { PlaybackSource.addonId(url) }, kind,
                                        row.optString("name").take(100).ifBlank { "Custom server" }, row.optBoolean("enabled", false), url)
                                }
                            }.let(PlaybackSource::ordered)
                        }.getOrElse { withContext(AppDispatchers.Io) { PlaybackSource.ordered(legacy(context, preferences)) } }
                    }
                // Persist canonical normalization once, on IO, under the existing
                // read transaction. Calling persist() here would reacquire this lock.
                val canonical = if (cached != null) raw else if (parsed.isEmpty()) null else encode(parsed)
                if (preferences.getString(KEY_CONFIG, null) == raw) {
                    if (canonical != null && canonical != raw) preferences.edit { putString(KEY_CONFIG, canonical) }
                    loaded = Snapshot(canonical ?: raw, parsed)
                }
            }
            checkNotNull(loaded).also(memory::set).sources
        }
    }

    private suspend fun persist(context: Context, sources: List<PlaybackSource>) {
        val snapshot = PlaybackSource.ordered(sources.filter { it.kind == PlaybackSourceKind.ADDON }.take(16) +
            sources.filter { it.kind != PlaybackSourceKind.ADDON })
        val raw = encode(snapshot)
        reads.withLock {
            memory.set(Snapshot(raw, snapshot))
            prefs(context).edit().putString(KEY_CONFIG, raw).apply()
        }
    }
    private suspend fun encode(sources: List<PlaybackSource>): String = withContext(AppDispatchers.CpuBounded) {
        JSONArray().apply { sources.forEach { source ->
            put(JSONObject().put("id", source.id).put("kind", source.kind.name).put("name", source.name)
                .put("enabled", source.enabled).put("url", source.baseUrl))
        } }.toString()
    }
    fun save(context: Context, sources: List<PlaybackSource>) {
        val app = context.applicationContext
        scope.launch { writes.withLock { persist(app, sources) } }
    }
    private fun mutate(context: Context, change: (List<PlaybackSource>) -> List<PlaybackSource>) {
        val app = context.applicationContext
        scope.launch { writes.withLock {
            val sources = load(app).ifEmpty { OtherSettingsPreferences.playbackIntegrations(app)
                .sourceList(NetworkQualityMonitor.getInstance(app).activeQualityCeiling.value.effectiveCeiling) }
            persist(app, change(sources))
        } }
    }
    fun setSourceEnabled(context: Context, sourceId: String, enabled: Boolean) = mutate(context) { sources ->
        sources.map { if (it.id == sourceId && it.kind != PlaybackSourceKind.YOUTUBE) it.copy(enabled = enabled) else it }
    }
    fun swapSources(context: Context, fromIndex: Int, toIndex: Int) = mutate(context) { sources ->
        sources.toMutableList().apply {
            if (fromIndex in indices && toIndex in indices &&
                this[fromIndex].kind == PlaybackSourceKind.ADDON && this[toIndex].kind == PlaybackSourceKind.ADDON)
                add(toIndex, removeAt(fromIndex))
        }
    }
    fun reorderAddons(context: Context, orderedIds: List<String>) = mutate(context) { sources ->
        val addons = sources.filter { it.kind == PlaybackSourceKind.ADDON }
        val byId = addons.associateBy { it.id }
        val moved = orderedIds.distinct().mapNotNull(byId::get)
        moved + addons.filterNot { it.id in orderedIds } + sources.filterNot { it.kind == PlaybackSourceKind.ADDON }
    }
    fun saveAddon(context: Context, url: String, name: String, previousId: String? = null) = mutate(context) { sources ->
        val normalized = com.example.data.remote.StreamingAddons.normalize(url)
        val source = PlaybackSource(PlaybackSource.addonId(normalized), PlaybackSourceKind.ADDON, name.take(100), false, normalized)
        if (sources.any { it.id == source.id && it.id != previousId }) sources
        else if (previousId != null) sources.map { if (it.id == previousId) source.copy(enabled = it.enabled) else it }
        else if (sources.count { it.kind == PlaybackSourceKind.ADDON } < 16) sources + source else sources
    }
    fun remove(context: Context, id: String) = mutate(context) { sources -> sources.filterNot { it.id == id && it.kind == PlaybackSourceKind.ADDON } }

    internal fun changes(context: Context): Flow<Unit> = callbackFlow {
        val preferences = prefs(context.applicationContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener); trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().flowOn(AppDispatchers.Io)

    @Immutable
    data class SourceItem(val id: String, val name: String, val description: String,
        val isLossless: Boolean, val iconType: String, val isEnabled: Boolean,
        val isQualityAllowed: Boolean, val isAlwaysOn: Boolean = false,
        val qualityRequirementText: String? = null, val url: String = "") {
        val isEffectiveActive: Boolean get() = isEnabled && isQualityAllowed
        val isReorderable: Boolean get() = iconType == ID_ADDON
    }
    fun isQualityAllowed(id: String, ceiling: AudioQualityCeiling): Boolean = when (id) {
        ID_YOUTUBE -> true
        ID_JIOSAAVN -> ceiling in setOf(AudioQualityCeiling.HIGH, AudioQualityCeiling.MAX)
        else -> ceiling == AudioQualityCeiling.MAX
    }
    fun getSources(context: Context): List<SourceItem> {
        val ceiling = NetworkQualityMonitor.getInstance(context).activeQualityCeiling.value.effectiveCeiling
        return OtherSettingsPreferences.playbackIntegrations(context).sourceList(ceiling).map { source ->
            val type = when (source.kind) {
                PlaybackSourceKind.YOUTUBE -> ID_YOUTUBE
                PlaybackSourceKind.JIOSAAVN -> ID_JIOSAAVN
                PlaybackSourceKind.INTERNET_ARCHIVE -> ID_INTERNET_ARCHIVE
                PlaybackSourceKind.ADDON -> ID_ADDON
            }
            val allowed = source.available(ceiling)
            SourceItem(source.id, source.name, when (source.kind) {
                PlaybackSourceKind.YOUTUBE -> "Lossy • Full catalogue • Radio"
                PlaybackSourceKind.JIOSAAVN -> "Verified recording matches • AAC up to 320 kbps"
                PlaybackSourceKind.INTERNET_ARCHIVE -> "Verified, authorized lossless recordings"
                PlaybackSourceKind.ADDON -> "Custom streaming server"
            }, source.kind in setOf(PlaybackSourceKind.ADDON, PlaybackSourceKind.INTERNET_ARCHIVE), type,
                source.enabled, allowed, isAlwaysOn = source.kind == PlaybackSourceKind.YOUTUBE,
                qualityRequirementText = if (allowed) null else
                    if (source.kind == PlaybackSourceKind.JIOSAAVN) "Requires High or Maximum/Lossless"
                    else "Requires Maximum/Lossless", url = source.baseUrl)
        }
    }
    fun getActiveSourceName(context: Context): String = NetworkQualityMonitor.getInstance(context).sourcePolicy.value
        .playbackSources.firstOrNull()?.name ?: "No enabled source"
    fun observeSources(context: Context): Flow<List<SourceItem>> = combine(
        OtherSettingsPreferences.observePlaybackIntegrations(context), NetworkQualityMonitor.getInstance(context).activeQualityCeiling
    ) { _, _ -> getSources(context) }.distinctUntilChanged().flowOn(AppDispatchers.Io)
}
