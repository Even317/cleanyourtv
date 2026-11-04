package com.x96.remote;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class RootShell {
    private static final String TAG = "RootShell";
    private static RootShell sInstance;

    public static synchronized RootShell getInstance() {
        if (sInstance == null) {
            sInstance = new RootShell();
        }
        return sInstance;
    }

    private RootShell() {}

    public void exec(String... args) {
        if (args == null || args.length == 0) return;

        // Convert command for x96_cmd bridge
        String cmdLine;
        if (args.length >= 3 && "input".equals(args[0]) && "text".equals(args[1])) {
            cmdLine = "text " + args[2];
        } else if (args.length >= 3 && "input".equals(args[0]) && "keyevent".equals(args[1])) {
            cmdLine = "key " + args[2];
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(args[i]);
            }
            cmdLine = sb.toString();
        }

        // 1. Root FIFO Bridge (Instant execution as root UID 0)
        try {
            File pipe = new File("/dev/x96_cmd");
            if (pipe.exists()) {
                try (FileOutputStream fos = new FileOutputStream(pipe)) {
                    fos.write((cmdLine + "\n").getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "FIFO bridge write failed: " + e.getMessage());
        }

        // 2. Direct privileged execution fallback
        try {
            Process p = Runtime.getRuntime().exec(args);
            int code = p.waitFor();
            if (code == 0) return;
        } catch (Exception ignored) {}

        // 3. Su elevation fallback
        try {
            String[] suCmd = new String[args.length + 2];
            suCmd[0] = "/system/xbin/su";
            suCmd[1] = "0";
            System.arraycopy(args, 0, suCmd, 2, args.length);
            Process p = Runtime.getRuntime().exec(suCmd);
            p.waitFor();
        } catch (Exception ignored) {}
    }

    public void exec(String singleCmd) {
        if (singleCmd == null || singleCmd.trim().isEmpty()) return;
        String[] parts = singleCmd.trim().split("\\s+");
        exec(parts);
    }

    public void close() {}
}
