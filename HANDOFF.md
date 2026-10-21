# StepBridge Handoff

更新时间：2026-10-21 17:40

## 当前状态

- 当前分支：`codex/accessibility-ui`
- 远程仓库：`https://github.com/amazingzxc/amoobridge.git`
- 应用包名：`com.stepx.amoobridge`
- Android 模块：`app/`
- 业务源码：`stepx-amoo-bridge/`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 测试机：StepX PZ261A，Android 16（SDK 36），StepOS，GBox 1.8.4.02

FCM/诊断部分已提交到 `codex/current-changes`（8f0c792），
无障碍 UI 部分在 `codex/accessibility-ui` 分支继续。

## 本地 agent + 无障碍（2026-10-21 已验证，主方案）

原生应用现在带 `BridgeAccessibilityService`。开启无障碍后：

- 系统长期绑定该服务，**StepOS 不再冻结原生 uid**：
  实测退后台 3 分钟，8896 仍返回 200，`status.json` 持续更新；
- 新增 UI 工具（HTTP `/call` 或文件信箱都可用）：
  - `ui_dump`：写 `/sdcard/bridge-amoobridge/ui_dump.json`
  - `ui_click {"text":"外卖"}`：按文本点击
  - `ui_tap {"x":540,"y":1200}`
  - `ui_swipe {"x1":600,"y1":1700,"x2":600,"y2":700,"duration":400}`
  - `shot`：截图到 `shot_<ts>.png`
  - `ui_back` / `ui_home` / `ui_recents`
- 实测（美团）：
  - `ui_click 外卖` → `TakeoutActivity`
  - `ui_tap` 搜索框 → `GlobalSearchActivity`
  - `ui_swipe` 正常
  - `shot` 生成约 100KB 的截图
  - `ui_click 美团`（桌面图标）可拉起/恢复美团
- 结论：**本地 agent 不需要 FCM / GBox / VPN**，直接用原生 + 无障碍。

开启无障碍：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.stepx.amoobridge/com.stepx.amoobridge.BridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

`start_app` / `open_url` 在后台仍会被 StepOS 拦截；
让 agent 用 `ui_click` 点桌面图标来打开应用。

## FCM 现状（2026-10-21 已验证可用）

**结论：在这台机器上，FCM 可以通过 GBox 克隆 + 手机 VPN 正常工作。**

关键前提：

1. **手机必须开 VPN**（全局或至少让 GBox/Google 域名走代理）。
   这是之前所有 `SERVICE_NOT_AVAILABLE` 的根因；Mac 开着 VPN，手机没开。
2. 运行 FCM 的是 **GBox 里的 `stepx-amoo-bridge` 克隆**，不是原生应用。
   克隆里用的是 `firebase-messaging:19.0.0`（纯 GCM 注册，无 Firebase Installations），
   对应安装包在手机 `/sdcard/Download/amoobridge-fcm19.apk`。
3. GBox 必须处于运行状态（它承载 microG 的 MCS 连接）。

### 已验证的证据

- microG 成功向 Google 发出 GCM `register3`，并拿到 token：

  ```
  RegisterRequest[... app="com.stepx.amoobridge" sender="816279290204" ...]
  Response token=fXQ3jr8epLE:APA91bHiqv...3xOhqw
  BridgeFCM: FCM token acquired
  ```

- `fcm_token.txt` 已生成；`fcm_status.json` 为 `{"state":"ok",...}`。
- 用 Firebase Admin 服务账号通过 HTTP v1 发送 data-only 消息
  `{"type":"bridge_wake"}`，返回 200，消息 ID 例如
  `projects/amoo-bridge/messages/0:1792572121075444%...`。
- 设备日志出现：

  ```
  GmsGcmMcsSvc: Deliver message to all receivers in package com.stepx.amoobridge
  ActivityManager: start_service ... com.stepx.amoobridge/.BridgeService
  ```

- **冷启动唤醒**：先 `am force-stop com.gbox.android`（克隆进程消失），
  再只启动 GBox 主界面（不点克隆），发送 FCM 后：
  - 克隆进程被拉起（pid 15450）；
  - `BridgeService` 启动；
  - `curl /ping` 返回 200，`/fcm` 返回 `has_token:true`。
- **重复唤醒**：wake 窗口约 60 秒后应用被 StepOS 冻结，`/ping` 超时；
  再发一条 FCM，`/ping` 立即恢复 200。

### 使用的发送脚本

仓库内的 `scripts/send_fcm.py`（无凭据，已入库）使用
`/Users/zxc/Downloads/amoo-bridge-firebase-adminsdk-fbsvc-d10b4a37e5.json`
服务账号签发 OAuth2 token，再调用：

```bash
python3 scripts/send_fcm.py --sa ~/Downloads/amoo-bridge-firebase-adminsdk-fbsvc-d10b4a37e5.json
```

```
POST https://fcm.googleapis.com/v1/projects/amoo-bridge/messages:send
{
  "message": {
    "token": "<设备 token>",
    "android": {"priority": "high"},
    "data": {"type": "bridge_wake"}
  }
}
```

服务账号 JSON 不要提交到仓库，用完可删除/吊销。

## 运行方式（2026-10-21 更新）

本地 agent 用**原生应用 + 无障碍**，不需要 FCM：

1. 保持原生应用启用；
2. 开启无障碍（命令见上一节，或点应用里的按钮②）；
3. agent 直接调 `http://127.0.0.1:8896/call` 的 `ui_*` 工具；
4. 打开应用优先用 `ui_click` 点桌面图标，不要依赖 `start_app`。

远程 FCM 仍可用 GBox 克隆（19.0.0），但它和原生不能同时跑：

- 用 GBox 前：`adb shell pm disable-user --user 0 com.stepx.amoobridge`
- 测完切回原生：`adb shell pm enable com.stepx.amoobridge`，
  再 `am start -n com.stepx.amoobridge/.MainActivity`
- **不要删除 GBox 里的克隆和 `/sdcard/Download/amoobridge-fcm19.apk`**，
  远程 FCM 还需要它们。

GBox 的 recents 锁已实测**不能阻止 StepOS 冻结**，GBox 保活只能靠 FCM 反复唤醒。

## 原生 GMS 的问题（保留记录）

- GSF 16 的 provider authority 改成了
  `com.google.android.gms.gservices.provider.do.not.use` 且不导出；
  GMS 25.39.31 仍注册旧 authority `com.google.android.gsf.gservices`，启动即崩。
- 用 GSF shim 修掉这个崩溃后，GMS 又崩在 Android 16 的
  `BackgroundServiceStartNotAllowedException`（CHIMERA_LOG）。
  侧载 GMS 是普通用户应用，无法豁免；要原生 FCM 必须把 GMS 做成系统应用。
- 结论：原生应用在这台机器上收不了 FCM，GBox 克隆是唯一可行路径。

## Amoo（com.stepos.ailauncher）调查结论

- 包名 `com.stepos.ailauncher`，label **Amoo**，系统应用；
- 拥有 `SCREEN_OPS`、`ACCESS_MCP_SERVICES`、`SYSTEM_ALERT_WINDOW`、
  `WRITE_SECURE_SETTINGS`、`MANAGE_ACTIVITY_TASKS`、`ACCESS_SURFACE_FLINGER`；
- 但它绑定的 `IScreenOpsService` 只有：
  `getEditContent` / `setEditContent` / `setEditContentBatch`（只读写输入框）；
- 它的 `ScreenMCPService` 只有截图/录屏工具；
- **没有** `INJECT_EVENTS`、没有 AccessibilityService、没有 `dispatchGesture`；
- 因此无法通过配置让 Amoo 直接模拟点击，必须由本桥的
  `BridgeAccessibilityService` 提供 UI 能力。

## 本轮代码改动

- 新增 `stepx-amoo-bridge/src/com/stepx/amoobridge/BridgeFcm.java`
  - 主动调用 `FirebaseMessaging.getInstance().getToken()`；
    之前代码里没有任何地方请求 token，`fcm_token.txt` 永远不会生成；
  - 60 秒超时、失败按 2 分钟起指数退避（上限 30 分钟）；
  - 成功写 `fcm_token.txt`，每次结果写 `fcm_status.json`。
- `BridgeService.onCreate()`、`MainActivity.onCreate()` 调用 `BridgeFcm.start(this)`。
- `BridgeFirebaseMessagingService.onNewToken()` 复用 `BridgeFcm` 持久化并更新状态。
- `HttpBridge` 新增 `GET /fcm`，`/status` 附带 FCM 状态。
- `Tools` 新增 `http` 工具，可在应用进程内探测 HTTPS。
- `MainActivity` 状态页显示 FCM 是否有 token。

## 构建与安装

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

本机 `adb install` 会返回 `INSTALL_FAILED_USER_RESTRICTED`，需要：

1. `adb push ... /sdcard/Download/`
2. 在手机文件管理器中点 APK → **仍要安装** → **完成**

**重装后注意：** 如果包 uid 变化，`MANAGE_EXTERNAL_STORAGE` 会掉，
表现为服务显示 running、但 `status.json` 不更新、HTTP 一直 401。
重新授权：

```bash
adb shell appops set com.stepx.amoobridge MANAGE_EXTERNAL_STORAGE allow
```

或点应用里的「① 授予所有文件访问」。

## 后台冻结（分运行时）

- **原生 + 无障碍（uid 10202）**：无障碍服务被系统长期绑定，
  实测退后台 3 分钟不冻结，8896 持续可用；这是当前主方案。
- **GBox 克隆（uid 10193）**：仍然 3–5 秒后被冻结；
  前台服务、WakeLock、Alarm、Job、recents 锁都无效；
  FCM 消息可以重新拉起/解冻，适合远程按需唤醒。
- 早期试过的“服务自绑定”“通知使用权”对原生无效，已从代码中移除。

## start_app / open_url 的后台限制（2026-10-21 确认）

桥在后台时，`start_app` / `open_url` 会被 StepOS 的 `CommonInterception` 拦截：

```
CommonInterception: ACT | ... callerUid = 10193 | callerPid = 17036
  | callerPackage = com.gbox.android | calledPackage = com.sankuai.meituan
  | callerVisible = false | ... | Intercept = 9
  | Reason = Caller process is background
StepOSAppMonitor: ... "allowed":false ...
```

- 这不是普通 AOSP 的后台启动限制，而是 StepOS 自己的拦截；
- 已实测给 GBox 和 `com.stepx.amoobridge` 授予 `SYSTEM_ALERT_WINDOW` 仍被拒
  （测试后已恢复 `default`）；
- FCM 唤醒只能解冻进程，不能让调用方变成 visible，所以也无效；
- 只有 GBox/克隆本身处于前台（`callerVisible=true`）时，`start_app` 才会成功；
- `start_app` 返回 `{"ok":true}` 只表示 `startActivity` 没抛异常，不代表界面真的起来；
- 手机 agent 在本机启动应用时不要走桥，直接用 agent 自己的系统/UI 能力；
  桥负责 exec、文件、HTTP 等非 Activity 操作。

## 设备上的遗留物

- 原生应用 `com.stepx.amoobridge` 已**停用**（`pm disable-user`），
  避免开机自动启动占用 8896；GBox 克隆不受影响，仍可独立冷启动。
  恢复：`adb shell pm enable com.stepx.amoobridge`。
- `/sdcard/Download/amoobridge-fcm19.apk`：**不要删**，这是 GBox 可用的 FCM 构建。
- `/sdcard/Download` 里其他 `amoobridge-*.apk` 是实验包，可清理。
- 服务账号 JSON 在 Mac `~/Downloads`，不要提交。
- 之前的 GSF shim（`com.stepx.gsfshim`）已卸载。
- `cached_apps_freezer` 已恢复为 `enabled`，通知使用权已撤销。

## 后续建议

1. 把这套 GBox 运行方式做成脚本/说明：开 VPN → 开 GBox → 停原生应用。
2. 如果希望不依赖 GBox，需要系统级 GMS/microG，或改用 HMS Push / 短信唤醒。
3. 每次唤醒后的 60 秒窗口可以根据需要调大（`BridgeService.WAKE_WINDOW_MS`）。
4. 不要再随意重装原生 APK；重装后如果要重新克隆，先用 19.0.0 的包。

## 基础冒烟测试

```bash
adb shell am force-stop com.stepx.amoobridge
adb shell monkey -p com.stepx.amoobridge 1
adb shell pidof com.stepx.amoobridge
adb shell dumpsys activity services com.stepx.amoobridge
adb forward tcp:18896 tcp:8896
TOKEN=$(adb shell cat /sdcard/bridge-amoobridge/token.txt | tr -d '\r')
curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/ping
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/fcm
adb shell cat /sdcard/bridge-amoobridge/status.json
adb shell cat /sdcard/bridge-amoobridge/fcm_status.json
adb logcat -d -b crash | grep 'com.stepx.amoobridge'
```
