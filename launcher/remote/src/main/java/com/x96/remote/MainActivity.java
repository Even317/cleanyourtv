package com.x96.remote;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView mTxtUrl;
    private TextView mTxtPin;
    private TextView mTxtLogs;
    private ScrollView mScrollLog;
    private final StringBuilder mLogBuffer = new StringBuilder();

    private final BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra(RemoteService.EXTRA_LOG);
            if (msg != null) {
                appendLog(msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTxtUrl = findViewById(R.id.txtUrl);
        mTxtPin = findViewById(R.id.txtPin);
        mTxtLogs = findViewById(R.id.txtLogs);
        mScrollLog = findViewById(R.id.scrollLog);

        Button btnHome = findViewById(R.id.btnHome);
        Button btnTest = findViewById(R.id.btnTest);

        // Start RemoteService
        Intent serviceIntent = new Intent(this, RemoteService.class);
        startService(serviceIntent);

        // Resolve Local IP
        String ip = getLocalIpAddress();
        mTxtUrl.setText("http://" + ip + ":8080");

        btnHome.setOnClickListener(v -> {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
        });

        btnTest.setOnClickListener(v -> {
            RootShell.getInstance().exec("input keyevent 24"); // Volume Up
            Toast.makeText(this, "Test Volume + envoyé via injection", Toast.LENGTH_SHORT).show();
            appendLog("Test manuel d'injection exécuté (Volume +)");
        });

        registerReceiver(mLogReceiver, new IntentFilter(RemoteService.ACTION_CMD));
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        mLogBuffer.append("\n[").append(time).append("] ").append(message);
        mTxtLogs.setText(mLogBuffer.toString());
        mScrollLog.post(() -> mScrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                WifiInfo winfo = wm.getConnectionInfo();
                return Formatter.formatIpAddress(winfo.getIpAddress());
            }
        } catch (Exception ignored) {}

        return "192.168.1.28";
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(mLogReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
