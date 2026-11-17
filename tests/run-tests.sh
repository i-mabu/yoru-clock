#!/usr/bin/env bash
# よる時計 純ロジック単体テスト (javac + java)
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
"$JAVA" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tokyo -cp "$OUT" TestMain
