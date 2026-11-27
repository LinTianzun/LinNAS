# LinNAS — 本地云盘/NAS 方案与版本路线图

> 当前组件版本（2026-11-27）：Windows 服务端 **1.8.4**；Android 客户端 **1.8.15**（`versionCode` 24）。发布流程见根目录 [RELEASING.md](RELEASING.md)。

> 下文保留早期技术决策与里程碑记录；历史版本号描述当时状态，不代表当前版本。

> 决策已确认(2026-11-24):
> - PC 服务端:**Go**
> - Android:**Kotlin 原生**(Media3/ExoPlayer)
> - 相册自动备份:**二期**
> - 片源:**混合格式**(MP4/MKV 为主 + AVI/RMVB 等老格式)→ ffmpeg 按需转码为**必选组件**

## 一、目标

- PC(Windows)插两块外置机械硬盘(H盘 / I盘),跑一个**服务端程序**
- Android 手机回家连同一 WiFi:**像本地云盘一样上传 / 浏览 / 下载**,并能**在线观看视频**
- PC 端有管理界面
- 全程局域网直连,不依赖公网、不依赖云服务

## 二、总体架构

```
[Android App]         [任意浏览器(PC/手机)]
      |  HTTP REST           |
      +----------+  +--------+
                 |  |
          [PC 服务端 linNas-server]
                 |
   [H盘]  [I盘]  [SQLite 索引]  [缩略图缓存]
```

- 一切通信走 HTTP(REST + 少量 WebSocket)
- 手机 App 与浏览器共用同一套服务端 API
- 数据不落第三方,盘上文件保持原样(不打包、不改名)

## 三、技术选型(推荐)

| 部分 | 选型 | 理由 |
|---|---|---|
| PC 服务端 | **Go** | 编译成单个 exe,双击即用,无运行时依赖;标准库 `net/http` 原生支持 Range 请求(视频拖动进度条的命脉);将来可原样搬到真 NAS / 树莓派 |
| Web UI(PC 管理端) | Vue 3 + Vite,构建产物嵌入 exe | 一套 UI,PC 浏览器和手机浏览器都能用;M1 阶段手机直接用浏览器就能体验全部功能 |
| Android 端 | **Kotlin + Media3/ExoPlayer + OkHttp/Retrofit** | 视频硬解最稳、格式支持最广;上传走 OkHttp 分块 |
| 数据 | SQLite | 零配置,单文件,够用 |
| 转码(可选) | ffmpeg | 仅对不兼容格式按需转码 |

备选:
- PC 端 Python(FastAPI):开发最快,但分发要装环境,不适合"家用双击即用"
- Android 用 Flutter:UI 一套代码,但播放器/后台上传要写平台通道,坑多;不推荐一期用

## 四、功能模块

### PC 服务端
1. **存储池管理**:把 `H:\`、`I:\` 注册为存储库(每个库可设 可读可写 / 只读),路径映射
2. **文件 API**:目录列表、上传(分块 + 断点续传)、下载、Range 流式播放
3. **媒体库**:后台扫描建 SQLite 索引;图片缩略图;视频用 ffmpeg 抽帧生成封面
4. **局域网发现**:mDNS 广播 + 首次配对二维码(扫码免输 IP)
5. **鉴权**:配对 token,防局域网蹭网
6. **Web 管理界面**:库管理、文件管理、上传、设置

### Android 端
1. **发现/连接**:mDNS 自动发现或扫码配对
2. **文件浏览**:目录树 + 缩略图网格
3. **上传**:系统文件选择 / 相册多选;后台上传队列、断点续传
4. **播放**:ExoPlayer 在线播放——拖进度、倍速、外挂 srt 字幕
5. (二期)相册自动后台备份

## 五、关键设计点

- **视频拖动进度条** = HTTP `Range: bytes=...` → 服务端必须返回 `206 Partial Content`,这是播放体验的核心
- **格式兼容策略**:
  - MP4(H.264 + AAC):全设备直放
  - MKV / H.265(HEVC):大多数手机硬解可直放
  - AVI / FLV / RMVB 等老格式:点播时 ffmpeg 按需转码(或提示不支持)
- **大文件上传**:5MB 分块,失败只重传该块
- **机械盘够用**:单盘顺序读 100MB/s+,支持 3~4 人同时看 1080p 无压力
- **PC 不能睡眠**:手机访问时 PC 得醒着 → 电源计划设"接通电源时不睡眠"(二期可做 Wake-on-LAN 唤醒)
- **Windows 防火墙**:首次运行提示放行端口(默认 52700)

## 六、里程碑

| 阶段 | 内容 | 效果 |
|---|---|---|
| **M1** ✅ 2026-11-24 | Go 服务端:文件 API + Range 播放 + Web UI(嵌入 exe) | 手机浏览器打开即能传、能看 —— 核心体验最快落地 |
| **M2** ✅ 2026-11-24 | Android 端:连接/浏览/上传/观看(APK 已构建) | 像云盘一样用 |
| **M3** ✅ 2026-11-24 | 播放器集成 ✅、缩略图 ✅、ffmpeg 按需转码 ✅、媒体库索引(SQLite)✅ | 完整影院体验 |
| **M4** ✅ 2026-11-24 | 相册自动备份 ✅；Tailscale 远程访问 ✅；WebDAV 挂载 ✅（服务端 v1.6.0） | 进阶 |

### M1 完成情况(2026-11-24)
- Go 1.27.4 服务端,单文件 `server/linNas-server.exe`(UI 已嵌入)
- 存储库注册(H:\ I:\ 等,可只读)、虚拟路径防穿越
- Range→206 流式播放(字节级校验通过)、分块上传 5MB + 断点续传(SHA256 一致)
- Web UI:深色手机友好,添加库/浏览/上传/改名/删除/播放器区
- 详见 server/README.md

### M1.1 体验增强(2026-11-24,v0.2.0)
- **一键启动**:`server/启动linNas.bat` 双击即启动服务并自动开浏览器(已在运行则仅开页面);`停止linNas.bat` 一键停止;exe 自身也支持启动后自动开浏览器(`-no-browser` 可关)
- **UI 重做**:深墨绿+琥珀电源灯主题、桌面端侧栏布局、移动端单列
- **功能补齐**:当前目录搜索、按名称/大小/时间排序、列表/网格切换、拖拽上传、上传队列面板(进度/速度)、视频/音频/图片页内灯箱播放

### M1.2 文件管理与隐私(2026-11-24,v0.5.0)
- **目录选择器**:添加/编辑存储库时可浏览服务器盘符与目录,无需手写路径;已有存储库可重新「设置」(改名/换路径/只读)
- **文件操作**:多选后「移动到…」「复制/剪切→粘贴」;支持把文件/文件夹拖到文件夹或面包屑上移动
- **网格缩略图**:图片 / 视频缩略图接入网格与列表(ffmpeg)
- **隐私保险箱**:服务端隐藏目录 + 密码门禁(非加密),解锁后可上传/下载/预览/导出/删除
- **主题与设置**:深/浅/跟随系统主题;系统设置(版本/端口/数据目录/ffmpeg 状态、索引统计与重建、访问口令、保险箱管理)

### M1.3 媒体/搜索/设备/保险箱增强(2026-11-24,v0.6.0)
- **媒体库**:侧栏入口,视频/图片/音乐分类网格(缩略图墙,200 项/页,可加载更多)
- **全局搜索**:跨库文件名搜索(走 SQLite 索引),结果可预览/定位
- **设备管理**:记录并展示访问过的设备(UA 识别浏览器/系统、IP、次数、首次/最近),可清空
- **保险箱**:内部视频/音频/图片可直接播放(Range 拖动);支持整文件夹批量导入 + 文件浏览多选「存入保险箱」
- **拖拽修复**:文件/文件夹可直接拖到同目录文件夹或面包屑上完成移动(改用自维护拖拽状态)

### M1.4 稳定性与体验(2026-11-24,v0.9.0)
- **开机自启**:`安装开机自启.bat` 在启动文件夹写 `linNas-autostart.vbs`,登录后静默起服务(最小化、不弹浏览器);`卸载开机自启.bat` 取消;已实测
- **存储库排除子目录**:Library 增加 `Exclude []string`;浏览/索引/缩略图/raw 均隐藏并 403;库改动后自动重建索引
- **回收站**:删除改为移动到 `<库>\.linNasTrash`(带 `.meta/<id>.json` 记录原位置),侧栏「回收站」支持恢复/彻底删除/清空,跨盘走复制后删除
- **上传队列增强**:多文件排队 + 最多 3 并发;单任务暂停/继续(断点续传)、取消(清理 `.part`)、失败自动重试(≤3 次指数退避)后可手动重试/移除;`.part` 不再出现在目录与索引
- 新增接口:`POST /api/upload/abort`;`GET /api/trash`、`POST /api/trash/restore|delete|empty`

### M1.5 效率与传输(2026-11-24,v1.2.0)
- **A1 排序/筛选**:工具栏加类型筛选(全部/仅文件夹/视频/图片/音乐/压缩包/其他)+ 升降序切换(文件夹恒在前),选择持久化 localStorage
- **A2 快捷键**:Del/Backspace 删除、F2 改名、Ctrl+C/X/V、Ctrl+A 全选、Enter 打开、Esc 取消(输入框/弹窗/播放器内不拦截)
- **A3 底部状态栏**:共 N 项(文件夹/文件数)+ 文件合计大小 + 已选统计;「计算本目录总大小」按钮调用新接口 `GET /api/size?vpath=`(递归统计,跳过保险箱/回收站/排除目录)
- **B1 跨库拖拽**:可拖到侧栏其它存储库/面包屑/目录行;Ctrl+拖拽=复制,否则=移动;dragVPaths 延迟 400ms 清理并在 drop 时直接读取,避免浏览器清空 dataTransfer 导致丢失
- **B2 操作进度条**:移动/复制/批量删除走 runBatch 逐项队列,面板显示 i/N 与进度条(大目录不确定进度),可随时取消
- 临时测试库 lib3/lib4 与 _abtest/_abdest 已清理

### M2 完成情况(2026-11-24,v0.2.0)
- 构建环境:Android 命令行 SDK(D:\android-sdk,platform 34 + build-tools 34)、Gradle 8.7(D:\gradle-8.7)、JDK 21
- APK:`release/linNas-0.2.0-debug.apk`(com.linnas.app,minSdk 24 / target 34,8.9MB,debug 签名)
- 功能:连接(地址+口令)、存储库/目录浏览、面包屑、多选上传(前台服务队列、5MB 分块断点续传、通知进度)、系统播放器在线看片(拖进度)、新建文件夹
- 服务端首启自动注册 `H:\`、`I:\`(实际测试:H盘 3.3TB/I盘 2.7TB 已自动识别,真实视频 SSE.mp4 走 206 分段正常)
- 详细说明:`android/README.md`

### M3 完成情况(2026-11-24,v0.3.0)
- **服务端 media.go**:`GET /api/thumb`(ffmpeg 抽帧/缩放,JPEG 落盘缓存)、`GET /api/stream`(native 格式转发 raw 支持 Range 拖动;AVI/RMVB/FLV/WMV/MPG/VOB 等走 ffmpeg 边转边发 frag-mp4,`&t=秒` 指定起始)。实测:视频缩略图 200/7.9KB、图片缩略图 200/8.8KB、183MB mp4 转码首包 ftyp 正确、native 直出 200+Accept-Ranges
- **Android**:新增 `PlayerActivity`(ExoPlayer 内置播放页,倍速 0.75~2x、保持亮屏;原生格式走 raw 可拖动、老格式走 stream);列表新增图片/视频缩略图(Coil 加载 /api/thumb);APK `release/linNas-0.3.0-debug.apk`(9.4MB)
- 未做:媒体库索引(SQLite)、字幕、mDNS 自动发现

### M3.1 媒体库索引完成(2026-11-24,v0.4.0)
- **服务端 index.go**:SQLite(modernc 纯 Go 驱动,无 CGO/单 exe 不受影响)。表 `files(lib_id,path,parent,name,is_dir,size,mod_time,kind)` + 索引 `linnas-data/index.db`(WAL 模式)。启动后后台全量扫描(6 小时内不重扫,结果存 meta)。
- **新接口**:`GET /api/search?q=` 文件名搜索、`GET /api/media?type=video|image|audio&sort=&offset=&limit=` 媒体库分页、`GET /api/index/status`、`POST /api/reindex`。
- **实测**:244,998 个文件全量扫描 1 分 15 秒(video 16959 / image 6917 / audio 174 / 目录 39897);搜索与分页正常。
- **Android v0.4.0**:新增「媒体库」页(视频/图片/音乐聚合,点视频直进播放器);APK `release/linNas-0.4.0-debug.apk`(9.4MB)。

### M4 完成情况(2026-11-24,v0.5.0)
- **相册自动备份(Android)**:`backup/PhotoBackup.kt` 用 WorkManager 每 6 小时(可限 WiFi)增量扫描 MediaStore 新照片/视频,分块上传到指定目录(默认 `/lib1/手机备份`),按 DATE_ADDED 逐条前移断点、失败自动重试;主界面「备份」按钮配置(启用/目录/仅WiFi/立即备份/状态),Android 13+ 自动申请媒体权限。
- APK:`release/linNas-0.5.0-debug.apk`(9.8MB)。
- M4 原待办现已完成：Tailscale 远程访问在服务端 v1.5.0 提供，WebDAV 在 v1.6.0 提供。

### M4.1 App 界面重构 + 局域网自动发现(2026-11-24,v0.6.0)
- **界面重做**:底部 4 tab(文件/媒体/传输/设置)+ 顶部连接状态胶囊 + 搜索框 + 面包屑 chip;深墨蓝(#0B0D11)配冷蓝主色(#6EA8FE),薄荷/琥珀/红作状态色;18 个手绘描边矢量图标(统一 1.7 描边,替换原 emoji);缩略图列表、媒体两列网格、空状态与加载态。
- **局域网自动发现**(解决"要手输 IP"):`Discovery.kt` 并发扫描本机 /24 网段 52700 端口(250ms 超时、64 并发),命中 `/api/health` 即列出;首次打开 App 自动弹「发现服务器」,点一下连接并记住;保留「手动输入」兜底。
- APK:`release/linNas-0.6.0-debug.apk`(9.9MB);设计预览:`shots/redesign-4screens.png`。

### M4.2 主题 / 排序 / 磁盘(v0.7.0)
- **主题系统**:自定义主题属性(lInk/lSurface/lSurfaceHigh/lOutline/lText/lTextDim/lPrimary/lOnPrimary),4 套主题 `Theme.LinNas`(墨蓝)/`.Green`/`.Amber`/`.Mono`,布局与形状全部改用 `?attr/` 引用 → 设置页切换即时生效(recreate)。
- **文件页**:列表/网格双视图切换;按 名称/大小/时间 排序 + 升降序(目录恒在前),偏好持久化。
- **顶部磁盘剩余**:接入 `/api/disks`,进库显示「H盘 · 剩余 1.8 TB」,根目录显示「共剩余 3.5 TB」。
- APK:`release/linNas-0.7.0-debug.apk`(9.8MB);主题预览:`shots/theme-4screens.png`。

### M4.3 功能对齐 PC 端 + 传输进度(v0.8.0)
- **全局搜索**:文件页「全库」开关,调 `/api/search` 全库搜(300ms 防抖)。
- **移动 / 复制**:行菜单「移动到…/复制到…」+ 目录选择弹窗,调 `/api/move`、`/api/copy`。
- **设置页服务器区**:改访问口令(`/api/settings` POST)、系统信息(GET)、设备管理(`/api/devices` + `/clear`)、保险箱入口。
- **保险箱**:`VaultActivity`(status/setup/unlock/lock/ls/upload/raw/delete),密码解锁后浏览、上传、播放、下载、删除。
- **传输进度**:上传改为实时回调(逐块上报百分比,通知栏 + 传输页);下载用系统 DownloadManager(通知栏 + 传输页进度)。
- APK:`release/linNas-0.8.0-debug.apk`(9.96MB)。

### M4.4 修闪退 + 播放优化 + 加库/保险箱导入导出(v0.9.0)
- **闪退加固**:主题里 `android:statusBarColor`/`textColorPrimary` 不再引用自定义主题属性;`bg_tile/bg_pill/bg_card` 改用固定颜色(排除 drawable 主题属性导致的 inflate 崩溃);新增 `LinNasApp`(全局 UncaughtExceptionHandler → 落盘 `crash.txt` + 打开 `CrashActivity` 显示可复制堆栈)。
- **播放优化**:±10s 快进/快退、画面比例(适应/裁剪/拉伸)、倍速、解码错误提示、拔耳机自动暂停、保持亮屏。
- **手机端添加存储库**:设置 → 服务器 → 添加存储库,浏览电脑盘符/目录(`/api/fs/dirs`)→ 命名 → `POST /api/libs`。
- **保险箱导入/导出**:导入(从存储库选文件移入)、导出(条目「⋯」→ 导出到存储库…),接口 `/api/vault/import|export`。
- APK:`release/linNas-0.9.0-debug.apk`(9.9MB)。

### M4.5 闪退定位 + 播放体验包(v0.9.1 / v1.0.0)
- **闪退根因(由用户堆栈定位)**:主题中 Material 颜色属性写成字面色值(`#E9EDF5`)而非 `@color` 引用 → `resolveAttribute` 得到 resourceId=0 → `NavigationBarMenuView.createDefaultColorStateList` 取 0 号资源 → `Resources$NotFoundException: Resource ID #0x0` → BottomNavigationView inflate 失败 → 启动闪退。修复:为 4 套主题各建 8 个颜色资源(`t_blue/green/amber/mono_*`),主题内全部改 `@color` 引用;底部导航补 `itemIconTint`。
- **播放体验包(v1.0.0)**:
  - 横竖屏:顶部「旋转」按钮锁横屏/恢复自动旋转,全屏黑底 + 隐藏系统栏氛围。
  - 手势:双击左/右 ±10s、上下滑音量/亮度、水平拖动进度、长按 2.0x、单击显隐控件。
  - 续播记忆:`PlaybackStore.kt`(SharedPreferences,按 vpath 记录 position/duration;>10s 且距结尾 >30s 才记录),文件行副标题显示「继续观看 · 还剩约 x 分钟」。
  - 字幕:同目录同名 `.srt/.ass/.ssa/.vtt` 自动挂载(MediaItem.SubtitleConfiguration)。
- APK:`release/linNas-1.0.0-debug.apk`(9.9MB)。

### M5 零配置连接:mDNS 广播 + 二维码配对(2026-11-24,v1.1.0)
目标(用户原话):「做 ②:mDNS 服务端广播 + 二维码配对,让「连接」彻底不用手输 IP(App 打开自动弹服务器列表 → 或扫电脑上的二维码)」。
- **服务端**:
  - `mdns.go` 广播 `_linnas._tcp.`(A/PTR/SRV/TXT,TTL 120;组播 224.0.0.251:5353 + 各网卡子网广播,30s 一次)。
  - `pair.go`:UDP **52701** 局域网发现(每 3s 广播配对 JSON;收到 `LINNAS_DISCOVER` 时按「同网段优先」单播应答)、`GET /api/pair`(JSON)、`GET /api/pair/qr`(PNG 二维码,size 128~1024)、`GET /pair`(深色配对页:大二维码 + 三步提示 + 三个可达地址,推荐地址带标签)。
  - 配对 URI `linnas://pair?ip=&port=&name=linNas[&token=]`,设了口令会带上 `token`,App 扫码后自动鉴权。
  - 主地址选择用 rank:192.168.* > 10.* > 172.16-31 > 其它,避免误选 VPN 网卡。
  - 说明:Windows 上 5353 常被系统 DNS Client 占用(mDNS 只能单向广播),故叠加 UDP 52701 与网段扫描三路并行,广播在 Windows 上可靠。
- **Android(v1.1.0,versionCode 2)**:
  - `Discovery.kt` 重写:`listenBeacon()`(UDP 52701 广播探测+收包)、`mdns()`(委托 `MdnsDiscovery`)、`scan()`(原 /24 扫描)三路并行去重合并;`parsePair()` 支持 `linnas://pair?...`、`http(s)://host:port`、裸 `ip[:port]`。
  - 新增 `MdnsDiscovery.kt`:NsdManager 发现 `_linnas._tcp.` 并 resolveService 拿 host/port。
  - `MainActivity`:发现弹窗加「扫码配对」按钮(ZXing `ScanContract`),扫到配对二维码直接连;自动发现列表点选即连(不再硬编码 52700)。
  - 新增依赖 `com.journeyapps:zxing-android-embedded:4.3.0`,清单加 `CAMERA` 权限。
- 实测:`/api/pair` 主地址正确为 192.168.10.2;`/pair` 配对页截图 `shots/pair-page.png`;`/api/pair/qr?size=320` 返回合法 PNG;UDP `LINNAS_DISCOVER` 收到 JSON 回包。
- APK:`release/linNas-1.1.0-debug.apk`(10.7MB)。

## 七、已确认决策

1. PC 端语言:**Go** ✅
2. Android 端:**Kotlin 原生** ✅
3. 相册自动备份:**二期** ✅
4. 视频格式:**混合都有** → ffmpeg 按需转码列入必选组件 ✅

### ffmpeg 转码子方案(因混合格式而必选)
- 服务端启动时探测 ffmpeg 是否可用;没有则仅提示"该格式不支持在线播放"
- 点播 AVI/RMVB/FLV 等老格式时:`ffmpeg -i 输入 -c:v libx264 -preset veryfast -c:a aac -f mp4 -movflags frag_keyframe+empty_moov pipe:1` 边转边流(无需落盘等待转完)
- 转码会话按 (文件, 清晰度) 缓存,热门片可转码后缓存到临时目录复用

## M5.1 PC 端二维码入口 + 扫码竖屏(2026-11-24,v1.1.1)
- Web UI(server/ui/dist/index.html + pp.js)侧栏「更多」新增 #btn-pair「手机连接」,点击弹出 #dlg-pair:内含 /api/pair/qr?size=320&ip= 二维码与 info.urls 地址列表。UI 由 //go:embed ui 内嵌,改后必须重新 go build 才生效。
- Android v1.1.1:扫码 Activity 锁定竖屏(manifest 覆盖 + setOrientationLocked(true))。

### M6 详情统计 / 可编辑路径 / 播放增强(2026-11-24,v1.3.0)
- **server/info.go**:GET /api/info?vpath= —— 文件/文件夹详情;目录 filepath.WalkDir 递归统计(跳过 vault/trash/excluded);媒体文件 ffprobe(-show_format -show_streams)解析分辨率/时长/帧率/编码/码率;findFFprobe() 定位 ffprobe.exe。
- **server/subtitle.go**:GET /api/subtitle?vpath=(同目录同名或 <stem>.<lang>.srt 字幕列表)、GET /api/subtitle/content?vpath=(srt/sub→WebVTT,ass/ssa→ffmpeg -f webvtt,去 BOM/CRLF/索引行)。
- **server/transcode.go**:streamVideo 转码结果按 sha1(abs|mtime|size|v1) 缓存到 DataDir/transcode/<key>.mp4,二次请求 http.ServeContent 命中(支持 Range 拖动);&t= 时禁缓存。
- **前端**:可编辑路径(#btn-editpath/#in-path + resolveUserPath,支持虚拟路径与 Windows 绝对路径映射);详情弹窗 #dlg-info(infoRow/fmtDur/showInfo);播放器重写——播放列表(buildPlaylist/playItem/renderPlaylist)、字幕(track 用 trackEl.track.mode 切换)、倍速/画中画/全屏/上一下一;媒体库按文件夹分组 #media-group;文件与媒体行加“详情”按钮。
- **实测**:字幕 clip1.srt→WebVTT 显示“你好,这是第一行字幕”(track mode=showing, activeCues=1);播放列表 2 项自动连播(1/2→2/2);转码缓存首播 502ms→二次 22ms(23×);媒体分组 /lib1/anime、/lib2、/lib3;详情 1920×1080 / 23:41 / 23.98fps / h264 / aac / 1610kbps;可编辑路径 H:\NAS\anime→/lib1/anime。go vet 干净、go build OK、health 1.3.0。

### M7 安全:多用户 / 自动锁定 / 分享链接 / 操作日志(2026-11-25,v1.4.0)
- **server/security.go**:User/UsersDoc/Session/authStore —— hashPassword(pbkdf2 风格 120000 轮 + 每用户 salt,常量时间比较);CreateUser/SetPassword/SetRole/DeleteUser(禁删最后管理员)/List(脱敏)/Login(token=randHex 24)/Get(闲置超 idle 即失效=自动锁定)/Logout/CheckUserPassword/MultiUser/UserCount;AuditEntry+auditLog(DataDir/audit.log,上限 5000,启动读入,追加写,仅 POST/DELETE 记录,含 IP 与状态码);Share+shareStore(DataDir/shares.json,过期即失效);公开处理器 handleShareInfo / handleShareRaw(支持 Range/206、?dl=1) / handleSharePage(内嵌深色单页,可浏览目录、内联播放、密码校验)。
- **api.go**:Server 增加 auth/audit/shares;中间件链改为 withToken → auditLog → recordDevice → logRequests;withToken 重写 —— 放行 health/auth/status/auth/login,无用户且无全局口令时开放访问,接受全局口令(视为 admin)或会话 token,401「未登录或登录已过期」;新增 authorize(sess,r) 角色守卫(admin 全通;user 禁 /api/users*、/api/audit*、改 settings、改 libs、devices/clear;viewer 禁一切写操作,除 /api/auth* 与保险箱解锁/上锁);分享相关路由挂在 mux 上免鉴权。
- **config.go**:新增 IdleLockMinutes(默认 30);settings GET 增 idleLockMinutes/multiUser/userCount,POST 可改 1–1440 分钟并同步 s.auth.idle。
- **前端**:顶栏账号菜单(#btn-user/#usermenu:当前身份、改密码、锁定、退出);登录框 #dlg-login 与锁屏 #lockscreen;api() 的 401 分流 —— 多用户时按是否有会话决定弹锁屏或登录框,单用户口令模式沿用 askToken;用户管理 #dlg-users(新建/改角色/改密码/删除);操作日志 #view-audit;分享管理 #view-shares + 文件行“分享链接”按钮 + #dlg-share/#dlg-share-done;系统设置“安全与账号”组;角色感知 UI(updateRoleUI/isAdmin/canWrite,只读用户隐藏上传/新建/改名/删除/设置等)。
- **实测**:admin 登录显示“管理员”;只读用户 GET /api/ls 200、POST /api/mkdir 403、GET /api/users 403、界面只剩“下载/详情”;会话失效自动弹锁屏,密码解锁成功;分享页免登录可看文件,密码分享先校验密码;目录分享返回 entries;操作日志显示登录/用户管理/分享记录。go vet 干净、go build OK、health 1.4.0。
- **数据文件**:linnas-data/users.json、shares.json、audit.log(删除 users.json 并重启可回到免登录模式)。

### M8 远程访问与版本发布整理(2026-11-25)
- **Windows 服务端 v1.5.0**:Web UI 增加远程访问入口；读取本机 Tailscale 状态、展示可访问地址，并可管理 Tailscale Serve；支持以托盘模式后台运行和开机自启。
- **Android 客户端 v1.4.0 (versionCode 6)**:包含多用户登录、分享链接、回收站、离线缓存与播放器画中画(P0/P1),以及操作日志页、播放列表连播、跨库搜索结果打开子目录字幕上下文修复(P2)等能力。
- **Android 客户端 v1.5.0 (versionCode 7)**:体验改版——上传改为 56dp 普通 FAB;播放器重做为市面主流样式(顶部渐变条返回/标题/列表,中部播放键与快进快退,底部进度条+时间+倍速/画面/旋转,控制层 3.5s 自动隐藏、点击视频切换,保留手势与画中画);设置页改为分区卡片+图标行布局;主题扩为 6 套(墨蓝/墨绿/琥珀/纯黑 + 云白/暖沙两套浅色),修复切换主题跳回文件页;相册备份可选媒体库内目标文件夹、下载保存位置可选;保险箱改为顶栏+锁定态/空状态+底部动作条的界面。
- 服务端与 Android 客户端采用**独立版本号**；版本、构建产物命名和校验步骤以根目录 [RELEASING.md](RELEASING.md) 为准。
- 截至 v1.5.0，WebDAV 仍在待办；后续在 v1.6.0 完成。

### M9 WebDAV、可靠性与自动化检查（2026-11-25，服务端 v1.6.0）
- **WebDAV**：按 `/dav/<库ID>/` 提供标准文件访问，继承现有账号/访问口令与存储库只读设置；viewer 只读。保险箱、回收站、排除目录、未完成上传文件及库外符号链接不可访问；删除进入回收站。
- **上传并发**：上传会话表与每会话操作加锁，阻止重复偏移写入及 init 时同名目标冲突；分块大小受限，写入失败会回滚临时文件。
- **索引**：每个存储库在单一 SQLite 事务中替换索引；目录遍历、文件元信息或数据库写入失败会回滚并保留旧索引；离线库报告部分失败且不推进完整扫描时间。
- **备份恢复**：提供数据目录备份/恢复脚本，校验 SHA-256，恢复前保留当前数据目录；媒体文件和保险箱内容需另行备份。说明见 [DATA_BACKUP.md](DATA_BACKUP.md)。
- **自动化检查**：Go 测试、竞态检测、vet、Windows 构建和 Android debug 构建接入 GitHub Actions。

### M10 Android 体验与端到端验收（2026-11-26 真机基本回归通过，Android 版本暂不变更）
- **离线下载**：改用唯一 WorkManager 任务，支持进程重启后恢复；校验 Range 起点、Content-Range、响应体长度和最终文件大小；对不支持 Range 的 200 响应从头覆盖临时文件，完成后原子改名。下载错误可重试，并检查已知文件大小所需的可用空间。
- **播放器**：增加 Media3 音频焦点处理、后台回前台的控制层恢复、播放错误重试和外部播放器入口；续播位置校验拆为可单测策略。
- **主题与无障碍**：主题选择器添加已选状态和读屏名称；播放器进度控件增加描述；浅色主题强调色调整，并新增六套主题的对比度自动检查（正文/次级文字/强调色至少 4.5:1）。
- **自动化验收**：新增下载协议单元测试、API 34 模拟器下载/续传集成测试及主题对比度与偏好测试；CI 增加 Android 单元测试和模拟器任务。真机连接真实服务端的流程见 [ANDROID_TESTING.md](ANDROID_TESTING.md)。
- **验证状态**：本地 Gradle 8.7 下载因环境无法解析 `services.gradle.org` 而未能完成构建/测试；需以 CI 模拟器结果和真实设备验收通过作为发布门槛。后续已在本地改用镜像 Gradle 8.7 完成 `:app:assembleDebug` 构建（见 M12）。2026-11-26 用户已在真机完成基本回归（单设备），未发现阻断问题；完整 Android 7 / 14 多机型矩阵列为可选补测。

### M11 索引增量更新、安全加固与多媒体增强（2026-11-25，服务端 v1.7.0）
- **索引增量更新**：新增 `Index.UpdatePaths(changes...)`，移动/复制/重命名/删除/上传/回收站恢复/保险箱导入导出/WebDAV 写入后只更新受影响子树，不再触发全库重扫（24.5 万文件全量约 75 秒）；增删库改为按库重建/清行；全量扫描进行中的增量请求自动丢弃（全量会重建）。扫描时跳过系统目录（`$RECYCLE.BIN`、`System Volume Information` 等）避免权限报错。
- **登录防爆破**：按「用户名+IP」计失败次数，连续 5 次失败锁定 30 秒起、指数退避封顶 15 分钟，锁定期间返回 `429` 与剩余秒数并记入审计日志；登录成功清零。
- **活跃会话管理**：新增 `GET /api/auth/sessions`（返回会话 ID 前 6 位、账号、角色、IP、最近活动，标记当前会话）与 `POST /api/auth/revoke`（按 ID 前缀吊销，非管理员只能吊销本人会话）；Web UI 账号菜单新增「活跃会话」面板。
- **回收站自动清理 / 转码缓存上限**：配置项 `trashRetentionDays`（默认 30，0 关闭）与 `transcodeCacheMB`（默认 2048，0 不限）；新增 `POST /api/maintenance/cleanup` 手动清理，服务启动后与每 6 小时自动执行 housekeeping（含过期保险箱会话清理）。设置页「存储与维护」可改阈值并「立即清理」。
- **文件夹 Zip 打包下载**：`GET /api/zip?vpaths=a,b&name=x.zip` 服务端 `archive/zip` 流式打包（媒体/压缩包按存储直出，其余压缩），跳过保险箱/回收站/排除目录/`.part`；浏览页工具栏与多选栏新增「打包下载」。
- **网络唤醒（Wake-on-LAN）**：`GET/POST/DELETE /api/wol` 管理主机（名称+MAC），`POST /api/wol/send` 发送 102 字节 magic packet（默认 `255.255.255.255:9`）；设置页新增「网络唤醒」分组。
- **在线音乐播放器**：Web UI「音乐」视图，渐变背景 + 旋转唱片 + 自绘进度条 + 播放列表，支持上一/下一、自动连播、拖动进度、当前曲高亮。
- **索引定时自动对账**：配置项 `indexRefreshSeconds`（默认 120 秒，0 关闭）驱动 `Index.ReconcileAll` 周期对账，发现资源管理器等外部移动/删除/重命名；启动后延迟 30 秒首次执行，全量扫描进行中自动跳过。
- **存储空间分析**：`GET /api/storage/summary`、`GET /api/storage/large` 与重复文件 `GET /api/storage/duplicates`、`POST /api/storage/duplicates/scan`——库占用概览、大文件 Top N、重复文件候选（按大小分组 + 抽样 SHA-256，后台异步单飞、结果缓存）；删除复用 `POST /api/delete` 移入回收站，必须人工确认。
- **媒体库排除（不在媒体库展示）**：`POST /api/libs/media-hide` 增删路径，`Library.MediaHide` 记录相对库根、正斜杠的路径；这些路径仍索引、仍可在文件浏览/搜索访问，只是不出现在媒体库（视频/图片/音乐）。
- **发布后修补（2026-11-26）**：媒体库自动分页（每页 500，进入页面自动拉完剩余页，不再需要手动点「加载更多」）；回收站新增 `GET /api/trash/thumb`，网格与列表显示图片/视频缩略图；「不在媒体库展示」按钮改用睁眼/闭眼图标并即时显示「已隐藏」徽标。
- **测试**：新增索引增量、登录防爆破/会话、维护清理、Zip、WoL、存储空间分析、媒体库排除单元测试；`go vet`/`go test`/竞态检测全绿。

### M12 Android 端对齐服务端 v1.7.0（2026-11-26，Android v1.7.0）
- **活跃会话管理**：新增「活跃会话」页，列出在线会话（账号、角色、当前会话标记、ID 前缀、IP、最近活动），可吊销指定会话（非管理员仅限本人）；顶栏「改密码」修改本人登录密码（至少 4 位）。
- **用户管理**（管理员）：新增「用户管理」页，列出服务端用户，支持新建（用户名/密码/角色）、编辑角色或重置密码、删除。
- **维护与存储设置**：新增「维护」页，展示回收站保留天数、转码缓存上限与媒体索引状态（扫描中/已索引文件数/上次扫描/视频·图片·音乐统计）；可修改保留天数与缓存上限、立即清理（回收站+转码缓存，显示清理项与释放空间）、重建媒体索引。
- **网络唤醒（WoL）**：新增「网络唤醒」页，管理唤醒主机（名称+MAC），点按发送 magic packet，长按删除，顶栏添加主机。
- **存储库管理**：新增「存储库」页，编辑存储库名称/路径/只读/启用，删除存储库（说明为 App 侧移除）。
- **Zip 打包下载**：文件操作菜单新增「打包下载(ZIP)」，对文件或文件夹调用 `GET /api/zip` 打包下载。
- **保险箱增强**：新增修改保险箱密码（改后需重新解锁）、当前目录新建文件夹、子目录浏览与「返回上级」。
- **登录防爆破提示**：服务端返回 `429` 时读取 `Retry-After`，登录页显示剩余秒数倒计时，结束后允许重试。
- **设置页**：系统信息补充多用户（用户数）、空闲锁屏、回收站保留、转码缓存上限、保险箱是否已初始化；服务器管理卡片新增会话/用户/网络唤醒/维护/存储库五个入口（用户管理与维护/存储库仅管理员可见）。
- **版本**：Android `versionCode` 8 / `versionName` 1.7.0；产物 `release/linNas-1.7.0-debug.apk`（附 SHA-256）。
- **验证**：本地镜像 Gradle 8.7 + JDK 21 执行 `:app:compileDebugKotlin` 与 `:app:assembleDebug` 均 BUILD SUCCESSFUL（仅原有 Mdns/OfflineDownloader 弃用告警）。

### M13 Android v1.8.0 体验升级（2026-11-26，Android v1.8.0）
- **统一传输中心**：新增前台 `TransferService` + `TransferStore`，把「下载 / ZIP 打包下载」统一入队；支持暂停 / 继续（断点续传）、失败重试与失败原因展示、取消；下载目录改用 SAF（`ACTION_OPEN_DOCUMENT_TREE`）由用户在手机上自选，修复离线缓存与 ZIP 打包下载卡死/失败问题。
- **文件多选 + 批量操作**：文件页支持多选，批量删除 / 移动 / 复制 / 打包下载(ZIP) / 离线缓存。
- **生物识别**：新增 `BiometricGate`（API 28+ 平台 `BiometricPrompt`、24-27 `FingerprintManager`、无硬件时降级锁屏凭据；敏感串用 Android Keystore AES/GCM 加密存储）。保险箱指纹解锁与生物识别登录；Manifest 补 `USE_BIOMETRIC` / `USE_FINGERPRINT`。
- **版本检查**：新增 `VersionCheckActivity`，展示本机 App 版本与服务端版本，并可「检查更新」；服务端新增 `GET /api/app/version`、`GET /api/app/apk`（来源 `release/android-version.json` 与 APK，带防路径穿越）。
- **收藏 / 最近访问 / 搜索历史 + 筛选**：新增 `FavoritesStore`（收藏、最近访问 30 条、搜索历史 12 条，本机 SharedPreferences）；文件操作菜单可收藏/取消收藏，顶部菜单可查看「收藏与最近」「搜索历史」；文件页新增按类型 / 大小 / 时间筛选（`FileFilters` 纯函数），排序按钮显示当前筛选状态。
- **播放器 B 站式重做**：竖屏详情 + 选集抽屉；横屏全屏手势（左/右双击快退快进、上下滑音量/亮度、水平拖动进度、长按倍速）；倍速面板（0.5–2.0）、字幕轨切换、睡眠定时（10/20/30/60 分钟或播完本集）。
- **媒体库按文件夹分组**：`MediaGridAdapter` 支持分组标题（`item_media_header`），媒体按父文件夹分组显示（库根用库名）。
- **设置页重排**：按「连接 / 账号 / 服务器管理 / 外观 / 关于」分组收敛，新增版本检查入口与 App/服务端版本展示。
- **全局视觉打磨**：新增 `DESIGN.md` 设计规范（颜色/字号/间距/圆角/组件样式/页面骨架，纯 XML + 代码动态视图，无 Compose）；补充 `dimens.xml` / `styles.xml` 与一批 `bg_*`、`ic_player_*` drawable，统一卡片/按钮/播放器控件外观。
- **版本**：Android `versionCode` 9 / `versionName` 1.8.0；产物 `release/linNas-1.8.0-debug.apk`（附 SHA-256）。
- **验证**：本地镜像 Gradle 8.7 + JDK 21 执行 `:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug` 均 BUILD SUCCESSFUL；Android 单测 11 个通过（新增 `FileFiltersTest` 3 个），Go 侧新增 `app_update_test.go` 并通过 `go test ./...`。

### M13.1 Android v1.8.1 真机反馈修复与体验打磨（2026-11-26，Android v1.8.1）
- **媒体库双模式**：媒体页新增「全部 / 文件夹」切换。全部模式平铺展示所有媒体；文件夹模式像文件页一样按目录浏览（目录树由媒体路径推导），点文件夹进入，返回键逐级返回。
- **横屏播放**：竖屏视频右上角新增「全屏」按钮，点击切横屏沉浸（隐藏系统栏、显示 B 站式控制层）；横屏顶栏新增「退出全屏」按钮；返回键在横屏时先回竖屏。
- **设置页主题色块**：色块改为「主题底色 + 主色条」双色预览，6 套主题一眼可辨（此前深色主题都显示为近黑方块）。
- **面包屑导航**：改用主题色（浅色主题不再发白看不清），长路径自动省略（maxWidth + 省略号）。
- **三点菜单**：文件页/媒体页的 ⋯ 菜单改为圆角底部动作面板（BottomSheetDialog），替代默认 PopupMenu。
- **离线缓存与下载**：缓存完成后隐藏满格进度条，显示「已缓存 · 仅本应用可播」；新增「打开」直接播放与「另存」到下载目录（SAF）；下载完成显示保存目录与文件名。
- **添加存储库**：修复磁盘列表显示裸 JSON 的问题，改为「磁盘 C: · 剩余 x」。
- **相册备份去重**：改为逐文件指纹（名称|大小|创建时间）记录，已备份文件跳过不再重复上传；查询用 `DATE_ADDED >=` 避免同秒遗漏；设置页显示「已记录 N 个」，点备份状态可查看已备份清单 / 清空记录。
- **版本**：Android `versionCode` 10 / `versionName` 1.8.1；产物 `release/linNas-1.8.1-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（11 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.2 Android v1.8.2 传输中心提速与播放器/二级页体验升级（2026-11-26，Android v1.8.2）
- **传输速度显示**：新增 `SpeedMeter`（滑动窗口估算瞬时速度），上传 / 离线缓存 / 下载三区都显示「百分比 · 1.2 MB/s」；新增 `SpeedMeterTest` 单测。
- **修复进度条闪烁**：传输页此前每秒重建整页并重播进度动画，完成后仍显示满格进度条。现在进度条只在数值真正变化时更新、完成后隐藏，完成项显示「已保存 · 目录 · 文件名」。
- **缓存等同下载**：离线缓存完成后自动把成品复制一份到用户在「下载保存位置」里选的目录（SAF），传输页显示「已缓存 · 已保存到「下载目录」」，应用内副本仍保留用于离线播放。
- **应用内更新**：版本检查页支持直接下载更新包（带进度与速度），下载完成后调起系统安装器，不再跳转浏览器。
- **横屏右侧选集**：横屏点「选集」从右侧滑出 300dp 面板（竖屏仍是底部弹层），选集条目增加封面缩略图（服务端 ffmpeg 抽帧）。
- **播放器更多菜单**：右上角三点改为图标网格面板（倍速 / 字幕 / 画中画 / 定时 / 画面比例 / 锁定横屏 / 外部播放器），画中画与睡眠定时从底部按钮行移入该面板，底部不再拥挤。
- **长按加速**：长按视频临时 2.0 倍速，松手恢复原倍速；长按时不再误触发单击暂停。
- **滑动进度优化**：竖屏也支持左右滑动调进度，提示同时给出目标时间与偏移秒数（如 `→ 12:34  (+35 秒)`）。
- **二级页视觉重做**：`ScreenScaffold` 统一顶栏（返回 + 标题 + 动作 + 分隔线）、卡片行、分组小标题、空状态与加载态；会话管理 / 用户管理 / 维护与索引 / 网络唤醒 / 存储库管理 / 操作日志 / 分享管理 / 版本检查统一风格，新增 `ScreenUi` 辅助与 `screen_dimens.xml`。
- **版本**：Android `versionCode` 11 / `versionName` 1.8.2；产物 `release/linNas-1.8.2-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.3 Android v1.8.3 浅色主题、播放页与二级弹窗重做（2026-11-26，Android v1.8.3）
- **浅色主题「月白」**：原第 4 套主题「纯黑」改为浅色「月白」（ink `#F1F3F7` / surface `#FFFFFF` / 主色 `#3D6BA6`），`Theme.LinNas.Mono` 改继承 `Theme.LinNas.Light` 并补 `windowLightStatusBar`；六套主题变为「3 深 + 3 浅」。`DESIGN.md` 同步。
- **播放器按 B 站风格重做**：横屏底部第二行改为「左侧分辨率标签（4K/1080P/720P/自动）+ 右侧紧凑文字按钮 画面比例 › 字幕 › 倍速 › 选集」，选集按钮仅横屏且多集时出现。
- **选集面板**：背景改为 90% 半透明圆角面板（`bg_player_panel`，不再纯色），四边留白；支持四种关闭方式——右上关闭按钮、点击面板外的半透明遮罩、选中某一集、返回键；面板打开期间暂停控制层自动隐藏；宽度随屏幕自适应（屏宽 42%，上限 300dp）。
- **三点「更多」弹窗**：重写为深色半透明三段式且不占满屏——①六个图标入口（倍速 / 字幕 / 画面比例 / 画中画 / 外部播放器 / 锁定横屏）②「定时关闭」分段（不开启 / 15:00 / 30:00 / 60:00 / 自定义）③开关行（自动连播、循环播放 单集/列表/不循环、镜像翻转）；状态写入 `linnas_ui` 并真实生效（播完按 定时 → 单集循环 → 列表循环 → 自动连播 顺序处理）。镜像翻转通过 `PlayerView.scaleX = -1f` 实现。
- **竖屏详情可收起**：标题下新增「简介 · 展开/收起」折叠条，默认收起；进度行、操作行与选集区始终可见；折叠状态跨页面记忆（`player_detail_collapsed`）。
- **二级弹窗统一重做**：新增主题化底部弹窗组件 `Sheet`（拖动条 / 标题 / 滚动区 / 底部按钮，语义色取色，支持列表行、勾选、输入、分段、键值、危险操作），替换原先的裸 `AlertDialog`：系统信息、设备管理、手动输入地址、改访问口令、添加存储库、文件详情、重命名、新建文件夹、删除确认、目录选择，以及收藏/历史/筛选/批量/备份记录/下载目录/发现服务器等。
- **传输页删除**：已下载与已缓存条目支持「删除 / 移除」（`tclear` 按钮与长按卡片），会同时删除本机文件（`content://` 走 `DocumentsContract.deleteDocument`，FileProvider 的 `content://` 与 `file://` 回退到真实路径删除）与 SAF 副本，并二次确认；服务器文件不受影响。
- **传输效率**：新增全局共享 `TransferHttp` 客户端（连接池 8 / 5 分钟），`Dispatcher` 并发上限 4/域、统一连接 10s·读写 60s 超时、开启失败重连；读缓冲统一 256KB，写盘改 `BufferedOutputStream`（保留 `fd.sync()`）；私有目录下载保留 Range 断点续传。
- **版本**：Android `versionCode` 12 / `versionName` 1.8.3；产物 `release/linNas-1.8.3-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.4 Android v1.8.4 更新安装修复、登录持久化与弹窗收尾（2026-11-26，Android v1.8.4）
- **应用内更新修复（Android 16）**：补 `REQUEST_INSTALL_PACKAGES` 权限；安装前用 `PackageManager.canRequestPackageInstalls()` 校验，未授权时弹 Sheet 引导到「安装未知应用」设置页，返回后经 `ActivityResultLauncher` 只自动重试一次；安装 Intent 一次性 `setDataAndType` + `FLAG_GRANT_READ_URI_PERMISSION`（去掉 `FLAG_ACTIVITY_NEW_TASK`）；安装前校验文件存在、大小 > 1MB、头 2 字节为 `PK`，坏包直接删除并提示重下。
- **网页更新入口**：版本检查页新增「在浏览器中打开更新页」（`ServerConfig.baseUrl`）、更新包下载直链展示（`<baseUrl>/api/app/apk`）与「复制下载直链」。
- **登录页美化**：品牌区（72dp 圆底图标 + LinNAS + 副标题）+ 圆角卡片（服务器地址行 + 指纹主按钮 + 描边用户名/密码输入框 + 自动登录复选框 + 全宽登录按钮 + 错误提示）+ 底部「跳过登录，只用访问口令进入」，全部用主题语义色。
- **指纹登录不再重复登录**：会话持久化到加密存储（`sess_token` 用 Android Keystore AES/GCM，`sess_user/role/base_url` 明文元数据），`Prefs.init` 时执行 `SessionRestore.tryRestore`，进程重启/App 更新后自动恢复；旧版明文 `session_token` 与旧 `bio_session_*` 键自动迁移；登录成功即保存（复选框语义改为「下次自动登录，不再询问指纹」）；有会话但未开自动登录时，进登录页会自动弹一次指纹。
- **播放器「更多」面板更透明**：新增 `bg_player_more` 纵向渐变半透明（顶部约 62% → 底部约 78%）+ 18dp 圆角，BottomSheet 遮罩由默认 0.32 降到 0.12，面板不再显成实心灰块。
- **主题**：「月白」与「云白」过近，第 4 套改为浅绿灰「青瓷」（ink `#E8F1ED` / 主色 `#2E7D6B`），内部 key 仍为 `mono` 以免破坏已存偏好。
- **剩余二级页弹窗收尾**：`TrashActivity` / `VaultActivity` / `SharesActivity` / `UserAdminActivity` / `SessionsActivity` / `WolActivity` / `LibraryAdminActivity` / `AuditActivity` / `MaintenanceActivity` 共 26 处裸 `AlertDialog` / `PopupMenu` 全部改为 `Sheet`（确认类用危险色按钮、表单类用 `input` + 内联校验、动作菜单用 `row` 列表、多行详情用 `keyValue`）；全仓 `setMessage(` 仅剩 `PlayerActivity.showErrorDialog` 的播放错误提示。
- **版本**：Android `versionCode` 13 / `versionName` 1.8.4；产物 `release/linNas-1.8.4-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`（强制全量重编）、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。
### M13.5 Android v1.8.5 播放器面板重做、生物识别开关与选中态修复（2026-11-26，Android v1.8.5）
- **播放器「更多」重排**：图标入口由「一行六个挤在一起」改为 3 列 × 2 行网格（图标 26dp / 文字 12sp 单行 / 单元最小宽 64dp / 间距 12dp / 行高 64dp），分段与开关行统一 56dp 行高。
- **横屏从右侧滑出**：横屏点「更多」不再是底部弹层，改为与选集面板同款的右侧浮层（340dp、`bg_player_panel` 半透明圆角、180ms 滑入、遮罩点击关闭、返回键先关面板），竖屏仍是底部弹出；「更多」与「选集」互斥，打开一个自动关另一个。
- **修复进度条被遮挡**：横屏此前未给系统手势区留白，竖屏 `videoContainer` 固定 210dp 装不下四行控制层。现在底部两行包进 `bottomBar` 并按 `WindowInsets` 动态补手势区高度；竖屏视频区改为 `min(235dp, 屏宽*9/16)`，第二行按钮压缩到 40dp。
- **三级面板统一**：倍速、字幕、播放错误等改为同一套玻璃面板（新增 `showPlayerSheet()` 构建器、`panel_player_sheet.xml`、`bg_player_option_on.xml`、`bg_player_button.xml`），`PlayerActivity` 内 `AlertDialog` 归零。
- **主题顺序**：青瓷与云白位置交换（云白在前），并加一次性迁移 `theme_order_v2`，旧索引 3↔4 互换，已选主题不会跳变。
- **设备管理**：按 UA 判定平台（Android / 桌面电脑 / 未知），分别用手机、显示器、通用图标并显示平台名。
- **设置页图标去重**：手动输入地址、网络唤醒、改访问口令、会话管理、维护与索引、添加存储库、局域网扫描、版本检查等各自独立图标（新增 10 个 `ic_*`），同一页不再出现重复图标。
- **生物识别开关**：设置页新增「生物识别」行（`swSettingsBio`），可整体开启/关闭指纹登录与保险箱解锁（`BiometricGate.isEnabled/setEnabled`，键 `bio_enabled`）。
- **保险箱免密**：新增 `VaultSession`（5 分钟超时，`SystemClock.elapsedRealtime`），解锁后退出页面不再要求密码；App 整体进后台超过 5 分钟或进程结束才重新验证；新建保险箱后直接进入已解锁态；指纹可用时优先指纹解锁。
- **登录页**：指纹按钮按 `isEnabled && canUse && 有已保存会话` 显示；去除系统 Autofill 的黄色高亮（`importantForAutofill="no"` + 透明底色 + 选区用主色）。
- **存储库管理**：显示容量「已用 X · 共 Y」（取自 `GET /api/disks` 与库所在卷匹配，卷级口径；无数据时显示「容量未知」）。
- **选中态修复**：`Sheet.segmented` 改为「主色实底 + 白色加粗文字」的分段控件（槽底 `lSurfaceHigh`），勾选行加 1dp 主色描边；六套主题对比度均 ≥4.5:1。
- **版本**：Android `versionCode` 14 / `versionName` 1.8.5；产物 `release/linNas-1.8.5-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.6 Android v1.8.6 指纹登录修复、保险箱免勾选与设备图标修复（2026-11-27，Android v1.8.6）
用户反馈（m01530）：保险箱指纹解锁还要自己勾选；登录界面没有指纹解锁、指纹通过后也没直接登录进去；登录页「切换服务器」二级页未改造；设备管理里已连接设备图标依旧不可见。
- **指纹入口始终可见**：`setupBioEntry` 改为「设置里开启 + 硬件可用」就无条件显示指纹按钮，不再要求 `Prefs.sessionMeta() != null`。根因：加密会话是 v1.8.4 才引入的，之前登录过的老用户升级后没有加密会话，旧逻辑把按钮 `GONE` 掉，看起来像指纹功能丢了。无会话时按钮为引导态并显示提示「首次使用请先用账号密码登录一次」。
- **修复「验证通过却进不去」**：新增 `enterWithSession()` 作为指纹/会话进入的唯一出口——先写回 `ServerConfig`，再 `client.authMe()` 向服务端校验，通过才 `setResult(RESULT_OK)+finish()`。旧版把可能已过期的 token 直接带回主界面，主界面第一发请求 401 又弹回登录页。过期（`ApiException` / `username` 为空）→ `Prefs.clearSessionMeta()+clearSession()` 并提示「登录已过期,请重新输入密码」；纯网络异常**不清会话**（只提示重试），避免临时离线让用户永久丢失指纹登录。
- **切换服务器改为 Sheet**：新增 `showSwitchServerSheet()` 替换裸 `AlertDialog`——URI 键盘输入框（缺协议自动补 `http://`，`ServerConfig.baseUrl` setter 即持久化）、回车提交、`ic_scan`「扫描局域网」行动态列出 `Discovery.discover`（UDP 广播 + mDNS + 网段扫描）发现的服务器（点击回填不关弹层）、`scanning` 防重入与 `sheet.isShowing()` 守卫迟到回调。布局新增 `tvBioHint` 引导副标题，原有 12 个 id 全保留。
- **保险箱去掉指纹勾选**：删除 `activity_vault.xml` 的 `cbVaultBio`/`btnVaultBio` 与 `VaultActivity` 的 `cbVaultBioListener/refreshBio/doBioUnlock`；`promptUnlock()` 改为 `if (bioAvailable()) bioUnlockThenFallBack() else showPasswordSheet()`；密码解锁成功后只要 `bioAvailable()` 就无条件 `BiometricGate.saveSecret(KEY_VAULT_PW, pw)`（保存失败只影响下次指纹入口，不影响本次解锁）。`lockNow()` 不再清密钥；仅「修改保险箱密码」与 `onResume` 检测到设置里关闭生物识别时才 `clearSecret`。
- **设备图标不可见的真因**：`bg_sheet_tile.xml` 的 layer-list `<item android:alpha="0.12">` 在真机上不生效，圆底被渲染成**不透明 `lPrimary` 实心圆**，行图标又被 tint 成同一 `lPrimary` → 同色隐形（真机像素采样：圆盘中心 `#2E7D6B` = `t_mono_primary`）。修复：`ScreenUi.iconTile` 改用 `GradientDrawable` 把 12% alpha 直接乘进 ARGB 生成圆底（`tileTintBackground`），图标加 `imageTintList` + `setColorFilter(SRC_IN)` 双保险（`tintGlyph`），图标尺寸限制为 `min(icon_lg, tileSize-8dp)`；`Sheet.iconTile` 图标 20→24dp、`tileSizeDp=34`。该修复同时覆盖所有用 `Sheet.row` 的弹窗。
- **设备图标重做**：`ic_device_phone` / `ic_device_desktop` 改为实心形状 + `fillType="evenOdd"` 镂空（纯描边在 24dp 下会发虚），新建 `ic_device_unknown`（加粗问号 + 实心圆点）。`devicePlatform()`：空 UA → 未知设备；`python|curl|wget|go-http|libwww|java/` → 「命令行/脚本」；去掉易误判的 `pc` 子串；`okhttp` 保留在 Android 侧（本 App 自身 UA 就是 okhttp，归脚本会把本机标错）。
- **版本**：Android `versionCode` 15 / `versionName` 1.8.6；产物 `release/linNas-1.8.6-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin --rerun-tasks`（全量重编，仅既有 deprecation 警告）、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.7 Android v1.8.7 更新包版本核对、指纹登录可用与应用内图片浏览（2026-11-27，Android v1.8.7）
用户反馈（m01654）：应用内更新下载完却装成上个版本的包；退出登录后点指纹登录报「还没有保存登录信息」；媒体页点图片不能浏览（只能外部打开）；添加存储库选完磁盘没有后续且列表闪烁。
- **更新包装成旧版**：`VersionCheckActivity.downloadApk()` 原固定写 `cacheDir/linnas-update.apk`（跨版本同名，安装器可能复用同一 content URI 的旧包），`installApk()` 也从不校验包内版本。改为按版本命名 `linnas-update-<code>-<name>.apk`，并新增 `readApkInfo()`（`PackageManager.getPackageArchiveInfo`）在打开安装器前校验包名与 `versionCode == expected.code`（服务端未给 code 时退化为比 `versionName`）；不符则删包 + `Sheet("安装包版本不符")` + 「再次检查」。安装返回后 `cleanCachedUpdateApks()` 清理旧包；未知来源授权弹窗改走 `installPermissionLauncher`，授权返回自动继续安装。
- **退出登录后指纹登录失效**：根因是 `LinNasClient.logout()` 的 `finally` 里 `Prefs.clearSession(); Prefs.clearSessionMeta()` 把身份信息全删，且从未保存过账号密码。新增 `Prefs.saveBioCredentials/loadBioCredentials/hasBioCredentials/clearBioCredentials`（用 `BiometricGate.saveSecret` 加密存 `bio_user`/`bio_pass`）与 `clearSessionToken()`（只清 token，保留身份与指纹凭据）；`logout()` 只 `clearSessionToken()`。密码登录成功且生物识别开启时保存凭据；`doBioAuth()` 指纹通过后先用 `enterWithSession(silent=true)` 试旧 token，失败再用保存的账号密码 `login()` 换新 token 进入；401 清凭据提示重登，网络异常不清凭据。
- **应用内图片浏览**：新增 `ImageActivity`（`ViewPager2` + 自绘 `ZoomableImageView`：双指 1x–5x、双击 2.5x/复原、放大时不拦截横向滑动），Coil 加载、点击切换顶/底栏、顶栏显示文件名与 `i / n`，支持保存到下载目录（SAF）、分享（FileProvider）、详情（`Sheet.keyValue`）；`AndroidManifest.xml` 注册（`exported=false`、`Theme.LinNas`）。`MainActivity` 新增 `openImageGallery()`/`openOfflineGallery()`，媒体页点图、文件页点图、离线缓存点图都进应用内浏览，不再依赖外部查看器打不开 http。
- **添加存储库逐级浏览**：`showAddLibrary()` 重做为单个 `Sheet`——顶部面包屑 + 目录行容器，`reload()` 用 `seq` 版本号 + `isShowing()` 三重校验丢弃过期回调，整体重建行容器（不再逐行异步 addView，消除闪烁）；空路径列磁盘、非空先「.. 返回上一级」再列子目录，末尾固定「✓ 就选这个目录」；名称随目录自动填（盘符根填「C盘」）；选中即 `client.addLibrary()` 并 `connectAndLoad(silent=true)`。
- **版本**：Android `versionCode` 16 / `versionName` 1.8.7；产物 `release/linNas-1.8.7-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`（仅 VersionCheckActivity 两处 `versionCode` deprecation 警告）、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.8 Android v1.8.8 服务器自定义名展示、GIF 动图与手动路径弹层统一（2026-11-27，Android v1.8.8）
配合服务端 1.8.1 新增「服务器自定义显示名」（`server/config.go` 的 `ServerName` + `Config.DisplayName()`，`GET /api/health` 已回传 `name`），手机端把名称接到各处展示，并补齐两处遗留。
- **名称替代 IP 展示**：`LinNasClient` 新增 `serverName()`（读 `/api/health` 的 `name`）与 `Prefs.setServerName(baseUrl,name)/serverNameFor(baseUrl)`（按 baseUrl 绑定缓存，换地址自动失效，明文非敏感）；`ServerConfig.host()` 与 `ServerConfig.displayLabel()`（优先「自定义名 · host」，无名字退化为 host）。`MainActivity.loadAfterAuth()` 拉取并缓存名称，`bindSettings()` 的设置页「服务器」行与 `LoginActivity` 登录页服务器栏都改用 `displayLabel()`；局域网发现选中服务器时顺便缓存其广播名。
- **图库支持 GIF 动图**：`ImageActivity` 原先交给 Coil，动图只显示首帧。新增 `isGifUrl()` 判定 + `fetchBytes()`（http 走含 token 的 URL，本地读文件）+ `decodeAnimated()`：API 28+ 用 `ImageDecoder` 解成 `AnimatedImageDrawable`，旧版本退回 `android.graphics.Movie` 的 `MovieDrawable` 逐帧绘制；缩放/双击/翻页/保存/分享逻辑不变。
- **添加存储库「手动输入路径」统一弹层**：`MainActivity.showAddLibrary()` 里该兜底入口原为裸 `AlertDialog` + 独立输入框，改为 `Sheet` + `input()` + `onEnter`（回车即进入），与其余弹层一致；至此该页面弹层全部走 `Sheet`。
- **版本**：Android `versionCode` 17 / `versionName` 1.8.8；产物 `release/linNas-1.8.8-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.10 Android v1.8.10 GIF 动图与添加存储库弹层修复（2026-11-27，Android v1.8.10）
真机反馈两个「装了新版还是没有」的问题，均定位为真实缺陷：
- **GIF 仍只显示第一帧**：`ImageActivity.decodeAnimated()` 用 `ImageDecoder` 拿到的 `AnimatedImageDrawable` **不会自动播放**，必须显式 `start()`；之前只 `setImageDrawable`，所以停在首帧。现在判定为 `AnimatedImageDrawable` 时设 `repeatCount = REPEAT_INFINITE` 并 `start()`；API 28 以下的 `Movie` 兜底路径不变。
- **「手动输入路径」看不到**：`ScreenScaffold.Sheet` 的内容区是 `WRAP_CONTENT` 的 `NestedScrollView`，且 `show()` 直接 `STATE_EXPANDED`；「添加存储库」的目录列表一多，弹层被撑得比屏幕还高，底部按钮区（手动输入路径 / 添加为存储库）被挤出屏幕。新增私有 `SheetScrollView`（`NestedScrollView` 子类，`onMeasure` 用 `AT_MOST` 把高度限制在屏幕 58%，超出则内部滚动），保证底部按钮始终可见。
- **版本**：Android `versionCode` 19 / `versionName` 1.8.10；产物 `release/linNas-1.8.10-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.15 Android v1.8.15 无选集时收起选集区（2026-11-27，Android v1.8.15）

M13.14 把选集挪成独立滚动区后，episodeArea 恒占 layout_weight=1，单集视频也会空出半屏。

- 修复：E:\linNas\android\app\src\main\java\com\linnas\app\PlayerActivity.kt 新增 private fun applyEpisodeAreaVisibility() —— episodeArea.visibility = if (!isLandscape() && playlist.isNotEmpty()) View.VISIBLE else View.GONE，并在 efreshEpisodes() 末尾调用。
- 效果：单集/无选集 → 详情区独占整屏；多集 → 上下分栏，滑动选集只动下方列表。
- 验证：:app:testDebugUnitTest 15/15、:app:assembleDebug BUILD SUCCESSFUL；产物 `release/linNas-1.8.15-debug.apk`。
### M13.14 Android v1.8.14 选集改为独立滚动区（2026-11-27，Android v1.8.14）

上一版把选集列表改成固定高度后能滑了，但它是详情 ScrollView 的一部分：往下滑选集时整页跟着动，标题与简介被顶到视频下面遮住。

- 根因：v_episodes 与标题/简介同处 contentScroll（ScrollView，:505）内，滚动方向必然共享。
- 修复：E:\linNas\android\app\src\main\res\layout\activity_player.xml 把选集段落（	v_episode_header/tn_prev_p/tn_next_p + v_episodes）移出 contentScroll，改为其兄弟 LinearLayout android:id="@+id/episodeArea"（layout_height=0dp + layout_weight=1）；v_episodes 改  dp + weight=1 + 
estedScrollingEnabled=true，自己滚自己的。contentScroll 只保留标题/简介/进度/操作。
- E:\linNas\android\app\src\main\java\com\linnas\app\PlayerActivity.kt：新增 private lateinit var episodeArea: View 并在 onCreate 绑定；efreshEpisodes() 去掉动态设高度的逻辑（布局已定高）；updateVideoSize() 横屏把 episodeArea 一起 GONE，竖屏 VISIBLE。
- 验证：:app:testDebugUnitTest 15/15、:app:assembleDebug BUILD SUCCESSFUL；产物 `release/linNas-1.8.14-debug.apk`。
### M13.13 Android v1.8.13 竖屏详情页选集列表可滚动（2026-11-27，Android v1.8.13）

v1.8.12 只修了竖屏的**底部弹层**选集，用户实际用的是详情页里的**内联**选集列表，仍然只能看到前 5 集：

- 根因：v_episodes 在 contentScroll（普通 ScrollView + illViewport）里是 layout_height=wrap_content，被测量成可见几行；同时它的 
estedScrollingEnabled=false 又拒绝自己滚动，于是第 6 集之后完全够不到。
- 修复：efreshEpisodes() 里超过 5 集时给列表设固定高度 min(屏高*0.55, dp(68)*集数) 并开启自身滚动（isNestedScrollingEnabled = true），5 集以内仍用 wrap_content；打开时 scrollToPosition(playIdx) 对齐正在播放的一集。
- 验证：:app:testDebugUnitTest 15/15、:app:assembleDebug BUILD SUCCESSFUL；产物 `release/linNas-1.8.13-debug.apk`。
### M13.12 Android v1.8.12 保险箱/指纹与播放器选集修复（2026-11-27，Android v1.8.12）

修复一组真机反馈：

- **保险箱**：解锁成功后密码输入弹层自动关闭（之前停留在已解锁页面）；指纹解锁用的保险箱密码与指纹登录凭据都**按服务器地址分开保存**——换过服务器后不再互相覆盖，新服务器首次进入以密码输入为主、输入一次后才启用指纹，换回原服务器指纹仍然可用。
- **媒体**：文件夹模式切到图片/音乐/视频时回到根目录（避免留在旧目录显示空白）；文件夹模式下选集只取**当前文件夹**的视频（不再跨目录串台）；全部模式选集仍为整个类型。
- **播放器**：镜像翻转生效（PlayerView 改 	exture_view，scaleX=-1 真正翻转画面）；竖屏选集弹层可滚动（高度按屏高 62% 封顶、skipCollapsed 展开到位），打开即滚动到正在播放的一集，播放中的条目加粗显示。

构建：:app:testDebugUnitTest 15/15、:app:assembleDebug BUILD SUCCESSFUL；产物 `release/linNas-1.8.12-debug.apk`。
### M13.11 添加存储库路径自动创建与新建文件夹（2026-11-27，服务端 1.8.3 + Android 1.8.11）
用户反馈「手动输入路径」只是跳转，服务端并不会创建目录；且希望能在添加存储库界面直接新建文件夹。本轮：
- **服务端**：`POST /api/libs`（`handleAddLib`）对不存在的绝对路径改为 `os.MkdirAll` 自动创建（非绝对路径/文件路径仍 400）；新增 `POST /api/fs/mkdir`（`handleFsMkdir`，`fsops.go`）按电脑绝对路径创建目录（MkdirAll 补建父目录、拒绝盘符根），仅管理员（`authorize` 拦截）；新增 `fsops_mkdir_test.go`，`go test ./...`/`go vet ./...` 全绿。
- **Android**：`LinNasClient.fsMkdir(path)`；「添加存储库」的「手动输入路径」改为**先在服务端创建目录再进入**（标题与副文案注明「不存在时会自动创建」，主按钮「创建并进入」，校验绝对路径）；新增**「新建文件夹」**入口，在当前浏览位置下按名称创建目录（校验非法字符），成功后刷新列表。
- **版本**：服务端 `const Version = "1.8.3"`（`server/config.go`），产物 `server/linNas-server.exe` 13,674,496B（SHA-256 `d5a18beb…467c2c0`）；Android `versionCode` 20 / `versionName` 1.8.11，产物 `release/linNas-1.8.11-debug.apk` 11,142,985B（SHA-256 `79914d7d…ac37b`）。
- **验证**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M13.9 Android v1.8.9 服务器名称与 IP 同时展示（2026-11-27，Android v1.8.9）
v1.8.8 只把「名 · host」拼成一行，用户反馈希望**名称和 IP 都要看得见**，不是用名称替换 IP。本版改为两行结构：
- **设置页「当前服务器」行**：`activity_main.xml` 新增 `setServerAddr`（次级色小字，默认 gone）；`MainActivity.bindSettings()` 里 `setServer` 显示自定义名（无名称时退回 host，都没有显示「未连接」），`setServerAddr` 单独显示 `ServerConfig.host()`（含端口），名称为空时自动隐藏该行。
- **登录页服务器栏**：`activity_login.xml` 的 `tvServer` 由 `maxLines=1` 放宽到 2，`ServerConfig.displayLabel()` 的「名 · 地址」在长名称下不被截断。
- 局域网发现列表继续显示「名 · ip」，点击连接时缓存该名称，随后设置页与登录页自动带出。
- **版本**：Android `versionCode` 18 / `versionName` 1.8.9；产物 `release/linNas-1.8.9-debug.apk`（附 SHA-256）。
- **验证**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（15 个单测）、`:app:assembleDebug` 均 BUILD SUCCESSFUL。

### M14 Web UI 总览页与侧栏分组重构（2026-11-26，服务端 1.7.0）
- **问题**：主区 `.app` 限制 `max-width:1180px` 并居中，宽屏（1586px）左侧约 200px 死区；侧栏入口全塞在「更多」里，层级混乱。
- **布局**：`.app` 改为全宽（`width:100%; max-width:none; margin:0`），侧栏固定 236px 且可纵向滚动 → 左侧死区消失；主区内容自适应。
- **侧栏分组**：新增「总览」入口；存储库标题可点击回到文件浏览；入口按 `媒体`（媒体库 / 音乐）、`存储`（存储空间 / 回收站 / 隐私保险箱）、`设备与网络`（手机连接 / 远程访问 / 设备）、`分享与管理`（分享链接 / 操作日志）四组归位，低频项仍按角色隐藏。
- **总览仪表盘**（`<section id="view-overview">`）：顶部 KPI（总容量 / 已用 / 可用 / 文件总数 / 索引文件 / 存储库）；「存储容量概览」按库显示使用条与合计；「媒体与内容统计」视频 / 图片 / 音乐 / 其他 / 文件夹计数 + 系统状态（版本、索引状态、上次索引、自动对账间隔、活跃设备、分享链接、回收站）；「快捷入口」上传 / 扫描重复文件 / 回收站 / 分享管理 / 隐私保险箱；「近期活动」读审计日志（管理员全量、普通用户仅本人）；「规划中」两张路线图卡片（内容搜索 / 自动整理、存储空间分析增强）。
- **后端**：新增 `server/overview.go` 与 `GET /api/overview`（库容量、全库统计、媒体类型计数、索引 / 回收站 / 分享 / 设备、近期活动、告警）；子查询失败只降级对应字段，不返回 500；`server/overview_test.go` 覆盖字段、索引缺失降级、非管理员过滤与告警判定。
- **告警**：可用空间 <10% / <3% 为 warn，索引未启用 / 尚未首次扫描 / 超过 `max(10min, refreshSeconds×3)` 未更新为 info，最多 5 条。
- **兼容**：`GET /api/overview` 对所有已登录角色放行；Android 客户端不受影响；版本仍为 1.7.0。
- **验证**：`go build` / `go vet` / `go test -count=1` 全绿；`node --check app.js` 通过；Playwright 1586×1102 实测无左侧死区、六项 KPI、五张卡片、两张规划卡、侧栏四组与「总览」高亮；800px 宽单列且无横向溢出。

### M15 存储空间分析增强（两批均已实现，2026-11-26）
总览页「规划中」第二张卡片的落地计划。目标：在已有概览 / 大文件 / 重复候选之上补齐**增长趋势、校验巡检、磁盘健康与空间告警**，全部复用现有 `files` 索引与 `analysis.go` 模式（后台异步 + 状态轮询），不引入第三方依赖，删除动作一律只进回收站且需人工确认。

**批次 1：趋势快照 + 磁盘健康 + 空间告警**（✅ 已完成并部署 2026-11-26）

> 状态：批次 1 已实现、测试并部署到本机 52700（服务端版本仍为 1.7.0）；新增 `server/trend.go`、`server/disk_health.go`、`server/alerts.go` 及单测 `trend_test.go` / `disk_health_test.go` / `alerts_test.go`。批次 2（校验巡检）随后完成（见下）。
- 快照表：`storage_snapshots(ts INTEGER, lib_id TEXT, bytes INTEGER, files INTEGER)`，`PRIMARY KEY(lib_id, ts)` + `CREATE INDEX idx_snap_lib ON storage_snapshots(lib_id, ts)`；在 `ScanAll` / `ReconcileAll` 完成后调用 `Index.Snapshot()`，同一小时最多一条（按 `ts/3600` 去重），保留 400 天，超出由 housekeeping 清理。
- `GET /api/storage/trend?days=30&lib=` → `{days, libs:[{libId,name,points:[{t,bytes,files,delta}]}], total:{points:[...], weekDeltaBytes}}`；无快照时返回空数组而非 500。
- `GET /api/disks/health`：Windows 侧执行 `Get-PhysicalDisk` + `Get-Volume`（`exec.Command("powershell", "-NoProfile", "-Command", ...)`），把结果缓存 5 分钟，返回 `[{drive, model, mediaType, healthStatus, operationalStatus, sizeGB, temperature?}]`；非 Windows 或命令失败返回 `{supported:false, reason}`，不影响其它接口。
- 空间告警：`Config` 新增 `spaceWarnPercent`（默认 10）、`spaceCriticalPercent`（默认 3），settings 可改（clamp 1–50 / 1–20）；`overview.go` 的 `buildOverviewAlerts` 改读这两个配置；新增 `alerts.json` 记录触发/恢复时间与已读状态，`GET /api/alerts`、`POST /api/alerts/ack`（按 id 集合标记已读）。总览告警条显示未读数并提供「全部已读」。
- UI：存储空间页新增「趋势」tab（7 / 30 / 90 天切换，SVG 面积图 + 每库合计与本周变化）；总览「存储容量概览」卡片底部加迷你 sparkline 与「较上周 +X GB」；设置页「存储与维护」增加两个阈值输入；告警条加「已读」。

**批次 2：校验巡检（integrity patrol）**（✅ 已完成并部署 2026-11-26）

> 状态：批次 2 已实现、测试并部署到本机 52700（服务端版本仍为 1.7.0）；新增 `server/patrol.go` 与 `server/patrol_test.go`，`index.go` 增加 `file_sig` 表；签名带 `quick:` / `deep:` 前缀，切换模式不会误报「内容已变化」。同时 `GET /api/disks/health` 支持 `?lib=` 按存储库过滤磁盘。
- 新表：`file_sig(lib_id TEXT, path TEXT, size INTEGER, mod_time INTEGER, sig TEXT, sig_at INTEGER, PRIMARY KEY(lib_id, path))`。
- `server/patrol.go`：`patrolState{running, mode, startedAt, done, total, changed[], missing[], failed[], cancel}`；quick 模式 = 抽样哈希（大小 + 首尾各 64KB 的 SHA-256，与 `file_sig` 中上次签名比对），deep 模式 = 全文件 SHA-256 并限速（约 20MB/s，令牌桶）；两者都单飞、可取消、进度可轮询。
- API：`POST /api/storage/patrol/start?mode=quick|deep&lib=`、`GET /api/storage/patrol/status`、`POST /api/storage/patrol/cancel`、`GET /api/storage/patrol/last`（最近 20 次摘要，落 `patrol.json`）。
- 语义边界：巡检**只报告不修改**——「内容变更」提示可能是外部编辑，「文件缺失」仅提示可用「重新扫描索引」刷新，绝不自动删除。
- UI：存储空间页新增「巡检」tab：开始（快/深）、进度、取消、结果三组（内容变更 / 文件缺失 / 读取失败）每条可跳转到所在目录；「上次巡检」时间与耗时。
- 验收：≥1GB 目录 quick 模式在分钟级完成且可取消；人为改动一个文件内容后 quick 巡检能报出该文件；deep 模式 CPU/IO 不占满（限速生效）。

### M16 内容搜索与自动整理（批次 3、批次 4 均已交付）
总览页「规划中」第一张卡片的落地计划。两项能力共用「后台抽取/执行 + 预览 + 记录 + 可撤销」的骨架，故合并为一个大里程碑、分两批交付。

**批次 3：正文索引与内容搜索**（✅ 已完成并部署 2026-11-26）

> 状态：批次 3 已实现、测试并部署到本机 52700（服务端版本仍为 1.7.0）。新增 `server/content.go` 与 `server/content_test.go`；`index.go` 增加 `content` / `content_meta` 表与扫描/对账完成后的异步回调；`GET /api/search/content`、`GET /api/content/status`；文本编码 UTF-8 优先、失败按 GB18030 兜底（`golang.org/x/text`，兼容 GBK 文档），PDF 未索引。UI 搜索页新增「文件名 / 内容 / 全部」范围切换、内容片段高亮与索引状态提示。同时磁盘健康默认范围改为只显示承载已添加存储库的磁盘（`lib=all` 才返回全部，`lib=<库ID>` 仍按库过滤）。
- 支持类型（v1，仅标准库）：`.txt .md .log .csv .json .xml .html .htm .srt .ass .vtt` 直读；`.docx .xlsx .pptx` 用 `archive/zip` + `encoding/xml` 抽取 `word/document.xml`、`xl/sharedStrings.xml`、`ppt/slides/*.xml` 的文本；PDF 暂不支持（标准库无解析器，需引依赖 → 留作 v2 选项，UI 明确标注「PDF 未索引」）。
- 限制：单文件 ≤ 2MB、抽取文本截断至 1MB 入库；跳过保险箱、回收站、`MediaHide` 无关（照常索引）与二进制内容；编码按 UTF-8 优先、失败按 GB18030 兜底。
- 表：`content(lib_id TEXT, path TEXT, mtime INTEGER, size INTEGER, text TEXT, indexed_at INTEGER, PRIMARY KEY(lib_id, path))`；`content_meta(k,v)` 存最近一次抽取批次信息。
- 抽取 worker：索引扫描/对账完成后按 (mtime,size) 差异增量入队；并发 2、单文件 300ms 软超时、可取消；`GET /api/content/status`（queued/running/done/skipped/failed、最近完成时间）。
- 检索：`GET /api/search/content?q=&lib=&limit=50` → `{hits:[{libId,path,name,size,modTime,snippets:[{text,start,len}]}], total, truncated}`；SQL 用 `LIKE '%q%'`（少量文档足够），命中片段取前后 40 字并在前端高亮。
- UI：顶部搜索框加范围切换「文件名 / 内容 / 全部」；内容命中按文件聚合展示片段，点击定位到文件浏览并高亮该文件；无结果时提示「仅索引文本类文档，PDF 未支持」。
- 验收：放 20 个 txt/md/docx/xlsx 后索引完成，搜词能命中 docx 段落；对 5 万文件库不出现长时间卡顿（抽取可暂停/取消）。

**批次 4：自动整理规则（预览 → 执行 → 记录 → 撤销）**（✅ 已完成并部署 2026-11-27）

> 状态：批次 4 已实现、测试并部署到本机 52700（服务端版本已随 M16 完成升至 1.8.0）。新增 `server/organize.go` 与 `server/organize_test.go`；`api.go` 注册 10 条 `/api/organize/*` 路由并在 `authorize` 中限定管理端（非 GET 一律拒绝非 admin），`Server` 增加 `organize *organizeManager`。存储：规则 `DataDir/organize.json`，作业 `DataDir/organize/jobs.json`（保留最近 50），日志 `DataDir/organize/journal-<jobID>.jsonl`。UI 存储空间页新增「自动整理」页签（规则列表 / 编辑对话框 / 预览前 50 条 / 执行确认 / 进度 / 作业历史与撤销）。真实环境端到端验收：6 个测试文件按扩展名规则预览=执行数，执行后索引即时可见新路径，撤销后完全回原位（详见下方验收记录）。
- 规则模型：`organizeRule{id, name, enabled, libId, match{matchPathPrefix, exts[], nameContains, kind, minSizeMB, maxSizeMB, olderThanDays}, action{moveTo, groupBy:"ext|kind|year-month|none"}}`，存 `organize.json`；`GET/POST/PUT/DELETE /api/organize/rules`（admin only）。
- 预览：`POST /api/organize/preview` 只读计算计划 → `{items:[{from,to,reason}], skipped:[{from,reason}], conflicts, totalBytes}`，绝不落盘。
- 执行：`POST /api/organize/run` 逐条移动，目标同名自动加 ` (1)`、跨库/只读库/保险箱/回收站/排除目录一律跳过，单次上限 5000 个文件；每步写 `organize/journal-<jobID>.jsonl`（from/to/ts/ok/err），完成后调 `Index.UpdatePaths` 增量刷新索引。
- 撤销：`POST /api/organize/undo`（按 jobID 倒序回搬；目标被占用则跳过并逐条报告）；`GET /api/organize/jobs` 列最近 50 个作业（时间、规则名、移动数、失败数、是否可撤销）。
- 调度：v1 仅手动触发（避免夜间批量搬动数据），规则里预留 `intervalHours` 字段但默认 0、UI 置灰标注「v2 支持定时」。
- UI：新增「自动整理」页（侧栏「存储」组）：规则列表 + 编辑抽屉（匹配条件 / 目标目录 / 分组方式 + 「预览前 50 条」）、执行前确认弹窗（影响文件数、预计移动字节、跳过原因统计）、作业记录表（含「撤销」按钮与结果明细）。
- 安全：全部接口 admin only；执行不删除任何文件；撤销失败项逐条列出而不是静默忽略。
- 验收：造 200 个散落文件 + 一条「按扩展名归类」规则，预览与实际执行数量一致；执行后索引即时可见新路径；撤销后文件与索引完全回到原位；中途取消不产生半移动文件（每条移动本身原子）。
- 验收记录（2026-11-27，本机 52700 真实数据）：用 `I:\NAS\.orgtest` 下 6 个 txt + 1 个 jpg 建规则「lib2 / 前缀 `.orgtest` / 扩展名 `.txt` → `归档`」，预览返回 6 条且与执行数一致（jpg 不在规则内，正确留原地）；执行后 6 个文件落到 `I:\NAS\归档\` 且 `/api/search` 立即返回新路径；撤销后 6 个文件与索引全部回原位；作业表记 6 移动 0 失败，撤销后整理作业不再提供「撤销」。测试数据、规则与作业日志已清理。

**依赖与顺序**：批次 1 → 批次 2（复用 housekeeping 与后台任务骨架）→ 批次 3（抽取 worker 与批次 2 的限速/进度骨架同构）→ 批次 4（复用 `UpdatePaths` 与回收站/保险箱边界判断）。每批独立可发布、独立补测试与文档，全部完成后版本升 1.8.0（已完成：2026-11-27 服务端由 1.7.0 升到 **1.8.0**，`server/config.go` 的 `const Version`，并同步 README / PLAN / RELEASING / CHANGELOG）。

### M17 界面与体验改进（2026-11-27，服务端 1.8.1）
> 状态：已实现、测试并部署到本机 52700。用户反馈 8 项，逐项落地。
- **磁盘健康容量**：`GET /api/disks/health` 在物理磁盘信息外追加 `Win32_LogicalDisk`（DriveType=3）卷容量，条目新增 `totalGB` / `freeGB`（`attachVolumes` 按盘符大小写不敏感映射，1<<30 取整），修复同一 6TB 物理盘上 H:/I: 都显示 5589 GB 的问题；UI 磁盘健康卡改为「型号 · 介质 · 健康」+ 容量进度条（可用占比）+「已用 X% · 可用 A / B」。
- **双击启动**：无命令行参数且非从已有终端启动（或 `LINNAS_QUIET=1`）时，隐藏控制台窗口、自动打开浏览器并驻留托盘；日志改写入 `<DataDir>/linnas.log`（UTF-8）；`launchedFromTerminal()`（`GetConsoleProcessList`）避免把用户终端一起隐藏；mDNS 实例名改用 `cfg.DisplayName()`。
- **图片上一张 / 下一张**：`buildPlaylist` 图片单独成组（`kind==="image"`），左右按钮与 `ArrowLeft/ArrowRight` 在同组图片间切换；非视频隐藏倍速与画中画。
- **音乐音量记忆**：`linnas_music_vol` 存本机（首次默认 70%），`applyMusicVolume()` 统一应用到音频元素、滑块与百分比，进入播放器即恢复。
- **音乐播放器增强**：均衡器律动（7 条，播放时 `.on`）、睡眠定时（0/15/30/60 分钟，到点暂停）、静音、收藏筛选（`linnas_music_favs`）、列表扩展名徽标。
- **服务器自定义名称**：`Config.ServerName`（`serverName`）+ `Config.DisplayName()`（空回退 LinNAS）；`/api/health` 返回 `name`，`/api/settings` 可读写（trim + 40 字上限，仅管理员）；UI 应用到标题、品牌、锁屏与扫码说明。
- **移除总览「规划中」占位卡**（M14–M16 功能均已交付）。
- **侧边栏选中态**：`.lib-item.active` 改用左侧 3px 竖条（`::before`），不再被圆角挤压。
- **验证**：`go test ./...` / `go vet` / `go build` 全绿；`node --check app.js` 通过；隔离实例（独立 DataDir 与端口）实测磁盘健康容量、服务器改名、图片上一张/下一张与左右键、音乐音量恢复 35%、设置页服务器名称保存、总览无占位卡、侧栏选中竖条；`LINNAS_QUIET=1` 启动验证控制台隐藏且写入 `linnas.log`；已构建并部署到本机 52700。

### M18 播放器与登录体验改进（2026-11-27，服务端 1.8.2）
> 状态：已实现、测试并部署到本机 52700。用户 4 项反馈，逐项落地。
- **三态播放模式**：`#music-mode` 单键循环 `列表播放(all) → 单曲循环(one) → 随机播放(shuffle)`，图标 ⥁/⥂/⤨，状态存 `localStorage:linnas_music_mode`；移除原 `#music-repeat` 按钮与 `music-shuffle` 引用；`musicStep` / `ended` / `syncMusicToggles` 统一按 `musicState.mode` 判断。
- **睡眠定时可视化**：`#music-sleep-left` 显示 `🌙 mm:ss` 剩余并每秒刷新；菜单 15/30/45/60/自定义/关闭，自定义校验 1–600 分钟，到点暂停并 toast。
- **侧栏行高修复**：根因是 `.sidebar` flex 列下 `.lib-list` 被 `flex-shrink` 压缩且自身 `overflow:auto`；改为 `flex:0 0 auto; overflow:visible`，`.lib-item{min-height:40px}`，选中/悬停竖条 `top:6px;bottom:6px` 贯穿整行。
- **登录会话持久化**：`authStore` 增加 `sessions.json`（`loadSessions` / `saveSessionsLocked`，0600，60s 限流落盘），Login/Logout/Revoke/DeleteUser/闲置清理时同步；重启后恢复会话（跳过已删/禁用用户），停机时间不计入闲置超时；新增 `server/security_session_test.go`。
- **验证**：`go test -count=1 ./...` ok、`go vet` 干净、`node --check app.js` 通过；隔离实例实测三态循环、睡眠倒计时与自定义校验、侧栏无裁剪；已重建部署 52700（`/api/health` = 1.8.2）。
- **部署后小修（同日，随 1.8.3 发布）**：睡眠倒计时徽标从控制按钮行移到播放器右上角（.music-box 相对定位 + .music-sleep-left 绝对定位 top:12px/right:14px，悬浮显示不挤压按钮）；手机连接页热点名称/密码输入框改纵向卡片式（圆角 10px、聚焦 accent 光圈）。
`node --check` 通过，已重建部署 52700。

### M19 开机自启指向旧版本修复（2026-11-27，服务端 1.8.4）
> 状态：已修复、测试并部署到本机 52700。用户反馈「开机自启动后，托盘里打开的还是 1.7.0 版本」。
- **根因**：启动文件夹 `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\linNas-autostart.vbs` 里写的是绝对路径 `"E:\linNas\server\linNas.exe" -tray`（1.7.0 时代的文件名），后续构建产物改名为 `linNas-server.exe` 后脚本从未更新 → 每次登录都拉起旧 exe；旧 exe 又占住 52700，新版启动时绑定失败且（托盘模式）没有控制台可看，用户只看到托盘里还是旧版本。
- **修复**：`server/autostart.go` 拆出纯函数 `autostartScript` / `autostartExeFromScript` / `autostartTargetAt` / `autostartSetAt`，新增 `samePath`、`autostartStaleTarget`、`autostartHeal`；`main.go` 启动时若已启用自启且脚本指向别处，就用当前 exe 重写并记日志「开机自启原指向旧版本 X，已更新为 Y」。
- **可观测**：`GET /api/autostart` 增加 `target`（脚本当前指向）与 `stale`（是否指向别的 exe）；`POST /api/autostart` 也回 `target`。
- **端口占用**：`net.Listen` 失败且处于托盘 / 静默启动时改为弹窗提示「已有一个 linNas 在运行（可能是升级前的旧版本），请先在托盘退出或结束旧进程」，不再无声退出。
- **测试**：新增 `server/autostart_test.go`——`TestAutostartScriptRoundTrip`、`TestAutostartExeFromScriptLegacy`（解析旧版 `linNas.exe` 脚本）、`TestAutostartExeFromScriptInvalid`、`TestSamePath`、`TestAutostartSetAtAndTarget`（开启 / 改写 / 关闭）、`TestAutostartSetAtRejectsEmpty`。
- **验证**：把启动脚本改回旧路径后启动新 exe → 脚本被改写为 `linNas-server.exe`、日志出现自愈记录、`/api/autostart` 返回 `stale:false`；本机旧 `linNas.exe` 已改名 `linNas.exe.old-1.7.0.bak`，52700 现由 `linNas-server.exe` 提供服务（`/api/health` = 1.8.4）；`go test ./...` / `go vet` / `node --check` 全绿。

### M22 跨设备播放进度与分享链接增强（2026-11-27，服务端 1.8.5）
> 状态：已实现、测试通过；版本由 1.8.4 升至 **1.8.5**。

**跨设备播放进度（BL-013）**
- **服务端**：新增 `server/playback.go`——`playbackStore` 按账号保存到 `DataDir/playback.json`（`.tmp` 写入后 `os.Rename` 原子替换）；`playbackUserKey` 按 `UserID`（多用户）或 `guest`（开放模式）隔离；每账号上限 1000 条，进度 <3 秒或距结尾 <3 秒自动删除；`GET /api/playback?vpath=`、`POST /api/playback`、`GET /api/playback/recent?limit=`；`authorize` 放行 `/api/playback*`（viewer 也可读写自己的进度）。
- **Web**：`openPreviewSrc` 打开时取「服务端 / 本地 localStorage 中较新且可续播」的位置；播放中每 10s 回写，`pause`、关闭播放器时强制回写，`ended` 上报 0 清除；仅同步 `/libN/...` 虚拟路径。
- **Android**：`LinNasClient.getPlayback/putPlayback`（秒 ↔ 毫秒换算）；`PlayerActivity` 启动时远端恢复（远端更大才 seek）、播放中每 15s、暂停 / `onStop` / `onDestroy` / 播放失败时同步，播完上报 0。
- **测试**：`server/playback_test.go`（账号隔离、持久化、边界删除、validPath、handlers）。

**分享链接增强（BL-017）**
- **数据模型**：`Share` 新增 `MaxDownloads`（0=不限）/ `OneTime` / `Disabled` / `DisabledReason` 与统计字段 `Downloads` / `LastDownloadAt` / `Visits` / `LastVisitAt` / `LastVisitIP`。
- **shareStore**：新增 `GetState`（ok / missing / expired / disabled，`Get` 对非 ok 返回 nil）、`RecordVisit(id, ip)`、`ConsumeDownload(id) (ok, remaining)`（`dl=1` 下载前校验并计数；单次链接或达到上限后 `Disabled=true`，reason「单次链接已使用」/「下载次数已达上限」）。
- **接口**：`handleShareInfo` / `handleShareRaw` 按 GetState 区分 404（过期/不存在）与 403（已失效，附原因）；`handleSharesDelete` 支持 `?ids=a,b,c` 批量撤销（权限沿用 admin 或创建者，返回 `{ok, deleted, denied}`）；`List()` 返回统计字段；`X-Downloads-Remaining` 响应头。
- **UI**：创建对话框加「下载次数上限」「单次链接」；管理列表行内显示「单次链接 / 可下载 N/M 次」「访问 N 次 · 最近 …」「已失效」徽标与勾选框，「撤销所选」批量操作；分享页显示剩余下载次数。
- **测试**：`server/share_enhance_test.go` 7 个单测（下载上限、单次、统计、批量撤销、raw 403、过期、splitIDs）；隔离实例端到端实测：2 次下载后第 3 次 403，列表显示 disabled:true / downloads:2 / remaining:0 / visits:1。
- **验证**：`go test ./...` / `go vet` / `node --check` 全绿；版本升 1.8.5 并同步 README / RELEASING / CHANGELOG / AGENTS / ARCHITECTURE / DEVELOPMENT_STATUS / TASK_HISTORY / ROADMAP_BACKLOG。

### M20 手机端侧边栏（2026-11-27，服务端 1.8.5）
> 状态：已实现、浏览器实测通过并部署到本机 52700。用户反馈「手机登录网页看不见侧边栏」。
- **根因**：`server/ui/dist/style.css:122-132` `.sidebar { display: none; … }` 默认隐藏，只有 `@media (min-width: 900px) { .sidebar { display: flex } }`（style.css:579-584）显示 → 手机（<900px）完全没有导航入口。
- **实现**：
  - `server/ui/dist/index.html:14` 顶栏新增汉堡按钮 `<button class="btn icon-btn nav-toggle" id="btn-nav" title="菜单" aria-label="打开菜单" aria-expanded="false" aria-controls="sidebar">`；`index.html:40` 加 `<div class="nav-mask" id="nav-mask" hidden></div>`。
  - `server/ui/dist/style.css`：`.nav-toggle`/`.nav-mask` 默认 `display:none`；`@media (max-width: 899px)` 内侧栏改为 `position:fixed` 抽屉（`width:min(84vw,300px)`、`transform:translateX(-102%)`、`.sidebar.open { transform:none }`），遮罩 `position:fixed; inset:56px 0 0; background:rgba(0,0,0,.48)` + `backdrop-filter:blur(2px)` + `body.nav-open { overflow:hidden }`。
  - `server/ui/dist/app.js`（`showTrash()` 之后）：`NAV_MOBILE_MAX=899`、`navIsMobile()`、`navSetOpen(open)`、`navClose()`、`navToggle()`；`#btn-nav` 切换、`#nav-mask` 点击收起、`#sidebar` 委托点击任一条目后自动收起、Escape 关闭、`resize` 到宽屏自动复位。
- **实测**：390×844 移动视口下——点 `#btn-nav` 抽屉打开（transform→none、遮罩显示、body 锁滚动、按钮图标变 ×）；点侧栏条目 / 遮罩 / 按 ESC 均自动收起；切到 1440×900 宽屏——汉堡按钮隐藏、侧栏恢复 sticky 常驻、抽屉状态清空。截图 `shots/v1.8.5-mobile-sidebar.png`。
- **部署**：`go build` 后停旧实例换包，`/api/health`=1.8.5，线上 `/ui/*` 校验 `nav-toggle`/`sidebar.open`/`btn-nav`/`navSetOpen` 全部命中。