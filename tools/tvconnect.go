package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	addr      = flag.String("addr", ":8088", "listen address")
	tokenFile = flag.String("token-file", "/data/local/tmp/api_token.txt", "API token path")
	uiFile    = flag.String("ui", "/data/local/tmp/remote.html", "HTML dashboard path")
	apiToken  string
	screenMu  sync.Mutex
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

func main() {
	flag.Parse()
	apiToken = loadOrCreateToken(*tokenFile)

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
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":           true,
			"model":        runProp("ro.product.model"),
			"device":       runProp("ro.product.device"),
			"wifi_ip":      getIfaceIP("wlan", "eth"),
			"tailscale_ip": getIfaceIP("tun"),
			"adb_port":     5555,
			"ime":          strings.TrimSpace(string(imeOut)),
			"time":         time.Now().Format(time.RFC3339),
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
		go func() {
			_ = exec.Command("/system/bin/input", "keyevent", strconv.Itoa(code)).Run()
		}()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "key": code})
	}))

	mux.HandleFunc("/api/text", withAuth(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("q")
		if q == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "missing q"})
			return
		}
		escaped := strings.ReplaceAll(q, " ", "%s")
		go func() {
			_ = exec.Command("/system/bin/input", "text", escaped).Run()
		}()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	}))

	mux.HandleFunc("/api/tap", withAuth(func(w http.ResponseWriter, r *http.Request) {
		x, err1 := strconv.Atoi(r.URL.Query().Get("x"))
		y, err2 := strconv.Atoi(r.URL.Query().Get("y"))
		if err1 != nil || err2 != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid coordinates"})
			return
		}
		go func() {
			_ = exec.Command("/system/bin/input", "tap", strconv.Itoa(x), strconv.Itoa(y)).Run()
		}()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "x": x, "y": y})
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
		go func() {
			_ = exec.Command("/system/bin/input", "swipe", strconv.Itoa(x1), strconv.Itoa(y1), strconv.Itoa(x2), strconv.Itoa(y2), ms).Run()
		}()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	}))

	mux.HandleFunc("/api/launch", withAuth(func(w http.ResponseWriter, r *http.Request) {
		pkg := sanitizePkg(r.URL.Query().Get("pkg"))
		if pkg == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "missing pkg"})
			return
		}
		go func() {
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

	log.Printf("tvconnect listening on %s", *addr)
	log.Fatal(http.ListenAndServe(*addr, mux))
}
