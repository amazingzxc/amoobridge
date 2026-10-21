# 项目开发规范

## Feature 改动后的构建要求

每次完成 feature、bug fix 或影响 Android 源码、Manifest、资源、Gradle 配置的改动后，必须自动编译 APK，并在提交前确认构建结果。

在项目根目录执行：

```bash
./gradlew assembleDebug
```

Debug APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`。如果项目尚未生成 Gradle wrapper，先使用已安装的 Gradle 生成 wrapper；构建环境必须使用 JDK 17 或更高版本，并安装 Android SDK Platform 34 和对应 Build Tools。

如果有已连接且授权的 Android 设备，构建成功后继续执行：

```bash
./gradlew installDebug
adb shell am force-stop com.stepx.amoobridge
adb shell monkey -p com.stepx.amoobridge 1
```

然后通过 ADB 做基础冒烟测试：确认应用进程、前台服务、8896 端口和 `/ping` 接口可用；涉及后台保活的改动还要将应用切到后台，等待至少一个 watchdog 周期后再次检查端口和 `status.json` 心跳。

如果构建或设备测试因本机缺少 JDK、SDK、Gradle、设备授权或厂商限制而无法执行，必须在工作结果中明确记录阻塞原因和已完成的静态检查。
