# Baseline profile collection

The `benchmark` module records real startup, Home/Search/Library navigation, and mini-player
expansion/collapse paths. It does not replace app UI, inject music, or bypass recommendation gates.
The startup-only journey also supplies the startup profile used for DEX layout.

Use a dedicated portrait Android 13+ device/emulator (API 28+ is supported; older devices may
require root for collection). Enable USB debugging and connect it with ADB. The target uses
the plugin's non-debuggable, debug-signed `nonMinifiedRelease` variant. Install that variant,
finish onboarding, and play then pause a real song before collection so playback resumption
provides a mini-player. A production installation signed with another key cannot be updated by
this variant; use a dedicated device rather than deleting personal app data.

```sh
gradle :app:installNonMinifiedRelease
# Open the profiling installation, complete onboarding, play and pause a song.
gradle :benchmark:generateBaselineProfile
```

The second command intentionally runs device instrumentation to **generate profiles**. It was
not run as part of this change. It fails with a preparation message if no real session exists,
rather than silently producing a profile missing the player path. It never manually refreshes
Home or searches for music; existing Last.fm caching, viewport loading and request limits apply.

The consumer plugin saves the combined output to `app/src/main/baseline-prof.txt` (and startup
rules to `startup-prof.txt`) for review and commit. Existing profile rules remain in place until
collection succeeds. Release builds package the checked-in profile with ProfileInstaller and
do not run a device, benchmark or generation task: `automaticGenerationDuringBuild = false`.
Use a release build to assess AOT improvements; debug builds do not represent release performance.
