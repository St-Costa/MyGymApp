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
- `cache/images/` — Cached images from URLs

File naming: `slugify(name)-shortId.md` for exercises/routines, `YYYY-MM-DD_slug-id.md` for history.

## Key Design Decisions
- **No database**: All data in .md files, in-memory cache for performance
- **YAML frontmatter**: Structured data in YAML, free-text notes in markdown body
- **Pre-computed tonnage**: Stored in session frontmatter for fast gitgraph queries
- **History by date**: `YYYY/MM/` directories for efficient date-range scans
- **Vertical scroll picker**: For one-handed reps/weight input (no keyboard popup)
- **Auto-save**: Debounced 500ms writes for text fields (no save button)
- **Stopwatch**: Foreground service with Chronometer notification (status bar) + in-app MM:SS display (always visible, dimmed when stopped)
- **No popup notifications**

## Screens
1. **MainScreen** — Gitgraph view + navigation buttons (Week View, Exercises, Routines)
2. **WeekViewScreen** — 7 days with assigned enabled routines, current day highlighted
3. **ExerciseListScreen** — Grouped by body part, orange/blue borders (forza/stretch), picker mode for routine creation
4. **ExerciseEditScreen** — Create/edit exercise (name, link, notes, bodypart autocomplete, type toggle)
5. **RoutineListScreen** — List with enable/disable toggle
6. **RoutineEditScreen** — Name, day, exercise selection + set config
7. **ActiveRoutineScreen** — Active workout: exercise list, completion state, editable notes, progress after completion
8. **StrengthExerciseScreen** — Media, description, sets with scroll picker, previous values, progress chart
9. **StretchExerciseScreen** — Media, description, stopwatch, sets with checkboxes

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
- `ExerciseEditScreen` with ViewModel: form with name, type toggle (FilterChip), bodypart autocomplete, link, notes
- Components: `ExerciseCard`, `BodyPartAutocomplete`

### [x] Phase 3: Routine Management — DONE
- `RoutineListScreen` with ViewModel: list of routines with enable/disable Switch toggle
- `RoutineEditScreen` with ViewModel: name, day picker (dropdown), notes, exercise selection via picker navigation, set/rep config per exercise
- Exercise picker result passed via `savedStateHandle`

### [x] Phase 4: Active Workout — DONE
- `ActiveRoutineScreen` with ViewModel: shows exercises, completion state (strikethrough + checkmark + opacity), editable notes with AutoSaveTextField, progress section after all complete (tonnage comparison)
- `StrengthExerciseScreen` with ViewModel: description (auto-save), sets with `ScrollPickerInput` (vertical drag to change reps/weight), previous values shown, "Complete Exercise" button
- `StretchExerciseScreen` with ViewModel: description (auto-save), stopwatch button (starts/stops `StopwatchService`), sets with time + checkbox, "Complete Exercise" button
- Components: `ScrollPickerInput` (vertical swipe number picker), `AutoSaveTextField` (debounced 500ms save)
- `StopwatchService`: foreground service with chronometer notification in status bar

### [x] Phase 5: Progress & Charts — DONE
- `GitgraphView`: 4x7 grid (48dp cells), day-of-week header (M T W T F S S), colored squares (green=improved, red=regressed, dark=no workout), today highlighted with white border
- `MainViewModel`: computes gitgraph data from last 28 days; color based on **last completed session of each day** vs most recent previous session for that same routine (sorted by `completedAt`)
- `WeekViewScreen` with `WeekViewViewModel`: 7 days with assigned enabled routines, current day highlighted (bold + primary color), rest days shown
- Tonnage calculation in `ActiveRoutineViewModel.finalizeSession()`: sum(reps*weight) per exercise and by bodypart, stored in session file

### [x] Phase 6: Media & Polish — DONE
- `StopwatchService`: foreground service (`foregroundServiceType="specialUse"`) with Chronometer notification
  - Channel ID `stopwatch_channel_v2`, `IMPORTANCE_DEFAULT` + `setSound(null,null)` (silent but icon visible)
  - `FOREGROUND_SERVICE_IMMEDIATE` to bypass Android 14+ 10-second notification delay
  - API 34+ (`UPSIDE_DOWN_CAKE`): `startForeground()` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
  - Manifest: `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` required on API 34+
  - Custom icon `ic_stopwatch.xml`
- `MainActivity`: requests `POST_NOTIFICATIONS` permission at runtime on API 33+ (required for notification to appear)
- `StretchExerciseViewModel`: in-app coroutine timer (`timerJob`, `elapsedSeconds`), resets on each Start
- `StretchExerciseScreen`: in-app `MM:SS` display always visible (dimmed when stopped, secondary color when running); button logic captures `wasRunning` before `toggleStopwatch()` to avoid async state race
- `ImageCacheRepository`: downloads images from URLs, caches by SHA-256 hash, handles Google Drive URL conversion
- All screens have loading states (CircularProgressIndicator) and empty states

### [x] Phase 8: Media Display — DONE
- `MediaPreview` in `ui/components/`: handles images and YouTube videos
  - Images: loaded via Coil (SubcomposeAsyncImage), Google Drive share links auto-converted to direct download URL
  - YouTube: extracts video ID from watch/shorts/embed/youtu.be URLs; shows thumbnail (hqdefault.jpg) with play button overlay; tap → inline WebView player (`youtube.com/embed/{id}?autoplay=1&playsinline=1&rel=0`)
  - WebView settings: javaScriptEnabled, domStorageEnabled (required by YouTube embed), mediaPlaybackRequiresUserGesture=false
  - Error state: optional red error text (enabled in ExerciseEditScreen, silent in exercise screens)
- `ExerciseEditScreen`: live preview below link field, debounced 800ms; clears immediately if field emptied
- `StrengthExerciseScreen` + `StretchExerciseScreen`: MediaPreview shown above description field
- Note: WebView always shows gray in Android Studio Compose Preview — works correctly on device/emulator
- `MyGymApp` implements `ImageLoaderFactory`: Coil configured with permanent disk cache in `filesDir/gymdata/image_cache/` (100MB, never cleared by Android) + memory cache at 20% RAM

### Remaining Work (future enhancements)
- [ ] Delete confirmation dialogs for exercises and routines
- [ ] Reorder exercises in routine edit (drag & drop)
- [ ] Export/import data

### [x] Phase 7: Progress & Charts (post-completion) — DONE
- `TonnageLineChart` in `ui/components/`: pure Canvas line chart, one point per completed session, green/red coloring, no external library
- `ActiveRoutineScreen` progress section: filter chips (Totale + per bodypart), chart title with current tonnage, loading spinner
- `ActiveRoutineViewModel`: per-exercise tonnage change badge (loaded from reloaded session on disk), 12-week history query, fixed `finalizeSession` bug (now reloads from disk before computing tonnage)
- `WorkoutRepository`: added `getSession(sessionId, date)`, fixed `getLastSessionForRoutine` to sort by `completedAt` (was sorting by filename/UUID → wrong order for same-day sessions)

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
