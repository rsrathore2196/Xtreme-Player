# Fix App Reopen & Tab Navigation Performance Lag

Eliminate the 5 to 10 second lag and UI stutter experienced after reopening the app and when navigating between the Home and Library tabs by redesigning background process scheduling, caching the user taste profile, optimizing tab state retention, and strictly serializing deferred tasks across isolated dispatchers.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The architectural plan has been revised to incorporate your explicit process management and scheduling requirements:
>
> - **Primary Lag Surface**: Tab navigation between Home and Library, exacerbated when reopening the app.
> - **15-Second Idle Gate**: Defer all background maintenance, cache reconciliation, and discovery refreshes until the user has been completely idle for **15+ seconds**.
> - **Sequential Execution with Breathing Gaps**: After the 15-second idle gate, background processes will **never run simultaneously**. They run **strictly one by one** with a deliberate 750ms–1000ms breathing gap between them, immediately yielding or pausing if user interaction resumes.
> - **Dispatcher Isolation & Main Thread Protection**:
>   - **`Dispatchers.IO`**: Exclusively used for disk I/O, Room/SQLite queries, file traversals, cache reconciliation, and network calls.
>   - **`Dispatchers.Default`**: Exclusively used for CPU-intensive data transformations, mathematical scoring, affinity calculations, and candidate pool sorting.
>   - **`Dispatchers.Main`**: Kept strictly minimal. Used solely for emitting lightweight UI states to Jetpack Compose. Absolutely zero disk I/O, JSON serialization, database operations, or heavy list transformations will ever execute on Main.
> - **Taste Profile Optimization**: Persist the computed `UserTasteProfile` to durable local cache and load updates incrementally in the background rather than reprocessing 5,000 historical events on reopen or tab return.

---

## 1. Overview & Core Concept

### What It Does
When users reopen the app or switch between the **Home** and **Library** tabs, the interface will remain immediately responsive and buttery smooth at native 60/120Hz display rates. All non-essential background operations—such as disk cache reconciliation, storage pruning, catalog discovery, and listening history analysis—are kept completely off the critical UI path. They wait for a continuous 15-second quiet period, execute sequentially one by one with breathing intervals, and run on dedicated background threads so the Main thread never stutters.

### Target Audience & Persona
Music listeners who frequently switch between browsing recommendations on Home and checking playlists/liked tracks in Library, as well as users who multitask and frequently resume the app from the background.

### Key Value
- **Zero-Stutter Tab Switching**: Instant switching between Home and Library with 0ms frame drops and no cold-start recomposition cascades.
- **Main Thread Purity**: Guaranteed 0% CPU starvation on Main; UI frames render uninhibited.
- **Controlled Resource Consumption**: Eliminates CPU, battery, and disk lock contention by running one task at a time with spaced intervals.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Reopening the App**:
   - The user opens or resumes the app after it was in the background.
   - Home and Library tabs display their pre-rendered states instantly.
   - No background disk scans or network discovery bursts occur.
   - The UI remains completely fluid while the user scrolls, browses, or navigates.
2. **Navigating Between Home and Library**:
   - The user taps or swipes between Home and Library.
   - The horizontal transition runs smoothly at native display refresh rates without stutter.
   - Neither tab drops state or re-triggers cold-start initialization.
3. **Sequential Idle Background Enrichment (15+ Seconds Inactive)**:
   - Only after 15 continuous seconds of zero touch/scroll/navigation does the coordinator awaken the queue.
   - **Task 1** runs (e.g. incremental taste profile update) on its appropriate background dispatcher (`Default` for math, `IO` for Room).
   - A **750ms breathing gap** occurs where the CPU rests and checks for any user input.
   - **Task 2** runs (e.g. cache usage reconciliation) on `Dispatchers.IO`.
   - Another **750ms breathing gap** occurs.
   - **Task 3** runs (e.g. background discovery refresh) on `Dispatchers.IO`.
   - If the user touches the screen or changes tabs at *any* millisecond during this sequence, the active task immediately yields and the queue pauses.

### Visual Identity & Theme Preservation
- **Preserved Design Language**: The liquid glass aesthetic, specular border highlights, dynamic glass backdrops, and theme adaptability across Light, Dark, and AMOLED modes remain completely intact.
- **No Structural UI Degradation**: All animations, bottom sheets, mini-player interactions, and screen elements retain their visual fidelity.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: 15-Second Inactivity Idle Gate on Reopen
- **Chosen Approach**: Introduce a dedicated 15-second foreground inactivity gate in `BackgroundWorkCoordinator`. When the app resumes from the background or initializes, all `StartupPriority.MAINTENANCE` jobs and optional discovery refreshes are deferred until `SystemClock.elapsedRealtime() - lastInteraction >= 15_000L`.
- **Why**: Currently, `quiet` only requires 250ms of quiet time, and tasks queued >30s in the background get auto-promoted, causing immediate disk scans and thread pool saturation on reopen. A 15-second quiet window guarantees the user is done with their initial tab navigation and browsing before heavy work begins.

### Decision 2: Sequential Pipeline with Breathing Gaps
- **Chosen Approach**:
  - Re-architect the deferred/maintenance executor in `BackgroundWorkCoordinator` as a strict **single-concurrency serialized FIFO queue**.
  - Enforce a **750ms–1000ms pause** between the completion of one background task and the admission of the next.
  - Before admitting each successive task, re-verify the 15-second idle rule: if the user interacted during or between tasks, immediately pause the queue until another 15 seconds of quiet elapses.
- **Why**: Running background tasks in parallel (even on background dispatchers) causes CPU thermal throttling, thread pool starvation, and SQLite database lock contention. Spacing them out sequentially ensures negligible impact on system responsiveness.

### Decision 3: Strict Dispatcher Allocation & Main Thread Isolation
- **Chosen Approach**:
  - **`Dispatchers.IO`**:
    - Room database reads/writes (`listeningEventDao`, `musicDao`).
    - File system reads, folder traversals, and size calculations (`getFolderSize`).
    - OkHttp network requests and Coil disk cache operations.
    - JSON serialization and file writing for `UserTasteProfileStore`.
  - **`Dispatchers.Default`**:
    - Scoring algorithms and ranking in `PersonalizedHomeRecommendationEngine`.
    - Affinity calculations and normalization in `UserTasteProfile`.
    - In-memory track deduplication and shelf preparation.
  - **`Dispatchers.Main`**:
    - Restricted exclusively to updating Compose `StateFlow` values (e.g. assigning already-prepared immutable data classes) and lifecycle callbacks.
    - Zero business logic, heavy loops, or I/O operations will ever be scheduled on Main.

### Decision 4: Persist `UserTasteProfile` & Incremental Delta Loading
- **Chosen Approach**:
  1. Serialize and persist the computed `UserTasteProfile` to a dedicated local cache file (`user_taste_profile.json`) on `Dispatchers.IO` along with `lastComputedTimestamp` and `lastEventId`.
  2. On app reopen or ViewModel subscription, restore the persisted profile instantly (0ms cold start).
  3. Instead of reading and sorting 5,000 history rows from SQLite on every app resume, query only new events since `lastComputedTimestamp` / `lastEventId` on `Dispatchers.IO`, update affinities on `Dispatchers.Default`, and persist back to storage.
- **Why**: Processing 5,000 rows through sequences and affinity maps consumes significant CPU. Persisting the profile eliminates this work completely from the critical startup and tab-switch path.

### Decision 5: Fix `WhileSubscribed(5000L)` Resets & Tab Pager Disposal
- **Chosen Approach**:
  1. In `HomeViewModel`, change `tasteSnapshot`, `tasteProfile`, `recentlyPlayed`, and `heroSeedTrack` from `WhileSubscribed(5000L)` to `SharingStarted.Lazily` or an in-session retained StateFlow.
  2. In `PlayerViewModel`, retain `favoriteTracks`, `recentlyPlayed`, and `playlists` so switching to Library does not cancel Home's flows after 5 seconds and cause massive re-evaluations when navigating back.
  3. Update `MainTabPager` to retain adjacent tab state (`beyondViewportPageCount = 1`) so switching between Home (0), Search (1), and Library (2) avoids full teardown and recomposition of the screen hierarchies.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Activity / Main Navigation                        │
│                                                                             │
│   ┌─────────────────────┐               ┌───────────────────────────────┐   │
│   │   HomeFeedRoute     │               │        LibraryScreen          │   │
│   │   (HomeViewModel)   │ ◄───────────► │      (PlayerViewModel)        │   │
│   └──────────┬──────────┘  Tab Pager    └───────────────┬───────────────┘   │
└──────────────┼──────────────────────────────────────────┼───────────────────┘
               │                                          │
               ▼                                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Background Work Coordinator                              │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ 15-Second Idle Gate: SystemClock.elapsedRealtime() - lastInput >= 15s │  │
│  └───────────────────────────────────┬───────────────────────────────────┘  │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │      Sequential Execution Queue (Single Worker + 750ms Gaps)          │  │
│  │                                                                       │  │
│  │   [Task 1] ──(750ms Gap)──► [Task 2] ──(750ms Gap)──► [Task 3] ...     │  │
│  │    (Yields immediately if user interacts at any point)                │  │
│  └───────────────────────────────────┬───────────────────────────────────┘  │
└──────────────────────────────────────┼──────────────────────────────────────┘
                                       │
     ┌─────────────────────────────────┴─────────────────────────────────┐
     ▼                                                                   ▼
┌──────────────────────────────────────────┐  ┌──────────────────────────────────────────┐
│             Dispatchers.IO               │  │           Dispatchers.Default            │
│                                          │  │                                          │
│ • SmartCacheManager disk walks & metrics │  │ • UserTasteProfile affinity calculations │
│ • Room / SQLite reads & writes           │  │ • Recommendation scoring & ranking math  │
│ • JSON serialization of taste profile    │  │ • In-memory track filtering & sorting    │
│ • Network discovery & catalog fetches    │  │ • Audio DSP & format policy matching     │
└──────────────────────────────────────────┘  └──────────────────────────────────────────┘
                                       │
                                       ▼ (Final State Only)
                      ┌─────────────────────────────────┐
                      │        Dispatchers.Main         │
                      │                                 │
                      │ • Update UI StateFlow values    │
                      │ • Compose frame draw only       │
                      └─────────────────────────────────┘
```

### Component & State Mapping

| Component | Current Issue | Targeted Resolution |
| :--- | :--- | :--- |
| **`BackgroundWorkCoordinator`** | Maintenance tasks promoted after 30s queue; admitted together after 250ms quiet. | 1. 15-second idle gate on reopen.<br>2. Strict sequential execution (one task at a time).<br>3. 750ms–1000ms breathing gap between tasks. |
| **`SmartCacheManager`** | Immediately initiates disk walks (`getFolderSize`) and auto-trim on reopen. | Defer reconciliation until 15s idle timer expires; run strictly on `Dispatchers.IO` sequentially; use cached sizes for immediate UI. |
| **`UserTasteProfile`** | Recomputed from 5,000 SQLite events on every startup; lost when flows reset. | Persist to local JSON storage (`Dispatchers.IO`); compute math on `Dispatchers.Default`; process only new events incrementally. |
| **`HomeViewModel`** | `WhileSubscribed(5000L)` resets taste to cold start when user is on Library >5s. | Retain state across tab switches with `SharingStarted.Lazily` / session caching; eliminate reset cascades; emit to UI via Main. |
| **`PlayerViewModel`** | Re-executes queries when navigating between Home and Library. | Retain `favoriteTracks`, `recentlyPlayed`, and `playlists` in memory while the activity is alive. |
| **`MainTabPager`** | `beyondViewportPageCount = 0` disposes off-screen tabs, causing full reconstruction. | Set `beyondViewportPageCount = 1` for instantaneous back-and-forth tab switching between Home & Library. |

---

## 5. Implementation Phases & Verification Strategy

### Step 1: `BackgroundWorkCoordinator` 15-Second Idle Gate & Sequential Queue
- Add a dedicated reopen idle condition: `reopenIdleUntilMs = SystemClock.elapsedRealtime() + 15_000L`.
- Any user interaction (`dispatchTouchEvent`, scroll, navigation) pushes the timestamp forward by 15,000ms.
- Convert deferred/maintenance execution to a single-threaded sequential worker.
- Insert a cooperative 750ms–1000ms breathing gap between each job execution, re-checking user idle status before each task.

### Step 2: Dispatcher Allocation Audit & Main Thread Protection
- Audit all background routines in `SmartCacheManager`, `HomeViewModel`, and `PersonalizedHomeRecommendationEngine`.
- Explicitly route database operations and disk scans to `Dispatchers.IO`.
- Explicitly route mathematical ranking, scoring, and normalization to `Dispatchers.Default`.
- Ensure `Dispatchers.Main` only handles fast immutable state updates.

### Step 3: `SmartCacheManager` Non-Blocking Reopen
- In `initialize`, remove the immediate call to `refreshStats(userRequested = false)`.
- Schedule reconciliation through the coordinator's sequential queue after the 15-second idle window.
- Display cached metrics immediately from `SharedPreferences` without touching disk.

### Step 4: `UserTasteProfile` Persistence & Incremental Loading
- Create `UserTasteProfileStore` to serialize/deserialize `UserTasteProfile` to `cacheDir/user_taste_profile.json` on `Dispatchers.IO`.
- On startup/reopen, synchronously load the cached profile if present.
- When updating, fetch only `ListeningEventEntity` rows with `id > lastProcessedId`, update affinities on `Dispatchers.Default`, and persist back to storage on `Dispatchers.IO`.

### Step 5: Flow Lifecycle & Tab Retention
- Update `tasteSnapshot`, `tasteProfile`, and history flows in `HomeViewModel` to prevent resetting to cold start.
- Update `MainTabPager` to keep adjacent pages primed (`beyondViewportPageCount = 1`).
- Verify that switching between Home and Library tabs is seamless and takes 0ms.

### Step 6: Verification & Compilation
- Run `compile_applet` to ensure the entire codebase compiles cleanly without syntax errors or missing imports.
- Verify that all existing unit and Robolectric tests pass.
