#!/usr/bin/env bash
# よる時計 (Yoru Clock) - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + kotlinc + R8/D8 でAPKを作る)
# v1.2.0 から UI は Jetpack Compose。Compose 対応のため通常の手動ビルドと異なる点:
#   * 依存ライブラリ (Compose 1.7.6 / material3 1.3.1 等) は libs/ に置く (tools/resolve.py で取得)
#   * ライブラリ AAR の res/ を aapt2 compile し、--auto-add-overlay + --extra-packages でリンクする
#   * kotlinc には Compose コンパイラプラグイン (Maven 版 2.0.21) を -Xplugin で渡す
#   * dex 化は build-tools 同梱 d8 ではなく単体 R8 8.7.18 の D8 を使う (同梱版は lifecycle の
#     匿名内部クラスで NPE クラッシュするため)。64k メソッド超のため multidex (全 classes*.dex 同梱)
#   * AAR 内の .so (androidx.graphics.path) は lib/<abi>/ に同梱し zipalign -p でページ整列する
# 必要: JDK (~/workspace/tools/jdk-21)、Kotlin コンパイラ 2.0.21 (tools/kotlin-env.sh が解決)、
#       Android SDK (platforms;android-34, build-tools;34.0.0)
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH" # d8 等が java を PATH から探すため

# Kotlin コンパイラ解決 (KOTLIN_STDLIB_JAR と KOTLINC_BIN を提供する)
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

COMPOSE_PLUGIN="tools/kotlin-compose-compiler-plugin-2.0.21.jar"
R8_JAR="tools/r8-8.7.18.jar"
[ -f "$COMPOSE_PLUGIN" ] || { echo "Composeプラグインがありません: $COMPOSE_PLUGIN" >&2; exit 1; }
[ -f "$R8_JAR" ] || { echo "R8 jar がありません: $R8_JAR" >&2; exit 1; }
[ -f libs/artifacts.txt ] || { echo "libs/ がありません。python3 tools/resolve.py を実行してください" >&2; exit 1; }

BUILD=build/manual
AARS=build/aars
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/libres" "$AARS"

# ===== 0. ライブラリ収集 =====
CP_JARS=()    # コンパイル用 classpath
DEX_JARS=()   # dex 入力
AAR_DIRS=()   # 展開済み AAR (artifacts.txt の順序どおり。glob はしない)
while IFS= read -r f; do
  case "$f" in kotlin-stdlib*) continue;; esac   # stdlib は kotlinc 配布物 (2.0.21) を使う
  path="libs/$f"
  [ -f "$path" ] || { echo "成果物がありません: $path (python3 tools/resolve.py を実行してください)" >&2; exit 1; }
  if [[ "$f" == *.aar ]]; then
    d="$AARS/${f%.aar}"
    AAR_DIRS+=("$d")
    if [ ! -d "$d" ]; then mkdir -p "$d"; unzip -qo "$path" -d "$d"; fi
    if [ -f "$d/classes.jar" ]; then
      CP_JARS+=("$d/classes.jar"); DEX_JARS+=("$d/classes.jar")
    fi
  else
    # クラスを含まない KMP ルート jar は除外 (重複クラス回避)
    if [ "$(unzip -l "$path" | grep -c '\.class' || true)" -gt 0 ]; then
      CP_JARS+=("$path"); DEX_JARS+=("$path")
    fi
  fi
done < libs/artifacts.txt
CP="$(IFS=:; echo "${CP_JARS[*]}")"
echo "libraries: ${#CP_JARS[@]} jars"

# ===== 1. aapt2 compile (アプリ + ライブラリのリソース) =====
echo "[1/6] aapt2 compile (app + library resources)"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/libres/app.zip"
RES_ZIPS=("$BUILD/libres/app.zip")
EXTRA_PKGS=()
for d in "${AAR_DIRS[@]}"; do
  if [ -d "$d/res" ] && [ -n "$(find "$d/res" -type f -print -quit)" ]; then
    out="$BUILD/libres/$(basename "$d").zip"
    "$BT/aapt2" compile --dir "$d/res" -o "$out"
    RES_ZIPS+=("$out")
  fi
  if [ -f "$d/R.txt" ]; then
    pkg="$(grep -o 'package="[^"]*"' "$d/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
    [ -n "$pkg" ] && EXTRA_PKGS+=("$pkg")
  fi
done
echo "  resource sets: ${#RES_ZIPS[@]}, R packages: ${#EXTRA_PKGS[@]}"

# ===== 2. aapt2 link =====
echo "[2/6] aapt2 link (--auto-add-overlay, --extra-packages)"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーに注入して使う
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.yoru.clock"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
EXTRA_ARG=()
if [ "${#EXTRA_PKGS[@]}" -gt 0 ]; then
  EXTRA_ARG=(--extra-packages "$(IFS=:; echo "${EXTRA_PKGS[*]}")")
fi
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "${RES_ZIPS[@]}" \
  --java "$BUILD/gen" --auto-add-overlay "${EXTRA_ARG[@]}" \
  --min-sdk-version 26 --target-sdk-version 34

# ===== 3. Kotlin compile (Compose プラグイン有効) =====
echo "[3/6] kotlinc (+ compose-compiler plugin)"
export KOTLIN_COMPOSE_PLUGIN_JAR="$COMPOSE_PLUGIN"
SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find app/src/main/java -name '*.kt' | sort)
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$BUILD/gen" -name '*.java' | sort)
kotlin_compile "$BUILD/classes" "$AJAR:$CP" "${SOURCES[@]}"
unset KOTLIN_COMPOSE_PLUGIN_JAR
# R.java 群は javac でもコンパイルして classes に含める
GEN_JAVA=()
while IFS= read -r f; do GEN_JAVA+=("$f"); done < <(find "$BUILD/gen" -name '*.java' | sort)
if [ "${#GEN_JAVA[@]}" -gt 0 ]; then
  "$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" "${GEN_JAVA[@]}"
fi

# ===== 4. d8 (単体 R8 の D8、multidex) =====
echo "[4/6] d8 (standalone R8 8.7.18)"
[ -f "$BUILD/classes/jp/yoru/clock/MainActivity.class" ] || { echo "コンパイル成果物がありません: MainActivity.class" >&2; exit 1; }
CLASS_FILES=()
while IFS= read -r f; do CLASS_FILES+=("$f"); done < <(find "$BUILD/classes" -name '*.class' | sort)
# ヒープ上限とスレッド数を絞る (メモリの少ない環境で OOM Kill されるのを防ぐ)
"${JAVA_BIN}java" -Xmx2g -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 26 --thread-count 2 --lib "$AJAR" --output "$BUILD/dex" \
  "${CLASS_FILES[@]}" "${DEX_JARS[@]}" "$KOTLIN_STDLIB_JAR"
ls "$BUILD/dex"

# ===== 5. package + zipalign =====
echo "[5/6] package + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes*.dex )
# AAR 内のネイティブライブラリを lib/<abi>/ に同梱 (非圧縮で格納し zipalign -p で整列)
# AAR が無い場合、find の探索起点がカレントディレクトリになってしまうため実行しない
if [ "${#AAR_DIRS[@]}" -gt 0 ]; then
  while IFS= read -r so; do
    rel="${so#*jni/}"
    mkdir -p "$BUILD/nativelib/lib/$(dirname "$rel")"
    cp "$so" "$BUILD/nativelib/lib/$rel"
  done < <(find "${AAR_DIRS[@]}" -path '*/jni/*' -name '*.so')
fi
if [ -d "$BUILD/nativelib" ]; then
  ( cd "$BUILD/nativelib" && zip -q -0 -r ../app-with-dex.apk lib )
fi
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

# ===== 6. sign =====
# 既定は同梱の debug.keystore。自分の鍵で署名する場合は環境変数で指定する
# (YORU_CLOCK_KEYSTORE / YORU_CLOCK_KEYSTORE_PASS / YORU_CLOCK_KEY_ALIAS / YORU_CLOCK_KEY_PASS)
echo "[6/6] sign"
KS="${YORU_CLOCK_KEYSTORE:-debug.keystore}"
KS_PASS="${YORU_CLOCK_KEYSTORE_PASS:-android}"
KEY_ALIAS="${YORU_CLOCK_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${YORU_CLOCK_KEY_PASS:-$KS_PASS}"
if [ ! -f "$KS" ]; then
  if [ "$KS" = "debug.keystore" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=YoruClock,C=JP" >/dev/null 2>&1
  else
    echo "鍵ストアが見つかりません: $KS" >&2
    exit 1
  fi
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --out "$BUILD/yoru-clock.apk" "$BUILD/app-aligned.apk"
CERT_OUT="$("$BT/apksigner" verify --print-certs "$BUILD/yoru-clock.apk")"
printf '%s\n' "$CERT_OUT" | awk 'NR<=3'
mkdir -p dist
cp "$BUILD/yoru-clock.apk" dist/yoru-clock.apk
echo "OK: dist/yoru-clock.apk"
