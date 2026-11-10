#!/bin/bash
# 05_setup_web_remote.sh
# Deploys a zero-install, lightweight wireless TV remote server onto the STB on port 8080.
# Any smartphone connected to the local Wi-Fi can open http://<STB_IP>:8080 to navigate.

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Deploying Wireless Web Remote to STB ==="

# Push UI and handler
adb push "$DIR/web_remote/remote.html" /data/local/tmp/remote.html
adb push "$DIR/web_remote/handle_remote.sh" /data/local/tmp/handle_remote.sh
adb shell "chmod 755 /data/local/tmp/handle_remote.sh"

# Create startup loop daemon
adb shell "cat << 'EOF' > /data/local/tmp/start_remote_server.sh
#!/system/bin/sh
while true; do
  toybox nc -L -p 8080 /system/bin/sh /data/local/tmp/handle_remote.sh
  sleep 1
done
EOF
chmod 755 /data/local/tmp/start_remote_server.sh
nohup /system/bin/sh /data/local/tmp/start_remote_server.sh >/dev/null 2>&1 &
"

STB_IP=$(adb shell "ip -o -4 addr show wlan0 2>/dev/null || ip -o -4 addr show eth0 2>/dev/null" | awk '{print $4}' | cut -d/ -f1 | head -n 1)

echo "[+] Web Remote successfully deployed and running!"
echo "[+] Open this URL on your phone's browser: http://$STB_IP:8080"
echo "    Tip: Tap 'Add to Home Screen' in Chrome or Safari for a full-screen app!"
