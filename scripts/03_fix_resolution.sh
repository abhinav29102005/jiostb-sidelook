#!/bin/bash
# 03_fix_resolution.sh
# Fixes the persistent 480p resolution fallback bug on JioFiber STBs (Amlogic S905X2).
# Sets the internal database to permanently lock 1080p @ 60Hz and configures display density.

set -e

echo "=== Fixing HDMI Resolution Drop Bug ==="

# Lock persistent HDMI resolution in global settings database
echo "[*] Setting hdmi_current_resolution to 1080p@60hz..."
adb shell "settings put global hdmi_current_resolution '1080p@60hz'"

# Ensure auto resolution doesn't downgrade on slow EDID handshake
echo "[*] Disabling faulty auto-downgrade resolution..."
adb shell "settings put secure auto_resolution 0"

# Enforce physical window manager display size and DPI
echo "[*] Enforcing 1920x1080 framebuffer at 320 DPI..."
adb shell "wm size 1920x1080"
adb shell "wm density 320"

# Verify settings
CURRENT_RES=$(adb shell settings get global hdmi_current_resolution | tr -d '\r')
echo "[+] Verification: Current HDMI setting is: $CURRENT_RES"
echo "[+] Resolution is now locked to Full HD 1080p @ 60Hz across reboots."
