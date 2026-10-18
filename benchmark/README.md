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
Home or searches for music; existing recommendation caching, viewport loading and bounded request coordination apply.

The consumer plugin saves the combined output to `app/src/main/baseline-prof.txt` (and startup
rules to `startup-prof.txt`) for review and commit. Existing profile rules remain in place until
collection succeeds. Release builds package the checked-in profile with ProfileInstaller and
do not run a device, benchmark or generation task: `automaticGenerationDuringBuild = false`.
Use a release build to assess AOT improvements; debug builds do not represent release performance.

## Post-launch responsiveness measurement

`StartupResponsivenessBenchmark` records startup timing and frame timing for five cold launches
and five warm reopenings. It starts Home scrolling and Home → Search → Library → Home taps as
soon as the dock appears, while recommendation placeholders or cached cards are still visible.
It samples at least six seconds after the dock appears and disables UiAutomator's implicit
idle waits, so it does not skip the reported loading window or wait for network content.
If a paused real session is available, it also expands the mini-player. Onboarding must already
be complete; use the preparation instructions above without clearing an existing user's data.

```sh
gradle :benchmark:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.benchmark.StartupResponsivenessBenchmark
```

Run the same journey on the same device/build settings before and after changes. Compare
`frameOverrunMs` percentiles and the Perfetto traces around launch, tab slides and player expansion,
along with startup timing. Host unit tests and successful compilation do not measure device jank.

`optionalWorkWakeupAndInteraction` adds a warm-reopen journey covering the 15-second
optional-work idle window and subsequent Search/Library interaction. Its 17-second
pause is measurement-only: keep Perfetto recording to check whether contention was
merely moved later, then verify optional work yields when input resumes. Run this
with populated history/caches as well as a clean profiling installation. No
production animation, refresh timer or loading gate is changed by the benchmark.
