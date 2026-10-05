#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner. Installs the APK and runs the flow scripts.
# Usage: run-emulator-flows.sh <apk> <debug|release>
# Evidence lands in flow-artifacts/<variant>/. The debug build talks to the scripted mock LLM;
# the release build uses $RELEASE_AGENT_PROVIDER (deepseek when the secret is set).
set -uo pipefail
APK="$1"
VARIANT="${2:-debug}"
export FLOW_OUT="flow-artifacts/${VARIANT}"
mkdir -p "$FLOW_OUT"
if [ "$VARIANT" = "release" ]; then
  export APP_PACKAGE=com.nuvio.app
  export AGENT_PROVIDER="${RELEASE_AGENT_PROVIDER:-mock}"
else
  export APP_PACKAGE=com.nuviodebug.com
  export AGENT_PROVIDER=mock
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "::warning::no ${VARIANT} APK found"
  exit 0
fi
echo "Installing $APK (${VARIANT}, package ${APP_PACKAGE}, agent provider ${AGENT_PROVIDER})"
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.build.version.release
adb logcat -c || true

python3 scripts/ci/baseline_flows.py --addon-host 10.0.2.2:7000 --title "Baseline app flows (${VARIANT})"
python3 scripts/ci/agent_flows.py --addon-host 10.0.2.2:7000 --title "Agent tasks (${VARIANT})" || true

adb logcat -d -b crash > "$FLOW_OUT/crash-buffer.txt" || true
adb logcat -d > "$FLOW_OUT/logcat-final.txt" || true
if grep -q "FATAL EXCEPTION" "$FLOW_OUT/logcat-final.txt"; then
  echo "::warning::FATAL EXCEPTION found in ${VARIANT} logcat"
  grep -A 30 "FATAL EXCEPTION" "$FLOW_OUT/logcat-final.txt" | head -80
fi
# Leave the device free for the next variant.
adb shell am force-stop "$APP_PACKAGE" || true
adb shell input keyevent 3 || true
exit 0
