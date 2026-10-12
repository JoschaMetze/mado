#!/bin/sh
# Build the debug APK, install it on the frame over USB, and launch it.
# Machine-specific setup (JAVA_HOME, ANDROID_SERIAL, ...) goes in env.local.sh, which is not
# checked in. Pass a hub address to point the frame at it: ./run.sh ws://HOST:7878/frame
set -e
cd "$(dirname "$0")"
[ -f env.local.sh ] && . ./env.local.sh
./gradlew installDebug
adb shell am start -n jp.co.xenocode.mado/.MainActivity ${1:+--es hub "$1"}
