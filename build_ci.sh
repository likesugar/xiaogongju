#!/bin/bash
# 吃药小闹钟 APK 构建
set -e
cd "$(dirname "$0")"
BT="${BT:-${ANDROID_HOME}/build-tools/34.0.0}"
SDK="${SDK:-${ANDROID_HOME}/platforms/android-34/android.jar}"
PROJ="."
VC=${1:-1}
VN=${2:-"1.0"}

rm -rf build_out
mkdir -p build_out/gen build_out/classes build_out/dex

echo "[1/6] aapt2 compile..."
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o build_out/res.zip

echo "[2/6] aapt2 link..."
"$BT/aapt2" link -o build_out/app-unsigned.apk \
    -I "$SDK" \
    --manifest "$PROJ"/app/src/main/AndroidManifest.xml \
    -R build_out/res.zip \
    --java build_out/gen --auto-add-overlay \
    --min-sdk-version 26 --target-sdk-version 36 \
    --version-code "$VC" --version-name "$VN"

echo "[3/6] ecj compile..."
RJ=$(find build_out/gen -name R.java)
java -jar tools/ecj.jar -source 1.8 -target 1.8 -encoding UTF-8 -proc:none -nowarn \
    -cp "$SDK:deps/libvlc/classes.jar:deps/ffk/classes.jar:deps/ffk/sexcls" -d build_out/classes \
    "$RJ" \
    "$PROJ"/app/src/main/java/com/wink/pillmate/*.java \
    "$PROJ"/app/src/main/java/org/videolan/*.java

echo "[4/7] d8 dex (app + libvlc classes)..."
find build_out/classes -name "*.class" > build_out/classlist.txt
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release \
    --lib "$SDK" --min-api 26 --output build_out/dex \
    @build_out/classlist.txt deps/libvlc/classes.jar deps/ffk/classes.jar deps/ffk/smart-exception-java-0.2.1.jar deps/ffk/smart-exception-common-0.2.1.jar

echo "[5/7] dex + native libs + zipalign..."
for d in build_out/dex/*.dex; do
    (cd build_out/dex && zip -q ../app-unsigned.apk "$(basename $d)")
done
# libvlc arm64-v8a 原生库
mkdir -p build_out/lib/arm64-v8a
cp deps/libvlc/jni/arm64-v8a/*.so build_out/lib/arm64-v8a/
cp deps/ffk/jni/arm64-v8a/*.so build_out/lib/arm64-v8a/
cp deps/ffk/jni/arm64-v8a/*.so build_out/lib/arm64-v8a/

(cd build_out && zip -q -r app-unsigned.apk lib)
"$BT/zipalign" -f 4 build_out/app-unsigned.apk build_out/app-aligned.apk

echo "[6/7] sign + verify..."
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "./小工具_v$VN.apk" build_out/app-aligned.apk
"$BT/apksigner" verify "./小工具_v$VN.apk"

echo "=== DONE: ./小工具_v$VN.apk ==="
