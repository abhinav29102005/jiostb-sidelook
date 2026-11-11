package com.abhinav.cinejoy;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://cinejoy.pk/";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private FrameLayout rootLayout;
    private WebView webView;
    private CursorOverlay cursorOverlay;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private float cursorX = 960f;
    private float cursorY = 540f;
    private long lastDpadTime = 0L;
    private int repeatBoost = 0;
    private long lastBackPress = 0L;

    private static class CursorOverlay extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x = 960f;
        private float y = 540f;
        private boolean visible = true;

        CursorOverlay(Context context) {
            super(context);
            fillPaint.setColor(Color.argb(210, 149, 255, 80));
            fillPaint.setStyle(Paint.Style.FILL);
            ringPaint.setColor(Color.argb(235, 10, 10, 12));
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(4f);
        }

        void update(float nx, float ny, boolean show) {
            x = nx;
            y = ny;
            visible = show;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!visible) return;
            canvas.drawCircle(x, y, 13f, fillPaint);
            canvas.drawCircle(x, y, 13f, ringPaint);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#050505"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#050505"));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(DESKTOP_UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    String host = request.getUrl().getHost();
                    if (host != null && (host.contains("cinejoy") || host.contains("ddos-guard"))) {
                        return false;
                    }
                    // Allow embedded navigation inside same site; block blatant ad popups
                    if (request.isForMainFrame() && !request.hasGesture()) {
                        return true;
                    }
                    return false;
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                rootLayout.addView(customView, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                cursorOverlay.update(cursorX, cursorY, false);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                rootLayout.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                webView.setVisibility(View.VISIBLE);
                cursorOverlay.update(cursorX, cursorY, true);
                hideSystemUi();
            }
        });

        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        cursorOverlay = new CursorOverlay(this);
        rootLayout.addView(cursorOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);
        hideSystemUi();

        rootLayout.post(() -> {
            cursorX = Math.max(100f, rootLayout.getWidth() / 2f);
            cursorY = Math.max(100f, rootLayout.getHeight() / 2f);
            cursorOverlay.update(cursorX, cursorY, true);
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void clickAtCursor() {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, cursorX, cursorY, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        View target = (customView != null) ? customView : webView;
        target.dispatchTouchEvent(down);
        target.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                long now = SystemClock.uptimeMillis();
                if (now - lastDpadTime < 220) {
                    repeatBoost = Math.min(repeatBoost + 1, 14);
                } else {
                    repeatBoost = 0;
                }
                lastDpadTime = now;
                float step = 20f + repeatBoost * 4.5f;

                int w = Math.max(1, rootLayout.getWidth());
                int h = Math.max(1, rootLayout.getHeight());
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) cursorX -= step;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) cursorX += step;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (cursorY <= 60f && customView == null) {
                        webView.scrollBy(0, -140);
                    } else {
                        cursorY -= step;
                    }
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (cursorY >= h - 60f && customView == null) {
                        webView.scrollBy(0, 140);
                    } else {
                        cursorY += step;
                    }
                }
                cursorX = Math.max(8f, Math.min(w - 8f, cursorX));
                cursorY = Math.max(8f, Math.min(h - 8f, cursorY));
                cursorOverlay.update(cursorX, cursorY, customView == null);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                clickAtCursor();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                webView.loadUrl(HOME_URL);
                Toast.makeText(this, "Reloading Cinejoy", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (customView != null && webView.getWebChromeClient() != null) {
            webView.getWebChromeClient().onHideCustomView();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBackPress < 1800) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "Press Back again to exit Cinejoy", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
