package com.stepx.amoobridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Window enumeration and target selection for the PiP workflow.
 *
 * <p>{@code getRootInActiveWindow()} returns whatever window has input focus.
 * A PiP window deliberately does not take focus, so the legacy tools see the
 * launcher or the app underneath it. Everything PiP-related therefore goes
 * through {@link #resolve}, which selects a window by explicit id, by package
 * name (optionally requiring a PiP-sized window) and only falls back to the
 * active window for the legacy no-argument calls.
 *
 * <p>The "is this a PiP window" test is a heuristic: an application window that
 * does not cover (almost) the whole screen. Android does not expose the PiP
 * state through accessibility, and the shell cannot force PiP for a third-party
 * app, so size + package + window layer is the best signal available. A
 * floating dialog of the same package would also qualify; callers are expected
 * to confirm with {@code ui_dump} / a screenshot.
 */
final class Windows {
    /** {@link AccessibilityWindowInfo#TYPE_APPLICATION} */
    static final int TYPE_APPLICATION = AccessibilityWindowInfo.TYPE_APPLICATION;
    static final int TYPE_INPUT_METHOD = AccessibilityWindowInfo.TYPE_INPUT_METHOD;
    static final int TYPE_SYSTEM = AccessibilityWindowInfo.TYPE_SYSTEM;
    static final int TYPE_ACCESSIBILITY_OVERLAY = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;

    /** A window occupying less than this fraction of the screen counts as small. */
    private static final float PIP_SIZE_FRACTION = 0.85f;
    /** An overlay has to cover this much of the target to be considered on top of it. */
    private static final float OVERLAY_FRACTION = 0.2f;

    static final String ERR_WINDOW_NOT_FOUND = "target_window_not_found";
    static final String ERR_NOT_IN_PIP = "target_not_in_pip";
    static final String ERR_MULTIPLE = "package_multiple_windows";
    static final String ERR_MULTIPLE_PIP = "package_multiple_pip_windows";
    static final String ERR_NOT_PIP_CANDIDATE = "package_window_not_pip";
    static final String ERR_NO_ROOT = "target_window_no_root";
    static final String ERR_CONTROLS = "pip_controls_visible";
    static final String ERR_OUTSIDE = "outside_target_bounds";
    static final String ERR_NO_SESSION = "no_pip_session";

    /**
     * Returned on write calls aimed at a PiP-sized window. The gesture is
     * accepted by the a11y framework but on the tested Android 16/StepOS build
     * the PiP input consumer swallows it (and {@code ACTION_CLICK} is rejected
     * by the under-reported node), so the agent must confirm the effect.
     */
    static final String PIP_INPUT_WARNING =
            "gesture injected into a PiP window; PiP content may not receive touch "
            + "(the system may show its controls or move the window). "
            + "Confirm with ui_dump or shot before treating it as success";

    private Windows() {}

    // ---- request spec -----------------------------------------------------

    /** Optional target selector decoded from tool arguments. */
    static final class Spec {
        int windowId = -1;
        boolean windowIdSet;
        String pkg;
        boolean pip;
        boolean explicit;

        boolean hasWindow() {
            return windowIdSet;
        }
    }

    // ---- window snapshot --------------------------------------------------

    static final class Snapshot {
        int id;
        String pkg;
        int type;
        Rect bounds = new Rect();
        boolean active;
        boolean focused;
        int layer;
        String title;
        boolean hasRoot;
        boolean pipCandidate;
        AccessibilityWindowInfo window;
        AccessibilityNodeInfo root;

        int area() {
            return Math.max(0, bounds.width()) * Math.max(0, bounds.height());
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("window_id", id);
            if (pkg != null) o.put("package", pkg);
            o.put("type", type);
            o.put("type_name", typeName(type));
            o.put("bounds", rect(bounds));
            o.put("active", active);
            o.put("focused", focused);
            o.put("layer", layer);
            o.put("pip_candidate", pipCandidate);
            o.put("has_root", hasRoot);
            if (title != null) o.put("title", title);
            return o;
        }
    }

    // ---- resolve result ---------------------------------------------------

    static final class Selection {
        Snapshot target;
        String reason;          // window_id | package_pip | session_pip | package | active
        String error;
        String message;
        boolean pipRequested;
        String pkg;
        List<Snapshot> candidates = new ArrayList<Snapshot>();
        List<Snapshot> overlays = new ArrayList<Snapshot>();
        boolean controlsVisible;
        /** True when a window of the requested package exists (even if it is full screen). */
        boolean targetRunning;
    }

    // ---- enumeration ------------------------------------------------------

    /** Snapshot every interactive window. Must be called on a thread that may block briefly. */
    static List<Snapshot> list(AccessibilityService service) {
        ArrayList<Snapshot> out = new ArrayList<Snapshot>();
        if (service == null) return out;
        Rect screen = screen(service);
        List<AccessibilityWindowInfo> wins;
        try {
            wins = service.getWindows();
        } catch (Throwable t) {
            return out;
        }
        if (wins == null) return out;
        for (AccessibilityWindowInfo w : wins) {
            if (w == null) continue;
            try {
                out.add(snapshot(w, screen));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static Snapshot snapshot(AccessibilityWindowInfo w, Rect screen) {
        Snapshot s = new Snapshot();
        s.window = w;
        s.id = w.getId();
        s.type = w.getType();
        s.bounds = new Rect();
        try {
            w.getBoundsInScreen(s.bounds);
        } catch (Throwable ignored) {
        }
        try {
            s.active = w.isActive();
            s.focused = w.isFocused();
            s.layer = w.getLayer();
        } catch (Throwable ignored) {
        }
        try {
            CharSequence t = w.getTitle();
            if (t != null && t.length() > 0) s.title = t.toString();
        } catch (Throwable ignored) {
        }
        try {
            s.root = w.getRoot();
        } catch (Throwable ignored) {
        }
        if (s.root != null) {
            s.hasRoot = true;
            try {
                CharSequence p = s.root.getPackageName();
                if (p != null) s.pkg = p.toString();
            } catch (Throwable ignored) {
            }
        }
        s.pipCandidate = isPipSized(s, screen);
        return s;
    }

    private static boolean isPipSized(Snapshot s, Rect screen) {
        if (s.type != TYPE_APPLICATION) return false;
        int w = s.bounds.width();
        int h = s.bounds.height();
        if (w <= 0 || h <= 0) return false;
        if (w >= screen.width() * PIP_SIZE_FRACTION) return false;
        if (h >= screen.height() * PIP_SIZE_FRACTION) return false;
        return true;
    }

    static Rect screen(AccessibilityService service) {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        } catch (Throwable t) {
            dm = service.getResources().getDisplayMetrics();
            return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }
    }

    // ---- selection --------------------------------------------------------

    /**
     * Select the target window.
     *
     * <p>Priority: explicit window id, package + PiP, package, session package
     * (only when PiP was requested), then - for legacy calls - the active
     * window. A selector that matches nothing produces an explicit error and
     * never silently falls back to the bridge itself or a system window.
     */
    static Selection resolve(AccessibilityService service, Spec spec, String sessionPkg) {
        Selection sel = new Selection();
        sel.pipRequested = spec.pip;
        String pkg = spec.pkg;
        if ((pkg == null || pkg.length() == 0) && spec.pip) pkg = sessionPkg;
        sel.pkg = pkg;

        List<Snapshot> all = list(service);
        if (spec.hasWindow()) {
            if (spec.windowId <= 0) {
                return fail(sel, ERR_WINDOW_NOT_FOUND, "window_id must be a positive integer");
            }
            Snapshot hit = byId(all, spec.windowId);
            if (hit == null) {
                sel.candidates = all;
                return fail(sel, ERR_WINDOW_NOT_FOUND,
                        "no window with id " + spec.windowId + " (it may have been recreated)");
            }
            if (spec.pip && !hit.pipCandidate) {
                sel.candidates = all;
                return fail(sel, ERR_NOT_PIP_CANDIDATE,
                        "window " + hit.id + " is not a PiP-sized window");
            }
            sel.target = hit;
            sel.reason = "window_id";
            finish(sel, all);
            return sel;
        }

        if (pkg == null || pkg.length() == 0) {
            if (spec.pip) {
                return fail(sel, ERR_NO_SESSION,
                        "pip=true without package and no pip_target session");
            }
            // Legacy call: leave the active-window decision to the caller.
            sel.reason = "active";
            return sel;
        }

        ArrayList<Snapshot> same = new ArrayList<Snapshot>();
        for (Snapshot s : all) {
            if (pkg.equals(s.pkg) && s.type == TYPE_APPLICATION) same.add(s);
        }
        if (same.isEmpty()) {
            sel.candidates = all;
            sel.targetRunning = false;
            return fail(sel, ERR_WINDOW_NOT_FOUND,
                    "package " + pkg + " has no interactive application window");
        }

        if (spec.pip) {
            ArrayList<Snapshot> pip = new ArrayList<Snapshot>();
            for (Snapshot s : same) if (s.pipCandidate) pip.add(s);
            if (pip.isEmpty()) {
                sel.candidates = same;
                sel.targetRunning = true;
                return fail(sel, ERR_NOT_IN_PIP,
                        "package " + pkg + " has a window but it is full screen (not PiP)");
            }
            if (pip.size() > 1) {
                sel.candidates = pip;
                return fail(sel, ERR_MULTIPLE_PIP,
                        "package " + pkg + " has " + pip.size()
                                + " PiP-sized windows; pass window_id or pip_target");
            }
            sel.target = pip.get(0);
            sel.reason = (spec.pkg == null || spec.pkg.length() == 0) ? "session_pip" : "package_pip";
            finish(sel, all);
            return sel;
        }

        if (same.size() > 1) {
            sel.candidates = same;
            return fail(sel, ERR_MULTIPLE,
                    "package " + pkg + " has " + same.size()
                            + " windows; pass window_id or pip=true");
        }
        sel.target = same.get(0);
        sel.reason = "package";
        finish(sel, all);
        return sel;
    }

    private static Selection fail(Selection sel, String error, String message) {
        sel.error = error;
        sel.message = message;
        return sel;
    }

    private static void finish(Selection sel, List<Snapshot> all) {
        sel.targetRunning = true;
        sel.overlays = overlaysOver(sel.target, all);
        sel.controlsVisible = !sel.overlays.isEmpty();
    }

    /**
     * System windows that sit on top of the target. The PiP menu (close /
     * expand / settings) is drawn by SystemUI in its own window, so this is what
     * tells us the next gesture would hit the controls instead of the app.
     */
    static List<Snapshot> overlaysOver(Snapshot target, List<Snapshot> all) {
        ArrayList<Snapshot> out = new ArrayList<Snapshot>();
        if (target == null) return out;
        for (Snapshot w : all) {
            if (w == null || w.id == target.id) continue;
            if (w.type == TYPE_INPUT_METHOD) continue;
            if (w.type == TYPE_APPLICATION && !isSystemUi(w.pkg)) continue;
            int inter = intersectArea(w.bounds, target.bounds);
            if (inter <= 0) continue;
            if (inter < target.area() * OVERLAY_FRACTION) continue;
            out.add(w);
        }
        return out;
    }

    static boolean isSystemUi(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.android.systemui")
                || pkg.startsWith("com.android.systemui.")
                || pkg.equals("com.google.android.systemui")
                || pkg.equals("com.stepos.systemui");
    }

    static int intersectArea(Rect a, Rect b) {
        int l = Math.max(a.left, b.left);
        int t = Math.max(a.top, b.top);
        int r = Math.min(a.right, b.right);
        int bo = Math.min(a.bottom, b.bottom);
        if (r <= l || bo <= t) return 0;
        return (r - l) * (bo - t);
    }

    static Snapshot byId(List<Snapshot> all, int id) {
        for (Snapshot s : all) if (s.id == id) return s;
        return null;
    }

    // ---- JSON helpers -----------------------------------------------------

    static JSONArray windowsJson(List<Snapshot> list) throws Exception {
        JSONArray arr = new JSONArray();
        for (Snapshot s : list) arr.put(s.toJson());
        return arr;
    }

    static JSONArray rect(Rect r) {
        JSONArray a = new JSONArray();
        a.put(r.left).put(r.top).put(r.right).put(r.bottom);
        return a;
    }

    static boolean sameBounds(Rect a, Rect b) {
        if (a == null || b == null) return false;
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }

    static String typeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "input_method";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "accessibility_overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "split_screen_divider";
            case AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY:
                return "magnification_overlay";
            default:
                return "type_" + type;
        }
    }

    static JSONObject error(Selection sel, long elapsedMs) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", sel.error);
        if (sel.message != null) o.put("message", sel.message);
        if (sel.pkg != null) o.put("package", sel.pkg);
        o.put("pip", sel.pipRequested);
        if (!sel.candidates.isEmpty()) {
            try {
                o.put("candidates", compact(sel.candidates));
            } catch (Throwable ignored) {
            }
        }
        o.put("elapsed_ms", elapsedMs);
        return o;
    }

    static JSONArray compact(List<Snapshot> list) throws Exception {
        JSONArray arr = new JSONArray();
        for (Snapshot s : list) {
            JSONObject o = new JSONObject();
            o.put("window_id", s.id);
            if (s.pkg != null) o.put("package", s.pkg);
            o.put("type_name", typeName(s.type));
            o.put("bounds", rect(s.bounds));
            o.put("active", s.active);
            o.put("focused", s.focused);
            o.put("layer", s.layer);
            o.put("pip_candidate", s.pipCandidate);
            arr.put(o);
        }
        return arr;
    }
}
