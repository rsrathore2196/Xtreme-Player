package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Portable user preferences only. File grants, cache inventories and active deadlines belong to the device. */
internal object BackupSettings {
    private val fields = mapOf(
        AudioEffectsPreferences.STORE to mapOf(AudioEffectsPreferences.KEY to "string"),
        "xtreme_other_settings" to buildMap {
            listOf("proxy_enabled", "haptics_enabled", "dynamic_glass_enabled", "player_dynamic_album_glow",
                "player_high_contrast_typography", "player_full_screen_album_art", "playback_crossfade_enabled",
                "playback_spatial_audio", "playback_dolby_atmos", "playback_skip_silence", "keep_lyrics_screen_awake",
                "streaming_addon_enabled").forEach { put(it, "boolean") }
            listOf("proxy_host", "proxy_type", "lyrics_provider", "lyrics_translation_language",
                "playback_output_precision", "streaming_addon_url", "streaming_addon_name").forEach { put(it, "string") }
            listOf("proxy_port", "text_size_index", "ui_size_index", "lyrics_text_size_sp",
                "total_played_count", "total_listening_minutes").forEach { put(it, "int") }
        },
        "xtreme_audio_quality_prefs" to mapOf("wifi_quality_ceiling" to "string", "cellular_quality_ceiling" to "string",
            "treat_metered_wifi_as_cellular" to "boolean", "prefer_youtube_opus" to "boolean",
            "enhancer_return_to_youtube" to "boolean"),
        "xtreme_playback_sources_prefs" to mapOf("sources_v2" to "string", "sources_order" to "string"),
        "xtreme_smart_cache_preferences" to mapOf("key_max_quota_bytes" to "long", "key_smart_auto_trim" to "boolean",
            "key_auto_precache" to "boolean", "key_wifi_only" to "boolean"),
        "xtreme_recent_searches_prefs" to mapOf("recent_search_queries" to "string"),
        "xtreme_backup_preferences" to mapOf("auto_backup_enabled" to "boolean"),
        "lyrics_offset_preferences" to mapOf("global_lyrics_offset_ms" to "int"),
        "xtreme_theme_prefs" to buildMap {
            put("selected_theme_mode", "string")
            for (prefix in listOf("", "saved_dark_", "saved_light_")) {
                listOf("custom_preset_is_dark", "custom_is_amoled").forEach { put(prefix + it, "boolean") }
                put(prefix + "palette_version", "int")
                listOf("custom_preset_id", "custom_accent_name", "custom_accent_shade", "custom_accent_hex",
                    "custom_secondary_accent_hex", "custom_canvas_name", "custom_canvas_hex", "custom_card_name",
                    "custom_card_hex", "custom_card_border_hex", "custom_text_primary_hex", "custom_text_secondary_hex",
                    "custom_text_muted_hex", "custom_bg_gradient_colors", "custom_card_gradient_colors",
                    "custom_bs_gradient_colors", "custom_bg_gradient", "custom_card_gradient", "custom_bs_gradient",
                    "custom_theme_name").forEach { put(prefix + it, "string") }
            }
        }
    )

    private fun type(store: String, key: String): String? = fields[store]?.get(key) ?: when {
        store == "lyrics_offset_preferences" && key.startsWith("track_lyrics_offset_") -> "int"
        store == "xtreme_playback_sources_prefs" && key.startsWith("source_enabled_") -> "boolean"
        else -> null
    }

    fun read(context: Context, stores: Set<String> = fields.keys): Map<String, Map<String, Any>> = stores.associateWith { store ->
        ReactiveSettingsRepository.preferences(context, store).all.entries.mapNotNull { (key, value) ->
            if (value != null && type(store, key) != null) key to value else null
        }.toMap()
    }

    fun encode(settings: Map<String, Map<String, Any>>): JSONObject = JSONObject().apply {
        settings.forEach { (store, values) -> put(store, JSONObject().apply {
            values.forEach { (key, value) -> put(key, JSONObject().put("type", type(store, key)).put("value", value)) }
        }) }
    }

    fun capture(context: Context): Map<String, Map<String, Any>> = read(context) +
        (AudioEffectsPreferences.STORE to mapOf(AudioEffectsPreferences.KEY to
            AudioEffectsPreferences.encode(com.example.playback.AudioEffectsManager.effectsState.value)))

    fun publish(context: Context, settings: Map<String, Map<String, Any>>) {
        val raw = settings[AudioEffectsPreferences.STORE]?.get(AudioEffectsPreferences.KEY) as? String ?: return
        val configuration = AudioEffectsPreferences.decode(raw, com.example.playback.AudioEffectsManager.effectsState.value)
        com.example.playback.AudioEffectsManager.restoreConfiguration(configuration)
        // A durable barrier after the in-memory revision supersedes any queued pre-restore edits.
        AudioEffectsPreferences.write(context, configuration)
    }

    /** Validate the entire section, including native preference types, before any restore writes. */
    fun decode(json: JSONObject): Map<String, Map<String, Any>> {
        require(json.length() <= fields.size) { "Invalid settings section" }
        return json.keys().asSequence().associateWith { store ->
            require(store in fields) { "Unknown settings group" }
            val values = json.getJSONObject(store)
            require(values.length() <= 100_000) { "Too many settings" }
            values.keys().asSequence().associateWith { key ->
                val expected = type(store, key) ?: error("Unknown setting: $key")
                val entry = values.getJSONObject(key)
                require(entry.getString("type") == expected) { "Invalid setting type: $key" }
                val raw = entry.get("value")
                when (expected) {
                    "boolean" -> { require(raw is Boolean) { "Invalid setting: $key" }; raw }
                    "string" -> {
                        require(raw is String) { "Invalid setting: $key" }
                        if (store == AudioEffectsPreferences.STORE) AudioEffectsPreferences.decode(raw,
                            com.example.playback.AudioEffectsManager.effectsState.value)
                        raw
                    }
                    "int" -> {
                        require(raw is Int || raw is Long) { "Invalid setting: $key" }
                        val number = (raw as Number).toLong()
                        require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid setting: $key" }
                        number.toInt()
                    }
                    "long" -> { require(raw is Int || raw is Long) { "Invalid setting: $key" }; (raw as Number).toLong() }
                    else -> error("Invalid setting type")
                }
            }
        }
    }

    /** Called on IO under the restore mutex. Omitted legacy groups remain untouched. */
    fun write(context: Context, settings: Map<String, Map<String, Any>>) {
        settings.forEach { (store, values) ->
            val preferences = ReactiveSettingsRepository.preferences(context, store)
            val editor = preferences.edit()
            preferences.all.keys.filter { type(store, it) != null }.forEach(editor::remove)
            values.forEach { (key, value) -> put(editor, key, value) }
            check(editor.commit()) { "Could not save restored settings" }
        }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            else -> error("Invalid preference value")
        }
    }
}
