package com.abhinav.tvconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("TvConnectBoot", "Boot broadcast received: " + (intent != null ? intent.getAction() : "null"));
        TokenStore.getToken(context);
    }
}

