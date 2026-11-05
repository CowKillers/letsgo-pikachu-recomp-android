#!/bin/bash
# Assemble the Let's Go Pikachu Android APK manually (no Gradle).
# javac -> d8 -> aapt2 -> zipalign -> apksigner (your own debug keystore).
# javac -> d8 -> aapt2 -> zipalign -> apksigner (backup mvc2 keystore).
set -u
cd "$(dirname "$0")"
ROOT="$PWD"
SDK=~/Android/Sdk
BT=$SDK/build-tools/35.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
KS=~/workspace/user/keystores/mvc2-debug.keystore
APKDIR=$ROOT/apk
OUT=$ROOT/build/apk
rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex $OUT/lib/arm64-v8a
find $APKDIR/java $ROOT/deps/SDL3/android-project/app/src/main/java -name "*.java" > $OUT/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $PLATFORM -d $OUT/classes \
    @$OUT/sources.txt 2>$OUT/javac.log || { echo JAVAC_FAIL; tail -5 $OUT/javac.log; exit 1; }
$BT/d8 --min-api 28 --lib $PLATFORM --output $OUT/dex \
    $(find $OUT/classes -name "*.class") 2>$OUT/d8.log || { echo D8_FAIL; tail -5 $OUT/d8.log; exit 1; }
cp $ROOT/build/apk-lib/*.so $OUT/lib/arm64-v8a/ 2>/dev/null
cp $ROOT/build/liblgimport.so $OUT/lib/arm64-v8a/ 2>/dev/null
cp $ROOT/deps/SDL3/build-android/libSDL3.so $OUT/lib/arm64-v8a/
cp $ROOT/build/game-600/liblasgame.so $OUT/lib/arm64-v8a/ 2>/dev/null
for f in $ROOT/deps/ffmpeg-android/lib/libav*.so $ROOT/deps/ffmpeg-android/lib/libsw*.so; do
    [ -f "$f" ] && [ ! -L "$f" ] && cp $f $OUT/lib/arm64-v8a/ 2>/dev/null
done
cp ~/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so $OUT/lib/arm64-v8a/ 2>/dev/null
STRIP=~/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
for f in $OUT/lib/arm64-v8a/liblasrecomp.so $OUT/lib/arm64-v8a/libavcodec.so; do
    if [ -f "$f" ]; then echo "strip-debug $f: $(stat -c%s "$f")"; $STRIP --strip-debug "$f"; echo "  -> $(stat -c%s "$f")"; fi
done
ls $OUT/lib/arm64-v8a/
$BT/aapt2 compile --dir $APKDIR/res -o $OUT/res.zip 2>>$OUT/aapt.log \
    || { echo AAPT_COMPILE_FAIL; tail -10 $OUT/aapt.log; exit 1; }
$BT/aapt2 link -o $OUT/base.apk \
    --manifest $APKDIR/AndroidManifest.xml \
    --java $OUT/gen \
    -I $PLATFORM \
    -A $APKDIR/assets \
    --min-sdk-version 28 --target-sdk-version 34 \
    $OUT/res.zip \
    2>$OUT/aapt.log || { echo AAPT_FAIL; tail -10 $OUT/aapt.log; exit 1; }
cp $OUT/base.apk $OUT/unsigned.apk
cp $OUT/dex/classes.dex $OUT/classes.dex
cd $OUT && zip -q unsigned.apk classes.dex && zip -q -0 unsigned.apk lib/arm64-v8a/*.so && cd $ROOT
$BT/zipalign -f -P 16 4 $OUT/unsigned.apk $OUT/aligned.apk 2>>$OUT/aapt.log || { echo ALIGN_FAIL; exit 1; }
$BT/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android \
    --out $OUT/LetsGoPikachu-android.apk $OUT/aligned.apk 2>>$OUT/aapt.log || { echo SIGN_FAIL; exit 1; }
$BT/apksigner verify --print-certs $OUT/LetsGoPikachu-android.apk | grep -m1 SHA-256
cp $OUT/LetsGoPikachu-android.apk $ROOT/
ls -la $ROOT/LetsGoPikachu-android.apk
echo APK_OK
