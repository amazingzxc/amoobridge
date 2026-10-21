package com.stepx.amoobridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns FCM token acquisition and diagnostics.
 *
 * firebase-messaging does not request a token by itself on a fresh install: the
 * token is only created after getToken() is called (or after some other FCM API
 * is used). Without this class /sdcard/bridge-amoobridge/fcm_token.txt stays
 * empty forever even on a device with working Google Play services.
 *
 * Token requests can fail transiently while Google Play services is starting
 * (SERVICE_NOT_AVAILABLE) or hang while a virtual GMS (GBox / microG) is not
 * answering, so every attempt has a timeout, is retried with a bounded backoff,
 * and the last state is written to fcm_status.json for adb inspection.
 */
public final class BridgeFcm {
    private static final String TAG = "BridgeFCM";
    private static final long FIRST_RETRY_MS = 2 * 60 * 1000L;
    private static final long MAX_RETRY_MS = 30 * 60 * 1000L;
    private static final long TIMEOUT_MS = 60 * 1000L;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable requestRunnable = new Runnable() {
        public void run() { requestToken(); }
    };

    private static final AtomicInteger generation = new AtomicInteger(0);
    private static volatile boolean inFlight = false;
    private static volatile int failures = 0;
    private static volatile boolean started = false;

    private BridgeFcm() {}

    /** Start the token acquisition loop. Safe to call from every process entry point. */
    public static synchronized void start(Context ctx) {
        if (ctx != null) Tools.init(ctx);
        if (started) return;
        started = true;
        Log.i(TAG, "FCM token loop started");
        handler.post(requestRunnable);
    }

    private static void requestToken() {
        if (inFlight) return;
        inFlight = true;
        final int attempt = failures + 1;
        final int gen = generation.incrementAndGet();
        Log.i(TAG, "Requesting FCM token, attempt " + attempt);

        final Runnable timeout = new Runnable() {
            public void run() {
                if (gen != generation.get() || !inFlight) return;
                inFlight = false;
                failures++;
                String msg = "timeout after " + (TIMEOUT_MS / 1000) + "s waiting for Google Play services";
                writeStatus("timeout", msg, null);
                Log.w(TAG, msg + " (attempt " + attempt + ")");
                scheduleRetry();
            }
        };
        handler.postDelayed(timeout, TIMEOUT_MS);

        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                handler.removeCallbacks(timeout);
                if (gen != generation.get()) return;
                inFlight = false;
                if (task.isSuccessful() && task.getResult() != null
                        && task.getResult().length() > 0) {
                    failures = 0;
                    String token = task.getResult();
                    persistToken(token);
                    writeStatus("ok", null, token);
                    Log.i(TAG, "FCM token acquired: " + mask(token));
                } else {
                    failures++;
                    Throwable t = task.getException();
                    String msg = t == null ? "empty token" : (t.getClass().getSimpleName() + ": " + t.getMessage());
                    writeStatus("error", msg, null);
                    Log.w(TAG, "FCM token request failed (" + failures + "): " + msg);
                    scheduleRetry();
                }
            });
        } catch (Throwable t) {
            handler.removeCallbacks(timeout);
            inFlight = false;
            failures++;
            writeStatus("error", t.getClass().getSimpleName() + ": " + t.getMessage(), null);
            Log.w(TAG, "FCM token request threw", t);
            scheduleRetry();
        }
    }

    private static void scheduleRetry() {
        long delay = FIRST_RETRY_MS;
        for (int i = 1; i < failures && delay < MAX_RETRY_MS; i++) delay *= 2;
        if (delay > MAX_RETRY_MS) delay = MAX_RETRY_MS;
        handler.removeCallbacks(requestRunnable);
        handler.postDelayed(requestRunnable, delay);
    }

    static void persistToken(String token) {
        File f = new File(BridgeService.BRIDGE_DIR, "fcm_token.txt");
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(token.getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to persist FCM token", t);
        }
    }

    static void writeStatus(String state, String error, String token) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("ts", System.currentTimeMillis());
            o.put("pid", android.os.Process.myPid());
            if (error != null) o.put("error", error);
            if (token != null) o.put("token", mask(token));
            File f = new File(BridgeService.BRIDGE_DIR, "fcm_status.json");
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {}
    }

    static String mask(String token) {
        if (token == null) return "";
        return token.length() <= 12 ? token : token.substring(0, 6) + "..." + token.substring(token.length() - 6);
    }
}
