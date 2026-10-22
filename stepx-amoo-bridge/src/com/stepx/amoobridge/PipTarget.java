package com.stepx.amoobridge;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The {@code pip_target} tool: a small session around a package that is
 * expected to live in a PiP window. The bridge deliberately cannot put a
 * third-party app into PiP (that needs the app's own
 * {@code enterPictureInPictureMode()} call); this class only resolves,
 * remembers and validates the window. {@code inspect} is read-only,
 * {@code select} stores the window id/bounds, {@code recover} re-resolves a
 * vanished window and {@code clear} drops the session.
 */
final class PipTarget {
    private static final long APPS_CACHE_MS = 5 * 60 * 1000L;
    /**
     * {@code ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE}. The constant is
     * hidden from the public SDK; the value was read from this device's
     * framework.jar with dexdump (0x00400000) and matches AOSP {@code 1 << 22}.
     */
    private static final int FLAG_SUPPORTS_PIP = 0x00400000;

    private static volatile String sessionPkg;
    private static volatile int sessionWindowId = -1;
    private static volatile Rect sessionBounds;
    private static volatile long sessionAt;

    private static volatile JSONArray appsCache;
    private static volatile long appsCacheAt;
    private static volatile int appsCacheScanned;
    private static volatile String appsCacheError;

    private PipTarget() {}

    /** Package remembered by {@code pip_target select}; used by pip=true calls without a package. */
    static String sessionPkg() {
        return sessionPkg;
    }

    static void clear() {
        sessionPkg = null;
        sessionWindowId = -1;
        sessionBounds = null;
        sessionAt = 0;
    }

    static JSONObject sessionJson() {
        if (sessionPkg == null) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("package", sessionPkg);
            if (sessionWindowId > 0) o.put("window_id", sessionWindowId);
            if (sessionBounds != null) o.put("bounds", Windows.rect(sessionBounds));
            o.put("age_ms", System.currentTimeMillis() - sessionAt);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    static JSONObject tool(JSONObject args) throws Exception {
        String action = args.optString("action", "inspect");
        if ("clear".equals(action)) {
            boolean had = sessionPkg != null;
            clear();
            return new JSONObject().put("ok", true).put("action", action)
                    .put("cleared", had).put("session", JSONObject.NULL);
        }
        if ("apps".equals(action)) {
            JSONArray apps = pipApps();
            JSONObject r = new JSONObject().put("ok", true).put("action", action)
                    .put("count", apps.length()).put("apps", apps)
                    .put("scanned", appsCacheScanned);
            if (appsCacheError != null) r.put("scan_error", appsCacheError);
            return r;
        }

        BridgeAccessibilityService service = BridgeAccessibilityService.get();
        if (service == null) {
            return new JSONObject().put("ok", false)
                    .put("error", "accessibility service not enabled");
        }

        String pkg = args.optString("package", "");
        if (pkg.length() == 0) pkg = sessionPkg;
        if (pkg == null || pkg.length() == 0) {
            return new JSONObject().put("ok", false).put("error", "missing_package")
                    .put("message", "pass package, or select a target first");
        }

        boolean pip = !args.has("pip") || args.optBoolean("pip", true);
        Windows.Spec spec = new Windows.Spec();
        spec.pkg = pkg;
        spec.pip = pip;
        spec.explicit = true;
        if (args.has("window_id")) {
            spec.windowIdSet = true;
            spec.windowId = args.optInt("window_id", -1);
        }

        Windows.Selection sel = Windows.resolve(service, spec, sessionPkg);
        String pipActivity = pipActivity(pkg);
        boolean supports = pipActivity != null;

        JSONObject r = new JSONObject();
        r.put("action", action);
        r.put("package", pkg);
        r.put("supports_pip", supports);
        if (supports) r.put("pip_activity", pipActivity);

        if (sel.error != null) {
            r.put("ok", false).put("in_pip", false).put("error", sel.error);
            if (sel.message != null) r.put("message", sel.message);
            if (!sel.candidates.isEmpty()) {
                try {
                    r.put("candidates", Windows.compact(sel.candidates));
                } catch (Throwable ignored) {
                }
            }
            r.put("suggestions", suggestions(sel, supports));
            if ("recover".equals(action)) {
                r.put("session_cleared", sessionPkg != null);
                clear();
            }
            return r;
        }

        Windows.Snapshot t = sel.target;
        r.put("ok", true).put("in_pip", t.pipCandidate);
        r.put("window_id", t.id);
        r.put("bounds", Windows.rect(t.bounds));
        r.put("active", t.active);
        r.put("focused", t.focused);
        r.put("layer", t.layer);
        r.put("selection", sel.reason);
        r.put("pip_controls_visible", sel.controlsVisible);
        r.put("input_hint", "PiP content is not focused; verify writes with a follow-up ui_dump");
        if (!sel.overlays.isEmpty()) r.put("overlays", Windows.compact(sel.overlays));

        if ("select".equals(action)) {
            sessionPkg = pkg;
            sessionWindowId = t.id;
            sessionBounds = new Rect(t.bounds);
            sessionAt = System.currentTimeMillis();
            r.put("selected", true);
        } else if ("recover".equals(action)) {
            boolean existed = sessionPkg != null;
            sessionPkg = pkg;
            sessionWindowId = t.id;
            sessionBounds = new Rect(t.bounds);
            sessionAt = System.currentTimeMillis();
            r.put("still_present", true);
            r.put("session_cleared", false);
            r.put("recovered", true);
            r.put("note", existed
                    ? "window re-resolved; stale id/bounds replaced"
                    : "session created from the live window");
        }
        r.put("session", sessionJson());
        return r;
    }

    private static JSONArray suggestions(Windows.Selection sel, boolean supports) throws Exception {
        JSONArray a = new JSONArray();
        if (!supports) {
            a.put("target_app_does_not_support_pip");
            a.put("use_fullscreen_automation_instead");
            return a;
        }
        if (sel != null && sel.targetRunning) {
            a.put("app_is_running_but_not_in_pip");
            a.put("use_the_apps_own_pip_entry_then_inspect_again");
        } else {
            a.put("app_has_no_window_start_it_from_the_launcher");
            a.put("then_enter_pip_and_call_inspect_again");
        }
        return a;
    }

    // ---- package manager helpers -----------------------------------------

    private static PackageManager pm() {
        Context c = Tools.ctxRef();
        return c == null ? null : c.getPackageManager();
    }

    /** Name of the first activity declaring {@code supportsPictureInPicture}, or null. */
    static String pipActivity(String pkg) {
        PackageManager pm = pm();
        if (pm == null || pkg == null || pkg.length() == 0) return null;
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi == null || pi.activities == null) return null;
            for (ActivityInfo ai : pi.activities) {
                if (ai == null) continue;
                if ((ai.flags & FLAG_SUPPORTS_PIP) != 0) {
                    return ai.name;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Launchable apps that declare PiP support; useful for choosing a target. */
    private static JSONArray pipApps() throws Exception {
        JSONArray cached = appsCache;
        if (cached != null && System.currentTimeMillis() - appsCacheAt < APPS_CACHE_MS) {
            return cached;
        }
        PackageManager pm = pm();
        JSONArray arr = new JSONArray();
        if (pm == null) return arr;
        ArrayList<JSONObject> hits = new ArrayList<JSONObject>();
        int scanned = 0;
        String error = null;
        try {
            // queryIntentActivities is not affected by package-visibility edge
            // cases the way getInstalledPackages(GET_ACTIVITIES) can be, and it
            // is the same lookup list_apps already uses successfully.
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            i.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            if (ris != null) {
                for (android.content.pm.ResolveInfo ri : ris) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || !seen.add(pkg)) continue;
                    scanned++;
                    String activity = pipActivity(pkg);
                    if (activity == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("package", pkg);
                    String label = pkg;
                    try {
                        label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                    } catch (Throwable ignored) {
                    }
                    o.put("label", label);
                    o.put("pip_activity", activity);
                    hits.add(o);
                }
            }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        Collections.sort(hits, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return a.optString("label").compareToIgnoreCase(b.optString("label"));
            }
        });
        for (JSONObject o : hits) arr.put(o);
        appsCache = arr;
        appsCacheAt = System.currentTimeMillis();
        appsCacheScanned = scanned;
        appsCacheError = error;
        return arr;
    }
}
