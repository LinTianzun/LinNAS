# linNas Android 客户端

## 当前版本

- 版本名：**1.4.0**
- `versionCode`：**6**
- 最低系统：Android 7.0（API 24）
- 当前分发包：[`release/linNas-1.4.0-debug.apk`](../release/linNas-1.4.0-debug.apk)

该 APK 使用 Android debug 签名，适合手动安装和测试；不是 Google Play 发布包。服务端与 Android 客户端独立版本化，发布步骤见根目录 [RELEASING.md](../RELEASING.md)。

## 安装与连接

1. 在 Windows 电脑启动 `server/启动linNas.bat`。
2. 手机与电脑连接同一 Wi-Fi，安装 APK 并打开应用。
3. 选择自动发现的服务器，或在电脑打开 `http://电脑IP:52700/pair` 后扫码配对；也可以手动输入服务器地址。
4. 如果服务端启用了多用户，使用服务端账号登录；仅配置访问口令时按提示输入口令。

遇到连接问题时，先确认服务端正在运行、Windows 防火墙允许专用网络访问，并确认手机没有连访客 Wi-Fi 或隔离网络。

## 功能

- 自动发现（mDNS、UDP 广播和网段扫描）、二维码配对、手动连接
- 存储库与目录浏览、搜索、媒体库、缩略图、排序和列表/网格视图
- 分块上传、断点续传、后台上传与进度通知；单任务暂停/取消/重试、系统下载和离线缓存
- Media3 播放器、字幕、播放进度记忆、倍速、画中画、播放列表连播（上一集/下一集/列表）
- 相册自动备份（WorkManager）
- 隐私保险箱、回收站、分享链接
- 文件/目录详情、操作日志（管理员）
- 多用户登录及角色感知界面、主题切换

## 开发构建

依赖 Android Studio / Android SDK（compileSdk 34）和 JDK 17 或更高版本。SDK 路径通过本机 `local.properties` 配置，不应写入版本文档或发布包。

在 Windows PowerShell 中从项目根目录执行：

```powershell
.\android\gradlew.bat -p .\android --no-daemon :app:assembleDebug
```

构建产物位于 `android/app/build/outputs/apk/debug/app-debug.apk`。版本名和 `versionCode` 在 `app/build.gradle.kts` 中维护；每次发布都应递增 `versionCode`。

## 版本历史

较早版本的功能记录见根目录 [PLAN.md](../PLAN.md)。当前功能清单以本节为准。
