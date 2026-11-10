#!/bin/bash
# stb_diagnostics.sh
# Diagnostic utility to inspect Jio STB state, ADB validity, display resolution, and bloat status.

echo "=== JioFiber STB Diagnostics ==="

if ! adb get-state 1>/dev/null 2>&1; then
    echo "[-] No ADB device connected."
    exit 1
fi

echo "[*] Device Model:      $(adb shell getprop ro.product.model | tr -d '\r')"
echo "[*] Android Version:   $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
echo "[*] Build Fingerprint: $(adb shell getprop ro.build.display.id | tr -d '\r')"
echo "[*] Bootloader State:  $(adb shell getprop ro.boot.vbmeta.device_state | tr -d '\r')"
echo "[*] Verified Boot:     $(adb shell getprop ro.boot.verifiedbootstate | tr -d '\r')"

echo -e "\n[*] ADB Expiration Date:"
echo "    $(adb shell getprop persist.iwedia.tr.adb.date | tr -d '\r')"

echo -e "\n[*] HDMI Current Resolution:"
echo "    $(adb shell settings get global hdmi_current_resolution | tr -d '\r')"

echo -e "\n[*] Active User Home Launcher:"
echo "    $(adb shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -n 1 | tr -d '\r')"

echo -e "\n[*] Bloatware Removal Check:"
for p in com.communitake.remotecontrolservice com.rjil.podlogservice com.jio.tr069client com.iwedia.httpupdate com.jio.store; do
    if adb shell pm list packages | grep -q "$p"; then
        echo "    [!] WARNING: $p is still active!"
    else
        echo "    [+] OK: $p is successfully removed."
    fi
done
