#!/usr/bin/env bash
# よる時計 (Yoru Clock) - オフライン手動ビルド
# Gradle/AGP を使わず、aapt2 + kotlinc (Compose プラグイン) + R8/D8 で APK を作る。
#
# Compose 対応で通常の手動ビルドと異なる点:
#   * 依存ライブラリ (Compose 1.7.6 / Material3 1.3.1 等) は libs/ に置く
#     (取得は tools/resolve.py。libs/artifacts.txt の順序で読み込む)
#   * ライブラリ AAR の res/ も aapt2 compile し、link 時に --auto-add-overlay と
#     --extra-packages (各ライブラリの R クラス生成) を付ける
#   * Compose コンパイラは Maven 版プラグイン jar を -Xplugin で渡す
#     (kotlinc 配布物同梱版は embeddable コンパイラ向けで使えない)
#   * dex 化は build-tools 同梱 d8 ではなく単体 R8 8.7.18 の D8 を使う
#     (同梱版は lifecycle の匿名内部クラスでクラッシュするため)。メソッド数
#     64k 超のため multidex (生成された classes*.dex を全て同梱する)
#   * AAR 内の .so (androidx.graphics.path) は lib/<abi>/ に同梱し、
#     zipalign -p でページ整列する
#
# 必要環境: JDK 21 (~/workspace/tools/jdk-21)、Kotlin 2.0.21
#           (tools/kotlin-env.sh が解決)、Android SDK (platforms;android-34 /
#           build-tools;34.0.0、ANDROID_SDK_ROOT / ANDROID_HOME で上書き可)
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH" # d8 等が java を PATH から探すため

# Kotlin コンパイラ解決 (KOTLINC_BIN / KOTLIN_STDLIB_JAR / kotlin_compile を提供)
. "$(dirname "$0")/tools/kotlin-env.sh"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "android.jar が見つかりません: $ANDROID_JAR" >&2; exit 1; }
[ -x "$BUILD_TOOLS/aapt2" ] || { echo "aapt2 が見つかりません: $BUILD_TOOLS" >&2; exit 1; }

JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool

COMPOSE_PLUGIN_JAR="tools/kotlin-compose-compiler-plugin-2.0.21.jar"
R8_JAR="tools/r8-8.7.18.jar"
[ -f "$COMPOSE_PLUGIN_JAR" ] || { echo "Compose プラグインがありません: $COMPOSE_PLUGIN_JAR" >&2; exit 1; }
[ -f "$R8_JAR" ] || { echo "R8 jar がありません: $R8_JAR" >&2; exit 1; }
[ -f libs/artifacts.txt ] || { echo "libs/ がありません。python3 tools/resolve.py を実行してください" >&2; exit 1; }

WORK=build/manual
AAR_ROOT=build/aars
rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex" "$WORK/libres" "$AAR_ROOT"

# ===== 0. ライブラリ収集 =====
COMPILE_JARS=()  # コンパイル用 classpath
DEX_JARS=()      # dex の入力
AAR_DIRS=()      # 展開済み AAR のディレクトリ (artifacts.txt の順序どおり)
while IFS= read -r entry; do
  case "$entry" in
    kotlin-stdlib*) continue ;; # stdlib は kotlinc 配布物 (2.0.21) を使う
  esac
  artifact_path="libs/$entry"
  [ -f "$artifact_path" ] || { echo "成果物がありません: $artifact_path (python3 tools/resolve.py を実行してください)" >&2; exit 1; }
  if [[ "$entry" == *.aar ]]; then
    dir="$AAR_ROOT/${entry%.aar}"
    AAR_DIRS+=("$dir")
    if [ ! -d "$dir" ]; then
      mkdir -p "$dir"
      unzip -qo "$artifact_path" -d "$dir"
    fi
    if [ -f "$dir/classes.jar" ]; then
      COMPILE_JARS+=("$dir/classes.jar")
      DEX_JARS+=("$dir/classes.jar")
    fi
  else
    # クラスを含まない KMP ルート jar は重複クラス回避のため除外する
    if [ "$(unzip -l "$artifact_path" | grep -c '\.class' || true)" -gt 0 ]; then
      COMPILE_JARS+=("$artifact_path")
      DEX_JARS+=("$artifact_path")
    fi
  fi
done < libs/artifacts.txt
CLASSPATH="$(IFS=:; echo "${COMPILE_JARS[*]}")"
echo "libraries: ${#COMPILE_JARS[@]} jars"

# ===== 1. aapt2 compile (アプリ + ライブラリのリソース) =====
echo "[1/6] aapt2 compile (app + library resources)"
"$BUILD_TOOLS/aapt2" compile --dir app/src/main/res -o "$WORK/libres/app.zip"
RESOURCE_ZIPS=("$WORK/libres/app.zip")
EXTRA_PACKAGES=()
for dir in "${AAR_DIRS[@]}"; do
  if [ -d "$dir/res" ] && [ -n "$(find "$dir/res" -type f -print -quit)" ]; then
    compiled="$WORK/libres/$(basename "$dir").zip"
    "$BUILD_TOOLS/aapt2" compile --dir "$dir/res" -o "$compiled"
    RESOURCE_ZIPS+=("$compiled")
  fi
  if [ -f "$dir/R.txt" ]; then
    pkg="$(grep -o 'package="[^"]*"' "$dir/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
    [ -n "$pkg" ] && EXTRA_PACKAGES+=("$pkg")
  fi
done
echo "  resource sets: ${#RESOURCE_ZIPS[@]}, R packages: ${#EXTRA_PACKAGES[@]}"

# ===== 2. aapt2 link =====
echo "[2/6] aapt2 link (--auto-add-overlay, --extra-packages)"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーへ注入して使う
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.yoru.clock"|' \
  app/src/main/AndroidManifest.xml > "$WORK/AndroidManifest.xml"
EXTRA_PACKAGE_ARGS=()
if [ "${#EXTRA_PACKAGES[@]}" -gt 0 ]; then
  EXTRA_PACKAGE_ARGS=(--extra-packages "$(IFS=:; echo "${EXTRA_PACKAGES[*]}")")
fi
"$BUILD_TOOLS/aapt2" link -o "$WORK/app-unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$WORK/AndroidManifest.xml" "${RESOURCE_ZIPS[@]}" \
  --java "$WORK/gen" --auto-add-overlay "${EXTRA_PACKAGE_ARGS[@]}" \
  --min-sdk-version 26 --target-sdk-version 34

# ===== 3. Kotlin コンパイル (Compose プラグイン有効) =====
echo "[3/6] kotlinc (+ compose compiler plugin)"
export KOTLIN_COMPOSE_PLUGIN_JAR="$COMPOSE_PLUGIN_JAR"
APP_SOURCES=()
while IFS= read -r file; do APP_SOURCES+=("$file"); done < <(find app/src/main/java -name '*.kt' | sort)
GENERATED_SOURCES=()
while IFS= read -r file; do GENERATED_SOURCES+=("$file"); done < <(find "$WORK/gen" -name '*.java' | sort)
kotlin_compile "$WORK/classes" "$ANDROID_JAR:$CLASSPATH" "${APP_SOURCES[@]}" "${GENERATED_SOURCES[@]}"
unset KOTLIN_COMPOSE_PLUGIN_JAR
# 生成された R.java 群は javac でもコンパイルして classes に含める
if [ "${#GENERATED_SOURCES[@]}" -gt 0 ]; then
  "$JAVAC" --release 17 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$WORK/classes" "${GENERATED_SOURCES[@]}"
fi

# ===== 4. dex 化 (単体 R8 の D8、multidex) =====
echo "[4/6] d8 (standalone R8 8.7.18)"
[ -f "$WORK/classes/jp/yoru/clock/MainActivity.class" ] || { echo "コンパイル成果物がありません: MainActivity.class" >&2; exit 1; }
CLASS_FILES=()
while IFS= read -r file; do CLASS_FILES+=("$file"); done < <(find "$WORK/classes" -name '*.class' | sort)
# メモリの少ない環境で OOM Kill されないよう、ヒープ上限とスレッド数を絞る
"$JAVA_HOME/bin/java" -Xmx2g -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 26 --thread-count 2 --lib "$ANDROID_JAR" --output "$WORK/dex" \
  "${CLASS_FILES[@]}" "${DEX_JARS[@]}" "$KOTLIN_STDLIB_JAR"
ls "$WORK/dex"

# ===== 5. パッケージ + zipalign =====
echo "[5/6] package + zipalign"
cp "$WORK/app-unsigned.apk" "$WORK/app-with-dex.apk"
( cd "$WORK/dex" && zip -q -uj ../app-with-dex.apk classes*.dex )
# AAR 内のネイティブライブラリを lib/<abi>/ に同梱する (非圧縮で格納し、zipalign -p で整列)
if [ "${#AAR_DIRS[@]}" -gt 0 ]; then
  while IFS= read -r so_file; do
    relative="${so_file#*jni/}"
    mkdir -p "$WORK/nativelib/lib/$(dirname "$relative")"
    cp "$so_file" "$WORK/nativelib/lib/$relative"
  done < <(find "${AAR_DIRS[@]}" -path '*/jni/*' -name '*.so')
fi
if [ -d "$WORK/nativelib" ]; then
  ( cd "$WORK/nativelib" && zip -q -0 -r ../app-with-dex.apk lib )
fi
"$BUILD_TOOLS/zipalign" -f -p 4 "$WORK/app-with-dex.apk" "$WORK/app-aligned.apk"

# ===== 6. 署名 =====
# 既定は同梱の debug.keystore (無ければ自動生成)。自分の鍵で署名する場合は
# 環境変数 YORU_CLOCK_KEYSTORE / YORU_CLOCK_KEYSTORE_PASS /
# YORU_CLOCK_KEY_ALIAS / YORU_CLOCK_KEY_PASS で指定する。
echo "[6/6] sign"
KEYSTORE="${YORU_CLOCK_KEYSTORE:-debug.keystore}"
KEYSTORE_PASS="${YORU_CLOCK_KEYSTORE_PASS:-android}"
KEY_ALIAS="${YORU_CLOCK_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${YORU_CLOCK_KEY_PASS:-$KEYSTORE_PASS}"
if [ ! -f "$KEYSTORE" ]; then
  if [ "$KEYSTORE" = "debug.keystore" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=YoruClock,C=JP" >/dev/null 2>&1
  else
    echo "鍵ストアが見つかりません: $KEYSTORE" >&2
    exit 1
  fi
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" \
  --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$WORK/yoru-clock.apk" "$WORK/app-aligned.apk"
CERT_REPORT="$("$BUILD_TOOLS/apksigner" verify --print-certs "$WORK/yoru-clock.apk")"
printf '%s\n' "$CERT_REPORT" | awk 'NR<=3'
mkdir -p dist
cp "$WORK/yoru-clock.apk" dist/yoru-clock.apk
echo "OK: dist/yoru-clock.apk"
