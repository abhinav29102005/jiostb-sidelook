# JioFiber STB Exploitation, Permanent ADB & Custom TV OS Guide (JHSD200 & JMS Series)

A complete, battle-tested guide to unlocking permanent ADB debugging on locked **Reliance JioFiber Set-Top Boxes** (Amlogic S905X2 / `franklin` / JioOS 2.0 / Android 11 & Android 9), stripping all Jio tracking and bloatware, and transforming it into an ad-free, high-performance custom TV experience.

Tested on **JHSD200** (firmware `STB-JHSD200-11.3.46`, Android 11, Security Patch June 2024), **JMSC200Av2**, and **JMSB200A**.

---

## ⚡ Key Achievements of This Exploit

1. **Permanent Network ADB**: Valid until year **2038** (`2038-01-01T05:29:59.000Z`) surviving reboots.
2. **Android 11 Restriction Bypass**: Bypasses `UserManager.DISALLOW_DEBUGGING_FEATURES` ("no developer options for this user").
3. **100% De-Jioification**: Completely removes 35+ packages:
   - **Remote Screen Viewing & Spyware**: CommuniTake (`com.communitake.remotecontrolservice`), PodLogService (`com.rjil.podlogservice`).
   - **Cloud Management**: TR-069 CPE daemons (`com.jio.tr069client`, `insight.tr069.client`).
   - **OTA Updates Blocked**: `com.iwedia.httpupdate`, `com.iwedia.updateservice`, `com.jio.store` neutralized.
4. **Resolution Fix**: Eliminates the persistent Amlogic 480p resolution drop bug and permanently locks **1080p @ 60Hz**.
5. **Custom TV Environment**:
   - Custom Launcher (Monet Launcher) with Joker/Cyberpunk theme.
   - Sideloaded streaming apps: **NetMirror TV**, **TV Bro** (ad-free browser), **SmartTube** (ad-free YouTube for TV), **Stremio**, and **Aurora Store**.
   - Physical Bluetooth Remote (`JIO BLE Remote`) 100% functional.
6. **Wireless Mobile Web Remote**: Zero-install mobile browser remote hosted directly on the TV (`http://<STB_IP>:8080`).

---

## 🔍 How the Exploit Works (Technical Deep Dive)

### 1. The Vulnerability: CWMP / TR-069 Trust Store Flaw
JioFiber STBs run a TR-069 CPE management client (`com.jio.tr069client`) that regularly contacts Jio's Auto-Configuration Server (ACS) over HTTPS.
- On Android 11 (`11.3.46`), the client's SSL trust manager accepts certificates signed by **eMudhra BV / AAA Certificate Services** (`forged_chain.pem`).
- By setting up a rogue Wi-Fi AP / hotspot with DNS spoofing, we redirect `jioacs.jiostb.com` and `ucast-key.iptv.jio.com` to our local host.
- A Python TLS reverse proxy (`tls/pass_front.py`) terminates the TLS handshake on ports `8443` and `9443` using the forged certificate chain and proxies the CWMP traffic to a local **GenieACS** server.

### 2. The Parameter Injection
Once the STB sends its `Inform` request, GenieACS queues a `SetParameterValues` RPC with:
```xml
Device.JioSTB.USB.USBDebugging = true (xsd:boolean)
Device.JioSTB.USB.DebugValidityDate = 2038-01-01T05:29:59.000Z (xsd:dateTime)
```

### 3. The Two-Boot NVRAM Cycle
- **Boot 1 (Provisioning)**: The STB receives the CWMP response, executes `TR069ClientService`, and writes:
  - `persist.iwedia.tr.adb.state = enabled`
  - `persist.iwedia.tr.adb.date = 2038-01-01T05:29:59.000Z`
  into persistent properties (`/data/property/persistent_properties`).
- **Boot 2 (Commit & Activation)**: A cold power-cycle forces the Linux `init` process to parse `/system/etc/init/hw/init.usb.rc`:
  ```rc
  on property:persist.iwedia.tr.adb.state=enabled && property:ro.debuggable=0
      setprop persist.sys.usb.config adb
      setprop sys.usb.config adb
  ```
  `adbd` starts listening on TCP port `5555`.

---

## 🚀 Step-by-Step Guide

### Step 1: Requirements & Setup
- A Linux or Windows PC with Wi-Fi capability.
- [Docker](https://www.docker.com/) & [Docker Compose](https://docs.docker.com/compose/) installed.
- [Python 3](https://www.python.org/) installed.
- [Android Platform Tools (ADB)](https://developer.android.com/tools/releases/platform-tools).

Clone this repository:
```bash
git clone https://github.com/abhinav29102005/jiostb-sidelook.git
cd jiostb-sidelook
```

---

### Step 2: Configure Rogue Wi-Fi Hotspot & DNS
1. Create a Wi-Fi hotspot on your PC (e.g. `jio-test-ap`, password: `password123`).
2. Configure DNS (`dnsmasq` or Technitium DNS) to redirect the following domains to your PC's IP:
   ```text
   jioacs.jiostb.com      -> YOUR_PC_IP
   ucast-key.iptv.jio.com -> YOUR_PC_IP
   acs.oss.jio.com        -> YOUR_PC_IP
   ```
3. Connect your Jio STB to this Wi-Fi hotspot.

---

### Step 3: Start GenieACS & TLS MitM Proxy
1. Start GenieACS and MongoDB in Docker:
   ```bash
   cd docker
   docker compose up -d
   cd ..
   ```
2. Launch the TLS termination proxy using the forged certificate chain:
   ```bash
   TLS_CERT=tls/forged_chain.pem python3 tls/pass_front.py --ports 8443,9443
   ```

---

### Step 4: Inject the Permanent ADB Task
1. Power cycle your STB so it connects to your hotspot and sends an `Inform` packet to your local GenieACS.
2. In a new terminal, run the task injection script:
   ```bash
   python3 scripts/01_unlock_adb.py
   ```
3. Once the proxy log confirms `SetParameterValues` has been sent and acknowledged by the STB with `SetParameterValuesResponse`:
4. **Cold reboot your STB**: Unplug the power cable from the wall, wait 5 seconds, and plug it back in.

---

### Step 5: Connect via ADB
Once the box boots up:
```bash
adb connect <STB_IP>:5555
adb devices
```
You should see:
```text
List of devices attached
<STB_IP>:5555    device
```

---

### Step 6: Full Debloat & Isolation
Run the automated debloating script to strip all 35+ bloatware, tracking, remote access, and updater packages:
```bash
./scripts/02_debloat_os.sh
```

---

### Step 7: Fix Resolution Drop (1080p Lock)
Jio STBs suffer from a bug where the internal database forces `480p@60hz` on cold power-on. Fix it permanently:
```bash
./scripts/03_fix_resolution.sh
```

---

### Step 8: Rebrand System & Disable Boot Chime
Rebrand your device name and bluetooth identity across the system:
```bash
./scripts/04_rebrand.sh "abhinav"
```

---

### Step 9: Install Custom TV Launcher & Apps
Install your desired apps via ADB:
```bash
# Custom Launcher (Monet Launcher)
adb install apps/monet-launcher.apk
adb shell cmd package set-home-activity com.klevico.monet/.HomeActivity

# Ad-Free YouTube (SmartTube)
adb install apps/SmartTube_stable.apk

# Google Play Store Client (Aurora Store)
adb install apps/AuroraStore.apk

# NetMirror TV
adb install apps/NetMirrorTV.apk

# Open-Source Ad-Free TV Browser (TV Bro)
adb install apps/tvbro.apk
```

---

### Step 10: Wireless Mobile Web Remote (Zero-Install)
Deploy the lightweight web remote server to control your TV from any phone browser on your Wi-Fi:
```bash
./scripts/05_setup_web_remote.sh
```
Open on your mobile phone:
```text
http://<STB_IP>:8088/?token=<TOKEN>
```
*(Tap **Add to Home Screen** on your phone to turn it into a web remote with live screen capture, text input, app launcher, and haptic D-pad controls).*

---

## 🌐 Step 6: Anywhere Connection API & Remote Access (Over Any Network)

To control the STB from **any Wi-Fi or 4G/5G cellular network worldwide** without port forwarding or leaving a PC on:

1. Deploy the native Go Connection API server (`tools/tvconnect.go`) on port `8088`:
   ```bash
   ./scripts/06_setup_anywhere_api.sh
   ```
   This compiles & starts `tvconnect` (with `tools/daemonize.c`) and writes a unique API token to `/sdcard/tv_connect_token.txt`.
2. Install **Tailscale** (`com.tailscale.ipn`) on the STB and sign in once to join your private encrypted tailnet (`100.x.x.x`).
3. From anywhere on any network, access:
   * **Web Dashboard & Live Screen:** `http://<TAILSCALE_IP>:8088/?token=<TOKEN>`
   * **Remote ADB:** `adb connect <TAILSCALE_IP>:5555`
   * **REST API Endpoints** (authenticate via `?token=<TOKEN>`, `X-API-Key: <TOKEN>`, or `Authorization: Bearer <TOKEN>`):
     * `GET /api/ping` — unauthenticated liveness check
     * `GET /api/health` — device model, Wi-Fi IP, Tailscale IP, active IME
     * `GET /api/key?code=23` — send Android keycode (`3`=Home, `4`=Back, `19/20/21/22`=D-Pad, `23`=OK, `24/25`=Vol, `85`=Play/Pause)
     * `GET /api/text?q=hello` — type text into focused TV input
     * `GET /api/tap?x=960&y=540` / `GET /api/swipe?x1=...&y1=...&x2=...&y2=...` — touch injection
     * `GET /api/launch?pkg=com.abhinav.cinejoy` — launch any app
     * `GET /api/open?url=https://...` — open URL on TV
     * `GET /api/apps` — list installed 3rd-party packages
     * `GET /api/screen` — live `image/png` TV screenshot

### Additional Installed Stack
* **On-Screen Keyboard:** LeanKeyboard (`org.liskovsoft.androidtv.rukeyboard/com.liskovsoft.leankeyboard.ime.LeanbackImeService`) set as active `default_input_method` with `show_ime_with_hard_keyboard=1`.
* **Installer Permissions:** `package_verifier_enable=0`, `install_non_market_apps=1`, `REQUEST_INSTALL_PACKAGES` + `MANAGE_EXTERNAL_STORAGE` allowed for Aurora Store, plus **Shizuku** (`moe.shizuku.privileged.api`) running over ADB.
* **Casting & Mirroring:** **Google Cast** (`com.google.android.apps.mediashell` updated to `v3.72.446070`), **Miracast Receiver** (`top.jast.miracastreceiver`), and **FCast Receiver** (`com.futo.fcast.receiver`).
* **Custom TV Apps:** Dedicated `Cinejoy` (`com.abhinav.cinejoy`, source in `tools/cinejoy_tv/`) wrapper for `https://cinejoy.pk/` with Leanback TV banner & D-pad virtual cursor.

---

## 🔒 Bootloader & Hardware Architecture Notes

### 1. Why a "100% Wiped Hardware Custom ROM" Cannot Be Flashed Over Software
Many users ask: *"Can I flash LineageOS, AOSP GSI, or SlimBOXtv directly over fastboot or USB Burning Tool?"*

```text
[ro.boot.vbmeta.device_state]: [locked]
[ro.boot.verifiedbootstate]:   [green]
[ro.boot.vbmeta.invalidate_on_error]: [yes]
```

* **Hardware eFuse Protection**: The Amlogic S905X2 SoC has write-once hardware eFuses burned by Reliance at the factory containing their private RSA master keys.
* **Cryptographic Signature Checks**: The hardware bootloader (`u-boot`) will **only execute partitions signed by Reliance Jio's private key**.
* **What Happens if You Force-Flash an Unsigned ROM**: The bootloader checks the cryptographic hash tree on power-up. Because custom ROMs (like SlimBOX or LineageOS) are not signed with Reliance's private key, the signature check fails immediately. With `invalidate_on_error: yes`, the processor halts boot permanently. The box becomes a **hard-bricked dead brick with no display and no recovery**.
* **The Systemless Solution**: The TR-069 exploit and ADB debloat documented here give you 100% of the benefits (clean launcher, ad-free streaming, zero Jio tracking, permanent ADB) while keeping the cryptographically signed bootloader and hardware drivers safe and intact.

---

### 2. The Hardware-Only Modification Method (Advanced Tinkering)
For developers who want true low-level access to the Amlogic bootloader:

* **Hardware Prerequisites**:
  * USB-to-TTL Serial UART Adapter (CH340, CP2102, or FT232RL).
  * Fine-tip soldering iron or pogo pins.
  * Male-to-Male USB-A cable for Amlogic USB Burning Tool.
* **UART Serial Console Access**:
  1. Open the JHSD200 plastic casing (held by bottom plastic clips).
  2. Locate the 4 unpopulated UART pads on the PCB near the SoC:
     * `GND` $\rightarrow$ Connect to Adapter GND
     * `TX` $\rightarrow$ Connect to Adapter RX
     * `RX` $\rightarrow$ Connect to Adapter TX
     * `VCC (3.3V)` $\rightarrow$ **DO NOT CONNECT** (box is powered by its 12V adapter)
  3. Open a serial terminal on your PC:
     ```bash
     minicom -D /dev/ttyUSB0 -b 115200
     ```
  4. Power on the STB and press `Enter` or `Space` rapidly to interrupt u-boot and drop into the `g12a_franklin#` u-boot prompt.
* **eMMC Clock Pin Shorting (Mask ROM Mode)**:
  * If the bootloader console is locked or password-protected, locate the eMMC clock resistor (`CLK` to `GND`) near the eMMC NAND flash chip.
  * Short the `CLK` pin to ground using tweezers while plugging in the USB-A cable connected to your PC.
  * This forces the Amlogic boot ROM into **WorldCup Device mode** (`ID 1b8e:0c00`), allowing the Amlogic USB Burning Tool to interface directly with RAM.

---

### 3. Daily Usage: How to Never See the Jio Boot Screen
The static Jio boot logo and startup animation only execute during a **cold boot** (when the physical 12V power adapter is unplugged from the wall).

In normal everyday TV usage:
* **Press the Power button on your remote**: The STB enters **instant Standby / Sleep** mode (HDMI and video processing sleep, box draws ~0.5W).
* **Press Power again**: The TV **wakes up instantly in 0.5 seconds directly into Monet Launcher / Abhinav OS** with zero boot logos, zero loading delays, and zero Jio branding.

---

## 🛠️ Diagnostics
To verify your box's status at any time, run:
```bash
./tools/stb_diagnostics.sh
```

---

## 📜 License
This project is open-source under the [MIT License](LICENSE).
