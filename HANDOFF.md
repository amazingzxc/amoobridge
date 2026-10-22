# StepBridge Handoff

更新时间：2026-10-22 18:24

## 当前状态

- 分支：`codex/pip-windows`
- 远程仓库：`https://github.com/amazingzxc/amoobridge.git`
- 应用包名：`com.stepx.amoobridge`
- 源码：`stepx-amoo-bridge/`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`（约 92KB，versionName 1.3，36 个工具）
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
  ├─ KeepHot（防 StepOS 冻结，见下）
  └─ BridgeAccessibilityService（系统长期绑定）
        ├─ ui_tap / ui_swipe（含 hold）/ ui_click
        ├─ ui_dump / ui_windows / shot
        ├─ pip_target（Android PiP 会话）
        ├─ open_small_window / start_small_window（StepOS 小窗）
        └─ ui_back / ui_home / ui_recents
```

没有 FCM 唤醒、没有 GBox 容器、没有 VPN 依赖。

## UI 延迟问题：根因已定位（2026-10-22）

### 结论：不是节点遍历慢，是 StepOS 把整个进程冻住了

之前的推测（"每次 getChild 都夹着冻结/解冻"）方向对，但机制不是这样。
实测数据（同一台机器，同一份 APK）：

| 场景 | 耗时 |
|---|---|
| `ui_dump`，进程是热的 | **7–20ms**（53–215 节点） |
| `ui_dump`，进程被冻住 | **25.8 / 28.6 / 29.9 / 30.0 / 34.1 / 41.5 秒** |
| `ui_click`，进程被冻住 | ~65 秒（= 两次"等解冻"） |
| `exec`（完全不用无障碍） | 65ms（热）/ 同样会被冻 |
| `/ping`（不碰无障碍） | 4ms（热）/ 7.8–56 秒（冻） |

也就是说 **遍历本身只要 10–17ms**，25–40 秒全是"等进程被解冻"。

证据链：

1. `adb shell cat /sys/fs/cgroup/apps/uid_10203/cgroup.freeze` = **1**，长期为 1；
   而 `pid_7202/cgroup.freeze` = 0 —— StepOS 冻的是 **uid 级 cgroup**，
   不是 AOSP 的 per-pid freezer。
2. `status.json`（每 2 秒写一次）在 `/ping` 卡住的 23 秒里**时间戳完全不动**，
   证明整个进程的线程都没在跑，不是某个请求线程被阻塞。
3. `dumpsys activity processes` 里 `isFrozen=false`——
   StepOS 不走 ActivityManager 的冻结接口，所以这个字段一直是 false，
   这也是这个坑一直没被发现的原因。
4. 解冻只能靠 **system_server 主动 binder 调用**：
   `StepOSFreeze[Logging]: [UNFREEZE] ... reason=BINDER_CALL_UNFREEZE(1)
   detail=unfreeze due to proc system binder calls with class
   android.accessibilityservice.IAccessibilityServiceClient code 2
   perceivableUnfreeze false`，然后 3 秒后 `[FREEZE]`。
   `perceivableUnfreeze=false` 表示这是**异步解冻**，所以一等就是几十秒。
5. 对照实验：用 `am broadcast` 每 1.2 秒敲一下再 `startForegroundService`，
   连续 10 次 `ui_dump` 全部 **11–20ms**；停掉后立刻回到 16.5 秒。

补充：`SCHEDULE_EXACT_ALARM` 在 targetSdk 34 侧载时 appop 是 default（未授权），
所以旧的 60 秒 watchdog 走的是 `setInexactRepeating`，实际上几乎不触发，
等于没有兜底。

### 修复：KeepHot（自适应热窗口 + 可常驻）

`KeepHot.java` 在"被使用"时把进程焐热：

- **热窗口**：任何 HTTP 请求（含 `/ping`）都会把 `hotUntil` 推到 +30 秒，
  期间每 **2 秒** `startForegroundService(BridgeService)` 一次。
  StepOS 把它记为服务使用并直接取消待执行的冻结：
  `[BLOCK_FREEZE] outcome=cancel_pending detail=add flag: FLAG_USING_SERVICE
  service com.stepx.amoobridge/.BridgeService perceivableUnfreeze true`。
- **兜底闹钟**：热窗口内每 12 秒排一个自唤醒广播（`KeepaliveReceiver`）。
  闹钟由 system_server 投递，本身就是一次能让冻结进程解冻的 binder 调用；
  如果保活线程被冻住，它负责把进程救回来。窗口关闭后这条链**不会**自我续命。
- **always-hot**：`keep_hot {"on":true}` 常驻，适合无人值守的长任务；
  `{"on":false}` 关闭；`{"window":60000}` 只延长当前窗口。
- **观测**：`freeze_gaps` 计数（keepalive 线程检测到 >6 秒空档 = 真被冻过），
  以及 `/status`、`status.json`、每个 `/call` 返回里的 `hot` / `always_hot`。

同时（不是根因，但都是真问题）：

- `ui_dump` / `ui_click` 的遍历改成**墙钟预算**（默认 2000ms），
  超时返回 `partial:true`，不再可能把一次调用拖到几十秒。
- `ui_click` 优先复用上一次 `ui_dump` 收集的节点（`refresh()` 校验 + 窗口 id
  比对），命中时 `method` 是 `cache+node` / `cache+gesture`，省掉整棵树重走。
- 返回里加了 `root_ms` / `walk_ms` / `write_ms` / `elapsed_ms` / `partial`。
- `WatchdogReceiver` 改用 `setAndAllowWhileIdle`（不再走几乎不触发的
  `setInexactRepeating`）；精确闹钟需要手动授权：
  `adb shell appops set com.stepx.amoobridge SCHEDULE_EXACT_ALARM allow`。
- `promoteToForeground()` 复用同一个 Notification 实例，避免保活每 2 秒
  重建通知造成通知栏抖动。

### 实测（versionName 1.3，同一台机器）

| 场景 | 修复前 | 修复后 |
|---|---|---|
| `ui_dump` 热 | 7–20ms | 13–31ms（含 `write_ms`） |
| `ui_dump` 空闲 45 秒后，always-hot | 25–41s | **32 / 14.5 / 13.7ms** |
| `ui_dump` always-hot 连续空闲 5 分钟 | 25–41s | **27.6 / 7.4 / 8.6ms**，`freeze=0`、`freeze_gaps=0` |
| `ui_dump` 空闲 6 分钟后，adaptive | 25–41s | 20.8ms（该次本身没进入冻结状态） |
| `ui_click`（dump 之后，走缓存） | ~65s | **23.7ms**，`method=cache+node` |
| `ui_click`（Settings "搜索"） | ~65s | 成功进入搜索页，68 节点 |
| `shot` | — | 成功，1.1MB 可读截图 |
| `cgroup.freeze`（always-hot，空闲 5 分钟） | 1 | **0** |
| CPU（always-hot / 空闲） | — | 0.98% / 0.90% 单核（见下） |

CPU 是 `ro.variable` 无关的实测：`/proc/<pid>/stat` 的 utime+stime 差分。

- always-hot，300 秒空闲：295 ticks ≈ 2.95 秒 CPU ≈ **0.98%** 单核；
- 关闭 always-hot 后的空闲基线，120 秒：109 ticks ≈ 1.09 秒 ≈ **0.90%** 单核。

也就是保活本身的增量约 **0.08% 单核**（基线那 0.9% 主要是 `status.json`
每 2 秒 `fsync` 一次，属于原有行为）。所以 always-hot 的代价基本可以忽略，
真正贵的是唤醒次数对续航的间接影响，长任务用完建议关掉。

对照组（旧版 0.4.1 之前的行为）仍成立：**冷启动**（长时间没有任何请求、
进程已经冻死）的第一个请求还是要等解冻，可能几十秒。这是 StepOS 的策略，
应用侧改不了，只能靠 always-hot 规避。

## 后台保活（已更新）

无障碍绑定能让系统长期连接本应用，但**不能阻止 StepOS 冻结 uid**。
保活现在由 `KeepHot` 负责，详见上一节。

**息屏 / Doze 的限制（已实测，未改变）：** 关屏约 30 秒后 `/ping` 会超时，
`status.json` 停止更新；亮屏后恢复 200。电池优化白名单和无障碍绑定都不能
阻止 StepOS 在息屏时冻结 uid。本地 agent 使用时保持亮屏。
注意：息屏时 keepalive 也会一起被冻住，所以 always-hot 不能替代亮屏。

## UI 工具（已验证）

通过 `POST /call` 或文件信箱调用：

| 工具 | 说明 |
|---|---|
| `ui_dump` | 当前/目标窗口写入 `/sdcard/bridge-amoobridge/ui_dump.json`；可选 `budget`/`max`/`package`/`window_id`/`pip` |
| `ui_click {"text":"外卖"}` | 按文本/描述点击；优先复用上次 dump 的节点；可带目标选择器 |
| `ui_tap {"x":..,"y":..}` | 坐标点击；带选择器时校验目标 bounds |
| `ui_swipe {"x1":..,"y1":..,"x2":..,"y2":..,"duration":..}` | 滑动；带选择器时两端都要在目标 bounds 内；可选 `hold`（毫秒，末尾停顿） |
| `ui_windows` | 列出所有可交互窗口（PiP/多窗口选择用） |
| `pip_target` | PiP 会话：`inspect` / `select` / `clear` / `recover` / `apps` |
| `open_small_window {"package":"..."}` | 优先用 StepOS `stepos-mini-window`（windowingMode 11）打开 App；失败回退到手势/卡片菜单 |
| `start_small_window {"package":"..."}` | 低层诊断：直接用 mode 11 启动并返回窗口信息 |
| `shot` | 截图到 `shot_<ts>.png` |
| `ui_back` / `ui_home` / `ui_recents` | 全局动作 |
| `keep_hot {"on":true}` | 常驻保活；`{"window":60000}` 延长热窗口 |

`ui_dump` 返回示例（1.3）：

```json
{"ok":true,"count":106,"package":"com.android.settings","partial":false,
 "root_ms":4,"walk_ms":27,"write_ms":1,"elapsed_ms":31,
 "hot":true,"always_hot":false,"hot_left_ms":29968,"freeze_gaps":0}
```

实测（versionName 1.3）：

- Settings：`ui_dump` 106 节点 / 31ms；`ui_click 搜索` 23.7ms
  （`method=cache+node`）→ 进入搜索页；
- launcher（Amoo AI 首屏）：`ui_dump` 38 节点 / 15–23ms；
- `shot` 生成 1.1MB 可读截图；
- 桌面图标点击（`ui_click 美团`）在 AI 首屏上找不到目标属正常，
  需要先把 launcher 切到应用列表页。

## PiP 自动化（阶段 A–C，2026-10-22）

### 工具与选择器

```json
{"tool":"ui_windows","args":{}}
{"tool":"pip_target","args":{"package":"com.example.target","action":"inspect"}}
{"tool":"pip_target","args":{"package":"com.example.target","action":"select"}}
{"tool":"ui_dump","args":{"package":"com.example.target","pip":true}}
{"tool":"ui_click","args":{"package":"com.example.target","pip":true,"text":"确定"}}
{"tool":"ui_tap","args":{"package":"com.example.target","pip":true,"x":900,"y":2200}}
```

选择器优先级：显式 `window_id` > `package`+`pip` > `package` > 当前 active window
（仅无参数旧调用）。`pip=true` 不带 `package` 时使用 `pip_target select` 的会话。

会话只保存在内存里：桥进程重启/被系统杀死后 `pip_target` 需要重新 `select`；
`pip_target apps` 的结果缓存 5 分钟。

`ui_windows` 每个窗口返回：`window_id` / `package` / `type` / `type_name` / `bounds` /
`active` / `focused` / `layer` / `pip_candidate` / `has_root`；顶层还有
`screen`、`active_window_id`、`pip_window_ids`、`session`。
`pip_candidate` 的判定是“应用窗口且不占满屏幕”的启发式（Android 不暴露 PiP 状态）。

`ui_dump.json` 顶层现在带 `window_id` / `bounds` / `selection` / `pip_candidate` /
`pip_controls_visible`；`ui_click`/`ui_tap`/`ui_swipe` 返回里也带这些字段和 `elapsed_ms`。

### 错误码

| `error` | 含义 |
|---|---|
| `target_window_not_found` | 包没有可交互窗口，或 `window_id` 已失效 |
| `target_not_in_pip` | 包有窗口但是全屏；改用不带 `pip` 的调用 |
| `package_window_not_pip` | 显式 `window_id` 不是 PiP 尺寸窗口 |
| `package_multiple_windows` / `package_multiple_pip_windows` | 同包多窗口，需要 `window_id` 消歧 |
| `pip_controls_visible` | SystemUI PiP 菜单盖住目标；先 `ui_back` 或点外部关掉 |
| `outside_target_bounds` | 坐标在目标窗口外；PiP 移动后旧坐标会被这样拒绝 |
| `no_pip_session` | `pip=true` 但既没有 `package` 也没有 `select` 过 |
| `target_window_no_root` | 目标窗口没有无障碍 root |

### 实测（StepX PZ261A / Android 16 / StepOS）

用 `work/piptest`（临时测试 App，验证后已卸载）做目标：它自己声明
`supportsPictureInPicture` 并调用 `enterPictureInPictureMode()`，PiP 窗口约 514×514。

已验证：

- `ui_windows` 稳定发现 PiP 窗口（`package`、`bounds`、`layer`、`pip_candidate=true`）；
  窗口默认 `active=false/focused=false`，与“PiP 默认不拿输入焦点”一致。
- `pip_target inspect/select/recover/clear` 正常；`apps` 能列出本机 9 个声明 PiP 的 App。
- `ui_dump(package, pip=true)` 只返回目标 PiP 的节点（11 个），不会读到下层 App
  （实测下层是文件管理/桌面时仍然只拿到 `com.stepx.piptest`）。
- PiP 移动后旧 bounds 立即失效，旧坐标返回 `outside_target_bounds`。
- SystemUI PiP 菜单是独立的 `com.android.systemui` 窗口（layer 在目标之上），
  `pip_controls_visible` 能识别；此时 `ui_click`/`ui_tap` 返回该错误，不误报成功。
- 无参数 `ui_dump` / `ui_click` 旧行为不变。
- PiP 展开为全屏后 `pip_target inspect` 返回 `target_not_in_pip`；
  改用不带 `pip` 的 `ui_dump`/`ui_click` 正常，节点 `ACTION_CLICK` 生效
  （实测状态变成 `CLICKED_ONE`）。

### 关键限制：PiP 内容不接收点击/手势（本机）

三个独立证据：

1. `ui_tap` / `ui_swipe` 注入到 PiP 的手势被 PiP 输入消费层吃掉：
   滑动会移动 PiP 窗口（bounds 变化），但目标 App 的 `OnTouchListener` 收不到任何事件；
2. `adb shell input tap` 直接点 PiP 内容同样进不到 App；第一次点通常只弹出 SystemUI 菜单；
3. PiP 窗口子节点的 `isClickable()` 全是 false（根节点反而是 true），
   `performAction(ACTION_CLICK)` 返回 false，所以 `ui_click` 只能退回手势。

结论：**PiP 适合“读 + 监控 + 坐标校验”，不适合直接写**。
PiP 上的手势写入会返回 `input_verified:false` 和 `warning`；agent 必须用后续
`ui_dump`/`shot` 确认，不能只看 `ok=true`。需要真正操作目标时按计划降级：
把 PiP 展开为全屏，用不带 `pip` 的 `ui_dump`/`ui_click`，或改全屏自动化。
这是系统输入路由，不是桥能修复的；`AccessibilityService` 没有“让 PiP 可交互”的 API。

### 安装/测试自动化（未入库）

`adb install` 仍返回 `INSTALL_FAILED_USER_RESTRICTED`。本次用 `work/install_apk.py`
自动走文件管理器流程：push 到 `/sdcard/Download` → 文件管理「安装包」→ 点 APK
→「仍要安装」→「完成」，用 `uiautomator dump` + `input tap` 定位按钮。
`work/` 已被 gitignore，脚本只作本地工具保留。

## StepOS 原生小窗：windowingMode 11 / `open_small_window`（2026-10-22）

StepOS 有两套和 Android PiP 不同的窗口模式：

- `stepos-mini-window`：framework 里 **windowingMode 11**
  （`WindowConfiguration.isStepOSMiniWindow(int)` 就是判断 11）；
- `stepos-pinned-window`：**windowingMode 12**（旧的“迷你小窗”手势入口）；
- `stepos-magic-windows`：13。

### 工具

```json
{"tool":"open_small_window","args":{"package":"com.sankuai.meituan"}}
{"tool":"start_small_window","args":{"package":"com.sankuai.meituan"}}
```

`open_small_window` 现在优先走原生 mode 11；`start_small_window` 是低层诊断工具
（只做原生启动并返回窗口信息）。

成功返回（原生路径）：

```json
{"ok":true,"package":"com.sankuai.meituan","method":"windowing_mode_11",
 "window_id":1135,"bounds":[184,771,1016,1853],"elapsed_ms":1530}
```

- 原生路径**不要求目标在前台，也不需要先启动**：桥直接取
  `getLaunchIntentForPackage()`，在 `startActivity` 的 Bundle 里带隐藏 key
  **`android.activity.windowingMode=11`**（这是 `ActivityOptions.KEY_LAUNCH_WINDOWING_MODE`
  的真实值，不是 `android:activity.launchWindowingMode`）。
- 实测桥 UID、桥 Activity 在后台都能启动；系统日志：
  `setWindowingMode ... fullscreen to stepos-mini-window`、`windowingMode=11`、
  `BAL_ALLOW_TOKEN`。
- 目标已有小窗 → `already_open:true`（约 10ms）。
- 原生失败（应用忽略模式 / 没有 launcher intent / ROM 变化）→ 回退到两条 hold 手势 +
  最近任务卡片菜单，返回 `method:"stepos_gesture_menu"`；**只有 fallback 才要求目标前台全屏**。

### 实测（本机）

- Settings（桥在后台）：`ok:true`，`method=windowing_mode_11`，bounds 960×1863，
  task `mode=stepos-mini-window`，1538ms；
- 美团（完全没启动）：`ok:true`，bounds 832×1082，task `mode=stepos-mini-window`，1530ms；
- 重复调用：`already_open:true`，10ms；
- 美团 force-stop 后重开曾出现拼图验证（“用最短线连接紫色/橙色”），这是美团风控，
  与小窗模式无关；桥没有绕过，需用户手动完成。
- 旧的 `stepos-pinned-window` 手势路径仍可用：约 5s，336×652，作为 fallback。

### 回退：hold 手势 + 卡片菜单

`ui_swipe` 的 `hold` 参数（路径末尾 ±1px 微振荡）仍保留，用于：

```bash
# 底部中间上滑半屏 + 停顿 -> 最近任务
{"tool":"ui_swipe","args":{"x1":600,"y1":2640,"x2":600,"y2":1350,"duration":1500,"hold":700}}
# 卡片下滑 1/4 + 停顿 -> 卡片菜单
{"tool":"ui_swipe","args":{"x1":600,"y1":700,"x2":600,"y2":1367,"duration":1200,"hold":700}}
```

然后点「迷你小窗」（默认 39.4% 宽、33.7% 高，可用 `menu_x`/`menu_y` 覆盖）。
该路径产生 `stepos-pinned-window`，目标必须前台全屏。

### 限制

- `android.activity.windowingMode` 和 mode 11 都是 StepOS 隐藏实现，ROM 升级可能变；
- 目标必须有 launcher intent；某些应用可能忽略该模式，会回退；
- 原生启动依赖 StepOS 的 `BAL_ALLOW_TOKEN` 行为；本机后台实测可用，但不保证所有 StepOS 版本；
- 美团等 App 可能弹验证码/登录/风控；必须停下让用户处理；
- 对小窗用 `package`/`window_id`，不要用 `pip:true`；`pip_candidate` 会把 StepOS 小窗
  误标成 PiP；手势写入仍可能带 `input_verified:false` 的误报。

## 打开应用的注意事项

`start_app` / `open_url` 在桥处于后台时会被 StepOS 拦截：

```
CommonInterception ... Intercept = 9 | Reason = Caller process is background
```

`start_app` 返回 `{"ok":true}` 不等于界面真的起来了。

**正确方式**：用无障碍点桌面图标。

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

本机 `adb install` / `pm install` 都会返回 `INSTALL_FAILED_USER_RESTRICTED`，需要：

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

- APK 1.3 已安装（`lastUpdateTime=2026-10-22 15:23:39`，约 92KB，36 个工具，含
  `open_small_window` 原生 mode 11、`start_small_window` 与 `ui_swipe hold`），
  `MANAGE_EXTERNAL_STORAGE` 已补；
- 无障碍服务已开启并 connected；
- 宿主 `adb forward tcp:18896 tcp:8896` 已建立；
- **`always_hot` 已关**（验证完恢复成自适应默认），
  `/status` 里 `always_hot=false`、`freeze_gaps=0`；
- 最后一次冒烟：`open_small_window` 对 Settings（桥在后台）和美团（完全未启动）都返回
  `method=windowing_mode_11`、task `mode=stepos-mini-window`，约 1.5s；重复调用
  `already_open:true`；`/ping`、`/status` 200，`tools=36`；
- 测试后已关闭所有 StepOS 小窗；StepOS「手势呼出」开关已恢复为 **0**，
  `always_hot=false`；
- PiP/小窗测试 App `com.stepx.piptest` 已卸载，`/sdcard/Download` 里留有测试 APK；
- crash 缓冲为空。
- StepOS 冻结策略、`cached_apps_freezer` 均为设备原始状态
  （`cached_apps_freezer=enabled`，诊断时改过又改回）；
- GBox 已停止；GBox 里的旧克隆仍在，但不再使用。

## Amoo（com.stepos.ailauncher）调查结论

- 包名 `com.stepos.ailauncher`，label **Amoo**，系统应用；
- 有 `SCREEN_OPS`、`ACCESS_MCP_SERVICES`、`SYSTEM_ALERT_WINDOW`、
  `WRITE_SECURE_SETTINGS`、`MANAGE_ACTIVITY_TASKS`、`ACCESS_SURFACE_FLINGER`；
- 但 `IScreenOpsService` 只有 `getEditContent` / `setEditContent` /
  `setEditContentBatch`（只读写输入框）；
- `ScreenMCPService` 只有截图/录屏；
- 没有 `INJECT_EVENTS`、没有 AccessibilityService、没有 `dispatchGesture`；
- 所以 Amoo 自己无法模拟点击，UI 能力必须由本桥提供。

## Amoo 响应慢：一次 4 分钟任务的实测复盘（2026-10-22）

### 现象

用户让 Amoo「参考 `BUY_CHAGEE_DSS_PROMPT.md`，在抖省省买一杯伯牙绝弦」。
录屏 4:41（`/sdcard/Movies/Screenrecords/S61022-16121472.mp4`），
实际任务 **16:12:16–16:18:47（约 6 分 31 秒）**；最后停在会员授权，
16:18:29 抖音收银台只开了 6 秒就退回首页，**没有支付成功记录**。

### 时间去向（391 秒）

| 阶段 | 时长 | 占比 | 说明 |
|---|---:|---:|---|
| 读文档 + 规划 | 100 s | 26% | 22.7 KB / 378 行 prompt，2 次截图 |
| 启动 App → 搜索结果 | 41 s | 10% | 点桌面图标、mode 11、搜索/输入 |
| 结果页等待 | 83 s | 21% | 其中 59 s 画面完全静止 |
| 详情页核对 | 56 s | 14% | 核对价格/杯型/门店/规则，组织回复 |
| 会员/收银台/总结 | 111 s | 28% | 16:18:19 会员页、16:18:29 收银台、16:18:31–47 播报 |

**真正在操作 UI 的时间约 60 秒，其余 85% 是模型看截图/思考、读文档、等桥/等页面。**

### 关键时间线

| 时间 | 事件 |
|---|---|
| 16:12:16 | Amoo 开始 `thinking` |
| 16:12:35 | 计划：「收到，爸爸。我会按这份抖省省操作文档走一遍……」 |
| 16:12:59 | 桥 `start_app` 被拦：`Intercept = 9 / Caller process is background` |
| 16:13:19–16:13:42 | 读 prompt（系统截图 16:13:19、桥截图 16:13:38），回桌面 |
| 16:13:56 | 点桌面图标打开抖省省（距开始 100 s） |
| 16:14:15 | 搜索页 |
| 16:14:37 | 结果页（4 张券） |
| 16:14:37–16:15:37 | **画面完全静止约 59 秒**（1 fps 帧差 148 s→207 s 无变化） |
| 16:15:34 | 桥 `shot` 结果页（1.97 MB） |
| 16:15:35 | **StepOS freezer 杀桥**：`monitorFreeze ... freeze level consistently low, killing app` |
| 16:15:36 | 桥自动重启（PID 14715），16:15:42 聊天面板重新拿到焦点 |
| 16:16:00 | 券详情 |
| 16:16:56 | 录屏停止（约 5 分钟上限），任务继续 |
| 16:18:19 | 会员授权 WebView |
| 16:18:29 | 抖音收银台 `DyPayStandardActivity` |
| 16:18:35 | 退回抖省省首页；16:18:47 Amoo `speaking_done` |

### 根因

1. **视觉/模型回路是主因**：每轮「整屏截图（1200×2670，1.5–2 MB）→ 云端模型 → 动作」。
   视频里 16:14:37–16:15:37 与 16:16:00–16:16:56 两段画面零变化；
   桥在 16:13:38 / 16:15:34 / 16:16:11 / 16:17:45 各截一张；
   16:13:38 的聊天面板显示「正在处理中 47s」。
2. **抖省省无障碍树没有商品文本**：结果页 `ui_dump` 只有 118 个节点、几乎没有券名/价格；
   prompt 里的「文本优先、`ui_click` 按 text」对商品卡不适用，只能截图 + 坐标点。
   这是结果页 83 秒等待的主要来源。
3. **StepOS freezer 在任务中途杀桥**：`StepOSFreeze[Stats]` 在 16:15:35 报
   `freeze level consistently low, killing app`；进程死亡统计里
   `freeze times 365`、`totalFreezeDuration=659997ms`、`freezeLevel=0.26`。
   虽然 keep_hot 每 2 秒打 `FLAG_USING_SERVICE`，仍被 `FREEZE_LOW_KILL`。
   桥重启后 Amoo 要重新拿焦点/重连，16:15:42–16:16:00 这段是恢复成本。
4. **prompt 太长 + 目标不一致**：手机上 prompt 为 22,756 B / 378 行；
   文档写「青青糯山」，用户要「伯牙绝弦」，Amoo 必须自己改搜索词并重新核对
   4 张相近券（¥16.6 单杯 / ¥31.7 双大杯 / ¥14.9 学生 / ¥17 八选一）。
   另外首次 `start_app` 被拦，白等约 57 秒才改走桌面图标。
5. **会员门槛 + 长总结**：到「入会购买」后停下，生成并播报长中文总结；没有付款。

### Amoo 侧能看到的过程记录

Amoo 的会话历史可以完整打开（不需要 root）：
Amoo → 左上角 ☰ → 今天 → **购买伯牙绝弦**（40 个步骤那条）→ 已完成 → 展开 2/12/40 个步骤。

记录里有：

- 2 步：`正在读取文件内容`、`正在查看文件内容`；
- 12 步：`正在读取文件内容` + 多次 `正在设备能力调用`；
- 40 步：`正在按名称打开应用`、多次 `正在设备能力调用`、两次 `找到 6 个可用能力`、
  `正在执行终端命令「hostname」`、`正在切换目录`、`正在创建目录`、
  `正在写入文件内容`、`正在理解图片`、`正在列出目录`、`正在执行终端命令「find」`；
- 中间消息「我先用它，同类里它的综合体验最稳。」（录屏里没拍到）；
- 最后停在会员确认的总结。

注意：这些是 App 的**摘要标签**，不含工具参数；`/data/user/0/com.stepos.ailauncher`
的 SQLite 在 user build 上读不到。

### 结论与建议

- **不是桥的 UI 遍历慢**（热态 `ui_dump` 仍是 7–31 ms），而是
  **Amoo 的截图-模型回路 + 抖省省无可读文本 + StepOS 中途杀桥** 三者叠加。
- 长任务建议把 agent 放宿主侧，走 `adb forward tcp:18896 tcp:8896` + HTTP；
  手机侧 Amoo 只做展示/确认，避免视觉回路和 freezer 的双重开销。
- 如果必须用 Amoo：
  - 把 prompt 压成 10 行执行卡，商品参数化；
  - 明确「抖省省商品卡不要用 `ui_click` 按 text，直接 `shot` + 坐标」；
  - 禁止 `start_app`，第一步用 `open_small_window` 或桌面图标；
  - 一页一张截图，避免文档/详情重复截；
  - 到会员弹窗立即停，不要点「加入会员并购买」。
- 桥侧可以做的：长任务前 `keep_hot {"on":true}`；但实测仍会被
  `FREEZE_LOW_KILL`，所以不能只靠 keep_hot。若继续在手机侧跑长任务，
  需要考虑前台常驻/系统白名单，或改成宿主侧执行。
- **状态：未修复**（Amoo 是系统应用，改不了它的推理/视觉回路；本记录是复盘和规避建议）。

## 后续建议

1. **长任务前先 `keep_hot {"on":true}`**，结束后关掉；否则第一个请求可能等解冻。
2. 关注 `/status` 里的 `freeze_gaps`：一直增长说明保活失效（例如宿主机
   把 `startForegroundService` 拦了，或 StepOS 改了冻结策略），要重新实测。
3. 息屏/远程访问仍然是空的，需要单独做唤醒通道（不要再把 FCM 塞回主构建）。
4. 若将来要彻底摆脱冻结，只能走厂商白名单（StepOS 的
   `StepOSFreeze[Manager]` 在 system_server 里，应用侧无法直接豁免）。
5. **PiP 写操作先验证**：本机实测 PiP 内容不接收点击/手势，读操作没问题；
   要操作目标就先展开全屏，或确认 `ui_click` 后的 `input_verified`/下一次 dump。
6. 提交前确认 `work/`、`.DS_Store`、测试 APK 没有被加入。
