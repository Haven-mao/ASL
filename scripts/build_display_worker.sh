#!/usr/bin/env bash
set -euo pipefail
ASL_ROOT=$(cd "$(dirname "$0")/.." && pwd)
ASL_SDK=${ASL_ANDROID_SDK:?Android SDK path required}
ASL_BUILD="$ASL_ROOT/build/display-worker"
mkdir -p "$ASL_BUILD/classes" "$ASL_BUILD/dex"
javac -source 8 -target 8 -encoding UTF-8 -cp "$ASL_SDK/platforms/android-36/android.jar" -d "$ASL_BUILD/classes" "$ASL_ROOT/shell/src/dev/asl/shell/DisplayWorker.java"
jar cf "$ASL_BUILD/classes.jar" -C "$ASL_BUILD/classes" .
"$ASL_SDK/build-tools/36.0.0/d8" --min-api 34 --lib "$ASL_SDK/platforms/android-36/android.jar" --output "$ASL_BUILD/dex" "$ASL_BUILD/classes.jar"
jar cf "$ASL_ROOT/../ASL-display-worker.jar" -C "$ASL_BUILD/dex" classes.dex
