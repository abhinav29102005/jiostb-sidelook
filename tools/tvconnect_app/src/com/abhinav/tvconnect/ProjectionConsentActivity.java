package com.abhinav.tvconnect;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

public class ProjectionConsentActivity extends Activity {
    private static final int REQ_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TvAccessibilityService.consentRequestedAt = SystemClock.uptimeMillis();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finish();
            return;
        }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, MirrorForegroundService.class);
            svc.putExtra(MirrorForegroundService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(MirrorForegroundService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Log.i("TvConnectConsent", "Started MirrorForegroundService");
        }
        finish();
    }
}

