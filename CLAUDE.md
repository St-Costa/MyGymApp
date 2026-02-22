# MyGymApp - Project Context

## Overview
Android gym tracking app built with Kotlin + Jetpack Compose. File-based architecture (like Obsidian) — all data stored as `.md` files with YAML frontmatter. No database.

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material3
- **DI**: Hilt
- **Navigation**: Compose Navigation (single Activity)
- **YAML parsing**: snakeyaml-engine
- **Image loading**: Coil
- **Charts**: Vico (to be added)
- **Theme**: Dark only
- **UI Language**: English
- **Min SDK**: 26 (Android 8.0)

## Architecture
```
data/model/      → Data classes (Exercise, Routine, WorkoutSession, ExerciseSet)
data/parser/     → Markdown+YAML serialization (MarkdownParser, ExerciseParser, RoutineParser, WorkoutParser)
data/repository/ → File-based CRUD with in-memory cache (ExerciseRepository, RoutineRepository, WorkoutRepository, FileManager)
ui/navigation/   → Screen routes + NavHost (AppNavigation, Screen)
ui/theme/        → Dark-only Material3 theme (Color, Type, Theme)
ui/components/   → Reusable composables
ui/screen/       → Screen composables + ViewModels (one package per screen)
di/              → Hilt modules
```

## Data Storage
Files stored in app internal storage under `gymdata/`:
- `exercises/` — Exercise .md files (YAML frontmatter: id, name, type, bodypart, link, created, updated)
- `routines/` — Routine .md files (YAML frontmatter: id, name, day, enabled, exercises list, created, updated)
- `history/YYYY/MM/` — Workout session .md files (date-organized for efficient querying)
- `history/_idx/` — Exercise index: one `{exerciseId}.idx` file per exercise, newline-separated session paths
- `cache/images/` — Cached images from URLs

File naming:
- Exercises/routines: `slugify(name)-{id}.md` where id has type prefix (`ex-`, `rt-`)
- History sessions: `YYYY-MM-DD_{routineId}_{sessionId}.md` — routineId embedded for filename-based lookup

## Key Design Decisions
- **No database**: All data in .md files, in-memory cache for performance
- **YAML frontmatter**: Structured data in YAML, free-text notes in markdown body
- **Pre-computed tonnage**: Stored in session frontmatter for fast gitgraph queries
- **History by date**: `YYYY/MM/` directories for efficient date-range scans
- **ID-based session filenames**: `YYYY-MM-DD_{routineId}_{sessionId}.md` — routineId in filename allows routine-session lookup by filename filter (no YAML parse needed)
- **Exercise index**: `history/_idx/{exerciseId}.idx` maps exerciseId → session file paths; maintained on every save/delete; rebuilt by one-time migration
- **Typed ID prefixes**: `ex-{8hex}` for exercises, `rt-{8hex}` for routines — visually distinguishable in filenames and logs
- **Name sync on rename**: ExerciseRepository and RoutineRepository call WorkoutRepository to update all history sessions when a name changes
- **Auto-prune**: Sessions older than 3 months deleted on startup (MainViewModel.init), with exercise index cleanup
- **One-time migration**: `migrateOldSessionFiles()` renames old slug-based history files to new ID format and rebuilds exercise index; guarded by `_idx/.migrated` sentinel
- **Vertical scroll picker**: For one-handed reps/weight input (no keyboard popup)
- **Auto-save**: Debounced 500ms writes for text fields (no save button); ExerciseEdit and RoutineEdit save automatically via `ViewModel.onCleared()` when user navigates back. Save uses a dedicated `clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` (not `runBlocking`) to avoid ANR
- **DataChangedSignal**: Singleton `SharedFlow` bus (`data/DataChangedSignal.kt`). Edit ViewModels emit `notifyExercisesChanged()` / `notifyRoutinesChanged()` after save completes; list ViewModels (ExerciseList, RoutineList, WeekView, Main) collect and reload so UI reflects changes immediately on navigate back
- **Stopwatch**: Foreground service with Chronometer notification (status bar) + in-app MM:SS display (always visible, dimmed when stopped)
- **No popup notifications**

## Screens
1. **MainScreen** — Gitgraph view + navigation buttons (Week View, Exercises, Routines)
2. **WeekViewScreen** — 7 days with assigned enabled routines, current day highlighted
3. **ExerciseListScreen** — Grouped by body part, orange/blue borders (forza/stretch), picker mode for routine creation
4. **ExerciseEditScreen** — Create/edit exercise (name, link, notes, bodypart autocomplete, type toggle)
5. **RoutineListScreen** — List with enable/disable toggle
6. **RoutineEditScreen** — Name, day, exercise selection + set config; superset pairing via chain-link button
7. **ActiveRoutineScreen** — Active workout: exercise list (singles + superset groups), completion state, editable notes, progress after completion
8. **StrengthExerciseScreen** — Media, description, sets with scroll picker, previous values, progress chart
9. **StretchExerciseScreen** — Media, description, stopwatch, sets with checkboxes; takes `onComplete` (separate from `onBack`)
10. **SupersetScreen** — Interleaved sets for two paired exercises; FORZA pickers pre-populated from previous session; per-exercise tonnage % badge

## Implementation Progress

### [x] Phase 1: Foundation — DONE
What exists:
- Android project: Kotlin + Compose + Hilt, builds successfully
- **Data models**: `Exercise`, `Routine`, `WorkoutSession`, `ExerciseSet` (sealed class: Strength/Stretch)
- **Parsers**: `MarkdownParser` (generic YAML frontmatter + body), `ExerciseParser`, `RoutineParser`, `WorkoutParser`
- **Repositories**: `FileManager` (manages gymdata/ dirs), `ExerciseRepository` (CRUD + in-memory cache), `RoutineRepository` (CRUD + in-memory cache), `WorkoutRepository` (history querying by date range, by routine, by exercise)
- **Navigation**: `Screen` sealed class with all routes, `AppNavigation` NavHost with all 9 screens + ExercisePicker mode
- **Theme**: Dark-only Material3 (Color.kt, Theme.kt, Type.kt)
- **All 9 screens**: Currently placeholder composables with "Coming Soon" text, but fully wired in navigation
- **Launcher icon**: Simple barbell vector drawable

### [x] Phase 2: Exercise Management — DONE
- `ExerciseListScreen` with ViewModel: grouped by bodypart, orange (forza) / blue (stretch) borders, picker mode
- `ExerciseEditScreen` with ViewModel: form with name, type toggle (FilterChip), bodypart autocomplete, link, notes, default rep range (FORZA only, same +/− UI as RoutineEditScreen)
- `Exercise` model: `defaultRepRangeMin`/`defaultRepRangeMax` stored in YAML frontmatter (fallback 8/12 for existing files)
- When adding an exercise to a routine, the default rep range comes from the exercise's `defaultRepRangeMin`/`defaultRepRangeMax`
- Components: `ExerciseCard`, `BodyPartAutocomplete`

### [x] Phase 3: Routine Management — DONE
- `RoutineListScreen` with ViewModel: list of routines with enable/disable Switch toggle
- `RoutineEditScreen` with ViewModel: name, day picker (dropdown), notes, exercise selection via picker navigation, set/rep config per exercise
- Exercise picker result passed via `savedStateHandle`
- Drag-and-drop reorder: long press hamburger handle → card lifts (elevation + surfaceVariant), drag vertically, surrounding items shift in real-time, release commits via `moveExercise(from, to)`
- Round +/− buttons (32dp `CircleShape`) for sets, rep range, time; rep range centered with `headlineSmall` font

### [x] Phase 4: Active Workout — DONE
- `ActiveRoutineScreen` with ViewModel: shows exercises, completion state (strikethrough + checkmark + opacity), editable notes with AutoSaveTextField, progress section after all complete (tonnage comparison)
- `StrengthExerciseScreen` with ViewModel: description (auto-save), sets with `ScrollPickerInput` (vertical drag to change reps/weight), previous values shown, "Complete Exercise" button
- `StretchExerciseScreen` with ViewModel: description (auto-save), stopwatch button (starts/stops `StopwatchService`), sets with time + checkbox, "Complete Exercise" button
- Components: `ScrollPickerInput` (vertical swipe number picker), `AutoSaveTextField` (debounced 500ms save)
- `StopwatchService`: foreground service with chronometer notification in status bar

### [x] Phase 5: Progress & Charts — DONE
- `GitgraphView`: 4x7 grid (dynamic cell size via `BoxWithConstraints`, fills width with 12dp horizontal padding + 6dp gap between cells), day-of-week header (M T W T F S S), colored squares (green=improved, red=regressed, dark=no workout), today highlighted with white border
  - Each colored square shows the absolute tonnage % change (vs previous session for that routine) in black bold, auto-sized via `AutoShrinkText` (starts at `cellSize * 0.48sp`, shrinks by 0.85× until it fits)
  - Below the last row (current week): routine name split word-per-line (`\n`), font auto-sized to maximize width (`cellSize * 0.55sp` start, min 5sp)
- `MainViewModel`: computes gitgraph data from last 28 days in a single loop; produces `gitgraphDays` (DayStatus ×28), `gitgraphTonnageChanges` (Double? ×28), `lastWeekRoutineNames` (String? ×7); color based on **last completed session of each day** vs most recent previous session for that same routine (sorted by `completedAt`); tonnage % change computed at query time (not stored), null if no previous or previous tonnage = 0; `seedDebugData()` for debug: deletes current-week sessions, creates 1 FORZA + 1 STRETCH exercise, 1 routine, previous-week baseline sessions + current-week sessions with varied weights
- `WeekViewScreen` with `WeekViewViewModel`: 7 days with assigned enabled routines, current day highlighted (bold + primary color), rest days shown
- Tonnage calculation in `ActiveRoutineViewModel.finalizeSession()`: sum(reps*weight) per exercise and by bodypart, stored in session file

### [x] Phase 6: Media & Polish — DONE
- `StopwatchService`: foreground service (`foregroundServiceType="specialUse"`) with dynamic bitmap notification icon
  - Channel ID `stopwatch_channel_v2`, `IMPORTANCE_DEFAULT` + `setSound(null,null)` (silent but visible)
  - `FOREGROUND_SERVICE_IMMEDIATE` to bypass Android 14+ 10-second notification delay
  - API 34+ (`UPSIDE_DOWN_CAKE`): `startForeground()` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
  - Manifest: `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` required on API 34+
  - API 36+ (`BAKLAVA`): uses `Notification.Builder` directly with `VISIBILITY_PUBLIC`, `setColor()`, `setUsesChronometer()`, and `requestPromotedOngoing=true` extra (for future Live Updates / Samsung NowBar support)
  - Dynamic bitmap icon: 96×96px square, **seconds only** (00–59), 72f bold font, color cycles every 60s through 5 colors (purple, amber, cyan, green, pink) readable with black text
  - `setLargeIcon(bitmap)` for notification shade display; `setUsesChronometer(true)` + `setWhen(startTime)` for auto-running time in shade
  - Vibration every 30 seconds (2000ms); trigger uses `lastVibrationAt` tracking (`elapsed - lastVibrationAt >= 30`) to avoid Handler drift skipping multiples of 30
  - Note: Samsung NowBar / Android 16 Live Updates pill requires proprietary Samsung APIs — NOT accessible to third-party apps. Standard APIs confirmed insufficient after testing.
- `MainActivity`: requests `POST_NOTIFICATIONS` permission at runtime on API 33+
- `StretchExerciseViewModel`: in-app coroutine timer (`timerJob`, `elapsedSeconds`), resets on each Start
- `StretchExerciseScreen`: in-app `MM:SS` display always visible (dimmed when stopped, secondary color when running); button logic captures `wasRunning` before `toggleStopwatch()` to avoid async state race
- `ImageCacheRepository`: downloads images from URLs, caches by SHA-256 hash, handles Google Drive URL conversion
- All screens have loading states (CircularProgressIndicator) and empty states

### [x] Phase 8: Media Display — DONE
- `MediaPreview` in `ui/components/`: handles images and YouTube videos
  - Images: loaded via Coil (SubcomposeAsyncImage), Google Drive share links auto-converted to direct download URL
  - YouTube: extracts video ID from watch/shorts/embed/youtu.be URLs; shows thumbnail (hqdefault.jpg) with play button overlay; tap → Chrome Custom Tab (`youtube.com/watch?v={id}`), fallback to ACTION_VIEW
  - **YouTube note**: WebView + YouTube embed is not viable on Android — MediaCodec writes decoded frames to a Surface that Compose cannot composite (confirmed via adb logcat: `setOutputSurface BAD_INDEX`, codec runs but frames invisible). Chrome Custom Tabs (`androidx.browser:1.8.0`) is the correct solution: stays in-app task, back button returns to exercise screen.
  - Error state: optional red error text (enabled in ExerciseEditScreen, silent in exercise screens)
- `ExerciseEditScreen`: live preview below link field, debounced 800ms; clears immediately if field emptied
- `StrengthExerciseScreen` + `StretchExerciseScreen`: MediaPreview shown above description field
- `MyGymApp` implements `ImageLoaderFactory`: Coil configured with permanent disk cache in `filesDir/gymdata/image_cache/` (100MB, never cleared by Android) + memory cache at 20% RAM

### [x] Phase 10: Code Quality & Bug Fixes — DONE
- **ANR fix**: Replaced `runBlocking` in `ExerciseEditViewModel.onCleared()` and `RoutineEditViewModel.onCleared()` with `clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- **DataChangedSignal**: `data/DataChangedSignal.kt` singleton bus — edit ViewModels emit after save, list ViewModels collect to reload (fixes stale data after rename)
- **Deduplication**: `slugify()` extracted to `data/util/StringUtils.kt`; shared composables (`FullscreenLoading`, `EmptyStateBox`, `DeleteConfirmationDialog`, `RoundStepButton`) extracted to `ui/components/CommonComposables.kt` — used across 7+ screens
- **Performance**: `WorkoutRepository.save()` uses `.distinct()` before exercise index writes; `ImageCacheRepository` streams downloads instead of `readBytes()` (avoids heap spikes); `ExerciseListViewModel.deleteExercise()` and `RoutineListViewModel.toggleEnabled()`/`deleteRoutine()` update state in-memory instead of full reload
- **Compose**: `remember(key)` memoization for font sizes in `GitgraphView`, rep range text in `StrengthExerciseScreen`, timer string in `StretchExerciseScreen`
- **Delete dialogs**: `DeleteConfirmationDialog` used in both `ExerciseEditScreen` and `RoutineEditScreen`

### [x] Phase 11: Supersets — DONE
- `RoutineExercise` gains `supersetWithNext: Boolean = false` (persisted in YAML frontmatter)
- `RoutineEditScreen`: chain-link (`Link`/`LinkOff`) button on each exercise card; paired exercises wrapped in a primary-color-bordered `SupersetPairContainer` that drags as a unit; segment-based drag-drop via `ExerciseSegment` sealed class (`Single` / `SupersetPair`)
- `ActiveRoutineScreen`: exercises grouped into `ExerciseGroup.Single` / `ExerciseGroup.Superset`; superset shown as a primary-bordered card with both exercises; routes to `SupersetScreen` via `onNavigateToSuperset`
- `SupersetScreen` / `SupersetViewModel` (`ui/screen/superset/`): loads both exercises + previous FORZA session sets + rep ranges; builds interleaved set list (ex1_set0 → ex2_set0 → …); FORZA pickers pre-populated from previous session values (`repsModified`/`weightModified` flag pattern same as `StrengthExerciseScreen`); STRETCH sets centered; `completeSuperset()` marks both exercises done and saves to session
- Completion signal: `SupersetScreen.onComplete` sets `completedSupersetIds = "id1,id2"` on `previousBackStackEntry.savedStateHandle`; `AppNavigation` reads it and calls `markExerciseCompleted` for each ID
- `material-icons-extended` dependency added (for `Icons.Default.Link` / `Icons.Default.LinkOff`)

### Remaining Work (future enhancements)
- [ ] Export/import data

### [x] Phase 7: Progress & Charts (post-completion) — DONE
- `TonnageLineChart` in `ui/components/`: pure Canvas line chart, one point per completed session, green/red coloring, no external library
- `ActiveRoutineScreen` progress section: filter chips (Totale + per bodypart), chart title with current tonnage, loading spinner
- `ActiveRoutineViewModel`: per-exercise tonnage change badge (loaded from reloaded session on disk), 12-week history query, fixed `finalizeSession` bug (now reloads from disk before computing tonnage)
- `WorkoutRepository`: added `getSession(sessionId, date)`, fixed `getLastSessionForRoutine` to sort by `completedAt` (was sorting by filename/UUID → wrong order for same-day sessions)

### [x] Phase 9: History Consistency & ID-Based File Structure — DONE
- **Typed IDs**: exercises use `ex-{8hex}`, routines use `rt-{8hex}` — generated in their respective repositories
- **Session filename**: `YYYY-MM-DD_{routineId}_{sessionId}.md` — routineId embedded so sessions for a routine are found by filename filter (no YAML parse)
- **Exercise index**: `history/_idx/{exerciseId}.idx` — maintained on every `save()`/`delete()`; used by `getSessionsForExercise` and `updateExerciseNameInHistory` for O(targeted) access instead of full scan
- **Name sync**: `ExerciseRepository.save()` calls `updateExerciseNameInHistory()` on rename; `RoutineRepository.save()` calls `updateRoutineNameInHistory()` — no file rename needed since filenames use IDs
- **Auto-prune**: `pruneOldSessions(now - 3 months)` runs at startup, cleans exercise index entries for deleted files
- **One-time migration**: `migrateOldSessionFiles()` renames old `YYYY-MM-DD_{slug}-{id}.md` files to new format and rebuilds exercise index; guarded by `_idx/.migrated` sentinel; runs sequentially before gitgraph load in `MainViewModel.init`

## Build & Run
```bash
ANDROID_HOME=/home/stefano/Android/Sdk ./gradlew assembleDebug    # Build debug APK
ANDROID_HOME=/home/stefano/Android/Sdk ./gradlew installDebug     # Install on device/emulator
```
Note: `local.properties` has `sdk.dir` set, so ANDROID_HOME may not be needed from Android Studio.

## Design Files
Design mockups are in `Design/` folder (Excalidraw files + `Funzionalita_schermate.md` spec in Italian).

## Gotchas
- Kotlin: don't name a private property `fooBar` and create `getFooBar()` — the generated getter clashes. Use distinct names.
- Gradle wrapper jar was copied from `~/.gradle/caches/` (no global gradle installed).
- **YouTube in WebView + Compose = impossible**: MediaCodec writes video frames to a driver-level Surface that bypasses all LAYER_TYPE settings and Compose compositing. Use Chrome Custom Tabs (`CustomTabsIntent`) instead.
