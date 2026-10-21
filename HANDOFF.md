# StepBridge Handoff

更新时间：2026-10-21 18:15

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

## 重启 / 息屏测试（2026-10-21）

- **重启**：应用自动启动（pid 7202），无障碍自动绑定，
  无需手动打开应用，8896 直接返回 200；`status.json` 正常。
- **息屏 / Doze**：关屏约 30 秒后 `/ping` 超时，`status.json` 停止更新；
  亮屏后立即恢复 200。
- 已加入电池优化白名单（`dumpsys deviceidle whitelist` 可见），
  但仍无法阻止 StepOS 在息屏时冻结。
- 结论：本地 agent 使用时保持亮屏；息屏/远程访问需要额外唤醒通道，
  当前纯原生版本不提供。

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

## 手机 agent 端到端测试（2026-10-21 18:05）

- agent 云端无法直连 `127.0.0.1:8896`，改为在手机端 shell 用 curl 发同样的 JSON；
  未使用 `start_app`，也未用 agent 自己的点击能力代替。
- 结果：
  - `/ping` 200；
  - `ui_home` 17ms；
  - `ui_dump` 桌面 53 节点，找到 `美团`；
  - `ui_click 美团` 200（`method=node`），package 变为 `com.sankuai.meituan`（203 节点）；
  - `ui_swipe` 200；
  - `shot` 200，生成 `shot_1792576055103.png`（约 2.3MB）；
  - `ui_click 外卖` 200（`method=node`），页面变为 12 节点，
    命中“点击预订，可选购对应时段可售商品”入口页。
- 结论：接口链路、无障碍点击、滑动、截图均真实可用。

### 性能问题（待解决）

- `ui_dump` 耗时 25–40 秒；
- `ui_click` 约 65 秒；
- 期间并发 `/ping` 会超时，但进程 pid 不变，不是崩溃；
- 疑似 StepOS 对后台桥反复 freeze/unfreeze，叠加递归 `getChild()` 的 binder 调用；
- agent 的 shell 单命令上限 10 秒，会先 SIGKILL，但服务端仍会完成；
  客户端超时应设 90 秒以上；
- 待办：加计时日志、对比前台/后台、查 StepOS 冻结白名单、
  必要时优化 `ui_dump` / `ui_click`；
- 截图内容尚未核对：测试后手机 USB 断开，`adb devices` 为空。

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
- 已在电池优化白名单中（`com.stepx.amoobridge`）；
- GBox 已停止；GBox 里的旧克隆仍在，但不再使用；
- 测试后手机 USB 断开，`adb devices` 为空，待重连。

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

1. **重连 USB**，拉取 `shot_1792576055103.png` 核对截图内容。
2. **诊断 UI 延迟**：
   - 在 `BridgeAccessibilityService` 里加 `getRoot` / `walk` / JSON / 写文件 / `performAction` 计时；
   - 把桥切到前台再测 `ui_dump`，确认是否 StepOS 冻结导致；
   - 查 StepOS 的厂商冻结白名单/后台设置。
3. 如果延迟降不到可用水平，考虑分屏（桥和 agent/目标应用同时可见）或厂商白名单。
4. 远程控制单独做中继/推送方案，不要重新把 FCM 塞回主构建。
5. 提交前确认 `work/`、`.DS_Store`、测试 APK 没有被加入。
