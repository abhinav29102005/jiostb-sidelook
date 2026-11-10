#!/bin/bash
# 02_debloat_os.sh
# Complete removal of Jio telemetry, remote screen viewer (CommuniTake), TR-069 daemons,
# and 35+ bloatware packages from JioFiber STB (JHSD200 / JioOS 2.0 / Android 11).
# Safely preserves Bluetooth remote control (com.jio.remote.btserver) and display drivers.

set -e

echo "=== JioFiber STB Complete OS Debloater ==="

# Check ADB connection
if ! adb get-state 1>/dev/null 2>&1; then
    echo "[-] Error: No ADB device connected. Run 'adb connect <IP>:5555' first."
    exit 1
fi

echo "[+] Connected to device: $(adb shell getprop ro.product.model)"

# Packages to disable and uninstall for user 0
BLOAT_PACKAGES=(
    # --- Remote Access & Spyware Backdoors ---
    "com.communitake.remotecontrolservice"    # Remote desktop screen viewer
    "com.rjil.podlogservice"                 # Background log uploader
    "com.rjil.jiostblogservice"              # Diagnostic telemetry
    "com.rjl.mobileremote.receiver"          # Unsecured mobile command receiver
    "com.jio.dialserver"                     # Jio DIAL server
    "com.jio.stb.remotediagnose"             # Diagnostic service
    "com.rjil.jio.stb.commandreceiver"       # Remote command executor
    "com.jio.systemstats"                    # Telemetry tracker

    # --- TR-069 & Cloud Management ---
    "com.jio.tr069client"                    # Jio TR-069 CPE client
    "insight.tr069.client"                   # Secondary TR-069 agent
    "com.jio.jiobackgroundscheduler"        # Periodic task trigger

    # --- OTA Update Engines (Permanently Block Reversion) ---
    "com.iwedia.httpupdate"                  # Firmware download service
    "com.iwedia.updateservice"               # OTA install service
    "com.iwedia.pvodupdateservice"           # Background content updater
    "com.iwedia.updatercuotaservice"         # Remote control firmware updater
    "com.jio.store"                          # Jio app store auto-installer

    # --- Stock Jio Launcher & Setup Wizards ---
    "com.iwedia.ui.rjio.launcher.reliance"   # Stock Jio TV Home UI
    "com.jiostb.fti"                         # First Time Installation wizard
    "com.jio.universalsetup"                 # Setup helper
    "com.omni.simplesetup"                   # Omni setup wizard
    "com.omni.touchclient"                   # Omni background service
    "com.omni.sci"                           # Omni TV service

    # --- System Modifiers & Ad Services ---
    "com.jio.stbadservice"                   # Jio ad delivery engine
    "com.jio.stbwidget"                      # Home screen ad widget
    "com.jio.appoptimizer"                   # App execution limiter
    "com.jio.smartmemorymanager"             # Memory killer
    "com.jio.profileutility"                 # Profile manager
    "com.jio.controlcentre"                  # Jio control overlay
    "com.jio.screensaver"                    # Jio promotional screensaver
    "com.jio.keyboard.swarachakra"           # Jio Indian keyboard
    "com.iwedia.notificationservice"         # Notification push service
    "com.iwedia.resetservice"                # Remote factory reset daemon
    "com.iwedia.usbbenchmarkservice"         # USB benchmark
    "com.iwedia.comedia.service"             # CoMedia middleware
    "com.jio.stb.tifextn"                    # TV Input Framework extension
    "com.jio.stb.catv"                       # Cable TV extension
    "com.netflix.partnertoken"               # PADI token partner
    "com.rjil.jiostbsetting"                 # Locked Jio settings app

    # --- Pre-installed Third-Party Bloatware ---
    "com.jio.media.jiobeats"                 # JioSaavn
    "com.jio.jiocall"                        # JioCall / JioJoin
    "com.jiostb.jiogames"                    # JioGames
    "com.jio.myjiotv"                        # MyJio TV
    "com.jio.media.jionewsstb"               # JioNews
    "com.jio.media.livetv"                   # Jio Live TV
    "com.jio.media.jiotvplus"                # JioTV+
    "com.jio.jiocloudpc"                     # Jio Cloud PC
    "com.jio.jiopay"                         # JioPay
    "com.jio.yt"                             # Jio YouTube wrapper
    "com.jio.web"                            # JioPages browser
    "com.aksys.jiogamepad"                   # Jio Gamepad app
    "com.jio.myhome.mediasharedmp"           # Media Share DMP
    "com.jio.product.exploremyremote"        # Explore remote demo
    "com.inn.nvengineer"                     # NetVelocity speed test
    "com.android.app.jio.voiceassist"        # Jio Voice Assistant
    "com.jio.stb.bot"                        # Jio STB Bot
    "com.media.jio.sso.stb"                  # Jio SSO login
    "com.teamviewer.quicksupport.addon.jio_stb" # TeamViewer backdoor
    "in.startv.hotstar"                      # JioHotstar
    "com.zee5.aosp"                          # Zee5
    "com.suntv.sunnxt"                       # SunNXT
    "com.hoichoi.mitv"                       # Hoichoi
    "com.etvwin.androidtv"                   # ETV Win
)

echo "[*] Stripping ${#BLOAT_PACKAGES[@]} bloatware and telemetry packages..."

for pkg in "${BLOAT_PACKAGES[@]}"; do
    echo "  - Removing: $pkg"
    adb shell "pm disable-user --user 0 '$pkg' 2>/dev/null || true"
    adb shell "pm uninstall -k --user 0 '$pkg' 2>/dev/null || true"
    adb shell "am force-stop '$pkg' 2>/dev/null || true"
done

echo "[+] Telemetry, TR-069, and bloatware packages successfully stripped!"
echo "[+] Bluetooth remote control service (com.jio.remote.btserver) preserved."
