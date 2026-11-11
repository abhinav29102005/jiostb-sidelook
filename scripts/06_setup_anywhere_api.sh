#!/bin/bash
# 06_setup_anywhere_api.sh
# Compiles and deploys:
#   1. Native Go Live Mirror & Control Gateway (Port 8088: MJPEG + hardware H.264 + ordered input)
#   2. Reboot-persistent Android APK + AccessibilityService (com.abhinav.tvconnect, Port 8089 + :8088 fallback + remote remaps)
# Works over home LAN and from anywhere via Tailscale (com.tailscale.ipn).

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
TARGET="${ADB_TARGET:-192.168.1.10:5555}"

adb connect "$TARGET" >/dev/null 2>&1 || true

echo "=== 1. Building / Deploying Native Go Live Mirror Gateway (Port 8088) ==="
CC_BIN="${ANDROID_NDK_CC:-$(find "${ANDROID_HOME:-/mnt/storage/Android}/ndk" "$HOME/Android/ndk" -name "armv7a-linux-androideabi24-clang" 2>/dev/null | sort -V | tail -n 1)}"
if command -v go >/dev/null 2>&1 && [ -n "$CC_BIN" ] && [ -x "$CC_BIN" ]; then
  "$CC_BIN" -O2 -s "$ROOT/tools/daemonize.c" -o /tmp/daemonize
  GOOS=android GOARCH=arm GOARM=7 CGO_ENABLED=1 CC="$CC_BIN" go build -ldflags="-s -w" -o /tmp/tvconnect "$ROOT/tools/tvconnect.go"
  adb -s "$TARGET" push /tmp/daemonize /data/local/tmp/daemonize
  adb -s "$TARGET" push /tmp/tvconnect /data/local/tmp/tvconnect
  adb -s "$TARGET" shell "chmod 755 /data/local/tmp/daemonize /data/local/tmp/tvconnect"
fi

adb -s "$TARGET" push "$DIR/web_remote/remote.html" /data/local/tmp/remote.html
adb -s "$TARGET" push "$DIR/web_remote/remote.html" /sdcard/remote.html

TOKEN=$(adb -s "$TARGET" shell '
if [ ! -s /data/local/tmp/api_token.txt ]; then
  dd if=/dev/urandom bs=18 count=1 2>/dev/null | base64 | tr -dc "A-Za-z0-9" | head -c 24 > /data/local/tmp/api_token.txt
fi
cp /data/local/tmp/api_token.txt /sdcard/tv_connect_token.txt
cat /data/local/tmp/api_token.txt
' | tr -d '\r\n')

if [ -x "$ROOT/tools/tvconnect_app/build_apk.sh" ] && find "${ANDROID_HOME:-/mnt/storage/Android}/build-tools" -name "apksigner" 2>/dev/null | grep -q .; then
  echo "=== 2. Building / Installing Reboot-Persistent com.abhinav.tvconnect APK ==="
  APK=$("$ROOT/tools/tvconnect_app/build_apk.sh")
  adb -s "$TARGET" install -r -g "$APK" >/dev/null
  adb -s "$TARGET" shell '
    appops set com.abhinav.tvconnect MANAGE_EXTERNAL_STORAGE allow
    dumpsys deviceidle whitelist +com.abhinav.tvconnect >/dev/null
    CUR=$(settings get secure enabled_accessibility_services)
    SVC="com.abhinav.tvconnect/.TvAccessibilityService"
    case "$CUR" in
      *"$SVC"*) NEW="$CUR" ;;
      ""|"null") NEW="$SVC" ;;
      *) NEW="$CUR:$SVC" ;;
    esac
    settings put secure enabled_accessibility_services "$NEW"
    settings put secure accessibility_enabled 1
  '
fi

STB_IP=$(adb -s "$TARGET" shell "ip -o -4 addr show wlan0 2>/dev/null || ip -o -4 addr show eth0 2>/dev/null" | awk '{print $4}' | cut -d/ -f1 | head -n 1 | tr -d '\r')
TS_IP=$(adb -s "$TARGET" shell "ip -o -4 addr show tun0 2>/dev/null" | awk '{print $4}' | cut -d/ -f1 | head -n 1 | tr -d '\r')

# Ask fallback APK on :8089 to yield :8088 if it was holding :8088 after a reboot.
if [ -n "$STB_IP" ] && command -v curl >/dev/null 2>&1; then
  curl -fsS --max-time 2 "http://${STB_IP}:8089/api/yield?token=${TOKEN}" >/dev/null 2>&1 || true
fi

adb -s "$TARGET" shell '
kill $(pgrep -x tvconnect 2>/dev/null) 2>/dev/null || true
sleep 0.3
/data/local/tmp/daemonize /data/local/tmp/tvconnect -addr :8088
SHIZUKU_BIN=$(find /data/app -name libshizuku.so 2>/dev/null | head -n 1)
if [ -n "$SHIZUKU_BIN" ] && ! pgrep -f shizuku >/dev/null 2>&1; then
  "$SHIZUKU_BIN" >/dev/null 2>&1 &
fi
sleep 1
'

echo "[+] Local Mirror Gateway : http://${STB_IP}:8088/?token=${TOKEN}"
[ -n "$TS_IP" ] && echo "[+] Tailscale Anywhere   : http://${TS_IP}:8088/?token=${TOKEN}  (ADB: adb connect ${TS_IP}:5555)"
echo "[+] Reboot Fallback API  : http://${STB_IP}:8089/?token=${TOKEN}  (always-on AccessibilityService)"
echo "[+] Desktop Mirror App   : python3 \"$ROOT/tools/laptop_mirror_client.py\""
echo "[+] API Token            : ${TOKEN} (stored at /sdcard/tv_connect_token.txt)"
