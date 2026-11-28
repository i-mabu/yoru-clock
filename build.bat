@echo off
rem よる時計 (Yoru Clock) の一括ビルド (Windows 用ラッパー)
rem 本体は bash スクリプト (build.sh → build-apk.sh) のため、Windows では
rem Git Bash または WSL の bash 経由で build.sh を実行する。
rem
rem   build.bat                単体テスト → APK ビルド → 署名 (dist\yoru-clock.apk)
rem   build.bat --skip-tests   テストを省略してビルドだけ行う
rem 署名は環境変数 YORU_CLOCK_KEYSTORE 等で指定する (build.sh のヘッダー参照)。
setlocal
chcp 65001 >nul
cd /d "%~dp0"

where bash >nul 2>nul
if errorlevel 1 (
  echo ERROR: bash が見つかりません。Git Bash または WSL から実行してください: bash build.sh 1>&2
  exit /b 1
)

bash build.sh %*
if errorlevel 1 (
  echo ERROR: ビルドが失敗しました (bash build.sh) 1>&2
  exit /b 1
)

exit /b 0
