#!/usr/bin/env bash
# よる時計の純ロジック単体テスト (Kotlin をコンパイルして JVM で実行する)
#   パス 1: 通常テスト (Asia/Tokyo)
#   パス 2: DST テスト (America/New_York、引数 "dst")
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
JAVA_BIN="$JAVA_HOME/bin/java"

# Kotlin コンパイラ解決 (KOTLIN_STDLIB_JAR / kotlin_compile を提供する)
. "$(pwd)/tools/kotlin-env.sh"

OUT_DIR="$(mktemp -d)"
trap 'rm -rf "$OUT_DIR"' EXIT
kotlin_compile "$OUT_DIR" "" \
  app/src/main/java/jp/yoru/clock/ClockLogic.kt \
  app/src/main/java/jp/yoru/clock/AlarmItem.kt \
  tests/TestMain.kt

echo "--- pass 1: normal (Asia/Tokyo) ---"
"$JAVA_BIN" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tokyo -cp "$OUT_DIR:$KOTLIN_STDLIB_JAR" TestMain
echo "--- pass 2: DST (America/New_York) ---"
"$JAVA_BIN" -Dfile.encoding=UTF-8 -Duser.timezone=America/New_York -cp "$OUT_DIR:$KOTLIN_STDLIB_JAR" TestMain dst
