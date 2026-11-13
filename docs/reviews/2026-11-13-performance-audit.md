# Recent changes and performance audit

Reviewed the ten most recent first-parent changes on `main`, ending at `18ae0cb`.
The baseline is `daafb33`; merge contents are included. Git commits are used as
the review boundary because the checkout does not retain push batches.

| Change | Areas reviewed |
| --- | --- |
| `18ae0cb` | Collection play controls; connected import merging and recording deduplication |
| `a4fbed8` | Collection headers, metadata, artwork blends and download actions |
| `925458d` | Download progress, storage accounting and settings scrolling |
| `7e56c5f` | Library Downloads, square cards and offline queues |
| `6b8564b` | Liked Songs header and playback action placement |
| `0b27541` | Permanent downloads, account storage, imports and scrobbling |
| `fed7a6e` | Audio output popup and artwork shared between both players |
| `580e3af` | Player background colors and original song artwork |
| `6ecd712` | Refresh rate choices and collection/player artwork |
| `bfb31fd` | Glass sampling, rendering profiles and refresh settings |

## Findings and fixes

- Download mutations previously published state before persistence completed.
  Removal also deleted files before committing the index. All durable changes
  now commit first; failed writes retain the previous state and saved files.
- Clearing a collection rewrote its full JSON index for every track. It now
  performs one index commit and one WorkManager tag cancellation. Network
  preference changes use two batched commits around cancellation/requeue,
  retaining partial audio. Old requests cannot claim a replaced record or
  pause a newly queued replacement. Preference edits merge under
  the command lock, so concurrent Wi-Fi and quality edits cannot overwrite
  each other.
- Removed downloads could acquire stale playback readers or recreate storage
  while a worker was still preparing its transfer. Expected-record checks
  reject stale readers. A worker retains its storage through preparation,
  transfer and artwork saving; existing readers defer deletion until close.
- Download progress invalidated unrelated menus, collection sorting and the
  Library screen. Track observers now emit only their own changes. Saved
  collections sort only when their contents change. Counts, storage summaries,
  collection membership/progress and saved-track preparation run off Main.
  Membership and URI classification are reused between byte updates.
- Slow Downloads/Liked Songs lookups from the new library play controls could
  replace a newer song selection after finishing. Preparation now shares the
  same playback owner as all other selections; cancellation invalidates the
  older result before it can reach the player.
- Collection shuffling ran on Main. Queue preparation now uses the shared CPU
  dispatcher and cancels when a newer selection replaces it or the page exits.
- Palette extraction allocated five 32,768-entry float arrays even for tiny
  cover samples. Compact accumulators preserve bin order, floating-point
  accumulation order and tie behavior. The larger-sample path remains dense.
- Fractional glass capture offsets reconstructed the same native blur graph
  throughout a spring. Each surface now retains its last identical blur graph;
  radius, input and edge treatment invalidate it. Lens geometry and uniforms
  continue to update at every required animation frame.
- The shared CPU budget now uses one background lane on devices exposing up to
  three processors, retaining two lanes on larger devices. Audio renderer and
  loader threads retain their existing execution policies. Background work may
  take longer on a small CPU; the budget leaves room for UI, rendering and audio.
- Audio volume broadcasts now use the supplied music-stream value, avoiding
  repeated Binder queries for unrelated streams. Missing payloads retain the
  AudioManager fallback. The receiver uses explicit registration flags.
- Connected YouTube imports walked the entire response, including suggested
  tracks and unrelated continuation tokens. Imports now select the collection
  shelf and its cursor, respecting the active tab and excluding carousels.
- Spotify metadata requests could fail on an early-expired bearer token. One
  unauthorized response now refreshes and retries once. Rate limits do not
  trigger refresh, and disconnecting during refresh cannot restore an account.
- The app supports API 24 but uses `java.time` in listening/date paths. Core
  library desugaring supplies those APIs on Android 7 without changing dates.
  Date/time receivers also use explicit compatibility registration flags.
  Static-analysis annotations retain required superclass key dispatch and the
  API-23 text-layout constant (numerically identical to its API-29 alias);
  neither changes key behavior or text rendering.

## Preservation checks

No feature, layout, theme, artwork resolution, audio quality, blur radius,
sampling scale, animation duration or animation was removed or reduced.
Seven frozen fixtures compare the five extracted colors and every pixel of
the existing 40 × 40 background texture against the original implementation.
The fixtures include white borders, transparency, color ties and dense input.

Host JVM pixel-analysis allocation measurements for a 120 × 120 sample
(the unchanged decode and bitmap-copy buffer are excluded):

| Fixture | Before | After | Allocation reduction |
| --- | ---: | ---: | ---: |
| Two-color cover | 748,856 bytes | 159,120 bytes | 78.8% |
| Detailed opaque sample | 748,859 bytes | 463,632 bytes | 38.1% |

These are warmed allocation measurements, not phone frame-rate results.
Host timings vary with compilation and emulator load; no device FPS improvement
is claimed. The device regressions also check one commit for 500 removals,
unrelated progress suppression, failed-write preservation, reader leases, and
reuse of one native blur graph across 240 fractional capture offsets.

## Validation

- Debug application and Android-test APK builds, plus compilation of all JVM
  tests, passed.
- 64 pure JVM regression tests passed.
- Production lint analysis completed with zero errors; release R8 optimization passed.
- 111 distinct Android regression cases passed on API 34, including portrait
  and landscape layouts, playback, account isolation, migrations and transactions.

The full Robolectric runtime requires `nativeruntime-dist-compat:1.0.18`; its Maven
redirect to `gcp.mirrors.maven.org` is rejected by the cloud proxy (HTTP 403).
The dependency is not excluded or replaced to hide that failure.

Selected existing JVM fixtures are also exercised on the API 34 emulator with
an AndroidJUnit4 adapter outside the repository. Their native captures replace
the host-only screenshot API. Landscape cases were run separately at their
specified 740 × 600 and 740 × 400 viewports; portrait scrolling was checked at
400 × 850. These device runs complement, rather than stand in for, the blocked complete
Robolectric suite.

External account protocols are checked with fixtures and isolated browser/store
tests. Live sign-in and arbitrary server changes require real accounts and
network access. A software emulator cannot certify frame pacing, power use or
memory pressure across older physical phones; those require device traces.
