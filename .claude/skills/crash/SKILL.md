---
name: crash
description: Pull the app's crash reports and app.log from the phone and diagnose the most recent crash/ANR down to the source line. Use when the user says the app crashed, froze, closed by itself, or "si è chiuso / è crashata".
---

# Diagnose a crash from the phone's own logs

Background: docs/CONVENTIONS.md#crash-reports--action-trail.

1. Check the phone is connected (`adb devices -l`; same rules as the `install` skill —
   none → ask the user to plug it in; several → ask which and prefix `ANDROID_SERIAL=<serial>`).
2. Run `scripts/pull-logs.sh` (read-only on the phone). If it says the build isn't debuggable,
   ask the user to share the files from Options → Debug → LOGS & CRASHES instead.
3. Read the newest `backups/logs/<ts>/logs/crashes/*.txt` (or the one matching the time the
   user mentions) and the matching window of `logs/app.log`.
4. From the stack trace find the first frame in `com.mygymapp`, open that file and line, and
   use the action trail (screen + last actions) to reconstruct how the user got there.
5. Reply with: what happened (one sentence, plain language), the cause at
   `file:line`, and a proposed fix. Don't change code until the user agrees.
   If the root cause is pure logic, the fix comes with a JUnit test (CLAUDE.md).
