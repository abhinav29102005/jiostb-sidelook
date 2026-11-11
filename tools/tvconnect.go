package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	addr       = flag.String("addr", ":8088", "listen address")
	tokenFile  = flag.String("token-file", "/data/local/tmp/api_token.txt", "API token path")
	uiFile     = flag.String("ui", "/data/local/tmp/remote.html", "HTML dashboard path")
	apiToken   string
	screenMu   sync.Mutex
	h264Mu     sync.Mutex
	inputQueue = make(chan []string, 256)
)

func loadOrCreateToken(path string) string {
	if b, err := os.ReadFile(path); err == nil {
		if t := strings.TrimSpace(string(b)); t != "" {
			return t
		}
	}
	buf := make([]byte, 12)
	_, _ = rand.Read(buf)
	t := hex.EncodeToString(buf)
	_ = os.WriteFile(path, []byte(t+"\n"), 0644)
	_ = os.WriteFile("/sdcard/tv_connect_token.txt", []byte(t+"\n"), 0644)
	return t
}

func getIfaceIP(prefixes ...string) string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, pref := range prefixes {
		for _, iface := range ifaces {
			if !strings.HasPrefix(iface.Name, pref) {
				continue
			}
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, a := range addrs {
				if ipNet, ok := a.(*net.IPNet); ok && !ipNet.IP.IsLoopback() {
					if v4 := ipNet.IP.To4(); v4 != nil {
						return v4.String()
					}
				}
			}
		}
	}
	return ""
}

func runProp(name string) string {
	out, err := exec.Command("/system/bin/getprop", name).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func authorized(r *http.Request) bool {
	if r.URL.Query().Get("token") == apiToken {
		return true
	}
	if r.Header.Get("X-API-Key") == apiToken {
		return true
	}
	auth := r.Header.Get("Authorization")
	if strings.HasPrefix(auth, "Bearer ") && strings.TrimSpace(strings.TrimPrefix(auth, "Bearer ")) == apiToken {
		return true
	}
	return false
}

func withAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if !authorized(r) {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"ok": false, "error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

func sanitizePkg(s string) string {
	var b strings.Builder
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '.' || r == '_' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func startInputWorker() {
	go func() {
		for args := range inputQueue {
			if len(args) == 0 {
				continue
			}
			_ = exec.Command("/system/bin/input", args...).Run()
		}
	}()
}

func enqueueInput(args ...string) {
	select {
	case inputQueue <- args:
	default:
		// Drop stale burst input rather than blocking HTTP handler.
	}
}

var (
	rawFrameBuf []byte
	localHTTP   = &http.Client{Timeout: 2 * time.Second}
)

func is8089ProjectionActive() bool {
	resp, err := localHTTP.Get("http://127.0.0.1:8089/api/fast_status?token=" + apiToken)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return false
	}
	var res struct {
		ProjectionActive bool `json:"projection_active"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return false
	}
	return res.ProjectionActive
}

func fetch8089Jpeg(scale, quality int, res string) ([]byte, error) {
	u := fmt.Sprintf("http://127.0.0.1:8089/api/screen.jpg?scale=%d&q=%d&res=%s&token=%s", scale, quality, url.QueryEscape(res), apiToken)
	resp, err := localHTTP.Get(u)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("8089 status %d", resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

func captureJPEG(scale, quality int, res string) ([]byte, error) {
	if scale < 1 || scale > 4 {
		scale = 2
	}
	if quality < 20 || quality > 95 {
		quality = 80
	}
	if is8089ProjectionActive() {
		if jpg, err := fetch8089Jpeg(scale, quality, res); err == nil && len(jpg) > 0 {
			return jpg, nil
		}
	}
	const tmpRaw = "/data/local/tmp/_tvconnect_frame.raw"

	screenMu.Lock()
	capCtx, capCancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	err := exec.CommandContext(capCtx, "/system/bin/screencap", tmpRaw).Run()
	capCancel()
	if err != nil {
		screenMu.Unlock()
		if jpg, ferr := fetch8089Jpeg(scale, quality, res); ferr == nil && len(jpg) > 0 {
			return jpg, nil
		}
		return nil, fmt.Errorf("screencap failed: %w", err)
	}
	f, err := os.Open(tmpRaw)
	if err != nil {
		screenMu.Unlock()
		return nil, fmt.Errorf("open frame failed: %w", err)
	}
	st, err := f.Stat()
	if err != nil {
		_ = f.Close()
		screenMu.Unlock()
		return nil, fmt.Errorf("stat frame failed: %w", err)
	}
	n := int(st.Size())
	if n < 16 {
		_ = f.Close()
		screenMu.Unlock()
		return nil, fmt.Errorf("screencap file too short (%d)", n)
	}
	if cap(rawFrameBuf) < n {
		rawFrameBuf = make([]byte, n)
	}
	raw := rawFrameBuf[:n]
	_, rerr := io.ReadFull(f, raw)
	_ = f.Close()
	if rerr != nil {
		screenMu.Unlock()
		return nil, fmt.Errorf("read frame failed: %w", rerr)
	}

	w := int(binary.LittleEndian.Uint32(raw[0:4]))
	h := int(binary.LittleEndian.Uint32(raw[4:8]))
	if w <= 0 || h <= 0 || w > 4096 || h > 4096 {
		screenMu.Unlock()
		return nil, fmt.Errorf("invalid frame dimensions %dx%d", w, h)
	}
	expectedPixels := w * h * 4
	headerLen := len(raw) - expectedPixels
	if headerLen < 12 || headerLen > 64 {
		screenMu.Unlock()
		return nil, fmt.Errorf("unexpected raw size %d for %dx%d", len(raw), w, h)
	}
	pix := raw[headerLen:]

	outW := w / scale
	outH := h / scale
	dst := image.NewRGBA(image.Rect(0, 0, outW, outH))
	srcStride := w * 4
	dstStride := dst.Stride
	for dy := 0; dy < outH; dy++ {
		sy := dy * scale * srcStride
		dp := dy * dstStride
		for dx := 0; dx < outW; dx++ {
			sp := sy + dx*scale*4
			dst.Pix[dp+0] = pix[sp+0]
			dst.Pix[dp+1] = pix[sp+1]
			dst.Pix[dp+2] = pix[sp+2]
			dst.Pix[dp+3] = 255
			dp += 4
		}
	}
	screenMu.Unlock()

	var buf bytes.Buffer
	buf.Grow(outW * outH / 8)
	if err := jpeg.Encode(&buf, dst, &jpeg.Options{Quality: quality}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

type mirrorHub struct {
	mu      sync.Mutex
	subs    map[chan []byte]struct{}
	running bool
	scale   atomic.Int32
	quality atomic.Int32
}

func newMirrorHub() *mirrorHub {
	h := &mirrorHub{subs: make(map[chan []byte]struct{})}
	h.scale.Store(2)
	h.quality.Store(65)
	return h
}

func (h *mirrorHub) subscribe(scale, quality int) chan []byte {
	if scale >= 1 && scale <= 4 {
		h.scale.Store(int32(scale))
	}
	if quality >= 20 && quality <= 95 {
		h.quality.Store(int32(quality))
	}
	ch := make(chan []byte, 2)
	h.mu.Lock()
	h.subs[ch] = struct{}{}
	needStart := !h.running
	h.running = true
	h.mu.Unlock()
	if needStart {
		go h.loop()
	}
	return ch
}

func (h *mirrorHub) unsubscribe(ch chan []byte) {
	h.mu.Lock()
	delete(h.subs, ch)
	h.mu.Unlock()
	close(ch)
}

func (h *mirrorHub) loop() {
	for {
		h.mu.Lock()
		n := len(h.subs)
		if n == 0 {
			h.running = false
			h.mu.Unlock()
			return
		}
		h.mu.Unlock()

		frame, err := captureJPEG(int(h.scale.Load()), int(h.quality.Load()), "")
		if err != nil {
			time.Sleep(300 * time.Millisecond)
			continue
		}
		h.mu.Lock()
		for ch := range h.subs {
			select {
			case ch <- frame:
			default:
				// Drop old frame so viewer stays real-time.
				select {
				case <-ch:
				default:
				}
				select {
				case ch <- frame:
				default:
				}
			}
		}
		h.mu.Unlock()
	}
}

func main() {
	flag.Parse()
	apiToken = loadOrCreateToken(*tokenFile)
	startInputWorker()
	hub := newMirrorHub()

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" && r.URL.Path != "/index.html" {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not found"})
			return
		}
		b, err := os.ReadFile(*uiFile)
		if err != nil {
			http.Error(w, "UI not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(b)
	})

	mux.HandleFunc("/api/ping", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "service": "tvconnect"})
	})

	mux.HandleFunc("/api/health", withAuth(func(w http.ResponseWriter, r *http.Request) {
		imeOut, _ := exec.Command("/system/bin/settings", "get", "secure", "default_input_method").Output()
		accOut, _ := exec.Command("/system/bin/settings", "get", "secure", "enabled_accessibility_services").Output()
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":            true,
			"model":         runProp("ro.product.model"),
			"device":        runProp("ro.product.device"),
			"wifi_ip":       getIfaceIP("wlan", "eth"),
			"tailscale_ip":  getIfaceIP("tun"),
			"adb_port":      5555,
			"screen_w":      1920,
			"screen_h":      1080,
			"ime":           strings.TrimSpace(string(imeOut)),
			"accessibility": strings.TrimSpace(string(accOut)),
			"time":          time.Now().Format(time.RFC3339),
		})
	}))

	mux.HandleFunc("/api/key", withAuth(func(w http.ResponseWriter, r *http.Request) {
		c := r.URL.Query().Get("code")
		if c == "" {
			c = r.URL.Query().Get("c")
		}
		code, err := strconv.Atoi(c)
		if err != nil || code <= 0 {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid key code"})
			return
		}
		long := r.URL.Query().Get("long") == "1"
		if long {
			enqueueInput("keyevent", "--longpress", strconv.Itoa(code))
		} else {
			enqueueInput("keyevent", strconv.Itoa(code))
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "key": code, "long": long})
	}))

	mux.HandleFunc("/api/text", withAuth(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("q")
		if q == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "missing q"})
			return
		}
		escaped := strings.ReplaceAll(q, " ", "%s")
		enqueueInput("text", escaped)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	}))

	mux.HandleFunc("/api/tap", withAuth(func(w http.ResponseWriter, r *http.Request) {
		x, err1 := strconv.Atoi(r.URL.Query().Get("x"))
		y, err2 := strconv.Atoi(r.URL.Query().Get("y"))
		if err1 != nil || err2 != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid coordinates"})
			return
		}
		go func(cx, cy int) {
			if resp, err := localHTTP.Get(fmt.Sprintf("http://127.0.0.1:8089/api/cursor?x=%d&y=%d&click=1&token=%s", cx, cy, apiToken)); err == nil {
				_ = resp.Body.Close()
			}
		}(x, y)
		enqueueInput("tap", strconv.Itoa(x), strconv.Itoa(y))
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "x": x, "y": y})
	}))

	mux.HandleFunc("/api/cursor", withAuth(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		u := fmt.Sprintf("http://127.0.0.1:8089/api/cursor?x=%s&y=%s&visible=%s&click=%s&token=%s",
			url.QueryEscape(q.Get("x")),
			url.QueryEscape(q.Get("y")),
			url.QueryEscape(q.Get("visible")),
			url.QueryEscape(q.Get("click")),
			apiToken,
		)
		if q.Has("enabled") {
			u += "&enabled=" + url.QueryEscape(q.Get("enabled"))
		}
		resp, err := localHTTP.Get(u)
		if err != nil {
			writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(resp.StatusCode)
		_, _ = w.Write(b)
	}))

	mux.HandleFunc("/api/swipe", withAuth(func(w http.ResponseWriter, r *http.Request) {
		x1, e1 := strconv.Atoi(r.URL.Query().Get("x1"))
		y1, e2 := strconv.Atoi(r.URL.Query().Get("y1"))
		x2, e3 := strconv.Atoi(r.URL.Query().Get("x2"))
		y2, e4 := strconv.Atoi(r.URL.Query().Get("y2"))
		ms := r.URL.Query().Get("ms")
		if ms == "" {
			ms = "250"
		}
		if e1 != nil || e2 != nil || e3 != nil || e4 != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid swipe coordinates"})
			return
		}
		enqueueInput("swipe", strconv.Itoa(x1), strconv.Itoa(y1), strconv.Itoa(x2), strconv.Itoa(y2), ms)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	}))

	mux.HandleFunc("/api/launch", withAuth(func(w http.ResponseWriter, r *http.Request) {
		pkg := sanitizePkg(r.URL.Query().Get("pkg"))
		if pkg == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "missing pkg"})
			return
		}
		go func() {
			for _, cat := range []string{"android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"} {
				if out, err := exec.Command("/system/bin/cmd", "package", "resolve-activity", "--brief", "-c", cat, pkg).Output(); err == nil {
					lines := strings.Split(strings.TrimSpace(string(out)), "\n")
					comp := strings.TrimSpace(lines[len(lines)-1])
					if strings.Contains(comp, "/") && !strings.HasPrefix(comp, "No activity") {
						if exec.Command("/system/bin/am", "start", "-n", comp).Run() == nil {
							return
						}
					}
				}
			}
			_ = exec.Command("/system/bin/monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1").Run()
		}()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "pkg": pkg})
	}))

	mux.HandleFunc("/api/open", withAuth(func(w http.ResponseWriter, r *http.Request) {
		u := strings.TrimSpace(r.URL.Query().Get("url"))
		if !strings.HasPrefix(u, "http://") && !strings.HasPrefix(u, "https://") {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "http/https url required"})
			return
		}
		go func() {
			_ = exec.Command("/system/bin/am", "start", "-a", "android.intent.action.VIEW", "-d", u).Run()
		}()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "url": u})
	}))

	mux.HandleFunc("/api/apps", withAuth(func(w http.ResponseWriter, r *http.Request) {
		out, err := exec.Command("/system/bin/pm", "list", "packages", "-3").Output()
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		var pkgs []string
		for _, line := range strings.Split(string(out), "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "package:") {
				pkgs = append(pkgs, strings.TrimPrefix(line, "package:"))
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "packages": pkgs})
	}))

	mux.HandleFunc("/api/screen", withAuth(func(w http.ResponseWriter, r *http.Request) {
		screenMu.Lock()
		defer screenMu.Unlock()
		out, err := exec.Command("/system/bin/screencap", "-p").Output()
		if err != nil || len(out) == 0 {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": fmt.Sprintf("screencap failed: %v", err)})
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, _ = w.Write(out)
	}))

	mux.HandleFunc("/api/screen.jpg", withAuth(func(w http.ResponseWriter, r *http.Request) {
		scale, _ := strconv.Atoi(r.URL.Query().Get("scale"))
		quality, _ := strconv.Atoi(r.URL.Query().Get("q"))
		res := r.URL.Query().Get("res")
		jpg, err := captureJPEG(scale, quality, res)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, _ = w.Write(jpg)
	}))

	mux.HandleFunc("/api/start_mirror", withAuth(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		u := fmt.Sprintf("http://127.0.0.1:8089/api/start_mirror?scale=%s&res=%s&q=%s&token=%s",
			url.QueryEscape(q.Get("scale")),
			url.QueryEscape(q.Get("res")),
			url.QueryEscape(q.Get("q")),
			apiToken,
		)
		resp, err := localHTTP.Get(u)
		if err != nil {
			writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(resp.StatusCode)
		_, _ = w.Write(b)
	}))

	mux.HandleFunc("/api/mirror.mjpeg", withAuth(func(w http.ResponseWriter, r *http.Request) {
		scale, _ := strconv.Atoi(r.URL.Query().Get("scale"))
		if scale < 1 || scale > 4 {
			scale = 2
		}
		res := r.URL.Query().Get("res")
		quality, _ := strconv.Atoi(r.URL.Query().Get("q"))
		if quality < 35 || quality > 95 {
			quality = 80
		}

		// Kick off hardware VirtualDisplay on :8089 if not yet running and wait briefly so stream locks onto HD MediaProjection.
		if !is8089ProjectionActive() {
			if resp, err := localHTTP.Get(fmt.Sprintf("http://127.0.0.1:8089/api/start_mirror?scale=%d&res=%s&q=%d&token=%s", scale, url.QueryEscape(res), quality, apiToken)); err == nil {
				_ = resp.Body.Close()
			}
			for i := 0; i < 8; i++ {
				if is8089ProjectionActive() {
					break
				}
				time.Sleep(150 * time.Millisecond)
			}
		}

		if is8089ProjectionActive() {
			streamReq, _ := http.NewRequestWithContext(
				r.Context(),
				http.MethodGet,
				fmt.Sprintf("http://127.0.0.1:8089/api/mirror.mjpeg?scale=%d&res=%s&q=%d&token=%s", scale, url.QueryEscape(res), quality, apiToken),
				nil,
			)
			streamClient := &http.Client{}
			if sresp, err := streamClient.Do(streamReq); err == nil {
				defer sresp.Body.Close()
				w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=frame")
				w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate")
				w.Header().Set("Pragma", "no-cache")
				w.Header().Set("Connection", "close")
				w.Header().Set("Access-Control-Allow-Origin", "*")
				flusher, _ := w.(http.Flusher)
				buf := make([]byte, 32*1024)
				for {
					n, rerr := sresp.Body.Read(buf)
					if n > 0 {
						if _, werr := w.Write(buf[:n]); werr != nil {
							return
						}
						if flusher != nil {
							flusher.Flush()
						}
					}
					if rerr != nil {
						return
					}
				}
			}
		}

		w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=frame")
		w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Connection", "close")
		w.Header().Set("Access-Control-Allow-Origin", "*")

		flusher, _ := w.(http.Flusher)
		ch := hub.subscribe(scale, quality)
		defer hub.unsubscribe(ch)

		ctx := r.Context()
		for {
			select {
			case <-ctx.Done():
				return
			case frame, ok := <-ch:
				if !ok {
					return
				}
				hdr := fmt.Sprintf("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", len(frame))
				if _, err := io.WriteString(w, hdr); err != nil {
					return
				}
				if _, err := w.Write(frame); err != nil {
					return
				}
				if _, err := io.WriteString(w, "\r\n"); err != nil {
					return
				}
				if flusher != nil {
					flusher.Flush()
				}
			}
		}
	}))

	mux.HandleFunc("/api/mirror.h264", withAuth(func(w http.ResponseWriter, r *http.Request) {
		h264Mu.Lock()
		defer h264Mu.Unlock()
		size := r.URL.Query().Get("size")
		switch size {
		case "960x540", "1280x720", "1920x1080":
		default:
			size = "1280x720"
		}
		br, err := strconv.Atoi(r.URL.Query().Get("bitrate"))
		if err != nil || br < 500000 || br > 12000000 {
			br = 2500000
		}
		bitrate := strconv.Itoa(br)
		cmd := exec.Command(
			"/system/bin/screenrecord",
			"--output-format=h264",
			"--size", size,
			"--bit-rate", bitrate,
			"-",
		)
		stdout, err := cmd.StdoutPipe()
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		if err := cmd.Start(); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}

		waitDone := make(chan error, 1)
		go func() {
			waitDone <- cmd.Wait()
		}()
		ctxDone := make(chan struct{})
		go func() {
			select {
			case <-r.Context().Done():
				if cmd.Process != nil {
					_ = cmd.Process.Signal(os.Interrupt)
				}
			case <-ctxDone:
			}
		}()
		defer func() {
			close(ctxDone)
			if cmd.Process != nil {
				_ = cmd.Process.Signal(os.Interrupt)
			}
			select {
			case <-waitDone:
			case <-time.After(1500 * time.Millisecond):
				if cmd.Process != nil {
					_ = cmd.Process.Kill()
				}
				<-waitDone
			}
		}()
		w.Header().Set("Content-Type", "video/H264")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		flusher, _ := w.(http.Flusher)
		buf := make([]byte, 32*1024)
		for {
			n, rerr := stdout.Read(buf)
			if n > 0 {
				if _, werr := w.Write(buf[:n]); werr != nil {
					return
				}
				if flusher != nil {
					flusher.Flush()
				}
			}
			if rerr != nil {
				return
			}
		}
	}))

	log.Printf("tvconnect listening on %s", *addr)
	log.Fatal(http.ListenAndServe(*addr, mux))
}
