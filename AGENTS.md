# 项目开发规范

本文档记录本项目在这台测试机（StepX PZ261A / Android 16 / StepOS / GBox）上的完整
构建、安装、部署和验证流程。每次 feature、bug fix 或涉及 Android 源码、Manifest、
资源、Gradle 配置的改动，都必须按本文执行。

## 0. 关键事实

| 项目 | 值 |
|---|---|
| 包名 | `com.stepx.amoobridge` |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 桥目录 | `/sdcard/bridge-amoobridge` |
| HTTP 端口 | `8896` |
| 令牌 | `/sdcard/bridge-amoobridge/token.txt` |
| FCM 状态 | `/sdcard/bridge-amoobridge/fcm_status.json` |
| FCM token | `/sdcard/bridge-amoobridge/fcm_token.txt` |
| 主构建 FCM SDK | `firebase-messaging:24.1.2` |
| GBox 克隆 FCM 构建 | `firebase-messaging:19.0.0`（`/sdcard/Download/amoobridge-fcm19.apk`） |

这台机器上存在两套运行时，**不能同时运行**（它们共用 8896 和桥目录）：

1. **原生应用** `com.stepx.amoobridge`（推荐，当前主运行时）
   - 用主构建（24.1.2）安装；
   - 开启无障碍后，`BridgeAccessibilityService` 被系统长期绑定，
     **实测退后台 3 分钟不冻结，8896 一直可用**；
   - 提供 `ui_tap` / `ui_swipe` / `ui_click` / `ui_dump` / `shot` / `ui_back` 等 UI 工具；
   - 收不到 FCM（原生 GMS 被 Android 16 限制），但本地 agent 不需要 FCM。
2. **GBox 克隆**（可选，仅远程 FCM 唤醒时使用）
   - 同一个包名，运行在 GBox 容器里（uid 10193）；
   - 用 `firebase-messaging:19.0.0` 构建，能收 FCM；
   - 依赖 GBox + 手机 VPN；退后台会被 StepOS 冻结
     （已实测 recents 锁也无效），只能靠 FCM 反复唤醒。

**不要删除 GBox 里的克隆和 `/sdcard/Download/amoobridge-fcm19.apk`。**
远程 FCM 还需要它；本地 agent 优先用原生 + 无障碍。

## 1. 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

成功标志：

- 输出 `BUILD SUCCESSFUL`；
- 生成 `app/build/outputs/apk/debug/app-debug.apk`。

构建失败时不要继续安装，先修编译错误。

> 如果改动涉及 FCM / GBox 运行时，主构建（24.1.2）只用于原生冒烟；
> 要在 GBox 里验证，必须另外用 19.0.0 构建并重新克隆，见第 4 节。

## 2. 安装到手机

**不要用 `./gradlew installDebug` 或 `adb install`。** 这台 StepOS 设备会返回：

```
INSTALL_FAILED_USER_RESTRICTED
```

正确流程：

1. 推送 APK：

   ```bash
   adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/amoobridge-$(date +%H%M).apk
   ```

2. 在手机上打开 **文件管理 → 下载**，点刚推送的 APK；
3. 出现「未备案应用」提示时点 **仍要安装**；
4. 出现「安装成功」后点 **完成**。

如果 APK 已经在 `Download` 里，也可以直接在文件管理器的「最近添加」里点它。
adb 的 UI 自动化（`input tap`）容易点偏，**安装这一步优先让用户手动操作**；
需要自动化时，先用 `uiautomator dump` 确认按钮 bounds 再点。

安装后检查：

```bash
adb shell dumpsys package com.stepx.amoobridge | grep -E 'lastUpdateTime|enabled='
adb shell pm path com.stepx.amoobridge
```

### 重装后的权限

重装后 `MANAGE_EXTERNAL_STORAGE` 可能被重置。表现为：

- 服务显示 running，但 `status.json` 不更新；
- 所有 HTTP 请求返回 401（内存里生成了新 token，读不到旧的 `token.txt`）。

修复：

```bash
adb shell appops set com.stepx.amoobridge MANAGE_EXTERNAL_STORAGE allow
```

或让用户点应用里的「① 授予所有文件访问」。

### 原生应用当前是启用状态（主运行时）

原生 + 无障碍是本地 agent 的主入口，不要停用。只有在需要测试 GBox FCM
时才临时停用，让克隆独占 8896：

```bash
# 测试 GBox/FCM 前
adb shell pm disable-user --user 0 com.stepx.amoobridge

# 测完切回本地 agent
adb shell pm enable com.stepx.amoobridge
adb shell am start -n com.stepx.amoobridge/.MainActivity
```

## 3. 原生应用冒烟测试

先把手机 VPN 打开，并确认 Google 可达（见第 5 节）。

```bash
adb shell pm enable com.stepx.amoobridge
adb shell am force-stop com.stepx.amoobridge
adb shell am start -n com.stepx.amoobridge/.MainActivity
sleep 3
adb shell pidof com.stepx.amoobridge
adb shell dumpsys activity services com.stepx.amoobridge | grep -E 'isForeground|types='

adb forward tcp:18896 tcp:8896
TOKEN=$(adb shell cat /sdcard/bridge-amoobridge/token.txt | tr -d '\r')
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/ping
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/status
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/fcm

adb shell cat /sdcard/bridge-amoobridge/status.json
adb shell cat /sdcard/bridge-amoobridge/fcm_status.json
adb logcat -d -b crash | grep -E 'com.stepx.amoobridge|FATAL EXCEPTION'
```

通过标准：

- 进程存在；
- `isForeground=true`；
- `/ping`、`/status`、`/fcm` 返回 200；
- `status.json` 心跳在 5 秒内；
- crash 缓冲里**没有** `com.stepx.amoobridge` 的 FATAL
  （`com.google.android.gms` 的 FATAL 是这台机器的已知现象，可忽略）。

无障碍是本机保活和 UI 自动化的关键。安装后执行：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.stepx.amoobridge/com.stepx.amoobridge.BridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep -i stepx
```

验证保活：

```bash
adb shell input keyevent KEYCODE_HOME
sleep 60
curl -s -m 5 -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/ping
```

预期 60 秒后仍返回 200；实测原生 + 无障碍 3 分钟不被冻结。
**不需要再用 `pm disable-user`**；原生是当前主运行时。

### 后台行为（原生 + 无障碍）

开启无障碍后，系统会长期绑定 `BridgeAccessibilityService`，StepOS 不再冻结原生 uid。
实测退后台 3 分钟，8896 仍可用：

```bash
adb shell input keyevent KEYCODE_HOME
sleep 60
curl -s -m 5 -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/ping
adb logcat -d | grep -E 'StepOSFreeze.*10202' | tail
```

预期：60 秒后仍返回 200，日志里没有 `FREEZE ... freezeValue=1`。

注意：**GBox 克隆（uid 10193）不享受这个豁免**，仍会被冻结；
GBox 的保活只能靠 FCM 反复唤醒。

## 4. GBox / FCM 验证

> 只有改动涉及 FCM、唤醒或 GBox 运行时，才需要走这一节。

前置条件：

1. 手机 VPN 已开，能打开 `google.com`；
2. GBox 已安装并登录 Google 账号；
3. GBox 里存在 `stepx-amoo-bridge` 克隆（**不要删**）。

### 4.1 确认 GBox 克隆能收 FCM

```bash
adb shell pm disable-user --user 0 com.stepx.amoobridge
adb shell am force-stop com.gbox.android
adb shell am start -n com.gbox.android/.SplashActivity
sleep 10
# 发送 data-only 唤醒消息（见 4.2）
```

发送后检查：

```bash
adb shell pidof com.stepx.amoobridge          # 克隆进程被拉起
adb logcat -d | grep -E 'GmsGcmMcsSvc|BridgeFCM|BridgeService' | tail
adb shell cat /sdcard/bridge-amoobridge/status.json
TOKEN=$(adb shell cat /sdcard/bridge-amoobridge/token.txt | tr -d '\r')
curl -s -m 5 -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/ping
```

通过标准：

- 日志出现 `GmsGcmMcsSvc: Deliver message to all receivers in package com.stepx.amoobridge`；
- 日志出现 `start_service ... BridgeService`；
- 克隆 pid 出现；
- `/ping` 返回 200；
- `fcm_status.json` 为 `{"state":"ok",...}`。

### 4.2 发送测试消息

使用仓库中的 `scripts/send_fcm.py`（不包含任何凭据），并配一个 Firebase Admin
服务账号 JSON（**不要提交到仓库**）：

```bash
# 依赖：python3 -m pip install cryptography
python3 scripts/send_fcm.py --sa ~/Downloads/amoo-bridge-firebase-adminsdk-xxxx.json

# 或用环境变量
export FCM_SERVICE_ACCOUNT=~/Downloads/amoo-bridge-firebase-adminsdk-xxxx.json
python3 scripts/send_fcm.py
```

脚本行为：

- 用服务账号签 JWT，换 OAuth2 token；
- 从手机读取 `/sdcard/bridge-amoobridge/fcm_token.txt`；
- 调用 `https://fcm.googleapis.com/v1/projects/amoo-bridge/messages:send`；
- 发送 data-only：

  ```json
  {
    "message": {
      "token": "<设备 token>",
      "android": { "priority": "high" },
      "data": { "type": "bridge_wake" }
    }
  }
  ```

可选参数：`--type` 改 `data.type`，`--data key=value` 追加字段，
`--token-file` 指定其他 token 文件。

通过标准：

- HTTP 200，返回 `projects/amoo-bridge/messages/...`；
- 设备日志出现 FCM 投递和 `BridgeService` 启动；
- 60 秒窗口内 `/ping` 200。

### 4.3 重新克隆（仅在克隆丢失/需要更新时）

1. 先安装 19.0.0 构建：

   ```bash
   adb push /path/to/amoobridge-fcm19.apk /sdcard/Download/
   ```

   然后在手机上用文件管理器安装（同第 2 节）。

2. 在 GBox 中长按 `stepx-amoo-bridge` → **删除** → 确认；
3. GBox 右上角菜单 → **克隆** → 选中 `stepx-amoo-bridge` → **克隆** → **添加**；
4. 启动克隆，确认 `fcm_status.json` 变成 `ok`。

> 如果重新克隆后没有 token，先检查手机 VPN 是否开启，再检查
> `firebase-messaging` 是否真的是 19.0.0（24.1.2 在这台机器上收不到 FCM）。

## 5. 网络与 VPN 检查

手机必须能访问 Google。用应用自带 `http` 工具验证：

```bash
TOKEN=$(adb shell cat /sdcard/bridge-amoobridge/token.txt | tr -d '\r')
curl -s -m 20 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tool":"http","args":{"url":"https://www.google.com/","timeout":15}}' \
  http://127.0.0.1:18896/call
```

通过：返回 `"code":200`。
如果超时/5xx，先让用户打开手机 VPN，再重测；不要继续排查 FCM。

## 6. 验收清单

每次改动结束前逐项确认：

- [ ] `./gradlew assembleDebug` 成功；
- [ ] APK 已通过文件管理器安装，`lastUpdateTime` 是本次时间；
- [ ] 安装后 `MANAGE_EXTERNAL_STORAGE` 仍为 allow（否则 401 / 心跳不动）；
- [ ] 原生前台冒烟：`/ping`、`/status`、`/fcm` 返回 200；
- [ ] 涉及 FCM：`fcm_status.json` 为 `ok`，测试消息 HTTP 200，设备有投递日志；
- [ ] 涉及 FCM：冷启动唤醒（先停 GBox 再发消息）能拉起克隆；
- [ ] crash 缓冲没有 `com.stepx.amoobridge` 的 FATAL；
- [ ] 后台行为按已知限制记录，没有误报“后台常驻可用”；
- [ ] [HANDOFF.md](HANDOFF.md) 已更新（设备状态、验证结果、遗留问题）。

## 7. 不要提交

- `work/`（实验脚本、日志、截图、测试 APK）
- `.DS_Store`、`app/.DS_Store`
- Firebase 服务账号 JSON、签名密钥、token
- 任何 `amoobridge-*.apk` 测试包

## 8. 已知限制（不要重复踩）

1. **`adb install` 不可用**：只能用文件管理器手动安装。
2. **原生 GMS 收不了 FCM**：Android 16 后台服务限制，侧载 GMS 无解。
3. **GBox 克隆是唯一 FCM 运行时**：用 19.0.0 构建，不要被 24.1.2 覆盖。
4. **手机 VPN 必须开**：否则 GCM 注册、Firebase Installations、MCS 全部失败。
5. **StepOS 后台冻结分两种**：
   - GBox 克隆（uid 10193）：3–5 秒后冻结，recents 锁也无效，只能 FCM 唤醒；
   - 原生 + 无障碍（uid 10202）：无障碍服务被系统绑定，实测退后台 3 分钟不冻结。
6. **原生与克隆不能同时跑**：共用 8896；本地 agent 用原生，远程 FCM 才切 GBox。
7. **重装后权限会掉**：先补 `MANAGE_EXTERNAL_STORAGE`，否则 401。
8. **后台不能直接启动 Activity**：`start_app` / `open_url` 在桥处于后台时会被 StepOS 拦截：
   `CommonInterception ... Intercept = 9 | Reason = Caller process is background`。
   前台服务、FCM 唤醒、`SYSTEM_ALERT_WINDOW`（已实测）都不能绕过。
   **改用无障碍点击桌面图标**：
   `ui_dump` 找到 `美团` 的 bounds，再 `ui_click {"text":"美团"}`，
   由 launcher 自己启动应用，不受该拦截影响。
   `start_app` 返回 `{"ok":true}` 只代表 `startActivity` 没抛异常，不代表界面真的起来了。
9. **手机 agent 的 UI 操作走 `ui_*` 工具**：本地 agent 不需要 FCM；
   打开应用用 `ui_click` 点桌面图标，点击/滑动用 `ui_tap` / `ui_swipe`，
   看界面用 `ui_dump` / `shot`。前提是无障碍服务已开启（见第 3 节）。
