package com.example.data.remote

import com.example.data.model.MusicTrack

object MusicDataSource {

    // Curated high-fidelity audio streams (320kbps CBR/VBR audio files with working streaming endpoints and high-res covers)
    val curatedTracks: List<MusicTrack> = listOf(
        MusicTrack(
            id = "xtreme_01",
            title = "Cybernetic Horizon",
            artist = "Neon Odyssey",
            album = "Night City 2088",
            durationMs = 214000L,
            coverUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Synthwave"
        ),
        MusicTrack(
            id = "xtreme_02",
            title = "Midnight Drive",
            artist = "Aether Void",
            album = "Retrowave Dreams",
            durationMs = 188000L,
            coverUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Electronic"
        ),
        MusicTrack(
            id = "xtreme_03",
            title = "Hyperdrive Echoes",
            artist = "Kavinsky Matrix",
            album = "Outrun Protocol",
            durationMs = 245000L,
            coverUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Synthwave"
        ),
        MusicTrack(
            id = "xtreme_04",
            title = "Starlight Lofi Chill",
            artist = "Luna Waves",
            album = "Cosmic Coffee Sessions",
            durationMs = 196000L,
            coverUrl = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Chillhop"
        ),
        MusicTrack(
            id = "xtreme_05",
            title = "Electric Pulse",
            artist = "Subzero Syndicate",
            album = "Bassline Overdrive",
            durationMs = 230000L,
            coverUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Electronic"
        ),
        MusicTrack(
            id = "xtreme_06",
            title = "Neon Boulevard",
            artist = "Viper City",
            album = "Turbulence EP",
            durationMs = 210000L,
            coverUrl = "https://images.unsplash.com/photo-1492684223066-81342ee5ff30?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Rock"
        ),
        MusicTrack(
            id = "xtreme_07",
            title = "Solar Flare",
            artist = "Astral Projection",
            album = "Infinite Horizons",
            durationMs = 260000L,
            coverUrl = "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-7.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Pop"
        ),
        MusicTrack(
            id = "xtreme_08",
            title = "Deep Velvet Groove",
            artist = "The Jazz Syndicate",
            album = "Midnight Blue Club",
            durationMs = 225000L,
            coverUrl = "https://images.unsplash.com/photo-1511192336575-5a79af67a629?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Jazz"
        ),
        MusicTrack(
            id = "xtreme_09",
            title = "Underground Odyssey",
            artist = "District 9",
            album = "Concrete Jungle",
            durationMs = 205000L,
            coverUrl = "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-9.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Hip-Hop"
        ),
        MusicTrack(
            id = "xtreme_10",
            title = "Quantum Echoes",
            artist = "Chrono Trigger",
            album = "Space Continuum",
            durationMs = 280000L,
            coverUrl = "https://images.unsplash.com/photo-1506157786151-b8491531f063?w=800&q=80",
            audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-10.mp3",
            bitrateKbps = 320,
            qualityBadge = "HD • 320 kbps",
            genre = "Ambient"
        )
    )

    val genres = listOf(
        "Pop",
        "Hip-Hop",
        "Rock",
        "Electronic",
        "Bollywood",
        "Punjabi",
        "Synthwave",
        "Chillhop",
        "Lo-Fi",
        "Jazz",
        "Ambient"
    )

    val topMixes = listOf(
        MixItem("Cyberpunk 2088 Mix", "Synthwave, Electro, Darksynth", "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=800&q=80", "Synthwave"),
        MixItem("Deep Focus & Coding", "Lofi Beats, Chillhop, Ambient", "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=800&q=80", "Chillhop"),
        MixItem("High Energy Workout", "Heavy Bass, Electronic, Rock", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=800&q=80", "Electronic"),
        MixItem("Late Night Vibes", "Smooth Jazz, Neo-Soul, R&B", "https://images.unsplash.com/photo-1511192336575-5a79af67a629?w=800&q=80", "Jazz")
    )
}

data class MixItem(
    val title: String,
    val description: String,
    val coverUrl: String,
    val targetGenre: String
)
