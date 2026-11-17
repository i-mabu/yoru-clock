# Kotlin コンパイラ 2.0.21 の解決 (build-apk.sh / tests/run-tests.sh 共通)
# 他のアプリ (aegis-android 等) と同じ共有ツールチェーン ~/workspace/tools/kotlin を使う。
# 優先順位: (1) $KOTLIN_HOME/bin/kotlinc (明示指定を最優先)
#           (2) ~/workspace/tools/kotlin/bin/kotlinc (共有ツールチェーン)
#           (3) PATH 上の kotlinc (シンボリックリンクを解決して stdlib を探す)
#   バージョンが 2.0.21 でない kotlinc は採用しない (次の候補へ進む)。
# 提供するもの:
#   KOTLIN_STDLIB_JAR : kotlin-stdlib jar のパス (d8 / 実行時 classpath 用)
#   KOTLINC_BIN       : kotlinc のパス
#   kotlin_compile <out_dir> <extra_classpath> <source...> : Kotlin (+混在する Java) を一括コンパイル
#     環境変数 KOTLIN_COMPOSE_PLUGIN_JAR が設定されていれば -Xplugin で Compose コンパイラを有効化する
# このファイルは source して使う。見つからない場合は分かりやすいエラーを出して非ゼロで戻る。

KOTLIN_VERSION="2.0.21"
KOTLINC_BIN=""
KOTLIN_STDLIB_JAR=""

# ===== 1. 解決処理とコンパイル =====
# ----- 1.1 kotlinc 候補の検証 -----
_try_kotlinc() {
    local kc="$1"
    [ -x "$kc" ] || return 1
    local real dir home cand
    real="$(readlink -f "$kc" 2>/dev/null || echo "$kc")"
    dir="$(dirname "$real")"
    home="$(cd "$dir/.." 2>/dev/null && pwd)"
    for cand in \
        "$home/lib/kotlin-stdlib.jar" \
        "$home"/lib/kotlin-stdlib-*.jar \
        "$home/share/kotlin/lib/kotlin-stdlib.jar"; do
        if [ -f "$cand" ]; then
            local ver
            ver="$("$kc" -version 2>&1 | head -1)"
            case "$ver" in
                *"$KOTLIN_VERSION"*) ;;
                *) return 1 ;;
            esac
            KOTLIN_STDLIB_JAR="$cand"
            KOTLINC_BIN="$kc"
            return 0
        fi
    done
    return 1
}

# ----- 1.2 コンパイラの解決 -----
_kotlin_resolve() {
    if [ -n "${KOTLIN_HOME:-}" ] && _try_kotlinc "$KOTLIN_HOME/bin/kotlinc"; then
        return 0
    fi
    if _try_kotlinc "$HOME/workspace/tools/kotlin/bin/kotlinc"; then
        return 0
    fi
    if command -v kotlinc >/dev/null 2>&1 && _try_kotlinc "$(command -v kotlinc)"; then
        return 0
    fi
    echo "エラー: Kotlin コンパイラ $KOTLIN_VERSION が見つかりません。" >&2
    echo "  対処のいずれかを行ってください:" >&2
    echo "    - KOTLIN_HOME に Kotlin $KOTLIN_VERSION のインストール先を設定する" >&2
    echo "    - 共有ツールチェーン ~/workspace/tools/kotlin を用意する" >&2
    echo "    - kotlinc ($KOTLIN_VERSION) を PATH に入れる" >&2
    return 1
}

if ! _kotlin_resolve; then
    return 1 2>/dev/null || exit 1
fi

# ----- 1.3 コンパイル -----
kotlin_compile() {
    local out="$1"
    local cp="$2"
    shift 2
    mkdir -p "$out"
    local fullcp="$KOTLIN_STDLIB_JAR"
    if [ -n "$cp" ]; then fullcp="$fullcp:$cp"; fi
    local plugin_args=()
    if [ -n "${KOTLIN_COMPOSE_PLUGIN_JAR:-}" ]; then
        plugin_args=(-Xplugin="$KOTLIN_COMPOSE_PLUGIN_JAR")
    fi
    "$KOTLINC_BIN" -no-stdlib -no-reflect -jvm-target 17 -cp "$fullcp" -d "$out" "${plugin_args[@]}" "$@"
}
