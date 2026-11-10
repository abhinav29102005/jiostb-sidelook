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
http://<STB_IP>:8080
```
*(Tap **Add to Home Screen** on your phone to turn it into an app with haptic touch controls).*

---

## 🔒 Bootloader & Hardware Architecture Notes

### Why Flashing Raw Partitions Bricks the Box
Many users ask: *"Can I flash LineageOS, AOSP GSI, or SlimBOXtv directly over fastboot or USB Burning Tool?"*

```text
[ro.boot.vbmeta.device_state]: [locked]
[ro.boot.verifiedbootstate]:   [green]
[ro.boot.vbmeta.invalidate_on_error]: [yes]
```

* The **Amlogic S905X2** SoC has write-once hardware **eFuses** burned at the factory with Reliance Jio's private RSA keys.
* Android Verified Boot (AVB 1.1) and dm-verity cryptographically sign `/dev/block/super` (`system`, `vendor`, `product`) and `boot`.
* If you overwrite partitions with an unsigned generic custom ROM over software, the hardware bootloader detects a checksum failure and **permanently halts boot**. The box becomes a **hard-bricked dead brick**.
* The **Systemless Debloating Method** documented here gives you 100% of the benefits (clean launcher, ad-free streaming, zero Jio tracking, permanent ADB) while keeping the signed hardware drivers stable and safe.

---

## 🛠️ Diagnostics
To verify your box's status at any time, run:
```bash
./tools/stb_diagnostics.sh
```

---

## 📜 License
This project is open-source under the [MIT License](LICENSE).
