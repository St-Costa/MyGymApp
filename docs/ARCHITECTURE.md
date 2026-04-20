# Architecture

High-level map of the codebase. For per-file details read the source — this document exists to tell you **where to look**.

## Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material3 (dark only) |
| DI | Hilt |
| Navigation | Compose Navigation (single Activity) |
| YAML | snakeyaml-engine |
| Images | Coil |
| Charts | Custom Canvas |
| BLE / heart rate | Polar BLE SDK + RxJava 3 |
| Min SDK | 26 (Android 8.0) · Target 36 |

## Package layout

```
com.mygymapp/
├── MyGymApp.kt              — Application + Hilt entry + Coil ImageLoaderFactory
├── MainActivity.kt          — Single activity (POST_NOTIFICATIONS runtime request)
├── data/
│   ├── DataChangedSignal.kt — App-wide SharedFlow event bus (exercises/routines changed)
│   ├── model/               — Exercise, Routine, WorkoutSession, ExerciseSet (sealed)
│   ├── parser/              — MarkdownParser, ExerciseParser, RoutineParser, WorkoutParser
│   ├── polar/               — Polar H10 subsystem (see docs/POLAR.md)
│   ├── repository/          — File-based CRUD with in-memory cache
│   └── util/                — StringUtils (slugify)
├── di/                      — Hilt modules (thin; most bindings via @Inject constructor)
├── ui/
│   ├── components/          — Reusable composables
│   ├── navigation/          — Screen sealed class + AppNavigation NavHost
│   ├── screen/              — One package per screen (Screen composable + ViewModel)
│   └── theme/               — Dark Material3 (Color, Type, Theme)
└── ui/service/              — StopwatchService, PolarStreamingService (foreground)
```

## Screens & routes

Defined in [Screen.kt](../app/src/main/java/com/mygymapp/ui/navigation/Screen.kt), wired in [AppNavigation.kt](../app/src/main/java/com/mygymapp/ui/navigation/AppNavigation.kt).

| Screen | Route | Purpose |
|---|---|---|
| Main | `main` | Gitgraph dashboard (last 28 days) + nav buttons |
| WeekView | `weekview` | 7-day grid with assigned routines |
| ExerciseList | `exercises` | Grouped by bodypart; also picker mode |
| ExerciseEdit | `exercises/edit?id={id}` | Create/edit exercise |
| RoutineList | `routines` | List with enable/disable toggle |
| RoutineEdit | `routines/edit?id={id}` | Create/edit routine with superset chaining |
| ActiveRoutine | `workout/{routineId}` | Live workout; routes to exercise screens |
| StrengthExercise | `workout/{sessionId}/strength/{exerciseId}` | Sets with scroll picker |
| StretchExercise | `workout/{sessionId}/stretch/{exerciseId}` | Sets with stopwatch |
| Superset | `workout/{sessionId}/superset/{id1}/{id2}` | Interleaved sets for paired exercises |
| ExercisePicker | `exercises/pick` | ExerciseList in picker mode (hidden) |
| SessionProgress | `session/{sessionId}/{date}` | Post-workout charts + ECG/cardio analysis |
| HeartRate | `heartrate` | Polar pairing, live ECG, readiness, cardio trends |

### Navigation graph

```
Main ─┬─→ WeekView ──→ ActiveRoutine ─┬─→ StrengthExercise
      │                               ├─→ StretchExercise
      │                               └─→ Superset
      ├─→ ExerciseList ──→ ExerciseEdit
      ├─→ RoutineList  ──→ RoutineEdit ──→ ExercisePicker ─(result)→ RoutineEdit
      ├─→ HeartRate
      └─→ SessionProgress   (reachable from Main gitgraph tap)
```

Exercise completion uses a `savedStateHandle` callback pattern: StrengthExercise/StretchExercise/Superset set `completedExerciseId` / `completedSupersetIds` on the previous back stack entry, which `AppNavigation` reads to fire `markExerciseCompleted`. See [CONVENTIONS.md](CONVENTIONS.md#completionsaved-pattern).

## ViewModels

Every screen has one ViewModel (same package). A few non-obvious collaborators:

| ViewModel | Key dependencies | Notes |
|---|---|---|
| `MainViewModel` | WorkoutRepository, RoutineRepository, DataChangedSignal | Computes gitgraph data (28 days, routine-name overlay) |
| `ActiveRoutineViewModel` | WorkoutRepository, RoutineRepository, ExerciseRepository, PolarManager, EcgAnalyzer | Starts/stops ECG recording + HR series capture |
| `SessionProgressViewModel` | WorkoutRepository, EcgAnalyzer, CardioTrendLoader | Post-workout charts and cardio analysis |
| `HeartRateViewModel` | PolarManager, UserProfileRepository, CardioTrendLoader | Combines ~9 PolarManager StateFlows for the Heart Rate screen |
| `HeartRateBarViewModel` | PolarManager | Lightweight VM for the in-workout HR/recovery bar |

The `ExerciseEditViewModel` and `RoutineEditViewModel` auto-save in `onCleared()` via a private `clearScope` — see [CONVENTIONS.md](CONVENTIONS.md#onclearedsave).

## Reusable components (`ui/components/`)

| Component | Role |
|---|---|
| `ScrollPickerInput` | Vertical-swipe number input for reps/weight (no keyboard) |
| `AutoSaveTextField` | 500ms-debounced text field with on-dispose flush |
| `MediaPreview` | Image (Coil, Google Drive URL rewrite) + YouTube (Chrome Custom Tabs) |
| `BodyPartAutocomplete` | Filtered dropdown over existing bodyparts |
| `ExerciseCard` | Card with FORZA orange / STRETCH blue border |
| `TonnageLineChart` | Pure-Canvas line chart for tonnage progression |
| `GitgraphView` | 4×7 grid of day statuses with tonnage % overlay |
| `HeartRateBar` | In-workout HR + recovery semaphore (hidden if disconnected) |
| `LiveEcgCard` | Live ECG waveform + beat counter + arrhythmia flags |
| `CardioTrendSection` | 4-week cardio sparklines + self-diagnosis (HeartRateScreen) |
| `SupersetPairContainer` | Primary-bordered wrapper for paired exercises in edit/active |
| `CommonComposables` | `FullscreenLoading`, `EmptyStateBox`, `DeleteConfirmationDialog`, `RoundStepButton` |

## Repositories

| Repository | Scope | Notes |
|---|---|---|
| `FileManager` | Directory layout | Owns `filesDir/gymdata` + subdirs |
| `ExerciseRepository` | Exercises CRUD | In-memory cache + mutex; `ex-{8hex}` IDs; updates history on rename |
| `RoutineRepository` | Routines CRUD | Like Exercise; `rt-{8hex}` IDs |
| `WorkoutRepository` | Session history | See [STORAGE.md](STORAGE.md#workout-history-and-exercise-index) for index structure |
| `ImageCacheRepository` | URL → local file | SHA-256 hash, Google Drive URL rewrite |
| `CardioTrendLoader` | 4-week cardio aggregates | Used by HeartRate and SessionProgress screens |
| `UserProfileRepository` | Age / weight / sex | SharedPreferences; needed for calorie & TRIMP formulas |

## Services

- **`StopwatchService`** — foreground, `specialUse` type. Running timer notification with per-minute color cycle and dynamic bitmap icon. Vibrates every 30s.
- **`PolarStreamingService`** — foreground, `connectedDevice` type. Keeps the BLE connection + HR visible in the status bar while the screen is off.

## Threading & concurrency

- Repositories use `withContext(Dispatchers.IO)` + `Mutex` on mutating ops.
- Coroutine scopes:
  - `viewModelScope` — screen-lifecycle work.
  - `clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — used **only** in `onCleared()` of the edit ViewModels to guarantee save completion after the VM is torn down. Cancellation happens in `finally` after save. See [CONVENTIONS.md](CONVENTIONS.md#onclearedsave).
- `DataChangedSignal` uses two `MutableSharedFlow(extraBufferCapacity=1)` for cross-VM invalidation.
- Polar work is RxJava (SDK constraint). Each `Disposable` is disposed before reassigning.

## Where to go next

- **File paths, session format, migration** → [STORAGE.md](STORAGE.md)
- **Heart rate / HRV / ECG / VO2max** → [POLAR.md](POLAR.md)
- **Patterns, gotchas, things you won't guess from the code** → [CONVENTIONS.md](CONVENTIONS.md)
- **Phase history** → [CHANGELOG.md](CHANGELOG.md)
