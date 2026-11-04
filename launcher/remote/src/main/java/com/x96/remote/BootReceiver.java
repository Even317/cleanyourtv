package com.x96.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
            Log.i(TAG, "Boot received, launching RemoteService");
            Intent serviceIntent = new Intent(context, RemoteService.class);
            context.startService(serviceIntent);
        }
    }
}
