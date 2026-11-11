#!/usr/bin/env python3
"""
JioSTB Live Mirror & Remote Control Desktop Client
Works seamlessly over home LAN (192.168.1.10) OR any network worldwide via Tailscale (100.x.x.x).
"""

import argparse
import io
import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path
import tkinter as tk
from tkinter import ttk
from PIL import Image, ImageTk

CONFIG_PATH = Path.home() / ".config" / "jiostb_mirror.json"
DEFAULT_TOKEN = "43bqeSYTlOPeDo3gr70PmChs"
DEFAULT_LAN_HOST = "192.168.1.10"
PORT = 8088

APP_PACKAGES = {
    "Cinejoy": "com.abhinav.cinejoy",
    "SmartTube": "org.smarttube.stable",
    "NetMirror": "app.netmirror.newtv",
    "Stremio": "com.stremio.one",
    "TV Bro": "com.phlox.tvwebbrowser",
    "Aurora Store": "com.aurora.store",
    "Tailscale": "com.tailscale.ipn",
    "Miracast": "top.jast.miracastreceiver",
    "FCast": "com.futo.fcast.receiver",
    "Projectivy": "com.klevico.monet",
    "TV Connect": "com.abhinav.tvconnect",
    "Settings": "com.android.tv.settings",
}


def load_saved_config():
    try:
        if CONFIG_PATH.exists():
            return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except Exception:
        pass
    return {}


def save_config(base_url, token):
    try:
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(
            json.dumps({"base_url": base_url, "token": token}, indent=2),
            encoding="utf-8",
        )
    except Exception:
        pass


def discover_candidate_urls(explicit_host):
    candidates = []
    if explicit_host:
        h = explicit_host.strip()
        if not h.startswith("http://") and not h.startswith("https://"):
            h = f"http://{h}:{PORT}" if ":" not in h else f"http://{h}"
        candidates.append(h.rstrip("/"))
        return candidates

    cfg = load_saved_config()
    if cfg.get("base_url"):
        candidates.append(cfg["base_url"].rstrip("/"))

    # LAN STB IP first (lowest latency on local Wi-Fi)
    candidates.append(f"http://{DEFAULT_LAN_HOST}:{PORT}")

    # Any Android/JioSTB Tailscale peers (for off-network remote access)
    try:
        out = subprocess.check_output(
            ["tailscale", "status", "--json"], stderr=subprocess.DEVNULL, timeout=2.0
        )
        data = json.loads(out.decode("utf-8"))
        peers = (data.get("Peer") or {}).values()
        for p in peers:
            os_name = (p.get("OS") or "").lower()
            host_name = (p.get("HostName") or "").lower()
            ips = p.get("TailscaleIPs") or []
            v4 = next((ip for ip in ips if "." in ip), None)
            if not v4:
                continue
            if "android" in os_name or "jio" in host_name or "franklin" in host_name or "stb" in host_name:
                candidates.append(f"http://{v4}:{PORT}")
    except Exception:
        pass

    # Deduplicate preserving order
    seen = set()
    unique = []
    for c in candidates:
        if c not in seen:
            seen.add(c)
            unique.append(c)
    return unique


def probe_working_url(candidates, token):
    for base in candidates:
        try:
            url = f"{base}/api/health?token={urllib.parse.quote(token)}"
            with urllib.request.urlopen(url, timeout=1.8) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    if data.get("ok"):
                        return base, data
        except Exception:
            continue
    return None, None


class MirrorClientApp:
    def __init__(self, root, base_url, token, scale):
        self.root = root
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.scale = scale
        self.quality = 65

        self.root.title(f"JioSTB Live Mirror — {self.base_url}")
        self.root.geometry("1120x700")
        self.root.configure(bg="#080b12")

        self.frame_queue = queue.Queue(maxsize=2)
        self.stop_event = threading.Event()
        self.stream_gen = 0
        self.photo_ref = None
        self.img_item_id = None
        self.img_rect = (0, 0, 960, 540)
        self.drag_start = None
        self.direct_kbd = tk.BooleanVar(value=True)
        self.status_var = tk.StringVar(value=f"Connected: {self.base_url}")

        self._build_ui()
        self._send_api("/api/start_mirror")
        self._start_stream_thread()
        self.root.after(15, self._poll_frames)
        self.root.after(200, self._refresh_health_bg)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_ui(self):
        top = tk.Frame(self.root, bg="#121726", padx=8, pady=6)
        top.pack(fill=tk.X)

        self.host_entry = tk.Entry(top, width=26, bg="#0a0e1a", fg="#f1f5f9", insertbackground="#f1f5f9")
        self.host_entry.insert(0, self.base_url)
        self.host_entry.pack(side=tk.LEFT, padx=(0, 6))

        tk.Button(top, text="Reconnect", command=self._reconnect, bg="#3b82f6", fg="white", relief=tk.FLAT).pack(side=tk.LEFT, padx=3)

        self.scale_var = tk.StringVar(value=str(self.scale))
        q_menu = ttk.Combobox(top, textvariable=self.scale_var, values=["1", "2", "3"], width=3, state="readonly")
        q_menu.pack(side=tk.LEFT, padx=4)
        q_menu.bind("<<ComboboxSelected>>", lambda _e: self._change_scale())

        nav_buttons = [
            ("Power", 26), ("Home", 3), ("Back", 4), ("Menu", 82),
            ("Vol-", 25), ("Mute", 164), ("Vol+", 24), ("Play", 85),
        ]
        for label, code in nav_buttons:
            tk.Button(
                top, text=label,
                command=lambda c=code: self._send_api(f"/api/key?code={c}"),
                bg="#1c243b", fg="#f1f5f9", relief=tk.FLAT, padx=7
            ).pack(side=tk.LEFT, padx=2)

        self.app_var = tk.StringVar(value="Launch App...")
        app_menu = ttk.Combobox(top, textvariable=self.app_var, values=list(APP_PACKAGES.keys()), width=13, state="readonly")
        app_menu.pack(side=tk.LEFT, padx=6)
        app_menu.bind("<<ComboboxSelected>>", self._launch_selected_app)

        if shutil.which("ffplay"):
            tk.Button(
                top, text="30fps H264", command=self._open_ffplay,
                bg="#10b981", fg="#04130d", relief=tk.FLAT, padx=7
            ).pack(side=tk.LEFT, padx=3)

        bar2 = tk.Frame(self.root, bg="#0e1320", padx=8, pady=5)
        bar2.pack(fill=tk.X)

        tk.Checkbutton(
            bar2, text="Direct Laptop Keyboard (Arrows/Enter/Esc/Typing)",
            variable=self.direct_kbd, bg="#0e1320", fg="#93c5fd",
            selectcolor="#1c243b", activebackground="#0e1320"
        ).pack(side=tk.LEFT)

        tk.Label(bar2, text="  Send Text:", bg="#0e1320", fg="#94a3b8").pack(side=tk.LEFT, padx=(12, 4))
        self.text_entry = tk.Entry(bar2, width=28, bg="#0a0e1a", fg="#f1f5f9", insertbackground="#f1f5f9")
        self.text_entry.pack(side=tk.LEFT, padx=3)
        self.text_entry.bind("<Return>", lambda _e: self._send_text_box())
        tk.Button(bar2, text="Send", command=self._send_text_box, bg="#1c243b", fg="#f1f5f9", relief=tk.FLAT).pack(side=tk.LEFT, padx=2)

        tk.Label(bar2, textvariable=self.status_var, bg="#0e1320", fg="#94a3b8", anchor=tk.E).pack(side=tk.RIGHT, fill=tk.X, expand=True)

        self.canvas = tk.Canvas(self.root, bg="#000000", highlightthickness=0, cursor="crosshair")
        self.canvas.pack(fill=tk.BOTH, expand=True)

        self.canvas.bind("<ButtonPress-1>", self._on_mouse_down)
        self.canvas.bind("<ButtonRelease-1>", self._on_mouse_up)
        self.canvas.bind("<Button-3>", lambda _e: self._send_api("/api/key?code=4"))
        self.canvas.bind("<Button-4>", lambda _e: self._send_api("/api/key?code=19"))
        self.canvas.bind("<Button-5>", lambda _e: self._send_api("/api/key?code=20"))
        self.canvas.bind("<MouseWheel>", self._on_mouse_wheel)
        self.root.bind("<KeyPress>", self._on_key_press)

    def _set_status(self, text):
        if not self.stop_event.is_set():
            try:
                self.root.after(0, lambda: self.status_var.set(text))
            except Exception:
                pass

    def _send_api(self, path):
        def worker():
            sep = "&" if "?" in path else "?"
            url = f"{self.base_url}{path}{sep}token={urllib.parse.quote(self.token)}"
            try:
                with urllib.request.urlopen(url, timeout=3.0) as resp:
                    resp.read()
            except Exception as e:
                self._set_status(f"Err: {e}")
        threading.Thread(target=worker, daemon=True).start()

    def _refresh_health_bg(self):
        def worker():
            try:
                url = f"{self.base_url}/api/health?token={urllib.parse.quote(self.token)}"
                with urllib.request.urlopen(url, timeout=2.5) as resp:
                    d = json.loads(resp.read().decode("utf-8"))
                ts = d.get("tailscale_ip") or "off"
                wf = d.get("wifi_ip") or "-"
                self._set_status(f"WiFi: {wf} | Tailscale: {ts} | Scale 1/{self.scale}")
                save_config(self.base_url, self.token)
            except Exception:
                self._set_status(f"Reconnecting to {self.base_url}...")
        threading.Thread(target=worker, daemon=True).start()

    def _launch_selected_app(self, _e):
        name = self.app_var.get()
        pkg = APP_PACKAGES.get(name)
        if pkg:
            self._send_api(f"/api/launch?pkg={urllib.parse.quote(pkg)}")
        self.canvas.focus_set()

    def _send_text_box(self):
        txt = self.text_entry.get()
        if txt:
            self._send_api(f"/api/text?q={urllib.parse.quote(txt)}")
            self.text_entry.delete(0, tk.END)
        self.canvas.focus_set()

    def _reconnect(self):
        new_host = self.host_entry.get().strip().rstrip("/")
        if new_host and not new_host.startswith("http"):
            new_host = f"http://{new_host}:{PORT}"
        self.base_url = new_host
        self.stream_gen += 1
        self._refresh_health_bg()
        self.canvas.focus_set()

    def _change_scale(self):
        self.scale = int(self.scale_var.get())
        self.stream_gen += 1
        self._refresh_health_bg()
        self.canvas.focus_set()

    def _open_ffplay(self):
        url = f"{self.base_url}/api/mirror.h264?size=1280x720&bitrate=2500000&token={urllib.parse.quote(self.token)}"
        subprocess.Popen(["ffplay", "-fflags", "nobuffer", "-flags", "low_delay", "-framedrop", "-f", "h264", url])

    def _canvas_to_tv(self, cx, cy):
        rx, ry, rw, rh = self.img_rect
        if rw <= 0 or rh <= 0:
            return 960, 540
        nx = max(0, min(1919, int((cx - rx) * 1920 / rw)))
        ny = max(0, min(1079, int((cy - ry) * 1080 / rh)))
        return nx, ny

    def _on_mouse_down(self, ev):
        self.canvas.focus_set()
        self.drag_start = (ev.x, ev.y)

    def _on_mouse_up(self, ev):
        if not self.drag_start:
            return
        x0, y0 = self.drag_start
        self.drag_start = None
        if abs(ev.x - x0) + abs(ev.y - y0) > 18:
            tx1, ty1 = self._canvas_to_tv(x0, y0)
            tx2, ty2 = self._canvas_to_tv(ev.x, ev.y)
            self._send_api(f"/api/swipe?x1={tx1}&y1={ty1}&x2={tx2}&y2={ty2}&ms=240")
        else:
            tx, ty = self._canvas_to_tv(ev.x, ev.y)
            self._send_api(f"/api/tap?x={tx}&y={ty}")

    def _on_mouse_wheel(self, ev):
        self._send_api(f"/api/key?code={19 if ev.delta > 0 else 20}")

    def _on_key_press(self, ev):
        focus = self.root.focus_get()
        if focus in (self.host_entry, self.text_entry):
            return
        if not self.direct_kbd.get():
            return

        keymap = {
            "Up": 19, "Down": 20, "Left": 21, "Right": 22,
            "Return": 23, "KP_Enter": 66, "Escape": 4, "BackSpace": 67,
            "Home": 3, "Prior": 92, "Next": 93, "Delete": 67,
            "F1": 3, "F2": 82,
        }
        if ev.keysym in keymap:
            code = 66 if (ev.keysym == "Return" and (ev.state & 0x1)) else keymap[ev.keysym]
            self._send_api(f"/api/key?code={code}")
            return
        if ev.char and len(ev.char) == 1 and ord(ev.char) >= 32:
            self._send_api(f"/api/text?q={urllib.parse.quote(ev.char)}")

    def _start_stream_thread(self):
        def loop():
            while not self.stop_event.is_set():
                gen = self.stream_gen
                url = f"{self.base_url}/api/mirror.mjpeg?scale={self.scale}&q={self.quality}&token={urllib.parse.quote(self.token)}"
                try:
                    with urllib.request.urlopen(url, timeout=8.0) as resp:
                        buf = bytearray()
                        while not self.stop_event.is_set() and gen == self.stream_gen:
                            chunk = resp.read(16384)
                            if not chunk:
                                break
                            buf.extend(chunk)
                            while True:
                                soi = buf.find(b"\xff\xd8")
                                if soi < 0:
                                    if len(buf) > 65536:
                                        del buf[:-4]
                                    break
                                eoi = buf.find(b"\xff\xd9", soi + 2)
                                if eoi < 0:
                                    if soi > 0:
                                        del buf[:soi]
                                    break
                                jpg = bytes(buf[soi : eoi + 2])
                                del buf[: eoi + 2]
                                if self.frame_queue.full():
                                    try:
                                        self.frame_queue.get_nowait()
                                    except queue.Empty:
                                        pass
                                self.frame_queue.put(jpg)
                except Exception:
                    time.sleep(0.8)
        threading.Thread(target=loop, daemon=True).start()

    def _poll_frames(self):
        if self.stop_event.is_set():
            return
        latest = None
        while not self.frame_queue.empty():
            try:
                latest = self.frame_queue.get_nowait()
            except queue.Empty:
                break
        if latest is not None:
            try:
                cw = max(320, self.canvas.winfo_width())
                ch = max(180, self.canvas.winfo_height())
                img = Image.open(io.BytesIO(latest))
                iw, ih = img.size
                ratio = min(cw / iw, ch / ih)
                nw, nh = max(1, int(iw * ratio)), max(1, int(ih * ratio))
                rx = (cw - nw) // 2
                ry = (ch - nh) // 2
                self.img_rect = (rx, ry, nw, nh)
                resized = img.resize((nw, nh), Image.Resampling.BILINEAR)
                self.photo_ref = ImageTk.PhotoImage(resized)
                if self.img_item_id is None:
                    self.img_item_id = self.canvas.create_image(rx, ry, anchor=tk.NW, image=self.photo_ref)
                else:
                    self.canvas.coords(self.img_item_id, rx, ry)
                    self.canvas.itemconfig(self.img_item_id, image=self.photo_ref)
            except Exception:
                pass
        self.root.after(15, self._poll_frames)

    def _on_close(self):
        self.stop_event.set()
        self.root.destroy()


def main():
    ap = argparse.ArgumentParser(description="JioSTB Live Mirror & Control Desktop Client")
    ap.add_argument("--host", default="", help="STB IP/URL (default: auto-discover LAN or Tailscale)")
    ap.add_argument("--token", default="", help="API token")
    ap.add_argument("--scale", type=int, default=2, choices=[1, 2, 3], help="Downscale divisor (1=1080p, 2=540p, 3=360p)")
    args = ap.parse_args()

    cfg = load_saved_config()
    token = args.token or os.environ.get("TVCONNECT_TOKEN") or cfg.get("token") or DEFAULT_TOKEN
    candidates = discover_candidate_urls(args.host)
    working, _ = probe_working_url(candidates, token)
    base_url = working or candidates[0]
    if working:
        save_config(base_url, token)

    root = tk.Tk()
    MirrorClientApp(root, base_url, token, args.scale)
    root.mainloop()


if __name__ == "__main__":
    main()

