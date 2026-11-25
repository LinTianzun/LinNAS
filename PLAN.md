# LinNAS — 本地云盘/NAS 方案与版本路线图

> 当前组件版本（2026-11-25）：Windows 服务端 **1.6.0**；Android 客户端 **1.4.0**（`versionCode` 6）。发布流程见根目录 [RELEASING.md](RELEASING.md)。

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
- 服务端与 Android 客户端采用**独立版本号**；版本、构建产物命名和校验步骤以根目录 [RELEASING.md](RELEASING.md) 为准。
- 截至 v1.5.0，WebDAV 仍在待办；后续在 v1.6.0 完成。

### M9 WebDAV、可靠性与自动化检查（2026-11-25，服务端 v1.6.0）
- **WebDAV**：按 `/dav/<库ID>/` 提供标准文件访问，继承现有账号/访问口令与存储库只读设置；viewer 只读。保险箱、回收站、排除目录、未完成上传文件及库外符号链接不可访问；删除进入回收站。
- **上传并发**：上传会话表与每会话操作加锁，阻止重复偏移写入及 init 时同名目标冲突；分块大小受限，写入失败会回滚临时文件。
- **索引**：每个存储库在单一 SQLite 事务中替换索引；目录遍历、文件元信息或数据库写入失败会回滚并保留旧索引；离线库报告部分失败且不推进完整扫描时间。
- **备份恢复**：提供数据目录备份/恢复脚本，校验 SHA-256，恢复前保留当前数据目录；媒体文件和保险箱内容需另行备份。说明见 [DATA_BACKUP.md](DATA_BACKUP.md)。
- **自动化检查**：Go 测试、竞态检测、vet、Windows 构建和 Android debug 构建接入 GitHub Actions。