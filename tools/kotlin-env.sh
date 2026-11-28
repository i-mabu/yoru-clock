# Kotlin コンパイラ 2.0.21 の解決 (build-apk.sh / tests/run-tests.sh 共通)
#
# 探す順序:
#   (1) $KOTLIN_HOME/bin/kotlinc          (明示指定を最優先)
#   (2) ~/workspace/tools/kotlin/bin/kotlinc (他のアプリと共有のツールチェーン)
#   (3) PATH 上の kotlinc                  (シンボリックリンクを解決して stdlib を探す)
# バージョンが 2.0.21 でない kotlinc は採用せず、次の候補へ進む。
#
# このファイルは source して使う。成功すると次が使えるようになる:
#   KOTLINC_BIN        : kotlinc のパス
#   KOTLIN_STDLIB_JAR  : kotlin-stdlib jar のパス (dex 入力 / 実行時 classpath 用)
#   kotlin_compile <out_dir> <extra_classpath> <source...>
#                      : Kotlin (と混在する Java) をまとめてコンパイルする。
#                        環境変数 KOTLIN_COMPOSE_PLUGIN_JAR があれば -Xplugin で渡す。
# 見つからない場合は対処法を表示して非ゼロで戻る。

KOTLIN_VERSION="2.0.21"
KOTLINC_BIN=""
KOTLIN_STDLIB_JAR=""

# 候補 kotlinc を検証し、使えるなら KOTLINC_BIN / KOTLIN_STDLIB_JAR を設定する
_kotlin_try_candidate() {
    local candidate="$1"
    [ -x "$candidate" ] || return 1
    local resolved dir home jar
    resolved="$(readlink -f "$candidate" 2>/dev/null || echo "$candidate")"
    dir="$(dirname "$resolved")"
    home="$(cd "$dir/.." 2>/dev/null && pwd)"
    jar=""
    for path in \
        "$home/lib/kotlin-stdlib.jar" \
        "$home"/lib/kotlin-stdlib-*.jar \
        "$home/share/kotlin/lib/kotlin-stdlib.jar"; do
        if [ -f "$path" ]; then
            jar="$path"
            break
        fi
    done
    [ -n "$jar" ] || return 1
    local version_line
    version_line="$("$candidate" -version 2>&1 | head -1)"
    case "$version_line" in
        *"$KOTLIN_VERSION"*) ;;
        *) return 1 ;;
    esac
    KOTLINC_BIN="$candidate"
    KOTLIN_STDLIB_JAR="$jar"
    return 0
}

_kotlin_resolve() {
    if [ -n "${KOTLIN_HOME:-}" ] && _kotlin_try_candidate "$KOTLIN_HOME/bin/kotlinc"; then
        return 0
    fi
    if _kotlin_try_candidate "$HOME/workspace/tools/kotlin/bin/kotlinc"; then
        return 0
    fi
    if command -v kotlinc >/dev/null 2>&1 && _kotlin_try_candidate "$(command -v kotlinc)"; then
        return 0
    fi
    echo "エラー: Kotlin コンパイラ $KOTLIN_VERSION が見つかりません。" >&2
    echo "  次のいずれかを行ってください:" >&2
    echo "    - KOTLIN_HOME に Kotlin $KOTLIN_VERSION のインストール先を設定する" >&2
    echo "    - 共有ツールチェーン ~/workspace/tools/kotlin を用意する" >&2
    echo "    - kotlinc ($KOTLIN_VERSION) を PATH に入れる" >&2
    return 1
}

if ! _kotlin_resolve; then
    return 1 2>/dev/null || exit 1
fi

# Kotlin (+ Java 混在) の一括コンパイル
kotlin_compile() {
    local out_dir="$1"
    local extra_cp="$2"
    shift 2
    mkdir -p "$out_dir"
    local classpath="$KOTLIN_STDLIB_JAR"
    if [ -n "$extra_cp" ]; then classpath="$classpath:$extra_cp"; fi
    local plugin_args=()
    if [ -n "${KOTLIN_COMPOSE_PLUGIN_JAR:-}" ]; then
        plugin_args=(-Xplugin="$KOTLIN_COMPOSE_PLUGIN_JAR")
    fi
    "$KOTLINC_BIN" -no-stdlib -no-reflect -jvm-target 17 \
        -cp "$classpath" -d "$out_dir" "${plugin_args[@]}" "$@"
}
