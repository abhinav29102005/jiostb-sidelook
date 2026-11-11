package com.abhinav.tvconnect;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TvAccessibilityService extends AccessibilityService {
    private static final String TAG = "TvConnectAcc";
    public static volatile boolean running = false;
    public static volatile boolean port8088Bound = false;
    public static volatile boolean port8089Bound = false;
    public static volatile long consentRequestedAt = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService captureExec = Executors.newSingleThreadExecutor();
    private final ExecutorService httpPool = Executors.newCachedThreadPool();

    private ServerSocket server8089;
    private ServerSocket server8088;
    private volatile boolean alive = false;
    private long lastTapX = 960;
    private long lastTapY = 540;

    private WindowManager wm;
    private CursorOverlayView cursorView;
    private WindowManager.LayoutParams cursorLp;
    private volatile boolean cursorEnabled = true;
    private final Runnable hideCursorTask = () -> {
        if (cursorView != null && cursorView.getVisibility() == View.VISIBLE) {
            cursorView.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        alive = true;
        TokenStore.getToken(this);
        mainHandler.post(this::initCursorOverlay);
        startServerLoop(8089, true);
        startServerLoop(8088, false);
        Log.i(TAG, "TvAccessibilityService connected");
    }

    private void initCursorOverlay() {
        if (cursorView != null) return;
        try {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            cursorView = new CursorOverlayView(this);
            cursorView.setVisibility(View.GONE);
            int sizePx = Math.round(getResources().getDisplayMetrics().density * 44f);
            if (sizePx < 48) sizePx = 56;
            cursorLp = new WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );
            cursorLp.gravity = Gravity.TOP | Gravity.START;
            cursorLp.x = 960;
            cursorLp.y = 540;
            wm.addView(cursorView, cursorLp);
        } catch (Throwable t) {
            Log.w(TAG, "Cursor overlay init failed: " + t.getMessage());
        }
    }

    private void updateCursorOverlay(final int x, final int y, final boolean show, final boolean clickRipple) {
        lastTapX = x;
        lastTapY = y;
        mainHandler.post(() -> {
            if (cursorView == null) initCursorOverlay();
            if (cursorView == null || wm == null || cursorLp == null) return;
            mainHandler.removeCallbacks(hideCursorTask);
            if (!cursorEnabled || !show) {
                cursorView.setVisibility(View.GONE);
                return;
            }
            int hot = cursorView.hotspot();
            cursorLp.x = Math.max(-hot, Math.min(1919 - hot, x - hot));
            cursorLp.y = Math.max(-hot, Math.min(1079 - hot, y - hot));
            if (cursorView.getVisibility() != View.VISIBLE) {
                cursorView.setVisibility(View.VISIBLE);
            }
            try {
                wm.updateViewLayout(cursorView, cursorLp);
            } catch (Throwable ignored) {}
            if (clickRipple) {
                cursorView.triggerRipple();
            }
            mainHandler.postDelayed(hideCursorTask, 4500);
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (consentRequestedAt == 0 || event == null) return;
        if (SystemClock.uptimeMillis() - consentRequestedAt > 6000) {
            consentRequestedAt = 0;
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !"com.android.systemui".equals(pkg.toString())) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (clickStartNow(root)) {
            consentRequestedAt = 0;
        }
    }

    private boolean clickStartNow(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence txt = node.getText();
        if (txt != null) {
            String s = txt.toString().trim().toLowerCase();
            if ("start now".equals(s) || "allow".equals(s)) {
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
                AccessibilityNodeInfo p = node.getParent();
                if (p != null && p.isClickable()) {
                    p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickStartNow(node.getChild(i))) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "TvAccessibilityService interrupted");
    }

    @Override
    public void onDestroy() {
        running = false;
        alive = false;
        closeSocket(server8089);
        closeSocket(server8088);
        mainHandler.removeCallbacks(hideCursorTask);
        if (wm != null && cursorView != null) {
            try { wm.removeViewImmediate(cursorView); } catch (Throwable ignored) {}
            cursorView = null;
        }
        captureExec.shutdownNow();
        httpPool.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return false;
        }
        String targetPkg = null;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_PROG_RED:
                targetPkg = "com.abhinav.cinejoy";
                break;
            case KeyEvent.KEYCODE_PROG_GREEN:
                targetPkg = "org.smarttube.stable";
                break;
            case KeyEvent.KEYCODE_PROG_YELLOW:
                targetPkg = "app.netmirror.newtv";
                break;
            case KeyEvent.KEYCODE_PROG_BLUE:
                targetPkg = "com.stremio.one";
                break;
            case KeyEvent.KEYCODE_STEM_1:
            case KeyEvent.KEYCODE_EXPLORER:
                targetPkg = "com.abhinav.tvconnect";
                break;
            default:
                return false;
        }
        launchPackage(targetPkg);
        return true;
    }

    private void closeSocket(ServerSocket ss) {
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
    }

    private void startServerLoop(final int port, final boolean primary8089) {
        httpPool.execute(() -> {
            try {
                ServerSocket ss = new ServerSocket(port);
                if (primary8089) {
                    server8089 = ss;
                    port8089Bound = true;
                } else {
                    server8088 = ss;
                    port8088Bound = true;
                }
                Log.i(TAG, "Gateway fallback listening on :" + port);
                while (alive && !ss.isClosed()) {
                    try {
                        Socket client = ss.accept();
                        httpPool.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (!ss.isClosed()) Log.w(TAG, "accept err on " + port, e);
                        break;
                    }
                }
            } catch (IOException e) {
                Log.i(TAG, "Port " + port + " already in use (" + e.getMessage() + ")");
            } finally {
                if (primary8089) port8089Bound = false;
                else port8088Bound = false;
            }
        });
    }

    private void handleClient(Socket sock) {
        try (Socket s = sock) {
            s.setTcpNoDelay(true);
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String reqLine = in.readLine();
            if (reqLine == null || reqLine.isEmpty()) return;

            String headerAuth = "";
            String headerApiKey = "";
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim().toLowerCase();
                    String v = line.substring(idx + 1).trim();
                    if ("authorization".equals(k)) headerAuth = v;
                    else if ("x-api-key".equals(k)) headerApiKey = v;
                }
            }

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String fullPath = parts[1];
            String path = fullPath;
            Map<String, String> query = new HashMap<>();
            int qIdx = fullPath.indexOf('?');
            if (qIdx >= 0) {
                path = fullPath.substring(0, qIdx);
                parseQuery(fullPath.substring(qIdx + 1), query);
            }

            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            if ("OPTIONS".equalsIgnoreCase(method)) {
                writeHeaders(out, 204, "text/plain", -1);
                out.flush();
                return;
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] ui = readFileBytes("/sdcard/remote.html");
                if (ui == null) {
                    String fallback = "<html><body style='background:#080b12;color:#f1f5f9;font-family:sans-serif;padding:24px'>"
                        + "<h2>TV Connect Accessibility Fallback Gateway</h2>"
                        + "<p>Ports: 8088=" + port8088Bound + ", 8089=" + port8089Bound + "</p>"
                        + "<p>Endpoints: /api/health, /api/screen.jpg, /api/mirror.mjpeg, /api/tap, /api/swipe, /api/key, /api/launch</p>"
                        + "</body></html>";
                    ui = fallback.getBytes(StandardCharsets.UTF_8);
                }
                writeHeaders(out, 200, "text/html; charset=utf-8", ui.length);
                out.write(ui);
                out.flush();
                return;
            }

            if ("/api/ping".equals(path)) {
                writeJson(out, 200, "{\"ok\":true,\"service\":\"tvconnect-apk\"}");
                return;
            }

            String expected = TokenStore.getToken(this);
            boolean okAuth = expected.equals(query.get("token"))
                || expected.equals(headerApiKey)
                || ("Bearer " + expected).equals(headerAuth);
            if (!okAuth) {
                writeJson(out, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }

            if ("/api/yield".equals(path)) {
                closeSocket(server8088);
                server8088 = null;
                port8088Bound = false;
                writeJson(out, 200, "{\"ok\":true,\"yielded\":8088}");
                return;
            }

            if ("/api/fast_status".equals(path)) {
                writeJson(out, 200, "{\"ok\":true,\"projection_active\":" + MirrorForegroundService.isActive()
                    + ",\"seq\":" + MirrorForegroundService.getLatestSeq()
                    + ",\"width\":" + MirrorForegroundService.getStreamWidth()
                    + ",\"height\":" + MirrorForegroundService.getStreamHeight()
                    + ",\"quality\":" + MirrorForegroundService.getStreamQuality()
                    + ",\"cursor_enabled\":" + cursorEnabled + "}");
                return;
            }

            if ("/api/start_mirror".equals(path)) {
                int[] whq = new int[3];
                MirrorForegroundService.resolvePreset(
                    parseInt(query.get("scale"), 2),
                    query.get("res"),
                    parseInt(query.get("q"), 0),
                    whq
                );
                MirrorForegroundService.configure(whq[0], whq[1], whq[2]);
                if (!MirrorForegroundService.isProjectionStarted()) {
                    Intent pi = new Intent(this, ProjectionConsentActivity.class);
                    pi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(pi);
                }
                if (MirrorForegroundService.getLatestJpeg() == null) {
                    httpPool.execute(() -> {
                        byte[] seed = captureJpeg(2, 72);
                        if (seed != null) MirrorForegroundService.seedFrameIfEmpty(seed);
                    });
                }
                writeJson(out, 200, "{\"ok\":true,\"projection_active\":" + MirrorForegroundService.isActive()
                    + ",\"width\":" + whq[0] + ",\"height\":" + whq[1] + "}");
                return;
            }

            if ("/api/cursor".equals(path)) {
                if (query.containsKey("enabled")) {
                    cursorEnabled = !"0".equals(query.get("enabled")) && !"false".equalsIgnoreCase(query.get("enabled"));
                    if (!cursorEnabled) {
                        updateCursorOverlay((int) lastTapX, (int) lastTapY, false, false);
                    }
                }
                int x = parseInt(query.get("x"), (int) lastTapX);
                int y = parseInt(query.get("y"), (int) lastTapY);
                boolean show = !"0".equals(query.get("visible"));
                boolean click = "1".equals(query.get("click"));
                updateCursorOverlay(x, y, show, click);
                writeJson(out, 200, "{\"ok\":true,\"cursor_enabled\":" + cursorEnabled + ",\"x\":" + x + ",\"y\":" + y + "}");
                return;
            }

            if ("/api/health".equals(path)) {
                String wifi = TokenStore.getIfaceIP("wlan", "eth");
                String ts = TokenStore.getIfaceIP("tun");
                String body = "{\"ok\":true,\"service\":\"tvconnect-apk\",\"projection_active\":"
                    + MirrorForegroundService.isActive() + ",\"model\":\"" + escapeJson(Build.MODEL)
                    + "\",\"device\":\"" + escapeJson(Build.DEVICE)
                    + "\",\"wifi_ip\":\"" + escapeJson(wifi)
                    + "\",\"tailscale_ip\":\"" + escapeJson(ts)
                    + "\",\"adb_port\":5555,\"screen_w\":1920,\"screen_h\":1080}";
                writeJson(out, 200, body);
                return;
            }

            if ("/api/screen.jpg".equals(path) || "/api/screen".equals(path)) {
                int[] whq = new int[3];
                MirrorForegroundService.resolvePreset(
                    parseInt(query.get("scale"), 2),
                    query.get("res"),
                    parseInt(query.get("q"), 0),
                    whq
                );
                MirrorForegroundService.configure(whq[0], whq[1], whq[2]);
                byte[] jpg = MirrorForegroundService.isActive() ? MirrorForegroundService.getLatestJpeg() : null;
                if (jpg == null) jpg = captureJpeg(parseInt(query.get("scale"), 2), whq[2]);
                if (jpg == null && MirrorForegroundService.isActive()) jpg = MirrorForegroundService.getLatestJpeg();
                if (jpg == null) {
                    writeJson(out, 500, "{\"ok\":false,\"error\":\"screenshot failed\"}");
                    return;
                }
                writeHeaders(out, 200, "image/jpeg", jpg.length);
                out.write(jpg);
                out.flush();
                return;
            }

            if ("/api/mirror.mjpeg".equals(path)) {
                int[] whq = new int[3];
                MirrorForegroundService.resolvePreset(
                    parseInt(query.get("scale"), 2),
                    query.get("res"),
                    parseInt(query.get("q"), 0),
                    whq
                );
                MirrorForegroundService.configure(whq[0], whq[1], whq[2]);
                String hdr = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                    + "Cache-Control: no-store, no-cache\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n";
                out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                long lastSeq = -1;
                while (alive && !s.isClosed()) {
                    byte[] frame;
                    if (MirrorForegroundService.isActive()) {
                        frame = MirrorForegroundService.waitNextFrame(lastSeq, 90);
                        lastSeq = MirrorForegroundService.getLatestSeq();
                    } else {
                        frame = captureJpeg(parseInt(query.get("scale"), 2), whq[2]);
                        if (frame != null) MirrorForegroundService.seedFrameIfEmpty(frame);
                    }
                    if (frame != null) {
                        String partHdr = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
                        out.write(partHdr.getBytes(StandardCharsets.US_ASCII));
                        out.write(frame);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                    if (!MirrorForegroundService.isActive()) {
                        try { Thread.sleep(60); } catch (InterruptedException ie) { break; }
                    }
                }
                return;
            }

            if ("/api/tap".equals(path)) {
                int x = parseInt(query.get("x"), -1);
                int y = parseInt(query.get("y"), -1);
                if (x < 0 || y < 0) {
                    writeJson(out, 400, "{\"ok\":false,\"error\":\"invalid coords\"}");
                    return;
                }
                updateCursorOverlay(x, y, true, true);
                performSwipe(x, y, x, y, 45);
                writeJson(out, 200, "{\"ok\":true}");
                return;
            }

            if ("/api/swipe".equals(path)) {
                int x1 = parseInt(query.get("x1"), -1);
                int y1 = parseInt(query.get("y1"), -1);
                int x2 = parseInt(query.get("x2"), -1);
                int y2 = parseInt(query.get("y2"), -1);
                int ms = parseInt(query.get("ms"), 220);
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                    writeJson(out, 400, "{\"ok\":false,\"error\":\"invalid swipe\"}");
                    return;
                }
                updateCursorOverlay(x2, y2, true, false);
                performSwipe(x1, y1, x2, y2, ms);
                writeJson(out, 200, "{\"ok\":true}");
                return;
            }

            if ("/api/key".equals(path)) {
                int code = parseInt(query.get("code") != null ? query.get("code") : query.get("c"), 0);
                boolean handled = handleKeyCode(code);
                writeJson(out, 200, "{\"ok\":" + handled + ",\"key\":" + code + "}");
                return;
            }

            if ("/api/text".equals(path)) {
                String qText = query.get("q");
                if (qText != null && !qText.isEmpty()) {
                    setFocusedText(qText);
                }
                writeJson(out, 200, "{\"ok\":true}");
                return;
            }

            if ("/api/launch".equals(path)) {
                String pkg = query.get("pkg");
                boolean launched = pkg != null && launchPackage(pkg);
                writeJson(out, 200, "{\"ok\":" + launched + "}");
                return;
            }

            if ("/api/open".equals(path)) {
                String u = query.get("url");
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    writeJson(out, 200, "{\"ok\":true}");
                } else {
                    writeJson(out, 400, "{\"ok\":false,\"error\":\"bad url\"}");
                }
                return;
            }

            writeJson(out, 404, "{\"ok\":false,\"error\":\"not found\"}");
        } catch (Throwable t) {
            Log.w(TAG, "Client error: " + t.getMessage());
        }
    }

    private byte[] captureJpeg(int scale, int quality) {
        if (scale < 1 || scale > 4) scale = 2;
        if (quality < 20 || quality > 95) quality = 65;
        final AtomicReference<Bitmap> bmpRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        takeScreenshot(Display.DEFAULT_DISPLAY, captureExec, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult res) {
                try (HardwareBuffer hb = res.getHardwareBuffer()) {
                    ColorSpace cs = res.getColorSpace();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                    if (hw != null) {
                        bmpRef.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                        hw.recycle();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "screenshot copy err", t);
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(int errorCode) {
                latch.countDown();
            }
        });
        try {
            latch.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}

        Bitmap full = bmpRef.get();
        if (full == null) return null;
        try {
            Bitmap target = full;
            if (scale > 1) {
                int nw = Math.max(1, full.getWidth() / scale);
                int nh = Math.max(1, full.getHeight() / scale);
                target = Bitmap.createScaledBitmap(full, nw, nh, true);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
            target.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            if (target != full) target.recycle();
            return baos.toByteArray();
        } finally {
            full.recycle();
        }
    }

    private void performSwipe(int x1, int y1, int x2, int y2, int ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(30, ms)));
        mainHandler.post(() -> dispatchGesture(gb.build(), null, null));
    }

    private boolean handleKeyCode(int code) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case KeyEvent.KEYCODE_HOME:
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case KeyEvent.KEYCODE_APP_SWITCH:
                return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case KeyEvent.KEYCODE_POWER:
                return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (am != null) {
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                performSwipe(960, 700, 960, 380, 140);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                performSwipe(960, 380, 960, 700, 140);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                performSwipe(700, 540, 380, 540, 140);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                performSwipe(380, 540, 700, 540, 140);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null) {
                        focused.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    }
                }
                performSwipe((int) lastTapX, (int) lastTapY, (int) lastTapX, (int) lastTapY, 45);
                return true;
            default:
                return false;
        }
    }

    private void setFocusedText(String text) {
        mainHandler.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) return;
            CharSequence prev = focused.getText();
            String combined = (prev != null ? prev.toString() : "") + text;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined);
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        });
    }

    private boolean launchPackage(String pkg) {
        try {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readFileBytes(String p) {
        File f = new File(p);
        if (!f.exists() || !f.canRead()) return null;
        try (FileInputStream fis = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void parseQuery(String qs, Map<String, String> out) {
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    out.put(k, v);
                } catch (Exception ignored) {}
            }
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeHeaders(OutputStream out, int status, String contentType, int contentLen) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(status == 200 ? " OK" : status == 204 ? " No Content" : " Error").append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Authorization, X-API-Key, Content-Type\r\n");
        if (contentLen >= 0) sb.append("Content-Length: ").append(contentLen).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeJson(OutputStream out, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, status, "application/json; charset=utf-8", b.length);
        out.write(b);
        out.flush();
    }

    private static final class CursorOverlayView extends View {
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();
        private long rippleStartMs = 0;

        CursorOverlayView(Context ctx) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            float ox = 16f * d;
            float oy = 16f * d;

            shadowPaint.setColor(Color.argb(110, 0, 0, 0));
            shadowPaint.setStyle(Paint.Style.FILL);

            fillPaint.setColor(Color.WHITE);
            fillPaint.setStyle(Paint.Style.FILL);

            strokePaint.setColor(Color.rgb(15, 23, 42));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(2.2f, 2.0f * d));
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            ripplePaint.setColor(Color.argb(200, 59, 130, 246));
            ripplePaint.setStyle(Paint.Style.STROKE);
            ripplePaint.setStrokeWidth(Math.max(2.5f, 2.5f * d));

            // Sharp pointer arrow with hotspot at (ox, oy)
            arrowPath.moveTo(ox, oy);
            arrowPath.lineTo(ox, oy + 20f * d);
            arrowPath.lineTo(ox + 5.2f * d, oy + 15.2f * d);
            arrowPath.lineTo(ox + 9.2f * d, oy + 23.0f * d);
            arrowPath.lineTo(ox + 12.2f * d, oy + 21.6f * d);
            arrowPath.lineTo(ox + 8.2f * d, oy + 13.8f * d);
            arrowPath.lineTo(ox + 14.5f * d, oy + 13.8f * d);
            arrowPath.close();
        }

        int hotspot() {
            return Math.round(16f * getResources().getDisplayMetrics().density);
        }

        void triggerRipple() {
            rippleStartMs = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float hot = 16f * d;
            if (rippleStartMs > 0) {
                long dt = SystemClock.uptimeMillis() - rippleStartMs;
                if (dt < 260) {
                    float t = dt / 260f;
                    ripplePaint.setAlpha(Math.round(210 * (1f - t)));
                    canvas.drawCircle(hot, hot, (4f + 13f * t) * d, ripplePaint);
                    postInvalidateOnAnimation();
                } else {
                    rippleStartMs = 0;
                }
            }
            canvas.save();
            canvas.translate(1.5f * d, 2.0f * d);
            canvas.drawPath(arrowPath, shadowPaint);
            canvas.restore();
            canvas.drawPath(arrowPath, fillPaint);
            canvas.drawPath(arrowPath, strokePaint);
        }
    }
}

