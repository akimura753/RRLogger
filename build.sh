#!/bin/bash
# Gradle を使わない最小ビルド: javac → D8 → aapt2 → 整列 → 署名
set -euo pipefail
unset JAVA_TOOL_OPTIONS
HERE="$(cd "$(dirname "$0")" && pwd)"
T="$HERE/../tools"
ANDROID="$T/android-34.jar"
OUT="$HERE/out"
rm -rf "$OUT/classes" "$OUT/dex" "$OUT/res" "$OUT/sign"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res" "$OUT/sign"

echo "== javac"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -Xlint:deprecation \
  -bootclasspath "$ANDROID" -d "$OUT/classes" $(find "$HERE/src" -name '*.java')

echo "== d8"
java -cp "$T/jadx-all.jar" com.android.tools.r8.D8 --release --min-api 29 \
  --lib "$ANDROID" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== aapt2"
"$T/aapt2" compile --dir "$HERE/res" -o "$OUT/res/res.zip"
"$T/aapt2" link -I "$ANDROID" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 34 --version-code 9 --version-name 1.8 \
  -o "$OUT/base.apk" "$OUT/res/res.zip"

echo "== align"
python3 "$HERE/buildtools/align.py" "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/aligned.apk"

echo "== sign"
KS="$HERE/keystore/rrlogger.p12"
if [ ! -f "$KS" ]; then
  mkdir -p "$HERE/keystore"
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass rrlogger -keypass rrlogger \
    -alias rrlogger -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=RR Logger, O=Kimura Lab, C=JP" 2>/dev/null
fi
javac -cp "$T/jadx-all.jar" -d "$OUT/sign" "$HERE/buildtools/Sign.java"
java -cp "$T/jadx-all.jar:$OUT/sign" Sign "$KS" rrlogger rrlogger "$OUT/aligned.apk" "$OUT/RRLogger-1.8.apk" 29
ls -la "$OUT/RRLogger-1.8.apk"
