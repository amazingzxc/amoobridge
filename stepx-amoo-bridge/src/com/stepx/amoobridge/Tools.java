package com.stepx.amoobridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Vibrator;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

public class Tools {
    private static Context ctx;

    public static void init(Context c) {
        ctx = c.getApplicationContext();
    }

    public static Context ctxRef() {
        return ctx;
    }

    public static final String[] NAMES = {
        "ping", "info", "exec", "ls", "read", "write", "mkdir", "rm", "mv", "cp", "stat",
        "start_app", "open_url", "share_text", "clipboard", "toast", "notify", "list_apps",
        "battery", "volume", "brightness", "vibrate", "http",
        "ui_tap", "ui_swipe", "ui_click", "ui_dump", "shot", "ui_back", "ui_home", "ui_recents",
        "ui_windows", "pip_target", "open_small_window", "start_small_window", "keep_hot"
    };

    public static JSONObject dispatch(String tool, JSONObject a) {
        JSONObject r = new JSONObject();
        try {
            if (tool.equals("shell_exec")) tool = "exec";
            if (tool.equals("file_read")) tool = "read";
            if (tool.equals("file_write")) tool = "write";
            if (tool.equals("file_list")) tool = "ls";
            if (tool.equals("sys_info")) tool = "info";

            if (tool.equals("ping")) {
                r.put("ok", true).put("pong", System.currentTimeMillis())
                 .put("pid", android.os.Process.myPid())
                 .put("ui", false);
                return r;
            }
            if (tool.equals("http")) {
                return http(a);
            }
            if (tool.equals("ui_tap")) return UiTools.tap(a);
            if (tool.equals("ui_swipe")) return UiTools.swipe(a);
            if (tool.equals("ui_click")) return UiTools.click(a);
            if (tool.equals("ui_dump")) return UiTools.dump(a);
            if (tool.equals("ui_windows")) return UiTools.windows(a);
            if (tool.equals("pip_target")) return UiTools.pipTarget(a);
            if (tool.equals("open_small_window")) return UiTools.openSmallWindow(a);
            if (tool.equals("start_small_window")) return UiTools.startSmallWindow(a);
            if (tool.equals("shot")) return UiTools.shot(a);
            if (tool.equals("keep_hot")) return KeepHot.tool(a);
            if (tool.equals("ui_back") || tool.equals("ui_home")
                    || tool.equals("ui_recents")) {
                JSONObject action = new JSONObject();
                action.put("action", tool.substring(3));
                return UiTools.global(action);
            }
            if (tool.equals("info")) {
                r.put("ok", true).put("model", Build.MODEL).put("brand", Build.BRAND)
                 .put("device", Build.DEVICE).put("sdk", Build.VERSION.SDK_INT)
                 .put("release", Build.VERSION.RELEASE).put("uid", android.os.Process.myUid())
                 .put("ui", false)
                 .put("http", HttpBridge.port());
                try {
                    r.put("ver", ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName);
                } catch (Throwable ignored) {}
                return r;
            }
            if (tool.equals("exec")) {
                String cmd = a.optString("cmd", "");
                if (cmd.length() == 0) return r.put("ok", false).put("error", "empty cmd");
                return exec(cmd, Math.max(1, Math.min(a.optInt("timeout", 20), 120)));
            }
            if (tool.equals("ls")) return ls(a);
            if (tool.equals("read")) return read(a);
            if (tool.equals("write")) return write(a);
            if (tool.equals("mkdir")) {
                File f = new File(a.optString("path", ""));
                return r.put("ok", f.mkdirs() || f.isDirectory()).put("path", f.getAbsolutePath());
            }
            if (tool.equals("rm")) {
                File f = new File(a.optString("path", ""));
                boolean ok = a.optBoolean("recursive", true) ? deleteTree(f) : f.delete();
                return r.put("ok", ok).put("path", f.getAbsolutePath());
            }
            if (tool.equals("mv") || tool.equals("cp")) {
                File s = new File(a.optString("path", ""));
                File d = new File(a.optString("dest", ""));
                boolean ok = tool.equals("mv") ? s.renameTo(d) : copyTree(s, d);
                return r.put("ok", ok).put("path", s.getAbsolutePath()).put("dest", d.getAbsolutePath());
            }
            if (tool.equals("stat")) {
                File f = new File(a.optString("path", ""));
                return r.put("ok", f.exists()).put("path", f.getAbsolutePath())
                        .put("exists", f.exists()).put("dir", f.isDirectory())
                        .put("size", f.length()).put("mtime", f.lastModified())
                        .put("readable", f.canRead()).put("writable", f.canWrite());
            }
            if (tool.equals("start_app")) return startApp(a);
            if (tool.equals("open_url")) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url", "")));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return r.put("ok", true);
            }
            if (tool.equals("share_text")) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, a.optString("text", ""));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(i, a.optString("title", "分享")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return r.put("ok", true);
            }
            if (tool.equals("clipboard")) {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (a.optString("op", "get").equals("set")) {
                    cm.setPrimaryClip(ClipData.newPlainText("bridge", a.optString("text", "")));
                    return r.put("ok", true).put("op", "set");
                }
                String t = "";
                ClipData c = cm.getPrimaryClip();
                if (c != null && c.getItemCount() > 0 && c.getItemAt(0).getText() != null) {
                    t = c.getItemAt(0).getText().toString();
                }
                return r.put("ok", true).put("op", "get").put("text", t);
            }
            if (tool.equals("toast")) {
                final String m = a.optString("text", "");
                new android.os.Handler(ctx.getMainLooper()).post(new Runnable() {
                    public void run() { Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show(); }
                });
                return r.put("ok", true);
            }
            if (tool.equals("notify")) {
                BridgeService.notifyText(a.optString("title", "StepBridge"), a.optString("text", ""));
                return r.put("ok", true);
            }
            if (tool.equals("list_apps")) return listApps();
            if (tool.equals("battery")) return battery();
            if (tool.equals("volume")) return volume(a);
            if (tool.equals("brightness")) return brightness(a);
            if (tool.equals("vibrate")) {
                Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(a.optInt("ms", 200));
                return r.put("ok", true);
            }
            return r.put("ok", false).put("error", "unknown tool: " + tool);
        } catch (Throwable t) {
            try {
                return r.put("ok", false).put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {
                return r;
            }
        }
    }

    private static JSONObject startApp(JSONObject a) throws Exception {
        JSONObject r = new JSONObject();
        String pkg = a.optString("package", "");
        Intent i = null;
        JSONObject ex = a.optJSONObject("extras");
        if (a.has("component")) {
            String[] parts = a.optString("component").split("/");
            if (parts.length == 2) {
                String cls = parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1];
                i = new Intent();
                i.setClassName(parts[0], cls);
            }
        }
        if (i == null) i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return r.put("ok", false).put("error", "no launch intent: " + pkg);
        if (ex != null) {
            java.util.Iterator<String> it = ex.keys();
            while (it.hasNext()) {
                String k = it.next();
                i.putExtra(k, ex.optString(k));
            }
        }
        if (a.has("action")) i.setAction(a.optString("action"));
        if (a.has("uri")) i.setData(Uri.parse(a.optString("uri")));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return r.put("ok", true).put("package", pkg);
    }

    private static JSONObject listApps() throws Exception {
        JSONObject r = new JSONObject();
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        // 走 launcher 查询，比 getInstalledPackages 稳（后者受包可见性限制）
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<String, String>();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        if (ris != null) {
            for (android.content.pm.ResolveInfo ri : ris) {
                try {
                    String pkg = ri.activityInfo.packageName;
                    if (!map.containsKey(pkg)) {
                        map.put(pkg, ri.loadLabel(pm).toString());
                    }
                } catch (Throwable ignored) {}
            }
        }
        // 再补一轮：能看到的已安装包（可能因可见性限制而不全，仅作补充）
        try {
            for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(0)) {
                if (!map.containsKey(pi.packageName)) {
                    String label = pi.packageName;
                    try { label = pm.getApplicationLabel(pi.applicationInfo).toString(); } catch (Throwable ignored) {}
                    map.put(pi.packageName, label);
                }
            }
        } catch (Throwable ignored) {}

        java.util.List<String> pkgs = new java.util.ArrayList<String>(map.keySet());
        java.util.Collections.sort(pkgs, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                return map.get(a).compareToIgnoreCase(map.get(b));
            }
        });
        JSONArray arr = new JSONArray();
        for (String pkg : pkgs) {
            if (arr.length() >= 800) break;
            arr.put(new JSONObject().put("package", pkg).put("label", map.get(pkg)));
        }
        return r.put("ok", true).put("count", arr.length()).put("apps", arr);
    }

    private static JSONObject battery() throws Exception {
        JSONObject r = new JSONObject();
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent b = ctx.registerReceiver(null, f);
        if (b != null) {
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = scale > 0 ? (int) (lvl * 100f / scale) : -1;
            r.put("percent", pct);
            r.put("status", b.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
            r.put("plugged", b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
            r.put("temp", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0);
        }
        return r.put("ok", true);
    }

    private static JSONObject volume(JSONObject a) throws Exception {
        JSONObject r = new JSONObject();
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int stream = AudioManager.STREAM_MUSIC;
        if (a.has("level")) {
            int max = am.getStreamMaxVolume(stream);
            int lv = Math.max(0, Math.min(a.optInt("level"), max));
            am.setStreamVolume(stream, lv, 0);
            return r.put("ok", true).put("level", lv).put("max", max);
        }
        return r.put("ok", true).put("level", am.getStreamVolume(stream))
                .put("max", am.getStreamMaxVolume(stream));
    }

    private static JSONObject brightness(JSONObject a) throws Exception {
        JSONObject r = new JSONObject();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(ctx)) {
            return r.put("ok", false).put("error", "需要「修改系统设置」权限");
        }
        if (a.has("value")) {
            int v = Math.max(0, Math.min(a.optInt("value"), 255));
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
            return r.put("ok", true).put("value", v);
        }
        return r.put("ok", true).put("value",
                Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1));
    }

    private static JSONObject ls(JSONObject a) throws Exception {
        JSONObject r = new JSONObject();
        File d = new File(a.optString("path", "/sdcard"));
        File[] fs = d.listFiles();
        JSONArray arr = new JSONArray();
        if (fs != null) {
            Arrays.sort(fs);
            for (File x : fs) {
                if (arr.length() >= 2000) break;
                arr.put(new JSONObject().put("name", x.getName()).put("dir", x.isDirectory())
                        .put("size", x.length()).put("mtime", x.lastModified()));
            }
        }
        return r.put("ok", fs != null).put("path", d.getAbsolutePath()).put("entries", arr);
    }

    private static JSONObject read(JSONObject a) throws Exception {
        String p = a.optString("path", "");
        File f = new File(p);
        int max = Math.min(a.optInt("max", 256 * 1024), 4 * 1024 * 1024);
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() >= max) break;
        }
        in.close();
        return new JSONObject().put("ok", true).put("path", p)
                .put("content", new String(bo.toByteArray(), "UTF-8")).put("size", f.length());
    }

    private static JSONObject write(JSONObject a) throws Exception {
        File f = new File(a.optString("path", ""));
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream fo = new FileOutputStream(f, a.optBoolean("append", false));
        fo.write(a.optString("content", "").getBytes("UTF-8"));
        fo.close();
        return new JSONObject().put("ok", true).put("path", f.getAbsolutePath()).put("bytes", f.length());
    }

    private static JSONObject exec(String cmd, int timeoutSec) throws Exception {
        JSONObject r = new JSONObject();
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
        pb.directory(new File("/sdcard"));
        final Process p = pb.start();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread et = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream is = p.getErrorStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) {
                        synchronized (err) { if (err.size() < 512 * 1024) err.write(b, 0, n); }
                    }
                } catch (Throwable ignored) {}
            }
        });
        et.start();
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (sb.length() < 1024 * 1024) sb.append(line).append('\n');
        }
        br.close();
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        boolean done = false;
        while (System.currentTimeMillis() < deadline) {
            try { p.exitValue(); done = true; break; } catch (IllegalThreadStateException e) { Thread.sleep(50); }
        }
        if (!done) {
            try { p.destroy(); } catch (Throwable ignored) {}
            return r.put("ok", false).put("error", "timeout " + timeoutSec + "s").put("out", sb.toString());
        }
        et.join(500);
        int code = p.exitValue();
        synchronized (err) { r.put("err", err.toString("UTF-8")); }
        return r.put("ok", code == 0).put("exit", code).put("out", sb.toString());
    }

    private static boolean deleteTree(File f) {
        if (!f.exists()) return true;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteTree(c);
        }
        return f.delete();
    }

    private static boolean copyTree(File s, File d) throws Exception {
        if (s.isDirectory()) {
            if (!d.exists() && !d.mkdirs()) return false;
            File[] cs = s.listFiles();
            if (cs != null) for (File c : cs) if (!copyTree(c, new File(d, c.getName()))) return false;
            return true;
        }
        File parent = d.getParentFile();
        if (parent != null) parent.mkdirs();
        FileInputStream in = new FileInputStream(s);
        FileOutputStream out = new FileOutputStream(d);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        out.close();
        return true;
    }

    /** Minimal HTTPS probe used to diagnose network problems from inside the app. */
    private static JSONObject http(JSONObject a) {
        JSONObject r = new JSONObject();
        String url = a.optString("url", "");
        if (url.length() == 0) {
            try { r.put("ok", false).put("error", "empty url"); } catch (Throwable ignored) {}
            return r;
        }
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(Math.max(1000, Math.min(a.optInt("timeout", 10), 30)) * 1000);
            c.setReadTimeout(Math.max(1000, Math.min(a.optInt("timeout", 10), 30)) * 1000);
            c.setRequestMethod(a.optString("method", "GET"));
            c.setRequestProperty("User-Agent", "stepx-bridge");
            String extra = a.optString("headers", "");
            if (extra.length() > 0) {
                for (String line : extra.split("\n")) {
                    int i = line.indexOf(':');
                    if (i > 0) c.setRequestProperty(line.substring(0, i).trim(), line.substring(i + 1).trim());
                }
            }
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                byte[] b = new byte[2048];
                int n;
                while ((n = in.read(b)) > 0 && sb.length() < 4096) sb.append(new String(b, 0, n, "UTF-8"));
                in.close();
            }
            r.put("ok", true).put("code", code).put("url", url).put("body", sb.toString());
        } catch (Throwable t) {
            try { r.put("ok", false).put("url", url)
                    .put("error", t.getClass().getSimpleName() + ": " + t.getMessage()); } catch (Throwable ignored) {}
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }
}
