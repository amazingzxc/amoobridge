# 项目开发规范

本项目是一个纯原生 Android 桥：HTTP / 文件信箱 + 无障碍 UI 自动化。
**不包含 FCM、GBox、Google 服务依赖。**

## 0. 关键事实

| 项目 | 值 |
|---|---|
| 包名 | `com.stepx.amoobridge` |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 桥目录 | `/sdcard/bridge-amoobridge` |
| HTTP 端口 | `8896` |
| 令牌 | `/sdcard/bridge-amoobridge/token.txt` |
| 无障碍服务 | `com.stepx.amoobridge/.BridgeAccessibilityService` |
| UI 状态 | `/sdcard/bridge-amoobridge/ui_dump.json` |
| 截图 | `/sdcard/bridge-amoobridge/shot_<ts>.png` |

测试机：StepX PZ261A，Android 16（SDK 36），StepOS。

## 1. 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

成功标志：`BUILD SUCCESSFUL`，生成 30–40KB 左右的纯原生 APK。
构建失败不要继续安装。

本项目没有 `google-services` 插件、没有 `firebase-messaging`、
没有 `google-services.json`；不要重新引入。

## 2. 安装到手机

**不要用 `./gradlew installDebug` 或 `adb install`。** 这台 StepOS 设备会返回：

```
INSTALL_FAILED_USER_RESTRICTED
```

正确流程：

1. 推送：

   ```bash
   adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/amoobridge.apk
   ```

2. 手机打开 **文件管理 → 下载**，点 APK；
3. 「未备案应用」→ **仍要安装**；
4. 「安装成功」→ **完成**。

安装后检查：

```bash
adb shell pm path com.stepx.amoobridge
adb shell dumpsys package com.stepx.amoobridge | grep lastUpdateTime
```

### 重装后的权限

重装后 `MANAGE_EXTERNAL_STORAGE` 可能被重置，表现为 401 / `status.json` 不更新：

```bash
adb shell appops set com.stepx.amoobridge MANAGE_EXTERNAL_STORAGE allow
```

或点应用里的「① 授予所有文件访问」。

## 3. 开启无障碍（必须）

无障碍是本项目的核心：UI 自动化 + 后台保活都依赖它。

```bash
adb shell settings put secure enabled_accessibility_services \
  com.stepx.amoobridge/com.stepx.amoobridge.BridgeAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep -i stepx
```

也可以点应用里的「② 开启无障碍（UI 自动化）」，到系统设置里手动开启。

验证已连接：

```bash
adb logcat -d | grep BridgeA11y
# I BridgeA11y: accessibility service connected
```

**没有开启无障碍时，`ui_*` / `shot` 会返回
`accessibility service not enabled`。**

## 4. 启动与冒烟测试

```bash
adb shell am force-stop com.stepx.amoobridge
adb shell am start -n com.stepx.amoobridge/.MainActivity
sleep 3
adb shell pidof com.stepx.amoobridge

adb forward tcp:18896 tcp:8896
TOKEN=$(adb shell cat /sdcard/bridge-amoobridge/token.txt | tr -d '\r')
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/ping
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/status
adb shell cat /sdcard/bridge-amoobridge/status.json
adb logcat -d -b crash | grep -E 'com.stepx.amoobridge|FATAL EXCEPTION'
```

通过标准：

- 进程存在；
- `/ping`、`/status` 返回 200；
- `status.json` 心跳在 5 秒内；
- crash 缓冲没有 `com.stepx.amoobridge` 的 FATAL。

## 5. 后台保活验证

开启无障碍后，系统会长期绑定 `BridgeAccessibilityService`，StepOS 不会冻结原生 uid。
实测退后台 3 分钟仍可用。

```bash
adb shell input keyevent KEYCODE_HOME
sleep 60
curl -s -m 5 -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/ping
adb logcat -d | grep -E 'StepOSFreeze.*com.stepx.amoobridge' | tail
```

预期：60 秒后仍返回 200，日志里没有 `FREEZE ... freezeValue=1`。
**不要在没有无障碍的情况下宣称后台可用。**

## 6. UI 自动化工具

通过 `POST http://127.0.0.1:8896/call` 或文件信箱调用：

| 工具 | 参数 | 说明 |
|---|---|---|
| `ui_dump` | `{}` | 当前界面写入 `ui_dump.json`，返回节点数/包名 |
| `ui_click` | `{"text":"外卖"}` | 按文本/描述查找并点击 |
| `ui_tap` | `{"x":540,"y":1200}` | 坐标点击 |
| `ui_swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":400}` | 滑动 |
| `shot` | `{}` | 截图到 `shot_<ts>.png` |
| `ui_back` / `ui_home` / `ui_recents` | `{}` | 全局返回/主页/最近任务 |

示例：

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tool":"ui_dump","args":{}}' http://127.0.0.1:18896/call

curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tool":"ui_click","args":{"text":"外卖"}}' http://127.0.0.1:18896/call

curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tool":"ui_tap","args":{"x":1066,"y":466}}' http://127.0.0.1:18896/call
```

已验证：`ui_click 外卖` → 进入 `TakeoutActivity`；
`ui_tap` 搜索框 → `GlobalSearchActivity`；`shot` 生成可读截图。

## 7. 打开应用的方式

`start_app` / `open_url` 在桥处于后台时会被 StepOS 拦截：

```
CommonInterception ... Intercept = 9 | Reason = Caller process is background
```

`start_app` 返回 `{"ok":true}` 只代表 `startActivity` 没抛异常，不代表界面真的起来了。

**正确做法**：用无障碍点桌面图标。

1. `ui_home`
2. `ui_dump`，找到目标应用的 `text`（例如 `美团`）
3. `ui_click {"text":"美团"}`

由 launcher 自己启动应用，不受后台 Activity 启动限制。

## 8. 验收清单

- [ ] `./gradlew assembleDebug` 成功；
- [ ] APK 已安装，`lastUpdateTime` 正确；
- [ ] `MANAGE_EXTERNAL_STORAGE` allow；
- [ ] 无障碍服务 connected；
- [ ] `/ping`、`/status` 200，心跳正常；
- [ ] 退后台 60 秒后 `/ping` 仍 200；
- [ ] `ui_dump` 能拿到当前界面节点；
- [ ] `ui_click` / `ui_tap` / `shot` 至少各验证一次；
- [ ] crash 缓冲没有本应用 FATAL；
- [ ] [HANDOFF.md](HANDOFF.md) 已更新。

## 9. 不要提交

- `work/`（实验脚本、日志、截图、测试 APK）
- `.DS_Store`、`app/.DS_Store`
- 签名密钥、token、任何测试 APK
- 不要把 FCM / GBox / Google 服务依赖重新引入（历史实现保留在
  `codex/current-changes` 分支，当前分支保持纯净）。

## 10. 已知限制

1. **`adb install` 不可用**：只能用文件管理器手动安装。
2. **后台启动 Activity 被拦截**：用 `ui_click` 点桌面图标，不要依赖 `start_app`。
3. **无障碍必须开启**：否则 UI 工具不可用，且后台会被 StepOS 冻结。
4. **重装后权限会掉**：先补 `MANAGE_EXTERNAL_STORAGE`。
5. **屏幕锁定/息屏** 下的行为未验证，涉及锁屏任务时先实测。
