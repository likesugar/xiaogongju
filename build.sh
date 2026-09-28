#!/bin/bash
# 小工具（com.wink.pillmate）轻量壳云构建：aapt2 + ecj + d8 + zipalign + apksigner
set -e
VC=${VC:-177}
VN=${VN:-"14.2"}

WORK=$HOME/work
mkdir -p $WORK && cd $WORK

BT=$ANDROID_HOME/build-tools/34.0.0
SDK=$ANDROID_HOME/platforms/android-34/android.jar
PROJ=$GITHUB_WORKSPACE

echo "[deps] download prebuilt libvlc/ffmpeg-kit..."
curl -sL -o deps.zip "https://github.com/likesugar/xiaogongju/releases/download/deps-v2/deps-v2.zip"
unzip -q deps.zip -d deps

rm -rf build_out && mkdir -p build_out/gen build_out/classes build_out/dex

echo "[1/6] aapt2 compile..."
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o build_out/res.zip

echo "[2/6] aapt2 link..."
"$BT/aapt2" link -o build_out/app-unsigned.apk \
    -I "$SDK" \
    --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
    -R build_out/res.zip \
    --java build_out/gen --auto-add-overlay \
    --min-sdk-version 26 --target-sdk-version 36 \
    --version-code "$VC" --version-name "$VN"

echo "[3/6] ecj compile..."
RJ=$(find build_out/gen -name R.java)
curl -sL -o ecj.jar "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar"
java -jar ecj.jar -source 1.8 -target 1.8 -encoding UTF-8 -proc:none -nowarn \
    -cp "$SDK:deps/media3/annotation.jar:deps/media3/guava.jar:deps/media3/media3-common.jar:deps/media3/media3-database.jar:deps/media3/media3-datasource.jar:deps/media3/media3-decoder.jar:deps/media3/media3-exoplayer.jar:deps/media3/media3-exoplayer-hls.jar:deps/media3/media3-extractor.jar:deps/media3/media3-ui.jar:deps/ffk_classes.jar" -d build_out/classes \
    "$RJ" \
    "$PROJ"/app/src/main/java/com/wink/pillmate/*.java

echo "[4/6] d8 dex (app + libvlc/ffk classes)..."
find build_out/classes -name "*.class" > build_out/classlist.txt
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release \
    --lib "$SDK" --min-api 26 --output build_out/dex \
    @build_out/classlist.txt deps/libvlc_out/classes.jar deps/ffk_out/classes.jar \
    deps/ffk_out/sex/smart-exception-java-0.2.1.jar deps/ffk_out/sex/common/smart-exception-common-0.2.1.jar

echo "[5/6] dex + native libs + zipalign..."
for d in build_out/dex/*.dex; do
    (cd build_out/dex && zip -q ../app-unsigned.apk "$(basename $d)")
done
mkdir -p build_out/lib/arm64-v8a
cp deps/ffk_out/jni/arm64-v8a/*.so build_out/lib/arm64-v8a/
(cd build_out && zip -q -r app-unsigned.apk lib)
"$BT/zipalign" -f 4 build_out/app-unsigned.apk build_out/app-aligned.apk

echo "[6/6] sign + verify..."
"$BT/apksigner" sign --ks "$PROJ/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --out "xiaogongju_v$VN.apk" build_out/app-aligned.apk
"$BT/apksigner" verify "xiaogongju_v$VN.apk"
cp "xiaogongju_v$VN.apk" "$GITHUB_WORKSPACE/xiaogongju_v$VN.apk"
echo "=== DONE: xiaogongju_v$VN.apk (vc$VC) ==="
