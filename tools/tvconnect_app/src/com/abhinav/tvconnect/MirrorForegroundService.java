package com.abhinav.tvconnect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class MirrorForegroundService extends Service {
    private static final String TAG = "TvMirrorSvc";
    private static final String CH_ID = "tvconnect_mirror";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";

    private static volatile MirrorForegroundService instance = null;
    private static volatile boolean active = false;
    private static volatile byte[] latestJpeg = null;
    private static volatile long latestSeq = 0;
    private static volatile int curWidth = 1280;
    private static volatile int curHeight = 720;
    private static volatile int curQuality = 80;
    private static volatile long lastConfigReqMs = 0;
    private static final Object frameLock = new Object();

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private HandlerThread encodeThread;
    private Handler encodeHandler;

    private volatile long lastEncodeMs = 0;
    private volatile boolean encodeBusy = false;

    // Owned exclusively by encodeHandler (and capture cleanup)
    private Bitmap reusableFull = null;
    private int reusableFullW = 0;
    private int reusableFullH = 0;
    private final ByteArrayOutputStream reusableBaos = new ByteArrayOutputStream(98304);

    public static boolean isActive() {
        return active && latestJpeg != null;
    }

    public static boolean isProjectionStarted() {
        return active;
    }

    public static byte[] getLatestJpeg() {
        return latestJpeg;
    }

    public static long getLatestSeq() {
        return latestSeq;
    }

    public static int getStreamWidth() {
        return curWidth;
    }

    public static int getStreamHeight() {
        return curHeight;
    }

    public static int getStreamQuality() {
        return curQuality;
    }

    public static void seedFrameIfEmpty(byte[] jpg) {
        if (jpg == null || jpg.length == 0) return;
        synchronized (frameLock) {
            if (latestJpeg == null) {
                latestJpeg = jpg;
                latestSeq++;
                frameLock.notifyAll();
            }
        }
    }

    public static void resolvePreset(int scale, String res, int quality, int[] outWHQ) {
        int w = 1280;
        int h = 720;
        int q = (quality >= 35 && quality <= 95) ? quality : 80;
        if (res != null && !res.isEmpty()) {
            String r = res.trim().toLowerCase();
            if ("1080p".equals(r) || "fhd".equals(r)) {
                w = 1920; h = 1080;
                if (quality <= 0) q = 82;
            } else if ("720p".equals(r) || "hd".equals(r)) {
                w = 1280; h = 720;
                if (quality <= 0) q = 80;
            } else if ("540p".equals(r) || "qhd".equals(r)) {
                w = 960; h = 540;
                if (quality <= 0) q = 72;
            } else if ("360p".equals(r) || "eco".equals(r)) {
                w = 640; h = 360;
                if (quality <= 0) q = 65;
            }
        } else if (scale == 1) {
            w = 1920; h = 1080;
            if (quality <= 0) q = 82;
        } else if (scale == 2) {
            w = 1280; h = 720;
            if (quality <= 0) q = 80;
        } else if (scale == 3) {
            w = 960; h = 540;
            if (quality <= 0) q = 72;
        } else if (scale >= 4) {
            w = 640; h = 360;
            if (quality <= 0) q = 65;
        }
        outWHQ[0] = w;
        outWHQ[1] = h;
        outWHQ[2] = q;
    }

    public static void configure(int targetW, int targetH, int targetQ) {
        int q = Math.max(35, Math.min(95, targetQ));
        curQuality = q;
        MirrorForegroundService svc = instance;
        if (svc == null || !active) {
            curWidth = targetW;
            curHeight = targetH;
            return;
        }
        if (targetW == curWidth && targetH == curHeight) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastConfigReqMs < 350) {
            return;
        }
        lastConfigReqMs = now;
        svc.requestResize(targetW, targetH);
    }

    public static byte[] waitNextFrame(long lastSeenSeq, long timeoutMs) {
        synchronized (frameLock) {
            if (latestJpeg != null && latestSeq != lastSeenSeq) {
                return latestJpeg;
            }
            try {
                frameLock.wait(timeoutMs);
            } catch (InterruptedException ignored) {}
            return latestJpeg;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        captureThread = new HandlerThread("MirrorCap", -4);
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        encodeThread = new HandlerThread("MirrorEnc", -2);
        encodeThread.start();
        encodeHandler = new Handler(encodeThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        cleanupCapture();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection = mpm.getMediaProjection(rc, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                cleanupCapture();
                stopSelf();
            }
        }, captureHandler);

        int w = curWidth;
        int h = curHeight;
        imageReader = buildReader(w, h);
        virtualDisplay = projection.createVirtualDisplay(
            "TvMirror",
            w,
            h,
            dpiForWidth(w),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            captureHandler
        );
        active = true;
        Log.i(TAG, "Hardware MediaProjection VirtualDisplay active (" + w + "x" + h + " q=" + curQuality + ")");
        return START_NOT_STICKY;
    }

    private static int dpiForWidth(int w) {
        if (w >= 1920) return 320;
        if (w >= 1280) return 213;
        return 160;
    }

    private ImageReader buildReader(final int targetW, final int targetH) {
        ImageReader reader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 4);
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null) return;
                long now = System.currentTimeMillis();
                if (encodeBusy || (now - lastEncodeMs < 16)) {
                    img.close();
                    return;
                }
                lastEncodeMs = now;
                encodeBusy = true;

                Image.Plane[] planes = img.getPlanes();
                if (planes == null || planes.length == 0) {
                    encodeBusy = false;
                    img.close();
                    return;
                }
                ByteBuffer buf = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * targetW;
                int bmpW = targetW + rowPadding / pixelStride;
                int bmpH = targetH;

                Bitmap snap;
                synchronized (this) {
                    if (reusableFull == null || reusableFullW != bmpW || reusableFullH != bmpH) {
                        if (reusableFull != null && !reusableFull.isRecycled()) {
                            reusableFull.recycle();
                        }
                        reusableFull = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
                        reusableFullW = bmpW;
                        reusableFullH = bmpH;
                    }
                    buf.rewind();
                    reusableFull.copyPixelsFromBuffer(buf);
                    snap = (bmpW == targetW)
                        ? reusableFull.copy(Bitmap.Config.ARGB_8888, false)
                        : Bitmap.createBitmap(reusableFull, 0, 0, targetW, targetH);
                }
                img.close();
                img = null;

                final Bitmap toEncode = snap;
                final int q = curQuality;
                encodeHandler.post(() -> {
                    try {
                        reusableBaos.reset();
                        toEncode.compress(Bitmap.CompressFormat.JPEG, q, reusableBaos);
                        toEncode.recycle();
                        byte[] jpg = reusableBaos.toByteArray();
                        synchronized (frameLock) {
                            latestJpeg = jpg;
                            latestSeq++;
                            frameLock.notifyAll();
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Async encode err: " + t.getMessage());
                    } finally {
                        encodeBusy = false;
                    }
                });
            } catch (Throwable t) {
                encodeBusy = false;
                Log.w(TAG, "Frame cap err: " + t.getMessage());
                if (img != null) {
                    try { img.close(); } catch (Throwable ignored) {}
                }
            }
        }, captureHandler);
        return reader;
    }

    private void requestResize(final int newW, final int newH) {
        captureHandler.post(() -> {
            if (!active || virtualDisplay == null) return;
            try {
                ImageReader oldReader = imageReader;
                ImageReader newReader = buildReader(newW, newH);
                virtualDisplay.resize(newW, newH, dpiForWidth(newW));
                virtualDisplay.setSurface(newReader.getSurface());
                imageReader = newReader;
                curWidth = newW;
                curHeight = newH;
                if (oldReader != null) {
                    try { oldReader.close(); } catch (Throwable ignored) {}
                }
                Log.i(TAG, "Resized VirtualDisplay -> " + newW + "x" + newH);
            } catch (Throwable t) {
                Log.w(TAG, "Resize failed: " + t.getMessage());
            }
        });
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "TV Live Mirror", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
            ? new Notification.Builder(this, CH_ID)
            : new Notification.Builder(this);
        Notification notif = nb.setContentTitle("TV Connect Live Mirror")
            .setContentText("HD hardware screen stream ready")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(101, notif);
        }
    }

    private void cleanupCapture() {
        active = false;
        encodeBusy = false;
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
        synchronized (this) {
            if (reusableFull != null && !reusableFull.isRecycled()) {
                reusableFull.recycle();
                reusableFull = null;
            }
            reusableFullW = 0;
            reusableFullH = 0;
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        cleanupCapture();
        if (captureThread != null) captureThread.quitSafely();
        if (encodeThread != null) encodeThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
