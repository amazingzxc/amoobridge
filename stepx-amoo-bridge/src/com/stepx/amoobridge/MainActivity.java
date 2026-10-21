package com.stepx.amoobridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF10131A);

        TextView title = new TextView(this);
        title.setText("stepx-amoo-bridge");
        title.setTextSize(24);
        title.setTextColor(0xFF7EE0C0);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(0xFFE8ECF4);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        root.addView(btn("① 授予「所有文件访问」", new View.OnClickListener() {
            public void onClick(View v) { openAllFiles(); }
        }));
        root.addView(btn("② 开启无障碍（UI 自动化）", new View.OnClickListener() {
            public void onClick(View v) { openAccessibility(); }
        }));
        root.addView(btn("③ 允许后台常驻（电池优化）", new View.OnClickListener() {
            public void onClick(View v) { openBattery(); }
        }));
        root.addView(btn("④ 允许「修改系统设置」", new View.OnClickListener() {
            public void onClick(View v) { openWriteSettings(); }
        }));
        root.addView(btn("启动桥服务", new View.OnClickListener() {
            public void onClick(View v) { startBridge(); refresh(); }
        }));
        root.addView(btn("停止桥服务", new View.OnClickListener() {
            public void onClick(View v) {
                HttpBridge.stop();
                stopService(new Intent(MainActivity.this, BridgeService.class));
                refresh();
            }
        }));
        root.addView(btn("刷新状态", new View.OnClickListener() {
            public void onClick(View v) { refresh(); }
        }));

        TextView help = new TextView(this);
        help.setTextSize(12);
        help.setTextColor(0xFF8B93A7);
        help.setText("\n两条通道：\n1) 文件信箱 /sdcard/bridge-amoobridge  →  cmd_<id>.json 进，result_<id>.json 出\n2) 本机 HTTP  http://127.0.0.1:8896/call  →  {\\\"tool\\\":\\\"exec\\\",\\\"args\\\":{...}}\n   令牌在 /sdcard/bridge-amoobridge/token.txt，请求带 Authorization: Bearer <令牌>\n\n工具：exec ls read write mkdir rm mv cp stat start_app open_url share_text\nclipboard toast notify list_apps battery volume brightness vibrate http\nui_tap ui_swipe ui_click ui_dump shot ui_back ui_home ui_recents\n\nUI 工具需要开启无障碍服务（按钮②）");
        root.addView(help);

        ScrollView sc = new ScrollView(this);
        sc.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sc);

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        BridgeFcm.start(this);
        refresh();
        startBridge();
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 8;
        b.setLayoutParams(lp);
        return b;
    }

    private void openAllFiles() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {}
        openDetails();
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable ignored) {}
    }

    private void openBattery() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    openDetails();
                    return;
                }
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable ignored) { openDetails(); }
    }

    private void openWriteSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {}
        openDetails();
    }

    private void openDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {}
    }

    private void startBridge() {
        Intent i = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        WatchdogReceiver.schedule(this);
        BridgeJobService.schedule(this);
    }

    private void refresh() {
        boolean allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        boolean writeSet = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
        boolean batteryOk = true;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Throwable ignored) {}
        }
        java.io.File dir = new java.io.File(BridgeService.BRIDGE_DIR);
        java.io.File st = new java.io.File(dir, "status.json");
        String token = HttpBridge.ensureToken();
        String shown = token.length() > 8 ? token.substring(0, 4) + "…" + token.substring(token.length() - 4) : token;
        StringBuilder sb = new StringBuilder();
        sb.append("文件访问：").append(allFiles ? "✓" : "✗ 必须授予").append('\n');
        sb.append("无障碍：").append(BridgeAccessibilityService.isConnected() ? "✓ 已连接（UI 可用）" : "✗ 未开启（UI 工具不可用）").append('\n');
        sb.append("修改系统设置：").append(writeSet ? "✓" : "✗（调亮度需要）").append('\n');
        sb.append("电池优化：").append(batteryOk ? "✓ 已豁免" : "✗ 建议允许后台常驻").append('\n');
        sb.append("HTTP 端口：").append(HttpBridge.port() > 0 ? String.valueOf(HttpBridge.port()) : "未启动").append('\n');
        sb.append("令牌：").append(shown).append("（完整值在 /sdcard/bridge-amoobridge/token.txt）\n");
        sb.append("信箱目录：").append(BridgeService.BRIDGE_DIR)
          .append(allFiles && dir.canWrite() ? "（可写）" : "（不可写）").append('\n');
        if (st.exists()) sb.append("心跳：").append((System.currentTimeMillis() - st.lastModified()) / 1000).append(" 秒前\n");
        else sb.append("心跳：无（服务未启动）\n");
        java.io.File fcmToken = new java.io.File(dir, "fcm_token.txt");
        java.io.File fcmStatus = new java.io.File(dir, "fcm_status.json");
        sb.append("FCM：").append(fcmToken.exists() && fcmToken.length() > 0 ? "✓ 已有 token" : "✗ 暂无 token")
          .append(fcmStatus.exists() ? "（fcm_status.json 可查原因）" : "").append('\n');
        status.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
