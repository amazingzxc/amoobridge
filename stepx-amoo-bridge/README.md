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

## 重新构建

```bash
bash /root/apkbuild.sh /sdcard/bridgeapp stepbridge
cp /root/build-stepbridge/stepbridge.apk /sdcard/Download/
```

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
