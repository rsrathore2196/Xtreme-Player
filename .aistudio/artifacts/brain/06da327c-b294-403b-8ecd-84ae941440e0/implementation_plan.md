# Implementation Plan: Custom Lyrics (.lrc) Import & Default Settings

Implement custom `.lrc` lyrics file import into the Music Player screen's lyrics provider popup with track-specific persistence, word/line-level timing synchronization, update the default haptic feedback setting to off, and build the updated debug APK in the visible `apk/` folder.

---

## 1. Scope & Core Requirements
1. **Lyrics Provider Popup in Music Player Screen Only**:
   - Add an **"Import Lyrics"** entry at the very top of the popup, directly above **"Auto"**.
   - Subtitle/Description: *"Import Custom Lyrics .lrc file"*.
   - Include a radio button before the title matching the design of other lyrics provider options.
   - Do **NOT** add this option to the lyrics provider popup in *Other Settings*.
2. **Permission & File Selection Flow**:
   - Check storage permission (`READ_EXTERNAL_STORAGE` on API <= 32); request if not already granted.
   - Launch system document/file picker with MIME types filtering for `.lrc` files (`text/*`, `application/octet-stream`, `*/*`).
   - Validate that the picked file has a `.lrc` extension or valid timestamped lyric contents.
3. **Parsing & Timing Fidelity**:
   - Full support for both word-by-word / syllable timing (`[mm:ss.xx]<mm:ss.xx> word` and karaoke `(start,dur)`) and standard line-by-line timing (`[mm:ss.xx] line text`) with offset tags via `LyricsDocuments.timedText`.
4. **Permanent Track-Specific Persistence**:
   - Save the imported `.lrc` file in app-internal private storage (`context.filesDir/custom_lyrics/`).
   - Map strictly to the current playing track using track ID, song title, and artist name.
   - Automatically load this custom `.lrc` whenever this specific song is played in the future.
   - Allow switching back to standard online providers (Auto, LRCLIB, etc.) or re-selecting custom lyrics via the radio button.
5. **Default Settings**:
   - Set the default for haptic feedback (`KEY_HAPTICS_ENABLED`) to `false` (off for new users, customizable in Other Settings).
   - Restore the full `OtherSettingsPreferences.kt` class cleanly.
6. **Delivery**:
   - Verify compilation with `compile_applet`.
   - Build debug APK and place in visible `/apk/app-debug.apk`. Ensure no hidden build folders remain.

---

## 2. Proposed Architecture & Changes

### A. Custom Lyrics Storage Manager (`CustomLyricsManager.kt`)
- Location: `app/src/main/java/com/example/data/local/CustomLyricsManager.kt`
- Responsibilities:
  - Securely manage custom `.lrc` files in `context.filesDir/custom_lyrics/`.
  - Keys based on sanitized track ID and title/artist fingerprint: `hash("${track.id}_${track.title}_${track.artist}")`.
  - Methods:
    - `saveCustomLyrics(context: Context, track: MusicTrack, lrcContent: String): Boolean`
    - `getCustomLyrics(context: Context, track: MusicTrack): TrackLyrics?`
    - `hasCustomLyrics(context: Context, track: MusicTrack): Boolean`
    - `removeCustomLyrics(context: Context, track: MusicTrack): Boolean`
    - `isCustomLyricsSelected(context: Context, trackId: String): Boolean`
    - `setCustomLyricsSelected(context: Context, trackId: String, selected: Boolean)`

### B. Restore & Update `OtherSettingsPreferences.kt`
- Restore all preferences methods, properties, and constants from decompiled bytecode.
- Change `isHapticsEnabled` default fallback from `true` to `false`:
  ```kotlin
  fun isHapticsEnabled(context: Context): Boolean {
      return getPrefs(context).getBoolean(KEY_HAPTICS_ENABLED, false)
  }
  ```

### C. Lyrics Fetching & Priority in `PlayerViewModel.kt` & `LyricsProvider.kt`
- When `fetchLyricsForTrack(track)` is invoked:
  - Check if custom lyrics exist and are selected for this track (or if custom lyrics were imported for it).
  - If custom lyrics exist and are active for this track, load them instantly with `CustomLyricsManager.getCustomLyrics(context, track)` without hitting network providers.
  - Expose `importCustomLyrics(uri: Uri)` on `PlayerViewModel`:
    - Read input stream from SAF URI.
    - Parse using `LyricsDocuments.timedText`.
    - Persist to `CustomLyricsManager`.
    - Set custom lyrics active for the track and emit to `_currentLyrics.value`.
  - Expose `selectTrackLyricsProvider(provider: String)`:
    - If provider is `"Import Lyrics"`, enable custom lyrics for current track (or prompt file pick if none exists yet).
    - If another provider is picked, set custom lyrics inactive for current track and fetch from the selected online provider.

### D. UI Component in `LyricsControls.kt` & `ExpandedPlayerScreen.kt`
- Update `LyricsProviderSelectionPopup`:
  - Add optional parameters `allowImport: Boolean = false`, `hasCustomLyrics: Boolean = false`, `isCustomSelected: Boolean = false`, and `onImportLyrics: () -> Unit = {}`.
  - When `allowImport == true` (only passed from `ExpandedPlayerScreen`, not `OtherSettingsPage`):
    - Render the `"Import Lyrics"` item above `"Auto"` at the top.
    - Radio button checked when `isCustomSelected == true`.
    - Title: `"Import Lyrics"`.
    - Subtitle: if `hasCustomLyrics` is true, show *"Custom .lrc active (tap to replace)"*, else *"Import Custom Lyrics .lrc file"*.
    - On click: if custom lyrics already exist and radio button is unselected, select custom lyrics; if tapped to import/replace, trigger permission check and file picker.
- In `ExpandedPlayerScreen.kt`:
  - Setup `rememberLauncherForActivityResult` for storage permission (`ActivityResultContracts.RequestPermission()`) and file picker (`ActivityResultContracts.OpenDocument()`).
  - Wire callback to `playerViewModel.importCustomLyrics(uri)`.

---

## 3. Verification & Build
1. Run `compile_applet` to ensure zero compilation or syntax errors.
2. Build debug APK using `gradle assembleDebug`.
3. Copy generated APK to `apk/app-debug.apk` and clean up any hidden temporary build artifacts.
