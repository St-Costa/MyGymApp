#!/usr/bin/env bash
# Regenerate the baseline profile WITHOUT losing on-device data.
#
# :app:generateBaselineProfile uninstalls the app (uninstall_after_test: true),
# which destroys /data/data/com.mygymapp/files/gymdata/ — this wrapper backs
# gymdata (plus shared_prefs, best-effort) up first, runs the generator, then
# reinstalls the debug build and restores both afterwards.
#
# Afterwards: reopen the app, verify your data is intact, re-enter the sync
# server URL + token by hand (the Keystore keys die with the uninstall, so
# sync_config_enc can't be decrypted anymore), and commit the regenerated
# baseline-prof.txt. See docs/CONVENTIONS.md#baseline-profile.
#
# Usage: scripts/generate-baseline-profile.sh   (~20-30 min on the phone)
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

mkdir -p "$BACKUP_DIR" "$PREFS_BACKUP_DIR"
timestamp="$(date +%Y%m%d-%H%M%S)"
app_version="$(grep -E '^appVersionName=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')"
app_version="${app_version:-unknown}"
backup_file="$BACKUP_DIR/gymdata-backup-${timestamp}-v${app_version}.tar"
prefs_backup_file="$PREFS_BACKUP_DIR/shared-prefs-backup-${timestamp}-v${app_version}.tar"

echo "==> Backing up on-device gymdata (aborts if this fails)"
if ! adb shell run-as "$APP_ID" tar -C /data/data/"$APP_ID"/files -cf - gymdata > "$backup_file" 2>/tmp/baseline-backup-err.log; then
  echo "ERROR: backup failed (installed build may not be debuggable). Details:" >&2
  cat /tmp/baseline-backup-err.log >&2
  rm -f "$backup_file"
  exit 1
fi
if ! tar -tf "$backup_file" > /tmp/baseline-backup-list.txt 2>/tmp/baseline-backup-err.log; then
  echo "ERROR: backup tar failed its integrity check — refusing to proceed." >&2
  rm -f "$backup_file"
  exit 1
fi
backed_up_files="$(grep -c -v '/$' /tmp/baseline-backup-list.txt || true)"
if [ "${backed_up_files:-0}" -lt 1 ]; then
  echo "ERROR: backup tar holds no files — refusing to proceed." >&2
  rm -f "$backup_file"
  exit 1
fi
sha256sum "$backup_file" > "$backup_file.sha256"
echo "    backup OK: $backup_file ($backed_up_files files)"
ls -t "$BACKUP_DIR"/gymdata-backup-*.tar 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -v

echo "==> Backing up shared_prefs (best-effort)"
if adb shell run-as "$APP_ID" tar -C /data/data/"$APP_ID"/shared_prefs -cf - . > "$prefs_backup_file" 2>/dev/null \
  && [ "$(tar -tf "$prefs_backup_file" 2>/dev/null | grep -c -v '/$' || true)" -ge 1 ]; then
  echo "    prefs backup OK: $prefs_backup_file"
  prefs_backed_up=1
else
  echo "    WARNING: shared_prefs backup failed — continuing (gymdata is the critical one)."
  rm -f "$prefs_backup_file"
  prefs_backed_up=0
fi

echo "==> Running :app:generateBaselineProfile (uninstalls the app — backup above is the safety net)"
JAVA_HOME="${JAVA_HOME:-}" ANDROID_HOME="$ANDROID_HOME" ./gradlew :app:generateBaselineProfile

echo "==> Rebuilding + reinstalling the debug build"
JAVA_HOME="${JAVA_HOME:-}" ANDROID_HOME="$ANDROID_HOME" ./gradlew assembleDebug
adb install -r "$APK_PATH"

echo "==> Restoring gymdata from $backup_file"
adb shell run-as "$APP_ID" mkdir -p /data/data/"$APP_ID"/files/gymdata
adb shell run-as "$APP_ID" tar -C /data/data/"$APP_ID"/files -xf - < "$backup_file"
restored_md="$(adb shell run-as "$APP_ID" find files/gymdata -name '*.md' 2>/dev/null | wc -l)"
backed_up_md="$(grep -c '\.md$' /tmp/baseline-backup-list.txt || true)"
echo "    restored $restored_md .md files (backup held $backed_up_md)"
if [ "$restored_md" -lt "$backed_up_md" ]; then
  echo "WARNING: fewer .md files restored than backed up — inspect before using the app." >&2
fi

if [ "$prefs_backed_up" -eq 1 ]; then
  echo "==> Restoring shared_prefs (best-effort — Keystore keys are gone, token needs re-entry)"
  ./scripts/restore-prefs.sh "$prefs_backup_file" || echo "WARNING: prefs restore failed — re-enter config by hand." >&2
fi

echo "==> Done."
echo "    1. Open the app and verify your data is intact (keep $backup_file until confirmed)."
echo "    2. Re-enter the sync server URL + token in Options (Keystore wiped by the uninstall)."
echo "    3. Review + commit the regenerated baseline-prof.txt."
