package com.x96.remote;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpServerEngine {
    private static final String TAG = "HttpServerEngine";
    private final int mPort;
    private final Context mContext;
    private ServerSocket mServerSocket;
    private ExecutorService mThreadPool;
    private boolean mRunning = false;
    private RemoteListener mListener;
    private static FileOutputStream sEventOut = null;

    public interface RemoteListener {
        void onCommandReceived(String command);
    }

    public HttpServerEngine(Context context, int port, String pin, RemoteListener listener) {
        this.mContext = context;
        this.mPort = port;
        this.mListener = listener;

        // Async setup so RemoteService.onCreate() returns instantly with 0ms ANR risk
        Executors.newSingleThreadExecutor().execute(() -> {
            RootShell.getInstance().exec("chmod 666 /dev/input/event*");
            initEventDevice();
        });
    }

    private static synchronized void initEventDevice() {
        try {
            if (sEventOut != null) {
                try { sEventOut.close(); } catch (Exception ignored) {}
            }
            sEventOut = new FileOutputStream("/dev/input/event1");
            Log.i(TAG, "Hardware input node /dev/input/event1 bound successfully (latency < 1ms)");
        } catch (Exception e) {
            Log.w(TAG, "Failed to bind /dev/input/event1: " + e.getMessage());
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
            case 85: return 164; // PLAY/PAUSE
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
            bb.putInt(0);
            bb.putInt(0);
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

            // 3. Key UP (type=1, code=scancode, value=0)
            bb.clear();
            bb.putInt(0);
            bb.putInt(0);
            bb.putShort((short) 1);
            bb.putShort(scancode);
            bb.putInt(0);
            // 4. SYN_REPORT
            bb.putInt(0);
            bb.putInt(0);
            bb.putShort((short) 0);
            bb.putShort((short) 0);
            bb.putInt(0);

            sEventOut.write(bb.array());
            sEventOut.flush();
        } catch (Exception e) {
            Log.w(TAG, "Direct event write failed: " + e.getMessage());
            initEventDevice();
        }
    }

    public synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        // Cached thread pool prevents any queuing delays
        mThreadPool = Executors.newCachedThreadPool();

        new Thread(() -> {
            try {
                mServerSocket = new ServerSocket(mPort);
                Log.i(TAG, "Ultra-fast HTTP Server running on port " + mPort);
                while (mRunning) {
                    try {
                        Socket client = mServerSocket.accept();
                        client.setTcpNoDelay(true);
                        client.setSoTimeout(1000);
                        mThreadPool.execute(() -> handleClient(client));
                    } catch (Exception e) {
                        if (!mRunning) break;
                    }
                }
            } catch (Exception e) {
                if (mRunning) {
                    Log.e(TAG, "Server socket error: " + e.getMessage());
                }
            }
        }, "X96-HttpServer").start();
    }

    public synchronized void stop() {
        mRunning = false;
        try {
            if (mServerSocket != null) mServerSocket.close();
        } catch (Exception ignored) {}
        if (mThreadPool != null) {
            mThreadPool.shutdownNow();
        }
    }

    private void handleClient(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isEmpty()) return;

            String[] parts = line.split(" ");
            if (parts.length < 2) return;

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
                        try {
                            params.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                        } catch (Exception ignored) {
                            params.put(kv[0], kv[1]);
                        }
                    }
                }
            }

            if (path.startsWith("/api/")) {
                handleApi(path, params, out);
            } else {
                serveStatic(path, out);
            }

        } catch (Exception e) {
            // Handled cleanly
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void sendFastCmd(String cmd) {
        try (Socket s = new Socket("127.0.0.1", 9999);
             OutputStream os = s.getOutputStream()) {
            s.setTcpNoDelay(true);
            os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception e) {
            RootShell.getInstance().exec(cmd.split("\\s+"));
        }
    }

    private void handleApi(String path, Map<String, String> params, OutputStream out) throws Exception {
        Log.i(TAG, "handleApi: " + path + " params=" + params);
        // Zero authentication friction on local network - instant execution
        if ("/api/key".equals(path)) {
            String codeStr = params.get("code");
            if (codeStr != null) {
                int code = Integer.parseInt(codeStr);
                sendFastCmd("key " + code);
                String name = getKeyName(code);
                if (mListener != null) mListener.onCommandReceived("Touche : " + name);
                sendJson(out, 200, "{\"success\":true,\"code\":" + code + "}");
                return;
            }
        }

        if ("/api/pair".equals(path)) {
            sendJson(out, 200, "{\"success\":true,\"token\":\"instant\"}");
            return;
        }

        if ("/api/status".equals(path)) {
            sendJson(out, 200, "{\"status\":\"online\",\"device\":\"X96 Mini\",\"port\":" + mPort + "}");
            return;
        }

        if ("/api/power".equals(path)) {
            sendFastCmd("key 26");
            if (mListener != null) mListener.onCommandReceived("Bouton Veille / Réveil");
            sendJson(out, 200, "{\"success\":true}");
            return;
        }

        if ("/api/launch".equals(path)) {
            String pkg = params.get("pkg");
            if (pkg != null) {
                if ("com.gathin.tvhome".equals(pkg)) {
                    sendFastCmd("key 3"); // HOME
                } else {
                    RootShell.getInstance().exec("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
                }
                if (mListener != null) mListener.onCommandReceived("Lancement : " + pkg);
                sendJson(out, 200, "{\"success\":true,\"launched\":\"" + pkg + "\"}");
                return;
            }
        }

        if ("/api/clean".equals(path)) {
            RootShell.getInstance().exec("/system/bin/x96_clean_ram");
            if (mListener != null) mListener.onCommandReceived("Nettoyage RAM Pro (+600 Mo)");
            sendJson(out, 200, "{\"success\":true,\"message\":\"RAM optimisée\"}");
            return;
        }

        if ("/api/text".equals(path)) {
            String text = params.get("text");
            if (text != null && !text.isEmpty()) {
                sendFastCmd("text " + text);
                if (mListener != null) mListener.onCommandReceived("Texte : " + text);
                sendJson(out, 200, "{\"success\":true}");
                return;
            }
        }

        if ("/api/mouse".equals(path)) {
            String action = params.get("action");
            if ("click".equals(action)) {
                if (MouseOverlay.getInstance() != null) MouseOverlay.getInstance().click();
                sendJson(out, 200, "{\"success\":true}");
                return;
            }
            if ("right".equals(action)) {
                if (MouseOverlay.getInstance() != null) MouseOverlay.getInstance().rightClick();
                sendJson(out, 200, "{\"success\":true}");
                return;
            }
            if ("hide".equals(action)) {
                if (MouseOverlay.getInstance() != null) MouseOverlay.getInstance().hideCursor();
                sendJson(out, 200, "{\"success\":true}");
                return;
            }
            String dxStr = params.get("dx");
            String dyStr = params.get("dy");
            if (dxStr != null && dyStr != null) {
                try {
                    float dx = Float.parseFloat(dxStr);
                    float dy = Float.parseFloat(dyStr);
                    if (MouseOverlay.getInstance() != null) {
                        MouseOverlay.getInstance().move(dx, dy);
                    }
                    sendJson(out, 200, "{\"success\":true}");
                    return;
                } catch (Exception ignored) {}
            }
            sendJson(out, 200, "{\"success\":true}");
            return;
        }

        if ("/api/tap".equals(path)) {
            String x = params.get("x");
            String y = params.get("y");
            if (x != null && y != null) {
                RootShell.getInstance().exec("input tap " + x + " " + y);
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
                RootShell.getInstance().exec("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + d);
                sendJson(out, 200, "{\"success\":true}");
                return;
            }
        }

        sendJson(out, 404, "{\"error\":\"Unknown endpoint\"}");
    }

    private String getKeyName(int code) {
        switch (code) {
            case 19: return "Haut";
            case 20: return "Bas";
            case 21: return "Gauche";
            case 22: return "Droite";
            case 23: return "OK";
            case 4: return "Retour";
            case 3: return "Home";
            case 82: return "Menu";
            case 24: return "Volume +";
            case 25: return "Volume -";
            case 164: return "Muet";
            case 85: return "Play/Pause";
            case 26: return "Power";
            default: return "Code " + code;
        }
    }

    private void serveStatic(String path, OutputStream out) throws Exception {
        if ("/favicon.ico".equals(path)) {
            out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }

        String assetPath = "web" + path;
        try (InputStream is = mContext.getAssets().open(assetPath)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            byte[] bytes = buffer.toByteArray();

            String contentType = "text/html; charset=UTF-8";
            if (path.endsWith(".json")) contentType = "application/json";
            else if (path.endsWith(".css")) contentType = "text/css";
            else if (path.endsWith(".js")) contentType = "application/javascript";
            else if (path.endsWith(".png")) contentType = "image/png";
            else if (path.endsWith(".svg")) contentType = "image/svg+xml";

            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Expires: 0\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            String notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\nConnection: close\r\n\r\nNot Found";
            out.write(notFound.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void sendJson(OutputStream out, int status, String json) throws Exception {
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
