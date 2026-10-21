package com.stepx.amoobridge;

import android.accessibilityservice.AccessibilityService;

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

    static JSONObject tap(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        float x = (float) args.optDouble("x", Double.NaN);
        float y = (float) args.optDouble("y", Double.NaN);
        if (Float.isNaN(x) || Float.isNaN(y)) {
            return new JSONObject().put("ok", false).put("error", "x/y required");
        }
        int duration = Math.max(1, Math.min(args.optInt("duration", 60), 5000));
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
        boolean ok = service.swipe(x1, y1, x2, y2, duration);
        return new JSONObject().put("ok", ok)
                .put("from", new org.json.JSONArray().put(x1).put(y1))
                .put("to", new org.json.JSONArray().put(x2).put(y2))
                .put("duration", duration);
    }

    static JSONObject click(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        return service.clickText(args.optString("text", ""));
    }

    static JSONObject dump(JSONObject args) throws Exception {
        BridgeAccessibilityService service = service();
        if (service == null) return noService();
        JSONObject result = service.dump();
        if (!result.optBoolean("ok", false)) return result;
        File file = new File(BridgeService.BRIDGE_DIR, "ui_dump.json");
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(file, false);
        out.write(result.toString().getBytes("UTF-8"));
        out.close();
        JSONObject reply = new JSONObject();
        reply.put("ok", true);
        reply.put("file", file.getAbsolutePath());
        reply.put("count", result.optInt("count", 0));
        if (result.has("package")) reply.put("package", result.optString("package"));
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
