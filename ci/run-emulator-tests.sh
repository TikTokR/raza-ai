#!/usr/bin/env bash
# Runs the instrumented tests and always saves logcat so failures can be diagnosed.
adb logcat -c || true
set +e
gradle :app:connectedDebugAndroidTest --stacktrace
code=$?
mkdir -p app/build/emulator-logs
adb logcat -d -v time > app/build/emulator-logs/logcat-full.txt 2>&1
grep -E "com\.raza\.ai|WakeWordService|ActivityManager.*raza|FGS|SpeechRecognizer|AndroidRuntime|foreground" app/build/emulator-logs/logcat-full.txt > app/build/emulator-logs/logcat-raza.txt
exit $code
