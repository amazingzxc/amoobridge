package com.stepx.amoobridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bridges the HTTP/file-mailbox tools to {@link BridgeAccessibilityService}. */
final class UiTools {
    private UiTools() {}

    private static JSONObject noService() throws Exception {
        return new JSONObject().put("ok", false)
                .put("error", "accessibility service not enabled");
    }

    private static BridgeAccessibilityService service() {
        return BridgeAccessibilityService.get();
    }

    /**
     * Decode the optional target selector. Absent/empty arguments leave
     * {@code explicit=false} so the call keeps the legacy active-window
     * behaviour, including no bounds checks.
     */
    private static Windows.Spec spec(JSONObject args) {
        Windows.Spec s = new Windows.Spec();
        if (args.has("window_id")) {
            s.windowIdSet = true;
            s.windowId = args.optInt("window_id", -1);
            s.explicit = true;
        }
        String pkg = args.optString("package", "");
        if (pkg.length() > 0) {
            s.pkg = pkg;
            s.explicit = true;
        }
        if (args.optBoolean("pip", false)) {
            s.pip = true;
            s.explicit = true;
        }
        return s;
    }

    static JSONObject tap(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        float x = (float) args.optDouble("x", Double.NaN);
        float y = (float) args.optDouble("y", Double.NaN);
        if (Float.isNaN(x) || Float.isNaN(y)) {
            return new JSONObject().put("ok", false).put("error", "x/y required");
        }
        int duration = Math.max(1, Math.min(args.optInt("duration", 60), 5000));
        Windows.Spec spec = spec(args);
        if (spec.explicit) return service.tapTarget(spec, x, y, duration);
        boolean ok = service.tap(x, y, duration);
        return new JSONObject().put("ok", ok).put("x", x).put("y", y).put("duration", duration);
    }

    static JSONObject swipe(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        float x1 = (float) args.optDouble("x1", Double.NaN);
        float y1 = (float) args.optDouble("y1", Double.NaN);
        float x2 = (float) args.optDouble("x2", Double.NaN);
        float y2 = (float) args.optDouble("y2", Double.NaN);
        if (Float.isNaN(x1) || Float.isNaN(y1) || Float.isNaN(x2) || Float.isNaN(y2)) {
            return new JSONObject().put("ok", false).put("error", "x1/y1/x2/y2 required");
        }
        int duration = Math.max(20, Math.min(args.optInt("duration", 300), 10000));
        int hold = Math.max(0, Math.min(args.optInt("hold", 0), 3000));
        Windows.Spec spec = spec(args);
        if (spec.explicit) return service.swipeTarget(spec, x1, y1, x2, y2, duration, hold);
        boolean ok = hold > 0
                ? service.swipeHold(x1, y1, x2, y2, duration, hold)
                : service.swipe(x1, y1, x2, y2, duration);
        return new JSONObject().put("ok", ok)
                .put("from", new org.json.JSONArray().put(x1).put(y1))
                .put("to", new org.json.JSONArray().put(x2).put(y2))
                .put("duration", duration)
                .put("hold", hold);
    }

    private static int budget(JSONObject args) {
        return Math.max(200, Math.min(args.optInt("budget", BridgeAccessibilityService.DEFAULT_BUDGET_MS),
                15000));
    }

    static JSONObject click(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        JSONObject r = service.clickText(spec(args), args.optString("text", ""), budget(args));
        KeepHot.fillStatus(r);
        return r;
    }

    static JSONObject dump(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        int max = Math.max(1, Math.min(args.optInt("max", BridgeAccessibilityService.DEFAULT_MAX_NODES),
                5000));
        JSONObject result = service.dump(spec(args), budget(args), max);
        if (!result.optBoolean("ok", false)) return result;
        File file = new File(BridgeService.BRIDGE_DIR, "ui_dump.json");
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        long writeStart = android.os.SystemClock.elapsedRealtime();
        FileOutputStream out = new FileOutputStream(file, false);
        out.write(result.toString().getBytes("UTF-8"));
        out.close();
        JSONObject reply = new JSONObject();
        reply.put("ok", true);
        reply.put("file", file.getAbsolutePath());
        reply.put("count", result.optInt("count", 0));
        String[] copied = {"package", "window_id", "bounds", "selection", "pip_candidate",
                "active", "focused", "layer", "pip_controls_visible", "overlays", "message"};
        for (String k : copied) {
            if (result.has(k)) reply.put(k, result.get(k));
        }
        reply.put("partial", result.optBoolean("partial", false));
        reply.put("root_ms", result.optLong("root_ms", 0));
        reply.put("walk_ms", result.optLong("walk_ms", 0));
        reply.put("write_ms", android.os.SystemClock.elapsedRealtime() - writeStart);
        reply.put("elapsed_ms", result.optLong("elapsed_ms", 0));
        KeepHot.fillStatus(reply);
        return reply;
    }

    static JSONObject shot(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        File file = new File(BridgeService.BRIDGE_DIR,
                "shot_" + System.currentTimeMillis() + ".png");
        final CountDownLatch latch = new CountDownLatch(1);
        final JSONObject[] holder = new JSONObject[]{null};
        service.screenshot(file, new BridgeAccessibilityService.ShotCallback() {
            public void done(boolean ok, String message) {
                try {
                    holder[0] = new JSONObject().put("ok", ok);
                    if (ok) holder[0].put("file", message);
                    else holder[0].put("error", message);
                } catch (Throwable ignored) {
                }
                latch.countDown();
            }
        });
        latch.await(10, TimeUnit.SECONDS);
        if (holder[0] == null) {
            return new JSONObject().put("ok", false).put("error", "screenshot timeout");
        }
        return holder[0];
    }

    /** List interactive windows so an agent can pick a PiP target explicitly. */
    static JSONObject windows(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        JSONObject r = service.listWindows();
        KeepHot.fillStatus(r);
        return r;
    }

    /** PiP session: inspect / select / clear / recover / apps. */
    static JSONObject pipTarget(JSONObject args) throws Exception {
        JSONObject r = PipTarget.tool(args);
        KeepHot.fillStatus(r);
        return r;
    }

    /**
     * StepOS-only: put an app into StepOS' native "stepos-mini-window" mode.
     *
     * <p>Preferred path: launch the package's launcher intent with the hidden
     * ActivityOptions key {@code android.activity.windowingMode=11}
     * ({@code WindowConfiguration.isStepOSMiniWindow}). This works from the
     * bridge UID, even with the bridge activity in the background, and takes
     * about 1.5s; no recents/menu gestures are needed.
     *
     * <p>Fallback: recents task-card menu via two hold gestures, used when the
     * native launch fails or the app ignores the mode. The fallback needs the
     * app in the foreground, so {@code app_not_foreground} only applies to it.
     */
    static JSONObject openSmallWindow(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        JSONObject r = new JSONObject();
        String pkg = args.optString("package", "");
        if (pkg.length() == 0) {
            return r.put("ok", false).put("error", "package required");
        }
        long started = android.os.SystemClock.elapsedRealtime();
        android.graphics.Rect screen = Windows.screen(service);
        Windows.Snapshot existing = null;
        Windows.Snapshot active = null;
        for (Windows.Snapshot s : Windows.list(service)) {
            if (s.type != Windows.TYPE_APPLICATION || !pkg.equals(s.pkg)) continue;
            if (isSmallWindow(s, screen)) existing = s;
            if (s.active || s.focused) active = s;
        }
        if (existing != null) {
            return r.put("ok", true).put("package", pkg).put("already_open", true)
                    .put("method", "already_open")
                    .put("window_id", existing.id)
                    .put("bounds", Windows.rect(existing.bounds))
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        }
        // Preferred path: StepOS' own "stepos-mini-window" windowing mode (11),
        // launched with the hidden ActivityOptions bundle key. This works from
        // the bridge UID and needs no recents/menu gestures.
        JSONObject nativeTry = startSmallWindow(new JSONObject().put("package", pkg));
        if (nativeTry.optBoolean("ok", false)) {
            return r.put("ok", true)
                    .put("method", "windowing_mode_11")
                    .put("window_id", nativeTry.optInt("window_id"))
                    .put("bounds", nativeTry.opt("bounds"))
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        }
        // Fallback: recents task-card menu via two hold gestures.
        // Recompute the active window: the native attempt may have brought the
        // app to the foreground (but not into a small window).
        active = null;
        for (Windows.Snapshot s : Windows.list(service)) {
            if (s.type != Windows.TYPE_APPLICATION || !pkg.equals(s.pkg)) continue;
            if (s.active || s.focused) active = s;
        }
        if (active == null) {
            return r.put("ok", false).put("package", pkg)
                    .put("error", "app_not_foreground")
                    .put("message", "native mode-11 launch did not open a small window; "
                            + "the gesture fallback needs the app in the foreground "
                            + "(ui_home + ui_click), then retry")
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        }

        int cx = screen.centerX();
        int upStartY = screen.bottom - 30;
        int upEndY = screen.top + screen.height() / 2 + 15;
        int cardStartY = screen.top + (int) (screen.height() * 0.262f);
        int cardEndY = cardStartY + screen.height() / 4;
        int menuX = args.has("menu_x") ? args.optInt("menu_x")
                : screen.left + (int) (screen.width() * 0.394f);
        int menuY = args.has("menu_y") ? args.optInt("menu_y")
                : screen.top + (int) (screen.height() * 0.337f);

        r.put("package", pkg).put("method", "stepos_gesture_menu");
        r.put("active_window_id", active.id);
        r.put("active_bounds", Windows.rect(active.bounds));
        r.put("menu_point", new org.json.JSONArray().put(menuX).put(menuY));

        // 1) bottom-center swipe up (half screen) + hold -> recents overview
        boolean upOk = service.swipeHold(cx, upStartY, cx, upEndY, 1500, 700);
        if (!upOk) {
            return r.put("ok", false).put("error", "gesture_rejected")
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        }
        // dispatchGesture is asynchronous: wait for the full stroke plus the
        // recents animation before starting the next gesture.
        sleep(1500 + 600);
        // 2) swipe down on the top task card (~1/4 screen) + hold -> card menu
        boolean menuOk = service.swipeHold(cx, cardStartY, cx, cardEndY, 1200, 700);
        if (!menuOk) {
            return r.put("ok", false).put("error", "gesture_rejected")
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        }
        sleep(1200 + 400);
        // 3) tap "迷你小窗" in the card menu
        boolean tapOk = service.tap(menuX, menuY, 80);
        sleep(1300);

        Windows.Snapshot opened = null;
        for (Windows.Snapshot s : Windows.list(service)) {
            if (s.type == Windows.TYPE_APPLICATION && pkg.equals(s.pkg)
                    && isSmallWindow(s, screen)) {
                opened = s;
                break;
            }
        }
        r.put("menu_tap", tapOk);
        r.put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        if (opened == null) {
            return r.put("ok", false).put("error", "small_window_not_opened")
                    .put("message", "the gestures ran but no small window was found; "
                            + "the card menu layout may differ (try menu_x/menu_y)");
        }
        return r.put("ok", true)
                .put("window_id", opened.id)
                .put("bounds", Windows.rect(opened.bounds));
    }

    private static boolean isSmallWindow(Windows.Snapshot s, android.graphics.Rect screen) {
        return s.bounds.width() < screen.width() * 0.9f
                || s.bounds.height() < screen.height() * 0.9f;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Low-level: launch a package in StepOS' "stepos-mini-window" mode
     * ({@code WindowConfiguration} windowing mode 11) with the hidden
     * ActivityOptions bundle key. Exposed as {@code start_small_window} for
     * diagnostics; {@link #openSmallWindow} uses it as the preferred path.
     */
    static JSONObject startSmallWindow(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        JSONObject r = new JSONObject();
        String pkg = args.optString("package", "");
        if (pkg.length() == 0) {
            return r.put("ok", false).put("error", "package required");
        }
        Context ctx = Tools.ctxRef();
        if (ctx == null) {
            return r.put("ok", false).put("error", "no app context");
        }
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            return r.put("ok", false).put("error", "no_launch_intent").put("package", pkg);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int mode = args.optInt("windowing_mode", 11);
        Bundle opts = new Bundle();
        opts.putInt("android.activity.windowingMode", mode);
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            ctx.startActivity(launch, opts);
            r.put("started", true);
        } catch (Throwable t) {
            return r.put("ok", false).put("error", "start_failed")
                    .put("message", t.getClass().getSimpleName() + ": " + t.getMessage())
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        }
        sleep(1500);
        android.graphics.Rect screen = Windows.screen(service);
        Windows.Snapshot found = null;
        for (Windows.Snapshot s : Windows.list(service)) {
            if (s.type != Windows.TYPE_APPLICATION || !pkg.equals(s.pkg)) continue;
            if (found == null || isSmallWindow(s, screen)) found = s;
        }
        r.put("package", pkg).put("windowing_mode", mode);
        if (found == null) {
            return r.put("ok", false).put("error", "window_not_found_after_start")
                    .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        }
        r.put("window_id", found.id)
                .put("bounds", Windows.rect(found.bounds))
                .put("small_window", isSmallWindow(found, screen))
                .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started);
        r.put("ok", isSmallWindow(found, screen));
        return r;
    }

    static JSONObject global(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        String action = args.optString("action", "back");
        int code;
        if ("back".equals(action)) {
            code = AccessibilityService.GLOBAL_ACTION_BACK;
        } else if ("home".equals(action)) {
            code = AccessibilityService.GLOBAL_ACTION_HOME;
        } else if ("recents".equals(action)) {
            code = AccessibilityService.GLOBAL_ACTION_RECENTS;
        } else if ("notifications".equals(action)) {
            code = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
        } else {
            return new JSONObject().put("ok", false).put("error", "unknown action: " + action);
        }
        boolean ok = service.global(code);
        return new JSONObject().put("ok", ok).put("action", action);
    }
}
