上传的是1.x版本，2.0以后的还没有压测完
# StepBridge 

手机的「系统层手部」：能跑命令、读写盘、拉起应用、发通知、模拟点按滑动、截图。

- 两条通道：**本机 HTTP**（快）+ **文件信箱**（稳）

## 装完必做的四步

| 步骤 | 为什么 |
|---|---|
| ① 所有文件访问 | 不授权读不到 `/sdcard/bridge`，整套就废 |
| ② 无障碍服务 | UI 自动化、截图必需，不给就少一半能力 |
| ③ 电池优化白名单 | 不给会被系统杀掉，实测活不过几分钟 |
| ④ 修改系统设置 | 只有调亮度需要，不常用可跳过 |

App 里四个按钮一键直达对应设置页。打开 App 会自动启动服务。

## 通道一：本机 HTTP（推荐）

```
POST http://127.0.0.1:8898/call
Header: Authorization: Bearer <token>
Body:   {"tool":"exec","args":{"cmd":"ls /sdcard"}}
```

令牌自动生成，存在 `/sdcard/bridge/token.txt`。

延迟从信箱模式的 350ms 降到几毫秒。

## 通道二：文件信箱（兜底）

`/sdcard/bridge/cmd_<id>.json` 进，`result_<id>.json` 出，命令文件处理完自动删。
服务没起来时命令会排队，下次醒来照样执行。

## 命令行入口

VM 里有 `bridge` 命令，HTTP 优先、信箱兜底，自动切换：

```bash
bridge ping                 # 存活检查
bridge info                 # 机型/SDK/端口/无障碍状态
bridge status               # 心跳
bridge exec 'df -h /sdcard'
bridge ls /sdcard/dev
bridge read /sdcard/a.txt
bridge write /sdcard/a.txt 'hello'
bridge apps                 # 已装应用列表
bridge ui-dump              # 当前界面控件树
bridge ui-click 确定        # 按文本点击
bridge ui-tap 540 1200      # 坐标点击
bridge ui-back              # 返回键
bridge shot                 # 截图到 /sdcard/bridge/shot_*.png
bridge call <工具> '{"k":"v"}'   # 任意工具
```

## 工具清单（31 个）

| 分类 | 工具 |
|---|---|
| 基础 | `ping` `info` `status` |
| 执行 | `exec` |
| 文件 | `ls` `read` `write` `mkdir` `rm` `mv` `cp` `stat` |
| 应用 | `start_app` `list_apps` `open_url` `share_text` |
| 系统 | `clipboard` `toast` `notify` `battery` `volume` `brightness` `vibrate` |
| UI（需无障碍） | `ui_dump` `ui_tap` `ui_swipe` `ui_click` `ui_back` `ui_home` `ui_recents` `shot` |

## 边界

- App 身份：跑不了 `input`、`screencap`、`dumpsys` 这些要 shell 权限的（UI 操作全靠无障碍那张牌）
- 没有 root
- 平台不允许助手打开侧载应用，所以服务死了只能用户手动点开唤醒

## 从源码构建 APK

仓库已经包含标准 Android Gradle 配置，源码仍放在 `stepx-amoo-bridge/` 目录中。构建需要：

- Android Studio（建议最新版稳定版）
- Android SDK Platform 34 和 Android SDK Build-Tools
- JDK 17（Android Gradle Plugin 8.2.2 要求）

### 方式一：Android Studio

1. 用 Android Studio 打开仓库根目录（包含 `settings.gradle` 的 `amoobridge/`）。
2. 等待 Gradle 同步完成；如果提示缺少 SDK 34，按提示安装。
3. 选择 `Build > Make Project`，确认源码可以编译。
4. 选择 `Build > Build Bundle(s) / APK(s) > Build APK(s)`。
5. Debug APK 输出在：

   `app/build/outputs/apk/debug/app-debug.apk`

### 方式二：命令行

在仓库根目录执行。首次使用时需要本机已安装 Gradle 8.2 或更高版本：

```powershell
gradle wrapper --gradle-version 8.2
.\gradlew.bat assembleDebug
```

生成的 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。连接已开启 USB 调试的设备后，可以直接安装：

```powershell
.\gradlew.bat installDebug
```

Linux/macOS 使用 `./gradlew` 替换上面的 `.\gradlew.bat`。

### 构建 Release APK

Release 构建默认关闭代码压缩，但发布前仍应使用自己的签名密钥。先创建一次密钥库（密钥库文件不要提交到 Git）：

```powershell
keytool -genkeypair -v -keystore stepx-release.jks -alias stepx \
  -keyalg RSA -keysize 2048 -validity 10000
```

然后在 Android Studio 的 `Build > Generate Signed Bundle / APK` 中选择 `APK`、`release` 和该密钥库，或按 Android Studio 的签名向导完成构建。未签名的 Release APK 可用下面命令生成，位置为 `app/build/outputs/apk/release/app-release-unsigned.apk`：

```powershell
.\gradlew.bat assembleRelease
```

### 安装后首次配置

安装 APK 后打开应用，依次授予“所有文件访问”、后台运行（电池优化白名单）和通知权限；需要调节亮度时再授予“修改系统设置”。应用启动后会自动启动桥服务。

### 防止后台被冻结

应用使用前台服务、`START_STICKY`、每分钟 watchdog 闹钟和部分唤醒锁来维持处理线程。首次启动时仍需要在系统设置中完成下面配置，否则部分厂商系统会冻结或清理进程：

1. 点击应用内的“允许后台常驻（电池优化）”，把本应用设为“不优化”或“无限制”。
2. 在系统的“应用启动/自启动”设置中允许本应用自启动。
3. 在“后台活动/电池”设置中选择“无限制”，并允许后台运行。
4. 不要从系统的最近任务界面清理本应用；如果系统支持应用锁定，请将本应用锁定。
5. 保留通知权限，让前台服务通知可以正常显示。

不同厂商的入口名称可能是“自启动管理”“后台弹出界面”“省电策略”或“应用休眠”。应用被厂商策略强制停止时，Android 不允许普通应用绕过系统限制；完成上述设置后，开机广播和 watchdog 会自动尝试恢复服务。

## 版本记录

| 版本 | 变更 |
|---|---|
| 2.0 | 加本机 HTTP 通道（8898）、无障碍服务、工具扩到 31 个 |
| 2.1 | 修 `list_apps`：改走 launcher 查询，绕开包可见性限制 |
| 2.2 | 新增 `ui_longpress`（长按，默认 700ms）、`shot_b64`（截图缩放后 base64 回传） |

### v2.2 两个新能力的用法

```bash
# 长按：微信/银行/游戏这些不暴露控件树的应用，只能靠坐标长按
bridge call ui_longpress '{"x":900,"y":1200,"ms":700}'

# 截图回传：设备端缩放成 JPEG 再 base64，助手能直接“看”屏幕
bridge call shot_b64 '{"maxw":480,"quality":60}'
# 返回 {ok, orig_w, orig_h, w, h, bytes, scale, b64}
```

`scale` = w/orig_w，用它把图片里的坐标反算回设备像素。
