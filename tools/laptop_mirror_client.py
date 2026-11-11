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


RES_PRESETS = {
    "720p HD": (2, 80, "720p"),
    "1080p Full HD": (1, 84, "1080p"),
    "540p Smooth": (3, 72, "540p"),
    "360p Eco": (4, 62, "360p"),
}
SCALE_TO_LABEL = {v[0]: k for k, v in RES_PRESETS.items()}


class MirrorClientApp:
    def __init__(self, root, base_url, token, scale):
        self.root = root
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.scale = scale if scale in SCALE_TO_LABEL else 2
        self.quality = RES_PRESETS[SCALE_TO_LABEL[self.scale]][1]
        self.res_tag = RES_PRESETS[SCALE_TO_LABEL[self.scale]][2]

        self.root.title(f"JioSTB Live Mirror — {self.base_url}")
        self.root.geometry("1280x780")
        self.root.configure(bg="#080b12")

        self.raw_queue = queue.Queue(maxsize=2)
        self.decoded_queue = queue.Queue(maxsize=2)
        self.stop_event = threading.Event()
        self.stream_gen = 0
        self.photo_ref = None
        self.img_item_id = None
        self.img_rect = (0, 0, 1280, 720)
        self.canvas_wh = (1280, 720)
        self.video_wh = (1280, 720)
        self.drag_start = None
        self.direct_kbd = tk.BooleanVar(value=True)
        self.stb_cursor_var = tk.BooleanVar(value=True)
        self.status_var = tk.StringVar(value=f"Connected: {self.base_url}")

        self.fps_count = 0
        self.fps_t0 = time.time()
        self.cur_fps = 0.0
        self.last_cursor_sync = 0.0
        self.last_cursor_tv = (960, 540)

        self.cursor_shadow_id = None
        self.cursor_poly_id = None
        self.drag_line_id = None
        self.ripple_id = None
        self.hud_bg_id = None
        self.hud_txt_id = None

        self._build_ui()
        self._send_api(f"/api/start_mirror?scale={self.scale}&res={self.res_tag}&q={self.quality}")
        self._start_stream_thread()
        self._start_decode_thread()
        self.root.after(8, self._poll_frames)
        self.root.after(200, self._refresh_health_bg)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_ui(self):
        top = tk.Frame(self.root, bg="#121726", padx=8, pady=6)
        top.pack(fill=tk.X)

        self.host_entry = tk.Entry(top, width=25, bg="#0a0e1a", fg="#f1f5f9", insertbackground="#f1f5f9")
        self.host_entry.insert(0, self.base_url)
        self.host_entry.pack(side=tk.LEFT, padx=(0, 6))

        tk.Button(top, text="Reconnect", command=self._reconnect, bg="#3b82f6", fg="white", relief=tk.FLAT).pack(side=tk.LEFT, padx=3)

        self.res_var = tk.StringVar(value=SCALE_TO_LABEL.get(self.scale, "720p HD"))
        q_menu = ttk.Combobox(top, textvariable=self.res_var, values=list(RES_PRESETS.keys()), width=14, state="readonly")
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
                top, text="H264", command=self._open_ffplay,
                bg="#10b981", fg="#04130d", relief=tk.FLAT, padx=7
            ).pack(side=tk.LEFT, padx=3)

        bar2 = tk.Frame(self.root, bg="#0e1320", padx=8, pady=5)
        bar2.pack(fill=tk.X)

        tk.Checkbutton(
            bar2, text="Direct Keyboard",
            variable=self.direct_kbd, bg="#0e1320", fg="#93c5fd",
            selectcolor="#1c243b", activebackground="#0e1320"
        ).pack(side=tk.LEFT)

        tk.Checkbutton(
            bar2, text="TV Cursor",
            variable=self.stb_cursor_var, command=self._toggle_stb_cursor,
            bg="#0e1320", fg="#6ee7b7", selectcolor="#1c243b", activebackground="#0e1320"
        ).pack(side=tk.LEFT, padx=(8, 0))

        tk.Label(bar2, text="  Send Text:", bg="#0e1320", fg="#94a3b8").pack(side=tk.LEFT, padx=(10, 4))
        self.text_entry = tk.Entry(bar2, width=26, bg="#0a0e1a", fg="#f1f5f9", insertbackground="#f1f5f9")
        self.text_entry.pack(side=tk.LEFT, padx=3)
        self.text_entry.bind("<Return>", lambda _e: self._send_text_box())
        tk.Button(bar2, text="Send", command=self._send_text_box, bg="#1c243b", fg="#f1f5f9", relief=tk.FLAT).pack(side=tk.LEFT, padx=2)

        tk.Label(bar2, textvariable=self.status_var, bg="#0e1320", fg="#94a3b8", anchor=tk.E).pack(side=tk.RIGHT, fill=tk.X, expand=True)

        self.canvas = tk.Canvas(self.root, bg="#000000", highlightthickness=0, cursor="arrow")
        self.canvas.pack(fill=tk.BOTH, expand=True)
        self.canvas.bind("<Configure>", self._on_canvas_resize)

        # Layered canvas items: tv_img -> drag_line -> ripple -> cursor_shadow -> cursor_poly -> hud
        self.drag_line_id = self.canvas.create_line(0, 0, 0, 0, fill="#60a5fa", width=2.5, dash=(5, 4), state=tk.HIDDEN, tags=("cursor_layer",))
        self.ripple_id = self.canvas.create_oval(0, 0, 0, 0, outline="#60a5fa", width=2.5, state=tk.HIDDEN, tags=("cursor_layer",))
        self.cursor_shadow_id = self.canvas.create_polygon(0, 0, fill="#020617", outline="", state=tk.HIDDEN, tags=("cursor_layer",))
        self.cursor_poly_id = self.canvas.create_polygon(0, 0, fill="#ffffff", outline="#0f172a", width=1.8, state=tk.HIDDEN, tags=("cursor_layer",))
        self.hud_bg_id = self.canvas.create_rectangle(10, 10, 260, 34, fill="#0b101b", outline="#334155", width=1, tags=("hud_layer",))
        self.hud_txt_id = self.canvas.create_text(20, 22, anchor=tk.W, text="Connecting HD stream...", fill="#e2e8f0", font=("Sans", 10, "bold"), tags=("hud_layer",))

        self.canvas.bind("<Motion>", self._on_mouse_move)
        self.canvas.bind("<Leave>", self._on_mouse_leave)
        self.canvas.bind("<ButtonPress-1>", self._on_mouse_down)
        self.canvas.bind("<B1-Motion>", self._on_mouse_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_mouse_up)
        self.canvas.bind("<Button-2>", lambda _e: self._send_api("/api/key?code=3"))
        self.canvas.bind("<Button-3>", lambda _e: self._send_api("/api/key?code=4"))
        self.canvas.bind("<Button-4>", lambda _e: self._send_api("/api/key?code=19"))
        self.canvas.bind("<Button-5>", lambda _e: self._send_api("/api/key?code=20"))
        self.canvas.bind("<MouseWheel>", self._on_mouse_wheel)
        self.root.bind("<KeyPress>", self._on_key_press)

    def _on_canvas_resize(self, ev):
        self.canvas_wh = (max(320, ev.width), max(180, ev.height))

    def _toggle_stb_cursor(self):
        en = 1 if self.stb_cursor_var.get() else 0
        self._send_api(f"/api/cursor?enabled={en}&visible={en}")

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
        preset = RES_PRESETS.get(self.res_var.get(), (2, 80, "720p"))
        self.scale, self.quality, self.res_tag = preset
        self.stream_gen += 1
        self._send_api(f"/api/start_mirror?scale={self.scale}&res={self.res_tag}&q={self.quality}")
        self._refresh_health_bg()
        self.canvas.focus_set()

    def _open_ffplay(self):
        url = f"{self.base_url}/api/mirror.h264?size=1280x720&bitrate=3500000&token={urllib.parse.quote(self.token)}"
        subprocess.Popen(["ffplay", "-fflags", "nobuffer", "-flags", "low_delay", "-framedrop", "-f", "h264", url])

    def _canvas_to_tv(self, cx, cy):
        rx, ry, rw, rh = self.img_rect
        if rw <= 0 or rh <= 0:
            return 960, 540, False
        inside = (rx <= cx <= rx + rw) and (ry <= cy <= ry + rh)
        nx = max(0, min(1919, int((cx - rx) * 1920 / rw)))
        ny = max(0, min(1079, int((cy - ry) * 1080 / rh)))
        return nx, ny, inside

    def _draw_cursor_at(self, cx, cy, pressed=False):
        s = 0.86 if pressed else 1.0
        pts = [
            (0, 0), (0, 20), (5, 15), (9, 23), (12, 21.5), (8, 13.5), (15, 13.5)
        ]
        coords = []
        sh_coords = []
        for px, py in pts:
            coords.extend([cx + px * s, cy + py * s])
            sh_coords.extend([cx + px * s + 1.5, cy + py * s + 2.0])
        self.canvas.coords(self.cursor_shadow_id, *sh_coords)
        self.canvas.coords(self.cursor_poly_id, *coords)
        self.canvas.itemconfig(self.cursor_shadow_id, state=tk.NORMAL)
        self.canvas.itemconfig(self.cursor_poly_id, state=tk.NORMAL)
        self.canvas.tag_raise("cursor_layer")
        self.canvas.tag_raise("hud_layer")

    def _show_ripple(self, cx, cy):
        self.canvas.coords(self.ripple_id, cx - 10, cy - 10, cx + 10, cy + 10)
        self.canvas.itemconfig(self.ripple_id, state=tk.NORMAL)
        self.root.after(180, lambda: self.canvas.itemconfig(self.ripple_id, state=tk.HIDDEN))

    def _sync_stb_cursor(self, tx, ty, visible):
        if not self.stb_cursor_var.get():
            return
        now = time.time()
        if visible and (now - self.last_cursor_sync) < 0.032:
            return
        self.last_cursor_sync = now
        self.last_cursor_tv = (tx, ty)
        self._send_api(f"/api/cursor?x={tx}&y={ty}&visible={1 if visible else 0}")

    def _on_mouse_move(self, ev):
        tx, ty, inside = self._canvas_to_tv(ev.x, ev.y)
        if inside:
            self.canvas.configure(cursor="none")
            self._draw_cursor_at(ev.x, ev.y, pressed=bool(self.drag_start))
            self._sync_stb_cursor(tx, ty, True)
        else:
            self.canvas.configure(cursor="arrow")
            self.canvas.itemconfig(self.cursor_shadow_id, state=tk.HIDDEN)
            self.canvas.itemconfig(self.cursor_poly_id, state=tk.HIDDEN)

    def _on_mouse_leave(self, _ev):
        self.canvas.configure(cursor="arrow")
        self.canvas.itemconfig(self.cursor_shadow_id, state=tk.HIDDEN)
        self.canvas.itemconfig(self.cursor_poly_id, state=tk.HIDDEN)
        self.canvas.itemconfig(self.drag_line_id, state=tk.HIDDEN)
        self._sync_stb_cursor(self.last_cursor_tv[0], self.last_cursor_tv[1], False)

    def _on_mouse_down(self, ev):
        self.canvas.focus_set()
        self.drag_start = (ev.x, ev.y)
        self._draw_cursor_at(ev.x, ev.y, pressed=True)

    def _on_mouse_drag(self, ev):
        if not self.drag_start:
            return
        x0, y0 = self.drag_start
        if abs(ev.x - x0) + abs(ev.y - y0) > 14:
            self.canvas.coords(self.drag_line_id, x0, y0, ev.x, ev.y)
            self.canvas.itemconfig(self.drag_line_id, state=tk.NORMAL)
        self._draw_cursor_at(ev.x, ev.y, pressed=True)
        tx, ty, inside = self._canvas_to_tv(ev.x, ev.y)
        if inside:
            self._sync_stb_cursor(tx, ty, True)

    def _on_mouse_up(self, ev):
        self.canvas.itemconfig(self.drag_line_id, state=tk.HIDDEN)
        if not self.drag_start:
            return
        x0, y0 = self.drag_start
        self.drag_start = None
        self._draw_cursor_at(ev.x, ev.y, pressed=False)
        self._show_ripple(ev.x, ev.y)
        if abs(ev.x - x0) + abs(ev.y - y0) > 14:
            tx1, ty1, _ = self._canvas_to_tv(x0, y0)
            tx2, ty2, _ = self._canvas_to_tv(ev.x, ev.y)
            self._send_api(f"/api/swipe?x1={tx1}&y1={ty1}&x2={tx2}&y2={ty2}&ms=220")
        else:
            tx, ty, _ = self._canvas_to_tv(ev.x, ev.y)
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
                url = f"{self.base_url}/api/mirror.mjpeg?res={self.res_tag}&scale={self.scale}&q={self.quality}&token={urllib.parse.quote(self.token)}"
                try:
                    with urllib.request.urlopen(url, timeout=8.0) as resp:
                        buf = bytearray()
                        while not self.stop_event.is_set() and gen == self.stream_gen:
                            chunk = resp.read(32768)
                            if not chunk:
                                break
                            buf.extend(chunk)
                            newest = None
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
                                newest = bytes(buf[soi : eoi + 2])
                                del buf[: eoi + 2]
                            if newest is not None:
                                if self.raw_queue.full():
                                    try:
                                        self.raw_queue.get_nowait()
                                    except queue.Empty:
                                        pass
                                self.raw_queue.put(newest)
                except Exception:
                    time.sleep(0.35)
        threading.Thread(target=loop, daemon=True).start()

    def _start_decode_thread(self):
        def worker():
            while not self.stop_event.is_set():
                try:
                    jpg = self.raw_queue.get(timeout=0.25)
                except queue.Empty:
                    continue
                while not self.raw_queue.empty():
                    try:
                        jpg = self.raw_queue.get_nowait()
                    except queue.Empty:
                        break
                try:
                    cw, ch = self.canvas_wh
                    img = Image.open(io.BytesIO(jpg))
                    iw, ih = img.size
                    ratio = min(cw / iw, ch / ih)
                    nw, nh = max(1, int(iw * ratio)), max(1, int(ih * ratio))
                    rx = (cw - nw) // 2
                    ry = (ch - nh) // 2
                    if nw != iw or nh != ih:
                        resized = img.resize((nw, nh), Image.Resampling.BILINEAR)
                    else:
                        resized = img
                    if self.decoded_queue.full():
                        try:
                            self.decoded_queue.get_nowait()
                        except queue.Empty:
                            pass
                    self.decoded_queue.put((resized, (rx, ry, nw, nh), (iw, ih)))
                except Exception:
                    pass
        threading.Thread(target=worker, daemon=True).start()

    def _poll_frames(self):
        if self.stop_event.is_set():
            return
        latest = None
        while not self.decoded_queue.empty():
            try:
                latest = self.decoded_queue.get_nowait()
            except queue.Empty:
                break
        if latest is not None:
            try:
                resized, rect, vwh = latest
                self.img_rect = rect
                self.video_wh = vwh
                rx, ry, _nw, _nh = rect
                self.photo_ref = ImageTk.PhotoImage(resized)
                if self.img_item_id is None:
                    self.img_item_id = self.canvas.create_image(rx, ry, anchor=tk.NW, image=self.photo_ref, tags=("tv_img",))
                else:
                    self.canvas.coords(self.img_item_id, rx, ry)
                    self.canvas.itemconfig(self.img_item_id, image=self.photo_ref)
                self.canvas.tag_lower(self.img_item_id)
                self.canvas.tag_raise("cursor_layer")
                self.canvas.tag_raise("hud_layer")

                self.fps_count += 1
                now = time.time()
                if now - self.fps_t0 >= 0.8:
                    self.cur_fps = self.fps_count / (now - self.fps_t0)
                    self.fps_count = 0
                    self.fps_t0 = now
                badge = "FHD" if vwh[0] >= 1920 else "HD" if vwh[0] >= 1280 else "SD"
                txt = f"LIVE {badge} {vwh[0]}x{vwh[1]} • {self.cur_fps:.1f} fps | Cursor {self.last_cursor_tv[0]},{self.last_cursor_tv[1]}"
                self.canvas.itemconfig(self.hud_txt_id, text=txt)
                bbox = self.canvas.bbox(self.hud_txt_id)
                if bbox:
                    self.canvas.coords(self.hud_bg_id, bbox[0] - 8, bbox[1] - 4, bbox[2] + 8, bbox[3] + 4)
            except Exception:
                pass
        self.root.after(8, self._poll_frames)

    def _on_close(self):
        self.stop_event.set()
        self.root.destroy()


def main():
    ap = argparse.ArgumentParser(description="JioSTB Live Mirror & Control Desktop Client")
    ap.add_argument("--host", default="", help="STB IP/URL (default: auto-discover LAN or Tailscale)")
    ap.add_argument("--token", default="", help="API token")
    ap.add_argument("--scale", type=int, default=2, choices=[1, 2, 3, 4], help="Resolution preset (1=1080p FHD, 2=720p HD, 3=540p, 4=360p)")
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

