#!/usr/bin/env bash
# Phase 0.5: 対象タブレットの実態を採取し、設計値を決めるための情報を集める。
# 使い方: USB 接続して開発者オプション/USB デバッグを有効にしたあと
#   bash tools/probe-device.sh > docs/device-probe-$(date +%Y%m%d).txt
set -uo pipefail

say() { printf '\n===== %s =====\n' "$1"; }

if ! adb get-state >/dev/null 2>&1; then
  echo "端末が見つかりません。USB 接続と『USB デバッグを許可』を確認してください。" >&2
  exit 1
fi

say "端末"
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk

say "WebView（使える CSS の下限を決める）"
adb shell dumpsys package com.google.android.webview 2>/dev/null | grep -m2 versionName
adb shell cmd webviewupdate query 2>/dev/null

say "画面（レイアウト基準）"
adb shell wm size
adb shell wm density

say "メモリ"
adb shell cat /proc/meminfo | head -3

say "省電力"
adb shell dumpsys deviceidle enabled 2>/dev/null
adb shell settings get global low_power
adb shell dumpsys power 2>/dev/null | grep -m5 -i 'mWakefulness\|mScreenOn\|Display Power'

say "発熱（30 分以上の連続表示後に取ると意味がある）"
adb shell dumpsys thermalservice 2>/dev/null | head -40

say "スリープ設定"
adb shell settings get system screen_off_timeout
