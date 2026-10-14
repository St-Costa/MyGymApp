---
name: fase
description: Close the current work as a new Phase — version bump, CHANGELOG paragraph, doc alignment, tests, single "Phase N — title" commit.
disable-model-invocation: true
argument-hint: "[titolo della fase]"
---

# Close a phase

Rules: docs/CONVENTIONS.md#commits and #versioning. One phase = one commit.

1. `git status` + `git diff` — understand what's being shipped. If nothing changed, stop.
2. N = `appVersionCode` in `gradle.properties` + 1. Set `appVersionCode=N`,
   `appVersionName=1.N` (hotfix shape `1.<prev>.1` only if the user says it's a hotfix).
3. Title: `$ARGUMENTS` if given, otherwise propose a short Italian title (style of
   `git log --oneline -10`) and confirm it with the user.
4. Add `## Phase N — <title>` at the top of `docs/CHANGELOG.md` (right after the intro):
   one Italian paragraph — the problem, what changed (key classes in backticks), tests added,
   ending with "Suite verde (JDK 21)." like the previous entries.
5. Docs alignment: if the change touches something described in CLAUDE.md, ARCHITECTURE,
   STORAGE, CONVENTIONS, SYNC, BACKUP — update it now. New pattern/gotcha → CONVENTIONS.md.
6. Run `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=~/Android/Sdk ./gradlew test`.
   Red → stop and report, don't commit.
7. Stage the specific files (never `backups/`, `gym-recovery/`, APKs) and commit:
   `Phase N — <title>` + a short English body of what changed + the Co-Authored-By trailer.
   The pre-commit hook re-runs the tests; don't `--no-verify`.
8. Report the commit hash. Don't push and don't install unless asked (`/install` for that).
