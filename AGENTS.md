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

成功标志：`BUILD SUCCESSFUL`，生成约 90KB 的纯原生 APK（加入 PiP 后变大）。
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

无障碍绑定能让系统长期连接本应用，但**不能阻止 StepOS 冻结整个 uid**。
StepOS 在后台把 uid 写进 cgroup（`/sys/fs/cgroup/apps/uid_<uid>/cgroup.freeze=1`），
冻结期间进程里所有线程都不运行，`/ping` 和 UI 调用会一起卡住，直到
system_server 有一个 binder 调用把它异步解冻，约 3 秒后再冻结。

`KeepHot` 负责在被使用时把进程"焐热"：每 2 秒 `startForegroundService`
一次（StepOS 记为 `FLAG_USING_SERVICE` → `BLOCK_FREEZE`），窗口默认 30 秒。
任何 HTTP 请求都会重新开窗。

```bash
# 看当前状态：hot / always_hot / freeze_gaps
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18896/status
adb shell cat /sys/fs/cgroup/apps/uid_$(adb shell dumpsys package com.stepx.amoobridge | grep -m1 userId= | tr -dc '0-9')/cgroup.freeze

# 长任务前打开 always-hot（结束后关掉）
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tool":"keep_hot","args":{"on":true}}' http://127.0.0.1:18896/call
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tool":"keep_hot","args":{"on":false}}' http://127.0.0.1:18896/call
```

`freeze_gaps` 是"进程真的被冻住过"的计数（keepalive 线程检测到 >6 秒空档）。
always-hot 下它应长期保持 0；如果一直涨，说明保活失效，退回上面的冷启动预期。

**冷启动的代价（必要认知）：** 超过 30 秒没有请求后窗口关闭，进程会再次被冻结；
**下一个**请求仍要等解冻（可长达几十秒），之后又是 10–30ms。这是 StepOS 行为，
不是桥的 bug。需要每次都快的场景请开 always-hot。

**息屏 / Doze 的限制（已实测）：** 关屏后约 30 秒，`/ping` 会超时，
`status.json` 停止更新；亮屏后恢复 200。
电池优化白名单和无障碍绑定都不能阻止 StepOS 在息屏时冻结 uid。
本地 agent 使用时保持亮屏；息屏/远程场景需要额外的唤醒通道（当前未实现）。

## 6. UI 自动化工具

通过 `POST http://127.0.0.1:8896/call` 或文件信箱调用：

| 工具 | 参数 | 说明 |
|---|---|---|
| `ui_dump` | `{}`，可选 `budget`/`max`/`package`/`window_id`/`pip` | 当前/目标窗口写入 `ui_dump.json`，返回节点数/包名/耗时/窗口信息 |
| `ui_click` | `{"text":"外卖"}`，可选 `budget`/目标选择器 | 按文本/描述查找并点击；优先复用上次 dump 的节点 |
| `ui_tap` | `{"x":540,"y":1200}`，可选目标选择器 | 坐标点击；带选择器时校验目标 bounds |
| `ui_swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":400}`，可选目标选择器 / `hold` | 滑动；带选择器时两端都要在目标 bounds 内；`hold` 毫秒在终点停顿 |
| `ui_windows` | `{}` | 列出可交互窗口（PiP/多窗口选择用） |
| `pip_target` | `{"package":"...","action":"inspect|select|clear|recover|apps"}` | PiP 会话与目标发现 |
| `open_small_window` | `{"package":"..."}`，可选 `menu_x`/`menu_y` | 优先 StepOS mode 11（`stepos-mini-window`）原生启动；失败回退手势/卡片菜单 |
| `start_small_window` | `{"package":"..."}` | 低层诊断：直接用 mode 11 启动并返回窗口 |
| `shot` | `{}` | 截图到 `shot_<ts>.png` |
| `ui_back` / `ui_home` / `ui_recents` | `{}` | 全局返回/主页/最近任务 |
| `keep_hot` | `{"on":true}` 或 `{"window":60000}` | 常驻保活 / 延长热窗口 |

`ui_dump` 的返回里有 `root_ms` / `walk_ms` / `write_ms` / `elapsed_ms` /
`partial`；`ui_click` 有 `method`（`cache+node` / `cache+gesture` / `node+node`…）
和 `elapsed_ms`。遍历有墙钟预算（默认 2000ms），超时会返回
`partial:true` 而不是把调用卡到几十秒。

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

### 6.1 PiP 目标窗口

目标 App 必须自己声明 `supportsPictureInPicture` 并自己调用
`enterPictureInPictureMode()`；桥不能强制第三方 App 进 PiP。

```bash
# 发现本机支持 PiP 的 App
curl ... -d '{"tool":"pip_target","args":{"action":"apps"}}'
# 目标已在 PiP 后：检查 + 记住会话
curl ... -d '{"tool":"pip_target","args":{"package":"com.example.target","action":"inspect"}}'
curl ... -d '{"tool":"pip_target","args":{"package":"com.example.target","action":"select"}}'
# 只读目标 PiP 的节点
curl ... -d '{"tool":"ui_dump","args":{"pip":true}}'
# 目标 PiP 内的文本点击（仍建议随后 dump 验证）
curl ... -d '{"tool":"ui_click","args":{"pip":true,"text":"确定"}}'
# 列出所有窗口（window_id/bounds/layer/pip_candidate）
curl ... -d '{"tool":"ui_windows","args":{}}'
```

选择器优先级：`window_id` > `package`+`pip` > `package` > active window
（仅无参数旧调用）。`pip=true` 不带 `package` 时用 `select` 的会话。

**实测限制（StepX PZ261A / Android 16 / StepOS）：** PiP 窗口内容收不到
点击/手势（`dispatchGesture` 会被 PiP 输入层吃掉，滑动会移动窗口；
`adb input tap` 也进不到 App；节点 `isClickable` 被系统报成 false，
`ACTION_CLICK` 失败）。因此：

- **读/监控/坐标校验可以用 PiP**；
- **写操作不要只看 `ok=true`**：PiP 手势写入会返回 `input_verified:false` + `warning`，
  必须用下一次 `ui_dump` 或 `shot` 验证；
- 要真正操作目标，按计划降级：展开 PiP 为全屏，然后用**不带** `pip` 的
  `ui_dump`/`ui_click`。`pip_target inspect` 这时会返回 `target_not_in_pip`。

PiP 菜单是独立的 `com.android.systemui` 窗口；菜单开着时写操作返回
`pip_controls_visible`，先 `ui_back` 或点外部关掉再重试。

### 6.2 StepOS 原生小窗（windowingMode 11）

StepOS 的窗口模式在 framework 里是：

- `stepos-mini-window` = **11**（`WindowConfiguration.isStepOSMiniWindow(int)`）；
- `stepos-pinned-window` = **12**（旧的“迷你小窗”手势入口）；
- `stepos-magic-windows` = **13**。

`open_small_window` 现在**优先走原生 mode 11**：

```bash
curl ... -d '{"tool":"open_small_window","args":{"package":"com.sankuai.meituan"}}'
curl ... -d '{"tool":"start_small_window","args":{"package":"com.sankuai.meituan"}}'
```

成功返回 `method=windowing_mode_11` + `window_id` + `bounds`，约 **1.5s**。
实现是 `getLaunchIntentForPackage()` + `startActivity(intent, bundle)`，bundle 里带
隐藏 key **`android.activity.windowingMode=11`**（不是 `android:activity.launchWindowingMode`）。
本机实测桥 UID、桥 Activity 在后台都能启动；系统日志
`setWindowingMode ... fullscreen to stepos-mini-window`、`windowingMode=11`、`BAL_ALLOW_TOKEN`。

- 不要求目标在前台，不需要先启动；目标已有小窗返回 `already_open:true`；
- `start_small_window` 是低层诊断工具（只做原生启动并返回窗口）；
- 原生失败（应用忽略模式 / 没 launch intent / ROM 变化）会回退到旧路径：
  两条 hold 手势 + 最近任务卡片菜单，返回 `method=stepos_gesture_menu`，
  产生 `stepos-pinned-window`（约 5s、336×652）；**只有 fallback 才要求目标前台全屏**；
- 实测：Settings（桥后台）960×1863 / 1538ms；美团（未启动）832×1082 / 1530ms；
  重复调用 10ms。

注意：

- mode 11 和这个 Bundle key 都是 StepOS 隐藏实现，ROM 升级可能变；
- 目标必须有 launcher intent；某些 App 可能忽略该模式；
- 美团等 App 可能弹验证码/登录/风控（实测 force-stop 重开出现过拼图验证）；
  必须停下让用户处理，不要绕过；
- 对小窗用 `package`/`window_id`，**不要用 `pip:true`**；`pip_candidate` 会把 StepOS 小窗
  误标成 PiP，手势写入可能带 `input_verified:false` 的误报。

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
- [ ] 开 `keep_hot {"on":true}` 后，`ui_dump` 的 `elapsed_ms` < 200ms；
- [ ] `ui_dump` 能拿到当前界面节点；
- [ ] `ui_click` / `ui_tap` / `shot` 至少各验证一次；
- [ ] `ui_windows` 能列出窗口，PiP 窗口 `pip_candidate=true`；
- [ ] `pip_target inspect/select` 正常，`ui_dump {"pip":true}` 只返回目标节点；
- [ ] PiP 写操作后 `input_verified` / 下一次 `ui_dump` 已确认（本机 PiP 不收触摸）；
- [ ] `open_small_window` 能用 mode 11 把未启动的 App 起成 `stepos-mini-window`（桥后台也可），重复调用返回 `already_open`；
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
5. **息屏 / Doze 会冻结**：关屏约 30 秒后 8896 超时，亮屏恢复；
   电池优化白名单 + 无障碍也无效。本地 agent 请保持亮屏。
6. **StepOS 整体冻结 uid**：这是 25–40 秒 UI 延迟的根因，与节点遍历无关。
   `KeepHot` 只在"被使用时"焐热进程，冷启动的第一个请求仍可能等几十秒；
   要每次都快就开 `keep_hot {"on":true}`（代价是持续的 2 秒一次唤醒）。
7. **`SCHEDULE_EXACT_ALARM` 默认没授权**（targetSdk 34 侧载），
   watchdog 走 `setAndAllowWhileIdle`；需要精确闹钟时手动
   `adb shell appops set com.stepx.amoobridge SCHEDULE_EXACT_ALARM allow`。
8. **Android PiP 内容只读**；需要可交互小窗时用 StepOS 的 `open_small_window`
   （优先隐藏 mode 11，仅 StepOS、ROM 可能变；失败回退手势，回退路径要求目标前台全屏）。
