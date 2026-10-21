# StepBridge Handoff

更新时间：2026-10-21

## 当前状态

- 当前分支：`codex/current-changes`
- 远程仓库：`https://github.com/amazingzxc/amoobridge.git`
- 应用包名：`com.stepx.amoobridge`
- Android 模块：`app/`
- 业务源码：`stepx-amoo-bridge/`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

最近的相关提交：

- `993fad5`：修复 Firebase 新版依赖导致的启动闪退
- `2469019`：加入 Firebase Google Services 配置
- `88d10ce`：加入 FCM 远程唤醒流程
- `421a2cf`：增强后台 HTTP 监听恢复

## 构建与安装

构建环境要求 JDK 17、Android SDK Platform 34 和对应 Build Tools。项目根目录执行：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。设备存在厂商安装限制时，`adb install` 可能返回 `INSTALL_FAILED_USER_RESTRICTED`，可改用：

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/amoobridge-fcm-configured.apk
```

然后在手机的 Download 目录中手动点按安装。更新安装会保留应用数据。

## Firebase / FCM

- `app/google-services.json` 已配置，包名必须保持为 `com.stepx.amoobridge`。
- 根 `build.gradle` 已启用 Google Services 插件。
- 当前使用 `com.google.firebase:firebase-messaging:24.1.2`。不要随意升级到会引入不兼容 DataStore 变体的版本；之前的 Firebase BOM 34.19.0 会导致 `NoClassDefFoundError: androidx.datastore.preferences.PreferenceDataStoreDelegateKt`。
- `BridgeFirebaseMessagingService` 只处理 data payload 中 `type=bridge_wake` 的消息，并通过 `BridgeService.EXTRA_WAKE_WINDOW` 启动约 60 秒的临时 HTTP 服务。
- 设备 token 预期写入 `/sdcard/bridge-amoobridge/fcm_token.txt`。
- 发送测试消息时使用 Android 高优先级 data payload：

```json
{
  "message": {
    "token": "<设备 FCM token>",
    "android": { "priority": "high" },
    "data": { "type": "bridge_wake" }
  }
}
```

## 已验证行为

修复版安装后，设备上的应用进程和前台 `BridgeService` 可以正常启动，8896 端口在前台返回预期的 `401 unauthorized`，应用自身没有新的 `FATAL EXCEPTION`。

该测试机的 Google Play Services 进程因缺少 `com.google.android.gsf.gservices` provider 崩溃，Firebase 注册最终返回 `SERVICE_NOT_AVAILABLE`，因此这台设备不能作为真实 FCM 到达测试机。这个错误发生在 `com.google.android.gms`，不是 StepBridge 应用进程。

应用退到后台后，进程仍显示为前台服务且未被冻结，但通过 ADB 转发访问 8896 可能超时。这是当前 StepOS/厂商网络或后台策略的已知限制，需要在目标设备上单独验证；同时检查 `status.json` 的 `ts` 和 `running` 字段。

## 基础冒烟测试

```bash
adb shell am force-stop com.stepx.amoobridge
adb shell monkey -p com.stepx.amoobridge 1
adb shell pidof com.stepx.amoobridge
adb shell dumpsys activity services com.stepx.amoobridge
adb forward tcp:18896 tcp:8896
curl -i --max-time 3 http://127.0.0.1:18896/
adb forward --remove tcp:18896
adb shell cat /sdcard/bridge-amoobridge/status.json
adb logcat -d -b crash | rg 'com.stepx.amoobridge|FATAL EXCEPTION'
```

涉及 Android 源码、Manifest、资源或 Gradle 的改动后，必须先执行 `./gradlew assembleDebug`，再按 `AGENTS.md` 完成设备冒烟测试。不要提交 `.DS_Store` 或 `work/` 目录中的现有未跟踪文件。

## 后续工作建议

1. 在带有正常 Google Play Services 的设备或模拟器上取得真实 FCM token，并用 Firebase HTTP v1 API 发送 `bridge_wake` 测试消息。
2. 在目标 StepOS 设备上分别测试前台、锁屏、退后台和重启后的 8896 可达性，记录 `status.json` 心跳和 `dumpsys activity services` 状态。
3. 如需发布版本，使用项目专用签名密钥构建 Release APK；签名文件和服务账号凭据不要提交到仓库。
