#!/usr/bin/env bash
# Installs the fixture chat app, then runs the engine's instrumentation tests.
set -euo pipefail
adb wait-for-device
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb install -r fixtures/chat/build/outputs/apk/debug/chat-debug.apk
adb logcat -c || true
status=0
./gradlew :app:connectedDebugAndroidTest -Pgenesis.requireAndroid=true || status=$?
adb logcat -d -s GenesisE2E:I TestRunner:I AndroidRuntime:E > e2e-logcat.txt || true
exit $status
