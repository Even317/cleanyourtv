package com.x96.remote;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class RemoteService extends Service implements HttpServerEngine.RemoteListener {
    private static final String TAG = "RemoteService";
    public static final String ACTION_CMD = "com.x96.remote.CMD";
    public static final String EXTRA_LOG = "log_msg";

    private final IBinder mBinder = new LocalBinder();
    private HttpServerEngine mServer;
    private String mPin = "9696";

    public class LocalBinder extends Binder {
        public RemoteService getService() {
            return RemoteService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences("remote_prefs", Context.MODE_PRIVATE);
        mPin = prefs.getString("pin", "9696");

        mServer = new HttpServerEngine(this, 8080, mPin, this);
        mServer.start();
        MouseOverlay.init(this);
        Log.i(TAG, "RemoteService started with PIN " + mPin);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public String getPin() {
        return mPin;
    }

    public void setPin(String pin) {
        mPin = pin;
        getSharedPreferences("remote_prefs", Context.MODE_PRIVATE).edit().putString("pin", pin).apply();
        if (mServer != null) {
            mServer.stop();
            mServer = new HttpServerEngine(this, 8080, mPin, this);
            mServer.start();
        }
    }

    @Override
    public void onCommandReceived(String command) {
        Intent intent = new Intent(ACTION_CMD);
        intent.putExtra(EXTRA_LOG, command);
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
        RootShell.getInstance().close();
        super.onDestroy();
    }
}
