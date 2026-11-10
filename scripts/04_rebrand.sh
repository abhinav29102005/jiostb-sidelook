#!/bin/bash
# 04_rebrand.sh
# Rebrands the Jio STB identity (Device Name, Bluetooth Name, Cast Name)
# to a custom name of your choice.

CUSTOM_NAME="${1:-abhinav}"

echo "=== Rebranding Jio STB to: '$CUSTOM_NAME' ==="

# Set system device name (shows in About, Cast, and Settings)
echo "[*] Setting global device_name..."
adb shell "settings put global device_name '$CUSTOM_NAME'"

# Set Bluetooth broadcast name
echo "[*] Setting Bluetooth broadcast name..."
adb shell "settings put secure bluetooth_name '$CUSTOM_NAME'"

# Set lock screen owner info
echo "[*] Setting lock screen info..."
adb shell "settings put secure lock_screen_owner_info '$CUSTOM_NAME'"
adb shell "settings put secure lock_screen_owner_info_enabled 1"

# Disable annoying boot audio chime
echo "[*] Disabling stock boot audio..."
adb shell "settings put global boot_sound 0"
adb shell "settings put system boot_sound 0"

echo "[+] Done! Device is now branded as '$CUSTOM_NAME'."

