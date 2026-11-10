#!/usr/bin/env python3
"""
01_unlock_adb.py
Automates the GenieACS task injection to permanently enable ADB on JioFiber STBs (JHSD200 & JMS series).
Bypasses the GenieACS caching trap by clearing the existing USBDebugging cache and queuing the parameter values.
"""

import sys
import json
import urllib.request
import urllib.parse

GENIE_NBI_URL = "http://127.0.0.1:7557"

def inject_adb_task(device_id="A38021-JHSD200-RFLSBCGE2028682"):
    encoded_dev = urllib.parse.quote(device_id)
    url = f"{GENIE_NBI_URL}/devices/{encoded_dev}/tasks?connection_request"
    
    # Expiration date set to year 2038
    validity_date = "2038-01-01T05:29:59.000Z"
    
    task_payload = {
        "name": "setParameterValues",
        "parameterValues": [
            ["Device.JioSTB.USB.USBDebugging", True, "xsd:boolean"],
            ["Device.JioSTB.USB.DebugValidityDate", validity_date, "xsd:dateTime"]
        ]
    }
    
    req = urllib.request.Request(
        url,
        data=json.dumps(task_payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            print(f"[+] Task successfully queued on GenieACS! Status: {resp.status}")
            print(f"    Injected: Device.JioSTB.USB.USBDebugging = True")
            print(f"    Injected: Device.JioSTB.USB.DebugValidityDate = {validity_date}")
            print("\n[!] Next Steps:")
            print("    1. Reboot the STB once to receive the parameters and save them to NVRAM.")
            print("    2. Cold power-cycle (unplug & replug) to parse persist.iwedia.tr.adb.state at boot.")
            print("    3. Connect: adb connect <STB_IP>:5555")
    except Exception as e:
        print(f"[-] Error queuing task: {e}")
        print("    Ensure GenieACS NBI service is running on port 7557.")

if __name__ == "__main__":
    dev_id = sys.argv[1] if len(sys.argv) > 1 else "A38021-JHSD200-RFLSBCGE2028682"
    inject_adb_task(dev_id)
