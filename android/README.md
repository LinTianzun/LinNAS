# linNas Android 端

连接局域网内的 linNas-server:浏览文件、上传、在线看片、媒体库、相册自动备份。

## 安装

APK:`E:\linNas\release\linNas-0.6.0-debug.apk`(约 9.9 MB,debug 签名)。

- **USB**:手机连电脑,把 APK 拷进手机,用文件管理器点开安装(需允许"安装未知来源应用")。
- 或微信/QQ 把 APK 传到手机再安装。系统要求 Android 7.0(API 24)及以上。

## 首次使用(现在不用手输 IP 了)

1. 电脑上双击 `server/启动linNas.bat` 启动服务端。
2. 手机**连同一个 WiFi**,打开 linNas App。
3. App 打开即自动搜索服务器(三路并行:mDNS 发现 / UDP 52701 广播发现 / 网段扫描),弹出「发现服务器」列表(名称 + 地址)→ **点一下即可连接**。
   - **扫码配对(最快)**:电脑浏览器打开 `http://电脑IP:52700/pair`(服务端启动日志里也会打印这个地址),手机 App 点「扫码配对」扫页面上的二维码 → 自动连上(带访问口令时也会自动带上)。
   - 找不到时:确认电脑端已启动、手机连的是同一 WiFi;也可点「手动输入」填 `http://电脑IP:52700`。
   - 连过一次后会记住,下次打开自动连。
4. 连上后:文件页浏览 H盘/I盘、右下角「上传」、点视频直接内置播放(可拖进度条、倍速);
   底部「媒体」按视频/图片/音乐聚合全库;「传输」里配置相册自动备份;「设置」里可重新扫描/改服务器。

### 连不上排查
- **防火墙**:首次运行服务端时 Windows 弹窗要点"允许";没弹过就右键管理员运行
  `server/防火墙放行-管理员运行.bat`。
- **网段**:自动发现会扫本机同网段(192.168/10./172.16-31);若手机在访客网络/VPN 下可能扫不到,改用「扫码配对」或「手动输入」。
- **PC 睡眠**:电脑睡眠时连不上,把电源计划设为"接通电源时不睡眠"。

## 功能

| 版本 | 能力 |
|---|---|
| v1.0.0 | **播放体验包**:① **横屏/竖屏**(顶部「旋转」按钮锁横屏,或随设备自动转,全屏黑底);② **手势**:双击左/右快退/快进 10s、上下滑调音量/亮度、水平拖动进度、长按 2x 倍速、单击显示/隐藏控件;③ **续播记忆**:自动记住进度,文件行显示「继续观看 · 还剩约 x 分钟」,再次点开从断点播;④ **字幕**:自动挂载同目录同名 `.srt/.ass/.vtt`;⑤ 画面比例、倍速、解码错误提示、拔耳机暂停 |
| v0.9.1 | **修复启动闪退**:主题 Material 颜色属性必须用 `@color` 资源引用(字面色值会让 Material 拿到 0 号资源 → Resources$NotFoundException);新增崩溃自检页(堆栈可复制,落盘 crash.txt) |
| v0.9.0 | 视频播放基础优化(±10s、画面比例、倍速、解码提示);手机端添加存储库(`/api/fs/dirs`);保险箱导入/导出 |
| v0.8.0 | **全局搜索**(「全库」开关);**移动/复制**(行菜单→选目标文件夹);**设置页服务器区**(改口令 / 系统信息 / 设备管理 / 保险箱入口);**保险箱**(创建密码/解锁/锁定/上传/播放/下载/删除);**上传/下载进度**(上传实时百分比 + 通知;下载用系统 DownloadManager,通知栏与传输页都有进度) |
| v0.7.0 | **主题切换**(墨蓝/墨绿/琥珀/纯黑,主题属性驱动,即时切换);文件页 **列表/网格切换**、按 **名称/大小/时间** 排序(升/降序);顶部显示 **磁盘剩余空间**(进库显示该盘,根目录显示合计) |
| v0.6.0 | **全新界面**:底部 4 个 tab(文件/媒体/传输/设置)、深色墨蓝 + 冷蓝主色、手绘线性图标、缩略图列表、空状态/加载/连接状态;**局域网自动发现**(免手输 IP,App 打开即扫描) |
| v0.5.0 | 相册自动备份(WorkManager 每 6 小时、仅 WiFi、增量、断点续传) |
| v0.4.0 | 媒体库(视频/图片/音乐聚合,服务端 SQLite 索引) |
| v0.3.0 | 内置 ExoPlayer 播放页(倍速、保持亮屏;老格式服务端转码) |
| v0.2.0 | 文件浏览、缩略图、分块上传、断点续传、新建文件夹 |

## 结构

```
app/src/main/java/com/linnas/app/
  MainActivity.kt          主界面(4 tab 壳 + 文件/媒体/传输/设置绑定)
  Discovery.kt             局域网自动发现(广播 52701 + mDNS + /24 扫描三路并行;parsePair 解析配对 URI/地址)
  MdnsDiscovery.kt         mDNS(NsdManager)发现 _linnas._tcp. 服务
  PlayerActivity.kt        内置 ExoPlayer 播放页(倍速)
  FileListAdapter.kt       文件列表(存储库/文件行、缩略图、类型图标)
  FileGridAdapter.kt       文件网格视图(卡片,长按出菜单)
  MediaGridAdapter.kt      媒体库网格
  ThemeStore.kt            4 套主题 + 视图/排序偏好(linnas_ui)
  ThemeColors.kt           读取主题属性色(跟随主题)
  Downloads.kt             下载(系统 DownloadManager,进度可查)
  VaultActivity.kt         保险箱(密码解锁、上传、播放、删除)
  Models.kt                数据模型
  LinNasClient.kt          OkHttp API 客户端(分块上传/断点续传/媒体/搜索)
  backup/PhotoBackup.kt    相册自动备份(WorkManager)
  upload/UploadService.kt  前台上传服务(dataSync 前台服务)
app/src/main/res/
  values/colors.xml        调色板(ink/surface/surface_high/outline/primary/mint/amber/danger)
  values/themes.xml        Theme.LinNas + 文本层级 TextTitle/TextLabel/TextItem/TextMeta
  drawable/ic_*.xml        18 个手绘描边矢量图标(统一 1.7 描边)
  layout/  menu/nav_main.xml  color/nav_item.xml
```

## 构建(开发者)

依赖:JDK 21、Android SDK(`local.properties` 用 `sdk.dir=D:/android-sdk`)。

```
D:\gradle-8.7\bin\gradle.bat assembleDebug
```

产物:`app/build/outputs/apk/debug/app-debug.apk`。

> 本机 Gradle wrapper 自举下载曾失败,故直接用已下载的 Gradle 8.7;依赖走阿里云 Maven 镜像(见 `settings.gradle.kts`)。

## 待办

- 字幕(外挂 srt)、画中画
- 离线缓存 / 断点下载
- WebDAV / Tailscale(远程访问)
