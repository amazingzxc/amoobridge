package com.stepx.amoobridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/**
 * Keeps the bridge out of StepOS' uid freezer while it is being used.
 *
 * <p>Background: StepOS freezes the whole process by writing to
 * {@code /sys/fs/cgroup/apps/uid_<uid>/cgroup.freeze}. A frozen process runs no
 * threads at all, so an incoming HTTP request is not even accepted until some
 * system -&gt; app binder call thaws the uid ("BINDER_CALL_UNFREEZE" in
 * {@code StepOSFreeze[Logging]}); it then freezes again ~3s later. That is the
 * whole 25-40s {@code ui_dump} / 65s {@code ui_click} mystery: the a11y
 * traversal itself measures 7-20ms once the process is running.
 *
 * <p>Two cheap mechanisms keep the uid awake, both observed to make StepOS
 * cancel a pending freeze ("BLOCK_FREEZE ... add flag: FLAG_USING_SERVICE"):
 *
 * <ol>
 *   <li>{@link #TICK_MS} thread re-starting {@link BridgeService}. Service
 *       usage is a freezer flag, and the flag has a 3s timeout, so a tick
 *       shorter than that keeps the timer from ever expiring.</li>
 *   <li>A self-rearming alarm that delivers a broadcast from system_server.
 *       This is the fallback that thaws a process that did freeze anyway, and
 *       restarts the keeper afterwards.</li>
 * </ol>
 *
 * <p>Both only run while the bridge is being used: every inbound request opens
 * a {@link #WINDOW_MS} hot window. {@link #setAlwaysHot(boolean)} keeps it open
 * permanently for unattended agent sessions through the {@code keep_hot} tool.
 */
final class KeepHot {
    private static final String TAG = "BridgeKeepHot";

    /** How long one inbound request keeps the process out of the freezer. */
    static final long WINDOW_MS = 30000;
    /** Keeper cadence. Must stay well below StepOS' 3000ms freeze timer. */
    private static final long TICK_MS = 2000;
    /** A keeper gap longer than this means the process really was frozen. */
    private static final long GAP_MS = 6000;
    /** How far ahead the safety alarm is armed while hot. */
    private static final long SAFETY_MS = 12000;

    private static volatile Context appCtx;
    private static volatile boolean started;
    private static volatile boolean alwaysHot;
    private static volatile long hotUntil;
    private static volatile long lastTick;
    private static volatile long lastSafetyAt;

    private static volatile int freezeGaps;
    private static volatile long lastFreezeGapMs;
    private static volatile long lastFreezeAt;

    private KeepHot() {}

    static synchronized void init(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        if (started || appCtx == null) return;
        started = true;
        lastTick = SystemClock.elapsedRealtime();
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "keep-hot");
        t.setDaemon(true);
        t.start();
    }

    /** Called on every bridge request: the process has work, keep it running. */
    static void touch() {
        hotUntil = SystemClock.elapsedRealtime() + WINDOW_MS;
    }

    static void touch(long windowMs) {
        long w = windowMs > 0 ? windowMs : WINDOW_MS;
        long until = SystemClock.elapsedRealtime() + Math.min(w, 30 * 60 * 1000L);
        if (until > hotUntil) hotUntil = until;
    }

    static void setAlwaysHot(boolean on) {
        alwaysHot = on;
        if (on) {
            hotUntil = Long.MAX_VALUE;
        } else {
            touch();
        }
        Log.i(TAG, "always hot = " + on);
    }

    static boolean alwaysHot() {
        return alwaysHot;
    }

    static boolean hot() {
        return alwaysHot || SystemClock.elapsedRealtime() < hotUntil;
    }

    private static void loop() {
        while (true) {
            try {
                long now = SystemClock.elapsedRealtime();
                long gap = now - lastTick;
                if (gap > GAP_MS) {
                    freezeGaps++;
                    lastFreezeGapMs = gap - TICK_MS;
                    lastFreezeAt = System.currentTimeMillis();
                    Log.w(TAG, "freeze gap " + gap + "ms: process was frozen while hot");
                }
                lastTick = now;
                if (hot()) {
                    poke();
                    // Re-arm only after the previous alarm had a chance to fire:
                    // re-arming on every tick would push it forward forever and
                    // the backstop would never be delivered.
                    if (now - lastSafetyAt >= SAFETY_MS) {
                        lastSafetyAt = now;
                        armSafety();
                    }
                }
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /**
     * Re-start the bridge service. ActiveServices records this as service
     * usage, which is one of the conditions StepOS uses to block a freeze.
     */
    private static void poke() {
        Context c = appCtx;
        if (c == null) return;
        Intent i = new Intent(c, BridgeService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            try {
                c.startService(new Intent(c, BridgeService.class));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Arm a broadcast that arrives from system_server and thaws a frozen uid. */
    private static void armSafety() {
        Context c = appCtx;
        if (c == null) return;
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, 1002,
                    new Intent(c, KeepaliveReceiver.class), flags);
            long at = SystemClock.elapsedRealtime() + SAFETY_MS;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // Exact alarms are denied on targetSdk 34 unless the user or adb
                // grants SCHEDULE_EXACT_ALARM; this one is still allowed while idle.
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Package-private for {@link KeepaliveReceiver}. Deliberately does not call
     * {@link #touch()}: the alarm exists to thaw a process that is already
     * inside a hot window, and must not extend the window itself, otherwise the
     * chain would keep itself alive forever after the last real request.
     */
    static void onSafetyAlarm() {
        if (!hot()) return;
        poke();
    }

    static void fillStatus(JSONObject o) throws Exception {
        boolean h = hot();
        o.put("hot", h);
        o.put("always_hot", alwaysHot);
        if (h && !alwaysHot) {
            long left = hotUntil - SystemClock.elapsedRealtime();
            o.put("hot_left_ms", Math.max(0, left));
        }
        o.put("freeze_gaps", freezeGaps);
        if (freezeGaps > 0) {
            o.put("last_freeze_gap_ms", lastFreezeGapMs);
            o.put("last_freeze_at", lastFreezeAt);
        }
    }

    static JSONObject tool(JSONObject args) throws Exception {
        JSONObject r = new JSONObject();
        if (args.has("on")) {
            setAlwaysHot(args.optBoolean("on", false));
        }
        if (args.has("window")) {
            touch(args.optLong("window", WINDOW_MS));
        }
        if (!args.has("on") && !args.has("window")) {
            // A bare call is a no-op refresh, so agents can use it as a warm-up.
            touch();
        }
        r.put("ok", true);
        fillStatus(r);
        r.put("window_ms", WINDOW_MS);
        return r;
    }
}
