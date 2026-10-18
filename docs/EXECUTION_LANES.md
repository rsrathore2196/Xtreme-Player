# Process execution lanes (baseline e230409)

## Implemented phases and profiling checkpoints

This change preserves UI/animation parameters, recording verification, recommendation scoring,
stream/provider priorities, DSP processing, and existing preference/cache formats. A dispatcher
limit is an execution budget, not a physical-core reservation or a guarantee of frame timing.

### 1. One shared CPU budget

`performance/AppDispatchers.kt` creates the **only** production
`Dispatchers.Default.limitedParallelism(2)` view. `BackgroundWorkCoordinator.optionalCpuDispatcher`
is a compatibility alias to that same object. Existing dispatcher-injectable components retain
that capability; callers must pass the shared view in production, never create another view.

- Lane 1: `Main.immediate` for UI and the player application looper.
- Lane 2: `CpuBounded` for ranking, scoring, parsing, decode/transform coordination and queue preparation.
- Lane 3: `Io`, shared `Database` (4), `HomeBookkeeping` (1), and `Imports` (2). These limit runnable
  work, not the number of suspended requests; existing HTTP/semaphore limits remain necessary.
- Lane 4: Media3-owned playback, renderer, codec, DSP, AudioSink and DataSource loader threads.
- Lane 5: WorkManager cache and diagnostic maintenance; worker file operations use IO.

Production CPU replacements cover Player/Home/Catalog view models, Music/Catalog/Stream
repositories, search routing, YouTube/Archive/JioSaavn parsing, recommendation discovery/ranking,
listening/taste preparation, lyrics analysis and cached-document parsing, playlist matching,
artist identity matching, player palettes, Coil decoders/transformations, and cache serialization.
Independent CPU(1) views were removed rather than layered on top of the new view. Queue candidate
updates have a mutex and generation guard to retain serialization/cancellation correctness.
Import metadata HTTP uses cancellable OkHttp callbacks; response reads run on Imports IO(2),
while HTML/JSON interpretation and fuzzy scoring use the shared CPU budget. Import matching has
2 workers/search permits (previously 6).

Profile checkpoint: inspect `Xtreme/Home preview restore`, existing Home preparation sections,
and CPU runnable time during cold start, scrolling and all tab transitions.

### 2. Visibility and playback boundaries

`MainNavigationScaffold.kt` no longer prefetches an ORIGINAL selected cover and a second palette
while the full player is collapsed. MiniPlayer requests remain bounded thumbnails (240px source
policy / constrained display decode). `ExpandedPlayerScreen.kt` owns the visible large cover and
120px software palette request; its keyed effect is cancelled on hiding/track change. The retained
exit composition uses a 192px request. The high-quality iTunes source remains preferred.

`PlayerViewModel.kt` starts a hidden lyrics lookup only after 10 seconds of stable foreground
playback **and** DEFERRED idle admission (foreground, first draw, Home readiness, no gestures,
no navigation, no competing admitted work). Opening Lyrics bypasses optional waiting. Selection
changes cancel the admission job and existing generation-protected lookup. No artificial lyrics
timing or scoring/provider preference change was introduced.

`PlaybackManager.kt` prepares queue metadata on CPU before application-looper mutation. Timeline
callbacks only capture MediaItems on their owning thread, then prepare metadata asynchronously
with selection/revision guards. The visible animation clock reads a thread-safe position anchor;
Media3 is sampled at the existing 500ms foreground cadence and on events/seeks, rather than being
queried from each display-frame lyrics callback. Interpolation preserves speed, paused seeks,
backward seeks and duration bounds; a stale sample cannot extrapolate indefinitely.

`MusicService.kt` remains a MediaLibraryService, exported for the existing Auto/browser clients,
with `foregroundServiceType="mediaPlayback"` and both foreground-service permissions. Media3
owns notification channel/foreground promotion and demotion; no competing ServiceCompat
promotion or second player was added. Controller creation still uses `buildAsync()` and callbacks
return to Main. Media3 loader-thread synchronous DataSource bridges remain worker-only; audio
rendering/DSP is deliberately excluded from the CPU dispatcher gate.

### 3. Preference snapshots without Main-thread files

`data/local/ReactiveSettingsRepository.kt` adapts the existing files/keys, not a second persistent
store. Application initialization preloads appearance, profile, network, other settings,
sleep-timer, backup and cache preferences on IO. UI getters use immutable map/StateFlow snapshots.
Optimistic edits update memory immediately, then one IO consumer commits them in order. No Android
apply() disk queue is added by these wrappers. Explicit restore commits wait for a real result only
on IO; Main commits are rejected. External raw preference edits refresh the same snapshots.
`StartupSettingsCache` listens to both optimistic and existing raw writes and drains pending
refreshes before producing a coherent settings snapshot.

`SleepTimerManager.kt` reads saved deadlines/boot count asynchronously and guards restoration
against a newer start/cancel. Deadline scheduling and pause/listener commands stay on Main;
persistence is ordered on IO. `BackupRestorePage.kt` acquires Room only inside the requested
backup/restore coroutine, not in composition.

### 4. A prepared singleton ImageLoader

`ui/util/AsyncImageLoader.kt` is a lightweight Coil ImageLoader handle installed in Application
before UI requests. Its factory constructs OkHttp and forces memory/disk-cache initialization
once on IO. Early `execute`/`enqueue` requests suspend for that same instance without blocking Main.
Synchronous cache getters never open a directory. No second cache, image resolver or Bitmap cache
was introduced. The actual loader and request defaults both share CpuBounded decode/transform
work. Coil hardware decoding, cache ownership, source quality and request identity are retained.

### 5. Maintenance outside startup

`performance/MaintenanceWorkers.kt` registers unique KEEP jobs on IO after actual first draw and DEFERRED idle admission:

| Work | Schedule | Constraints |
|---|---|---|
| Cache reconciliation/trim, lyrics disk quota, artwork LRU checkpoint | 12-hour periodic; coalesced one-time request when stale/over quota | 15-minute initial delay, battery not low |
| Old diagnostic files | 24-hour periodic | 15-minute initial delay, battery not low, device idle |

The two worker types share a maintenance mutex. WorkManager owns rescheduling/retries after
process death. A job already due during warm reopen also rechecks foreground Home/first-draw and
interaction readiness before taking cache locks. Headless/background workers do not depend on
Home readiness, an Activity or awaitStartupIdle(); no worker holds an active-work lease while
waiting for this check.
SmartCache retains its own mutex across explicit/scheduled cache actions. Owner counters and
small debounced state checkpoints remain immediate IO bookkeeping, not directory scans. Actual
cache owners are re-read before eviction; saved estimates never authorize deletion. Coil/Media3/
OkHttp journal APIs remain the only owners of their data. Advisory artwork access indexing no
longer loads/parses a 2,000-entry file during image-loader startup or starts a save coroutine per
cache touch. Periodic workers merge/checkpoint it; explicit image trim loads it when needed.
Lyrics saving no longer sorts/scans its cache directory every 30 seconds.

Only app-owned diagnostic directories and old log/txt/tmp files are swept. User-requested backup,
restore, update verification, cache clear and storage inspection stay immediate IO operations.
No new automatic user backup policy or manual SQLite WAL/VACUUM behavior was invented.
Transport resource cleanup (SABR sessions/temp windows, HTTP journals) stays with its playback/
cache owner: it is essential bounded resource lifecycle, not disposable periodic maintenance.

### 6. Optional SDK initialization and traces

Manifest merger removals target `FirebaseInitProvider`, `EmojiCompatInitializer`,
`ProfileInstallerInitializer`, and `WorkManagerInitializer`. ProcessLifecycle, OkHttp platform
initialization, media service, FileProvider and ProfileInstallReceiver remain intact.
`DeferredLibraryInitialization.kt` performs equivalent optional Firebase/font/profile work on IO
through the existing DEFERRED queue after first draw. Profile installation uses the supported
ProfileInstaller API and retains its existing per-install skip behavior. WorkManager uses the
Application Configuration.Provider and is first acquired by idle-admitted IO scheduling, so its own database initialization does not start immediately after first draw.

MainActivity reports first draw from an actual OnDraw traversal followed by a posted callback,
and then calls reportFullyDrawn(). This is a UI-shell milestone, not evidence that every shelf
or GPU frame has completed. Async trace sections include `Xtreme/Preferences preload`,
`Xtreme/Settings restore`, `Xtreme/ImageLoader init`, `Xtreme/MediaController connect`,
`Xtreme/Foreground first draw`, and the existing named Home/startup sections.

## Exact file changes by phase

Paths below are relative to `app/src/main/java/com/example/`, unless prefixed otherwise. Some
files participate in more than one phase; no visual effect implementation was modified.

### 1 — CPU / data execution

- [`performance/AppDispatchers.kt`](../app/src/main/java/com/example/performance/AppDispatchers.kt)
- [`performance/BackgroundWorkCoordinator.kt`](../app/src/main/java/com/example/performance/BackgroundWorkCoordinator.kt)
- [`ui/viewmodel/HomeViewModel.kt`](../app/src/main/java/com/example/ui/viewmodel/HomeViewModel.kt)
- [`ui/viewmodel/PlayerViewModel.kt`](../app/src/main/java/com/example/ui/viewmodel/PlayerViewModel.kt)
- [`ui/viewmodel/CatalogBrowserState.kt`](../app/src/main/java/com/example/ui/viewmodel/CatalogBrowserState.kt)
- [`data/repository/MusicRepository.kt`](../app/src/main/java/com/example/data/repository/MusicRepository.kt)
- [`data/repository/CatalogRepository.kt`](../app/src/main/java/com/example/data/repository/CatalogRepository.kt)
- [`data/repository/StreamResolver.kt`](../app/src/main/java/com/example/data/repository/StreamResolver.kt)
- [`data/repository/ArtistPhotoRepository.kt`](../app/src/main/java/com/example/data/repository/ArtistPhotoRepository.kt)
- [`data/repository/iTunesArtworkRepository.kt`](../app/src/main/java/com/example/data/repository/iTunesArtworkRepository.kt)
- [`data/remote/AudioRoutingService.kt`](../app/src/main/java/com/example/data/remote/AudioRoutingService.kt)
- [`data/remote/OnlineMusicApiService.kt`](../app/src/main/java/com/example/data/remote/OnlineMusicApiService.kt)
- [`data/remote/YouTubeMusicApiService.kt`](../app/src/main/java/com/example/data/remote/YouTubeMusicApiService.kt)
- [`data/remote/YouTubeAudioStreamService.kt`](../app/src/main/java/com/example/data/remote/YouTubeAudioStreamService.kt)
- [`data/remote/InternetArchiveApiService.kt`](../app/src/main/java/com/example/data/remote/InternetArchiveApiService.kt)
- [`data/remote/LyricsProvider.kt`](../app/src/main/java/com/example/data/remote/LyricsProvider.kt)
- [`data/importer/PlaylistImportEngine.kt`](../app/src/main/java/com/example/data/importer/PlaylistImportEngine.kt)
- [`recommendation/UserTasteProfile.kt`](../app/src/main/java/com/example/recommendation/UserTasteProfile.kt)
- [`recommendation/RecommendationListeningObserver.kt`](../app/src/main/java/com/example/recommendation/RecommendationListeningObserver.kt)
- [`recommendation/UnifiedRecommendationEngine.kt`](../app/src/main/java/com/example/recommendation/UnifiedRecommendationEngine.kt)
- [`recommendation/YouTubeRecommendationDiscovery.kt`](../app/src/main/java/com/example/recommendation/YouTubeRecommendationDiscovery.kt)
- [`recommendation/PersonalizedHomeRecommendationEngine.kt`](../app/src/main/java/com/example/recommendation/PersonalizedHomeRecommendationEngine.kt)
- [`data/cache/HomeCardCache.kt`](../app/src/main/java/com/example/data/cache/HomeCardCache.kt)
- [`data/cache/RecommendationCache.kt`](../app/src/main/java/com/example/data/cache/RecommendationCache.kt)
- [`playback/AutomixQueueManager.kt`](../app/src/main/java/com/example/playback/AutomixQueueManager.kt)

### 2 — Visibility / playback

- [`ui/components/MainNavigationScaffold.kt`](../app/src/main/java/com/example/ui/components/MainNavigationScaffold.kt)
- [`ui/components/ExpandedPlayerScreen.kt`](../app/src/main/java/com/example/ui/components/ExpandedPlayerScreen.kt)
- [`ui/components/SyncedLyricsView.kt`](../app/src/main/java/com/example/ui/components/SyncedLyricsView.kt)
- [`ui/util/PlayerArtworkPreparer.kt`](../app/src/main/java/com/example/ui/util/PlayerArtworkPreparer.kt)
- [`playback/PlaybackManager.kt`](../app/src/main/java/com/example/playback/PlaybackManager.kt)
- [`playback/PlaybackPositionClock.kt`](../app/src/main/java/com/example/playback/PlaybackPositionClock.kt)
- [`service/PlaybackSessionStore.kt`](../app/src/main/java/com/example/service/PlaybackSessionStore.kt)
- [`service/MusicService.kt`](../app/src/main/java/com/example/service/MusicService.kt)

### 3 — Settings / persistence

- [`data/local/ReactiveSettingsRepository.kt`](../app/src/main/java/com/example/data/local/ReactiveSettingsRepository.kt)
- [`data/local/StartupSettings.kt`](../app/src/main/java/com/example/data/local/StartupSettings.kt)
- [`data/local/ThemePreferences.kt`](../app/src/main/java/com/example/data/local/ThemePreferences.kt)
- [`data/local/UserProfilePreferences.kt`](../app/src/main/java/com/example/data/local/UserProfilePreferences.kt)
- [`data/local/OtherSettingsPreferences.kt`](../app/src/main/java/com/example/data/local/OtherSettingsPreferences.kt)
- [`data/local/AudioQualityPreferences.kt`](../app/src/main/java/com/example/data/local/AudioQualityPreferences.kt)
- [`data/local/RecentSearchPreferences.kt`](../app/src/main/java/com/example/data/local/RecentSearchPreferences.kt)
- [`data/local/BackupRestoreManager.kt`](../app/src/main/java/com/example/data/local/BackupRestoreManager.kt)
- [`ui/screens/settings/BackupRestorePage.kt`](../app/src/main/java/com/example/ui/screens/settings/BackupRestorePage.kt)
- [`playback/SleepTimerManager.kt`](../app/src/main/java/com/example/playback/SleepTimerManager.kt)

### 4 — Artwork initialization

- [`XtremeMusicApp.kt`](../app/src/main/java/com/example/XtremeMusicApp.kt)
- [`ui/util/AsyncImageLoader.kt`](../app/src/main/java/com/example/ui/util/AsyncImageLoader.kt)
- [`ui/util/ImageUtils.kt`](../app/src/main/java/com/example/ui/util/ImageUtils.kt)

### 5 — WorkManager maintenance

- [`performance/MaintenanceWorkers.kt`](../app/src/main/java/com/example/performance/MaintenanceWorkers.kt)
- [`data/cache/ManagedImageDiskCache.kt`](../app/src/main/java/com/example/data/cache/ManagedImageDiskCache.kt)
- [`data/cache/SmartCacheManager.kt`](../app/src/main/java/com/example/data/cache/SmartCacheManager.kt)
- [`app/build.gradle.kts`](../app/build.gradle.kts)
- [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)

### 6 — SDK lifecycle / tracing

- [`MainActivity.kt`](../app/src/main/java/com/example/MainActivity.kt)
- [`performance/DeferredLibraryInitialization.kt`](../app/src/main/java/com/example/performance/DeferredLibraryInitialization.kt)
- [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml)

## Audit task-group routing

This is the routing contract for all 111 audited groups. Combined lanes indicate a required
handoff within a group, not two competing CPU pools. Android callbacks, RenderThread, SQLite,
network callbacks and audio threads keep their framework ownership; their expensive application
transformations use Lane 2. Small immutable UI choices/layout iteration and required player
property capture remain UI work, not background analytical loops.

| # | Audited task group | Lanes after refactor |
|---|---|---|
| 1 | Application settings restore | 3 |
| 2 | Initial settings/profile read | 3 |
| 3 | Window lifecycle / touch / first draw | 1 |
| 4 | Frame responsiveness observation | 1 (framework frame-observer callback) |
| 5 | Palette/image-loader/iTunes/key/portrait/device prewarming | 3 → 2 (admitted) |
| 6 | Cache maintenance initialization | 3 counters; 5 scans/trim |
| 7 | Home owners and requests | 3 (HomeBookkeeping) |
| 8 | Home DB collection | 3 → 2 |
| 9 | Home cached preview | 3 → 2 |
| 10 | Home full-cache enrichment | 3 → 2 (deferred) |
| 11 | Home favorites | 3 → 2 |
| 12 | Recent listening events | 3 → 2 |
| 13 | Recent track metadata | 3 → 2 |
| 14 | Historical personalization | 3 → 2 (incremental) |
| 15 | Taste scoring and fingerprints | 2 |
| 16 | Recently Played / Most Listened Artists preparation | 2 |
| 17 | Recommendation section filtering/diversification | 2 |
| 18 | Prepared recommendation publication | 3 publication; 1 Compose |
| 19 | Unified shelf discovery orchestration | 3 → 2 |
| 20 | YouTube Music discovery / response parsing | 3 → 2 |
| 21 | JioSaavn recording matching | 3 → 2 |
| 22 | Shared discovery admission | 3 admission |
| 23 | Radio / low-water replenishment | 3 → 2 → 1/4 |
| 24 | Persistent taste snapshot | 3 → 2 → 3 |
| 25 | Recommendation metadata/mapping cache | 3 → 2 → 3 |
| 26 | Listening evidence tracking | 2 → 3; 1 player triggers |
| 27 | Pager / dock selection, gestures and springs | 1 |
| 28 | Navigation hosting / screen visibility | 1 |
| 29 | Home viewport and row scroll reporting | 1 lightweight viewport reporting |
| 30 | Shared player UI projection | 2 |
| 31 | Search display projection | 2 |
| 32 | Catalog query/category/detail control | 1 control; 3 → 2 results |
| 33 | JioSaavn + YouTube song search | 3 → 2 |
| 34 | Legacy/global search helper | 3 → 2 |
| 35 | Recent search preferences | 3 |
| 36 | Library playlists/favorites/recent rows | 3 → 2 |
| 37 | Open playlist/detail rows | 3 → 2 → 1 |
| 38 | Favorite toggles | 2 → 3 → 1 |
| 39 | Create/rename/delete/add/remove playlists | 1 control; 3 DB; 2 preparation |
| 40 | Profile/theme/dynamic glass edits | 1 memory; 3 persistence |
| 41 | Onboarding/profile save | 1 state; 3 persistence |
| 42 | Network-quality setting changes | 3; 1 state |
| 43 | Lyrics provider/size preferences | 3; 1 state |
| 44 | Other Settings initial preference reads | 1 memory snapshots |
| 45 | Storage statistics screen refresh | 3 (explicit); 5 (automatic) |
| 46 | Backup discovery / metadata | 3 |
| 47 | Backup write / restore / import transactions | 3 → 2 → 3 |
| 48 | Theme export / file and URI sharing | 3; 1 sharing intent |
| 49 | Country/language/theme picker filtering | 1 static UI choices; 2 catalog results |
| 50 | About/community links / external intents | 1 |
| 51 | PlaybackManager application orchestration | 1 |
| 52 | Saved playback session restore/write | 3 → 2 → 1 |
| 53 | Controller connection | 1 async builder; 3 restore |
| 54 | Queue/media-item preparation | 2 → 1 |
| 55 | Queue snapshot/callback bookkeeping | 1 player capture; 2 conversion |
| 56 | Candidate pool merging | 2 |
| 57 | Foreground progress | 1 at 500ms while foreground |
| 58 | Visible player timeline / lyric clock | 1 interpolation from sampled anchor |
| 59 | Playback history / meaningful-play saves | 3; 2 taste |
| 60 | Stream source resolution | 3 → 2 |
| 61 | Exact YouTube/InnerTubeX extraction | 3 |
| 62 | JioSaavn API / DES URLs | 3 → 2 |
| 63 | Archive metadata search | 3 → 2 |
| 64 | In-play lossless upgrade | 3 → 1/4 |
| 65 | Lossless prebuffer | 3; 4 loader |
| 66 | Media3 cache construction / data source open | 4 loader; 3 disk owner |
| 67 | Synchronous YouTube DataSource bridge | 4 loader → 3 resolver bridge |
| 68 | YouTube range transport / expiry recovery | 4 loader; 3 token refresh |
| 69 | SABR DataSource open/read | 4 loader |
| 70 | SABR producer / disk windows / session cleanup | 3 producer; 4 loader/resource lifecycle |
| 71 | MusicService create / MediaSession / player listeners | 1 application looper; 4 engine |
| 72 | Service queue restoration / Auto browsing / search | 3 → 2 → 1 |
| 73 | Service persistent checkpoint | 1 capture → 3 persistence |
| 74 | Six-second JioSaavn fallback watcher | 1 state → 3/2 resolution |
| 75 | DSP desired state / enhancer and EQ commands | 1 desired state → 3 commands |
| 76 | PCM EQ/enhancer / limiter | 4 audio thread |
| 77 | Bit-perfect/direct AudioTrack / output observation | 4 audio; 3 route query; 1 command |
| 78 | Network transport monitoring | 3 callbackFlow |
| 79 | Output device list and selection | 3 query; 1 player routing |
| 80 | System volume observation | 3 query; 1 callback |
| 81 | Sleep timer deadline | 3 restore/persist; 1 deadline |
| 82 | Signal path popup / device dialogs | 1 |
| 83 | Lyrics selection / automatic prefetch decision | 1 visibility; 3 idle admission |
| 84 | Lyrics lookup/cache orchestration | 3 cache/network; 2 analysis |
| 85 | Lyrics provider requests | 3 HTTP callbacks |
| 86 | Lyrics parse / quality / recording scoring | 2 |
| 87 | Lyrics line/word/syllable animation and autoscroll | 1 |
| 88 | Lyrics glow preparation | 2 bitmap mask; 1 paragraph draw |
| 89 | ImageLoader factory / first acquisition | 3 initialization; 1 nonblocking handle |
| 90 | Track-artwork interceptor | 3 resolution; 2 matching/decode |
| 91 | Home/ordinary thumbnail decode | 2 decode; 3 cache/network |
| 92 | Expanded-player image decode | 2 visible-only decode; 3 image IO |
| 93 | High-resolution iTunes lookup | 3 → 2 |
| 94 | Home thumbnail enrichment gate | 3 admission → 2 |
| 95 | Selected-track artwork prefetch (even collapsed player) | Removed hidden full-size prefetch; bounded visible thumbnails (2/3) |
| 96 | Visible-player palette extraction | 2 visible-only |
| 97 | Decoded artwork cache scan for player placeholder | 2 |
| 98 | Palette cache persistence | 2 encode/decode; 3 atomic write |
| 99 | Artist portrait lifecycle / display | 1 lifecycle; 2 decode; 3 fetch |
| 100 | Artist photo metadata / preferred-source fallback | 3 → 2 |
| 101 | Home card disk persistence | 2 → 3 (debounced, atomic) |
| 102 | Coil managed key index | 1/3 tiny map updates; 5 index checkpoint |
| 103 | Smart Cache stats/trim/clear | 5 automatic; 3 explicit actions |
| 104 | Automatic release check / pending update recovery | 3 recovery; 3 admitted optional check; 1 dialogs |
| 105 | Update download work | 3 verification; Android DownloadManager |
| 106 | Playlist source metadata download | 3 IO(2) → 2 parse |
| 107 | Playlist recording matching | 3 IO(2) → 2 score |
| 108 | Liquid Glass / backdrop / blur resource setup | 1 draw setup; framework RenderThread/GPU |
| 109 | Visible shimmer/ambient animation clock | 1 clock; framework RenderThread/GPU |
| 110 | Motion album art player | 1 lifecycle; framework media renderer |
| 111 | Haptics | 1; preferences from memory |

## Verification and practical limits

Host regression tests cover settings consistency, shared CPU admission, image-loader suspension,
artwork identity/caching, position anchors, maintenance schedules, migrations/restores, playback
resolution/recovery, Home presentation and navigation isolation. Build the debug APK and the
profileable/non-minified benchmark variants before device profiling.

On a physical device, record Perfetto with the named trace sections, ART/GC, sched, FrameTimeline,
IO and RenderThread during cold launch, warm reopen, rapid navigation, opening/closing Player,
lyrics opening, network quality changes and background playback. Compare frame overruns and GC
against baseline e230409 using the same device/build/data. Verify USB/DSP/audio continuity and
notification/Auto behavior on hardware. Host tests cannot establish audible continuity, a
measured startup-lag reduction, or "zero jank". This environment has no connected Android device.
