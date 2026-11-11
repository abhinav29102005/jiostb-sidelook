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

    private static volatile boolean active = false;
    private static volatile byte[] latestJpeg = null;
    private static volatile long latestSeq = 0;
    private static final Object frameLock = new Object();

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private volatile long lastEncodeMs = 0;

    private final int width = 960;
    private final int height = 540;

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
        workerThread = new HandlerThread("MirrorEncode");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
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
        }, workerHandler);

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                long now = System.currentTimeMillis();
                if (now - lastEncodeMs < 45) {
                    return;
                }
                lastEncodeMs = now;
                Image.Plane[] planes = img.getPlanes();
                if (planes == null || planes.length == 0) return;
                ByteBuffer buf = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * width;
                int bmpW = width + rowPadding / pixelStride;

                Bitmap full = Bitmap.createBitmap(bmpW, height, Bitmap.Config.ARGB_8888);
                full.copyPixelsFromBuffer(buf);
                Bitmap cropped = (bmpW == width) ? full : Bitmap.createBitmap(full, 0, 0, width, height);
                if (cropped != full) full.recycle();

                ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
                cropped.compress(Bitmap.CompressFormat.JPEG, 68, baos);
                cropped.recycle();

                byte[] jpg = baos.toByteArray();
                synchronized (frameLock) {
                    latestJpeg = jpg;
                    latestSeq++;
                    frameLock.notifyAll();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Frame encode err: " + t.getMessage());
            } finally {
                if (img != null) img.close();
            }
        }, workerHandler);

        virtualDisplay = projection.createVirtualDisplay(
            "TvMirror",
            width,
            height,
            160,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            workerHandler
        );
        active = true;
        Log.i(TAG, "Hardware MediaProjection VirtualDisplay active (" + width + "x" + height + ")");
        return START_NOT_STICKY;
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
            .setContentText("Hardware screen stream ready")
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
    }

    @Override
    public void onDestroy() {
        cleanupCapture();
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

