# linNas — 本地云盘

家用 NAS:PC 插外置机械硬盘(H:\、I:\),手机/浏览器在同一局域网内上传、浏览、在线看视频。

方案与里程碑见 [PLAN.md](../PLAN.md)。

## 一键启动(推荐)

双击 **`server/启动linNas.bat`** 即可:自动启动服务端,并自动在浏览器打开管理界面。

- 服务已在运行时,再次双击只会打开网页,不会启动第二个实例
- 停止服务:双击 **`server/停止linNas.bat`**
- 也可以直接双击 `linNas-server.exe`:启动后会自动打开浏览器(带 `-no-browser` 参数则不打开)

> 首次运行时 Windows 防火墙会弹窗,请允许「专用网络」。

## 快速开始

1. 双击 `server/启动linNas.bat`
2. 浏览器会自动打开 `http://127.0.0.1:52700/ui/`(没自动打开就手动访问)
3. 「添加目录」注册 `H:\NAS`、`I:\NAS`(可设只读)
4. 手机连同一 WiFi,浏览器访问启动日志里打印的 `http://<电脑IP>:52700/`

## Web UI 功能(v0.5.0)

- **主页**:存储库总览,容量条、剩余空间、只读/离线状态;每个库可「设置」(改名/换路径/只读)或移除
- **添加目录**:点「选择…」用内置**服务器目录选择器**(盘符 + 逐级目录),无需手写路径
- **文件浏览**:面包屑导航、当前目录搜索、按名称/大小/时间排序、列表/网格切换
- **网格缩略图**:图片与视频自动显示缩略图(ffmpeg 抽帧,失败回退图标)
- **文件操作**:新建文件夹、上传(5MB 分块+断点续传)、下载、改名、删除
- **多选与移动/复制粘贴**:「选择」多选后,可「移动到…」「复制」「剪切」,到目标文件夹「粘贴」
- **拖拽移动**:把文件/文件夹拖到某个文件夹行或面包屑上即移动到该处
- **上传**:上传队列面板(进度/速度),桌面端支持整文件拖拽到窗口上传
- **播放**:视频/音频/图片点开即灯箱播放(Esc 关闭),视频支持拖进度条
- **隐私保险箱**:端侧隐藏目录 + 密码门禁;设置密码后上传/导入文件,普通浏览看不到,解锁后才可查看/导出
- **深/浅主题**:顶栏一键切换,设置里可选「跟随系统/深色/浅色」
- **系统设置**:版本/端口/数据目录/ffmpeg 状态、索引统计与重建、主题与默认视图、访问口令、保险箱管理

### v0.6.0 新增

- **媒体库**:侧栏「媒体库」,视频/图片/音乐分类浏览(SQLite 索引 + 缩略图墙),可播放/下载/打开所在文件夹
- **全局搜索**:侧栏「搜索」,跨所有存储库按文件名搜索(走索引),结果可预览/打开所在位置
- **设备管理**:侧栏「设备」,列出最近访问过的设备(浏览器/系统识别、IP、访问次数、首次/最近时间),可刷新/清空
- **保险箱增强**:保险箱内视频/音频/图片可**直接播放**(`/api/vault/raw` 支持 Range 拖动);新增「导入文件夹」(整目录批量导入)与文件浏览里多选「存入保险箱」
- **拖拽修复**:把文件/文件夹直接拖到同目录(或任意)文件夹行、面包屑上即移动进去(改为自维护拖拽状态,不再依赖 dataTransfer 类型,并禁用图片原生拖拽)

### v0.7.0 修复

- **媒体库/搜索严格按各存储库所选路径**:索引重建只扫每个库注册的目录(不再出现库路径之外的旧内容);库新增/删除/改路径会自动后台重建索引;搜索/媒体结果再做一次「当前能否解析 + 文件确实存在 + 非保险箱」过滤,杜绝陈旧行
- **保险箱放进存储库内**:保险箱目录默认是第一个可写库下的隐藏子目录 `.linNasVault`(如 `H:\NAS\.linNasVault`);普通浏览、目录选择、索引、raw/thumb/stream 接口都会隐藏并拒绝直接访问,只能通过「隐私保险箱」密码解锁访问;设置里可查看保险箱实际位置
- **保险箱支持多级文件夹**:可在保险箱内新建文件夹、进入子目录(面包屑导航)、上传文件到当前目录、删除/导出
- **导入文件 / 设为私密**:保险箱内「导入文件」可从各存储库多选文件导入当前目录;存储库文件行新增「设为私密」按钮(多选后也有「存入保险箱」),会弹出选择保险箱目标文件夹再导入

### v0.8.0

- **导入保留原目录结构**:从存储库导入到保险箱时,相对该存储库根的子路径会原样保留(导入 `电影/合集/` 会在保险箱里得到 `电影/合集/`)
- **批量导出整个文件夹**:保险箱里的文件夹可直接「导出整个文件夹到存储库…」,连同子目录一起移回
- **视频播放增强**:
  - 视频统一走 `/api/stream`:原生格式仍支持 Range 拖动,AVI/RMVB 等老格式自动 ffmpeg 转码后播放
  - 工具条:倍速(0.75×/1×/1.25×/1.5×/2×)、画中画、全屏
  - 键盘:空格播放/暂停、←/→ 快退快进 5 秒、M 静音、Esc 关闭
  - **断点续播**:自动记住每个文件的播放位置,再次打开从上次位置继续

### v0.9.0

- **开机自启**:`server\安装开机自启.bat` 会在 Windows 启动文件夹写一个 `linNas-autostart.vbs`,登录后静默运行 `start-linnas.bat`(最小化启动服务、不弹浏览器);`server\卸载开机自启.bat` 取消。已实测:安装后停掉服务,重登/执行 vbs 服务自动拉起。
- **存储库排除子目录**:添加/编辑存储库时可填「排除的子目录(每行一个)」,这些子目录在浏览、搜索索引、缩略图/raw 访问中都会被隐藏并拒绝访问(如 `BaiduNetdiskDownload`、`UCDownloads`)。改动后自动重建索引。
- **回收站**:删除文件/文件夹不再直接抹掉,而是移动到各存储库隐藏目录 `.linNasTrash`(保留原位置元数据);侧栏「回收站」可**恢复**到原位置、**彻底删除**或**清空回收站**。跨盘删除走复制后删除。
- **上传队列增强**:多文件排队、最多 3 个并发上传;每个任务可**暂停/继续**(断点续传,已传字节不重传)、**取消**(服务端删除 `.part` 残留)、失败**自动重试**(最多 3 次,指数退避),超过次数后可手动重试/移除;面板显示实时速度与进度。
- **未完成上传隐藏**:目录列表与索引不显示 `.part` 临时文件,避免半成品污染。
- 新增接口:`POST /api/upload/abort`、`GET /api/trash`、`POST /api/trash/restore|delete|empty`。

### v1.1.0 — 手机连接不用再手输 IP

- **mDNS 广播**:启动即广播 `_linnas._tcp.`(A/PTR/SRV/TXT 记录),支持组播 `224.0.0.251:5353` 与各网卡子网广播。
- **局域网发现协议(备用,Windows 更可靠)**:UDP `52701`。服务端每 3 秒向 `255.255.255.255` 与各网卡广播地址发一遍配对信息;手机发 `LINNAS_DISCOVER` 探测时,服务端用「同网段优先」选一个可达 IP 单播回 JSON。
- **配对页**:浏览器打开 `http://<电脑IP>:52700/pair` 显示大二维码 + 三个可达地址(推荐地址带标签),手机 App 扫一扫即可连上,无需手输。
- **配对 URI**:`linnas://pair?ip=<ip>&port=<port>&name=linNas[&token=<token>]`;设置了访问口令时 `token` 会带进去,App 扫码后自动带上鉴权。
- 新增接口:`GET /api/pair`(免鉴权)、`GET /api/pair/qr?size=128..1024&ip=`(返回 PNG 二维码)、`GET /pair`(配对页)。
- 启动日志会打印「mDNS 广播已启动」「局域网发现: UDP 应答已启动 (端口 52701)」以及「手机配对(二维码页): http://<主IP>:52700/pair」。
- 说明:Windows 上 `5353` 常被系统 DNS Client 占用,mDNS 只能单向广播、收不到查询包;因此手机端自动发现叠加了上面的 UDP 52701 协议与网段扫描,三者并行。

## 目录结构

```
server/
  main.go          启动、监听、本地 IP 打印、自动打开浏览器
  config.go        配置结构、存储库定义
  config_store.go  config.json 读写
  store.go         虚拟路径解析(防穿越)、目录列表
  api.go           路由、鉴权、文件 API(mkdir/rename/delete/raw)
  raw 流式播放     handleRaw: http.ServeContent → Range/206(拖进度条核心)
  upload.go        分块上传(5MB/块、断点续传、自动去重后缀)
  disks.go         磁盘剩余空间(GetDiskFreeSpaceExW,零依赖)
  media.go         缩略图(/api/thumb)+ ffmpeg 按需转码流(/api/stream)
  index.go         SQLite 文件/媒体索引(搜索、媒体库;modernc 纯 Go 驱动)
  api_index.go     搜索/媒体库/索引状态/重建 路由
  mdns.go          mDNS 广播(_linnas._tcp.,组播 + 子网广播)
  pair.go          手机配对:/api/pair、/api/pair/qr(PNG 二维码)、/pair 配对页、UDP 52701 局域网发现
  ui/dist/         内嵌 Web UI(//go:embed)
  启动linNas.bat / 停止linNas.bat
```

## 构建

```powershell
cd server
go build -o linNas-server.exe .
```

UI 是纯静态文件(`ui/dist/`),改完需重新 `go build` 才会嵌进 exe。

## API 一览(均在 /api 下,`?token=` 或 `X-Token` 头鉴权)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/health | 健康检查(免鉴权) |
| GET | /api/pair | 手机配对信息 JSON(name/version/port/ips/urls/uri),免鉴权 |
| GET | /api/pair/qr?size=320&ip= | 配对 URI 的二维码 PNG(128~1024,默认 320),免鉴权 |
| GET | /pair | 手机配对网页(二维码 + 可达地址),免鉴权 |
| GET/POST/DELETE | /api/libs | 存储库列表/添加/移除 |
| GET | /api/disks | 各库磁盘空间 |
| GET | /api/ls?vpath=/lib1/dir | 目录列表 |
| GET | /api/raw?vpath=/lib1/f.mp4 | 文件流(Range→206;`&dl=1` 下载) |
| GET | /api/stream?vpath=/lib1/f.avi | 播放流:native 格式转发 raw;AVI/RMVB 等用 ffmpeg 边转边发(frag mp4);`&t=秒` 从指定位置开始转码 |
| GET | /api/thumb?vpath=/lib1/f.mp4&w=320 | 图片/视频缩略图(JPEG,ffmpeg 抽帧,落盘缓存) |
| GET | /api/search?q=关键词&lib=&limit=100 | 文件名搜索(走索引,全库) |
| GET | /api/media?type=video&lib=&sort=name\|mtime\|size&offset=0&limit=100 | 媒体库列表(视频/图片/音乐) |
| GET | /api/index/status | 索引扫描状态 + 各类型文件数 |
| POST | /api/reindex | 手动触发后台重建索引 |
| POST | /api/mkdir | 新建目录 |
| POST | /api/rename | 重命名 |
| POST | /api/delete | 删除 |
| POST | /api/upload/init | 开始上传,返回 uploadId/已收字节(断点续传) |
| GET | /api/upload/status?id= | 查询进度 |
| POST | /api/upload/chunk?id=&offset= | 追加一块(原始字节流) |
| POST | /api/upload/complete | 完成(.part → 正式文件) |
| GET | /api/fs/dirs?path=E:\ | 目录选择器:列盘符/子目录 |
| POST | /api/libs/update | 修改已有存储库(名称/路径/只读/启用) |
| POST | /api/move | 移动文件/文件夹 `{vpaths:[],dest}` |
| POST | /api/copy | 复制文件/文件夹 `{vpaths:[],dest}` |
| GET/POST | /api/settings | 系统信息 / 修改访问口令 |
| GET/POST | /api/vault/* | 隐私保险箱:status/setup/password/unlock/lock/ls/upload/raw/delete/import/export(`?vt=` 会话令牌) |
| GET | /api/devices | 已访问/连接过的设备列表 |
| POST | /api/devices/clear | 清空设备记录 |

虚拟路径规则:`/库名/子路径`,库根即注册目录;所有路径解析防 `..` 穿越。

## 配置

`config.json` 在 exe 旁的 `linnas-data/` 目录(或环境变量 `LINNAS_DATA` 指定):

```json
{
  "Port": 52700,
  "Host": "",
  "Token": "",
  "Libraries": [ { "ID": "lib1", "Name": "电影", "Path": "H:\\", "ReadOnly": false, "Enabled": true } ]
}
```

- Token 非空时启用口令鉴权(UI 首次访问会弹框输入)
- 修改 Port/Token:编辑 config.json 后重启

## 已验证(2026-11-24)

- 编译:`go build` 通过(go vet 干净)
- Range 请求:206 + 正确 Content-Range + 字节级校验一致
- 分块上传:2×5MB 块上传 → SHA256 与源一致;浏览器内 fetch 上传 3MB → 字节级一致
- v0.2.0 UI:主页/浏览/搜索/排序/网格/灯箱播放/一键启动 bat 全部人工验证通过
- v0.3.0 缩略图:`/api/thumb` 视频抽帧(320px JPEG 7.9KB)、图片缩放(8.8KB)实测 200 ✅
- v0.3.0 转码:`/api/stream?force=1` 对 183MB mp4 转码出 frag-mp4,首包 `ftyp` 正确 ✅;native 格式直出 200 + Accept-Ranges + Content-Length ✅
- v0.4.0 索引:全量扫描 **244,998 个文件 / 1 分 15 秒**(video 16959、image 6917、audio 174、目录 39897);`/api/search`、`/api/media` 分页实测正常 ✅;索引 `linnas-data/index.db`(WAL),启动时 6 小时内不重扫
- 补充:未注册任何库时启动会**自动注册存在的 `H:\`、`I:\`**
- v0.5.0(本次):目录选择器、编辑存储库、移动/复制/剪切粘贴、拖拽到文件夹、网格缩略图、隐私保险箱(隐藏目录+密码)、深/浅主题、系统设置 全部实测通过(临时库验证复制/移动/拖拽;保险箱上传/下载/列表/删除;缩略图 image+video naturalWidth>0)
- v0.6.0:媒体库(视频/图片/音乐,200 项/页 + 缩略图墙)、全局搜索(/api/search)、设备管理(/api/devices);真实浏览器拖拽 b.txt→文件夹 a 成功移动(Playwright 实测);保险箱内 mp4 直接播放(readyState=4,videoWidth=320);保险箱导入整个文件夹成功
- v0.9.0:开机自启(安装后停服务→wscript 跑 vbs 自动拉起,health 正常)、存储库排除子目录(exclude=["skipme","sub2"] 生效:列表隐藏、/api/ls 与 /api/raw 直访均 403)、回收站(删除→列表含 origPath→恢复回原位置→彻底删除→清空 全通过)、上传队列增强(Fast 3G 下 3 并发上传、暂停变「已暂停」、继续后「完成」、取消「已取消」且 .part 已清理、慢速下第 4 个排队等空位)

## 已知限制

- 上传会话在内存中,服务重启后客户端需重新 init(已传的 .part 字节仍可续传)
- 转码为边转边发,不支持 Range 拖动(可用 `&t=秒` 从头转码);原生格式仍可完整拖动
- 索引为全量重建(暂未做 mtime 增量);目录浏览仍实时读盘,索引只用于搜索/媒体库
- 无 mDNS 发现/扫码配对(App 仍需手输 IP)
