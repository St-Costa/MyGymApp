#!/usr/bin/env bash
# Build the debug variant and install it on the connected device, backing up
# the on-device gymdata first. See the CLAUDE.md banner for why the backup
# step is non-negotiable: all user data lives ONLY on the phone.
#
# Usage: scripts/install-debug.sh
set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID="com.mygymapp"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BACKUP_DIR="backups/gymdata"
PREFS_BACKUP_DIR="backups/prefs"
KEEP_BACKUPS=5
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "==> Checking device connection"
if ! adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
  echo "ERROR: no device in 'device' state. Is it connected and authorized (adb devices)?" >&2
  exit 1
fi
adb wait-for-device

# JDK 21 is required (the machine-default JDK 25 breaks Gradle). Honor an explicit
# JAVA_HOME, otherwise pick up a local JDK 21 install when present.
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-21-openjdk "$HOME/.jdks/jdk-21"; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

mkdir -p "$BACKUP_DIR"
timestamp="$(date +%Y%m%d-%H%M%S)"
# Stamp the installed app version into the backup name (CONVENTIONS.md#versioning), so a
# tar says which build produced it. Falls back to "v-unknown" if gradle.properties
# ever stops carrying the version (never block the backup on this).
app_version="$(grep -E '^appVersionName=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')"
app_version="${app_version:-unknown}"
backup_file="$BACKUP_DIR/gymdata-backup-${timestamp}-v${app_version}.tar"

echo "==> Backing up on-device gymdata"
if ! adb shell run-as "$APP_ID" tar -C /data/data/"$APP_ID"/files -cf - gymdata > "$backup_file" 2>/tmp/install-debug-backup-err.log; then
  echo "ERROR: backup failed (installed build may not be debuggable). Details:" >&2
  cat /tmp/install-debug-backup-err.log >&2
  echo "Install the debug APK first with 'adb install -r $APK_PATH' (keeps data), then re-run this script." >&2
  rm -f "$backup_file"
  exit 1
fi

# Integrity first: tar -tf must exit 0 (catches truncation), then the archive must
# hold at least one real file — the old `< 1 entries` check always passed because the
# tar contains the top-level `gymdata/` dir entry even when there is no data at all.
if ! tar -tf "$backup_file" > /tmp/install-debug-backup-list.txt 2>/tmp/install-debug-backup-err.log; then
  echo "ERROR: backup tar failed its integrity check (truncated?) — refusing to proceed. Details:" >&2
  cat /tmp/install-debug-backup-err.log >&2
  rm -f "$backup_file"
  exit 1
fi
file_count="$(grep -c -v '/$' /tmp/install-debug-backup-list.txt || true)"
if [ "${file_count:-0}" -lt 1 ]; then
  echo "ERROR: backup tar holds no files, only directories ($backup_file) — refusing to proceed." >&2
  rm -f "$backup_file"
  exit 1
fi
sha256sum "$backup_file" > "$backup_file.sha256"
echo "    backup OK: $backup_file ($file_count files, $(du -h "$backup_file" | cut -f1))"
# Suspicious-drop tripwire (warn, don't abort): a legitimate prune never halves the
# file count between consecutive backups — a >50% drop usually means the backup ran
# against the wrong profile/empty dir. 10s to Ctrl-C.
prev_backup="$(ls -t "$BACKUP_DIR"/gymdata-backup-*.tar 2>/dev/null | grep -v "$backup_file" | head -n 1 || true)"
if [ -n "${prev_backup:-}" ]; then
  prev_count="$(tar -tf "$prev_backup" 2>/dev/null | grep -c -v '/$' || true)"
  if [ "${prev_count:-0}" -gt 0 ] && [ "$file_count" -lt $((prev_count / 2)) ]; then
    echo "WARNING: this backup holds $file_count files vs $prev_count in $prev_backup." >&2
    echo "WARNING: if that drop is unexpected, press Ctrl-C within 10s." >&2
    sleep 10
  fi
fi

echo "==> Pruning old backups (keeping last $KEEP_BACKUPS)"
ls -t "$BACKUP_DIR"/gymdata-backup-*.tar 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -v

# Best-effort backup of SharedPreferences (sync server URL/token, powerlifting
# schedule, paired devices). The user profile itself no longer needs this — it lives
# in gymdata/exercises/_settings.md since Phase 118 — but the sync bearer does: it is
# Keystore-encrypted and re-entering it by hand after every wipe is the annoyance this
# covers. Never aborts the install: gymdata above is the critical backup.
# Restore with: scripts/restore-prefs.sh <tar> (force-stops the app afterwards, since
# prefs are cached in memory). Caveat: after a FULL uninstall (not -r) the Keystore
# keys are gone, so sync_config_enc.xml can't be decrypted anymore — re-enter the
# server URL + token by hand in that case.
mkdir -p "$PREFS_BACKUP_DIR"
prefs_backup_file="$PREFS_BACKUP_DIR/shared-prefs-backup-${timestamp}-v${app_version}.tar"
echo "==> Backing up on-device shared_prefs (best-effort)"
if ! adb shell run-as "$APP_ID" tar -C /data/data/"$APP_ID"/shared_prefs -cf - . > "$prefs_backup_file" 2>/tmp/install-debug-prefs-err.log; then
  echo "WARNING: shared_prefs backup failed — continuing (gymdata backup is the critical one). Details:" >&2
  cat /tmp/install-debug-prefs-err.log >&2
  rm -f "$prefs_backup_file"
else
  prefs_count="$(tar -tf "$prefs_backup_file" | wc -l)"
  if [ "$prefs_count" -lt 1 ]; then
    echo "WARNING: shared_prefs tar is empty — dropping it and continuing." >&2
    rm -f "$prefs_backup_file"
  else
    echo "    prefs backup OK: $prefs_backup_file ($prefs_count entries)"
  fi
fi

echo "==> Pruning old prefs backups (keeping last $KEEP_BACKUPS)"
ls -t "$PREFS_BACKUP_DIR"/shared-prefs-backup-*.tar 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -v

echo "==> Building debug APK (JAVA_HOME=${JAVA_HOME:-unset})"
JAVA_HOME="${JAVA_HOME:-}" ANDROID_HOME="$ANDROID_HOME" ./gradlew assembleDebug

echo "==> Installing on device (adb install -r, data preserved)"
if ! install_out="$(adb install -r "$APK_PATH" 2>&1)"; then
  echo "$install_out" >&2
  if echo "$install_out" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    echo "HINT: signature mismatch — a differently-signed build is installed. A full" >&2
    echo "HINT: uninstall would WIPE gymdata; restore it afterwards with:" >&2
    echo "HINT:   adb shell run-as $APP_ID tar -C /data/data/$APP_ID/files -xf - < $backup_file" >&2
  fi
  exit 1
fi
echo "$install_out"

echo "==> Done. Backup kept at: $backup_file (+ .sha256)"
