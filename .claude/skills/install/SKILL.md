---
name: install
description: Build the debug APK and install it on the connected phone, backing up gymdata/ first. Use for "installa", "metti sul telefono", "build and install the latest version".
disable-model-invocation: true
---

# Install the debug build on the phone

The only sanctioned way is `scripts/install-debug.sh` — it backs up `gymdata/` + `shared_prefs/`,
verifies the tar, builds, then `adb install -r` (data kept). Never hand-roll `adb install` /
`gradlew installDebug` (the data-guard hook will ask for confirmation if you try).

1. **Find the phone**: `adb devices -l`.
   - Exactly one line in state `device` → go on.
   - None → stop and tell the user: plug in the USB cable, unlock the phone, accept the
     "Allow USB debugging" prompt. `unauthorized` = that prompt is still pending;
     `offline` = unplug/replug.
   - More than one → ask the user which one (show model + serial), then run step 2 with
     `ANDROID_SERIAL=<serial>` in front of the command.
2. **Run** `scripts/install-debug.sh` (timeout 600000 — the Gradle build is slow).
3. **Report** in one or two lines: backup file + file count, installed version
   (`appVersionName` in `gradle.properties`), and `Success` from adb.
   - Backup failed (installed build not debuggable): do **not** work around it — relay the
     script's message and ask the user.
   - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: stop. Uninstalling would wipe the data; relay the
     script's HINT lines and let the user decide.
   - A >50% file-count drop warning: tell the user explicitly, it may mean data loss on the phone.
