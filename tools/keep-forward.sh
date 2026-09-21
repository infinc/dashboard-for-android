#!/usr/bin/env bash
# adb の port forward を維持し続ける。
#
# adb forward は USB 接続に紐づくため、ケーブルの接触や端末側 USB の
# スリープ復帰、adb サーバーの再起動で静かに消える。そのたびに
# http://localhost:8080/settings が開けなくなるので、消えていたら張り直す。
#
#   bash tools/keep-forward.sh          # 既定 8080
#   PORT=9000 bash tools/keep-forward.sh
set -uo pipefail
PORT="${PORT:-8080}"
INTERVAL="${INTERVAL:-5}"

echo "adb forward tcp:$PORT を監視します（${INTERVAL}秒間隔）。Ctrl-C で終了。"
last=""
while true; do
  if adb get-state >/dev/null 2>&1; then
    if ! adb forward --list 2>/dev/null | grep -q "tcp:$PORT tcp:$PORT"; then
      if adb forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; then
        echo "$(date '+%H:%M:%S') 転送を張り直しました → http://localhost:$PORT/settings"
      fi
    fi
    [ "$last" != "up" ] && { echo "$(date '+%H:%M:%S') 端末接続を検出"; last=up; }
  else
    [ "$last" != "down" ] && { echo "$(date '+%H:%M:%S') 端末が見つかりません（USB を確認してください）"; last=down; }
  fi
  sleep "$INTERVAL"
done
