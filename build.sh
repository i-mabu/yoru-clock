#!/usr/bin/env bash
# よる時計 (Yoru Clock) - 一括ビルドスクリプト
# 単体テスト → APK ビルド → 成果物表示 を 1 コマンドで行う: ./build.sh
#   --skip-tests : テストを省略して APK ビルドだけを行う
#
# 署名: 既定はリポジトリ同梱の debug.keystore。自分の鍵で署名する場合は
# 環境変数で指定する (値はファイルに書かない):
#   YORU_CLOCK_KEYSTORE       鍵ストアのパス
#   YORU_CLOCK_KEYSTORE_PASS  鍵ストアのパスワード
#   YORU_CLOCK_KEY_ALIAS      鍵のエイリアス (既定 androiddebugkey)
#   YORU_CLOCK_KEY_PASS       鍵のパスワード (既定はストアと同じ)
set -euo pipefail
cd "$(dirname "$0")"

SKIP_TESTS=0
for arg in "$@"; do
    case "$arg" in
        --skip-tests) SKIP_TESTS=1 ;;
        *) echo "不明な引数: $arg" >&2; exit 2 ;;
    esac
done

if [ "$SKIP_TESTS" -eq 0 ]; then
    echo "===== [1/2] 単体テスト ====="
    ./tests/run-tests.sh
else
    echo "===== [1/2] 単体テスト (省略) ====="
fi

echo "===== [2/2] APK ビルド ====="
./build-apk.sh

APK="dist/yoru-clock.apk"
echo "===== 完了 ====="
echo "成果物: $(pwd)/$APK"
ls -la "$APK"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$APK"
fi
