# MyGymApp

A personal Android gym tracking app built with Kotlin and Jetpack Compose. Inspired by [Obsidian](https://obsidian.md/), all data is stored as plain `.md` files with YAML frontmatter — no database, no cloud, full ownership of your data.

## Screenshots

<p align="center">
  <img src="docs/screenshots/main_menu.jpg" width="30%" alt="Main screen with gitgraph" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/routine.jpg" width="30%" alt="Active routine screen" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/exercise.jpg" width="30%" alt="Exercise editor" />
</p>

<p align="center">
  <em>Main screen (gitgraph) &nbsp;|&nbsp; Active routine &nbsp;|&nbsp; Exercise editor</em>
</p>

## Features

- **Gitgraph dashboard** — 4×7 grid of the last 28 days. Each cell shows a workout's tonnage change vs. the previous session for that routine (green = improved, red = regressed). Routine names appear below the current week.
- **Week view** — see which routines are assigned to each day of the week.
- **Exercise library** — exercises grouped by body part with orange (strength) / blue (stretch) color coding. Attach an image or YouTube link to any exercise.
- **Routine builder** — configure sets, rep ranges, and pair exercises into supersets with a chain-link button. Drag-and-drop reorder.
- **Active workout** — tap an exercise to log sets. Strength exercises use a vertical scroll picker (no keyboard). Stretch exercises use a stopwatch with a status-bar notification.
- **Supersets** — interleaved sets for two paired exercises in a single screen, pre-populated from your previous session.
- **Progress charts** — per-bodypart tonnage line chart after completing a routine. Compares only exercises present in both sessions for a fair comparison.
- **Auto-save** — all text fields save automatically with a 500 ms debounce. No save button.
- **File-based storage** — data lives in `gymdata/` inside app internal storage as plain Markdown files. Easy to inspect, back up, or migrate.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material3 (dark only) |
| DI | Hilt |
| Navigation | Compose Navigation |
| YAML parsing | snakeyaml-engine |
| Image loading | Coil |
| Charts | Custom Canvas (`TonnageLineChart`) |

## Architecture

```
app/src/main/java/…/
├── data/
│   ├── model/        # Exercise, Routine, WorkoutSession, ExerciseSet
│   ├── parser/       # Markdown+YAML serialization
│   ├── repository/   # File-based CRUD with in-memory cache
│   └── util/         # StringUtils (slugify), DataChangedSignal
├── di/               # Hilt modules
├── ui/
│   ├── components/   # Reusable composables (ScrollPickerInput, MediaPreview, …)
│   ├── navigation/   # AppNavigation, Screen routes
│   ├── screen/       # One package per screen (Screen + ViewModel)
│   └── theme/        # Dark Material3 theme
└── service/          # StopwatchService (foreground)
```

### Data storage layout

```
gymdata/
├── exercises/              # {slug}-{ex-id}.md
├── routines/               # {slug}-{rt-id}.md
├── history/
│   ├── YYYY/MM/            # YYYY-MM-DD_{routineId}_{sessionId}.md
│   └── _idx/               # {exerciseId}.idx  (exercise → session path index)
└── image_cache/            # Coil permanent disk cache
```

## Building

```bash
# Debug APK
ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug

# Install on connected device / emulator
ANDROID_HOME=~/Android/Sdk ./gradlew installDebug
```

Requires Android SDK with min API 26 (Android 8.0).

## Design

Wireframes and functional specs are in the [`Design/`](Design/) folder (Excalidraw files).

## License

Personal project — all rights reserved.
