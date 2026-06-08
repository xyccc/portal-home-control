#!/usr/bin/env bash
# Build the debug APK and install it on a Portal over ADB.
#
# RUN THIS ON YOUR LOCAL MACHINE (laptop) — the one with:
#   - Android SDK + a JDK 17
#   - the Portal connected over USB with ADB debugging enabled
# It will NOT work on a devserver: the Portal is plugged into your laptop, not the server.
#
# Enable ADB on the Portal: Settings > System > Developer/About, enable developer
# mode, then "ADB debugging". Approve the RSA prompt that appears on the Portal.
set -euo pipefail
cd "$(dirname "$0")"

# Pick a Gradle invocation: prefer the wrapper, fall back to a system gradle.
if [[ -x ./gradlew ]]; then
  GRADLE=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  echo "No ./gradlew found; generating wrapper with system gradle..."
  gradle wrapper >/dev/null
  GRADLE=./gradlew
else
  echo "ERROR: neither ./gradlew nor 'gradle' found. Install Gradle or open the" >&2
  echo "       project in Android Studio once to generate the wrapper." >&2
  exit 1
fi

echo "==> Checking ADB devices"
if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not on PATH. Install platform-tools." >&2
  exit 1
fi
adb devices -l
if [[ -z "$(adb devices | sed '1d' | grep -w device || true)" ]]; then
  echo "ERROR: no authorized device. Plug in the Portal, enable ADB, approve the prompt." >&2
  exit 1
fi

echo "==> Building debug APK"
"$GRADLE" :app:assembleDebug

APK=app/build/outputs/apk/debug/app-debug.apk
echo "==> Installing $APK"
adb install -r "$APK"

echo "==> Launching"
adb shell monkey -p com.yvonna.portalhome -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
  adb shell am start -n com.yvonna.portalhome/.MainActivity

cat <<'EOF'

Installed. If the icon doesn't show in the Portal launcher (Portal hides
sideloaded apps), relaunch any time with:
  adb shell am start -n com.yvonna.portalhome/.MainActivity
EOF
