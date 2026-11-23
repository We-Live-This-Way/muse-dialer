#!/bin/bash
# Manual APK build (no Gradle daemon) for WLTW Dialer.
#
# Why this exists: Gradle builds its APK by talking to a Gradle daemon
# over a loopback TCP connection. In this VM raw TCP (including
# loopback) is intercepted by the runtime network sandbox, so the
# Gradle client can never reach its daemon ("Could not receive a
# message from the daemon"). The Gradle project files (build.gradle
# etc.) are still in this folder and are the canonical project — on a
# normal machine `gradle assembleDebug` builds the same app.
#
# This script builds the identical APK with the SDK command-line tools
# only: aapt2 -> javac -> d8 -> zipalign -> apksigner. No network needed.
#
# Signing: the app is signed with the persistent keystore in
# ../keystore/wltw-dialer.keystore (alias wltw-dialer). Its password
# lives in ../keystore/keystore.pass on this machine only — it is not
# in this script, not in the README, and never leaves the workspace.
# If the keystore is missing it is created once; it is never
# regenerated, so updates keep the same signature and install over
# the old version.
#
# Usage:  ./build-apk-manual.sh
# Output: ../WeLiveThisWay-Dialer.apk
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$(dirname "$ROOT")/WeLiveThisWay-Dialer.apk"
KEYSTORE_DIR="$(dirname "$ROOT")/keystore"
KEYSTORE="$KEYSTORE_DIR/wltw-dialer.keystore"
PASSFILE="$KEYSTORE_DIR/keystore.pass"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
export JAVA_HOME="$HOME/workspace/tools/jdk-17.0.21+9"
export PATH="$JAVA_HOME/bin:$PATH"
BUILD="$ROOT/build-manual"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "== 1. Compile resources (aapt2 compile)"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD/compiled.zip"

echo "== 2. Link APK + generate R.java (aapt2 link)"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" \
  -I "$SDK/platforms/android-34/android.jar" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -R "$BUILD/compiled.zip" --java "$BUILD/gen" --auto-add-overlay

echo "== 3. Compile Java (javac)"
find "$BUILD/gen" "$ROOT/app/src/main/java" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 -cp "$SDK/platforms/android-34/android.jar" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== 4. Dex (d8)"
(cd "$BUILD/classes" && jar --create --file ../classes.jar .)
"$BT/d8" --output "$BUILD/dex" "$BUILD/classes.jar" --lib "$SDK/platforms/android-34/android.jar"

echo "== 5. Add classes.dex to APK"
(cd "$BUILD/dex" && zip -q ../app-unsigned.apk classes.dex)

echo "== 6. Align (zipalign)"
"$BT/zipalign" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "== 7. Sign (apksigner, WLTW Dialer keystore)"
mkdir -p "$KEYSTORE_DIR"
if [ ! -f "$PASSFILE" ]; then
  openssl rand -base64 24 | tr -d '\n' > "$PASSFILE"
  chmod 600 "$PASSFILE"
  echo "   (created keystore password file — kept on this machine only)"
fi
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias wltw-dialer \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat "$PASSFILE")" -keypass "$(cat "$PASSFILE")" \
    -dname "CN=We Live This Way, O=We Live This Way LLC, C=US" >/dev/null 2>&1
  chmod 600 "$KEYSTORE"
  echo "   (generated $KEYSTORE — keep it; updates must use this same key)"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" \
  --ks-pass "pass:$(cat "$PASSFILE")" --key-pass "pass:$(cat "$PASSFILE")" \
  --out "$OUT" "$BUILD/app-aligned.apk"

echo "== 8. Verify"
"$BT/apksigner" verify "$OUT"
"$BT/aapt" dump badging "$OUT" | head -8
ls -lh "$OUT"
echo "DONE: $OUT"
