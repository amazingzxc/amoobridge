package com.stepx.amoobridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * UI automation entry point. The agent talks to the bridge over HTTP / the file
 * mailbox; this service performs gestures, text lookup and screenshots.
 *
 * It has to be enabled once by the user (Settings -> Accessibility) or via adb:
 *   adb shell settings put secure enabled_accessibility_services \
 *       com.stepx.amoobridge/com.stepx.amoobridge.BridgeAccessibilityService
 *   adb shell settings put secure accessibility_enabled 1
 *
 * <p>Every tree walk is bounded by a wall-clock budget. One {@code getChild()}
 * is a synchronous binder round trip into the target app, and on this device a
 * frozen vendor process can stall any of them, so a node-count limit alone is
 * not enough to keep a call short. When the budget runs out the walk stops and
 * reports {@code partial=true} instead of blocking the agent for tens of
 * seconds.
 */
public class BridgeAccessibilityService extends AccessibilityService {
    private static final String TAG = "BridgeA11y";
    private static volatile BridgeAccessibilityService instance;

    /** Wall-clock budget for one tree walk. Warm walks measure 10-20ms. */
    static final int DEFAULT_BUDGET_MS = 2000;
    static final int DEFAULT_MAX_NODES = 2000;
    private static final int MAX_DEPTH = 60;
    /** Cached nodes are only reused within this window after a dump. */
    private static final long CACHE_TTL_MS = 20000;
    private static final int CACHE_MAX = 800;

    public interface ShotCallback {
        void done(boolean ok, String message);
    }

    public static BridgeAccessibilityService get() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    // ---- last-dump node cache, so ui_click does not walk the tree again ----

    private static final class CacheEntry {
        AccessibilityNodeInfo node;
        String text;
        String desc;
    }

    private final ArrayList<CacheEntry> cache = new ArrayList<CacheEntry>();
    private volatile int cacheWindowId = -1;
    private volatile long cacheAt;
    private volatile String cachePkg;
    private volatile Rect cacheBounds;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally no-op: we only expose explicit UI actions.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        clearNodeCache();
        Log.i(TAG, "accessibility service destroyed");
        super.onDestroy();
    }

    public boolean tap(float x, float y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        return gesture(path, durationMs);
    }

    public boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        return gesture(path, durationMs);
    }

    /**
     * Swipe that pauses at the end point. {@link GestureDescription} has no
     * hold parameter, so the pause is faked with a +/-1px zigzag at the end:
     * the total path length is sized so the main movement takes
     * {@code duration - holdMs} and the zigzag (inside touch slop) takes
     * {@code holdMs}. This is what StepOS' "swipe up and hold" recents gesture
     * and its task-card menu gesture expect.
     */
    public boolean swipeHold(float x1, float y1, float x2, float y2,
                             long durationMs, long holdMs) {
        long total = Math.max(2, durationMs);
        long hold = Math.max(0, Math.min(holdMs, total - 1));
        long move = total - hold;
        double dx = (double) x2 - x1;
        double dy = (double) y2 - y1;
        double mainLen = Math.sqrt(dx * dx + dy * dy);
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        if (hold <= 0 || mainLen <= 1.0 || move <= 0) {
            return gesture(path, total);
        }
        double tailLen = mainLen * hold / (double) move;
        final float amp = 1.0f; // inside the platform touch slop
        int segments = (int) Math.ceil(tailLen / (2.0 * amp));
        if (segments < 2) segments = 2;
        if (segments > 4000) segments = 4000;
        for (int i = 0; i < segments; i++) {
            float y = (i % 2 == 0) ? y2 + amp : y2 - amp;
            path.lineTo(x2, y);
        }
        path.lineTo(x2, y2);
        return gesture(path, total);
    }

    private boolean gesture(Path path, long durationMs) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                    path, 0, Math.max(1, durationMs));
            GestureDescription description = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            return dispatchGesture(description, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "gesture failed", t);
            return false;
        }
    }

    public boolean global(int action) {
        try {
            return performGlobalAction(action);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Find a node whose text/contentDescription contains {@code text} and click it. */
    public JSONObject clickText(String text, int budgetMs) {
        JSONObject result = new JSONObject();
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(200, budgetMs);
        try {
            if (text == null || text.length() == 0) {
                return result.put("ok", false).put("error", "empty text");
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            long rootMs = SystemClock.elapsedRealtime() - started;
            if (root == null) {
                return result.put("ok", false).put("error", "no active window")
                        .put("root_ms", rootMs)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            }
            String method = "cache";
            AccessibilityNodeInfo found = findCached(root.getWindowId(), null, null, text);
            if (found == null) {
                method = "node";
                WalkState st = new WalkState(deadline, DEFAULT_MAX_NODES);
                found = findNode(root, text, 0, st);
                if (found == null && st.truncated) method = "truncated";
            }
            if (found == null) {
                return result.put("ok", false).put("error", "not found: " + text)
                        .put("root_ms", rootMs)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            }
            AccessibilityNodeInfo clickable = found;
            int hops = 0;
            while (clickable != null && !clickable.isClickable() && hops++ < 24) {
                if (SystemClock.elapsedRealtime() > deadline) break;
                clickable = clickable.getParent();
            }
            boolean clicked = false;
            String used = method;
            if (clickable != null && clickable.isClickable()) {
                clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                used = method + "+node";
            }
            if (!clicked) {
                Rect bounds = new Rect();
                found.getBoundsInScreen(bounds);
                if (bounds.width() > 0 && bounds.height() > 0) {
                    clicked = tap(bounds.centerX(), bounds.centerY(), 60);
                    used = method + "+gesture";
                }
            }
            return result.put("ok", clicked).put("text", text).put("method", used)
                    .put("root_ms", rootMs)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage())
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            } catch (Throwable ignored) {
                return result;
            }
        }
    }

    /**
     * Look the text up in the nodes collected by the previous {@link #dump}.
     * Saves the whole walk on the usual "dump then click" pattern; each hit is
     * re-validated with {@link AccessibilityNodeInfo#refresh()} so a stale
     * window falls back to a fresh walk.
     */
    private AccessibilityNodeInfo findCached(int windowId, String pkg, Rect bounds, String text) {
        if (windowId != cacheWindowId) return null;
        if (SystemClock.elapsedRealtime() - cacheAt > CACHE_TTL_MS) return null;
        // A PiP window can be moved or recreated while keeping the same id in
        // some shells. The cached nodes are only trustworthy for the exact
        // package and window rectangle they were collected from.
        if (pkg != null && cachePkg != null && !pkg.equals(cachePkg)) return null;
        if (bounds != null && !Windows.sameBounds(bounds, cacheBounds)) return null;
        CacheEntry hit = null;
        synchronized (cache) {
            for (int i = 0; i < cache.size(); i++) {
                CacheEntry e = cache.get(i);
                boolean match = (e.text != null && e.text.contains(text))
                        || (e.desc != null && e.desc.contains(text));
                if (match) {
                    hit = e;
                    break;
                }
            }
        }
        if (hit == null) return null;
        try {
            if (!hit.node.refresh()) return null;
            CharSequence t = hit.node.getText();
            CharSequence d = hit.node.getContentDescription();
            boolean still = (t != null && t.toString().contains(text))
                    || (d != null && d.toString().contains(text));
            return still ? hit.node : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void clearNodeCache() {
        // Drop the references without recycling: a ui_click may hold one of these
        // nodes on another thread, and recycling it there would invalidate a node
        // that is about to be clicked.
        synchronized (cache) {
            cache.clear();
        }
        cacheWindowId = -1;
        cachePkg = null;
        cacheBounds = null;
    }

    private static final class WalkState {
        final long deadline;
        final int maxNodes;
        int nodes;
        boolean truncated;

        WalkState(long deadline, int maxNodes) {
            this.deadline = deadline;
            this.maxNodes = maxNodes;
        }

        boolean stop() {
            if (truncated) return true;
            if (nodes >= maxNodes || SystemClock.elapsedRealtime() > deadline) {
                truncated = true;
                return true;
            }
            return false;
        }
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String text,
                                           int depth, WalkState st) {
        if (node == null || depth > MAX_DEPTH || st.stop()) return null;
        st.nodes++;
        try {
            CharSequence label = node.getText();
            if (label != null && label.toString().contains(text)) return node;
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().contains(text)) return node;
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                if (st.stop()) return null;
                AccessibilityNodeInfo hit = findNode(node.getChild(i), text, depth + 1, st);
                if (hit != null) return hit;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public JSONObject dump() {
        return dump(DEFAULT_BUDGET_MS, DEFAULT_MAX_NODES);
    }

    /** Flatten the current window into a JSON array (text/desc/id/bounds/clickable). */
    public JSONObject dump(int budgetMs, int maxNodes) {
        JSONObject result = new JSONObject();
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(200, budgetMs);
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            long rootMs = SystemClock.elapsedRealtime() - started;
            if (root == null) {
                return result.put("ok", false).put("error", "no active window")
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            }
            clearNodeCache();
            JSONArray nodes = new JSONArray();
            WalkState st = new WalkState(deadline, Math.max(1, maxNodes));
            walk(root, nodes, 0, st);
            long walkMs = SystemClock.elapsedRealtime() - started - rootMs;
            result.put("ok", true);
            CharSequence pkg = root.getPackageName();
            if (pkg != null) result.put("package", pkg.toString());
            result.put("count", nodes.length());
            result.put("partial", st.truncated);
            result.put("root_ms", rootMs);
            result.put("walk_ms", walkMs);
            result.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            if (st.truncated) {
                Log.w(TAG, "dump truncated after " + nodes.length() + " nodes, "
                        + (SystemClock.elapsedRealtime() - started) + "ms");
            }
            result.put("nodes", nodes);
            result.put("window_id", root.getWindowId());
            Rect rootBounds = new Rect();
            root.getBoundsInScreen(rootBounds);
            result.put("bounds", Windows.rect(rootBounds));
            result.put("selection", "active");
            cacheWindowId = root.getWindowId();
            cachePkg = pkg == null ? null : pkg.toString();
            cacheBounds = rootBounds;
            cacheAt = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage())
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            } catch (Throwable ignored) {
                return result;
            }
        }
        return result;
    }

    private void walk(AccessibilityNodeInfo node, JSONArray out, int depth, WalkState st) {
        if (node == null || depth > MAX_DEPTH || st.stop()) return;
        st.nodes++;
        try {
            JSONObject item = new JSONObject();
            CharSequence text = node.getText();
            String textStr = null;
            if (text != null && text.length() > 0) {
                textStr = text.toString();
                item.put("text", textStr);
            }
            CharSequence desc = node.getContentDescription();
            String descStr = null;
            if (desc != null && desc.length() > 0) {
                descStr = desc.toString();
                item.put("desc", descStr);
            }
            CharSequence cls = node.getClassName();
            if (cls != null) item.put("class", cls.toString());
            String id = node.getViewIdResourceName();
            if (id != null) item.put("id", id);
            if (node.isClickable()) item.put("clickable", true);
            if (node.isScrollable()) item.put("scrollable", true);
            if (node.isEditable()) item.put("editable", true);
            if (node.isFocused()) item.put("focused", true);
            item.put("depth", depth);
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            JSONArray rect = new JSONArray();
            rect.put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom);
            item.put("bounds", rect);
            out.put(item);

            if (textStr != null || descStr != null) {
                synchronized (cache) {
                    if (cache.size() < CACHE_MAX) {
                        CacheEntry e = new CacheEntry();
                        e.node = node;
                        e.text = textStr;
                        e.desc = descStr;
                        cache.add(e);
                    }
                }
            }

            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                if (st.stop()) return;
                walk(node.getChild(i), out, depth + 1, st);
            }
        } catch (Throwable ignored) {
        }
    }


    // ---- window-targeted operations (PiP support) --------------------------

    /** List every interactive window with the metadata an agent needs to pick one. */
    public JSONObject listWindows() {
        JSONObject result = new JSONObject();
        long started = SystemClock.elapsedRealtime();
        try {
            java.util.List<Windows.Snapshot> all = Windows.list(this);
            JSONArray windows = Windows.windowsJson(all);
            JSONArray pipIds = new JSONArray();
            int activeId = -1;
            for (Windows.Snapshot s : all) {
                if (s.active && activeId < 0) activeId = s.id;
                if (s.pipCandidate) pipIds.put(s.id);
            }
            result.put("ok", true);
            result.put("count", windows.length());
            result.put("screen", Windows.rect(Windows.screen(this)));
            result.put("active_window_id", activeId);
            result.put("pip_window_ids", pipIds);
            result.put("windows", windows);
            JSONObject session = PipTarget.sessionJson();
            if (session != null) result.put("session", session);
            result.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            return result;
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage())
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            } catch (Throwable ignored) {
                return result;
            }
        }
    }

    /**
     * Root node of the selected window. {@link Windows.Snapshot} already holds
     * the root fetched during resolution; the window id is checked so a window
     * that was replaced between the two calls is not read through a stale node.
     */
    private AccessibilityNodeInfo targetRoot(Windows.Selection sel) {
        AccessibilityNodeInfo root = sel.target.root;
        if (root == null && sel.target.window != null) {
            try {
                root = sel.target.window.getRoot();
            } catch (Throwable ignored) {
            }
        }
        if (root != null) {
            try {
                int id = root.getWindowId();
                if (id > 0 && id != sel.target.id) return null;
            } catch (Throwable ignored) {
            }
        }
        return root;
    }

    private JSONObject fillTarget(JSONObject o, Windows.Selection sel) {
        try {
            Windows.Snapshot t = sel.target;
            o.put("window_id", t.id);
            o.put("bounds", Windows.rect(t.bounds));
            o.put("selection", sel.reason);
            o.put("pip_candidate", t.pipCandidate);
            o.put("active", t.active);
            o.put("focused", t.focused);
            o.put("layer", t.layer);
            if (t.pkg != null) o.put("package", t.pkg);
            o.put("pip_controls_visible", sel.controlsVisible);
            if (!sel.overlays.isEmpty()) o.put("overlays", Windows.compact(sel.overlays));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * Attach a verification hint to gesture-based writes on a PiP window.
     * {@code ok=true} only means the gesture was injected; see
     * {@link Windows#PIP_INPUT_WARNING}.
     */
    private void markInput(JSONObject r, Windows.Selection sel, boolean gesture) {
        try {
            if (gesture && sel.target.pipCandidate) {
                r.put("input_verified", false);
                r.put("warning", Windows.PIP_INPUT_WARNING);
            } else if (gesture) {
                r.put("input_verified", true);
            } else {
                r.put("input_verified", true);
            }
        } catch (Throwable ignored) {
        }
    }

    private JSONObject targetNoRoot(Windows.Selection sel, long started) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", false).put("error", Windows.ERR_NO_ROOT)
                    .put("message", "the selected window has no accessibility root; "
                            + "the app may not expose content or the window changed")
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            fillTarget(r, sel);
        } catch (Throwable ignored) {
        }
        return r;
    }

    private JSONObject controlsBlocked(Windows.Selection sel, long started) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", false).put("error", Windows.ERR_CONTROLS)
                    .put("message", "system PiP controls overlap the target window; "
                            + "dismiss them (ui_back or tap outside) and retry")
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            fillTarget(r, sel);
        } catch (Throwable ignored) {
        }
        return r;
    }

    private JSONObject outsideTarget(Windows.Selection sel, long started,
                                     float x1, float y1, float x2, float y2) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", false).put("error", Windows.ERR_OUTSIDE)
                    .put("message", "point(s) outside the target window bounds; "
                            + "read ui_windows/pip_target again - the PiP may have moved");
            JSONArray from = new JSONArray();
            from.put(x1).put(y1);
            r.put("from", from);
            if (!Float.isNaN(x2)) {
                JSONArray to = new JSONArray();
                to.put(x2).put(y2);
                r.put("to", to);
            }
            r.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            fillTarget(r, sel);
        } catch (Throwable ignored) {
        }
        return r;
    }

    /** Target-aware dump. Falls back to the legacy active-window path with no selector. */
    public JSONObject dump(Windows.Spec spec, int budgetMs, int maxNodes) {
        if (!spec.explicit) return dump(budgetMs, maxNodes);
        JSONObject result = new JSONObject();
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(200, budgetMs);
        try {
            Windows.Selection sel = Windows.resolve(this, spec, PipTarget.sessionPkg());
            if (sel.error != null) return Windows.error(sel, SystemClock.elapsedRealtime() - started);
            AccessibilityNodeInfo root = targetRoot(sel);
            long rootMs = SystemClock.elapsedRealtime() - started;
            if (root == null) return targetNoRoot(sel, started);
            clearNodeCache();
            JSONArray nodes = new JSONArray();
            WalkState st = new WalkState(deadline, Math.max(1, maxNodes));
            walk(root, nodes, 0, st);
            long walkMs = SystemClock.elapsedRealtime() - started - rootMs;
            result.put("ok", true);
            CharSequence pkg = root.getPackageName();
            if (pkg != null) result.put("package", pkg.toString());
            result.put("count", nodes.length());
            result.put("partial", st.truncated);
            result.put("root_ms", rootMs);
            result.put("walk_ms", walkMs);
            result.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            fillTarget(result, sel);
            result.put("nodes", nodes);
            cacheWindowId = sel.target.id;
            cachePkg = sel.target.pkg;
            cacheBounds = sel.target.bounds;
            cacheAt = SystemClock.elapsedRealtime();
            return result;
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage())
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            } catch (Throwable ignored) {
                return result;
            }
        }
    }

    /** Target-aware text click: lookup and node/gesture dispatch stay inside one window. */
    public JSONObject clickText(Windows.Spec spec, String text, int budgetMs) {
        if (!spec.explicit) return clickText(text, budgetMs);
        JSONObject result = new JSONObject();
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(200, budgetMs);
        try {
            if (text == null || text.length() == 0) {
                return result.put("ok", false).put("error", "empty text");
            }
            Windows.Selection sel = Windows.resolve(this, spec, PipTarget.sessionPkg());
            if (sel.error != null) return Windows.error(sel, SystemClock.elapsedRealtime() - started);
            if (sel.controlsVisible) return controlsBlocked(sel, started);
            AccessibilityNodeInfo root = targetRoot(sel);
            long rootMs = SystemClock.elapsedRealtime() - started;
            if (root == null) return targetNoRoot(sel, started);
            String method = "cache";
            AccessibilityNodeInfo found = findCached(sel.target.id, sel.target.pkg,
                    sel.target.bounds, text);
            if (found == null) {
                method = "node";
                WalkState st = new WalkState(deadline, DEFAULT_MAX_NODES);
                found = findNode(root, text, 0, st);
                if (found == null && st.truncated) method = "truncated";
            }
            if (found == null) {
                result.put("ok", false).put("error", "not found: " + text)
                        .put("root_ms", rootMs)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
                fillTarget(result, sel);
                return result;
            }
            // Some PiP windows under-report isClickable() on real buttons (seen
            // on StepOS): the matched node itself must get the action first, or
            // the ancestor walk ends up "clicking" the window root.
            boolean clicked = false;
            String used = method;
            try {
                clicked = found.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                used = method + "+self";
            } catch (Throwable ignored) {
            }
            if (!clicked) {
                AccessibilityNodeInfo clickable = found;
                int hops = 0;
                while (clickable != null && !clickable.isClickable() && hops++ < 24) {
                    if (SystemClock.elapsedRealtime() > deadline) break;
                    clickable = clickable.getParent();
                }
                if (clickable != null && clickable.getParent() == null) {
                    clickable = null; // never click the whole window as a stand-in
                }
                if (clickable != null && clickable.isClickable()) {
                    clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    used = method + "+node";
                }
            }
            if (!clicked) {
                Rect nodeBounds = new Rect();
                found.getBoundsInScreen(nodeBounds);
                if (nodeBounds.width() <= 0 || nodeBounds.height() <= 0) {
                    result.put("ok", false).put("error", "node_has_empty_bounds");
                } else if (!sel.target.bounds.contains(nodeBounds.centerX(), nodeBounds.centerY())) {
                    result.put("ok", false).put("error", Windows.ERR_OUTSIDE)
                            .put("message", "node center is outside the target window")
                            .put("node_bounds", Windows.rect(nodeBounds));
                } else {
                    clicked = tap(nodeBounds.centerX(), nodeBounds.centerY(), 60);
                    used = method + "+gesture";
                }
            }
            result.put("ok", clicked).put("text", text).put("method", used)
                    .put("root_ms", rootMs)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            fillTarget(result, sel);
            markInput(result, sel, used.endsWith("+gesture"));
            return result;
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage())
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            } catch (Throwable ignored) {
                return result;
            }
        }
    }

    /** Target-aware tap with bounds and PiP-menu guards. */
    public JSONObject tapTarget(Windows.Spec spec, float x, float y, int durationMs) {
        JSONObject result = new JSONObject();
        long started = SystemClock.elapsedRealtime();
        try {
            Windows.Selection sel = Windows.resolve(this, spec, PipTarget.sessionPkg());
            if (sel.error != null) return Windows.error(sel, SystemClock.elapsedRealtime() - started);
            if (sel.controlsVisible) return controlsBlocked(sel, started);
            if (!sel.target.bounds.contains((int) x, (int) y)) {
                return outsideTarget(sel, started, x, y, Float.NaN, Float.NaN);
            }
            boolean ok = tap(x, y, durationMs);
            result.put("ok", ok).put("x", x).put("y", y).put("duration", durationMs)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            fillTarget(result, sel);
            markInput(result, sel, true);
            return result;
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage())
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            } catch (Throwable ignored) {
                return result;
            }
        }
    }

    /** Target-aware swipe; both endpoints must be inside the selected window. */
    public JSONObject swipeTarget(Windows.Spec spec, float x1, float y1, float x2, float y2,
                                  int durationMs, int holdMs) {
        JSONObject result = new JSONObject();
        long started = SystemClock.elapsedRealtime();
        try {
            Windows.Selection sel = Windows.resolve(this, spec, PipTarget.sessionPkg());
            if (sel.error != null) return Windows.error(sel, SystemClock.elapsedRealtime() - started);
            if (sel.controlsVisible) return controlsBlocked(sel, started);
            if (!sel.target.bounds.contains((int) x1, (int) y1)
                    || !sel.target.bounds.contains((int) x2, (int) y2)) {
                return outsideTarget(sel, started, x1, y1, x2, y2);
            }
            boolean ok = holdMs > 0
                    ? swipeHold(x1, y1, x2, y2, durationMs, holdMs)
                    : swipe(x1, y1, x2, y2, durationMs);
            result.put("ok", ok);
            JSONArray from = new JSONArray();
            from.put(x1).put(y1);
            JSONArray to = new JSONArray();
            to.put(x2).put(y2);
            result.put("from", from).put("to", to).put("duration", durationMs)
                    .put("hold", holdMs)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            fillTarget(result, sel);
            markInput(result, sel, true);
            return result;
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage())
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            } catch (Throwable ignored) {
                return result;
            }
        }
    }

    public void screenshot(File out, ShotCallback callback) {
        if (Build.VERSION.SDK_INT < 30) {
            callback.done(false, "screenshot requires Android 11+");
            return;
        }
        ScreenshotApi30.capture(this, out, callback);
    }

    /**
     * Kept in a nested class so devices below API 30 never try to resolve the
     * screenshot API in the main class.
     */
    private static final class ScreenshotApi30 {
        static void capture(final AccessibilityService service, final File out,
                            final ShotCallback callback) {
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult result) {
                                Bitmap bitmap = null;
                                try {
                                    bitmap = Bitmap.wrapHardwareBuffer(
                                            result.getHardwareBuffer(), result.getColorSpace());
                                    if (bitmap == null) throw new IllegalStateException("wrap failed");
                                    File parent = out.getParentFile();
                                    if (parent != null) parent.mkdirs();
                                    FileOutputStream fos = new FileOutputStream(out);
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                                    fos.close();
                                    callback.done(true, out.getAbsolutePath());
                                } catch (Throwable t) {
                                    callback.done(false, t.getClass().getSimpleName() + ": " + t.getMessage());
                                } finally {
                                    if (bitmap != null) bitmap.recycle();
                                    result.getHardwareBuffer().close();
                                }
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                callback.done(false, "screenshot error " + errorCode);
                            }
                        });
            } catch (Throwable t) {
                callback.done(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }
}
