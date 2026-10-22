# Implementation Plan: Streaming Addons & Playback Sources Configuration

## 1. Overview & Goals
Modernize the Streaming Addons and playback source management inside Other Settings by introducing a dedicated full-screen **Sources / Streaming Addons** management screen. The new architecture enables users to:
- See a clean, concise single-line entry in **Other Settings** displaying "Streaming Addons", the currently active/top preferred source, and an On/Off toggle.
- Open a dedicated full-screen page with top app bar, back navigation, and Material 3 design matching the reference UI.
- Reorder playback sources (YouTube Music, JioSaavn, Internet Archive, and custom user-installed Addons) via **drag-and-drop handles** to define the exact fallback hierarchy.
- Toggle individual sources On or Off.
- Add and manage custom streaming addons via manifest URL.
- Automatically filter and gate source availability based on active **Network & Streaming Quality** (Data Saver / Standard vs. High vs. Maximum / Lossless).
- Guarantee that **YouTube Music** remains the infallible final fallback if other sources are toggled off or unavailable.

---

## 2. Proposed Architecture & Components

### A. Data Layer: Sources & Quality State (`PlaybackSourcesPreferences.kt`)
1. **Source Representation**:
   - `id`: Unique identifier (`"youtube"`, `"jiosaavn"`, `"internet_archive"`, or addon IDs like `"addon_<hash>"`).
   - `name`: Display name (e.g., "YouTube Music", "JioSaavn", "Internet Archive", "Lossless Bridge").
   - `description`: Format/status caption (e.g., "Lossy • Full catalogue • Radio", "AAC • Best effort match", "FLAC • Uncompressed live & studio recordings").
   - `iconType`: Icon identifier (`YOUTUBE`, `SAAVN`, `ARCHIVE`, `ADDON`).
   - `isLossless`: Boolean indicating if source supplies lossless/high-bitrate streams.
   - `enabled`: User toggle state.
2. **Persistent Storage**:
   - Persist the ordered list of source IDs and enabled flags in SharedPreferences via `PlaybackSourcesPreferences`.
   - Maintain default order: `[Custom Addons..., JioSaavn, Internet Archive, YouTube Music]`.
   - Flow observation with `collectAsStateWithLifecycle`.
3. **Active Quality Observation**:
   - Observe the effective `AudioQualityCeiling` from `com.example.playback.NetworkQualityMonitor` (or `AudioQualityPreferences.getNetworkSettings()`).
   - Gating rules:
     - **Data Saver (~96 kbps) & Standard (~160 kbps)**: JioSaavn, Internet Archive, and custom lossless addons are **disabled and greyed out** (forced OFF). Only YouTube Music can play music.
     - **High (~320 kbps)**: JioSaavn is fully accessible and toggleable. YouTube Music is accessible. Internet Archive and lossless addons are greyed out (forced OFF).
     - **Maximum / Lossless**: All sources (Internet Archive, JioSaavn, custom addons, YouTube Music) are fully accessible to toggle and reorder.

---

### B. Playback Pipeline: Stream Resolution (`StreamResolver.kt`)
1. **Source Prioritization**:
   - Update `StreamResolver.resolve()` to read the active ordered sources list from `PlaybackSourcesPreferences`.
   - Query enabled and network-eligible sources in exact user-defined order:
     1. If top source is a custom addon and eligible, attempt addon streaming first.
     2. If JioSaavn is higher and eligible, attempt JioSaavn matching.
     3. If Internet Archive is higher and eligible (Maximum/Lossless quality), attempt Archive resolution.
     4. If YouTube Music is reached or needed as fallback, resolve YouTube audio stream.
   - If all optional sources fail or are turned off, YouTube Music resolves the playback stream as the guaranteed fallback.

---

### C. UI Layer: Settings & Sources Management

#### 1. Settings Entry Point (`PlaybackIntegrationsSection.kt` in `OtherSettingsPage.kt`)
- Simplify the existing "Streaming Addons" card into a clean row:
  - Title: **Streaming Addons**
  - Subtitle: Currently selected active source (e.g., "JioSaavn", "Lossless Bridge", or "YouTube Music (Default)")
  - On/Off Switch: Global enable toggle for addons
  - Row tap: Opens the full-screen `SourcesScreen` via callback or navigation state.

#### 2. Dedicated Full-Screen Screen (`SourcesScreen.kt`)
- **Top App Bar**: Back navigation button, title "Sources", action icon for adding a new addon.
- **Header Section**: `SOURCES, TRIED IN THIS ORDER` in uppercase muted typography.
- **Reorderable Card List**:
  - Drag-and-drop handle (`=`) for moving items up/down.
  - Number badge (1, 2, 3...) showing priority.
  - Source icon + bold title + descriptive subtitle.
  - On/Off Switch (styled with app theme accent).
  - Visual grey-out / disabled state with helper badge when restricted by the current network quality setting (e.g., *"Requires High or Lossless quality"*).
  - YouTube Music displayed with "Always on" label when it is the sole fallback.
- **Add Addon Card / Dialog**:
  - "Add an addon" item at bottom with icon.
  - Dialog or input sheet to enter manifest URL (`https://...`), test connection with `StreamingAddons.connect()`, and add the new addon to the sources list.
  - Option to remove custom addons.

---

## 3. Network & Streaming Quality Gating Matrix

| Source | Data Saver (~96k) | Standard (~160k) | High (~320k) | Maximum / Lossless |
|---|---|---|---|---|
| **YouTube Music** | Accessible (Always on) | Accessible (Always on) | Accessible (Active/Fallback) | Accessible (Active/Fallback) |
| **JioSaavn** | Greyed out / Off | Greyed out / Off | **Accessible & Toggleable** | **Accessible & Toggleable** |
| **Internet Archive** | Greyed out / Off | Greyed out / Off | Greyed out / Off | **Accessible & Toggleable** |
| **Custom Lossless Addons** | Greyed out / Off | Greyed out / Off | Greyed out / Off | **Accessible & Toggleable** |

---

## 4. Implementation Steps

1. **Create Data & Preference Model** (`PlaybackSourcesPreferences.kt`):
   - Model `PlaybackSourceItem` and persist ordered source list.
   - Helper methods to compute effective available sources based on current `AudioQualityCeiling`.
2. **Update `StreamResolver.kt`**:
   - Read prioritized sources from `PlaybackSourcesPreferences`.
   - Respect user sequence and active network quality gating during stream lookup.
3. **Build `SourcesScreen.kt`**:
   - Reorderable list supporting drag gestures.
   - Source switches and quality-based availability styling.
   - Addon connection dialog using `StreamingAddons.connect()`.
4. **Update `OtherSettingsPage.kt` & `PlaybackIntegrationsSection.kt`**:
   - Replace cluttered textfield layout with single openable entry row.
   - Navigate to `SourcesScreen` with back press handler.
5. **Verify & Test**:
   - Verify compilation with `compile_applet`.
   - Verify drag reordering, toggle states, network quality gating, and YouTube fallback.
