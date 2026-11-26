#!/usr/bin/env bash
# Installs the fixture chat app, then runs instrumentation tests.
# TEST_FILTER selects tests, for example "class=com.genesis.app.HiddenDisplayOnDeviceTest"
# or "notClass=com.genesis.app.HiddenDisplayOnDeviceTest". LOG_NAME names the logcat dump.
set -euo pipefail
adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb install -r fixtures/chat/build/outputs/apk/debug/chat-debug.apk
# The hidden-display host uses hidden APIs by reflection, as it does inside Shizuku's shell process.
# On the emulator it runs in the test process instead, so allow hidden APIs for apps before it starts.
adb shell settings put global hidden_api_policy 1 || true
adb logcat -c || true
status=0
args=()
if [[ -n "${TEST_FILTER:-}" ]]; then
  key="${TEST_FILTER%%=*}"
  value="${TEST_FILTER#*=}"
  args+=("-Pandroid.testInstrumentationRunnerArguments.${key}=${value}")
fi
./gradlew :app:connectedDebugAndroidTest -Pgenesis.requireAndroid=true "${args[@]}" || status=$?
adb logcat -d -s GenesisE2E:I GenesisHiddenE2E:I GenesisHidden:I TestRunner:I AndroidRuntime:E > "${LOG_NAME:-e2e-logcat.txt}" || true
exit $status
