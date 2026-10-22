# PiP 目标应用自动化改造计划

更新时间：2026-10-22

## 1. 目标

让手机上的 agent 通过 `amoobridge` 桥，持续操作一个处于 Android 画中画（PiP）窗口中的目标 App。

目标形态：

```text
手机 agent
    │ HTTP 127.0.0.1:8896 / 文件信箱
    ▼
BridgeService（前台服务）
    │
    ▼
BridgeAccessibilityService
    │
    ├─ 枚举所有可交互窗口
    ├─ 找到目标包名的 PiP 窗口
    ├─ 读取该窗口的节点树
    └─ 对该窗口执行点击、滑动、截图和全局动作

目标 App：由目标 App 自己进入 PiP，并保持在屏幕最上层
```

本计划中的 PiP 指“目标 App 在 PiP”。给桥自身增加 PiP 只能提供一个小型控制面板，不能让任意第三方 App 进入 PiP，也不是本功能的必要条件。

## 2. 必须先确认的 Android 约束

1. 目标 App 必须自行声明 `android:supportsPictureInPicture="true"`，并由目标 App 自己调用 `enterPictureInPictureMode()`。桥不能替目标 App 修改 Manifest，也不能用普通应用权限强制任意 App 进入 PiP。
2. 目标 App 进入 PiP 的入口可能是 App 内按钮、返回行为或 Home 后自动进入。不同 App 的行为不同，必须逐个验证。
3. PiP 窗口在最上层，默认不拥有输入焦点，且窗口尺寸较小。首次点击可能只打开系统 PiP 控制菜单。
4. PiP 不解决 StepOS 的息屏冻结。Handoff 中记录的“息屏约 30 秒后 HTTP 超时”仍然成立。
5. 无障碍服务仍必须由用户开启并保持 connected。桥的 HTTP 和文件信箱继续使用现有令牌和端口。

## 3. 当前代码评估

当前架构已经具备服务端基础：

- `BridgeService` 负责前台服务、HTTP、文件信箱和心跳。
- `BridgeAccessibilityService` 负责 `dispatchGesture`、节点点击、全局动作和截图。
- `UiTools` 将 `/call` 工具映射到无障碍服务。
- 无障碍配置已经包含 `flagRetrieveInteractiveWindows` 和读取窗口内容能力。

需要改造的核心问题是：当前 `ui_dump` 和 `ui_click` 主要使用 `getRootInActiveWindow()`。PiP 默认没有输入焦点时，该方法可能返回底层窗口，而不是目标 PiP 窗口。因此必须增加按包名/窗口 ID 选择窗口的能力。

当前工作区有未提交的性能和 `KeepHot` 修改。实施 PiP 前应先建立独立分支或提交检查点，避免把实验性保活改动和 PiP 功能混在一起。`KeepHot` 只能改善部分后台冻结场景，不能作为 PiP 正确性的依据。

## 4. 推荐实现方案

### 4.1 新增窗口模型

在 `BridgeAccessibilityService` 中增加窗口快照模型，至少包含：

| 字段 | 说明 |
|---|---|
| `window_id` | `AccessibilityWindowInfo` 的窗口 ID |
| `package` | 窗口根节点包名 |
| `type` | 应用窗口、系统窗口等类型 |
| `bounds` | 屏幕坐标中的窗口矩形 |
| `active` | 是否为当前活动窗口 |
| `focused` | 是否拥有输入焦点 |
| `layer` | 窗口层级，用于判断 PiP 是否在最上层 |
| `pip_candidate` | 是否符合“小窗口应用窗口”的候选条件 |

实现 `listWindows()`，通过 `getWindows()` 遍历交互窗口，并读取每个窗口的根节点包名和屏幕 bounds。

### 4.2 增加目标窗口选择器

增加统一选择逻辑，所有 PiP 自动化工具都使用同一个选择器：

```text
优先级：显式 window_id
其次：显式 package + pip=true
其次：显式 package
最后：当前 active window（兼容旧调用）
```

选择器必须返回明确错误，例如：

- `target window not found`
- `package has multiple windows`
- `package window is not a PiP candidate`
- `system PiP controls are active`

不要静默退回桥自身窗口或系统窗口，否则 agent 会误点击。

### 4.3 扩展现有 UI 工具

保持现有调用兼容，同时增加可选参数：

```json
{"tool":"ui_windows","args":{}}
```

返回当前可交互窗口列表。

```json
{"tool":"ui_dump","args":{"package":"com.example.target","pip":true}}
```

只读取目标包的 PiP 窗口，并在结果中返回 `window_id`、`bounds`、`partial` 和耗时信息。

```json
{"tool":"ui_click","args":{"package":"com.example.target","pip":true,"text":"确定"}}
```

只在目标窗口内按文本或描述查找并点击。

```json
{"tool":"ui_tap","args":{"package":"com.example.target","pip":true,"x":900,"y":300}}
```

执行前检查坐标是否落在目标 PiP bounds 内；不在范围内直接返回错误，避免点击底层 App。

`ui_swipe` 使用相同的 bounds 校验。`ui_back`、`ui_home`、`ui_recents` 仍然是全局动作，不绑定窗口。

### 4.4 增加 PiP 会话工具

建议新增一个轻量的 `pip_target` 工具，用来建立和检查会话，不负责强制目标 App 进入 PiP：

```json
{"tool":"pip_target","args":{"package":"com.example.target","action":"inspect"}}
```

返回：

```json
{
  "ok": true,
  "package": "com.example.target",
  "in_pip": true,
  "window_id": 42,
  "bounds": [720, 1200, 1080, 1500],
  "active": false,
  "focused": false
}
```

建议支持的动作：

- `inspect`：检查目标是否存在 PiP 窗口；
- `select`：缓存目标包名和窗口 ID；
- `clear`：清除会话；
- `recover`：目标窗口消失时清除缓存并返回恢复建议。

目标 App 的“进入 PiP”动作仍由以下方式之一完成：

1. agent 先用无障碍点击目标 App 自带的 PiP 控件；
2. 目标 App 支持 Home 自动进入 PiP 时调用 `ui_home`；
3. 用户手动把目标 App 放入 PiP 后，agent 再调用 `pip_target inspect`。

### 4.5 窗口和节点缓存

现有节点缓存可以保留，但必须同时绑定：

- `window_id`；
- 目标包名；
- 缓存时间；
- 当前 PiP bounds。

窗口 ID、包名或 bounds 发生变化时立即清除缓存。PiP 被移动、展开、收起或系统控件出现后，必须重新获取窗口和节点。

## 5. 是否修改 MainActivity 和 Manifest

### 必须修改的部分

桥本身不需要开启 PiP 才能控制目标 PiP，因此不是必须给 `MainActivity` 添加 `supportsPictureInPicture`。

### 可选修改的部分

如果希望用户把桥变成一个小型状态面板，可以给桥增加独立的 PiP 支持：

```xml
android:supportsPictureInPicture="true"
```

并在 `MainActivity` 增加“进入桥 PiP”按钮和 `onPictureInPictureModeChanged()` 处理。但这属于辅助界面功能，不能代替目标 App 的 PiP 支持，也不应作为 agent 自动化链路的前置条件。

如果最终加入桥自身 PiP，应做到：

- PiP 内隐藏权限按钮和长列表，只显示连接状态、目标包名和窗口 bounds；
- 不依赖 PiP Activity 接收点击；
- 不改变 `BridgeService` 和 `BridgeAccessibilityService` 的生命周期；
- 测试桥 PiP 是否遮挡目标 PiP，默认将两个窗口放在不同角落。

## 6. Agent 调用流程

推荐的 agent 流程如下：

```text
1. ping/status
2. 检查 accessibility connected
3. ui_home
4. ui_dump 读取桌面并点击目标应用图标
5. 让目标 App 自己进入 PiP
6. pip_target.inspect(package)
7. pip_target.select(package)
8. ui_dump(package, pip=true)
9. ui_click / ui_tap / ui_swipe(package, pip=true)
10. 每次关键操作后重新 inspect，确认 PiP 仍存在
```

agent 不应只根据 `ui_tap` 返回 `ok=true` 判断操作成功。应结合下一次 `ui_dump`、截图或目标窗口状态确认目标 App 发生了变化。

## 7. 错误和恢复策略

| 场景 | 处理方式 |
|---|---|
| 目标 App 没有 PiP 窗口 | 返回 `target_not_in_pip`，不要点击底层窗口 |
| PiP 被展开为全屏 | 允许切换为普通目标窗口，或要求重新选择 `pip=true` |
| PiP 被用户拖到新位置 | 重新读取 bounds，不使用旧坐标 |
| 系统 PiP 菜单打开 | 返回 `pip_controls_visible`，等待菜单关闭后重试 |
| 目标 App 节点为空 | 使用 bounds 内坐标手势，并要求截图确认 |
| 目标 App 被系统关闭 | 清除会话，重新走启动流程 |
| 无障碍断开 | 返回 `accessibility service not enabled`，停止后续点击 |
| 屏幕关闭后 HTTP 超时 | 按已知限制报告设备冻结，不能声称 PiP 已解决 |

## 8. 分阶段实施

### 阶段 A：窗口观察，不改变现有工具

- 实现 `ui_windows`；
- 打印包名、类型、bounds、active/focused、layer；
- 在测试机上确认 StepOS 是否把 PiP 目标暴露为可交互窗口；
- 分别测试目标 App 在 PiP、系统 PiP 菜单、桥 Activity 前台时的窗口列表。

交付标准：能稳定识别目标包名的 PiP 窗口，并能记录其 bounds。

### 阶段 B：目标窗口读操作

- 给 `ui_dump` 增加 `package`、`window_id`、`pip` 参数；
- 加入窗口 ID 和 bounds 到 `ui_dump.json`；
- 保留无参数调用的旧行为；
- 增加窗口变化时的缓存失效。

交付标准：目标 PiP 的 `ui_dump` 不会返回桥窗口、桌面或系统 PiP 菜单。

### 阶段 C：目标窗口写操作

- 给 `ui_click` 增加目标窗口选择；
- 给 `ui_tap` / `ui_swipe` 增加 bounds 校验；
- 增加 `pip_target` 的 inspect/select/clear；
- 在返回值中加入 `window_id`、`bounds`、`elapsed_ms`。

交付标准：文本点击、坐标点击、滑动至少各成功一次，并能证明作用于目标 PiP。

### 阶段 D：可选桥 PiP

- 仅在确有需要时修改桥 Manifest 和 MainActivity；
- 增加小型状态面板；
- 验证桥 PiP 不会改变 HTTP、文件信箱和无障碍服务行为。

### 阶段 E：文档和构建

- 更新 `HANDOFF.md`，记录目标 App、PiP 入口、窗口类型和实测限制；
- 更新工具清单和 agent 调用示例；
- 执行 `./gradlew assembleDebug`；
- 遵循项目要求手动安装 APK，不使用 `adb install`；
- 不引入 FCM、GBox 或 Google 服务依赖。

## 9. 测试矩阵

至少覆盖以下场景：

| 测试 | 预期 |
|---|---|
| 目标 App 全屏 | 旧版 `ui_dump` / `ui_click` 继续可用 |
| 目标 App 进入 PiP | `ui_windows` 能发现目标窗口 |
| 目标 PiP 无输入焦点 | 按 package/window_id 仍能 dump |
| PiP 移动位置 | 新 bounds 生效，旧坐标被拒绝 |
| 点击 PiP 内容 | 目标状态发生变化 |
| 点击 PiP 系统菜单 | 返回菜单可见或窗口变化，不误报业务成功 |
| 节点不可见 | 坐标手势可作为明确的 fallback |
| 目标退出 PiP | `pip_target` 返回 false，停止写操作 |
| 桥退后台 60 秒 | `/ping` 和目标窗口操作仍可用 |
| 屏幕关闭约 30 秒 | 如仍超时，记录为 StepOS 已知限制 |
| 重启设备 | 无障碍、前台服务和 HTTP 状态按现有验收清单恢复 |
| Crash 缓冲 | 无本应用 FATAL EXCEPTION |

## 10. 验收标准

- [ ] 目标 App 明确支持并成功进入 PiP；
- [ ] 桥能通过 `ui_windows` 识别目标 PiP 窗口；
- [ ] `ui_dump(package, pip=true)` 返回目标节点；
- [ ] `ui_click(package, pip=true)` 点击目标节点；
- [ ] `ui_tap` 和 `ui_swipe` 拒绝 PiP bounds 外坐标；
- [ ] PiP 移动、展开、收起后缓存自动失效；
- [ ] 系统 PiP 菜单不会被误判为目标 App 内容；
- [ ] 原有无参数 UI 工具兼容；
- [ ] `/ping`、`/status`、`status.json` 和无障碍连接不受影响；
- [ ] 息屏限制在文档中明确记录；
- [ ] `HANDOFF.md` 已更新；
- [ ] 构建、安装和 crash 检查通过。

## 11. 最终取舍

如果目标 App 支持 PiP，优先实施阶段 A 到 C，桥自身 PiP 可以不做。

如果目标 App 不支持 PiP，停止尝试从桥强制进入 PiP，改用全屏自动化或分屏方案。给桥自身添加 `supportsPictureInPicture` 不能解决这个问题。

## 12. 参考资料

- [Android PiP：Use picture-in-picture](https://developer.android.com/develop/ui/views/picture-in-picture)
- [Android AccessibilityService API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
