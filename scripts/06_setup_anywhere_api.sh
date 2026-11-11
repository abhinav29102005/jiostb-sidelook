#!/bin/bash
# 06_setup_anywhere_api.sh
# Compiles (if Go + NDK present) and deploys the native Go TV Connection API (port 8088)
# so the STB can be controlled securely over LAN or anywhere via Tailscale (com.tailscale.ipn).

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"

echo "=== Building / Deploying Native TV Connection API (Port 8088) ==="
CC_BIN="${ANDROID_NDK_CC:-$(find "${ANDROID_HOME:-$HOME/Android}/ndk" -name "armv7a-linux-androideabi24-clang" 2>/dev/null | sort -V | tail -n 1)}"
if command -v go >/dev/null 2>&1 && [ -n "$CC_BIN" ] && [ -x "$CC_BIN" ]; then
  "$CC_BIN" -O2 -s "$ROOT/tools/daemonize.c" -o /tmp/daemonize
  GOOS=android GOARCH=arm GOARM=7 CGO_ENABLED=1 CC="$CC_BIN" go build -ldflags="-s -w" -o /tmp/tvconnect "$ROOT/tools/tvconnect.go"
  adb push /tmp/daemonize /data/local/tmp/daemonize
  adb push /tmp/tvconnect /data/local/tmp/tvconnect
  adb shell "chmod 755 /data/local/tmp/daemonize /data/local/tmp/tvconnect"
fi

adb push "$DIR/web_remote/remote.html" /data/local/tmp/remote.html

TOKEN=$(adb shell '
if [ ! -s /data/local/tmp/api_token.txt ]; then
  dd if=/dev/urandom bs=18 count=1 2>/dev/null | base64 | tr -dc "A-Za-z0-9" | head -c 24 > /data/local/tmp/api_token.txt
fi
cp /data/local/tmp/api_token.txt /sdcard/tv_connect_token.txt
cat /data/local/tmp/api_token.txt
' | tr -d '\r\n')

adb shell '
kill $(pgrep -x tvconnect 2>/dev/null) 2>/dev/null || true
/data/local/tmp/daemonize /data/local/tmp/tvconnect -addr :8088
sleep 1
'

HEALTH=$(adb shell 'curl -s http://127.0.0.1:8088/api/ping 2>/dev/null || true')
STB_IP=$(adb shell "ip -o -4 addr show wlan0 2>/dev/null || ip -o -4 addr show eth0 2>/dev/null" | awk '{print $4}' | cut -d/ -f1 | head -n 1 | tr -d '\r')
TS_IP=$(adb shell "ip -o -4 addr show tun0 2>/dev/null" | awk '{print $4}' | cut -d/ -f1 | head -n 1 | tr -d '\r')

echo "[+] Local Connection API : http://${STB_IP}:8088/?token=${TOKEN}"
[ -n "$TS_IP" ] && echo "[+] Tailscale Anywhere   : http://${TS_IP}:8088/?token=${TOKEN}  (ADB: adb connect ${TS_IP}:5555)"
echo "[+] API Token            : ${TOKEN} (stored at /sdcard/tv_connect_token.txt)"
