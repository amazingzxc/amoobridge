package com.stepx.amoobridge;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/** 本地 HTTP 接口：比文件信箱快，端口只开在内网，必须带令牌 */
public class HttpBridge {
    public static final int DEFAULT_PORT = 8896;
    private static volatile int port = 0;
    private static volatile boolean running = false;
    private static volatile String token = "";
    private static ServerSocket server;
    private static volatile long lastAcceptAt;

    public static int port() { return port; }

    public static boolean isRunning() { return running && server != null && port > 0; }

    /** Recreate the listener if a vendor restart or a transient socket failure removed it. */
    public static synchronized void ensureRunning() {
        if (!isRunning()) start(DEFAULT_PORT);
    }

    public static String tokenFile() { return BridgeService.BRIDGE_DIR + "/token.txt"; }

    public static synchronized String ensureToken() {
        if (token.length() > 0) return token;
        try {
            File f = new File(tokenFile());
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                in.read(b);
                in.close();
                token = new String(b, "UTF-8").trim();
            }
            if (token.length() < 8) {
                SecureRandom r = new SecureRandom();
                byte[] raw = new byte[16];
                r.nextBytes(raw);
                StringBuilder sb = new StringBuilder();
                for (byte x : raw) sb.append(String.format("%02x", x));
                token = sb.toString();
                File f2 = new File(tokenFile());
                File parent = f2.getParentFile();
                if (parent != null) parent.mkdirs();
                FileOutputStream fo = new FileOutputStream(f2);
                fo.write(token.getBytes("UTF-8"));
                fo.close();
            }
        } catch (Throwable ignored) {}
        return token;
    }

    public static synchronized void start(final int wantPort) {
        if (running) return;
        ensureToken();
        running = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
                    server = new ServerSocket(wantPort);
                    server.setReuseAddress(true);
                    server.setSoTimeout(5000);
                    port = wantPort;
                    while (running) {
                        try {
                            final Socket s = server.accept();
                            lastAcceptAt = System.currentTimeMillis();
                            Thread t = new Thread(new Runnable() {
                                public void run() {
                                    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND); }
                                    catch (Throwable ignored) {}
                                    handle(s);
                                }
                            }, "http-client");
                            t.start();
                        } catch (SocketTimeoutException ignored) {
                            // Periodically wake the accept loop so stop/restart is responsive.
                        }
                    }
                } catch (Throwable t) {
                    running = false;
                    port = 0;
                    try { if (server != null) server.close(); } catch (Throwable ignored) {}
                    server = null;
                }
            }
        }, "http-bridge").start();
    }

    public static synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        server = null;
        port = 0;
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String reqLine = readLine(in);
            if (reqLine == null) { s.close(); return; }
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0"); } catch (Throwable ignored) {}
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int remain = len;
            while (remain > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remain));
                if (n <= 0) break;
                bo.write(buf, 0, n);
                remain -= n;
            }
            String body = new String(bo.toByteArray(), "UTF-8");

            JSONObject resp = new JSONObject();
            int code = 200;
            String auth = headers.containsKey("authorization") ? headers.get("authorization") : "";
            if (!("Bearer " + ensureToken()).equals(auth)) {
                resp.put("ok", false).put("error", "unauthorized");
                code = 401;
            } else if (path.startsWith("/ping")) {
                resp.put("ok", true).put("pong", System.currentTimeMillis());
            } else if (path.startsWith("/status")) {
                resp.put("ok", true).put("up", running).put("port", port).put("mode", "lite")
                    .put("tools", Tools.NAMES.length);
                try {
                    File fcm = new File(BridgeService.BRIDGE_DIR, "fcm_status.json");
                    if (fcm.exists()) resp.put("fcm", new JSONObject(readSmall(fcm)));
                } catch (Throwable ignored) {}
            } else if (path.startsWith("/fcm")) {
                File token = new File(BridgeService.BRIDGE_DIR, "fcm_token.txt");
                File status = new File(BridgeService.BRIDGE_DIR, "fcm_status.json");
                resp.put("ok", true).put("has_token", token.exists() && token.length() > 0);
                if (status.exists()) {
                    try { resp.put("status", new JSONObject(readSmall(status))); } catch (Throwable ignored) {}
                }
            } else if (path.startsWith("/call")) {
                JSONObject req = new JSONObject(body.length() == 0 ? "{}" : body);
                String tool = req.optString("tool", "");
                JSONObject args = req.optJSONObject("args");
                if (args == null) args = new JSONObject();
                resp = Tools.dispatch(tool, args);
                resp.put("tool", tool);
            } else {
                resp.put("ok", false).put("error", "not found");
                code = 404;
            }

            byte[] outB = resp.toString().getBytes("UTF-8");
            String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + outB.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(outB);
            out.flush();
            s.close();
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static String readSmall(File f) {
        try {
            int len = (int) Math.min(f.length(), 8192);
            byte[] b = new byte[len];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < len) {
                int n = in.read(b, off, len - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static String readLine(InputStream in) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') bo.write(c);
                if (bo.size() > 8192) break;
            }
            if (bo.size() == 0 && c == -1) return null;
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
