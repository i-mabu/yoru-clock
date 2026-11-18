# よる時計 (Yoru Clock) — Android

夜を基調にしたダークテーマの時計アプリ。UI は Jetpack Compose、完全オフライン（ネットワーク権限なし）。

- パッケージ: `jp.yoru.clock`
- バージョン: versionCode 5 / versionName 1.2.0
- minSdk 26 / targetSdk 34
- 言語: Kotlin 2.0.21 + Jetpack Compose 1.7.6 / Material3 1.3.1（v1.2.0 で従来のView実装から移行）

## ✨ 機能

### 🕐 時計タブ
- 大きなデジタル時計（HH:mm:ss、1秒の境界に合わせて更新）
- 日付表示「2026年11月17日（火）」形式
- アナログ時計（Compose Canvas 描画。目盛り・数字・時針/分針/秒針、秒針は滑らか）
- 世界時計: 既定で 東京 / ソウル / ロンドン / ニューヨーク / ロサンゼルス / シドニー
  - 現地時刻と日本との時差（例: -9時間）を表示
  - カタログ（全18都市）から追加、長押しで削除。SharedPreferencesに保存

### ⏰ アラームタブ
- 複数アラームの一覧（時刻・ラベル・繰り返し曜日・ON/OFFスイッチ・次回発火表示）
- 追加/編集画面: 時刻選択（24時間表記ドロップダウン）、ラベル、曜日繰り返し（月〜日、なし=単発）、保存/削除
- 次回発火計算は純ロジック `ClockLogic.nextTriggerMillis` に分離し単体テスト済み
- `AlarmManager.setAlarmClock` で発火（正確なアラームの特別権限が不要な方式）
- 鳴動画面: 全画面寄り表示、時刻とラベル、スヌーズ5分、停止
  - 音はデフォルトアラーム音（なければ通知音）をループ再生、バイブレーション併用
- 単発アラームは発火後に自動でOFF、繰り返しは次回分を自動再登録
- 鳴動は高優先度通知＋フルスクリーンインテントが主経路（直接起動も併用）
- 再起動・時刻/タイムゾーン変更・アプリ更新後は BootReceiver が有効なアラームと実行中タイマーを再登録
  （電源OFF中に発火時刻を過ぎた単発アラームは鳴らさずOFFにする）
- データは SharedPreferences に JSON（org.json）で保存

### ⏲️ タイマータブ
- 時/分/秒の数値ドロップダウン、プリセット 1/3/5/10/15/30/60分
- 開始/一時停止/再開/キャンセル、残り時間の大きな表示＋プログレスバー
- 終了時刻を SharedPreferences に保存し、アプリを閉じても残り時間を復元
- 終了時は AlarmManager 経由でアプリが閉じていても鳴動画面を表示（アラームと共通）

### ⏱️ ストップウォッチタブ
- 開始/一時停止/リセット、1/100秒表示（mm:ss.cc）
- ラップ: ラップ番号・ラップタイム・累計の一覧（新しい順）

## 🔨 ビルド方法

Gradle/AGPを使わず、aapt2 + Kotlinコンパイラ（Composeプラグイン）+ R8/D8 の手動ビルドです。

```sh
./build.sh       # テスト → APKビルド → 成果物表示（リリースビルドはこれ1つ）
./build-apk.sh   # APKビルドのみ → dist/yoru-clock.apk
```

初回のみ、Compose の依存ライブラリとビルド用 jar を取得します（ネットワークが必要）:

```sh
python3 tools/resolve.py   # libs/ に依存アーティファクト一式を取得
# あわせて tools/ に次の2つを配置する:
#   tools/r8-8.7.18.jar (com.android.tools:r8:8.7.18, Google Maven)
#   tools/kotlin-compose-compiler-plugin-2.0.21.jar (Maven Central)
```

Compose 化に伴う手動ビルド上の要点:
- ライブラリ AAR のリソースは aapt2 で compile し、`--auto-add-overlay` と
  `--extra-packages`（各ライブラリの R クラス生成）でリンクする
- Compose コンパイラは Maven 版プラグイン jar を `-Xplugin` で渡す
  （kotlinc 配布物同梱版は embeddable コンパイラ向けで使えない）
- dex 化は build-tools 同梱 d8 ではなく単体 R8 8.7.18 の D8 を使う
  （同梱版は lifecycle の匿名内部クラスでクラッシュする）。64k メソッド超のため multidex
- AAR 内の `.so`（androidx.graphics.path）は `lib/<abi>/` に同梱する

必要環境:
- JDK（`~/workspace/tools/jdk-21` を想定。コンパイルは jvm-target 17）
- Kotlin コンパイラ 2.0.21（他のアプリと共有の `~/workspace/tools/kotlin` を想定）
  - 解決の優先順位は `tools/kotlin-env.sh` を参照（KOTLIN_HOME → 共有ツールチェーン → PATH。
    バージョンが 2.0.21 でない kotlinc は採用しない）
  - APK には kotlin-stdlib（2.0.21）が同梱される（dex の入力に含める）
- Android SDK（`~/workspace/tools/android/sdk`、platforms;android-34、build-tools;34.0.0）
  - `ANDROID_SDK_ROOT` / `ANDROID_HOME` で上書き可能
- 署名は既定で同梱のデバッグ用キーストア（`debug.keystore`、なければ自動生成）。
  自分の鍵で署名する場合は環境変数 `YORU_CLOCK_KEYSTORE` / `YORU_CLOCK_KEYSTORE_PASS` /
  `YORU_CLOCK_KEY_ALIAS` / `YORU_CLOCK_KEY_PASS` で指定する

ビルドは bash スクリプトです（build.bat はありません）。Windows では WSL 上で実行してください。

## 🧪 テスト

純ロジック（アラーム次回発火・時差計算・フォーマット類）の単体テスト:

```sh
./tests/run-tests.sh   # 通常パス 68 checks + DSTパス 3 checks
```

## 🔐 権限の説明

| 権限 | 用途 |
|---|---|
| RECEIVE_BOOT_COMPLETED | 再起動後にアラーム/タイマーを再登録 |
| VIBRATE | 鳴動時のバイブレーション |
| POST_NOTIFICATIONS | 鳴動時の高優先度通知の表示（Android 13+ で許可が必要） |
| USE_FULL_SCREEN_INTENT | 通知のフルスクリーンインテントでロック画面上に鳴動画面を表示 |

- ネットワーク権限（INTERNET）は付けていません。完全オフラインで動作します。
- `setAlarmClock`（アラームアプリ向けAPI）を使うため、Android 12+ の
  `SCHEDULE_EXACT_ALARM` 権限は不要で、正確な発火が可能です。

## ⚠️ 既知の制限

- 実機未検証（このVM上でのビルド・単体テスト・APK署名検証のみ実施）。
  鳴動画面の表示、ロック画面上の挙動、メーカー独自の電池最適化による
  遅延などは実機での確認が必要です。
- 署名はデバッグ用キーストアです。配布する場合は正式なキーストアで再署名してください。
- アラーム音量は端末のアラーム音量設定に従います。独自の音源選択UIはありません。
- 世界時計の追加候補は固定カタログ（18都市）です。任意のタイムゾーン検索は未対応。
- ストップウォッチの状態はアプリを閉じると保持されません（タイマーは保持されます）。
- スヌーズは鳴動画面からのみ設定できます（事前設定は未対応）。
- 鳴動は高優先度通知＋フルスクリーンインテントが主経路です。通知許可がない場合
  （Android 13+ で未許可）は鳴動画面の直接起動へフォールバックしますが、
  端末の状態によっては画面が自動で出ない場合があります。
- タイマーは壁時計（終了時刻の epoch ミリ秒）基準のため、計測中に端末の時刻や
  タイムゾーンが変わると発火時刻もずれます（経過時間基準との併用は再起動を
  跨げないため、トレードオフとして現状の方式を採用しています）。

## 📂 リポジトリ

- GitHub: https://github.com/i-mabu/yoru-clock (Private)
