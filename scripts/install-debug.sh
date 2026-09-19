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

file_count="$(tar -tf "$backup_file" | wc -l)"
if [ "$file_count" -lt 1 ]; then
  echo "ERROR: backup tar is empty ($backup_file) — refusing to proceed." >&2
  exit 1
fi
echo "    backup OK: $backup_file ($file_count entries, $(du -h "$backup_file" | cut -f1))"

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

echo "==> Building debug APK"
ANDROID_HOME="$ANDROID_HOME" ./gradlew assembleDebug

echo "==> Installing on device (adb install -r, data preserved)"
adb install -r "$APK_PATH"

echo "==> Done. Backup kept at: $backup_file"
