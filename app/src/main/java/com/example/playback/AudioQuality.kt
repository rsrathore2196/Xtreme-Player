package com.example.playback

enum class AudioQuality(
    val id: String,
    val title: String,
    val kbps: Int,
    val badge: String,
    val description: String
) {
    EXTREME_320(
        id = "320",
        title = "Extreme HD (320 kbps)",
        kbps = 320,
        badge = "HD • 320 kbps",
        description = "Lossless-grade studio master fidelity & maximum acoustic detail"
    ),
    HIGH_160(
        id = "160",
        title = "High Quality (160 kbps)",
        kbps = 160,
        badge = "HQ • 160 kbps",
        description = "Balanced acoustic clarity with fast streaming & low latency"
    ),
    DATA_SAVER_96(
        id = "96",
        title = "Data Saver (96 kbps)",
        kbps = 96,
        badge = "SD • 96 kbps",
        description = "Lightweight compression optimized for mobile data networks"
    );

    companion object {
        fun fromId(id: String): AudioQuality {
            return entries.find { it.id == id } ?: EXTREME_320
        }
    }
}
