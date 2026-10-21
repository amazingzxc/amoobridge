# StepBridge Handoff

更新时间：2026-10-21 17:50

## 当前状态

- 分支：`codex/accessibility-ui`
- 远程仓库：`https://github.com/amazingzxc/amoobridge.git`
- 应用包名：`com.stepx.amoobridge`
- 源码：`stepx-amoo-bridge/`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`（约 38KB）
- 测试机：StepX PZ261A，Android 16（SDK 36），StepOS
- **当前是纯原生 APK：没有 FCM、没有 GBox、没有 Google 服务依赖。**

FCM/GBox 的历史实验保留在 `codex/current-changes`（`8f0c792`）；
本分支已把这些依赖、代码和文档全部移除。

## 架构

```
手机 agent
  │  HTTP POST http://127.0.0.1:8896/call
  ▼
BridgeService（前台服务）
  │
  ├─ HttpBridge / 文件信箱
  └─ BridgeAccessibilityService（系统长期绑定）
        ├─ ui_tap / ui_swipe / ui_click
        ├─ ui_dump / shot
        └─ ui_back / ui_home / ui_recents
```

没有 FCM 唤醒、没有 GBox 容器、没有 VPN 依赖。

## 后台保活（已验证）

开启无障碍后，系统会长期绑定 `BridgeAccessibilityService`，StepOS 不再冻结原生 uid：

- 退后台 60 秒：`/ping` 200；
- 退后台 120 秒：`/ping` 200；
- `status.json` 持续更新，日志里没有 `FREEZE ... freezeValue=1`；
- crash 缓冲没有本应用 FATAL（移除 FCM 后不再触发 GMS 崩溃）。

**关键前提：无障碍服务必须是 connected 状态。**

## UI 工具（已验证）

通过 `POST /call` 或文件信箱调用：

| 工具 | 说明 |
|---|---|
| `ui_dump` | 当前界面写入 `/sdcard/bridge-amoobridge/ui_dump.json` |
| `ui_click {"text":"外卖"}` | 按文本/描述点击 |
| `ui_tap {"x":..,"y":..}` | 坐标点击 |
| `ui_swipe {"x1":..,"y1":..,"x2":..,"y2":..,"duration":..}` | 滑动 |
| `shot` | 截图到 `shot_<ts>.png` |
| `ui_back` / `ui_home` / `ui_recents` | 全局动作 |

实测（美团）：

- `ui_click 外卖` → 进入 `com.sankuai.waimai...TakeoutActivity`；
- `ui_tap` 搜索框 → 进入 `GlobalSearchActivity`；
- `ui_swipe` 返回 ok；
- `shot` 生成约 100KB、可正常查看的截图；
- `ui_click 美团`（桌面图标）→ 拉起/恢复美团。

## 打开应用的注意事项

`start_app` / `open_url` 在桥处于后台时会被 StepOS 拦截：

```
CommonInterception ... Intercept = 9 | Reason = Caller process is background
```

`start_app` 返回 `{"ok":true}` 不代表界面真的起来了。

正确方式：

1. `ui_home`；
2. `ui_dump` 找到目标应用文本（如 `美团`）；
3. `ui_click {"text":"美团"}`。

由 launcher 启动应用，不受后台 Activity 启动限制。

## 构建与安装

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

本机 `adb install` 会返回 `INSTALL_FAILED_USER_RESTRICTED`，需要：

1. `adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/amoobridge.apk`
2. 手机文件管理器 → 下载 → 点 APK → **仍要安装** → **完成**

安装后补权限：

```bash
adb shell appops set com.stepx.amoobridge MANAGE_EXTERNAL_STORAGE allow
```

开启无障碍：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.stepx.amoobridge/com.stepx.amoobridge.BridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## 当前设备状态

- 纯原生 APK 已安装（`lastUpdateTime=2026-10-21 17:34:28`）；
- 无障碍服务已开启并 connected；
- 原生应用运行中，占用 8896；
- GBox 已停止；GBox 里的旧克隆仍在，但不再使用；
- 旧的 FCM token / `fcm_status.json` 可能还留在桥目录，属于历史文件，无影响，可删。

## Amoo（com.stepos.ailauncher）调查结论

- 包名 `com.stepos.ailauncher`，label **Amoo**，系统应用；
- 有 `SCREEN_OPS`、`ACCESS_MCP_SERVICES`、`SYSTEM_ALERT_WINDOW`、
  `WRITE_SECURE_SETTINGS`、`MANAGE_ACTIVITY_TASKS`、`ACCESS_SURFACE_FLINGER`；
- 但 `IScreenOpsService` 只有 `getEditContent` / `setEditContent` /
  `setEditContentBatch`（只读写输入框）；
- `ScreenMCPService` 只有截图/录屏；
- 没有 `INJECT_EVENTS`、没有 AccessibilityService、没有 `dispatchGesture`；
- 所以 Amoo 自己无法模拟点击，UI 能力必须由本桥提供。

## 后续建议

1. **重启测试**：确认重启后无障碍服务仍自动绑定、桥自动启动、8896 可用。
2. **锁屏/息屏测试**：当前只验证了亮屏退后台。
3. 如果后续需要远程控制，可以单独做中继/推送方案，不要重新把 FCM 塞回主构建。
4. 提交前确认 `work/`、`.DS_Store`、测试 APK 没有被加入。
