#!/usr/bin/env bash
# よる時計 (Yoru Clock) - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + Kotlinコンパイル + d8 でAPKを作る)
# 必要: JDK (~/workspace/tools/jdk-21, jvm-target 17 でコンパイル)、Kotlin コンパイラ 1.9.23
#       (解決方法は tools/kotlin-env.sh を参照)、Android SDK (platforms;android-34, build-tools;34.0.0)
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH" # d8 等が java を PATH から探すため

# Kotlin コンパイラ解決 (KOTLIN_STDLIB_JAR と kotlin_compile を提供する)
. "$(dirname "$0")/tools/kotlin-env.sh"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
[ -f "$AJAR" ] || { echo "android.jar が見つかりません: $AJAR" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "aapt2 が見つかりません: $BT" >&2; exit 1; }

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVAC="${JAVA_BIN}javac"; KEYTOOL="${JAVA_BIN}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

BUILD=build/manual
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

# ===== 1. ビルド手順 =====
# ----- 1.1 aapt2 compile -----
echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/compiled.zip"

# ----- 1.2 aapt2 link -----
echo "[2/6] aapt2 link"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーに注入して使う
# (ソース側の manifest は AGP の namespace 方式に合わせて package 属性なしのまま)
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.yoru.clock"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "$BUILD/compiled.zip" \
  --java "$BUILD/gen" --min-sdk-version 26 --target-sdk-version 34

# ----- 1.3 Kotlin compile -----
echo "[3/6] Kotlin compile (R.java との mixed compilation)"
SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find app/src/main/java -name '*.kt' | sort)
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$BUILD/gen" -name '*.java' | sort)
kotlin_compile "$BUILD/classes" "$AJAR" "${SOURCES[@]}"
# K2JVMCompiler は Java ソースをシンボル解決に使うだけでバイトコードを出さないため、
# aapt2 生成の R.java は javac でもコンパイルして classes に含める (旧 Java ビルドと同内容にする)
GEN_JAVA=()
while IFS= read -r f; do GEN_JAVA+=("$f"); done < <(find "$BUILD/gen" -name '*.java' | sort)
if [ "${#GEN_JAVA[@]}" -gt 0 ]; then
  "$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" "${GEN_JAVA[@]}"
fi

# ----- 1.4 d8 -----
echo "[4/6] d8 (kotlin-stdlib を含める)"
"$BT/d8" --min-api 26 --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class') "$KOTLIN_STDLIB_JAR"

# ----- 1.5 package + zipalign -----
echo "[5/6] package + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes.dex )
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

# ----- 1.6 sign -----
echo "[6/6] sign (debug keystore)"
KS="debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=YoruClock,C=JP" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/yoru-clock.apk" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$BUILD/yoru-clock.apk" | head -3
mkdir -p dist
cp "$BUILD/yoru-clock.apk" dist/yoru-clock.apk
echo "OK: dist/yoru-clock.apk"
