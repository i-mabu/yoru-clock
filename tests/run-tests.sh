#!/usr/bin/env bash
# よる時計 純ロジック単体テスト (javac + java)
# 1パス目: 通常テスト (Asia/Tokyo) / 2パス目: DSTテスト (America/New_York, 引数 "dst")
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$JAVAC" -encoding UTF-8 -d "$OUT" \
  app/src/main/java/jp/yoru/clock/ClockLogic.java \
  app/src/main/java/jp/yoru/clock/AlarmItem.java \
  tests/TestMain.java
echo "--- pass 1: normal (Asia/Tokyo) ---"
"$JAVA" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tokyo -cp "$OUT" TestMain
echo "--- pass 2: DST (America/New_York) ---"
"$JAVA" -Dfile.encoding=UTF-8 -Duser.timezone=America/New_York -cp "$OUT" TestMain dst
