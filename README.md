# 🎵 Xtreme Player

**Xtreme Player** is a lightweight, modern, open-source online music streaming application for Android. Built with a clean Material You (Material 3) interface, it delivers an ad-free, high-fidelity audio experience with zero subscription paywalls.

Stream millions of online tracks in studio-grade **320 kbps high-res audio**, manage custom libraries, and enjoy seamless playback without interruptions.

---

## ✨ Key Features

### 🎧 High-Fidelity Audio Streaming
- **True 320 kbps HQ Audio:** Stream crystal-clear, high-bitrate music (MP3, AAC, FLAC) with minimal compression.
- **Smart Caching & Zero Buffering:** Intelligent pre-caching ensures uninterrupted playback even on unstable connections.
- **Background Playback & Media Controls:** Persistent Android 13+ lock screen and notification controls powered by AndroidX Media3.

### 🎨 Clean & Modern Material UI
- **Material 3 Design:** Sleek, minimalistic aesthetic with dynamic AMOLED dark mode or Material light mode and fluid animations.
- **Modern Full-Screen Player:** Ambient artwork glow, dynamic color palette matching, real-time waveform scrubbing, and tactile haptic controls.
- **Persistent Mini-Player:** Floating mini-player with swipe-to-skip gestures and seamless bottom-sheet expansion.

### 🔍 Discovery & Music Management
- **Instant Online Search:** Fast, debounced search engine for tracks, artists, top hits, and albums.
- **Personalized Library:** Save favorites, organize custom playlists, and view listening history.
- **Queue & Playlists:** Drag-and-drop track reordering, shuffle modes, and smart loop controls.

### 🔓 100% Free & Open Source
- **No Ads, No Paywalls:** Pure music streaming without sponsored interruptions.
- **Privacy-First:** No invasive tracking, profiling, or unnecessary background permissions.
- **Community-Driven:** Fully open-source codebase open for contributions and audits.

---

## 🛠️ Built With

- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose & Material Design 3
- **Audio Engine:** AndroidX Media3 (ExoPlayer + MediaSessionService)
- **Architecture:** Clean Architecture + MVI / MVVM
- **Database:** Room (Local playlists and metadata caching)
- **Image Loading:** Coil Compose
- **Networking:** Retrofit2 & OkHttp3

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug / Meerkat or newer
- Android SDK 26+ (Android 8.0 Oreo or higher)
- JDK 17+

### Build & Run
```bash
# Clone the repository
git clone [https://github.com/your-username/xtreme-player.git](https://github.com/your-username/xtreme-player.git)

# Navigate to the project directory
cd xtreme-player

# Build the debug APK
./gradlew assembleDebug
