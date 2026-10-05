#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner. Installs the APK and runs the flow scripts.
# Usage: run-emulator-flows.sh <apk> <variant>
set -uo pipefail
APK="$1"
VARIANT="${2:-debug}"
export FLOW_OUT="${FLOW_OUT:-flow-artifacts}"
mkdir -p "$FLOW_OUT"

echo "Installing $APK (${VARIANT})"
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.build.version.release

python3 scripts/ci/baseline_flows.py --addon-host 10.0.2.2:7000 --title "Baseline app flows (${VARIANT})"

if [ -f scripts/ci/agent_flows.py ]; then
  python3 scripts/ci/agent_flows.py --addon-host 10.0.2.2:7000 --title "Agent tasks (${VARIANT})" || true
fi

adb logcat -d -b crash > "$FLOW_OUT/crash-buffer.txt" || true
adb logcat -d > "$FLOW_OUT/logcat-final.txt" || true
if grep -q "FATAL EXCEPTION" "$FLOW_OUT/logcat-final.txt"; then
  echo "::warning::FATAL EXCEPTION found in logcat"
  grep -A 30 "FATAL EXCEPTION" "$FLOW_OUT/logcat-final.txt" | head -80
fi
exit 0
