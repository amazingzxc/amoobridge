package com.stepx.amoobridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * UI automation entry point. The agent talks to the bridge over HTTP / the file
 * mailbox; this service performs gestures, text lookup and screenshots.
 *
 * It has to be enabled once by the user (Settings -> Accessibility) or via adb:
 *   adb shell settings put secure enabled_accessibility_services \
 *       com.stepx.amoobridge/com.stepx.amoobridge.BridgeAccessibilityService
 *   adb shell settings put secure accessibility_enabled 1
 */
public class BridgeAccessibilityService extends AccessibilityService {
    private static final String TAG = "BridgeA11y";
    private static volatile BridgeAccessibilityService instance;

    public interface ShotCallback {
        void done(boolean ok, String message);
    }

    public static BridgeAccessibilityService get() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

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
    public JSONObject clickText(String text) {
        JSONObject result = new JSONObject();
        try {
            if (text == null || text.length() == 0) {
                return result.put("ok", false).put("error", "empty text");
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return result.put("ok", false).put("error", "no active window");
            }
            AccessibilityNodeInfo found = findNode(root, text, 0, new int[]{0});
            if (found == null) {
                return result.put("ok", false).put("error", "not found: " + text);
            }
            AccessibilityNodeInfo clickable = found;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }
            boolean clicked = false;
            String method = "tap";
            if (clickable != null) {
                clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                method = "node";
            }
            if (!clicked) {
                Rect bounds = new Rect();
                found.getBoundsInScreen(bounds);
                clicked = tap(bounds.centerX(), bounds.centerY(), 60);
                method = "gesture";
            }
            return result.put("ok", clicked).put("text", text).put("method", method);
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
                return result;
            }
        }
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String text,
                                           int depth, int[] visited) {
        if (node == null || depth > 60 || visited[0] > 2000) return null;
        visited[0]++;
        try {
            CharSequence label = node.getText();
            if (label != null && label.toString().contains(text)) return node;
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().contains(text)) return node;
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo hit = findNode(node.getChild(i), text, depth + 1, visited);
                if (hit != null) return hit;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Flatten the current window into a JSON array (text/desc/id/bounds/clickable). */
    public JSONObject dump() {
        JSONObject result = new JSONObject();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return result.put("ok", false).put("error", "no active window");
            }
            JSONArray nodes = new JSONArray();
            int[] visited = new int[]{0};
            walk(root, nodes, 0, visited);
            result.put("ok", true);
            CharSequence pkg = root.getPackageName();
            if (pkg != null) result.put("package", pkg.toString());
            result.put("count", nodes.length());
            result.put("nodes", nodes);
        } catch (Throwable t) {
            try {
                return result.put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
                return result;
            }
        }
        return result;
    }

    private void walk(AccessibilityNodeInfo node, JSONArray out, int depth, int[] visited) {
        if (node == null || depth > 60 || visited[0] > 2000) return;
        visited[0]++;
        try {
            JSONObject item = new JSONObject();
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) item.put("text", text.toString());
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) item.put("desc", desc.toString());
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
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                walk(node.getChild(i), out, depth + 1, visited);
            }
        } catch (Throwable ignored) {
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
