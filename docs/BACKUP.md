# Full-store backup — git-style incremental sync of *all* app data

> **Status:** **phone side implemented** (branch `feature/full-store-backup`, see
> CHANGELOG.md). The server side (`POST /v1/repo`, `GET /v1/manifest`, `GET /v1/file`, the
> git-commit-per-push hook) lives in `MyGymApp_server` — brief:
> [docs/backup-server-brief.md](backup-server-brief.md) — and is not yet built. This
> extends the existing session/readiness/scale/ECG sync (`docs/SYNC.md`) to cover **every**
> user-authored file — exercises and routines included — and turns the server's raw store
> into a versioned git repo so any past state is recoverable.
>
> **Why this exists:** on 2026-08-28 a `:baseline-profile` generation run uninstalled the
> app and wiped `filesDir/gymdata/`. Sessions/readiness/scale/ECG were recoverable from the
> sync server; **exercises and routines were not** (they were never synced) and had to be
> reconstructed from the per-exercise data embedded in session YAML — lossy (no image
> links, no notes, no `day`/`enabled`/`isWarmup`). This closes that gap.

Read `docs/SYNC.md` first — this document reuses its ledger, worker, request shape, Tailscale
setup, and reliability properties wholesale. Only the deltas are spelled out here.

---

## 1. Goal

At the end of every session (and on a periodic net), the phone pushes to the server **only
the files that changed since the last successful push** — hash-compared locally, exactly
like `git` sends only changed blobs. The server writes the bytes and makes a git commit.
Result:

- **Incremental**: a typical end-of-session push is 1–4 small text files, not the whole store.
- **Versioned**: every push is a commit → `git log` is the backup history, any prior state
  is `git checkout`-able, an accidental delete/edit is recoverable.
- **Complete**: exercises (incl. image/video `link` and free-text notes), routines (sets,
  rep ranges, superset links, warmup flags, `day`, `enabled`), sessions, readiness, scale
  weigh-ins, raw ECG. Everything a human authored.
- **Restore is one call**: `GET /v1/manifest` → diff against the local ledger → pull the
  missing/differing files.

### What is NOT backed up, and why

| Not synced | Reason |
|---|---|
| `history/_stats/*.yaml` | Derived cache. Rebuilt from `history/**/*.md` in <1 s on first read (schema-mismatch ⇒ lazy rebuild, see CONVENTIONS.md). |
| `history/_gitgraph.yaml` | Derived cache. Recomputed from session files when the window slides or the cache is dropped. |
| `history/_idx/*.idx` | Derived index. Rebuilt on first run (guarded by `_idx/.migrated`). |
| `cache/images/`, `image_cache/` | Re-downloadable from the exercise `link` URLs (which *are* backed up). |
| `SharedPreferences` (`sync_config`, `user_profile`, paired Polar/scale) | Not files. Optional separate `settings.md` record — see §6, deferred. |

Backing up derived data would only add bytes and a chance of the cache disagreeing with its
source of truth. The rule: **sync what a human typed, never what the app computed.**

---

## 2. What changes vs `docs/SYNC.md`

`docs/SYNC.md` already ships four independent pipelines (sessions, readiness, scale, ECG),
each with its own ledger file, `…LedgerRepository`, `…SyncApi`, `…SyncWorker`, all draining
through WorkManager with exponential backoff. This adds a **fifth pipeline** for repo files
(exercises + routines) and a **git commit hook + restore endpoints** on the server.

```
NEW  data/sync/RepoLedgerRepository.kt     _sync/repo_state.yml
NEW  data/sync/RepoSyncApi.kt              POST /v1/repo   (multipart, same shape as /v1/sessions)
NEW  data/sync/RepoSyncWorker.kt           @HiltWorker, expedited + 4h periodic
NEW  data/sync/RestoreApi.kt              GET /v1/manifest, GET /v1/file   (restore path)
     ExerciseRepository.save()/delete()   → repoLedger.requeueIfChanged() / .markDeleted()
     RoutineRepository.save()/delete()    → same
     OptionsScreen                        → "Ripristina dal server" action + repo count in status line
```

Everything else — the envelope, bearer auth, `contentHash` verification, `stored`/`duplicate`
idempotency, the "local ledger is authoritative, never ask the server what it has" principle
(§1.2 of SYNC.md) — is unchanged.

---

## 3. Phone side

### 3.1 Repo ledger — `filesDir/gymdata/_sync/repo_state.yml`

Same structure as the session ledger, keyed by **relative path** (not an 8-hex id, because
exercises/routines are identified by `{slug}-{id}.md` and the slug can change on rename):

```yaml
files:
  "exercises/bench-press-ex-a1b2c3d4.md":
    status: SENT                 # PENDING | SENT | FAILED | DELETED_PENDING | DELETED_SENT
    op: upsert                   # upsert | delete
    attempts: 1
    lastAttemptAt: 2026-09-04T20:11:03
    lastError: ""
    contentHash: "sha256:9f8e7d6c…"
    bytesSent: 412
    durationMs: 120
    serverStatus: stored
  "routines/pull-rt-71284f58.md":
    status: PENDING
    op: upsert
    attempts: 0
    lastAttemptAt: ""
    lastError: ""
    contentHash: "sha256:1a2b3c…"
  "exercises/old-typo-ex-deadbeef.md":
    status: DELETED_PENDING
    op: delete
    attempts: 0
    contentHash: "sha256:…"      # hash of the last-known content, for the tombstone
```

Owned by `RepoLedgerRepository` — in-memory cache + mutex + IO dispatcher, snakeyaml-engine
for read/write, mirrors `SyncLedgerRepository` exactly.

Key methods:

- `requeueIfChanged(relPath, bytes)` — compute sha256; if it differs from the stored hash
  (or there's no entry), set `status=PENDING, op=upsert, contentHash=<new>`. No-op if
  identical (this is what makes the push incremental).
- `markDeleted(relPath, lastKnownHash)` — set `status=DELETED_PENDING, op=delete`. Keep the
  entry (don't drop it) until the delete is confirmed `DELETED_SENT`.
- `markSent(relPath)` / `markFailed(relPath, error)` — as the session ledger.

### 3.2 Rename handling

`ExerciseRepository.save()` on a name change writes a **new** file (`{new-slug}-{id}.md`)
and deletes the old (`{old-slug}-{id}.md`) — filenames embed the slug. The hook must:

1. `repoLedger.requeueIfChanged("exercises/{new-slug}-{id}.md", newBytes)` — upsert the new path.
2. `repoLedger.markDeleted("exercises/{old-slug}-{id}.md", oldHash)` — tombstone the old path.

The server keeps both operations in one commit; the old path lands in `deleted/` (§4.3), the
new one is live. Same for routines. There is **no** "move" op — an upsert + a delete is
simpler and the server's git history still shows the rename cleanly (`git log --follow` not
required; the content is identical so `git` may even detect the rename itself).

### 3.3 Enqueue hooks

| Trigger | Hook |
|---|---|
| Exercise created/edited | `ExerciseRepository.save()` — after the file write, `repoLedger.requeueIfChanged(relPath, bytes)`. |
| Exercise deleted | `ExerciseRepository.delete()` — `repoLedger.markDeleted(relPath, lastHash)`. |
| Routine created/edited | `RoutineRepository.save()` — same as exercise save. |
| Routine deleted | `RoutineRepository.delete()` — `repoLedger.markDeleted(...)`. |
| `rt-fixeddaily` auto-seed on first launch | Its `save()` path already runs → picked up automatically. |
| End of session | `ActiveRoutineViewModel.registerRoutine()` already triggers an expedited `SyncWorker`; add an expedited `RepoSyncWorker` alongside it, so a routine edited mid-session and its session land together. |
| 4-hourly periodic net | `MyGymApp.onCreate()` — add `RepoSyncWorker` to the existing `ensurePeriodic()` batch. |
| "Ripristina / Rinvia tutto" (Options) | `OptionsViewModel.resyncAll()` — also walk `exercises/` and `routines/`, `requeueIfChanged()` every file regardless of prior status. |

The enqueue is **never** inline/blocking — same discipline as `completionSaved` and the
`onCleared()` save (see CONVENTIONS.md).

### 3.4 `RepoSyncWorker` logic (per pending entry)

Identical to `SyncWorker` (SYNC.md §1.3) except:

- `op: upsert` → read the file bytes, sha256-check against the ledger hash, `POST /v1/repo`
  with `op: "upsert"`.
- `op: delete` → **no file to read** (it's gone from disk). `POST /v1/repo` with
  `op: "delete"`, `relPath`, and the `contentHash` of the last-known content (lets the
  server confirm it's tombstoning the version it actually had).
- On 2xx: `markSent` (upsert) or set `DELETED_SENT` (delete). A `DELETED_SENT` entry may be
  pruned from the ledger after, say, 30 days — or kept forever, it's a few bytes.

### 3.5 Request shape — `POST /v1/repo`

```
POST /v1/repo
Content-Type: multipart/form-data
Authorization: Bearer <shared-secret>

  part "envelope" (application/json):
    {
      "relPath":    "exercises/bench-press-ex-a1b2c3d4.md",
      "op":         "upsert",                 // "upsert" | "delete"
      "contentHash":"sha256:9f8e7d6c…",       // upsert: of the bytes below.
                                              // delete: of the last-known content.
      "appVersion": "1.0-phase84",
      "clientSentAt":"2026-09-04T20:11:03Z"
    }
  part "file" (text/markdown):               // OMITTED entirely when op == "delete"
    <raw bytes of the .md file, unmodified>
```

Response (mirrors `/v1/sessions`):

```
200 OK  { "relPath": "...", "status": "stored" }      // upsert, first time / changed
200 OK  { "relPath": "...", "status": "duplicate" }   // upsert, server already had this hash
200 OK  { "relPath": "...", "status": "deleted" }     // delete, moved to deleted/
200 OK  { "relPath": "...", "status": "already_absent" } // delete of a path the server doesn't have
400/422 { "error": "..." }
401
```

All of `stored` / `duplicate` / `deleted` / `already_absent` are **success** to the worker
(retries stay safe).

### 3.6 Restore — `GET /v1/manifest` + `GET /v1/file`

New `RestoreApi.kt`. Used by a **"Ripristina dal server"** action in Options (and could run
automatically once, on a fresh install where `_sync/repo_state.yml` is absent but the server
is configured):

1. `GET /v1/manifest` → `{ "files": { "<relPath>": "sha256:…", … } }` covering **every**
   record type on the server (sessions, readiness, scale, ecg, exercises, routines).
2. For each `relPath` the phone is missing, or whose local hash differs:
   `GET /v1/file?relPath=<relPath>` → raw bytes → write to disk at that path.
3. After writing, rebuild the ledger entry (`status=SENT, contentHash=<server's>`), so the
   next push doesn't re-send what was just restored.
4. Restart / let the app rebuild `_idx`, `_stats`, `_gitgraph.yaml` lazily.

This is the same manifest-diff a `git fetch` does. It is **pull-only** and never deletes a
local file the server lacks (a local-only draft the phone hasn't pushed yet must survive a
restore) — reconciliation of local-only files is a normal push, not the restore's job.

### 3.7 Options screen additions

Extend the existing sync section:

- Status line count now also sums the repo ledger's `PENDING` + `DELETED_PENDING`:
  "N in attesa (sessioni, misurazioni, pesate, **schede/esercizi**)".
- **"Ripristina dal server"** button → runs §3.6. Confirmation dialog ("Scarica dal server
  ogni file mancante o diverso. Non cancella nulla in locale."). Show a result summary:
  "Ripristinati 12 esercizi, 3 routine, 0 sessioni".
- The existing **"Invia tutti i dati in coda"** now also backfills `exercises/` + `routines/`.

---

## 4. Server side (summary — full brief for the server repo is separate)

Give the server repo's Claude Code session the standalone brief
(`docs/backup-server-brief.md` — copy it out of this repo). In short:

### 4.1 `data/raw/` becomes a git repo

- `git init` on first startup if `.git` is absent. Identity `MyGymApp Sync <sync@localhost>`.
- After **every** successful write (any endpoint), under a single process-wide lock:
  `git add -A && git commit -m "sync <ISO8601> — sessions:N readiness:N scale:N ecg:N repo:N"`.
- Nothing staged ⇒ skip the commit (idempotent re-push of identical bytes).
- No `gc --aggressive`; plain commits. Text files, slow growth — fine for years.
- Optional `git push` to `BACKUP_GIT_REMOTE` (env var) for an off-box copy; skipped if unset.

### 4.2 New endpoint `POST /v1/repo`

Same skeleton as `POST /v1/sessions` (auth, envelope parse, hash verify, tolerant-parse for
the SQL view, decouple raw write from parse). `op: upsert` writes `data/raw/<relPath>`;
`op: delete` moves the file to `data/raw/deleted/<relPath>.<unix-ts>` (never `rm`).

### 4.3 Soft delete

Deletes are `git mv` into `deleted/`, so:
- git history has the pre-delete content anyway (`git show HEAD~n:<path>`),
- **and** the working tree keeps a copy under `deleted/` for a no-git recovery,
- an accidental "delete exercise" tap on the phone is undoable from either.

### 4.4 Read endpoints

- `GET /v1/manifest` → `{relPath: contentHash}` for every live file (not `deleted/`).
- `GET /v1/file?relPath=…` → raw bytes of one file.
- Same bearer token.

### 4.5 SQLite view (optional, non-blocking)

Add `exercises` and `routines` tables paralleling `sessions` (name, type, bodypart, link,
rep ranges / the routine's exercise list). Tolerant parse; a parse failure still returns
`200` and still gets committed by §4.1. Never the source of truth — the raw files + git are.

---

## 5. Reliability — inherited, plus two new cases

Everything in SYNC.md Part 3 still holds (phone crash mid-send, server down for days, retry
dup, edit-after-SENT, schema drift, partial multipart). Two additions:

- **Delete + immediate re-create of the same slug** (rename typo, then rename back): two
  ledger entries, `delete` then `upsert`, delivered in order; the server's `git mv` to
  `deleted/` then a fresh write. History shows both. No data lost.
- **Restore onto a phone that has local-only drafts**: §3.6 is pull-only and hash-diffed —
  it never overwrites a local file whose content already matches, and never deletes. A
  draft the phone made offline survives the restore and syncs up on the next push.

---

## 6. Deferred: config / `SharedPreferences` backup

Out of scope for v1. If wanted later: a single `settings.md` (YAML frontmatter) holding the
non-secret bits of `user_profile` (age, sex, height, HR zones) synced as one more repo file.
**Secrets stay out** — the sync bearer token and server URL must not be backed up to the
server they authenticate against. Paired-device MACs (Polar, scale) are cheap to re-pair and
also excluded.

---

## 7. Implementation checklist (phone side)

- [x] `RepoLedgerRepository.kt` + `_sync/repo_state.yml` (mirrors `SyncLedgerRepository`;
      keyed by relPath; `requeueIfChanged` / `markDeleted` / `markSent` / `markFailed` /
      `markRestored`; `DELETED_PENDING`/`DELETED_SENT` added to `SyncStatus`)
- [x] `RepoSyncApi.kt` — multipart `POST /v1/repo`, `postUpsert` (file part) / `postDelete`
      (envelope only)
- [x] `RepoSyncWorker.kt` (`@HiltWorker`) + `RepoSyncWorker.Scheduler` (expedited + 4h periodic)
- [x] `RestoreApi.kt` — `GET /v1/manifest`, `GET /v1/file?relPath=…`
- [x] Hooks: `ExerciseRepository.save/delete`, `RoutineRepository.save/delete` (rename path
      does new-upsert + old-tombstone), `ActiveRoutineViewModel.registerRoutine()` expedited
      trigger, `MyGymApp.onCreate()` periodic batch
- [x] `OptionsViewModel` / `OptionsScreen` — "Schede/esercizi" count in the pending list,
      "Ripristina dal server" (confirm dialog + result summary via `restoreFromServer()`),
      `resyncAll()` walks `exercises/` + `routines/`
- [x] `STORAGE.md` — `_sync/repo_state.yml` documented; root-layout tree updated
- [x] `SYNC.md` — "Fifth record type: repo files" pointer added
- [x] `CLAUDE.md` banner softened (still mandates the tar before any install/test op)
- [x] `RepoLedgerRepositoryTest` — round-trip + state-machine coverage (10 cases)
- [x] `FileManager` given a test-only `constructor(root: File)` seam (this project's unit
      suite has no Robolectric/Context)
- [ ] End-to-end test against the live server: create/edit/delete an exercise and a routine,
      confirm one commit per push on the server, then wipe `exercises/`+`routines/` locally
      and restore via `GET /v1/manifest` — **blocked on the server side being built**
