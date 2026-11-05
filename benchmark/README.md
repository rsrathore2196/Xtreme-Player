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
gradle :benchmark:connectedNonMinifiedReleaseAndroidTest \
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
draw. These intervals include the existing six-second Home admissions; there is no new
production delay. `optionalWorkWakeupAndInteraction` records twenty idle seconds after its
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
- `Xtreme/Baseline profile install`.

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
CPU time or frame latency. Admission spans include the intentional idle wait; a six-second
span is not six seconds of CPU work. Use frame-overrun percentiles and Perfetto scheduling,
GC, disk, database and RenderThread tracks to establish a cause before tuning concurrency,
cache sizes, retained-tab behavior or image quality.

## Sequential startup stages

The initial cache projection contains only Quick Picks, Albums & Singles and Trending
Community Playlists. Missing native shelves receive bounded native browse attempts; a
terminal response settles startup without fabricating sections. First draw is independent
of the network and optional data.

After usable priority content is drawn (or its fetch terminates), `Startup core tabs`
contains cache-only Search restore and lightweight Library playlist preparation. Deferred
read stages then wait five continuous foreground idle seconds each: Recently Played,
Music for You, Most Listened Artists. Each timer starts after the preceding data stage
settles. Remaining native cache restoration follows six further idle seconds. Playback
recording/journaling and explicit user actions bypass optional startup admission.

`warmStagedStartupThenInteraction` records forty foreground idle seconds before the first
interaction. It supplements the existing first-twenty-second journeys; it does not add an
application delay or guarantee remote work completes within forty seconds. Inspect the
`Startup ... idle/cache`, `Startup Search cache`, `Startup Library playlists`, profile
discovery and artist-summary spans for the actual sequence. Wait spans are elapsed idle
time, not CPU work. No initialization popup or hidden-tab composition is involved.

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
