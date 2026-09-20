#!/usr/bin/env bash
# 1-click build + install for Shiina Mobile (Huawei JNY-LX1 via USB adb).
# Usage: ./build.sh  (builds debug APK and installs it on the USB device)
set -e
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$HOME/Android/Sdk/platform-tools:$PATH

cd "$(dirname "$0")"

if [ -x ./gradlew ]; then
  GRADLE_CMD="./gradlew"
else
  GRADLE_BIN=$(find "$HOME/.gradle/wrapper/dists" -path "*bin/gradle" -type f 2>/dev/null | head -n 1)
  if [ -z "$GRADLE_BIN" ]; then
    echo "ERROR: no ./gradlew and no cached Gradle dist found in ~/.gradle/wrapper/dists" >&2
    exit 1
  fi
  GRADLE_CMD="$GRADLE_BIN"
fi

echo "Using gradle: $GRADLE_CMD"
$GRADLE_CMD assembleDebug --parallel

APK=$(ls -t app/build/outputs/apk/debug/*.apk 2>/dev/null | head -n 1)
if [ -z "$APK" ]; then
  echo "ERROR: no debug APK found under app/build/outputs/apk/debug/" >&2
  exit 1
fi
echo "APK: $APK"
adb devices -l
adb install -r "$APK"
echo "DONE: built and installed $APK"
