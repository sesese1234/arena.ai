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
timeout 600 adb install -r -g "$APK" || { echo "install failed"; exit 1; }
timeout 120 adb shell settings put global window_animation_scale 0
timeout 120 adb shell settings put global transition_animation_scale 0
timeout 120 adb shell settings put global animator_duration_scale 0
timeout 120 adb shell settings put secure show_ime_with_hard_keyboard 0
# Keep ANR dialogs visible: with hide_error_dialogs the system kills an app that ANRs during a slow
# cold start instead of letting the flow scripts answer "Wait".
timeout 120 adb shell settings put global hide_error_dialogs 0 || true
# Skip the "Viewing full screen / Got it" hint the player's immersive mode would show.
timeout 120 adb shell settings put secure immersive_mode_confirmations confirmed || true
timeout 120 adb shell getprop ro.build.version.release
timeout 120 adb logcat -c || true
# Warm-up launch: the first cold start of the (unminified) debug build can block the main thread
# past the 5 s input deadline on the emulator; let it finish before the flows start timing taps.
# No -W: waiting for the launch to report "drawn" blocked forever when the debug build ANRed.
timeout 60 adb shell am start -n "$APP_PACKAGE/com.nuvio.app.MainActivity" || true
sleep 45
timeout 120 adb shell am force-stop "$APP_PACKAGE" || true

# Time boxes keep one slow variant (the unminified debug build ANRs on the software-rendered
# emulator) from eating the job timeout before the release build is tested.
if [ "$VARIANT" = "release" ]; then BASE_LIMIT=35m; AGENT_LIMIT=50m; else BASE_LIMIT=25m; AGENT_LIMIT=20m; fi
timeout "$BASE_LIMIT" python3 scripts/ci/baseline_flows.py --addon-host 10.0.2.2:7000 --title "Baseline app flows (${VARIANT})" \
  || echo "::warning::baseline flows (${VARIANT}) stopped: exit $? (124 = time box)"
timeout "$AGENT_LIMIT" python3 scripts/ci/agent_flows.py --addon-host 10.0.2.2:7000 --title "Agent tasks (${VARIANT})" \
  || echo "::warning::agent tasks (${VARIANT}) stopped: exit $? (124 = time box)"

timeout 120 adb logcat -d -b crash > "$FLOW_OUT/crash-buffer.txt" || true
timeout 120 adb logcat -d > "$FLOW_OUT/logcat-final.txt" || true
if grep -q "FATAL EXCEPTION" "$FLOW_OUT/logcat-final.txt"; then
  echo "::warning::FATAL EXCEPTION found in ${VARIANT} logcat"
  grep -A 30 "FATAL EXCEPTION" "$FLOW_OUT/logcat-final.txt" | head -80
fi
# Leave the device free for the next variant.
timeout 120 adb shell am force-stop "$APP_PACKAGE" || true
timeout 120 adb shell input keyevent 3 || true
exit 0
