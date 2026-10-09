package com.example.playback

enum class AudioQuality(
    val id: String,
    val title: String,
    val kbps: Int,
    val badge: String,
    val description: String
) {
    HI_RES_LOSSLESS(
        id = "lossless",
        title = "Hi-Res Lossless • 24-bit/192 kHz (Studio Master)",
        kbps = 9216,
        badge = "Hi-Res Lossless",
        description = "Highest Studio Grade Quality FLAC Audio"
    ),
    ULTRA_HD_320(
        id = "320",
        title = "Ultra HD • 320 kbps (Limitless)",
        kbps = 320,
        badge = "Ultra HD",
        description = "Audiophile Grade High Quality Sound Reproduction"
    ),
    HIGH_160(
        id = "160",
        title = "High • 160 kbps (Balanced)",
        kbps = 160,
        badge = "HD Audio",
        description = "Optimized clear sound with fast data streaming"
    ),
    MEDIUM_96(
        id = "96",
        title = "Medium • 96 kbps (Data Saver)",
        kbps = 96,
        badge = "Data Saver",
        description = "Lowest network bandwidth usage"
    );

    companion object {
        val EXTREME_320 get() = ULTRA_HD_320
        val DATA_SAVER_96 get() = MEDIUM_96

        fun fromId(id: String): AudioQuality {
            return when (id.lowercase().trim()) {
                "lossless", "hires", "flac" -> HI_RES_LOSSLESS
                "320", "ultrahd", "extreme" -> ULTRA_HD_320
                "160", "high" -> HIGH_160
                "96", "medium", "standard", "datasaver" -> MEDIUM_96
                else -> ULTRA_HD_320
            }
        }
    }
}
