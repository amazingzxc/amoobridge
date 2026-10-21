package com.stepx.amoobridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.content.pm.ServiceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;

public class BridgeService extends Service {
    public static final String BRIDGE_DIR = "/sdcard/bridge-amoobridge";
    private volatile boolean running = false;
    private volatile boolean destroyed = false;
    private Thread worker;
    private Thread httpWatchdog;
    private PowerManager.WakeLock wakeLock;
    private boolean temporaryWake = false;
    private final Handler stopHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopTemporary = new Runnable() {
        public void run() {
            if (temporaryWake) stopSelf();
        }
    };
    public static final String EXTRA_WAKE_WINDOW = "com.stepx.amoobridge.WAKE_WINDOW";
    public static final long WAKE_WINDOW_MS = 60 * 1000L;

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        Tools.init(this);
        promoteToForeground();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":bridge");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignored) {}
        WatchdogReceiver.schedule(this);
        BridgeJobService.schedule(this);
        HttpBridge.start(HttpBridge.DEFAULT_PORT);
        httpWatchdog = new Thread(new Runnable() {
            public void run() {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND); }
                catch (Throwable ignored) {}
                while (!destroyed) {
                    try {
                        HttpBridge.ensureRunning();
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable ignored) {
                        try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    }
                }
            }
        }, "http-watchdog");
        httpWatchdog.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // onStartCommand can be called after a vendor restart without a new onCreate.
        // Re-assert the foreground state and repair a listener that died independently.
        promoteToForeground();
        if (!HttpBridge.isRunning()) HttpBridge.start(HttpBridge.DEFAULT_PORT);
        boolean wakeRequested = intent != null && intent.getBooleanExtra(EXTRA_WAKE_WINDOW, false);
        if (!running) {
            temporaryWake = wakeRequested;
            running = true;
            worker = new Thread(new Runnable() {
                public void run() { loop(); }
            }, "bridge-worker");
            worker.start();
            if (temporaryWake) {
                stopHandler.removeCallbacks(stopTemporary);
                stopHandler.postDelayed(stopTemporary, WAKE_WINDOW_MS);
            }
        }
        return temporaryWake ? START_NOT_STICKY : START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        stopHandler.removeCallbacks(stopTemporary);
        if (httpWatchdog != null) httpWatchdog.interrupt();
        HttpBridge.stop();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        WatchdogReceiver.schedule(this);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        WatchdogReceiver.schedule(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void promoteToForeground() {
        try {
            Notification notification = buildNotification("桥已启动", "HTTP 与信箱就绪");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }
        } catch (Throwable ignored) {
            // Keep the service alive on older/vendor frameworks that reject the typed call.
            try { startForeground(1, buildNotification("桥已启动", "HTTP 与信箱就绪")); } catch (Throwable ignoredAgain) {}
        }
    }

    private Notification buildNotification(String title, String text) {
        String ch = "bridge";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel c = new NotificationChannel(ch, "StepBridge", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle(title).setContentText(text)
         .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true);
        return b.build();
    }

    public static void notifyText(String title, String text) {
        try {
            Context c = Tools.ctxRef();
            if (c == null) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("msg", "消息", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, "msg") : new Notification.Builder(c);
            b.setContentTitle(title).setContentText(text)
             .setSmallIcon(android.R.drawable.stat_notify_chat).setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
        } catch (Throwable ignored) {}
    }

    private void loop() {
        File dir = new File(BRIDGE_DIR);
        if (!dir.exists()) dir.mkdirs();
        long lastStatus = 0;
        while (running) {
            try {
                File[] files = dir.listFiles();
                if (files != null) {
                    Arrays.sort(files);
                    for (File f : files) {
                        String n = f.getName();
                        if (n.startsWith("cmd_") && n.endsWith(".json")) handle(f);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastStatus > 2000) {
                    lastStatus = now;
                    writeStatus();
                }
                Thread.sleep(120);
            } catch (Throwable t) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void writeStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("ts", System.currentTimeMillis());
            o.put("pid", android.os.Process.myPid());
            o.put("http_port", HttpBridge.port());
            o.put("mode", "lite");
            o.put("canWrite", new File(BRIDGE_DIR).canWrite());
            try {
                o.put("ver", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            } catch (Throwable ignored) {}
            JSONArray a = new JSONArray();
            for (String t : Tools.NAMES) a.put(t);
            o.put("tools", a);
            writeAtomic(new File(BRIDGE_DIR, "status.json"), o.toString());
        } catch (Throwable ignored) {}
    }

    private void handle(File f) {
        String id = null;
        JSONObject result = new JSONObject();
        try {
            String text = readText(f, 1024 * 512);
            JSONObject cmd = new JSONObject(text);
            id = cmd.optString("id", f.getName().substring(4, f.getName().length() - 5));
            String tool = cmd.optString("tool", "");
            JSONObject args = cmd.optJSONObject("args");
            if (args == null) args = new JSONObject();
            JSONObject out = Tools.dispatch(tool, args);
            result.put("id", id).put("tool", tool).put("ts", System.currentTimeMillis());
            java.util.Iterator<String> it = out.keys();
            while (it.hasNext()) {
                String k = it.next();
                result.put(k, out.get(k));
            }
        } catch (Throwable t) {
            try {
                result.put("ok", false).put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                if (id == null) {
                    String n = f.getName();
                    id = n.substring(4, n.length() - 5);
                    result.put("id", id);
                }
            } catch (Throwable ignored) {}
        }
        try {
            writeAtomic(new File(BRIDGE_DIR, "result_" + id + ".json"), result.toString());
        } catch (Throwable ignored) {}
        try { f.delete(); } catch (Throwable ignored) {}
    }

    private String readText(File f, int max) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() >= max) break;
        }
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private void writeAtomic(File f, String content) throws Exception {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        fo.write(content.getBytes("UTF-8"));
        fo.flush();
        fo.getFD().sync();
        fo.close();
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }
}
