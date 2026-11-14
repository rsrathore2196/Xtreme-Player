# Baseline profile collection

The `benchmark` module records real startup, Home/Search/Library navigation, and mini-player
expansion/collapse paths. It does not replace app UI, inject music, or bypass recommendation gates.
The startup-only journey also supplies the startup profile used for DEX layout.

Use a dedicated portrait Android 13+ device (API 28+ is supported; older devices may
require root for collection). Enable USB debugging and connect it with ADB.
The `benchmark` app build is minified, resource-shrunk, non-debuggable and shell-profileable,
with debug signing and the separate package `com.aistudio.xtremeplayer.vkmz.benchmark`.
It can coexist with the normal app and does not replace that app's data. Its normal cache
budgets, animation specifications, artwork quality and glass settings are unchanged.

```sh
bash gradlew :app:installBenchmark
# In the isolated installation: finish onboarding, play and pause a real song.
bash gradlew :benchmark:connectedBenchmarkBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.benchmark.StartupResponsivenessBenchmark
```

For profile collection use `:app:installNonMinifiedBenchmark`, prepare that same isolated
installation, then run `:app:generateBenchmarkBaselineProfile`. The plugin deliberately uses
an unminified target when collecting profiles; timing measurements above use the minified
`benchmark` target instead. The legacy `nonMinifiedRelease` / `:benchmark:generateBaselineProfile`
entry points still address the original package and require a dedicated device if its
production installation has a different signing key. Do not delete personal data to resolve
a signing mismatch.

Profile generation is an explicit device operation. It fails with a preparation message if
no real session exists, rather than producing a profile missing the player path. It does not
manually refresh Home or inject music. Generation was not run in the cloud workspace because
no Android device was connected.

The consumer plugin saves the combined output to `app/src/main/baselineProfiles/baseline-prof.txt` (and startup
rules to `startup-prof.txt` in the same directory) for review and commit. Existing profile rules remain in place until
collection succeeds. Release builds package the checked-in profile with ProfileInstaller and
do not run a device, benchmark or generation task: `automaticGenerationDuringBuild = false`.
Use a release build to assess AOT improvements; debug builds do not represent release performance.

## Post-launch responsiveness measurement

`StartupResponsivenessBenchmark` records startup timing, frame timing and production trace
sections for five iterations of each selected journey. It begins scrolling and tab changes as
soon as Home navigation is available. There is no initialization popup, global preparation
boundary, hidden-tab warmup, or wait for optional recommendations.

```sh
bash gradlew :benchmark:connectedBenchmarkBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.benchmark.StartupResponsivenessBenchmark
```

Prepare a dedicated installation as described above. Keep real user data intact. Run the same
journey with the same device, compilation mode and settings before and after a change. Repeat
with empty history, substantial history and populated caches, and paused/active playback.
Host tests and compilation do not establish device responsiveness or measured improvement.

The ordinary journeys disable UiAutomator's implicit idle waits, exercise six seconds of
scrolling/tab changes, and verify `main_tab_<index>_settled` rather than treating the dock's
selected label as proof that navigation completed. `rapidTabRetargeting` tests taps while
slides are still in flight. Mini-player expansion/collapse runs when a real retained session
exists. The Home navigation readiness check fails explicitly if onboarding is incomplete.

`warmInitialTwentySecondsThenInteraction` leaves the initial Home untouched for twenty
seconds, then exercises the first scroll, first visits to Search/Library, and the player.
Use its Perfetto trace to inspect 0–2, 2–6, 6–12 and 12–20 seconds after the first foreground
draw. The timestamps come from the actual draw and worker trace sections; idle waits are not CPU time. `optionalWorkWakeupAndInteraction` records twenty idle seconds after its
initial interaction journey, then interrupts optional work with tab changes.

`coldFirstInteractionAfterOneMinute` and `warmFirstInteractionAfterOneMinute` record sixty
seconds before any scroll or tab selection. Compare these with `retainedActivityReopening`
(HOT) and `warmReopening` (Activity recreation with the process retained). For a true
background return after sixty seconds, additionally background the prepared app for sixty
seconds and capture its resume with Perfetto; the one-minute tests keep Home foreground.
They must not be presented as equivalent lifecycle scenarios.

Current application trace names are:

- `Xtreme/Foreground first draw`, `Xtreme/Settings restore`.
- `Xtreme/Home native cache restore`, `Xtreme/Home profile cache restore`,
  `Xtreme/Startup Recently Played cache`.
- `Xtreme/Native Home response`, `Xtreme/Home shelf projection`,
  `Xtreme/Home cached shelf reveal`, `Xtreme/Home listening history`.
- `Xtreme/Home profile admission`, `Xtreme/Home artist admission`,
  `Xtreme/Home profile discovery`, `Xtreme/Home artist summaries`.
- `Xtreme/ImageLoader init`, `Xtreme/Artwork decode or fetch`, `Xtreme/Artwork fetch`,
  `Xtreme/Artwork decode`, `Xtreme/Artwork transform`.
- `Xtreme/Home row composition`, `Xtreme/Navigation transition`,
  `Xtreme/Search first access`, `Xtreme/Library first access`.
- `Xtreme/Discovery optional network`, `Xtreme/Playback source resolution`.
- `Xtreme/Baseline profile install`, `Xtreme/Glass backdrop capture`.

`Home row composition` wraps existing lazy item content without adding layout nodes.
`AndroidOwner:measureAndLayout` is emitted by the current Compose UI dependency; it covers
owner layout, not exclusively Home. Inspect its slices alongside Home row composition,
Main/RenderThread scheduling and frame deadlines. Search/Library first-access sections are
timestamp markers, not end-to-end screen-load durations.

The Coil listener is installed only when Android tracing is enabled. Its fetch/decode/transform
spans close on success, failure and cancellation, including individual fallback attempts.
Memory hits do not decode. Fetch includes disk/provider fetch time; decode identifies actual
decoder work on the shared bounded CPU lane. Inspect gaps between fetch completion and decode
start alongside thread scheduling to assess queue contention. The existing combined artwork
span includes the admitted candidate attempt and must not be added to its nested spans.

Trace metrics use `Mode.Sum` to include repeated occurrences instead of reporting only the
first row/decode/navigation. Sums of overlapping async spans are elapsed request time, not
CPU time or frame latency. Admission spans include their intentional quiet wait and must not be interpreted as CPU work. Use frame-overrun percentiles and Perfetto scheduling,
GC, disk, database and RenderThread tracks to establish a cause before tuning concurrency,
cache sizes, retained-tab behavior or image quality.

## Startup work and cache reuse

Home first draw does not wait for the network. Cached Recently Played, Music for You and
artist portraits restore after that draw, independently of native Home transport and live
historical aggregation. Search restores saved categories and recent queries; Library prepares
its lightweight playlist snapshot. This preparation does not compose unopened tabs, scan
liked/playlist tracks or start a historical query. A requested tab gets its first layout before
its slide starts, with artwork and live collectors paused until the slide settles.

Once the initial native projection settles, authoritative history, profile discovery, artist
aggregation and remaining native shelves use serial **750 ms continuous foreground quiet**
windows. Input, navigation and backgrounding restart pending quiet windows. Successful cached
cards remain usable on a warm return while live Room subscriptions wait for a real foreground
draw. Completed stages remain completed; explicit user actions and playback retain their
foreground paths. Workers wake on admission changes or their next quiet deadline, rather than
polling a held gesture every 50 ms. Ordinary Boolean urgency callbacks retain a bounded fallback
check because their callers do not expose a notification source.

Artwork requests retain the existing memory/disk budgets and shared decode lane. URL hints
remember only validated, sufficient renditions in the existing bounded metadata cache. They
preserve recording, signed-source, viewport and crop identity. Memory hits precede metadata IO;
missing or expired hints fall back to normal resolution without spending network time on the
hint. Same-URL waiters release global image slots while waiting. Glass capture reuses a layer
only when source revision, sampling transform, raster size/phase and effect padding match;
stationary artwork arrivals and geometry changes invalidate it. No blur quality is reduced.

`coldFirstTwentySecondsInteractive` and `warmFirstTwentySecondsInteractive` exercise scrolling
and first tab visits as soon as Home is available, throughout the opening window.
`sustainedNavigationAndScrolling` extends the journey to 60 seconds. `playerAndLyricsTransitions`
requires a real retained track and records repeated maximize/minimize transitions with player
controls returning. The initial-idle journeys remain available as separate scenarios; an idle
launch is not interchangeable with an interactive launch. `warmStagedStartupThenInteraction`
observes 40 seconds before interacting. None of these waits is an application delay.

Validate on the HMD Vibe 2 Pro / Android 16 and a reference phone using the same display mode,
settings, history, artwork cache state and audio route. Compare cold/warm/retained launches,
empty/populated history, fresh/evicted caches, offline/slow transport and paused/active playback.
Inspect first-20-second frame overrun p50/p95/p99 and missed deadlines, Main/RenderThread CPU,
GC, image decode counts and `Xtreme/Glass backdrop capture` slices. A shorter capture sum alone
does not establish lower GPU time. Compare sustained frame times and thermal state as well.

Android App Info **Data** and **Cache** are disk storage categories, not internet usage. Measure
actual network bytes separately with per-UID Android network statistics or a captured network
trace over the same journey. Record disk/audio/image cache sizes separately before and after;
cache eviction and downloads can affect both. Do not increase quotas or move audio files just
to match another app's App Info numbers. This change leaves those storage policies intact.

## Runtime profile installation

The application installs its packaged profile once after the first foreground draw, on
the existing IO dispatcher. Short input/navigation and playback-resolution admission
applies, but installation does not wait for Home stages or scheduled maintenance. A
transient IO failure permits another attempt on a later foreground draw; there is no
retry timer. `RuntimeProfile` logs ProfileInstaller's numeric result (`1` means installed,
`2` already installed). The trace above measures installation work, not ART compilation.

Play may supply install-time profiles and dex metadata. A plain sideloaded APK containing
`assets/dexopt/baseline.prof` and `baseline.profm` does not by itself guarantee install-time
AOT compilation. Runtime installation makes the profile available for subsequent ART
optimization; it cannot retroactively compile the first launch. Verify actual ART state
and compare the same compilation mode on each device before reporting a startup gain.

Release packaging can be checked without a device using SDK `profgen dumpProfile` with
the packaged binary profile and the exact APK, then `profgen extractProfile` with
`--profile-format v0_1_5_s` to verify Android 12+ profile/metadata conversion. This does not
replace collecting real journeys. No connected device was available for the low-end
audit, so the checked-in rules were retained and no new device-collected profile or
cold/warm frame-time result is claimed. Run the existing journeys on both Android 12/13
low-end hardware and a modern reference device with equivalent real cache/history data,
and inspect the first 20 seconds and background/reopening scenarios described above.
