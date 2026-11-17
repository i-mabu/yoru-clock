#!/usr/bin/env bash
# よる時計 純ロジック単体テスト (Kotlin コンパイル + java 実行)
# 1パス目: 通常テスト (Asia/Tokyo) / 2パス目: DSTテスト (America/New_York, 引数 "dst")
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
JAVA="$JAVA_HOME/bin/java"

# Kotlin コンパイラ解決 (KOTLIN_STDLIB_JAR と kotlin_compile を提供する)
. "$(pwd)/tools/kotlin-env.sh"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlin_compile "$OUT" "" \
  app/src/main/java/jp/yoru/clock/ClockLogic.kt \
  app/src/main/java/jp/yoru/clock/AlarmItem.kt \
  tests/TestMain.kt
echo "--- pass 1: normal (Asia/Tokyo) ---"
"$JAVA" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tokyo -cp "$OUT:$KOTLIN_STDLIB_JAR" TestMain
echo "--- pass 2: DST (America/New_York) ---"
"$JAVA" -Dfile.encoding=UTF-8 -Duser.timezone=America/New_York -cp "$OUT:$KOTLIN_STDLIB_JAR" TestMain dst
