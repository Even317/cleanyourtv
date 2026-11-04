package com.x96.remote;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class RootServer {
    private static final int PORT = 8080;
    private static final String PIN = "9696";
    private static String sToken = null;
    private static final String APK_PATH = "/system/priv-app/X96Remote/X96Remote.apk";
    private static final File TOKEN_FILE = new File("/data/local/tmp/x96_remote_token.txt");
    private static FileOutputStream sEventOut = null;

    public static void main(String[] args) {
        System.out.println("[X96 Remote] Starting ultra-low latency root server on port " + PORT + "...");
        loadOrGenerateToken();
        initEventDevice();

        ExecutorService threadPool = Executors.newFixedThreadPool(4);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[X96 Remote] Listening on 0.0.0.0:" + PORT);
            while (true) {
                Socket socket = serverSocket.accept();
                threadPool.execute(() -> handleClient(socket));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static synchronized void initEventDevice() {
        try {
            sEventOut = new FileOutputStream("/dev/input/event1");
            System.out.println("[X96 Remote] Bound to /dev/input/event1 (Direct Hardware Injection)");
        } catch (Exception e) {
            System.err.println("[X96 Remote] Failed to open /dev/input/event1: " + e.getMessage());
        }
    }

    private static short getLinuxScancode(int androidKeycode) {
        switch (androidKeycode) {
            case 19: return 103; // DPAD_UP
            case 20: return 108; // DPAD_DOWN
            case 21: return 105; // DPAD_LEFT
            case 22: return 106; // DPAD_RIGHT
            case 23: return 28;  // DPAD_CENTER / ENTER
            case 4:  return 158; // BACK
            case 3:  return 102; // HOME
            case 82: return 139; // MENU
            case 24: return 115; // VOLUME_UP
            case 25: return 114; // VOLUME_DOWN
            case 164: return 113;// VOLUME_MUTE
            case 26: return 116; // POWER
            case 66: return 28;  // ENTER
            case 67: return 14;  // DEL / BACKSPACE
            default: return 0;
        }
    }

    private static synchronized void sendDirectKey(short scancode) {
        if (sEventOut == null) {
            initEventDevice();
        }
        if (sEventOut == null) return;
        try {
            ByteBuffer bb = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
            // 1. Key DOWN (type=1, code=scancode, value=1)
            bb.putInt(0); // tv_sec
            bb.putInt(0); // tv_usec
            bb.putShort((short) 1);
            bb.putShort(scancode);
            bb.putInt(1);
            // 2. SYN_REPORT (type=0, code=0, value=0)
            bb.putInt(0);
            bb.putInt(0);
            bb.putShort((short) 0);
            bb.putShort((short) 0);
            bb.putInt(0);

            sEventOut.write(bb.array());
            sEventOut.flush();

            // Key UP
            bb.clear();
            bb.putInt(0);
            bb.putInt(0);
            bb.putShort((short) 1);
            bb.putShort(scancode);
            bb.putInt(0); // value=0 (release)

            bb.putInt(0);
            bb.putInt(0);
            bb.putShort((short) 0);
            bb.putShort((short) 0);
            bb.putInt(0);

            sEventOut.write(bb.array());
            sEventOut.flush();
        } catch (Exception e) {
            System.err.println("[X96 Remote] Direct event write failed: " + e.getMessage());
            initEventDevice();
        }
    }

    private static void loadOrGenerateToken() {
        try {
            if (TOKEN_FILE.exists()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(TOKEN_FILE)));
                sToken = r.readLine();
                r.close();
            }
            if (sToken == null || sToken.isEmpty()) {
                sToken = UUID.randomUUID().toString().substring(0, 16);
                FileOutputStream out = new FileOutputStream(TOKEN_FILE);
                out.write(sToken.getBytes(StandardCharsets.UTF_8));
                out.close();
            }
            System.out.println("[X96 Remote] Active Token: " + sToken);
        } catch (Exception e) {
            sToken = "47caceb8-1c00-47";
        }
    }

    private static void handleClient(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isEmpty()) return;

            String[] parts = line.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String fullPath = parts[1];

            String path = fullPath;
            Map<String, String> params = new HashMap<>();
            int qIndex = fullPath.indexOf('?');
            if (qIndex != -1) {
                path = fullPath.substring(0, qIndex);
                String queryString = fullPath.substring(qIndex + 1);
                for (String param : queryString.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        params.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                    }
                }
            }

            while ((line = reader.readLine()) != null && !line.isEmpty()) {}

            if (path.startsWith("/api/")) {
                handleApi(path, params, out);
            } else {
                serveStatic(path, out);
            }

        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static void handleApi(String path, Map<String, String> params, OutputStream out) throws Exception {
        String clientToken = params.get("token");

        if ("/api/pair".equals(path)) {
            String pin = params.get("pin");
            if (PIN.equals(pin)) {
                sendJson(out, 200, "{\"success\":true,\"token\":\"" + sToken + "\"}");
            } else {
                sendJson(out, 401, "{\"error\":\"Invalid PIN\"}");
            }
            return;
        }

        if (clientToken == null || !clientToken.equals(sToken)) {
            sendJson(out, 401, "{\"error\":\"Unauthorized - token required\"}");
            return;
        }

        if ("/api/status".equals(path)) {
            sendJson(out, 200, "{\"status\":\"online\",\"device\":\"X96 Mini\",\"port\":" + PORT + "}");
            return;
        }

        if ("/api/key".equals(path)) {
            String codeStr = params.get("code");
            if (codeStr != null) {
                int code = Integer.parseInt(codeStr);
                short scancode = getLinuxScancode(code);
                if (scancode > 0) {
                    // Instant < 1ms injection directly to Linux kernel character device
                    sendDirectKey(scancode);
                } else {
                    // Fallback for media keys via fast input binary
                    execCmd("input keyevent " + code);
                }
                sendJson(out, 200, "{\"success\":true,\"code\":" + code + "}");
                return;
            }
        }

        if ("/api/text".equals(path)) {
            String text = params.get("text");
            if (text != null) {
                String escaped = text.replace(" ", "%s").replace("'", "\\'");
                execCmd("input text '" + escaped + "'");
                sendJson(out, 200, "{\"success\":true}");
                return;
            }
        }

        if ("/api/tap".equals(path)) {
            String x = params.get("x");
            String y = params.get("y");
            if (x != null && y != null) {
                execCmd("input tap " + x + " " + y);
                sendJson(out, 200, "{\"success\":true}");
                return;
            }
        }

        if ("/api/swipe".equals(path)) {
            String x1 = params.get("x1");
            String y1 = params.get("y1");
            String x2 = params.get("x2");
            String y2 = params.get("y2");
            String d = params.get("d");
            if (d == null) d = "100";
            if (x1 != null && y1 != null && x2 != null && y2 != null) {
                execCmd("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + d);
                sendJson(out, 200, "{\"success\":true}");
                return;
            }
        }

        if ("/api/launch".equals(path)) {
            String pkg = params.get("pkg");
            if (pkg != null) {
                if ("com.gathin.tvhome".equals(pkg)) {
                    sendDirectKey((short) 102); // HOME
                } else {
                    execCmd("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
                }
                sendJson(out, 200, "{\"success\":true,\"launched\":\"" + pkg + "\"}");
                return;
            }
        }

        if ("/api/power".equals(path)) {
            sendDirectKey((short) 116); // POWER
            sendJson(out, 200, "{\"success\":true}");
            return;
        }

        if ("/api/clean".equals(path)) {
            execCmd("/system/bin/x96_clean_ram");
            sendJson(out, 200, "{\"success\":true,\"message\":\"RAM optimisée\"}");
            return;
        }

        sendJson(out, 404, "{\"error\":\"Unknown endpoint\"}");
    }

    private static void execCmd(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void serveStatic(String path, OutputStream out) throws Exception {
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }
        String entryName = "assets/web" + path;
        String contentType = "text/html; charset=UTF-8";
        if (path.endsWith(".json")) contentType = "application/json";
        else if (path.endsWith(".css")) contentType = "text/css";
        else if (path.endsWith(".js")) contentType = "application/javascript";
        else if (path.endsWith(".png")) contentType = "image/png";
        else if (path.endsWith(".svg")) contentType = "image/svg+xml";

        File overrideFile = new File("/data/local/tmp/web" + path);
        if (overrideFile.exists()) {
            byte[] bytes = new byte[(int) overrideFile.length()];
            try (FileInputStream fis = new FileInputStream(overrideFile)) {
                fis.read(bytes);
            }
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
            return;
        }

        File apkFile = new File(APK_PATH);
        if (!apkFile.exists()) {
            File dataApp = new File("/data/app");
            File[] files = dataApp.listFiles((dir, name) -> name.startsWith("com.x96.remote"));
            if (files != null && files.length > 0) {
                File baseApk = new File(files[0], "base.apk");
                if (baseApk.exists()) apkFile = baseApk;
            }
        }

        try (ZipFile zip = new ZipFile(apkFile)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                String notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\nConnection: close\r\n\r\nNot Found";
                out.write(notFound.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            InputStream is = zip.getInputStream(entry);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            byte[] bytes = buffer.toByteArray();

            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }
    }

    private static void sendJson(OutputStream out, int status, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }
}
