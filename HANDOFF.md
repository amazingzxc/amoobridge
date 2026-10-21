# StepBridge Handoff

更新时间：2026-10-21 16:45

## 当前状态

- 当前分支：`codex/current-changes`
- 远程仓库：`https://github.com/amazingzxc/amoobridge.git`
- 应用包名：`com.stepx.amoobridge`
- Android 模块：`app/`
- 业务源码：`stepx-amoo-bridge/`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 测试机：StepX PZ261A，Android 16（SDK 36），StepOS，GBox 1.8.4.02

本轮代码改动尚未提交。

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

## 运行方式（重要）

这台手机上同时存在两份 StepBridge：

- **原生应用**：`com.stepx.amoobridge`，用 24.1.2 构建，无法收 FCM
  （原生 GMS 被 Android 16 后台限制挡住），但可以正常跑 HTTP；
- **GBox 克隆**：同一个包名，运行在 GBox 容器（uid 10193），
  用 19.0.0 构建，**能收 FCM**。

两者共用 `/sdcard/bridge-amoobridge` 和 8896 端口，不能同时跑。

推荐流程：

1. 手机开 VPN，确认能打开 google.com。
2. 开机后打开一次 **GBox**，并保持它在后台（GBox 设置里可参考“后台保活”）。
3. **停用原生 StepBridge**，避免它开机自动启动并占用 8896：
   - 已执行：`adb shell pm disable-user --user 0 com.stepx.amoobridge`
   - 需要恢复时：`adb shell pm enable com.stepx.amoobridge`
   - 停用后 GBox 克隆仍能独立冷启动并收 FCM（已实测），
     所以不需要卸载原生应用；停用比卸载更安全，也便于以后重新克隆。
4. 需要远程唤醒时发送 data-only 的 `bridge_wake`；
   克隆会被拉起/解冻，8896 可用约 60 秒。
5. 需要持续可用时，手动启动 GBox 里的克隆并保持前台/最近任务。

**不要删除 GBox 里的克隆。** 如果克隆被删掉，重新克隆前必须先把
`/sdcard/Download/amoobridge-fcm19.apk` 装到手机上，否则克隆会使用 24.1.2 构建，
FCM 又会回到 `SERVICE_NOT_AVAILABLE`。

## 原生 GMS 的问题（保留记录）

- GSF 16 的 provider authority 改成了
  `com.google.android.gms.gservices.provider.do.not.use` 且不导出；
  GMS 25.39.31 仍注册旧 authority `com.google.android.gsf.gservices`，启动即崩。
- 用 GSF shim 修掉这个崩溃后，GMS 又崩在 Android 16 的
  `BackgroundServiceStartNotAllowedException`（CHIMERA_LOG）。
  侧载 GMS 是普通用户应用，无法豁免；要原生 FCM 必须把 GMS 做成系统应用。
- 结论：原生应用在这台机器上收不了 FCM，GBox 克隆是唯一可行路径。

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

## 后台冻结（仍存在）

- StepOS 会在应用退到后台约 3–5 秒后冻结整个 uid；
- 前台服务、电池优化白名单、JobScheduler、AlarmManager、临时 allowlist
  都不能阻止；
- 实测“服务自绑定”和“通知使用权”也无效，已从代码中移除；
- 原生应用的 8896 只有前台可靠；
- GBox 克隆被冻结后，FCM 消息可以重新拉起/解冻它（已验证），
  所以远程唤醒路径可用，但每次唤醒后的可用窗口有限。

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
