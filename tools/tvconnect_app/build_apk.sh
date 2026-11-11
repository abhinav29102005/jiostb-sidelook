#!/bin/bash
set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/mnt/storage/Android}"
BT="${ANDROID_BUILD_TOOLS:-$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1)}"
JAR="${ANDROID_PLATFORM_JAR:-$(find "$ANDROID_HOME/platforms" -name "android.jar" 2>/dev/null | sort -V | tail -n 1)}"
JAVAC="${JAVAC_BIN:-$(command -v javac 2>/dev/null || echo /opt/android-studio/jbr/bin/javac)}"
KEYTOOL="${KEYTOOL_BIN:-$(command -v keytool 2>/dev/null || echo /opt/android-studio/jbr/bin/keytool)}"

OUT="/tmp/tvconnect_apk_build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir "$APP_DIR/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
  -I "$JAR" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 30 \
  --target-sdk-version 30 \
  -o "$OUT/unaligned.apk" \
  "$OUT/res.zip"

"$JAVAC" -source 11 -target 11 -classpath "$JAR" -d "$OUT/classes" \
  $(find "$APP_DIR/src" "$OUT/gen" -name "*.java")

"$BT/d8" --lib "$JAR" --min-api 30 --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

cp "$OUT/unaligned.apk" "$OUT/with_dex.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/with_dex.apk" classes*.dex)

"$BT/zipalign" -f 4 "$OUT/with_dex.apk" "$OUT/aligned.apk"

KEYSTORE="$HOME/.android/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$HOME/.android"
  "$KEYTOOL" -genkey -v -keystore "$KEYSTORE" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$OUT/tvconnect.apk" \
  "$OUT/aligned.apk"

echo "$OUT/tvconnect.apk"
