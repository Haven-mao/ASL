#!/usr/bin/env bash
set -euo pipefail
ASL_ROOT=$(cd "$(dirname "$0")/.." && pwd)
ASL_ANDROID_SDK=${ASL_ANDROID_SDK:?Set ASL_ANDROID_SDK to an Android SDK with platform 36 and build-tools 36.0.0}
ASL_BUILD="$ASL_ROOT/build/apk"
ASL_TOOLS="$ASL_ANDROID_SDK/build-tools/36.0.0"
ASL_PLATFORM="$ASL_ANDROID_SDK/platforms/android-36/android.jar"
ASL_APK=${ASL_APK:-"$ASL_ROOT/../ASL-dev.apk"}
ASL_RUNTIME_ROOT=${ASL_RUNTIME_ROOT:-"$ASL_ROOT/build/runtime-bionic"}
mkdir -p "$ASL_BUILD/classes" "$ASL_BUILD/dex"
"$ASL_TOOLS/aapt2" compile --dir "$ASL_ROOT/android/res" -o "$ASL_BUILD/resources.zip"
"$ASL_TOOLS/aapt2" link -I "$ASL_PLATFORM" --manifest "$ASL_ROOT/android/AndroidManifest.xml" -A "$ASL_ROOT/android/assets" -o "$ASL_BUILD/base.apk" "$ASL_BUILD/resources.zip"
find "$ASL_ROOT/android/src" -name '*.java' -print > "$ASL_BUILD/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -cp "$ASL_PLATFORM" -d "$ASL_BUILD/classes" @"$ASL_BUILD/sources.txt"
jar cf "$ASL_BUILD/classes.jar" -C "$ASL_BUILD/classes" .
"$ASL_TOOLS/d8" --min-api 30 --lib "$ASL_PLATFORM" --output "$ASL_BUILD/dex" "$ASL_BUILD/classes.jar"
python3 - "$ASL_RUNTIME_ROOT" "$ASL_BUILD" <<'PY'
import json,pathlib,shutil,sys,zipfile
runtime,build=map(pathlib.Path,sys.argv[1:]);shutil.copyfile(build/'base.apk',build/'unsigned.apk')
manifest=json.loads((runtime/'runtime-manifest.json').read_text())
with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    for dex in (build/'dex').glob('*.dex'):z.write(dex,dex.name)
    for item in manifest['binaries']:z.write(runtime/'arm64-v8a'/item['name'],'lib/arm64-v8a/'+item['name'])
PY
"$ASL_TOOLS/zipalign" -f -P 16 4 "$ASL_BUILD/unsigned.apk" "$ASL_BUILD/aligned.apk"
if [ ! -f "$ASL_BUILD/debug.jks" ]; then
    keytool -genkeypair -keystore "$ASL_BUILD/debug.jks" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=ASL Development, O=ASL"
fi
"$ASL_TOOLS/apksigner" sign --ks "$ASL_BUILD/debug.jks" --ks-pass pass:android --key-pass pass:android --out "$ASL_APK" "$ASL_BUILD/aligned.apk"
"$ASL_TOOLS/apksigner" verify "$ASL_APK"
printf 'Built %s\n' "$ASL_APK"
